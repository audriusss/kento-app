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
 * Manages the continuous push-to-talk conversation loop:
 *
 *   Button tap → listen → (speech recognised) → AI call → speak → re-listen
 *                        → (no speech / silence) → wait → re-listen → keep alive
 *                        → (infra failure) → infra retry → re-listen or stop
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
 * ## Error policy — two separate categories
 *
 * ### Normal speech events (never close the conversation)
 *
 * - [RecoveryPolicy.E_NO_MATCH] — recognizer heard audio but could not match words.
 *   Happens after every breath, background sound, or sentence fragment.  Logged as
 *   NO_MATCH_RELISTEN; never increments [infraRetryCount].
 * - [RecoveryPolicy.E_SPEECH_TIMEOUT] — no speech detected within the silence window.
 *   Logged as SPEECH_TIMEOUT_RELISTEN; never increments [infraRetryCount].
 *
 * Both restart listening after a short delay and keep the same session alive.
 *
 * ### Infrastructure failures (counted toward [MAX_INFRA_RETRIES])
 *
 * All other recoverable errors ([RecoveryPolicy.E_SERVER], [RecoveryPolicy.E_SERVER_DISCONNECTED],
 * [RecoveryPolicy.E_NETWORK], etc.) increment [infraRetryCount].  The counter is reset to
 * zero whenever the recognizer successfully reaches [onReadyForSpeech], [onBeginningOfSpeech],
 * or [onResults] — meaning a successful recovery wipes the slate clean.
 *
 * ## Inactivity timer — real listening time only
 *
 * The 30-second timer represents time the user has to speak — not AI generation time,
 * Kentas TTS time, navigation TTS time, or time spent restarting the recognizer.
 *
 * Timer lifecycle:
 * - STARTED / RESUMED: when [onReadyForSpeech] fires (recognizer is genuinely ready).
 * - PAUSED: when AI generation starts, TTS starts, an error triggers a restart, or nav interrupts.
 *
 * The timer never starts from [startConversation]; it waits for the first [onReadyForSpeech]
 * from the new listening session.
 *
 * ## Session isolation
 *
 * Every [startConversation] call increments [generation].  All callbacks and coroutine
 * bodies guard with `isCurrentGenValue(myGen)` so stale callbacks from a previous session
 * are complete no-ops.
 *
 * @param scope             CoroutineScope tied to ViewModel lifetime (Main dispatcher).
 * @param speechManager     SR wrapper (must already be initialised).
 * @param speechCoordinator Centralised TTS owner.
 * @param apiKey            OpenAI API key; blank → fixed Lithuanian error string.
 */
class KentasConversationController(
    private val scope: CoroutineScope,
    private val speechManager: SpeechRecognitionManager,
    private val speechCoordinator: KentasSpeechCoordinator,
    private val apiKey: String,
) {

    companion object {
        private const val TAG = "KentasConversation"

        /** Inactivity window while the recognizer is actively listening. */
        const val INACTIVITY_TIMEOUT_MS = 30_000L

        /**
         * Maximum consecutive **infrastructure** failures before the conversation stops.
         * Normal speech events ([RecoveryPolicy.E_NO_MATCH], [RecoveryPolicy.E_SPEECH_TIMEOUT])
         * do NOT count toward this limit — they are expected during ordinary use and simply
         * trigger a new listening session.
         *
         * A "consecutive" run is broken by any successful recovery signal:
         * [onReadyForSpeech], [onBeginningOfSpeech], or [onResults].
         */
        const val MAX_INFRA_RETRIES = 3

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

    /**
     * Consecutive infrastructure failure count.
     *
     * Incremented only on errors that are NOT [RecoveryPolicy.isSilentRecovery].
     * Reset to zero on [onReadyForSpeech], [onBeginningOfSpeech], and [onResults].
     */
    @Volatile private var infraRetryCount = 0

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
     */
    private var pendingRelistenJob: Job? = null

    /** Recent conversation turns — (role, content) pairs. Cleared on stop. */
    private val history = mutableListOf<Pair<String, String>>()

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Start a conversation session (or stop the current one if already active).
     * Safe to call from any thread.
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
        speechManager.onStartRequested    = null
        speechManager.onReadyForSpeech    = null
        speechManager.onBeginningOfSpeech = null
        speechManager.onPartialResult     = null
        speechManager.onResult            = null
        speechManager.onRecoverableError  = null
        speechManager.onFatalError        = null
        speechManager.onListeningStopped  = null
    }

    // ── Private: session start ─────────────────────────────────────────────

    private fun startConversation() {
        _isActive.value = true
        infraRetryCount = 0
        generation++
        history.clear()
        pendingRelistenJob?.cancel()
        pendingRelistenJob = null
        val myGen = generation
        Log.i(TAG, "CONVERSATION_SESSION_OPENED session=$myGen")
        // Do NOT start inactivity timer here — wait for onReadyForSpeech.
        // The timer only counts time the user has to speak; startup latency is not that.
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
        pauseInactivityTimer("session-closed")
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
     * Pauses the inactivity timer (nav TTS duration must not count as user inactivity)
     * and schedules one new listening session via [requestRelisten] — dispatched to
     * the Main thread through `scope.launch`, because this callback fires on the TTS
     * background thread.
     *
     * The inactivity timer restarts when the new session's [onReadyForSpeech] fires.
     * No-op if the conversation was stopped or the generation has advanced.
     */
    fun resumeAfterNavInterrupt() {
        if (!_isActive.value) {
            Log.d(TAG, "resumeAfterNavInterrupt: conversation not active — skipped")
            return
        }
        val myGen = generation
        Log.i(TAG, "CONVERSATION_RESUMED_AFTER_NAV session=$myGen")
        pauseInactivityTimer("nav-interrupted")
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
                // Infrastructure is healthy — reset the consecutive failure counter.
                if (infraRetryCount > 0) {
                    Log.i(TAG, "INFRA_RETRY_RESET reason=onReady session=$myGen prevCount=$infraRetryCount")
                    infraRetryCount = 0
                }
                _state.value = ConversationState.LISTENING
                // Start (or resume) the inactivity timer — this is the first moment
                // the user actually has an opportunity to speak.
                startOrResumeInactivityTimer(myGen)
            }
        }

        speechManager.onBeginningOfSpeech = {
            if (isCurrentGen(myGen, "onBeginningOfSpeech")) {
                // User is speaking — infrastructure is clearly working.
                if (infraRetryCount > 0) {
                    Log.i(TAG, "INFRA_RETRY_RESET reason=onBeginning session=$myGen prevCount=$infraRetryCount")
                    infraRetryCount = 0
                }
                _state.value = ConversationState.USER_SPEAKING
                // Timer keeps running — user is actively using their window.
            }
        }

        speechManager.onPartialResult = null  // not used in conversation mode

        speechManager.onResult = { text ->
            if (isCurrentGen(myGen, "onResult")) {
                Log.i(TAG, "USER_SPEECH_RESULT session=$myGen text='${text.take(80)}'")
                // Speech was recognised — infrastructure is working.
                if (infraRetryCount > 0) {
                    Log.i(TAG, "INFRA_RETRY_RESET reason=onResults session=$myGen prevCount=$infraRetryCount")
                    infraRetryCount = 0
                }
                // Pause timer: AI generation time must not count as user inactivity.
                pauseInactivityTimer("result-received")
                handleResult(text, myGen)
            }
        }

        speechManager.onRecoverableError = { code ->
            if (isCurrentGen(myGen, "onRecoverableError code=$code")) {
                when {
                    RecoveryPolicy.isSilentRecovery(code) -> {
                        // ── Normal speech event — NEVER increments infraRetryCount ────────
                        // ERROR_NO_MATCH: recognizer heard audio but found no matching words.
                        //   Happens after every breath, cough, or sentence fragment.
                        // ERROR_SPEECH_TIMEOUT: no speech detected in the silence window.
                        //   Happens when the user is momentarily quiet.
                        // Neither of these is a platform failure; both are expected events
                        // that must simply restart listening and keep the session alive.
                        val logTag = if (code == RecoveryPolicy.E_NO_MATCH)
                            "NO_MATCH_RELISTEN" else "SPEECH_TIMEOUT_RELISTEN"
                        Log.i(TAG, "$logTag session=$myGen code=$code")
                        // Pause timer: recognizer is restarting; time must not count.
                        pauseInactivityTimer(logTag.lowercase())
                        val delayMs = RecoveryPolicy.delayMs(code)
                        scope.launch {
                            delay(delayMs)
                            if (isCurrentGenValue(myGen)) requestRelisten(myGen, logTag.lowercase())
                        }
                    }
                    else -> {
                        // ── Infrastructure failure — counts toward MAX_INFRA_RETRIES ──────
                        infraRetryCount++
                        Log.w(TAG,
                            "INFRA_RETRY increment=$infraRetryCount session=$myGen " +
                            "code=$code (${RecoveryPolicy.errorName(code)})")
                        // Pause timer: recognizer is restarting; time must not count.
                        pauseInactivityTimer("infra-retry")
                        if (infraRetryCount <= MAX_INFRA_RETRIES) {
                            val delayMs = RecoveryPolicy.delayMs(code)
                            scope.launch {
                                delay(delayMs)
                                if (isCurrentGenValue(myGen)) {
                                    requestRelisten(myGen, "infra-retry-$code")
                                }
                            }
                        } else {
                            Log.e(TAG,
                                "MAX_INFRA_RETRIES=$MAX_INFRA_RETRIES reached session=$myGen — stopping")
                            stopConversation(reason = "max-infra-retries")
                        }
                    }
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
     * This is the ONLY entry point for [SpeechRecognitionManager.startListening].
     *
     * ## Why scope.launch is required
     *
     * [TtsManager.UtteranceProgressListener.onDone] fires on Android's TTS internal
     * background thread.  [SpeechRecognizer] requires the Main thread for [startListening].
     *
     * [scope] (viewModelScope) uses [Dispatchers.Main.immediate]:
     * - On Main thread: executes immediately.
     * - On background thread (TTS callback, error callback): queued on Main Looper.
     *
     * ## Duplicate prevention
     *
     * Any [pendingRelistenJob] still queued is cancelled before scheduling a new one.
     * Inside the launched block, [isCurrentGenValue] and [speechManager.isSessionActive]
     * prevent stale or overlapping starts.
     */
    private fun requestRelisten(myGen: Long, reason: String) {
        pendingRelistenJob?.cancel()
        pendingRelistenJob = scope.launch {
            if (!isCurrentGenValue(myGen)) {
                Log.d(TAG,
                    "RELISTEN_SKIPPED reason=stale session=$myGen currentGen=$generation from=$reason")
                return@launch
            }
            if (speechManager.isSessionActive) {
                Log.d(TAG,
                    "RELISTEN_SKIPPED reason=already-listening session=$myGen from=$reason")
                return@launch
            }
            Log.i(TAG, "CONVERSATION_RELISTEN_REQUESTED session=$myGen from=$reason")
            _state.value = ConversationState.LISTENING
            speechManager.startListening()
        }
    }

    // ── Private: AI + TTS cycle ───────────────────────────────────────────

    private fun handleResult(text: String, myGen: Long) {
        Log.i(TAG, "AI_RESPONSE_STARTED session=$myGen text='${text.take(80)}'")
        // Pause the inactivity timer — AI generation and TTS are not user silence.
        // The timer restarts when onReadyForSpeech fires for the next listening session.
        pauseInactivityTimer("ai-generating")
        _state.value = ConversationState.THINKING

        aiJob?.cancel()
        aiJob = scope.launch {
            if (!isCurrentGenValue(myGen)) return@launch

            val navState       = latestNavState
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
                // requestRelisten dispatches to Main via scope.launch.
                Log.i(TAG, "AI_RESPONSE_TTS_DONE session=$myGen")
                if (isCurrentGenValue(myGen)) {
                    // Do NOT start the inactivity timer here — recognizer is about to
                    // restart; the timer resumes when onReadyForSpeech fires.
                    requestRelisten(myGen, "ai-response-done")
                }
            }
        }
    }

    // ── Inactivity timer ──────────────────────────────────────────────────

    /**
     * Start (or resume) the 30-second inactivity countdown.
     *
     * Called ONLY from [onReadyForSpeech] — the first moment the user genuinely
     * has an opportunity to speak.  All other cycle points (AI generation, TTS,
     * recognizer restarts, nav interrupts) must call [pauseInactivityTimer] instead.
     *
     * Logs [INACTIVITY_TIMER_STARTED] on the first call per session;
     * [INACTIVITY_TIMER_RESUMED] on subsequent calls (after a pause/resume cycle).
     */
    private fun startOrResumeInactivityTimer(myGen: Long) {
        val wasRunning = inactivityJob?.isActive == true
        inactivityJob?.cancel()
        val logTag = if (wasRunning) "INACTIVITY_TIMER_RESUMED" else "INACTIVITY_TIMER_STARTED"
        Log.i(TAG, "$logTag session=$myGen timeoutMs=$INACTIVITY_TIMEOUT_MS")
        inactivityJob = scope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            if (isCurrentGenValue(myGen)) {
                Log.i(TAG, "inactivity timeout fired session=$myGen — stopping conversation")
                stopConversation(reason = "inactivity-timeout")
            }
        }
    }

    /**
     * Pause (cancel) the inactivity timer without closing the session.
     *
     * Called when entering any phase where the user cannot speak:
     * AI generation, Kentas TTS, navigation TTS, recognizer restart.
     * The timer is restarted by the next [onReadyForSpeech].
     */
    private fun pauseInactivityTimer(reason: String) {
        if (inactivityJob?.isActive == true) {
            Log.i(TAG, "INACTIVITY_TIMER_PAUSED reason=$reason")
            inactivityJob?.cancel()
        }
        inactivityJob = null
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
