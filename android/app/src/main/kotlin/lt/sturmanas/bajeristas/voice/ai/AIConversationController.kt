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
import lt.sturmanas.bajeristas.navigation.PlacesAutocompleteClient
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
    // questionWords and semantic helpers live in SemanticCompletionDetector (pure Kotlin, testable).

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
        transcriptReceivedAtMs = System.currentTimeMillis()
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
                return
            }
        } else {
            // Wake-word-only: acknowledge and open session without calling AI.
            if (mode == ConversationMode.IDLE && isWakeWordOnly(norm)) {
                Log.i(TAG, "CONV_SEMANTIC_COMPLETE result=WAKE_ONLY")
                clearUtteranceBuffer("wake_only")
                mode = ConversationMode.ACTIVE
                speak("Klausau.")
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
     * The UI returns to [StartScreen] automatically: [MainActivity]'s
     * LaunchedEffect observing [NavigationPhase] resets isNavigating when the
     * engine transitions back to [NavigationPhase.IDLE].
     */
    private fun handleRouteCancellation() {
        Log.i(TAG, "VOICE_NAV_ROUTE_CANCEL_RECEIVED")
        // Also clear any pending place selection that may have been left open.
        pendingVoiceChoices = null
        pendingVoiceChoicesOneResult = false
        clearUtteranceBuffer("voice_route_cancel")
        onStopNavigation?.invoke()
        Log.i(TAG, "VOICE_NAV_ROUTE_STOPPED")
        handler.post { if (!destroyed) speak("Gerai, maršrutą nutraukiau.") }
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
            handleVoiceNavigation(text, norm)
            return
        }

        aiRequestStartedAtMs = System.currentTimeMillis()
        val elapsedSinceStt = if (transcriptReceivedAtMs > 0)
            aiRequestStartedAtMs - transcriptReceivedAtMs
        else -1L
        Log.i(TAG, "AI_REQUEST_STARTED elapsedSinceSttMs=$elapsedSinceStt text='$text'")
        clearUtteranceBuffer("sent_to_ai")
        transitionTo(Phase.THINKING)
        KentasChat.askKentas(text) { reply ->
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
            // 2. transitionTo → pipeline.mute() + increments generationId
            // 3. resetVadAndSegmenter() — clear Silero/segmenter state
            Log.i(TAG, "MIC_PIPELINE_MUTED reason=NAVIGATION")
            prePausedPhase = phase
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

        // Safe mute order for AI TTS:
        // 1. Log intent (reason visible in Logcat alongside pipeline's generation log)
        // 2. transitionTo(SPEAKING) → pipeline.mute() + increments generationId
        // 3. resetVadAndSegmenter() — clears Silero LSTM and segmenter state
        // 4. Start TTS playback
        Log.i(TAG, "MIC_PIPELINE_MUTED reason=AI_TTS")
        transitionTo(Phase.SPEAKING)
        pipeline.resetVadAndSegmenter()

        aiTtsTerminalHandled = false
        currentAiUtteranceId = UUID.randomUUID().toString().substring(0, 8)
        sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
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
            Log.i(TAG, "MIC_PIPELINE_UNMUTE_SCHEDULED cooldownMs=${PipelineConfig.POST_TTS_COOLDOWN_MS}")
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
