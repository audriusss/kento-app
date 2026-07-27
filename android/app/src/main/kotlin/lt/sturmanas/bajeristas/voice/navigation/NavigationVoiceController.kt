package lt.sturmanas.bajeristas.voice.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.navigation.NavigationState
import java.util.Locale

/**
 * Manages spoken navigation instructions using Android TextToSpeech.
 */
class NavigationVoiceController(private val context: Context) : TextToSpeech.OnInitListener {

    interface NavigationSpeechListener {
        fun onNavigationSpeechStarted(utteranceId: String)
        fun onNavigationSpeechFinished(utteranceId: String)
    }

    var listener: NavigationSpeechListener? = null

    companion object {
        private const val TAG = "KentasNavVoice"
    }

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isTtsReady = false
    private var pendingState: NavigationState? = null
    private val phraseFormatter = KentasNavigationPhraseFormatter()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Audio attributes for navigation guidance
    private val navAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Audio focus request
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
        
        // Force volume up for testing if it's too low
        if (vol < maxVol / 2) {
            Log.i(TAG, "AUDIO_VOLUME_BOOST: increasing volume from $vol to ${maxVol / 2}")
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol / 2, 0)
        }
    }

    private fun requestFocus(): Boolean {
        val res = audioManager.requestAudioFocus(focusRequest)
        Log.d(TAG, "AUDIO_FOCUS_REQUEST_RESULT: $res")
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // Tracking announced maneuvers: key -> Set of stages already spoken
    private val announcedStages = mutableMapOf<String, MutableSet<KentasNavigationPhraseFormatter.SpeechStage>>()
    private var lastManeuverId = ""
    private var lastVariantIndex = 0
    private var latestDistance = Int.MAX_VALUE

    fun getLatestDistance(): Int = latestDistance

    fun speak(state: NavigationState) {
        latestDistance = state.distanceToNextManeuverMeters
        val maneuver = state.maneuverType
        val dist = state.distanceToNextManeuverMeters
        val phase = state.phase
        
        Log.d(TAG, "NAV_REAL_VOICE_RECEIVED: maneuver=$maneuver dist=$dist phase=$phase ready=$isTtsReady nav=${state.isNavigating}")
        
        if (!isTtsReady) {
            Log.i(TAG, "NAV_VOICE_TTS_NOT_READY: NAV_VOICE_FIRST_MANEUVER_QUEUED")
            pendingState = state
            return
        }
        
        if (!state.isNavigating || phase != NavigationPhase.NAVIGATING) {
            if (phase == NavigationPhase.ARRIVED) {
                handleArrival(state)
            }
            return
        }

        // ── FORCE FIRST SPEECH ───────────────────────────────────────
        if (lastManeuverId.isEmpty()) {
            Log.i(TAG, "NAV_VOICE_FIRST_MANEUVER_FORCE_START")
            val maneuverId = "${maneuver}_${state.nextRoadName}"
            lastManeuverId = maneuverId
            val phrase = phraseFormatter.format(maneuver, dist, KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE, lastVariantIndex++)
            Log.i(TAG, "NAV_VOICE_FORMATTED phrase='$phrase' (forced)")
            speakText(phrase, KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE)
            announcedStages.getOrPut(maneuverId) { mutableSetOf() }.add(KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE)
            return
        }

        if (dist == Int.MAX_VALUE) {
            return
        }

        val stage = determineStage(dist)
        if (stage == null) return

        val maneuverId = "${maneuver}_${state.nextRoadName}"

        // Reset tracking if maneuver changed
        if (maneuverId != lastManeuverId) {
            Log.i(TAG, "NAV_VOICE_ROUTE_RESET previous=$lastManeuverId current=$maneuverId")
            lastManeuverId = maneuverId
            announcedStages.clear()
        }

        val stages = announcedStages.getOrPut(maneuverId) { mutableSetOf() }
        if (stage in stages) {
            return
        }

        Log.i(TAG, "NAV_REAL_STAGE_SELECTED: $stage")
        val phrase = phraseFormatter.format(
            maneuver = maneuver,
            distanceMeters = dist,
            stage = stage,
            variantIndex = lastVariantIndex++,
            exitNumber = state.exitNumber
        )
        Log.i(TAG, "NAV_VOICE_FORMATTED phrase='$phrase'")

        stages.add(stage)
        speakText(phrase, stage)
    }

    private fun handleArrival(state: NavigationState) {
        val stages = announcedStages.getOrPut("ARRIVAL") { mutableSetOf() }
        if (KentasNavigationPhraseFormatter.SpeechStage.ARRIVED in stages) return

        val phrase = phraseFormatter.format(state.maneuverType, 0, KentasNavigationPhraseFormatter.SpeechStage.ARRIVED, lastVariantIndex++)
        Log.i(TAG, "NAV_VOICE_STAGE ARRIVED")
        stages.add(KentasNavigationPhraseFormatter.SpeechStage.ARRIVED)
        speakText(phrase, KentasNavigationPhraseFormatter.SpeechStage.ARRIVED)
    }

    private fun determineStage(dist: Int): KentasNavigationPhraseFormatter.SpeechStage? {
        return when {
            dist <= 120 -> KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE
            dist <= 400 -> KentasNavigationPhraseFormatter.SpeechStage.MEDIUM
            dist <= 1000 -> KentasNavigationPhraseFormatter.SpeechStage.FAR
            else -> null
        }
    }

    private fun speakText(text: String, stage: KentasNavigationPhraseFormatter.SpeechStage) {
        val queueMode = if (stage == KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }

        requestFocus()
        
        val utteranceId = "nav_${System.currentTimeMillis()}_${(0..999).random()}"
        Log.i(TAG, "NAV_VOICE_SPEAK_QUEUED phrase='$text' mode=${if (queueMode == TextToSpeech.QUEUE_FLUSH) "FLUSH" else "ADD"} id=$utteranceId")
        
        // Notify engine BEFORE queuing to prevent flicker
        listener?.onNavigationSpeechStarted(utteranceId)
        
        val res = tts?.speak(text, queueMode, null, utteranceId)
        Log.i(TAG, "NAV_REAL_SPEAK_RESULT return=$res id=$utteranceId")
    }

    fun stop() {
        Log.i(TAG, "NAV_VOICE_STOPPED")
        tts?.stop()
        announcedStages.clear()
        lastManeuverId = ""
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
            
            val locale = Locale("lt", "LT")
            val setLangResult = tts?.setLanguage(locale)

            if (setLangResult == TextToSpeech.LANG_MISSING_DATA || setLangResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "NAV_VOICE_TTS_UNAVAILABLE: Lithuanian not supported")
            } else {
                isTtsReady = true
                
                pendingState?.let {
                    speak(it)
                    pendingState = null
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(TAG, "NAV_REAL_UTTERANCE_START id=$utteranceId")
                        // Idempotent notify (already queued, but keeps state solid)
                        utteranceId?.let { listener?.onNavigationSpeechStarted(it) }
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.i(TAG, "NAV_REAL_UTTERANCE_DONE id=$utteranceId")
                        utteranceId?.let { listener?.onNavigationSpeechFinished(it) }
                    }
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "NAV_REAL_UTTERANCE_ERROR id=$utteranceId")
                        utteranceId?.let { listener?.onNavigationSpeechFinished(it) }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        Log.i(TAG, "NAV_REAL_UTTERANCE_STOP id=$utteranceId interrupted=$interrupted")
                        utteranceId?.let { listener?.onNavigationSpeechFinished(it) }
                    }
                })
            }
        } else {
            Log.e(TAG, "NAV_VOICE_TTS_INIT_FAILED status=$status")
        }
    }
}
