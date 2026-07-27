package lt.sturmanas.bajeristas.voice.ai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Persistent Conversation Engine.
 * Manages logical user utterances across multiple STT sessions.
 * The single authoritative source of truth for conversation state and text.
 */
class AIConversationController(
    private val context: Context,
    private val getNextManeuverDist: () -> Int,
    private val onStateChanged: (String) -> Unit
) : TextToSpeech.OnInitListener, RecognitionListener {

    enum class ConversationMode { IDLE, ACTIVE, MUTED }
    enum class Phase {
        IDLE,
        LISTENING,
        COLLECTING,
        WAITING_FOR_CONTINUATION,
        THINKING,
        SPEAKING,
        PAUSED_BY_NAVIGATION,
        MUTED
    }

    private val TAG = "AIController"
    
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isTtsReady = false
    private var sentences = listOf<String>()
    private var currentIndex = 0
    private var isInterrupted = false

    private var speechRecognizer: SpeechRecognizer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val recognizerIntent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "lt-LT")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        
        // Strict Int extras for patient speech timing
        putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 2500)
        putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 10000)
        putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 10000)
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        .build()

    // ─── AUTHORITATIVE STATE (Private) ───
    private var mode = ConversationMode.IDLE
    private var phase = Phase.IDLE
    private var prePausedPhase: Phase? = null
    private var originalAudioMode: Int? = null
    
    // Internal state management
    @Volatile private var generation = 0L
    private var isListeningSessionActive = false
    private var restartScheduled = false
    private var destroyed = false
    private var navigationSpeaking = false

    private val handler = Handler(Looper.getMainLooper())
    private val continuationRunnable = Runnable { checkBufferCompletion(isTimeout = true) }
    private var inactivityRunnable: Runnable? = null
    private var hasOpenerFired = false

    private var currentAiUtteranceId: String? = null
    private var aiTtsTerminalHandled = false
    private val watchdogHandler = Handler(Looper.getMainLooper())
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

    private val strongWakeWords = setOf("kente", "kentai", "kentas", "kenta", "kentu", "kentui", "kentei")
    private val exactWakePhrases = setOf("kente", "kentai", "ei kente", "ei kentai", "klausyk kente", "klausyk kentai")
    private val questionWords = setOf("kur", "kada", "kodel", "kaip", "kas", "ar", "kiek", "koks", "kokia")
    private val acknowledgements = setOf("jo", "aha", "nu", "gerai", "mhm", "okei")

    private val muteCommands = setOf("uzsiciaup", "tylek", "baik", "ramiai", "issijunk")
    private val unmuteCommands = setOf("kalbek", "grizk", "bazarinam", "isijunk", "gali kalbeti")

    // ─── BUFFER MANAGER (Persistent) ───
    private val utteranceBuffer = StringBuilder()
    private var bufferOwner = "NONE"

    private fun logBufferOp(op: String, data: String = "") {
        val trace = Log.getStackTraceString(Throwable())
        val shortTrace = trace.split("\n").filter { it.contains("lt.sturmanas.bajeristas") && !it.contains("logBufferOp") }.take(3).joinToString(" -> ")
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
        val newWords = text.split(" ").filter { it.isNotBlank() }

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
        prepareRecognizer()
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
            Phase.IDLE, Phase.LISTENING, Phase.COLLECTING, Phase.WAITING_FOR_CONTINUATION -> "Klausau..."
            Phase.THINKING -> "Kentas galvoja..."
            Phase.SPEAKING -> "Kentas kalba..."
            Phase.MUTED -> "Muted"
            Phase.PAUSED_BY_NAVIGATION -> "Navigacija..."
        }
        onStateChanged(uiText)

        // Entry logic
        when (newPhase) {
            Phase.IDLE -> {
                // Buffer is preserved until inactivity triggers or query sent.
                startSttSession()
            }
            Phase.LISTENING, Phase.COLLECTING -> startSttSession()
            Phase.THINKING, Phase.SPEAKING, Phase.PAUSED_BY_NAVIGATION -> stopSttSession()
            else -> {}
        }
    }

    private fun prepareRecognizer() {
        if (destroyed) return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@AIConversationController)
            }
            Log.i(TAG, "STT_RECOGNIZER_RECREATED reason=init_or_recovery")
        } catch (e: Exception) {
            Log.e(TAG, "prepareRecognizer failed", e)
        }
    }

    fun startListening() {
        if (destroyed) return
        if (phase == Phase.MUTED || phase == Phase.PAUSED_BY_NAVIGATION) return
        startSttSession()
    }

    private var firstPartialAt = 0L
    private var sttSessionStartedAt = 0L

    private fun startSttSession() {
        val requestTime = System.currentTimeMillis()
        
        // --- EXPANDED AUDIO ROUTING DIAGNOSTICS ---
        try {
            val modeStr = when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RING"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "COMM"
                else -> "UNKNOWN(${audioManager.mode})"
            }
            
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val inputDetails = inputs.joinToString("\n") { device ->
                val typeName = when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
                    else -> "TYPE_${device.type}"
                }
                "  - [Input] id=${device.id}, type=${device.type}($typeName), product=${device.productName}, channels=${device.channelCounts.contentToString()}, rates=${device.sampleRates.contentToString()}"
            }

            val wiredHeadset = inputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

            val commDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.communicationDevice?.let { "id=${it.id}, type=${it.type}" } ?: "NONE"
            } else "N/A(<S)"

            Log.i(TAG, "AUDIO_INPUT_DIAG: mode=$modeStr wired=$wiredHeadset btSco=${audioManager.isBluetoothScoOn} commDevice=$commDevice")
            Log.i(TAG, "AUDIO_INPUT_DEVICES:\n$inputDetails")
            Log.i(TAG, "AUDIO_SOURCE_CHECK: setCommunicationDevice was NOT found in project grep.")
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO_INPUT_DIAG_FAILED", e)
        }
        // ------------------------------------------

        Log.d(TAG, "STT_START_SESSION_REQUEST phase=$phase mode=$mode sessionActive=$isListeningSessionActive navSpeaking=$navigationSpeaking destroyed=$destroyed time=$requestTime")
        
        if (isListeningSessionActive || restartScheduled || destroyed || navigationSpeaking || phase == Phase.THINKING || phase == Phase.SPEAKING || phase == Phase.PAUSED_BY_NAVIGATION) {
            val reason = when {
                isListeningSessionActive -> "already_active"
                restartScheduled -> "restart_scheduled"
                destroyed -> "destroyed"
                navigationSpeaking -> "nav_speaking"
                phase == Phase.THINKING -> "phase_thinking"
                phase == Phase.SPEAKING -> "phase_speaking"
                phase == Phase.PAUSED_BY_NAVIGATION -> "phase_paused_nav"
                else -> "unknown"
            }
            Log.d(TAG, "STT_START_SESSION_SKIPPED reason=$reason")
            return
        }

        generation++
        isListeningSessionActive = true
        Log.i(TAG, "STT_SESSION_CREATED gen=$generation mode=$mode phase=$phase")
        
        handler.post {
            if (destroyed || !isListeningSessionActive) {
                 Log.d(TAG, "STT_SESSION_START_CANCELLED destroyed=$destroyed active=$isListeningSessionActive")
                 return@post
            }
            try {
                if (speechRecognizer == null) {
                    Log.d(TAG, "STT_SESSION_RECREATING_RECOGNIZER")
                    prepareRecognizer()
                }
                
                if (speechRecognizer != null) {
                    sttSessionStartedAt = System.currentTimeMillis()
                    firstPartialAt = 0L
                    
                    // --- EXPERIMENT: Set Audio Mode ---
                    try {
                        originalAudioMode = audioManager.mode
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        Log.i(TAG, "AUDIO_MODE_CHANGED old=$originalAudioMode new=IN_COMMUNICATION")
                    } catch (e: Exception) {
                        Log.e(TAG, "FAILED_TO_SET_AUDIO_MODE", e)
                    }
                    
                    Log.d(TAG, "STT_SESSION_START_ATTEMPT gen=$generation time=$sttSessionStartedAt")
                    speechRecognizer?.startListening(recognizerIntent)
                } else {
                    isListeningSessionActive = false
                    restoreAudioMode()
                    scheduleSttRestart(2000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startSttSession error", e)
                isListeningSessionActive = false
                restoreAudioMode()
                scheduleSttRestart(2000)
            }
        }
    }

    private fun stopSttSession() {
        isListeningSessionActive = false
        speechRecognizer?.cancel()
        restoreAudioMode()
    }

    private fun restoreAudioMode() {
        val old = originalAudioMode ?: return
        try {
            audioManager.mode = old
            Log.i(TAG, "AUDIO_MODE_RESTORED old=IN_COMMUNICATION new=$old")
        } catch (e: Exception) {
            Log.e(TAG, "FAILED_TO_RESTORE_AUDIO_MODE", e)
        } finally {
            originalAudioMode = null
        }
    }

    private fun scheduleSttRestart(delayMs: Long) {
        if (destroyed || navigationSpeaking || restartScheduled) return
        restartScheduled = true
        handler.postAtTime({
            restartScheduled = false
            startSttSession()
        }, RESTART_TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    // ─── CONVERSATION LOGIC ───

    private fun processPacket(text: String, isFinal: Boolean) {
        if (text.isBlank()) return
        
        val norm = normalizeText(text)
        Log.i(TAG, if (isFinal) "CONV_PACKET_FINAL text='$text'" else "CONV_PACKET_PARTIAL text='$text'")

        // 1. Immediate Commands (Pre-buffer check)
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

        // 2. Buffer Interaction
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

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace("ą", "a")
            .replace("č", "c")
            .replace("ę", "e")
            .replace("ė", "e")
            .replace("į", "i")
            .replace("š", "s")
            .replace("ų", "u")
            .replace("ū", "u")
            .replace("ž", "z")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
    }

    private fun isWakeWordOnly(text: String): Boolean {
        val norm = normalizeText(text)
        if (exactWakePhrases.contains(norm)) return true
        val tokens = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        return tokens.all { strongWakeWords.contains(it) || it.startsWith("kent") || it == "ei" || it == "klausyk" }
    }

    private fun tokensContainQuestion(norm: String): Boolean {
        val tokens = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.any { questionWords.contains(it) }
    }

    // ─── STT CALLBACKS (Packet Receiver) ───

    override fun onReadyForSpeech(params: Bundle?) {
        val readyTime = System.currentTimeMillis()
        Log.i(TAG, "STT_READY_AT=$readyTime deltaSinceStart=${readyTime - sttSessionStartedAt}ms")
    }
    override fun onBeginningOfSpeech() {
        val beginTime = System.currentTimeMillis()
        Log.i(TAG, "STT_BEGIN_AT=$beginTime deltaSinceReady=${if (sttSessionStartedAt > 0) beginTime - sttSessionStartedAt else 0}ms")
        if (phase == Phase.LISTENING || phase == Phase.IDLE) transitionTo(Phase.COLLECTING)
    }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        Log.i(TAG, "CONV_EVENT type=STT_PACKET_END")
    }

    override fun onError(error: Int) {
        Log.i(TAG, "CONV_EVENT type=STT_ERROR code=$error")
        isListeningSessionActive = false
        restoreAudioMode()
        
        val isListeningPhase = phase == Phase.LISTENING || phase == Phase.COLLECTING || phase == Phase.IDLE
        if (isListeningPhase && !destroyed && !navigationSpeaking) {
            when (error) {
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    Log.w(TAG, "STT_RECOGNIZER_RECREATED reason=code_11")
                    prepareRecognizer()
                    scheduleSttRestart(2000)
                }
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    // Persistent buffer means code 7 is fine. Just restart listener.
                    val delay = if (mode == ConversationMode.IDLE) 2000L else 600L
                    scheduleSttRestart(delay)
                }
                else -> scheduleSttRestart(1000)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        isListeningSessionActive = false
        restoreAudioMode()
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.maxByOrNull { it.length } ?: ""
        processPacket(text, isFinal = true)
        
        // Auto-restart listening packet if we are still collecting/listening
        if (phase == Phase.LISTENING || phase == Phase.COLLECTING || phase == Phase.IDLE) {
            scheduleSttRestart(600)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.maxByOrNull { it.length } ?: ""
        if (text.isNotBlank() && firstPartialAt == 0L) {
            firstPartialAt = System.currentTimeMillis()
            Log.d(TAG, "STT_FIRST_PARTIAL_AT=$firstPartialAt deltaSinceReady=${if (sttSessionStartedAt > 0) firstPartialAt - sttSessionStartedAt else 0}ms text='$text'")
        }
        processPacket(text, isFinal = false)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ─── EXTERNAL CONTROL ───

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
            navigationSpeaking = true
            prePausedPhase = phase
            transitionTo(Phase.PAUSED_BY_NAVIGATION)
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
        
        Log.i(TAG, "CONV_LISTEN_RESUME_REQUESTED delay=100")
        navigationSpeaking = false
        handler.removeCallbacks(navResumeWatchdogRunnable)
        handler.removeCallbacks(navResumeRunnable)

        val resumePhase = when (prePausedPhase) {
            Phase.SPEAKING, Phase.THINKING -> Phase.LISTENING
            else -> prePausedPhase ?: Phase.IDLE
        }
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
        
        transitionTo(Phase.SPEAKING)
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
        
        if (reason != "CANCELLED_BY_NAVIGATION") {
            if (mode == ConversationMode.ACTIVE) {
                transitionTo(Phase.LISTENING)
            } else {
                transitionTo(Phase.IDLE)
            }
        }
    }

    fun resetIdleTimer() {
        hasOpenerFired = false
        cancelInactivityTimer()
        inactivityRunnable = Runnable {
            val dist = getNextManeuverDist()
            if (dist > 500 && !navigationSpeaking && phase == Phase.IDLE) {
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
        restoreAudioMode()
        watchdogHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        speechRecognizer?.destroy()
        cancelInactivityTimer()
    }

    companion object {
        private val RESTART_TOKEN = Any()
    }
}
