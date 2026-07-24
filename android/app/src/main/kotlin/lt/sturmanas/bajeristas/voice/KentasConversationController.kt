package lt.sturmanas.bajeristas.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.voice.askKentas

/**
 * Manages the continuous push-to-talk conversation loop:
 *
 *   Button tap → listen → (speech recognised) → AI call → speak → re-listen
 *                        → (no speech / silence) → re-listen (reset timer)
 *                        → (recoverable error) → retry once → re-listen or stop
 *
 * ## Multi-turn guarantee
 *
 * After every AI response (TTS finishes), [requestRelisten] schedules exactly one
 * new listening session via [scope.launch] on the Main dispatcher.  This is the
 * critical threading fix: [TtsManager.UtteranceProgressListener.onDone] fires on
 * Android's TTS background thread, and [SpeechRecognizer] requires the Main thread
 * for [startListening].  Calling [SpeechRecognitionManager.startListening] directly
 * from the TTS callback thread causes the recogniser to fail silently, ending the
 * conversation after exactly one turn.
 *
 * ## Inactivity timeout
 *
 * The 30-second timer represents time the user has to speak — NOT AI generation
 * time or Kentas TTS time.  The timer is reset after TTS finishes (in the
 * [onDone] callback), not when the speech result is received, so AI + TTS
 * duration never eats into the user's silence budget.
 *
 * ## Session isolation
 *
 * Every [startConversation] call increments [generation].  All callbacks and
 * coroutine bodies guard with `isCurrentGenValue(myGen)` so stale callbacks
 * from a previous session are no-ops.
 *
 * ## Navigation interruption
 *
 * [speakNavigation] pre-empts conversational TTS (QUEUE_FLUSH) and clears the
 * conversation onDone callback.  After the navigation utterance finishes,
 * [resumeAfterNavInterrupt] is called, which resets the inactivity timer and
 * schedules one new listening request via [requestRelisten] — again dispatched
 * to the Main thread.  The session is never closed or reset by a nav interrupt.
 *
 * @param scope           CoroutineScope tied to ViewModel lifetime (Main dispatcher).
 * @param speechManager   SR wrapper (must already be initialised).
 * @param speechCoordinator  Centralised TTS owner.
 * @param apiKey          OpenAI API key; blank → fixed Lithuanian error string.
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
    /** Current granular state. Drives the [MicButton] visual and status text. */
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

    /**
     * One-shot coroutine job that dispatches the next [SpeechRecognitionManager.startListening]
     * to the Main thread.
     *
     * Tracked here to:
     *  - prevent duplicate re-listen calls (cancel any pending job before scheduling a new one)
     *  - allow [stopConversation] to cancel a pending re-listen immediately so a stale
     *    TTS callback cannot reopen a session that was manually closed
     *
     * Created by [requestRelisten]; cancelled in [stopConversation] and [release].
     */
    private var pendingRelistenJob: Job? = null

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
        stopConversation(reason = "user-manual")
    }

    /** Release all resources. Call from ViewModel.onCleared. */
    fun release() {
        stopConversation(reason = "vm-cleared")
        pendingRelistenJob?.cancel()
        pendingRelistenJob = null
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
        pendingRelistenJob?.cancel()
        pendingRelistenJob = null
        val myGen = generation
        Log.i(TAG, "CONVERSATION_SESSION_OPENED session=$myGen")
        resetInactivityTimer(myGen, "session-start")
        installCallbacks(myGen)
        requestRelisten(myGen, "session-start")
    }

    // ── Private: session stop ──────────────────────────────────────────────

    private fun stopConversation(reason: String) {
        if (!_isActive.value) return
        Log.i(TAG, "CONVERSATION_SESSION_CLOSED reason=$reason session=$generation")
        _isActive.value = false
        generation++            // invalidate all in-flight callbacks
        pendingRelistenJob?.cancel()
        pendingRelistenJob = null
        inactivityJob?.cancel()
        aiJob?.cancel()
        speechManager.cancel()
        speechCoordinator.stop()
        history.clear()
        _state.value = ConversationState.IDLE
    }

    // ── Public: post-navigation recovery ──────────────────────────────────

    /**
     * Called by [MainViewModel] when a navigation TTS utterance has finished.
     *
     * Resumes exactly one listening session if the conversation is still active.
     * Restarts the 30-second inactivity timer so the nav-TTS duration does not
     * count against the user's silence budget.
     *
     * Uses [requestRelisten] to dispatch to the Main thread — this callback fires
     * on the TTS [UtteranceProgressListener] background thread and [SpeechRecognizer]
     * requires the Main thread.
     *
     * No-op if the conversation was stopped or the generation has advanced.
     */
    fun resumeAfterNavInterrupt() {
        if (!_isActive.value) {
            Log.d(TAG, "resumeAfterNavInterrupt: conversation not active — skipped")
            return
        }
        val myGen = generation
        Log.i(TAG, "CONVERSATION_RESUMED_AFTER_NAV session=$myGen")
        resetInactivityTimer(myGen, "nav-interrupt-resume")
        requestRelisten(myGen, "nav-interrupt-resume")
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
                Log.i(TAG, "USER_SPEECH_RESULT session=$myGen text='${text.take(80)}'")
                retryCount = 0
                resetInactivityTimer(myGen, "result-received")
                handleResult(text, myGen)
            }
        }

        speechManager.onRecoverableError = { code ->
            if (isCurrentGen(myGen, "onRecoverableError code=$code")) {
                retryCount++
                Log.d(TAG, "recoverable error session=$myGen code=$code retry=$retryCount")
                if (retryCount <= MAX_RETRIES) {
                    scope.launch {
                        delay(400)
                        if (isCurrentGenValue(myGen)) requestRelisten(myGen, "error-retry")
                    }
                } else {
                    Log.w(TAG, "MAX_RETRIES reached — stopping conversation session=$myGen")
                    stopConversation(reason = "max-retries")
                }
            }
        }

        speechManager.onFatalError = { msg ->
            if (isCurrentGen(myGen, "onFatalError")) {
                Log.e(TAG, "fatal SR error session=$myGen: $msg")
                stopConversation(reason = "fatal-sr-error")
            }
        }

        speechManager.onListeningStopped = {
            if (isCurrentGen(myGen, "onListeningStopped")) {
                _state.value = ConversationState.THINKING
            }
        }
    }

    // ── Private: re-listen dispatcher ─────────────────────────────────────

    /**
     * Schedules exactly one listening session on the Main thread.
     *
     * This is the ONLY entry point for starting [SpeechRecognitionManager.startListening].
     *
     * ## Why scope.launch is required
     *
     * [TtsManager.UtteranceProgressListener.onDone] fires on Android's TTS internal
     * background thread.  [SpeechRecognizer] requires the Main thread for [startListening].
     * Calling [SpeechRecognitionManager.startListening] directly from the TTS callback
     * thread causes the recogniser to fail silently — this is the root cause of the
     * one-turn-then-stop bug.
     *
     * [scope] is [MainViewModel.viewModelScope] which uses [Dispatchers.Main.immediate]:
     * - If already on Main: executes immediately (no coroutine overhead).
     * - If on background thread (TTS callback): queued on Main Looper.
     *
     * ## Duplicate prevention
     *
     * Any [pendingRelistenJob] still queued (from a concurrent callback) is cancelled
     * before scheduling the new one.  Four gates inside the launched block prevent
     * redundant or stale starts:
     *  1. [isCurrentGenValue] — conversation must still be active with the same generation.
     *  2. [speechManager.isSessionActive] — recogniser must not already be listening.
     *  3. The generation check implicitly covers manual-close (generation advances in stopConversation).
     */
    private fun requestRelisten(myGen: Long, reason: String) {
        // Cancel any existing pending relisten so we never queue two back-to-back starts.
        pendingRelistenJob?.cancel()
        pendingRelistenJob = scope.launch {
            // viewModelScope dispatches on Dispatchers.Main.immediate
            if (!isCurrentGenValue(myGen)) {
                Log.d(TAG, "RELISTEN_SKIPPED reason=stale session=$myGen currentGen=$generation from=$reason")
                return@launch
            }
            if (speechManager.isSessionActive) {
                Log.d(TAG, "RELISTEN_SKIPPED reason=already-listening session=$myGen from=$reason")
                return@launch
            }
            Log.i(TAG, "CONVERSATION_RELISTEN_REQUESTED session=$myGen from=$reason")
            _state.value = ConversationState.LISTENING
            speechManager.startListening()
        }
    }

    // ── Private: cycle steps ───────────────────────────────────────────────

    private fun handleResult(text: String, myGen: Long) {
        Log.i(TAG, "AI_RESPONSE_STARTED session=$myGen text='${text.take(80)}'")
        _state.value = ConversationState.THINKING

        aiJob?.cancel()
        aiJob = scope.launch {
            if (!isCurrentGenValue(myGen)) return@launch

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
                // ── onDone fires on TTS background thread ────────────────────
                // Do NOT call speechManager.startListening() here directly.
                // requestRelisten() dispatches to Main via scope.launch.
                Log.i(TAG, "AI_RESPONSE_TTS_DONE session=$myGen")
                if (isCurrentGenValue(myGen)) {
                    // Reset timer AFTER TTS finishes — user now has a full 30 s window.
                    resetInactivityTimer(myGen, "ai-response-done")
                    requestRelisten(myGen, "ai-response-done")
                }
            }
        }
    }

    // ── Inactivity timer ──────────────────────────────────────────────────

    private fun resetInactivityTimer(myGen: Long, reason: String = "") {
        Log.d(TAG, "resetInactivityTimer session=$myGen${if (reason.isNotEmpty()) " reason=$reason" else ""}")
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            if (isCurrentGenValue(myGen)) {
                Log.d(TAG, "inactivity timeout session=$myGen — stopping conversation")
                stopConversation(reason = "inactivity-timeout")
            }
        }
    }

    // ── Generation helpers ────────────────────────────────────────────────

    /** Check and log if this callback is stale. Returns true if current. */
    private fun isCurrentGen(myGen: Long, event: String): Boolean {
        val current = isCurrentGenValue(myGen)
        if (!current) Log.d(TAG, "$event: STALE session=$myGen currentGen=$generation — ignored")
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
