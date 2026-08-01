package lt.sturmanas.bajeristas.voice.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.navigation.LocationProvider
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.navigation.PlacesAutocompleteClient
import lt.sturmanas.bajeristas.voice.pipeline.ContinuousMicrophonePipeline
import lt.sturmanas.bajeristas.voice.pipeline.MicrophonePipeline
import lt.sturmanas.bajeristas.voice.pipeline.PipelineConfig
import lt.sturmanas.bajeristas.voice.pipeline.TranscriptionClient
import lt.sturmanas.bajeristas.voice.TtsDefaults
import java.util.Locale
import java.util.UUID

/**
 * Persistent Conversation Engine.
 * Manages logical user utterances and drives the conversation state machine.
 * The single authoritative source of truth for conversation state and text.
 *
 * Microphone input is provided exclusively by [MicrophonePipeline]
 * (AudioRecord → Silero VAD → OpenAI Transcription).  [SpeechRecognizer] is not used.
 *
 * ## Pipeline lifecycle rule
 * [AudioRecord] stays alive as long as the voice system is active.  Phase transitions
 * control [MicrophonePipeline.mute]/[MicrophonePipeline.unmute]; the pipeline is only
 * [MicrophonePipeline.stop]ped in [release].
 *
 * ## Self-recognition protection
 * Before any TTS (AI or navigation) begins, the pipeline is muted and VAD/segmenter
 * state is reset.  After TTS finishes, a [PipelineConfig.POST_TTS_COOLDOWN_MS] cooldown
 * elapses before unmuting so speaker echo does not trigger a false transcript.
 */
class AIConversationController(
    private val context: Context,
    private val getNextManeuverDist: () -> Int,
    private val onStateChanged: (String) -> Unit,
    transcriptionClient: TranscriptionClient,
    /**
     * Factory that produces the [MicrophonePipeline].  The factory receives a
     * transcript-ready callback and returns the pipeline.  The default creates a
     * [ContinuousMicrophonePipeline]; pass a fake in tests.
     */
    pipelineFactory: ((String) -> Unit) -> MicrophonePipeline = { cb ->
        ContinuousMicrophonePipeline(context, transcriptionClient, cb)
    },
) : TextToSpeech.OnInitListener {

    private val TAG = "AIController"

    // ─── Handlers — declared first so pipeline factory closure can capture them ──
    private val handler = Handler(Looper.getMainLooper())
    private val watchdogHandler = Handler(Looper.getMainLooper())

    // ─── Microphone pipeline ──────────────────────────────────────────────
    //
    // All transcripts enter through the single public entry point
    // onTranscriptReceived(), which performs the phase/mode gate checks before
    // calling processPacket().  The handler.post() ensures main-thread delivery.
    private val pipeline: MicrophonePipeline = pipelineFactory { text ->
        handler.post { if (!destroyed) onTranscriptReceived(text) }
    }

    // ─── TTS ─────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isTtsReady = false
    private var sentences = listOf<String>()
    private var currentIndex = 0
    private var isInterrupted = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    // ─── AUTHORITATIVE STATE (Private) ───────────────────────────────────
    private var mode = ConversationMode.IDLE
    private var phase = Phase.IDLE
    private var prePausedPhase: Phase? = null

    private var destroyed = false

    private val continuationRunnable = Runnable { checkBufferCompletion(isTimeout = true) }
    private var inactivityRunnable: Runnable? = null
    private var hasOpenerFired = false

    private var currentAiUtteranceId: String? = null
    private var aiTtsTerminalHandled = false
    private val watchdogRunnable = Runnable { handleTtsWatchdog() }

    // ── Interrupted AI response state ─────────────────────────────────────
    // When a navigation announcement interrupts an in-progress AI response, the
    // remaining sentences are saved here so they can be replayed after navigation
    // finishes.  A generation counter guards against stale resumes: every new
    // speak() call increments currentResponseGeneration, invalidating any saved
    // interrupted state from an earlier answer.
    private var interruptedSentences: List<String> = emptyList()
    private var interruptedFromIndex: Int = 0
    private var currentResponseGeneration: Long = 0L
    private var interruptedResponseGeneration: Long = -1L
    /** True while a nav-interrupted AI response is waiting to resume. */
    private var isNavInterruptResumePending: Boolean = false

    // ── End-to-end latency tracking ───────────────────────────────────────
    /** Epoch ms when the latest user transcript arrived from the pipeline. */
    private var transcriptReceivedAtMs = 0L
    /** Epoch ms when the AI HTTP request was dispatched. */
    private var aiRequestStartedAtMs = 0L

    private val activeNavUtterances = mutableSetOf<String>()

    /**
     * Optional callback set by [MainViewModel] once a [NavigationController] is available.
     * When non-null, voice destination commands are intercepted before reaching [KentasChat]
     * and routed through Places autocomplete instead.
     * Receives a pre-resolved "lat,lng" coordinate string understood by the existing
     * [GoogleNavigationEngine.startNavigation] raw-coordinate branch.
     */
    var onNavigateToDestination: ((String) -> Unit)? = null

    /**
     * Returns true when there is an active navigation route that can be cancelled by voice.
     * Set by [MainViewModel] once the engine state is observable.
     */
    var isNavigationActive: (() -> Boolean)? = null

    /**
     * Supplies the full [NavigationState] snapshot for building a compact nav-context string.
     * Set by [MainViewModel.startObserving] once the navigation controller is ready.
     * When null the prompt builder falls back to distance-only context.
     */
    var getNavState: (() -> NavigationState)? = null

    /**
     * Called when the user cancels the active route by voice.
     * Implementation in [MainViewModel] calls [NavigationController.stopNavigation] and
     * [NavigationVoiceController.stop]; the UI returns to [StartScreen] via the phase
     * observer in [MainActivity].
     */
    var onStopNavigation: (() -> Unit)? = null

    /** Coroutine scope for async Places API calls. Cancelled in [release]. */
    private val voiceNavScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Up to 3 suggestions Kentas just read aloud, awaiting the user's selection.
     * Non-null while in selection/confirmation mode; cleared on confirm or cancel.
     */
    private var pendingVoiceChoices: List<VoiceDestinationChoice>? = null

    /**
     * True when [pendingVoiceChoices] contains exactly one result and Kentas asked
     * "Važiuojam?" — user must say "taip"/"gerai" rather than an ordinal.
     */
    private var pendingVoiceChoicesOneResult: Boolean = false

    private val navResumeWatchdogRunnable = Runnable {
        if (phase == Phase.PAUSED_BY_NAVIGATION && activeNavUtterances.isEmpty()) {
            Log.w(TAG, "CONV_NAV_RESUME_WATCHDOG triggered")
            resumeAfterNavigation()
        }
    }

    // ── Destination-only input mode ───────────────────────────────────────────
    // When non-null, incoming transcripts are buffered until a final-silence
    // grace period elapses, then delivered as a single complete destination
    // phrase.  The pipeline stays unmuted during the grace window so short
    // mid-phrase pauses do not terminate the session prematurely.
    //
    // All access is on the main thread (the pipeline posts via handler.post).
    private var destinationInputCallback: ((String) -> Unit)? = null
    private var destinationInputEndCallback: (() -> Unit)? = null

    /** Accumulated transcript fragments during the current destination session. */
    private var destBufferedText: String = ""

    /**
     * Incremented each time a new transcript fragment arrives in destination
     * mode.  Every grace-period runnable captures its value at scheduling time
     * and no-ops if the counter has advanced — ensuring only the newest
     * complete utterance fires [DESTINATION_UTTERANCE_FINALIZED].
     */
    private var destFragmentGeneration: Int = 0

    /** Pending grace-period callback; kept so it can be cancelled on new speech. */
    private var destGraceRunnable: Runnable? = null

    /** Session-level idle timeout callback. */
    private var destTimeoutRunnable: Runnable? = null

    /** How long (ms) silence must persist after the last fragment before finalising. */
    private val DESTINATION_GRACE_MS = 1_800L

    /** Abandon the session after this many ms with no useful speech. */
    private val DESTINATION_TIMEOUT_MS = 10_000L

    private val navResumeRunnable = Runnable {
        if (activeNavUtterances.isEmpty()) {
            resumeAfterNavigation()
        }
    }

    // Cooldown runnable: fired after AI TTS terminal to reset VAD and unmute.
    // Routes back to Phase.MUTED when mode == MUTED so mute-command confirmation
    // TTS does NOT reopen the mic (see postTtsTargetPhase in ConversationState.kt).
    private val postTtsCooldownRunnable = Runnable {
        if (!destroyed) {
            pipeline.resetVadAndSegmenter()
            transitionTo(postTtsTargetPhase(mode))
            Log.i(TAG, "AI_IDLE_WAITING_FOR_NEW_SPEECH mode=$mode")
            startFollowUpWindow()
        }
    }

    private val strongWakeWords = setOf("kente", "kentai", "kentas", "kenta", "kentu", "kentui", "kentei")
    private val exactWakePhrases = setOf("kente", "kentai", "ei kente", "ei kentai", "klausyk kente", "klausyk kentai")
    // questionWords and semantic helpers live in SemanticCompletionDetector (pure Kotlin, testable).

    private val muteCommands = setOf("uzsiciaup", "tylek", "baik", "ramiai", "issijunk")
    private val unmuteCommands = setOf("kalbek", "grizk", "bazarinam", "isijunk", "gali kalbeti")

    // ── Turn tracking ─────────────────────────────────────────────────────
    /**
     * Monotonic counter: incremented for every transcript that reaches
     * [onTranscriptReceived] regardless of whether it is accepted or dropped.
     * Used to correlate USER_TURN_OPENED / TRANSCRIPT_DROPPED / USER_TURN_CLOSED
     * log lines.
     */
    private var transcriptSeq = 0
    /** Turn ID currently open; set when USER_TURN_OPENED fires. */
    private var activeTurnId = -1

    // ── Follow-up window ─────────────────────────────────────────────────
    /**
     * After Kentas finishes speaking, [ConversationMode] stays ACTIVE for this
     * window so the user can reply without repeating the wake-word.  When the
     * window expires without new accepted speech, mode transitions back to IDLE
     * and a wake-word is required again.
     */
    private val FOLLOW_UP_WINDOW_MS = 8_000L
    private var followUpWindowRunnable: Runnable? = null

    // ─── BUFFER MANAGER (Persistent) ─────────────────────────────────────
    private val utteranceBuffer = StringBuilder()
    private var bufferOwner = "NONE"

    private fun logBufferOp(op: String, data: String = "") {
        val trace = Log.getStackTraceString(Throwable())
        val shortTrace = trace.split("\n")
            .filter { it.contains("lt.sturmanas.bajeristas") && !it.contains("logBufferOp") }
            .take(3).joinToString(" -> ")
        Log.d(TAG, "BUFFER_$op owner=$bufferOwner text='$utteranceBuffer' new='$data' trace=$shortTrace")
    }

    private fun appendToBuffer(text: String) {
        if (text.isBlank()) return
        val base = utteranceBuffer.toString()
        if (base.isBlank()) {
            utteranceBuffer.append(text)
            logBufferOp("UPDATED", text)
            return
        }
        val baseWords = base.split(" ").filter { it.isNotBlank() }
        val newWords  = text.split(" ").filter { it.isNotBlank() }
        var overlapSize = 0
        for (i in 1..baseWords.size.coerceAtMost(newWords.size)) {
            if (baseWords.takeLast(i) == newWords.take(i)) overlapSize = i
        }
        val toAppend = newWords.drop(overlapSize).joinToString(" ")
        if (toAppend.isNotBlank()) {
            utteranceBuffer.append(" ")
            utteranceBuffer.append(toAppend)
            logBufferOp("MERGED", toAppend)
        }
    }

    private fun clearUtteranceBuffer(reason: String) {
        if (utteranceBuffer.isNotEmpty()) {
            logBufferOp("CLEARED", reason)
            utteranceBuffer.setLength(0)
        }
        handler.removeCallbacks(continuationRunnable)
    }

    init {
        Log.i(TAG, "AI_CONSTRUCTOR_ENTER")
        Log.i(TAG, "CONV_ENGINE_INIT locale=${Locale.getDefault()}")
        Log.i(TAG, "BUFFER_CREATED")
        transitionTo(Phase.IDLE)
        Log.i(TAG, "AI_CONSTRUCTOR_EXIT")
    }

    private fun transitionTo(newPhase: Phase) {
        if (phase == newPhase) return
        Log.i(TAG, "CONV_STATE from=$phase to=$newPhase")
        phase = newPhase
        bufferOwner = newPhase.name

        // Sync UI
        val uiText = when (newPhase) {
            Phase.IDLE, Phase.LISTENING, Phase.COLLECTING,
            Phase.WAITING_FOR_CONTINUATION -> "Klausau..."
            Phase.THINKING              -> "Kentas galvoja..."
            Phase.SPEAKING              -> "Kentas kalba..."
            Phase.MUTED                 -> "Muted"
            Phase.PAUSED_BY_NAVIGATION  -> "Navigacija..."
        }
        onStateChanged(uiText)

        // Pipeline control: AudioRecord stays alive; mute/unmute drives phases.
        // Phase.MUTED is intentionally UNMUTED here — the hardware mic keeps recording
        // so the driver can speak an unmute command.  The mode-level guard in
        // processPacket() blocks all non-unmute content when mode==MUTED.
        when (pipelineActionForPhase(newPhase)) {
            PipelineAction.UNMUTE -> {
                pipeline.unmute()
                pipeline.start()   // idempotent
            }
            PipelineAction.MUTE -> {
                pipeline.mute()
            }
        }
    }

    // ─── Public entry point for transcripts ──────────────────────────────

    /**
     * Entry point for transcripts delivered by [MicrophonePipeline].
     *
     * Must only be called on the main thread (the pipeline posts here via handler).
     */
    fun onTranscriptReceived(text: String) {
        transcriptReceivedAtMs = System.currentTimeMillis()

        // ── Destination-only intercept (StartScreen mic) ──────────────────────
        // Must run before the phase gate so it works even when the conversation
        // engine is in Phase.MUTED (its normal idle state on StartScreen).
        //
        // Each transcript fragment restarts a grace-period timer instead of
        // finalising immediately.  This prevents a short first fragment (e.g.
        // "Akropolis") from terminating the session while the user is still
        // speaking the rest of the destination ("Klaipėda").
        if (destinationInputCallback != null) {
            val fragGen = ++destFragmentGeneration

            // Cancel any pending grace timer — new speech has arrived.
            destGraceRunnable?.let {
                handler.removeCallbacks(it)
                Log.i(TAG, "DESTINATION_NEW_SPEECH_CANCELLED_PENDING_FINAL gen=$fragGen")
            }

            // Merge this fragment into the accumulated buffer.
            destBufferedText = mergeDestTexts(destBufferedText, text)
            Log.i(TAG, "DESTINATION_FRAGMENT_BUFFERED gen=$fragGen buffered='$destBufferedText'")

            // Arm the grace timer.  Only the runnable whose fragGen still matches
            // destFragmentGeneration when it fires will call finalizeDestination().
            val graceRunnable = Runnable {
                if (fragGen == destFragmentGeneration && destinationInputCallback != null) {
                    Log.i(TAG, "DESTINATION_UTTERANCE_FINALIZED text='$destBufferedText'")
                    finalizeDestination()
                } else {
                    Log.i(TAG, "DESTINATION_STALE_TRANSCRIPT_IGNORED gen=$fragGen current=$destFragmentGeneration")
                }
            }
            destGraceRunnable = graceRunnable
            Log.i(TAG, "DESTINATION_FINAL_GRACE_STARTED gen=$fragGen graceMs=$DESTINATION_GRACE_MS")
            handler.postDelayed(graceRunnable, DESTINATION_GRACE_MS)
            return
        }

        // ── Turn gate: drop transcripts while a turn is already active ──────
        // THINKING = AI HTTP request in flight; SPEAKING = TTS playing.
        // Both phases have the pipeline hardware-muted via transitionTo(), so
        // any transcript arriving here was captured just before that mute.
        // The mode-level guard inside processPacket() handles ConversationMode.MUTED
        // independently so unmute commands ("Kentai grįžk") can still reach it.
        val tid = ++transcriptSeq
        if (isTurnBlocked(phase)) {
            Log.i(TAG, "TRANSCRIPT_DROPPED reason=turn_already_active utteranceId=$tid state=$phase")
            return
        }

        // User spoke — cancel the follow-up window so it is not left dangling
        // while the new transcript is being processed.
        cancelFollowUpWindow()

        activeTurnId = tid
        Log.i(TAG, "USER_TURN_OPENED utteranceId=$tid")
        processPacket(text, isFinal = true)
    }

    // ─── Public lifecycle ─────────────────────────────────────────────────

    /**
     * Called by the navigation layer when navigation begins, to ensure the mic is
     * active in a listening phase.  This must NOT override an active TTS phase or
     * muted/navigation-paused state — those phases control the pipeline through
     * [transitionTo] and must not be pre-empted by external callers.
     *
     * Guard rule: only proceed when [pipelineActionForPhase] for the current phase is
     * [PipelineAction.UNMUTE] AND the driver has not explicitly muted the assistant.
     */
    fun startListening() {
        if (destroyed) return
        Log.i(TAG, "KENTAS_SESSION_STARTED reason=NAVIGATION_STARTED")
        // Do not unmute during TTS or navigation audio; let transitionTo() remain the
        // sole authority for pipeline control during those phases.
        if (pipelineActionForPhase(phase) == PipelineAction.MUTE) return
        // Respect explicit user mute — nav updates must not override mode==MUTED.
        if (phase == Phase.MUTED) return
        pipeline.unmute()
        pipeline.start()
    }

    /**
     * One-shot destination voice input for StartScreen.
     *
     * Routes the **next** transcript to [onResult] then immediately mutes the pipeline.
     * Does NOT activate the Kentas conversation engine (no wake-word, no AI call).
     * Safe to call while the engine is idle, muted, or in any non-navigation phase.
     *
     * [onEnd] is called when the session ends for any reason (result received or
     * [stopDestinationInput] called), so callers can clear their listening-state flag.
     */
    fun startDestinationInput(onResult: (String) -> Unit, onEnd: () -> Unit) {
        if (destroyed) return
        Log.i(TAG, "STARTSCREEN_VOICE_LISTENING_STARTED")
        // Reset per-session state.
        destBufferedText = ""
        destFragmentGeneration = 0
        destGraceRunnable?.let { handler.removeCallbacks(it) }
        destGraceRunnable = null
        destTimeoutRunnable?.let { handler.removeCallbacks(it) }

        destinationInputCallback = onResult
        destinationInputEndCallback = onEnd

        // Session-level idle timeout: if the user never speaks (or only produces
        // fragments too short to trigger a grace finalization), abandon the session.
        val timeoutRunnable = Runnable {
            if (destinationInputCallback != null) {
                Log.i(TAG, "DESTINATION_SESSION_TIMEOUT buffered='$destBufferedText'")
                if (destBufferedText.isNotBlank()) {
                    // Deliver what we have rather than silently dropping it.
                    Log.i(TAG, "DESTINATION_UTTERANCE_FINALIZED text='$destBufferedText'")
                    finalizeDestination()
                } else {
                    stopDestinationInput()
                }
            }
        }
        destTimeoutRunnable = timeoutRunnable
        handler.postDelayed(timeoutRunnable, DESTINATION_TIMEOUT_MS)

        pipeline.unmute()
        pipeline.start()
    }

    /**
     * Cancels an active [startDestinationInput] session. No-op if none is active.
     * Called when navigation starts or the user taps the mic button a second time.
     */
    fun stopDestinationInput() {
        val endCb = destinationInputEndCallback
        if (destinationInputCallback == null && endCb == null) return
        Log.i(TAG, "STARTSCREEN_VOICE_ENDED reason=stopped")
        destGraceRunnable?.let { handler.removeCallbacks(it) }
        destGraceRunnable = null
        destTimeoutRunnable?.let { handler.removeCallbacks(it) }
        destTimeoutRunnable = null
        destBufferedText = ""
        destinationInputCallback = null
        destinationInputEndCallback = null
        pipeline.mute()
        endCb?.invoke()
    }

    /**
     * Delivers the accumulated destination text and tears down the session.
     * Must only be called on the main thread when [destinationInputCallback] is non-null.
     */
    private fun finalizeDestination() {
        val text = destBufferedText.trim()
        val cb = destinationInputCallback ?: return
        val endCb = destinationInputEndCallback
        Log.i(TAG, "STARTSCREEN_VOICE_RESULT text='$text'")
        destGraceRunnable?.let { handler.removeCallbacks(it) }
        destGraceRunnable = null
        destTimeoutRunnable?.let { handler.removeCallbacks(it) }
        destTimeoutRunnable = null
        destBufferedText = ""
        destinationInputCallback = null
        destinationInputEndCallback = null
        pipeline.mute()
        endCb?.invoke()   // clears isDestinationListening in ViewModel
        cb(text)          // sets destination text in StartScreen
    }

    /**
     * Merges a new transcript fragment into the accumulated destination buffer,
     * deduplicating any overlapping word sequences between the two.
     *
     * This replicates the logic of [appendToBuffer] but operates on plain strings
     * rather than the shared [utteranceBuffer], keeping destination state isolated.
     */
    private fun mergeDestTexts(existing: String, newText: String): String {
        if (existing.isBlank()) return newText.trim()
        val existingWords = existing.split(Regex("\\s+")).filter { it.isNotBlank() }
        val newWords      = newText.split(Regex("\\s+")).filter { it.isNotBlank() }
        var overlapSize   = 0
        for (i in 1..existingWords.size.coerceAtMost(newWords.size)) {
            if (existingWords.takeLast(i) == newWords.take(i)) overlapSize = i
        }
        val toAppend = newWords.drop(overlapSize).joinToString(" ")
        return if (toAppend.isNotBlank()) "${existing.trim()} $toAppend" else existing.trim()
    }

    // ─── CONVERSATION LOGIC ───────────────────────────────────────────────

    private fun processPacket(text: String, isFinal: Boolean) {
        if (text.isBlank()) { closeTurn("empty_transcript"); return }

        // Hard guard: if the user has explicitly muted the assistant, drop all transcripts
        // (including in-flight callbacks that arrived after mute was applied).
        // Only explicit unmute commands pass through (checked below before this guard fires).
        if (mode == ConversationMode.MUTED) {
            val norm = normalizeText(text)
            if (!unmuteCommands.any { norm.contains(it) }) {
                Log.d(TAG, "CONV_PACKET_IGNORED mode=MUTED text='$text'")
                closeTurn("muted_drop")
                return
            }
        }

        val norm = normalizeText(text)
        Log.i(TAG, if (isFinal) "CONV_PACKET_FINAL text='$text'" else "CONV_PACKET_PARTIAL text='$text'")

        // 1. Immediate commands (pre-buffer)
        if (muteCommands.any { norm.contains(it) } && (norm.contains("kent") || mode == ConversationMode.ACTIVE)) {
            Log.i(TAG, "CONV_EVENT type=MUTE_COMMAND")
            mode = ConversationMode.MUTED
            cancelFollowUpWindow()
            transitionTo(Phase.MUTED)
            clearInterruptedResponse("mute_command")
            clearUtteranceBuffer("mute_command")
            speak("Gerai kapitone. Patylėsiu. Tik navigacija liks.")
            closeTurn("mute_command")
            return
        }

        if (unmuteCommands.any { norm.contains(it) }) {
            Log.i(TAG, "CONV_EVENT type=UNMUTE_COMMAND")
            mode = ConversationMode.ACTIVE
            transitionTo(Phase.LISTENING)
            clearUtteranceBuffer("unmute_command")
            speak("Grįžau, kapitone.")
            closeTurn("unmute_command")
            return
        }

        if (norm.contains("stop") || norm.contains("sustok") || norm.contains("gana")) {
            Log.i(TAG, "CONV_EVENT type=STOP_COMMAND")
            clearUtteranceBuffer("stop_command")
            closeTurn("stop_command")
            stop()
            return
        }

        // "Pamiršk pokalbį" — explicit memory wipe command.
        // Intercepted early so it never reaches the AI or nav gate.
        if (norm.contains("pamirsk") && norm.contains("pokalbi")) {
            Log.i(TAG, "CONV_EVENT type=MEMORY_CLEAR_COMMAND")
            KentasChat.clearMemory()
            clearUtteranceBuffer("memory_clear")
            speak("Gerai, pamiršau viską. Pradedame iš naujo.")
            closeTurn("memory_clear")
            return
        }

        // 2. Buffer interaction
        if (isFinal) {
            appendToBuffer(text)
            checkBufferCompletion(isTimeout = false)
        } else {
            resetContinuationTimer()
        }
    }

    private fun checkBufferCompletion(isTimeout: Boolean) {
        val currentText = utteranceBuffer.toString().trim()
        if (currentText.isBlank()) { closeTurn("empty_buffer"); return }

        val norm = normalizeText(currentText)
        val elapsedSinceStt = if (transcriptReceivedAtMs > 0)
            System.currentTimeMillis() - transcriptReceivedAtMs
        else -1L

        Log.d(TAG, "SEMANTIC_WAIT_STARTED isTimeout=$isTimeout elapsedMs=$elapsedSinceStt text='$currentText'")

        var shouldSend = false
        var reason = ""

        if (isTimeout) {
            if (norm.split(" ").size >= 2) {
                shouldSend = true
                reason = "timeout"
            } else {
                if (mode != ConversationMode.ACTIVE) {
                    clearUtteranceBuffer("too_short_on_timeout")
                    transitionTo(Phase.IDLE)
                }
                closeTurn("too_short_on_timeout")
                return
            }
        } else {
            // Wake-word-only: acknowledge and open session without calling AI.
            if (mode == ConversationMode.IDLE && isWakeWordOnly(norm)) {
                Log.i(TAG, "CONV_SEMANTIC_COMPLETE result=WAKE_ONLY")
                clearUtteranceBuffer("wake_only")
                mode = ConversationMode.ACTIVE
                speak("Klausau.")
                closeTurn("wake_word_only")
                return
            }

            val tokens = norm.split(Regex("\\s+")).filter { it.isNotBlank() }

            // Rule 1: question detected (word or punctuation) → immediate send.
            val isQuestion = SemanticCompletionDetector.tokensContainQuestion(norm) ||
                currentText.endsWith("?")
            if (isQuestion && tokens.size >= 2) {
                shouldSend = true
                reason = "question_detected"
            }
            // Rule 2: sentence-end punctuation → immediate send.
            else if (currentText.endsWith(".") || currentText.endsWith("!")) {
                shouldSend = true
                reason = "sentence_end_punct"
            }
            // Rule 3: imperative verb form → immediate send.
            else if (SemanticCompletionDetector.looksLikeImperative(norm)) {
                shouldSend = true
                reason = "imperative_detected"
            }
            // Rule 4: ≥4 tokens in ACTIVE mode and not an incomplete clause fragment.
            else if (tokens.size >= 4 &&
                mode == ConversationMode.ACTIVE &&
                !SemanticCompletionDetector.startsWithIncompleteClause(norm)
            ) {
                shouldSend = true
                reason = "active_long_phrase"
            }
        }

        if (shouldSend) {
            Log.i(TAG, "SEMANTIC_COMPLETE reason=$reason elapsedMs=$elapsedSinceStt text='$currentText'")
            sendToAi(currentText)
        } else {
            Log.i(TAG, "SEMANTIC_INCOMPLETE waiting elapsedMs=$elapsedSinceStt")
            transitionTo(Phase.COLLECTING)
            resetContinuationTimer()
        }
    }

    /**
     * Handles a voice transcript identified as a navigation/destination command.
     *
     * Search path selection (all return [VoiceDestinationChoice]):
     * - Generic category ("vaistinė", "degalinė") + location known → Nearby Search by type
     * - Known chain name ("Maxima", "Lidl") → Text Search with location bias
     * - Everything else (named place, address, partial name) → Autocomplete with location bias
     *
     * Navigation does NOT start here — the user must confirm or select from the list first.
     *
     * Cases:
     * - 0 results → "Neradau tokios vietos."
     * - 1 result  → "Radau X adresu. Važiuojam?" (confirmation required)
     * - 2-3 results → numbered list, "Kurią renkamės?"
     */
    private fun handleVoiceNavigation(text: String, @Suppress("UNUSED_PARAMETER") norm: String) {
        val query     = VoiceDestinationDetector.extractQuery(text)
        val normQuery = normalizeText(query)
        Log.i(TAG, "VOICE_NAV_COMMAND query='$query' normQuery='$normQuery'")
        clearUtteranceBuffer("voice_nav")
        transitionTo(Phase.THINKING)

        voiceNavScope.launch {
            val loc = LocationProvider.locationFlow.value

            val choices: List<VoiceDestinationChoice> = when {
                // Category search: nearby places of a known type (pharmacy, gas_station, …).
                // Requires location — without it falls through to autocomplete.
                loc != null &&
                VoiceDestinationDetector.detectCategoryType(normQuery) != null -> {
                    val type = VoiceDestinationDetector.detectCategoryType(normQuery)!!
                    Log.i(TAG, "VOICE_NAV_CATEGORY type='$type' lat=${loc.latitude} lng=${loc.longitude}")
                    PlacesAutocompleteClient.searchNearbyByType(
                        context    = context,
                        latitude   = loc.latitude,
                        longitude  = loc.longitude,
                        placeType  = type,
                        maxResults = 3,
                    )
                }

                // Chain name search: text search for a known brand near the user.
                VoiceDestinationDetector.normalizeChainName(normQuery) != null -> {
                    val chainName = VoiceDestinationDetector.normalizeChainName(normQuery)!!
                    Log.i(TAG, "VOICE_NAV_CHAIN name='$chainName' biased=${loc != null}")
                    PlacesAutocompleteClient.searchByTextNearby(
                        context    = context,
                        textQuery  = chainName,
                        latitude   = loc?.latitude,
                        longitude  = loc?.longitude,
                        maxResults = 3,
                    )
                }

                // Free-text / named place / address: existing autocomplete with location bias.
                else -> {
                    Log.i(TAG, "VOICE_NAV_AUTOCOMPLETE query='$query' biased=${loc != null}")
                    PlacesAutocompleteClient.getSuggestionsAsVoiceChoices(
                        context    = context,
                        query      = query,
                        latitude   = loc?.latitude,
                        longitude  = loc?.longitude,
                        maxResults = 3,
                    )
                }
            }

            Log.i(TAG, "VOICE_NAV_SUGGESTIONS count=${choices.size} biased=${loc != null}")

            when {
                choices.isEmpty() -> {
                    Log.i(TAG, "VOICE_NAV_NO_RESULTS query='$query'")
                    handler.post { if (!destroyed) speak("Neradau tokios vietos.") }
                }

                choices.size == 1 -> {
                    val c = choices[0]
                    val addressPart = if (c.shortAddress.isNotBlank()) " ${c.shortAddress}" else ""
                    val prompt = "Radau ${c.name}$addressPart. Važiuojam?"
                    Log.i(TAG, "VOICE_NAV_ONE_RESULT name='${c.name}'")
                    handler.post {
                        if (!destroyed) {
                            pendingVoiceChoices = choices
                            pendingVoiceChoicesOneResult = true
                            speak(prompt)
                        }
                    }
                }

                else -> {
                    val ordinals  = listOf("pirmas", "antras", "trečias")
                    val countWord = if (choices.size == 2) "du variantus" else "tris variantus"
                    val sb = StringBuilder("Radau $countWord: ")
                    choices.forEachIndexed { idx, c ->
                        sb.append("${ordinals[idx]} ${c.name}")
                        if (c.shortAddress.isNotBlank()) sb.append(" ${c.shortAddress}")
                        if (idx < choices.lastIndex) sb.append(", ")
                    }
                    sb.append(". Kurią renkamės?")
                    val prompt = sb.toString()
                    Log.i(TAG, "VOICE_NAV_MULTI_RESULTS count=${choices.size}")
                    handler.post {
                        if (!destroyed) {
                            pendingVoiceChoices = choices
                            pendingVoiceChoicesOneResult = false
                            speak(prompt)
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves coordinates for [choice] and starts navigation.
     * Called after the user selects from pending choices or confirms the single result.
     * Clears [pendingVoiceChoices] before the async call so stale state cannot leak.
     */
    private fun handleVoiceConfirm(choice: VoiceDestinationChoice) {
        pendingVoiceChoices = null
        pendingVoiceChoicesOneResult = false
        Log.i(TAG, "VOICE_NAV_CONFIRM placeId='${choice.placeId}' name='${choice.name}'")
        transitionTo(Phase.THINKING)

        voiceNavScope.launch {
            val coords = PlacesAutocompleteClient.resolveCoordinates(context, choice.placeId)
            if (coords == null) {
                Log.w(TAG, "VOICE_NAV_COORDS_FAIL placeId='${choice.placeId}'")
                handler.post { if (!destroyed) speak("Neradau tokios vietos.") }
                return@launch
            }
            val destination = "${coords.first},${coords.second}"
            Log.i(TAG, "VOICE_NAV_RESOLVED dest='$destination'")
            handler.post {
                if (!destroyed) {
                    speak("Gerai, pradedu maršrutą.")
                    onNavigateToDestination?.invoke(destination)
                }
            }
        }
    }

    /**
     * Stops the active navigation route by voice command.
     * Clears any pending place choices, delegates engine stop to [onStopNavigation],
     * then speaks a Lithuanian confirmation.
     *
     * NavigationScreen remains visible after this call — [MainActivity] no longer
     * exits to StartScreen on [NavigationPhase.IDLE]; only the manual "Baigti
     * navigaciją" button does that. The user can enter a new destination via the
     * floating search or a new voice command while the map is still on screen.
     */
    private fun handleRouteCancellation() {
        Log.i(TAG, "VOICE_NAV_ROUTE_CANCEL_RECEIVED")
        Log.i(TAG, "VOICE_ROUTE_CANCEL_KEEP_MAP")
        // Also clear any pending place selection that may have been left open.
        pendingVoiceChoices = null
        pendingVoiceChoicesOneResult = false
        clearInterruptedResponse("route_cancelled")
        clearUtteranceBuffer("voice_route_cancel")
        onStopNavigation?.invoke()
        Log.i(TAG, "VOICE_NAV_ROUTE_STOPPED")
        handler.post {
            if (!destroyed) speak("Gerai, maršrutą nutraukiau. Kur važiuojam toliau?")
        }
    }

    /**
     * Cancels pending voice destination selection and returns to normal conversation.
     * Must be called before any other gate so cancellation is always honoured.
     */
    private fun handleVoiceCancellation() {
        Log.i(TAG, "VOICE_PLACE_CANCEL_RECEIVED")
        pendingVoiceChoices = null
        pendingVoiceChoicesOneResult = false
        Log.i(TAG, "VOICE_PLACE_CHOICES_CLEARED")
        clearUtteranceBuffer("voice_nav_cancel")
        handler.post { if (!destroyed) speak("Gerai, atšaukiau.") }
    }

    private fun sendToAi(text: String) {
        val norm = normalizeText(text)

        // ── Active route cancellation ─────────────────────────────────────────
        // Checked BEFORE pending choices so a route-cancel phrase overrides any
        // open place-selection dialog.
        // • Explicit phrase (e.g. "nutrauk maršrutą") → cancel regardless of state.
        // • Plain cancel token (e.g. bare "atšauk") → cancel only when a route is
        //   currently active; otherwise fall through to the pending-choices gate.
        val navActive = isNavigationActive?.invoke() == true
        if (VoiceDestinationDetector.isRouteCancelCommand(norm) ||
            (navActive && VoiceDestinationDetector.isCancellationCommand(norm))) {
            if (navActive) {
                closeTurn("route_cancel")
                handleRouteCancellation()
                return
            }
            // Explicit route-cancel phrase but no active route — treat as ordinary
            // cancellation of pending choices (falls through to pending gate below).
        }

        // ── Pending voice destination selection ───────────────────────────────
        // When Kentas has listed nearby suggestions and is waiting for the user's
        // choice, intercept the next transcript here — before nav detection or AI.
        val pending = pendingVoiceChoices
        if (pending != null) {
            clearUtteranceBuffer("voice_selection")
            closeTurn("voice_selection")
            // Cancellation is checked FIRST — before any other gate.
            if (VoiceDestinationDetector.isCancellationCommand(norm)) {
                handleVoiceCancellation()
                return
            }
            when {
                pendingVoiceChoicesOneResult &&
                    VoiceDestinationDetector.isConfirmationCommand(norm) -> {
                    handleVoiceConfirm(pending[0])
                }
                !pendingVoiceChoicesOneResult -> {
                    val idx = VoiceDestinationDetector.extractSelectionIndex(norm)
                        ?: VoiceDestinationDetector.matchesNameIndex(
                            norm,
                            pending.map { normalizeText(it.name) },
                        )
                    if (idx != null && idx < pending.size) {
                        handleVoiceConfirm(pending[idx])
                    } else {
                        // Unrecognised input — re-prompt without clearing choices.
                        handler.post {
                            if (!destroyed) speak("Sakykite pirmas, antras ar trečias. Arba atšaukite.")
                        }
                    }
                }
                else -> {
                    // Single-result mode but not confirmation or cancellation — re-prompt.
                    handler.post {
                        if (!destroyed) speak("Radau ${pending[0].name}. Sakykite 'taip' arba 'atšauk'.")
                    }
                }
            }
            return
        }

        // ── Navigation command intercept ──────────────────────────────────────
        // Guard: only when a navigation callback is wired (engine is ready).
        if (onNavigateToDestination != null && VoiceDestinationDetector.isNavigationCommand(norm)) {
            closeTurn("nav_command")
            handleVoiceNavigation(text, norm)
            return
        }

        aiRequestStartedAtMs = System.currentTimeMillis()
        val elapsedSinceStt = if (transcriptReceivedAtMs > 0)
            aiRequestStartedAtMs - transcriptReceivedAtMs
        else -1L
        closeTurn("sent_to_ai")
        Log.i(TAG, "AI_REQUEST_STARTED elapsedSinceSttMs=$elapsedSinceStt text='$text'")
        clearUtteranceBuffer("sent_to_ai")
        transitionTo(Phase.THINKING)

        // Build a compact navigation context string for the AI prompt.
        // All fields come from NavigationState when available; falls back to
        // distance-only if getNavState is not yet wired.  Not stored in history.
        val navContext: String? = buildNavContext()

        KentasChat.askKentas(text, navContext) { reply ->
            val elapsedFromRequest = System.currentTimeMillis() - aiRequestStartedAtMs
            Log.i(TAG, "AI_RESPONSE_RECEIVED elapsedFromRequestMs=$elapsedFromRequest replyLength=${reply.length}")
            handler.post {
                if (phase == Phase.THINKING) {
                    speak(reply)
                }
            }
        }
    }

    private fun resetContinuationTimer() {
        handler.removeCallbacks(continuationRunnable)
        handler.postDelayed(continuationRunnable, PipelineConfig.CONTINUATION_TIMEOUT_MS)
        Log.d(TAG, "CONV_CONTINUATION_TIMER_RESET")
    }

    /**
     * Builds the compact navigation context string injected as a transient system
     * message before every AI request.
     *
     * Format (space-separated, only non-empty fields included):
     *   nav:on [road] [perskaičiuoja] [→maneuver dist] [ETA:Nmin]
     *
     * Examples:
     *   "nav:on Vilniaus g. →dešinė 350m ETA:12min"
     *   "nav:on perskaičiuoja"
     *   "nav:on Kauno pl. →žiedas 2 1.2km ETA:4min"
     *
     * Worst-case length is ≈ 80 chars, keeping the nav section well within the
     * prompt budget.  Falls back to distance-only when [getNavState] is not wired.
     */
    private fun buildNavContext(): String? {
        val state = getNavState?.invoke() ?: run {
            // getNavState not yet wired — distance-only fallback.
            val dist = getNextManeuverDist()
            return when {
                dist == Int.MAX_VALUE || dist <= 0 -> null
                dist >= 1_000 -> "${"%.1f".format(dist / 1_000.0)}km iki posūkio"
                else          -> "${dist}m iki posūkio"
            }
        }
        if (!state.isNavigating) return null

        val parts = mutableListOf("nav:on")

        if (state.currentRoadName.isNotBlank()) {
            parts += state.currentRoadName.take(20)
        }

        if (state.isRerouting) {
            parts += "perskaičiuoja"
        }

        val dist = state.distanceToNextManeuverMeters
        if (dist != Int.MAX_VALUE && dist > 0 &&
            state.maneuverType != ManeuverType.NONE &&
            state.maneuverType != ManeuverType.UNKNOWN
        ) {
            val distStr = if (dist >= 1_000) "${"%.1f".format(dist / 1_000.0)}km" else "${dist}m"
            val manStr = when (state.maneuverType) {
                ManeuverType.TURN_RIGHT    -> "dešinė"
                ManeuverType.TURN_LEFT     -> "kairė"
                ManeuverType.STRAIGHT      -> "tiesiai"
                ManeuverType.SLIGHT_RIGHT  -> "šv.dešinė"
                ManeuverType.SLIGHT_LEFT   -> "šv.kairė"
                ManeuverType.SHARP_RIGHT   -> "st.dešinė"
                ManeuverType.SHARP_LEFT    -> "st.kairė"
                ManeuverType.UTURN         -> "apsisukimas"
                ManeuverType.ROUNDABOUT    -> "žiedas" + (state.exitNumber?.let { " $it" } ?: "")
                ManeuverType.MOTORWAY_EXIT -> "išvažiavimas"
                ManeuverType.MERGE         -> "įsijungimas"
                ManeuverType.FORK          -> "išsišakojimas"
                else                       -> null
            }
            if (manStr != null) parts += "→$manStr $distStr"
        }

        val etaSec = state.remainingDurationSeconds
        if (etaSec > 60) parts += "ETA:${etaSec / 60}min"

        return parts.joinToString(" ")
    }

    private fun normalizeText(text: String): String =
        text.lowercase()
            .replace("ą", "a").replace("č", "c").replace("ę", "e")
            .replace("ė", "e").replace("į", "i").replace("š", "s")
            .replace("ų", "u").replace("ū", "u").replace("ž", "z")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()

    private fun isWakeWordOnly(text: String): Boolean {
        val norm = normalizeText(text)
        if (exactWakePhrases.contains(norm)) return true
        val tokens = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        return tokens.all { strongWakeWords.contains(it) || it.startsWith("kent") || it == "ei" || it == "klausyk" }
    }

    // Semantic detection delegated to SemanticCompletionDetector (testable without Android).

    // ─── EXTERNAL CONTROL ────────────────────────────────────────────────

    fun onNavigationStarted(utteranceId: String) {
        handler.removeCallbacks(navResumeRunnable)
        if (activeNavUtterances.contains(utteranceId)) {
            Log.d(TAG, "CONV_NAV_START_IGNORED id=$utteranceId (already tracked)")
            return
        }

        activeNavUtterances.add(utteranceId)
        val depth = activeNavUtterances.size
        Log.i(TAG, "CONV_NAV_PAUSE previousPhase=$phase depth=$depth id=$utteranceId")

        if (depth == 1) {
            // Safe mute order for navigation TTS (mirrors AI TTS order):
            // 1. Log intent
            // 2. Save pre-pause phase BEFORE transitionTo() changes it
            // 3. transitionTo → pipeline.mute() + increments generationId
            // 4. resetVadAndSegmenter() — clear Silero/segmenter state
            Log.i(TAG, "MIC_PIPELINE_MUTED reason=NAVIGATION")
            prePausedPhase = phase   // snapshot BEFORE transitionTo changes phase

            // Save interrupted AI response BEFORE transitionTo() and stopAiSpeech()
            // so the sentence list and index are still valid.
            // Must use the pre-pause snapshot, NOT the post-transition phase.
            if (prePausedPhase == Phase.SPEAKING && sentences.isNotEmpty()) {
                interruptedSentences = sentences
                interruptedFromIndex = currentIndex
                interruptedResponseGeneration = currentResponseGeneration
                isNavInterruptResumePending = true
                isInterrupted = true   // stops playNextAiSentence() if onDone fires after this
                Log.i(TAG, "AI_INTERRUPTED_BY_NAV fromIdx=$currentIndex remaining=${sentences.size - currentIndex} gen=$currentResponseGeneration")
            }

            transitionTo(Phase.PAUSED_BY_NAVIGATION)  // calls pipeline.mute()
            // Reset VAD state at navigation start so any audio captured just
            // before the mute cannot bleed into post-navigation listening.
            pipeline.resetVadAndSegmenter()
            clearUtteranceBuffer("nav_interrupt")
            stopAiSpeech()
        }

        handler.removeCallbacks(navResumeWatchdogRunnable)
    }

    fun onNavigationFinished(utteranceId: String) {
        if (!activeNavUtterances.contains(utteranceId)) {
            Log.d(TAG, "CONV_NAV_FINISH_IGNORED id=$utteranceId (not tracked)")
            return
        }

        activeNavUtterances.remove(utteranceId)
        val remainingDepth = activeNavUtterances.size
        Log.i(TAG, "CONV_NAV_FINISH remainingDepth=$remainingDepth id=$utteranceId")

        if (remainingDepth == 0) {
            handler.postDelayed(navResumeWatchdogRunnable, 2000)
            handler.removeCallbacks(navResumeRunnable)
            // Use the same post-TTS cooldown as AI TTS so speaker echo from
            // navigation announcements decays before VAD is re-armed.
            handler.postDelayed(navResumeRunnable, PipelineConfig.POST_TTS_COOLDOWN_MS)
        }
    }

    private fun resumeAfterNavigation() {
        if (phase != Phase.PAUSED_BY_NAVIGATION) {
            Log.d(TAG, "CONV_LISTEN_RESUME_SKIPPED reason=not_paused")
            return
        }
        if (activeNavUtterances.isNotEmpty()) {
            Log.d(TAG, "CONV_LISTEN_RESUME_SKIPPED reason=queue_not_empty")
            return
        }

        Log.i(TAG, "CONV_LISTEN_RESUME_REQUESTED cooldown=${PipelineConfig.POST_TTS_COOLDOWN_MS}ms")
        handler.removeCallbacks(navResumeWatchdogRunnable)
        handler.removeCallbacks(navResumeRunnable)

        Log.i(TAG, "NAV_SPEECH_FINISHED")

        if (isNavInterruptResumePending) {
            // An AI response was interrupted — resume it instead of opening the mic.
            Log.i(TAG, "AI_RESUME_AFTER_NAV fromIdx=$interruptedFromIndex remaining=${interruptedSentences.size - interruptedFromIndex}")
            resumeInterruptedAiResponse()
            return
        }

        val resumePhase = when (prePausedPhase) {
            Phase.SPEAKING, Phase.THINKING -> Phase.LISTENING
            else -> prePausedPhase ?: Phase.IDLE
        }

        // Reset VAD state so nav TTS echo cannot trigger a false transcript.
        pipeline.resetVadAndSegmenter()
        // transitionTo() calls pipeline.unmute() for listening phases.
        transitionTo(resumePhase)
        Log.i(TAG, "STT_SESSION_READY_AFTER_NAV")
    }

    /**
     * Checks that the saved interrupted response is still valid (same generation,
     * sentences remain) and calls [resumeAiSpeechFrom] to replay from the saved index.
     * Falls back to the normal listen-resume path if the response was superseded or
     * cancelled while the nav utterance was in flight.
     */
    private fun resumeInterruptedAiResponse() {
        if (!isNavInterruptResumePending) {
            Log.d(TAG, "AI_RESUME_CANCELLED reason=not_pending")
            pipeline.resetVadAndSegmenter()
            transitionTo(postTtsTargetPhase(mode))
            return
        }
        if (interruptedResponseGeneration != currentResponseGeneration) {
            Log.i(TAG, "AI_RESUME_CANCELLED reason=stale_generation saved=$interruptedResponseGeneration current=$currentResponseGeneration")
            isNavInterruptResumePending = false
            pipeline.resetVadAndSegmenter()
            transitionTo(postTtsTargetPhase(mode))
            return
        }
        val resumeSentences = interruptedSentences
        val fromIndex = interruptedFromIndex
        if (resumeSentences.isEmpty() || fromIndex >= resumeSentences.size) {
            Log.i(TAG, "AI_RESUME_CANCELLED reason=no_remaining_sentences")
            isNavInterruptResumePending = false
            pipeline.resetVadAndSegmenter()
            transitionTo(postTtsTargetPhase(mode))
            return
        }
        isNavInterruptResumePending = false
        Log.i(TAG, "AI_RESUME_AFTER_NAV fromIdx=$fromIndex sentences=${resumeSentences.size}")
        resumeAiSpeechFrom(resumeSentences, fromIndex)
    }

    /**
     * Restarts chunked TTS playback from [fromIndex] within [resumeSentences].
     * Mirrors the tail of [speak] without touching generation counters or interrupted state.
     * Audio focus, mic muting, watchdog, and cooldown all follow the same path as a
     * normal [speak] call.
     */
    private fun resumeAiSpeechFrom(resumeSentences: List<String>, fromIndex: Int) {
        if (!isTtsReady || destroyed) return
        Log.i(TAG, "MIC_PIPELINE_MUTED reason=AI_TTS_RESUME")
        transitionTo(Phase.SPEAKING)
        pipeline.resetVadAndSegmenter()
        aiTtsTerminalHandled = false
        sentences = resumeSentences
        currentIndex = fromIndex
        isInterrupted = false
        val remainingText = resumeSentences.drop(fromIndex).joinToString(" ")
        armWatchdog(remainingText.length)
        playNextAiSentence()
    }

    /**
     * Discards any saved interrupted-response state.
     * Must be called whenever a new [speak] replaces the old answer, the user stops
     * Kentas, the route is cancelled, or the controller is released.
     */
    private fun clearInterruptedResponse(reason: String) {
        if (isNavInterruptResumePending) {
            Log.i(TAG, "AI_RESUME_CANCELLED reason=$reason")
        }
        isNavInterruptResumePending = false
        interruptedSentences = emptyList()
        interruptedFromIndex = 0
        interruptedResponseGeneration = -1L
    }

    fun stop() {
        Log.i(TAG, "CONV_EVENT type=STOP_REQUESTED")
        clearInterruptedResponse("user_stop")
        cancelFollowUpWindow()
        // Cancel the idle/commentary timer so it cannot fire after navigation exits.
        cancelInactivityTimer()
        stopAiSpeech()
        onTtsTerminal("CANCELLED_BY_USER")
    }

    /**
     * Fully stops the [MicrophonePipeline] at end-of-navigation.
     *
     * Distinct from [stop], which only cuts TTS and cancels timers and is also
     * called during mid-route nav-announcement pauses.  This method is called
     * **only** from the full-navigation-cleanup path (manual stop and ARRIVED),
     * ensuring no VAD or STT activity continues on StartScreen.
     *
     * [pipeline.stop] increments [generationId] internally, so any in-flight
     * STT uploads whose captured generation is now stale will be discarded by
     * the existing guard in [transcribeAndDeliver].
     *
     * The pipeline can be restarted by [startListening] when the user presses
     * the StartScreen microphone button.
     */
    fun stopNavigationMicPipeline() {
        Log.i(TAG, "KENTAS_SESSION_STOP_REQUESTED reason=NAVIGATION_EXIT")
        
        // Cancel all pending callbacks that could reactivate the mic or process stale transcripts.
        handler.removeCallbacks(postTtsCooldownRunnable)
        handler.removeCallbacks(navResumeRunnable)
        handler.removeCallbacks(navResumeWatchdogRunnable)
        handler.removeCallbacks(continuationRunnable)
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        watchdogHandler.removeCallbacks(watchdogRunnable)
        
        destGraceRunnable?.let { handler.removeCallbacks(it) }
        destTimeoutRunnable?.let { handler.removeCallbacks(it) }

        Log.i(TAG, "MIC_PIPELINE_NAV_SESSION_STOPPED")
        pipeline.stop()
        Log.i(TAG, "KENTAS_SESSION_STOPPED reason=NAVIGATION_EXIT")
    }

    private fun stopAiSpeech() {
        tts?.stop()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        aiTtsTerminalHandled = true
    }

    fun speak(text: String) {
        if (!isTtsReady || destroyed) return

        // Safe mute order for AI TTS:
        // 1. Log intent (reason visible in Logcat alongside pipeline's generation log)
        // 2. transitionTo(SPEAKING) → pipeline.mute() + increments generationId
        // 3. resetVadAndSegmenter() — clears Silero LSTM and segmenter state
        // 4. Start TTS playback
        Log.i(TAG, "MIC_PIPELINE_MUTED reason=AI_TTS")
        transitionTo(Phase.SPEAKING)
        pipeline.resetVadAndSegmenter()

        // Discard any previously interrupted response — this answer supersedes it.
        clearInterruptedResponse("new_speak")
        currentResponseGeneration++

        aiTtsTerminalHandled = false
        currentAiUtteranceId = UUID.randomUUID().toString().substring(0, 8)
        sentences = TtsDefaults.splitForSpeaking(text)
        currentIndex = 0
        isInterrupted = false

        val elapsedSinceStt = if (transcriptReceivedAtMs > 0)
            System.currentTimeMillis() - transcriptReceivedAtMs
        else -1L
        Log.i(TAG, "AI_TTS_REQUESTED utteranceId=$currentAiUtteranceId textLength=${text.length} elapsedSinceSttMs=$elapsedSinceStt")
        armWatchdog(text.length)
        playNextAiSentence()
    }

    private fun playNextAiSentence() {
        val id = currentAiUtteranceId ?: return
        if (currentIndex < sentences.size && !isInterrupted) {
            val s = sentences[currentIndex]
            Log.i(TAG, "AI_RESPONSE_CHUNK_STARTED idx=$currentIndex total=${sentences.size}")
            val result = tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS) {
                onTtsTerminal("START_FAILED")
            }
        }
    }

    private fun armWatchdog(length: Int) {
        val timeoutMs = (length * 150L).coerceIn(10000L, 30000L)
        Log.i(TAG, "AI_TTS_WATCHDOG_STARTED timeoutMs=$timeoutMs")
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, timeoutMs)
    }

    private fun handleTtsWatchdog() {
        Log.w(TAG, "AI_TTS_WATCHDOG_TIMEOUT")
        onTtsTerminal("TIMED_OUT")
    }

    private fun onTtsTerminal(reason: String) {
        if (aiTtsTerminalHandled && reason != "CANCELLED_BY_NAVIGATION") return
        aiTtsTerminalHandled = true
        Log.i(TAG, "AI_TTS_TERMINAL reason=$reason")
        if (reason == "COMPLETED") Log.i(TAG, "AI_RESPONSE_COMPLETED")

        watchdogHandler.removeCallbacks(watchdogRunnable)
        audioManager.abandonAudioFocusRequest(focusRequest)

        // Cancel any pending cooldown, then schedule a fresh one.
        handler.removeCallbacks(postTtsCooldownRunnable)

        if (reason != "CANCELLED_BY_NAVIGATION") {
            // Wait for speaker echo to decay before unmuting the mic.
            Log.i(TAG, "MIC_PIPELINE_UNMUTE_SCHEDULED cooldownMs=${PipelineConfig.POST_TTS_COOLDOWN_MS}")
            handler.postDelayed(postTtsCooldownRunnable, PipelineConfig.POST_TTS_COOLDOWN_MS)
        }
        // CANCELLED_BY_NAVIGATION: the nav path re-evaluates phase independently.
    }

    fun resetIdleTimer() {
        hasOpenerFired = false
        cancelInactivityTimer()
        Log.i(TAG, "ROAD_COMMENTARY_SCHEDULED delayMs=30000")
        inactivityRunnable = Runnable {
            val dist = getNextManeuverDist()
            when {
                phase != Phase.IDLE -> {
                    // Not idle yet (TTS/thinking/nav) — retry later without speaking.
                    Log.i(TAG, "ROAD_COMMENTARY_SKIPPED reason=phase_not_idle phase=$phase")
                    handler.postDelayed(inactivityRunnable!!, 30000)
                }
                dist != Int.MAX_VALUE && dist > 500 -> {
                    // Navigating with a clear run ahead — road commentary.
                    // Only speak if the user has an active conversation window;
                    // do not push unsolicited AI speech in IDLE mode.
                    if (mode != ConversationMode.ACTIVE) {
                        Log.i(TAG, "ROAD_COMMENTARY_SKIPPED reason=not_active mode=$mode")
                        handler.postDelayed(inactivityRunnable!!, 30000)
                    } else {
                        hasOpenerFired = true
                        val comment = KentasChat.getNavComment(dist)
                        Log.i(TAG, "ROAD_COMMENTARY_SCHEDULED speaking distM=$dist comment='$comment'")
                        speak(comment)
                    }
                }
                dist == Int.MAX_VALUE -> {
                    // Not navigating — conversation opener.
                    // Only offer an opener when the user is already engaged.
                    if (mode != ConversationMode.ACTIVE) {
                        Log.i(TAG, "ROAD_COMMENTARY_SKIPPED reason=not_active mode=$mode")
                        handler.postDelayed(inactivityRunnable!!, 30000)
                    } else {
                        hasOpenerFired = true
                        speak(KentasChat.getOpener())
                    }
                }
                else -> {
                    // Close to a maneuver (dist ≤ 500 m) — stay quiet, retry later.
                    Log.i(TAG, "ROAD_COMMENTARY_SKIPPED reason=near_maneuver distM=$dist")
                    handler.postDelayed(inactivityRunnable!!, 30000)
                }
            }
        }
        handler.postDelayed(inactivityRunnable!!, 30000)
    }

    private fun cancelInactivityTimer() {
        if (inactivityRunnable != null) {
            Log.i(TAG, "ROAD_COMMENTARY_CANCELLED")
            inactivityRunnable?.let { handler.removeCallbacks(it) }
            inactivityRunnable = null
        }
    }

    /**
     * Returns true if [normalizedText] contains any of the recognised wake-word
     * variants for "Kentas".  Used to gate speech when [ConversationMode] is [IDLE].
     */
    private fun containsWakeWord(normalizedText: String): Boolean =
        strongWakeWords.any { normalizedText.contains(it) } ||
        normalizedText.contains("kent")

    /**
     * Opens a follow-up window after Kentas finishes speaking.
     *
     * While the window is open the user may reply without repeating the wake-word.
     * If no speech arrives within [FOLLOW_UP_WINDOW_MS], [ConversationMode] transitions
     * back to [ConversationMode.IDLE] and the next utterance must contain a wake-word.
     *
     * No-op when mode is not [ConversationMode.ACTIVE].
     */
    private fun startFollowUpWindow() {
        cancelFollowUpWindow()
        if (mode != ConversationMode.ACTIVE) return
        val run = Runnable {
            if (mode == ConversationMode.ACTIVE &&
                phase != Phase.THINKING &&
                phase != Phase.SPEAKING &&
                phase != Phase.PAUSED_BY_NAVIGATION
            ) {
                Log.i(TAG, "CONV_FOLLOW_UP_WINDOW_EXPIRED mode=ACTIVE->IDLE")
                mode = ConversationMode.IDLE
            }
        }
        followUpWindowRunnable = run
        handler.postDelayed(run, FOLLOW_UP_WINDOW_MS)
    }

    /**
     * Cancel any pending follow-up window runnable.
     * Safe to call when no window is scheduled.
     */
    private fun cancelFollowUpWindow() {
        followUpWindowRunnable?.let { handler.removeCallbacks(it) }
        followUpWindowRunnable = null
    }

    /**
     * Closes the currently open user turn, logging the reason.
     *
     * A turn is opened in [onTranscriptReceived] when [isTurnBlocked] returns false
     * and [USER_TURN_OPENED] is logged.  Every exit path that follows an open turn
     * must call [closeTurn] exactly once so the invariant
     * "USER_TURN_OPENED is always paired with USER_TURN_CLOSED" holds.
     *
     * Safe to call when no turn is open (activeTurnId == -1); in that case it
     * is a no-op so double-close situations (e.g. a command closes the turn then
     * an empty-buffer guard fires) are harmless.
     */
    private fun closeTurn(reason: String) {
        if (activeTurnId < 0) return
        Log.i(TAG, "USER_TURN_CLOSED utteranceId=$activeTurnId reason=$reason")
        activeTurnId = -1
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(TtsDefaults.LOCALE)
            tts?.setPitch(TtsDefaults.PITCH)
            tts?.setSpeechRate(TtsDefaults.SPEECH_RATE)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i(TAG, "AI_TTS_ON_START id=$utteranceId")
                }
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        currentIndex++
                        if (currentIndex < sentences.size && !isInterrupted) {
                            playNextAiSentence()
                        } else {
                            onTtsTerminal("COMPLETED")
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    handler.post { onTtsTerminal("ERROR") }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    handler.post { onTtsTerminal("CANCELLED_BY_USER") }
                }
            })
            isTtsReady = true
        }
    }

    fun release() {
        destroyed = true
        clearInterruptedResponse("released")
        cancelFollowUpWindow()
        voiceNavScope.cancel()
        pipeline.stop()
        watchdogHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        cancelInactivityTimer()
        Log.i(TAG, "AI_CONTROLLER_RELEASED")
    }
}
