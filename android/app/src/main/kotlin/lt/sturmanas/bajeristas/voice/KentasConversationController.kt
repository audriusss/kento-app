package lt.sturmanas.bajeristas.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.voice.askKentas

/**
 * Manages the simple push-to-talk conversation loop:
 *
 *   Button tap → listen → (speech recognised) → AI call → speak → re-listen
 *                        → (no speech / silence) → re-listen (reset timer)
 *                        → (recoverable error) → retry once → re-listen or stop
 *
 * ## Inactivity timeout
 * If the user speaks nothing for [INACTIVITY_TIMEOUT_MS] (30 s), the conversation
 * stops automatically and [isActive] becomes false.
 *
 * ## Session isolation
 * Every [startConversation] call increments [generation]. All SpeechRecognitionManager
 * callbacks and coroutine bodies guard with `generation == myGen` so stale callbacks
 * from a previous session are harmless no-ops.
 *
 * ## Navigation state
 * The latest navigation state is provided via [latestNavState], updated by the ViewModel
 * on every navigation SDK event so each AI call sees current road/maneuver data.
 *
 * Conversation history (last [MAX_HISTORY] turns) is kept in [history] and passed to
 * [askKentas] for context. Cleared on [stopConversation].
 *
 * @param scope     CoroutineScope tied to ViewModel lifetime.
 * @param speechManager  SR wrapper (must already be initialised).
 * @param speechCoordinator  Centralized TTS owner.
 * @param apiKey    OpenAI API key; blank = no AI, returns a fixed error string.
 */
class KentasConversationController(
    private val scope: CoroutineScope,
    private val speechManager: SpeechRecognitionManager,
    private val speechCoordinator: KentasSpeechCoordinator,
    private val apiKey: String,
) {

    companion object {
        private const val TAG = "KentasConversation"

        /** Inactivity window before the conversation auto-stops. */
        const val INACTIVITY_TIMEOUT_MS = 30_000L

        /** Maximum consecutive recoverable SR errors before the conversation stops. */
        const val MAX_RETRIES = 1

        /** Max conversation history entries kept (10 = 5 exchanges). */
        const val MAX_HISTORY = 10
    }

    // ── State ─────────────────────────────────────────────────────────────

    private val _isActive = MutableStateFlow(false)
    /** True while a conversation session is running. Drives the mic button ring. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _state = MutableStateFlow(ConversationState.IDLE)
    /**
     * Current granular state. Drives the [MicButton] visual and status text.
     */
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    // ── Latest navigation context ─────────────────────────────────────────

    /**
     * Must be updated by the ViewModel on every navigation state change.
     * Used in [askKentas] so the AI always sees current road / maneuver data.
     */
    @Volatile
    var latestNavState: NavigationState = NavigationState()

    // ── Internal ──────────────────────────────────────────────────────────

    @Volatile private var generation = 0L
    @Volatile private var retryCount = 0

    private var inactivityJob: Job? = null
    private var aiJob: Job? = null

    /** Recent conversation turns — (role, content) pairs. Cleared on stop. */
    private val history = mutableListOf<Pair<String, String>>()

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Start a conversation session (or stop the current one if already active).
     * Safe to call from any thread — all operations are dispatched to coroutines
     * or are @MainThread-safe SpeechRecognitionManager calls.
     */
    fun toggleConversation() {
        if (_isActive.value) {
            stopConversation()
        } else {
            startConversation()
        }
    }

    /** Stop the active conversation and return to IDLE state. */
    fun stopConversation() {
        if (!_isActive.value) return
        Log.d(TAG, "stopConversation gen=$generation")
        _isActive.value = false
        generation++            // invalidate all in-flight callbacks
        inactivityJob?.cancel()
        aiJob?.cancel()
        speechManager.cancel()
        speechCoordinator.stop()
        history.clear()
        _state.value = ConversationState.IDLE
    }

    /** Release all resources. Call from ViewModel.onCleared. */
    fun release() {
        stopConversation()
        // Clear all callbacks from the speech manager.
        speechManager.onStartRequested   = null
        speechManager.onReadyForSpeech   = null
        speechManager.onBeginningOfSpeech = null
        speechManager.onPartialResult    = null
        speechManager.onResult           = null
        speechManager.onRecoverableError = null
        speechManager.onFatalError       = null
        speechManager.onListeningStopped = null
    }

    // ── Private: session start ─────────────────────────────────────────────

    private fun startConversation() {
        _isActive.value = true
        retryCount = 0
        generation++
        history.clear()
        val myGen = generation
        Log.d(TAG, "startConversation gen=$myGen")
        resetInactivityTimer(myGen, "session-start")
        installCallbacks(myGen)
        startListening(myGen)
    }

    // ── Public: post-navigation recovery ──────────────────────────────────

    /**
     * Called by [MainViewModel] when a navigation TTS utterance has finished.
     *
     * Resumes exactly one listening session if the conversation is still active.
     * Restarts the 30-second inactivity timer so the nav-TTS duration does not
     * count against the user's silence budget.
     *
     * No-op if:
     *  - the conversation was stopped by the user before the nav utterance finished
     *  - the inactivity timer already expired during the nav utterance
     *  - the generation has advanced (session restarted)
     */
    fun resumeAfterNavInterrupt() {
        if (!_isActive.value) {
            Log.d(TAG, "resumeAfterNavInterrupt: conversation not active — skipped")
            return
        }
        val myGen = generation
        Log.d(TAG, "resumeAfterNavInterrupt gen=$myGen — nav TTS done, resuming listening")
        resetInactivityTimer(myGen, "nav-interrupt-resume")
        startListening(myGen)
    }

    // ── Callbacks ─────────────────────────────────────────────────────────

    private fun installCallbacks(myGen: Long) {
        speechManager.onStartRequested = {
            if (isCurrentGen(myGen, "onStartRequested")) {
                _state.value = ConversationState.LISTENING
            }
        }

        speechManager.onReadyForSpeech = {
            if (isCurrentGen(myGen, "onReadyForSpeech")) {
                _state.value = ConversationState.LISTENING
            }
        }

        speechManager.onBeginningOfSpeech = {
            if (isCurrentGen(myGen, "onBeginningOfSpeech")) {
                _state.value = ConversationState.USER_SPEAKING
                resetInactivityTimer(myGen, "beginning-of-speech")
            }
        }

        speechManager.onPartialResult = null  // not used in conversation mode

        speechManager.onResult = { text ->
            if (isCurrentGen(myGen, "onResult")) {
                retryCount = 0
                resetInactivityTimer(myGen, "result-received")
                handleResult(text, myGen)
            }
        }

        speechManager.onRecoverableError = { code ->
            if (isCurrentGen(myGen, "onRecoverableError code=$code")) {
                retryCount++
                Log.d(TAG, "recoverable error gen=$myGen code=$code retry=$retryCount")
                if (retryCount <= MAX_RETRIES) {
                    scope.launch {
                        delay(400)
                        if (isCurrentGen(myGen, "retry-listen")) startListening(myGen)
                    }
                } else {
                    Log.w(TAG, "MAX_RETRIES reached — stopping conversation")
                    stopConversation()
                }
            }
        }

        speechManager.onFatalError = { msg ->
            if (isCurrentGen(myGen, "onFatalError")) {
                Log.e(TAG, "fatal SR error gen=$myGen: $msg")
                stopConversation()
            }
        }

        speechManager.onListeningStopped = {
            if (isCurrentGen(myGen, "onListeningStopped")) {
                _state.value = ConversationState.THINKING
            }
        }
    }

    // ── Private: cycle steps ───────────────────────────────────────────────

    private fun startListening(myGen: Long) {
        if (!isCurrentGenValue(myGen)) return
        Log.d(TAG, "startListening gen=$myGen")
        _state.value = ConversationState.LISTENING
        speechManager.startListening()
    }

    private fun handleResult(text: String, myGen: Long) {
        Log.d(TAG, "handleResult gen=$myGen: '${text.take(60)}'")
        _state.value = ConversationState.THINKING

        aiJob?.cancel()
        aiJob = scope.launch {
            if (!isCurrentGenValue(myGen)) return@launch

            // Snapshot nav state before the IO call.
            val navState = latestNavState
            val historySnapshot = history.toList()

            val reply = askKentas(
                userText = text,
                navState = navState,
                apiKey   = apiKey,
                history  = historySnapshot,
            )

            if (!isCurrentGenValue(myGen)) return@launch

            // Append to conversation history (oldest entry dropped when full).
            history.add("user" to text)
            history.add("assistant" to reply)
            while (history.size > MAX_HISTORY) history.removeAt(0)

            _state.value = ConversationState.SPEAKING
            speechCoordinator.speakConversation(reply) {
                // onDone: AI response finished speaking.
                // Reset timer so the full 30 s belongs to the user, not to AI+TTS time.
                if (isCurrentGenValue(myGen)) {
                    resetInactivityTimer(myGen, "ai-response-done")
                    _state.value = ConversationState.LISTENING
                    startListening(myGen)
                }
            }
        }
    }

    // ── Inactivity timer ──────────────────────────────────────────────────

    private fun resetInactivityTimer(myGen: Long, reason: String = "") {
        Log.d(TAG, "resetInactivityTimer gen=$myGen${if (reason.isNotEmpty()) " reason=$reason" else ""}")
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            if (isCurrentGenValue(myGen)) {
                Log.d(TAG, "inactivity timeout gen=$myGen — stopping conversation")
                stopConversation()
            }
        }
    }

    // ── Generation helpers ────────────────────────────────────────────────

    /** Check and log if this callback is stale. Returns true if current. */
    private fun isCurrentGen(myGen: Long, event: String): Boolean {
        val current = isCurrentGenValue(myGen)
        if (!current) Log.d(TAG, "$event: STALE gen=$myGen currentGen=$generation — ignored")
        return current
    }

    private fun isCurrentGenValue(myGen: Long): Boolean = myGen == generation && _isActive.value
}

/**
 * Granular state of the conversation cycle.
 * Drives [MicButton] visuals and status text in the UI.
 */
enum class ConversationState {
    /** No conversation session running. */
    IDLE,
    /** Microphone is active and waiting for user speech. */
    LISTENING,
    /** User is currently speaking. */
    USER_SPEAKING,
    /** AI call is in flight. */
    THINKING,
    /** TTS is playing Kentas's response. */
    SPEAKING,
}
