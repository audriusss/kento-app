package lt.sturmanas.bajeristas.voice.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.voice.TtsDefaults

/**
 * Manages spoken navigation instructions using Android TextToSpeech.
 *
 * ## Maneuver deduplication
 * Each maneuver is announced at most once per [SpeechStage].  Stage tracking is
 * reset when the maneuver identity changes or a reroute is detected.
 *
 * ## STRAIGHT suppression
 * STRAIGHT maneuvers are not spoken at FAR or MEDIUM stages — they clutter
 * audio and add no value.  STRAIGHT at IMMEDIATE is a short confirmation spoken
 * only once per maneuver identity so the driver knows the current leg is straight.
 *
 * ## Rerouting
 * When [NavigationState.isRerouting] is true, "Perskaičiuoju maršrutą." is spoken
 * once.  Stage tracking is cleared so the new route triggers fresh announcements.
 */
class NavigationVoiceController(private val context: Context) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    interface NavigationSpeechListener {
        fun onNavigationSpeechStarted(utteranceId: String)
        fun onNavigationSpeechFinished(utteranceId: String)
    }

    var listener: NavigationSpeechListener? = null

    /**
     * Called on the main thread after the arrival TTS utterance finishes (onDone/onStop).
     * Used by [MainViewModel] to trigger the full navigation cleanup pipeline so navigation
     * state, nav voice, and AI speech are all stopped before returning to StartScreen.
     */
    var onArrivalSpeechCompleted: (() -> Unit)? = null

    /** Utterance ID of the active arrival announcement; null at all other times. */
    private var arrivalUtteranceId: String? = null

    companion object {
        private const val TAG = "KentasNavVoice"
    }

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isTtsReady = false
    private var pendingState: NavigationState? = null
    private val phraseFormatter = KentasNavigationPhraseFormatter()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val navAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(navAudioAttributes)
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "AUDIO_FOCUS_CHANGED: $focusChange")
        }
        .build()

    init {
        Log.i(TAG, "NAV_VOICE_CONTROLLER_CREATED")
        checkAudioSettings()
    }

    private fun checkAudioSettings() {
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        Log.i(TAG, "AUDIO_STREAM_CHECK: stream=MUSIC volume=$vol/$maxVol muted=$isMuted")
        if (vol < maxVol / 2) {
            Log.i(TAG, "AUDIO_VOLUME_BOOST: increasing volume from $vol to ${maxVol / 2}")
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol / 2, 0)
        }
    }

    // ── Stage / maneuver tracking ──────────────────────────────────────────

    /** Stages already spoken for each maneuver identity (maneuverId → Set<SpeechStage>). */
    private val announcedStages =
        mutableMapOf<String, MutableSet<KentasNavigationPhraseFormatter.SpeechStage>>()

    private var lastManeuverId = ""
    private var lastVariantIndex = 0
    private var latestDistance = Int.MAX_VALUE

    /** True once the rerouting announcement has been spoken for the current reroute cycle. */
    private var hasSpokenRerouting = false

    fun getLatestDistance(): Int = latestDistance

    // ── Main entry point ──────────────────────────────────────────────────

    fun speak(state: NavigationState) {
        latestDistance = state.distanceToNextManeuverMeters
        val maneuver = state.maneuverType
        val dist = state.distanceToNextManeuverMeters
        val phase = state.phase

        Log.d(
            TAG,
            "NAV_VOICE_STATE maneuver=$maneuver dist=$dist " +
            "phase=$phase rerouting=${state.isRerouting} ttsReady=$isTtsReady",
        )

        if (!isTtsReady) {
            pendingState = state
            return
        }

        // ── Handle rerouting ──────────────────────────────────────────────
        if (state.isRerouting) {
            if (!hasSpokenRerouting) {
                Log.i(TAG, "NAV_VOICE_REROUTING")
                hasSpokenRerouting = true
                // Clear all tracking so fresh announcements fire after the new route loads.
                // Rerouting speech ("Maršrutas perskaičiuojamas.") is handled by
                // TrafficEventMonitor so it goes through the correct QUEUE_ADD pipeline.
                announcedStages.clear()
                lastManeuverId = ""
            }
            return
        }
        // Route re-established — allow rerouting phrase again next time.
        if (hasSpokenRerouting) {
            Log.i(TAG, "NAV_VOICE_REROUTE_COMPLETE")
            hasSpokenRerouting = false
        }

        // ── Arrival ───────────────────────────────────────────────────────
        if (phase == NavigationPhase.ARRIVED || maneuver == ManeuverType.ARRIVE) {
            handleArrival(state)
            return
        }

        // ── Guard: must be actively navigating ───────────────────────────
        if (!state.isNavigating || phase != NavigationPhase.NAVIGATING) return

        // ── Guard: skip NONE / UNKNOWN maneuvers ─────────────────────────
        if (maneuver == ManeuverType.NONE || maneuver == ManeuverType.UNKNOWN) return

        // ── Guard: invalid distance ───────────────────────────────────────
        if (dist == Int.MAX_VALUE) return

        // ── First maneuver of the session ─────────────────────────────────
        if (lastManeuverId.isEmpty()) {
            val maneuverId = maneuverKey(state)
            lastManeuverId = maneuverId

            // Skip zero / negative distance and STRAIGHT on first announcement.
            if (dist <= 0) return
            if (maneuver == ManeuverType.STRAIGHT) return

            val firstStage = stageForDistance(dist)
                ?: KentasNavigationPhraseFormatter.SpeechStage.FAR  // default for > 1 000 m

            val phrase = phraseFormatter.format(
                maneuver     = maneuver,
                distanceMeters = dist,
                stage        = firstStage,
                variantIndex = lastVariantIndex++,
                exitNumber   = state.exitNumber,
            )
            if (phrase.isBlank()) return

            Log.i(TAG, "NAV_VOICE_FIRST_MANEUVER stage=$firstStage phrase='$phrase'")
            announcedStages.getOrPut(maneuverId) { mutableSetOf() }.add(firstStage)
            speakText(phrase, firstStage)
            return
        }

        // ── Guard: invalid / zero distance for advance warnings ───────────
        if (dist <= 0) {
            Log.d(TAG, "NAV_VOICE_SKIPPED reason=zero_distance maneuver=$maneuver")
            return
        }

        val stage = stageForDistance(dist) ?: return  // > 1 000 m: no announcement needed

        // ── STRAIGHT suppression ─────────────────────────────────────────
        // Never announce STRAIGHT at FAR or MEDIUM — it adds noise.
        // STRAIGHT at IMMEDIATE is allowed once per maneuver identity.
        if (maneuver == ManeuverType.STRAIGHT &&
            stage != KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE
        ) {
            Log.v(TAG, "NAV_VOICE_SKIPPED reason=straight_not_immediate stage=$stage")
            return
        }

        val maneuverId = maneuverKey(state)

        // ── Maneuver change → reset tracking ─────────────────────────────
        if (maneuverId != lastManeuverId) {
            Log.i(TAG, "NAV_VOICE_MANEUVER_CHANGED old=$lastManeuverId new=$maneuverId")
            lastManeuverId = maneuverId
            announcedStages.clear()
        }

        // ── Deduplication: skip if this stage was already spoken ─────────
        val stages = announcedStages.getOrPut(maneuverId) { mutableSetOf() }
        if (stage in stages) return

        // ── Format and speak ─────────────────────────────────────────────
        val phrase = phraseFormatter.format(
            maneuver     = maneuver,
            distanceMeters = dist,
            stage        = stage,
            variantIndex = lastVariantIndex++,
            exitNumber   = state.exitNumber,
        )
        if (phrase.isBlank()) {
            Log.d(TAG, "NAV_VOICE_SKIPPED reason=blank_phrase maneuver=$maneuver stage=$stage")
            return
        }

        Log.i(TAG, "NAV_VOICE_ANNOUNCING stage=$stage phrase='$phrase' dist=$dist")
        Log.i(TAG, "NAV_PHRASE_SELECTED maneuver=$maneuver stage=$stage phrase='$phrase'")
        stages.add(stage)
        speakText(phrase, stage)
    }

    // ── Arrival ───────────────────────────────────────────────────────────

    private fun handleArrival(state: NavigationState) {
        val stages = announcedStages.getOrPut("ARRIVAL") { mutableSetOf() }
        if (KentasNavigationPhraseFormatter.SpeechStage.ARRIVED in stages) return

        val phrase = phraseFormatter.format(
            maneuver     = state.maneuverType,
            distanceMeters = 0,
            stage        = KentasNavigationPhraseFormatter.SpeechStage.ARRIVED,
            variantIndex = lastVariantIndex++,
        )
        Log.i(TAG, "NAV_VOICE_ARRIVED phrase='$phrase'")
        Log.i(TAG, "ARRIVAL_SPEECH_STARTED phrase='$phrase'")
        stages.add(KentasNavigationPhraseFormatter.SpeechStage.ARRIVED)

        // Use a dedicated ID prefix so onDone can identify the arrival utterance
        // and fire onArrivalSpeechCompleted after the phrase finishes.
        val aId = "nav_arrived_${System.currentTimeMillis()}"
        arrivalUtteranceId = aId
        speakText(phrase, KentasNavigationPhraseFormatter.SpeechStage.ARRIVED, idOverride = aId)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Returns the [SpeechStage] for a given distance, or null when the distance
     * is too large for an advance announcement (> 1 000 m).
     *
     * Returns null for dist <= 0 to prevent "važiuok tiesiai už nulio metrų".
     */
    private fun stageForDistance(dist: Int): KentasNavigationPhraseFormatter.SpeechStage? =
        when {
            dist <= 0   -> null
            dist <= 120 -> KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE
            dist <= 400 -> KentasNavigationPhraseFormatter.SpeechStage.MEDIUM
            dist <= 1_000 -> KentasNavigationPhraseFormatter.SpeechStage.FAR
            else        -> null
        }

    /** Stable key that uniquely identifies the current maneuver leg. */
    private fun maneuverKey(state: NavigationState): String =
        "${state.maneuverType}_${state.nextRoadName}"

    private fun requestFocus(): Boolean {
        val res = audioManager.requestAudioFocus(focusRequest)
        Log.d(TAG, "AUDIO_FOCUS_REQUESTED result=$res")
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun speakText(
        text: String,
        stage: KentasNavigationPhraseFormatter.SpeechStage,
        idOverride: String? = null,
    ) {
        val queueMode = if (stage == KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE)
            TextToSpeech.QUEUE_FLUSH
        else
            TextToSpeech.QUEUE_ADD

        requestFocus()

        val utteranceId = idOverride ?: "nav_${System.currentTimeMillis()}_${(0..999).random()}"
        Log.i(
            TAG,
            "NAV_VOICE_SPEAK id=$utteranceId mode=${if (queueMode == TextToSpeech.QUEUE_FLUSH) "FLUSH" else "ADD"}",
        )

        // Notify engine BEFORE queuing so the mic mute gate is up before audio starts.
        listener?.onNavigationSpeechStarted(utteranceId)

        val normalized = TtsDefaults.normalizeForTts(text)
        val res = tts?.speak(normalized, queueMode, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            Log.e(TAG, "NAV_VOICE_SPEAK_FAILED result=$res id=$utteranceId")
            listener?.onNavigationSpeechFinished(utteranceId)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Speaks a short traffic or situational comment through the same nav-TTS pipeline
     * used for maneuver guidance.
     *
     * Routing through this pipeline (rather than [AIConversationController.speak]) ensures:
     *  - The [listener] hooks fire → [AIConversationController] correctly pauses and
     *    resumes any in-progress AI response via the existing interrupt-resume mechanism.
     *  - The saved interrupted AI response is never discarded by a traffic comment.
     *  - Audio focus and mic-mute lifecycle are handled identically to nav guidance.
     *
     * Uses [TextToSpeech.QUEUE_ADD] so an ongoing maneuver announcement is never
     * interrupted — navigation guidance retains highest priority.
     */
    fun speakTrafficComment(text: String) {
        if (!isTtsReady || text.isBlank()) return
        speakText(text, KentasNavigationPhraseFormatter.SpeechStage.FAR)
    }

    fun stop() {
        Log.i(TAG, "NAV_VOICE_STOPPED")
        tts?.stop()
        announcedStages.clear()
        lastManeuverId = ""
        hasSpokenRerouting = false
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setAudioAttributes(navAudioAttributes)

            val locale = TtsDefaults.LOCALE
            tts?.setPitch(TtsDefaults.PITCH)
            tts?.setSpeechRate(TtsDefaults.SPEECH_RATE)
            val setLangResult = tts?.setLanguage(locale)

            if (setLangResult == TextToSpeech.LANG_MISSING_DATA ||
                setLangResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.e(TAG, "NAV_VOICE_TTS_LANG_UNAVAILABLE locale=lt_LT")
            } else {
                isTtsReady = true
                Log.i(TAG, "NAV_VOICE_TTS_READY")

                pendingState?.let {
                    speak(it)
                    pendingState = null
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(TAG, "NAV_UTTERANCE_STARTED id=$utteranceId")
                        utteranceId?.let { listener?.onNavigationSpeechStarted(it) }
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.i(TAG, "NAV_UTTERANCE_DONE id=$utteranceId")
                        utteranceId?.let { listener?.onNavigationSpeechFinished(it) }
                        // Fire arrival callback after the arrival phrase finishes so
                        // the full cleanup pipeline (stop nav + voice + AI) can run.
                        if (utteranceId != null && utteranceId == arrivalUtteranceId) {
                            arrivalUtteranceId = null
                            mainHandler.post { onArrivalSpeechCompleted?.invoke() }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "NAV_UTTERANCE_ERROR id=$utteranceId")
                        utteranceId?.let { listener?.onNavigationSpeechFinished(it) }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        Log.i(TAG, "NAV_UTTERANCE_STOPPED id=$utteranceId interrupted=$interrupted")
                        utteranceId?.let { listener?.onNavigationSpeechFinished(it) }
                    }
                })
            }
        } else {
            Log.e(TAG, "NAV_VOICE_TTS_INIT_FAILED status=$status")
        }
    }
}
