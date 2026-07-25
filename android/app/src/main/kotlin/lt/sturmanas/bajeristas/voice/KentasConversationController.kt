package lt.sturmanas.bajeristas.voice

import android.os.SystemClock
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
 * for [startListening].
 *
 * ## Error policy — two separate categories
 *
 * ### Normal speech events (never close the conversation)
 *
 * - [RecoveryPolicy.E_NO_MATCH] — recognizer heard audio but could not match words.
 * - [RecoveryPolicy.E_SPEECH_TIMEOUT] — no speech detected within the silence window.
 *
 * Neither increments [infraRetryCount].  Both simply relisten — but only if the
 * inactivity deadline has not expired.
 *
 * ### Infrastructure failures (counted toward [MAX_INFRA_RETRIES])
 *
 * All other recoverable errors increment [infraRetryCount].  The counter resets to
 * zero on [onReadyForSpeech], [onBeginningOfSpeech], or [onResults].
 *
 * ## Inactivity deadline — absolute, not restartable
 *
 * The user-response window is a **single absolute deadline** per conversational turn,
 * not a per-cycle restartable timer.
 *
 * ### Why a restartable timer does not work
 *
 * [SpeechRecognizer] naturally emits [RecoveryPolicy.E_NO_MATCH] every few seconds
 * while the user is silent (ambient audio, breath, silence window expiry).  Each
 * NO_MATCH triggers a relisten cycle: error → delay → [requestRelisten] →
 * [onReadyForSpeech].  If [onReadyForSpeech] restarts a full 30-second timer, the
 * countdown resets every few seconds and can never expire.  The conversation stays
 * alive forever regardless of user silence.
 *
 * ### Absolute deadline model
 *
 * [inactivityDeadlineMs] stores `SystemClock.elapsedRealtime()` of the deadline.
 * [openInactivityWindow] is called from [onReadyForSpeech]:
 * - If no deadline exists → create `deadline = now + 30 s`, log INACTIVITY_WINDOW_CREATED,
 *   schedule job for 30 000 ms.
 * - If deadline already exists → preserve it, log INACTIVITY_WINDOW_PRESERVED,
 *   schedule job for `deadline − now` ms only (the remaining slice).
 *   If `remaining ≤ 0` the deadline already expired → close immediately.
 *
 * NO_MATCH and SPEECH_TIMEOUT paths do NOT touch [inactivityDeadlineMs] — they check
 * whether the deadline has already passed before scheduling a relisten and, if so,
 * close the session instead of relistening.
 *
 * Infrastructure errors also leave the deadline intact so recovery time does not grant
 * extra silence budget.
 *
 * ### Window lifecycle
 *
 * - CREATED: first [onReadyForSpeech] after TTS / nav / session start.
 * - PRESERVED: subsequent [onReadyForSpeech] within the same turn.
 * - PAUSED ([onBeginningOfSpeech]): the scheduled timeout job is cancelled while the user
 *   is actively speaking, but [inactivityDeadlineMs] is NOT nulled.  If speech ends without
 *   a valid result (ERROR_NO_MATCH), the next [openInactivityWindow] resumes from the
 *   remaining slice of the same window — not a fresh 30-second window.
 * - CLEARED (deadline nulled): valid [onResults], AI generation start, session close,
 *   nav interrupt.  Clearing makes the next [onReadyForSpeech] open a fresh 30-second
 *   window for the next turn.
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

        /** Total user-response window per conversational turn. */
        const val INACTIVITY_TIMEOUT_MS = 30_000L

        /**
         * Maximum consecutive **infrastructure** failures before the conversation stops.
         * Normal speech events (NO_MATCH, SPEECH_TIMEOUT) never count toward this limit.
         * A "consecutive" run is broken by any successful recovery: [onReadyForSpeech],
         * [onBeginningOfSpeech], or [onResults].
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
     * Incremented only for errors that are NOT [RecoveryPolicy.isSilentRecovery].
     * Reset to zero on [onReadyForSpeech], [onBeginningOfSpeech], and [onResults].
     */
    @Volatile private var infraRetryCount = 0

    /**
     * Absolute deadline for the current user-response window, expressed as
     * [SystemClock.elapsedRealtime] millis.
     *
     * Null  → no window is open (AI generating, TTS speaking, session stopped, or
     *          waiting for the first [onReadyForSpeech] of a new turn).
     * Non-null → a window is open; [openInactivityWindow] preserves this value
     *            across NO_MATCH/infra relisten cycles so only the remaining slice
     *            counts down each time, not a fresh 30 seconds.
     *
     * Paused (job cancelled, deadline preserved) on: [onBeginningOfSpeech] — via
     * [pauseInactivityJobWhileSpeaking].  Cleared (nulled) by [clearInactivityWindow]
     * on: valid [onResults], AI generation start, nav interrupt, and any session close.
     */
    private var inactivityDeadlineMs: Long? = null

    /** Coroutine that fires when [inactivityDeadlineMs] is reached. Replaced on each [openInactivityWindow] call. */
    private var inactivityJob: Job? = null

    private var aiJob: Job? = null

    /**
     * One-shot coroutine dispatching the next [SpeechRecognitionManager.startListening]
     * to the Main thread.  Cancelled in [stopConversation] so a stale TTS callback cannot
     * reopen a closed session.
     */
    private var pendingRelistenJob: Job? = null

    /** Recent conversation turns — (role, content) pairs. Cleared on stop. */
    private val history = mutableListOf<Pair<String, String>>()

    // ── Public API ────────────────────────────────────────────────────────

    /** Start a conversation session (or stop the current one if already active). */
    fun toggleConversation() {
        if (_isActive.value) stopConversation() else startConversation()
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
        inactivityDeadlineMs = null     // ensure clean slate for new session
        generation++
        history.clear()
        pendingRelistenJob?.cancel()
        pendingRelistenJob = null
        val myGen = generation
        Log.i(TAG, "CONVERSATION_SESSION_OPENED session=$myGen elapsedMs=${SystemClock.elapsedRealtime()}")
        // Do NOT open an inactivity window here — wait for the first onReadyForSpeech.
        // Startup latency (recognizer creation, SR service init) is not user inactivity.
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
        clearInactivityWindow(reason)
        aiJob?.cancel()
        speechManager.cancel()
        // stopConversationSpeechOnly rather than stop() so that any navigation utterance
        // currently in progress (e.g. an instruction that interrupted this conversation) is
        // not killed — the nav TTS fires its own onDone callback, which is a no-op because
        // the session generation has already been incremented by this point.
        speechCoordinator.stopConversationSpeechOnly()
        history.clear()
        _state.value = ConversationState.IDLE
    }

    // ── Public: post-navigation recovery ──────────────────────────────────

    /**
     * Called by [MainViewModel] when a navigation TTS utterance has finished.
     *
     * Clears the current inactivity deadline so the next [onReadyForSpeech] opens a
     * **fresh** 30-second window for the next conversational turn — navigation TTS
     * duration must not consume the user's response budget.
     *
     * Schedules one new listening session via [requestRelisten], dispatched to the Main
     * thread through `scope.launch` (this callback fires on the TTS background thread).
     * No-op if the conversation was stopped or the generation has advanced.
     */
    fun resumeAfterNavInterrupt() {
        if (!_isActive.value) {
            Log.d(TAG, "resumeAfterNavInterrupt: conversation not active — skipped")
            return
        }
        val myGen = generation
        Log.i(TAG, "CONVERSATION_RESUMED_AFTER_NAV session=$myGen")
        // Clear the deadline so the next onReadyForSpeech creates a fresh 30-second window.
        clearInactivityWindow("nav-interrupt")
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
                if (infraRetryCount > 0) {
                    Log.i(TAG, "INFRA_RETRY_RESET reason=onReady session=$myGen prevCount=$infraRetryCount")
                    infraRetryCount = 0
                }
                _state.value = ConversationState.LISTENING
                // Open (or preserve) the user-response window.
                // This is the ONLY call site for openInactivityWindow.
                openInactivityWindow(myGen)
            }
        }

        speechManager.onBeginningOfSpeech = {
            if (isCurrentGen(myGen, "onBeginningOfSpeech")) {
                if (infraRetryCount > 0) {
                    Log.i(TAG, "INFRA_RETRY_RESET reason=onBeginning session=$myGen prevCount=$infraRetryCount")
                    infraRetryCount = 0
                }
                _state.value = ConversationState.USER_SPEAKING
                // User started speaking — cancel the timeout job but PRESERVE inactivityDeadlineMs.
                // If speech ends without a valid result (ERROR_NO_MATCH), the next onReadyForSpeech
                // calls openInactivityWindow(), finds the existing deadline, and schedules only the
                // remaining slice — not a fresh 30-second window.
                pauseInactivityJobWhileSpeaking(myGen)
            }
        }

        speechManager.onPartialResult = null  // not used in conversation mode

        speechManager.onResult = { text ->
            if (isCurrentGen(myGen, "onResult")) {
                Log.i(TAG, "USER_SPEECH_RESULT session=$myGen text='${text.take(80)}'")
                if (infraRetryCount > 0) {
                    Log.i(TAG, "INFRA_RETRY_RESET reason=onResults session=$myGen prevCount=$infraRetryCount")
                    infraRetryCount = 0
                }
                // Clear the deadline — this turn is complete.
                // A new window will be created after AI TTS finishes and onReadyForSpeech fires.
                clearInactivityWindow("results")
                handleResult(text, myGen)
            }
        }

        speechManager.onRecoverableError = { code ->
            if (isCurrentGen(myGen, "onRecoverableError code=$code")) {
                when {
                    RecoveryPolicy.isSilentRecovery(code) -> {
                        // ── Normal speech event — NEVER increments infraRetryCount ────────
                        // ERROR_NO_MATCH:       recognizer heard audio but found no match.
                        // ERROR_SPEECH_TIMEOUT: no speech in silence window.
                        //
                        // Do NOT touch inactivityDeadlineMs — the existing deadline is
                        // shared across all relisten cycles within this turn.  Check whether
                        // it has already expired before scheduling another relisten.
                        val logTag = if (code == RecoveryPolicy.E_NO_MATCH)
                            "NO_MATCH_RELISTEN" else "SPEECH_TIMEOUT_RELISTEN"
                        Log.i(TAG, "$logTag session=$myGen code=$code")

                        val now      = SystemClock.elapsedRealtime()
                        val deadline = inactivityDeadlineMs
                        if (deadline != null && now >= deadline) {
                            // Deadline already passed — close rather than relistening.
                            Log.i(TAG, "INACTIVITY_DEADLINE_EXPIRED session=$myGen")
                            Log.i(TAG, "RELISTEN_SKIPPED reason=inactivity-deadline-expired session=$myGen")
                            inactivityDeadlineMs = null
                            stopConversation(reason = "inactivity-timeout")
                        } else {
                            val delayMs = RecoveryPolicy.delayMs(code)
                            scope.launch {
                                delay(delayMs)
                                if (isCurrentGenValue(myGen)) requestRelisten(myGen, logTag.lowercase())
                            }
                        }
                    }
                    else -> {
                        // ── Infrastructure failure — counts toward MAX_INFRA_RETRIES ──────
                        // Preserve inactivityDeadlineMs — infra recovery time must not
                        // grant extra silence budget.  The existing deadline job continues
                        // to run; if it expires during recovery the session closes correctly.
                        infraRetryCount++
                        Log.w(TAG,
                            "INFRA_RETRY increment=$infraRetryCount session=$myGen " +
                            "code=$code (${RecoveryPolicy.errorName(code)})")
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
     * [scope] (viewModelScope) uses Dispatchers.Main.immediate — if already on Main it
     * executes synchronously; if on TTS background thread it queues on the Main Looper.
     */
    private fun requestRelisten(myGen: Long, reason: String) {
        pendingRelistenJob?.cancel()
        pendingRelistenJob = scope.launch {
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

    // ── Private: AI + TTS cycle ───────────────────────────────────────────

    private fun handleResult(text: String, myGen: Long) {
        Log.i(TAG, "AI_RESPONSE_STARTED session=$myGen text='${text.take(80)}'")
        // Belt-and-suspenders clear: onResult already cleared the window, but if this
        // path is ever reached without prior onResult, ensure the window is not running.
        clearInactivityWindow("ai-start")
        _state.value = ConversationState.THINKING

        aiJob?.cancel()
        aiJob = scope.launch {
            if (!isCurrentGenValue(myGen)) return@launch

            val navState        = latestNavState
            val historySnapshot = history.toList()

            val reply = askKentas(
                userText = text,
                navState = navState,
                apiKey   = apiKey,
                history  = historySnapshot,
            )

            if (!isCurrentGenValue(myGen)) return@launch

            history.add("user" to text)
            history.add("assistant" to reply)
            while (history.size > MAX_HISTORY) history.removeAt(0)

            _state.value = ConversationState.SPEAKING
            speechCoordinator.speakConversation(reply) {
                // onDone fires on TTS background thread — dispatch to Main via scope.launch.
                Log.i(TAG, "AI_RESPONSE_TTS_DONE session=$myGen")
                if (isCurrentGenValue(myGen)) {
                    // No deadline exists at this point (cleared on onResult/ai-start).
                    // Do NOT create one here — wait for the next onReadyForSpeech, which
                    // will call openInactivityWindow and create a fresh 30-second window.
                    requestRelisten(myGen, "ai-response-done")
                }
            }
        }
    }

    // ── Inactivity window (absolute deadline) ─────────────────────────────

    /**
     * Open (or preserve) the user-response inactivity window.
     *
     * Called ONLY from [onReadyForSpeech] — the first moment the user genuinely
     * has an opportunity to speak.
     *
     * ## If no deadline exists (fresh turn)
     *
     * Creates `inactivityDeadlineMs = now + INACTIVITY_TIMEOUT_MS`, schedules a
     * coroutine job for the full 30 s.  Logs INACTIVITY_WINDOW_CREATED.
     *
     * ## If a deadline already exists (relisten after NO_MATCH/infra)
     *
     * The deadline is the SAME one set by the first [onReadyForSpeech] of this turn.
     * Schedules a new job for only the **remaining** time (`deadline − now`).
     * This is the key invariant: NO_MATCH relisten cycles do not grant extra silence budget.
     * Logs INACTIVITY_WINDOW_PRESERVED remainingMs=...
     *
     * If `remaining ≤ 0` the deadline expired during the relisten cycle — closes immediately.
     */
    private fun openInactivityWindow(myGen: Long) {
        val now              = SystemClock.elapsedRealtime()
        val existingDeadline = inactivityDeadlineMs

        if (existingDeadline == null) {
            val deadline = now + INACTIVITY_TIMEOUT_MS
            inactivityDeadlineMs = deadline
            Log.i(TAG,
                "INACTIVITY_WINDOW_CREATED session=$myGen " +
                "deadlineInMs=$INACTIVITY_TIMEOUT_MS elapsedMs=$now deadlineAt=${now + INACTIVITY_TIMEOUT_MS}")
            scheduleInactivityJob(myGen, INACTIVITY_TIMEOUT_MS)
        } else {
            val remaining = existingDeadline - now
            Log.i(TAG,
                "INACTIVITY_WINDOW_PRESERVED session=$myGen " +
                "remainingMs=$remaining elapsedMs=$now deadlineAt=$existingDeadline")
            if (remaining <= 0L) {
                Log.i(TAG, "INACTIVITY_DEADLINE_EXPIRED session=$myGen elapsedMs=$now (immediate — window expired during relisten)")
                inactivityDeadlineMs = null
                stopConversation(reason = "inactivity-timeout")
            } else {
                scheduleInactivityJob(myGen, remaining)
            }
        }
    }

    /**
     * Schedule (or replace) the inactivity timeout coroutine.
     *
     * Cancels any existing [inactivityJob] before launching a new one so only one
     * deadline job is ever running per turn.  The job fires after [remainingMs] and
     * closes the conversation if the generation and deadline are still current.
     */
    private fun scheduleInactivityJob(myGen: Long, remainingMs: Long) {
        inactivityJob?.cancel()
        val scheduledAt = SystemClock.elapsedRealtime()
        Log.i(TAG,
            "INACTIVITY_TIMEOUT_SCHEDULED session=$myGen " +
            "remainingMs=$remainingMs scheduledAtElapsedMs=$scheduledAt " +
            "willFireAtElapsedMs=${scheduledAt + remainingMs}")
        inactivityJob = scope.launch {
            delay(remainingMs)
            val firedAt = SystemClock.elapsedRealtime()
            if (isCurrentGenValue(myGen) && inactivityDeadlineMs != null) {
                Log.i(TAG,
                    "INACTIVITY_DEADLINE_EXPIRED session=$myGen " +
                    "firedAtElapsedMs=$firedAt scheduledMs=$remainingMs")
                inactivityDeadlineMs = null
                inactivityJob = null   // self-clear before stopConversation to avoid double log
                stopConversation(reason = "inactivity-timeout")
            } else {
                Log.d(TAG,
                    "INACTIVITY_JOB_NOOP session=$myGen firedAt=$firedAt " +
                    "isCurrentGen=${isCurrentGenValue(myGen)} deadlineNull=${inactivityDeadlineMs == null}")
            }
        }
    }

    /**
     * Suspend the running timeout job while the user is actively speaking.
     *
     * Distinct from [clearInactivityWindow]:
     * - [clearInactivityWindow] nulls [inactivityDeadlineMs] → next [openInactivityWindow]
     *   creates a fresh 30-second window for the new turn.
     * - [pauseInactivityJobWhileSpeaking] cancels only the [inactivityJob] coroutine while
     *   **preserving** [inactivityDeadlineMs].  When speech ends without a valid result
     *   (ERROR_NO_MATCH), the next [onReadyForSpeech] → [openInactivityWindow] sees the
     *   existing deadline and schedules only the remaining slice — not a new 30-second window.
     *
     * Call site: [onBeginningOfSpeech] ONLY.
     */
    private fun pauseInactivityJobWhileSpeaking(myGen: Long) {
        val hadJob = inactivityJob?.isActive == true
        if (hadJob || inactivityDeadlineMs != null) {
            Log.i(TAG,
                "INACTIVITY_JOB_PAUSED_DURING_SPEECH session=$myGen " +
                "hadActiveJob=$hadJob deadlinePreservedMs=$inactivityDeadlineMs " +
                "— timeout paused while user speaks; deadline preserved for next onReadyForSpeech")
        }
        inactivityJob?.cancel()
        inactivityJob = null
        // CRITICAL: inactivityDeadlineMs is intentionally NOT cleared here.
        // The deadline resumes from the remaining slice on the next onReadyForSpeech.
    }

    /**
     * Clear the inactivity window — cancel the job and null the deadline.
     *
     * Called when a new conversational turn begins (user gave a valid result or AI starts)
     * and when the session closes for any reason.  After clearing, the next call to
     * [openInactivityWindow] (from [onReadyForSpeech] after AI TTS) will create a
     * fresh 30-second window for the new turn.
     *
     * NOT called from [onBeginningOfSpeech] — use [pauseInactivityJobWhileSpeaking] there
     * so that an ERROR_NO_MATCH following speech counts down from the original deadline.
     */
    private fun clearInactivityWindow(reason: String) {
        val hadDeadline = inactivityDeadlineMs != null
        val hadJob      = inactivityJob?.isActive == true
        if (hadDeadline || hadJob) {
            Log.i(TAG, "INACTIVITY_WINDOW_CLEARED session=$generation reason=$reason")
        }
        inactivityJob?.cancel()
        inactivityJob        = null
        inactivityDeadlineMs = null
    }

    // ── Generation helpers ────────────────────────────────────────────────

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
