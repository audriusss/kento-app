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
import lt.sturmanas.bajeristas.voice.pipeline.ContinuousMicrophonePipeline
import lt.sturmanas.bajeristas.voice.pipeline.MicrophonePipeline
import lt.sturmanas.bajeristas.voice.pipeline.PipelineConfig
import lt.sturmanas.bajeristas.voice.pipeline.TranscriptionClient
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
    // Transcripts arrive on an IO thread and are dispatched to the main thread
    // via handler.post before entering processPacket().
    private val pipeline: MicrophonePipeline = pipelineFactory { text ->
        handler.post { if (!destroyed) processPacket(text, isFinal = true) }
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

    private val activeNavUtterances = mutableSetOf<String>()

    private val navResumeWatchdogRunnable = Runnable {
        if (phase == Phase.PAUSED_BY_NAVIGATION && activeNavUtterances.isEmpty()) {
            Log.w(TAG, "CONV_NAV_RESUME_WATCHDOG triggered")
            resumeAfterNavigation()
        }
    }

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
        }
    }

    private val strongWakeWords = setOf("kente", "kentai", "kentas", "kenta", "kentu", "kentui", "kentei")
    private val exactWakePhrases = setOf("kente", "kentai", "ei kente", "ei kentai", "klausyk kente", "klausyk kentai")
    private val questionWords = setOf("kur", "kada", "kodel", "kaip", "kas", "ar", "kiek", "koks", "kokia")

    private val muteCommands = setOf("uzsiciaup", "tylek", "baik", "ramiai", "issijunk")
    private val unmuteCommands = setOf("kalbek", "grizk", "bazarinam", "isijunk", "gali kalbeti")

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
        // Defensive guard: pipeline may deliver stale callbacks after mute if a
        // transcription was in-flight at the moment mute was requested.
        if (phase == Phase.MUTED) {
            Log.d(TAG, "CONV_TRANSCRIPT_IGNORED phase=MUTED text='$text'")
            return
        }
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
        // Do not unmute during TTS or navigation audio; let transitionTo() remain the
        // sole authority for pipeline control during those phases.
        if (pipelineActionForPhase(phase) == PipelineAction.MUTE) return
        // Respect explicit user mute — nav updates must not override mode==MUTED.
        if (phase == Phase.MUTED) return
        pipeline.unmute()
        pipeline.start()
    }

    // ─── CONVERSATION LOGIC ───────────────────────────────────────────────

    private fun processPacket(text: String, isFinal: Boolean) {
        if (text.isBlank()) return

        // Hard guard: if the user has explicitly muted the assistant, drop all transcripts
        // (including in-flight callbacks that arrived after mute was applied).
        // Only explicit unmute commands pass through (checked below before this guard fires).
        if (mode == ConversationMode.MUTED) {
            val norm = normalizeText(text)
            if (!unmuteCommands.any { norm.contains(it) }) {
                Log.d(TAG, "CONV_PACKET_IGNORED mode=MUTED text='$text'")
                return
            }
        }

        val norm = normalizeText(text)
        Log.i(TAG, if (isFinal) "CONV_PACKET_FINAL text='$text'" else "CONV_PACKET_PARTIAL text='$text'")

        // 1. Immediate commands (pre-buffer)
        if (muteCommands.any { norm.contains(it) } && (norm.contains("kent") || mode == ConversationMode.ACTIVE)) {
            Log.i(TAG, "CONV_EVENT type=MUTE_COMMAND")
            mode = ConversationMode.MUTED
            transitionTo(Phase.MUTED)
            clearUtteranceBuffer("mute_command")
            speak("Gerai kapitone. Patylėsiu. Tik navigacija liks.")
            return
        }

        if (unmuteCommands.any { norm.contains(it) }) {
            Log.i(TAG, "CONV_EVENT type=UNMUTE_COMMAND")
            mode = ConversationMode.ACTIVE
            transitionTo(Phase.LISTENING)
            clearUtteranceBuffer("unmute_command")
            speak("Grįžau, kapitone.")
            return
        }

        if (norm.contains("stop") || norm.contains("sustok") || norm.contains("gana")) {
            Log.i(TAG, "CONV_EVENT type=STOP_COMMAND")
            clearUtteranceBuffer("stop_command")
            stop()
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
        if (currentText.isBlank()) return

        val norm = normalizeText(currentText)
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
                return
            }
        } else {
            if (mode == ConversationMode.IDLE && isWakeWordOnly(norm)) {
                Log.i(TAG, "CONV_SEMANTIC_COMPLETE result=WAKE_ONLY")
                clearUtteranceBuffer("wake_only")
                mode = ConversationMode.ACTIVE
                speak("Klausau.")
                return
            }
            val isQuestion = tokensContainQuestion(norm) || currentText.endsWith("?")
            if (isQuestion && norm.split(" ").size >= 2) {
                shouldSend = true
                reason = "question_detected"
            }
        }

        if (shouldSend) {
            Log.i(TAG, "CONV_SEMANTIC_COMPLETE result=COMPLETE reason=$reason")
            sendToAi(currentText)
        } else {
            Log.i(TAG, "CONV_SEMANTIC_COMPLETE result=INCOMPLETE_WAITING")
            transitionTo(Phase.COLLECTING)
            resetContinuationTimer()
        }
    }

    private fun sendToAi(text: String) {
        Log.i(TAG, "CONV_SENT_TO_AI text='$text'")
        clearUtteranceBuffer("sent_to_ai")
        transitionTo(Phase.THINKING)
        KentasChat.askKentas(text) { reply ->
            Log.i(TAG, "CONV_EVENT type=AI_RESPONSE_RECEIVED")
            handler.post {
                if (phase == Phase.THINKING) {
                    speak(reply)
                }
            }
        }
    }

    private fun resetContinuationTimer() {
        handler.removeCallbacks(continuationRunnable)
        handler.postDelayed(continuationRunnable, 2500)
        Log.d(TAG, "CONV_CONTINUATION_TIMER_RESET")
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

    private fun tokensContainQuestion(norm: String): Boolean =
        norm.split(Regex("\\s+")).filter { it.isNotBlank() }.any { questionWords.contains(it) }

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
            prePausedPhase = phase
            transitionTo(Phase.PAUSED_BY_NAVIGATION)  // calls pipeline.mute()
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
            handler.postDelayed(navResumeRunnable, 100)
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

    fun stop() {
        Log.i(TAG, "CONV_EVENT type=STOP_REQUESTED")
        stopAiSpeech()
        onTtsTerminal("CANCELLED_BY_USER")
    }

    private fun stopAiSpeech() {
        tts?.stop()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        aiTtsTerminalHandled = true
    }

    fun speak(text: String) {
        if (!isTtsReady || destroyed) return

        // Mute mic and reset VAD before TTS begins.
        // transitionTo(SPEAKING) below calls pipeline.mute(); resetVadAndSegmenter()
        // clears any in-flight utterance data so echo cannot contaminate the next listen.
        transitionTo(Phase.SPEAKING)
        pipeline.resetVadAndSegmenter()

        aiTtsTerminalHandled = false
        currentAiUtteranceId = UUID.randomUUID().toString().substring(0, 8)
        sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        currentIndex = 0
        isInterrupted = false

        Log.i(TAG, "AI_TTS_REQUESTED utteranceId=$currentAiUtteranceId textLength=${text.length}")
        armWatchdog(text.length)
        playNextAiSentence()
    }

    private fun playNextAiSentence() {
        val id = currentAiUtteranceId ?: return
        if (currentIndex < sentences.size && !isInterrupted) {
            val s = sentences[currentIndex]
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

        watchdogHandler.removeCallbacks(watchdogRunnable)
        audioManager.abandonAudioFocusRequest(focusRequest)

        // Cancel any pending cooldown, then schedule a fresh one.
        handler.removeCallbacks(postTtsCooldownRunnable)

        if (reason != "CANCELLED_BY_NAVIGATION") {
            // Wait for speaker echo to decay before unmuting the mic.
            handler.postDelayed(postTtsCooldownRunnable, PipelineConfig.POST_TTS_COOLDOWN_MS)
        }
        // CANCELLED_BY_NAVIGATION: the nav path re-evaluates phase independently.
    }

    fun resetIdleTimer() {
        hasOpenerFired = false
        cancelInactivityTimer()
        inactivityRunnable = Runnable {
            val dist = getNextManeuverDist()
            if (dist > 500 && phase == Phase.IDLE) {
                hasOpenerFired = true
                speak(KentasChat.getOpener())
            } else {
                handler.postDelayed(inactivityRunnable!!, 30000)
            }
        }
        handler.postDelayed(inactivityRunnable!!, 30000)
    }

    private fun startInactivityTimer() {
        cancelInactivityTimer()
        inactivityRunnable = Runnable {
            Log.i(TAG, "AI_ACTIVE_WINDOW_EXPIRED: returning to IDLE")
            mode = ConversationMode.IDLE
            transitionTo(Phase.IDLE)
        }
        handler.postDelayed(inactivityRunnable!!, 60000)
    }

    private fun cancelInactivityTimer() {
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        inactivityRunnable = null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("lt", "LT"))
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
        pipeline.stop()
        watchdogHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        cancelInactivityTimer()
        Log.i(TAG, "AI_CONTROLLER_RELEASED")
    }
}
