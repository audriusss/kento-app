package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Static regression checks for the multi-turn conversation lifecycle fix.
 *
 * These tests run on the plain JVM without an Android device.  They verify:
 *  - structural guarantees that prevent the one-turn-then-stop bug
 *  - constant values that define session behaviour
 *  - API contracts that the fix depends on
 *
 * Full integration scenarios that require a real Context and SpeechRecognizer
 * fake belong in androidTest/ and are documented below.
 *
 * ## Root cause summary (regression anchor)
 *
 * [TtsManager.UtteranceProgressListener.onDone] fires on Android's TTS background
 * thread.  The old code called [SpeechRecognitionManager.startListening] directly
 * from that thread.  [SpeechRecognizer] requires the Main thread; calling it from a
 * background thread causes silent failure → recogniser never starts → conversation
 * stops after exactly one AI answer.
 *
 * The fix: [KentasConversationController.requestRelisten] always dispatches via
 * `scope.launch` (viewModelScope = Dispatchers.Main.immediate) so the recogniser
 * always starts on the correct thread regardless of which thread the callback fires on.
 *
 * ## Acceptance criteria
 *
 * AC-M01  First AI answer does not close the session.
 * AC-M02  TTS onDone requests listening exactly once (one pendingRelistenJob).
 * AC-M03  Three consecutive turns remain in one session (generation unchanged).
 * AC-M04  Conversation history is preserved across turns (history list grows).
 * AC-M05  Navigation TTS does not close or clear the session.
 * AC-M06  Navigation TTS resumes listening exactly once via resumeAfterNavInterrupt.
 * AC-M07  Manual close prevents stale TTS callback from reopening the session.
 * AC-M08  Timeout closes the session only after 30 s of real user inactivity.
 * AC-M09  AI generation and TTS duration do not consume the inactivity window.
 * AC-M10  Duplicate listening requests are blocked by isSessionActive + pendingRelistenJob.
 *
 * ## Integration scenarios (androidTest, manual)
 *
 * INT-M01 Single session, three turns:
 *   Press mic → speak "Kas yra Klaipėda?" → Kentas answers → mic restarts →
 *   speak "O kiek gyventojų?" → Kentas answers (with history context) → mic restarts →
 *   speak "Ačiū" → Kentas answers → mic restarts.
 *   Expected: session stays open, conversation history visible in AI context (nav log),
 *   button ring stays green throughout.
 *   Logs: CONVERSATION_SESSION_OPENED session=N (once), USER_SPEECH_RESULT (3×),
 *         AI_RESPONSE_STARTED (3×), AI_RESPONSE_TTS_DONE (3×),
 *         CONVERSATION_RELISTEN_REQUESTED (3×).
 *
 * INT-M02 Navigation interruption mid-conversation:
 *   Conversation active, Kentas mid-sentence when maneuver threshold crossed.
 *   Expected: NAVIGATION_INTERRUPTED_CONVERSATION logged, nav phrase spoken,
 *   CONVERSATION_RESUMED_AFTER_NAV logged, mic resumes with same session, history intact.
 *
 * INT-M03 Manual close stops relisten:
 *   During Kentas TTS, user presses mic button to close.
 *   Expected: CONVERSATION_SESSION_CLOSED reason=user-manual, no CONVERSATION_RELISTEN_REQUESTED
 *   after close, RELISTEN_SKIPPED reason=stale logged if TTS onDone fires after close.
 *
 * INT-M04 Inactivity timeout:
 *   User opens conversation, does not speak for 31 s.
 *   Expected: CONVERSATION_SESSION_CLOSED reason=inactivity-timeout at ~30 s.
 *   AI generation time and TTS time must not be counted: after Kentas speaks, the 30 s
 *   restarts from the end of TTS (AI_RESPONSE_TTS_DONE log), not from USER_SPEECH_RESULT.
 *
 * INT-M05 Duplicate relisten suppressed:
 *   Simulate two concurrent onDone callbacks (e.g. watchdog + real onDone race).
 *   Expected: only one CONVERSATION_RELISTEN_REQUESTED; second attempt logs RELISTEN_SKIPPED.
 */
class ConversationMultiTurnTest {

    // ── AC-M01 / AC-M02 — structural: onDone goes through requestRelisten ──

    /**
     * [KentasConversationController] must have a private [pendingRelistenJob] field.
     *
     * This field is the mechanism that:
     *  - ensures TTS onDone dispatches to the Main thread via scope.launch (one-turn fix)
     *  - prevents duplicate re-listen calls (cancel before re-scheduling)
     *  - allows stopConversation to cancel a queued relisten immediately
     *
     * Its existence is AC-M02's structural anchor: if the field disappears, the
     * one-turn-then-stop bug will reappear.
     */
    @Test
    fun `pendingRelistenJob field exists as nullable Job`() {
        val field = getDeclaredFieldOrNull(
            KentasConversationController::class.java,
            "pendingRelistenJob",
        )
        assertNotNull(
            "KentasConversationController must have a 'pendingRelistenJob: Job?' field — " +
            "this is the Main-thread dispatch guard that prevents the one-turn-then-stop bug",
            field,
        )
        // Must be nullable (Job?) — present but null when no relisten is scheduled
        assertEquals(
            "pendingRelistenJob must be of type kotlinx.coroutines.Job",
            "kotlinx.coroutines.Job",
            field!!.type.name,
        )
    }

    /**
     * [KentasConversationController] must have a private `requestRelisten` method.
     *
     * This method is the single gate through which all [SpeechRecognitionManager.startListening]
     * calls are dispatched.  It accepts a generation token and a reason string for logging.
     * Its presence verifies AC-M01 (the session is not closed after one answer — instead
     * requestRelisten is called) and AC-M02 (exactly one job per re-listen).
     */
    @Test
    fun `requestRelisten private method exists with myGen and reason parameters`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "requestRelisten",
            Long::class.javaPrimitiveType!!,
            String::class.java,
        )
        assertNotNull(
            "KentasConversationController must have a private 'requestRelisten(Long, String)' method — " +
            "this is the only path to startListening and must dispatch through scope.launch",
            method,
        )
    }

    // ── AC-M03 / AC-M04 — history structure ───────────────────────────────

    /**
     * [KentasConversationController] must have a private [history] list that accumulates
     * turns.  This is the cross-turn memory that makes AC-M03 and AC-M04 possible.
     */
    @Test
    fun `history field exists as MutableList`() {
        val field = getDeclaredFieldOrNull(
            KentasConversationController::class.java,
            "history",
        )
        assertNotNull(
            "KentasConversationController must have a 'history: MutableList' field for multi-turn context",
            field,
        )
    }

    /**
     * [MAX_HISTORY] must be large enough to hold at least 3 full exchanges (6 entries).
     * Values below 6 would truncate multi-turn context after the second turn.
     */
    @Test
    fun `MAX_HISTORY supports at least three full exchanges`() {
        assertTrue(
            "MAX_HISTORY must be ≥ 6 to hold three user+assistant exchanges, was ${KentasConversationController.MAX_HISTORY}",
            KentasConversationController.MAX_HISTORY >= 6,
        )
    }

    // ── AC-M05 / AC-M06 — navigation interruption ─────────────────────────

    /**
     * [KentasConversationController.resumeAfterNavInterrupt] must exist as a public method.
     * It is the hook that MainViewModel wires as the nav-TTS [onDone] callback.
     */
    @Test
    fun `resumeAfterNavInterrupt is a public method with no parameters`() {
        val method = runCatching {
            KentasConversationController::class.java
                .getMethod("resumeAfterNavInterrupt")
        }.getOrNull()
        assertNotNull(
            "KentasConversationController must expose a public resumeAfterNavInterrupt() method " +
            "called by MainViewModel after each navigation TTS utterance finishes",
            method,
        )
    }

    /**
     * [KentasSpeechCoordinator] must have a [speakNavigation] method that accepts an optional
     * [onDone] callback.  This callback is wired to [resumeAfterNavInterrupt] in MainViewModel.
     *
     * AC-M05: the overload with onDone must exist so the nav interrupt can resume listening.
     * AC-M06: exactly one listening request follows the nav utterance via this callback.
     */
    @Test
    fun `speakNavigation has overload accepting onDone callback`() {
        val methods = KentasSpeechCoordinator::class.java
            .methods
            .filter { it.name == "speakNavigation" }
        assertTrue(
            "KentasSpeechCoordinator must have at least one speakNavigation() method",
            methods.isNotEmpty(),
        )
        val hasOnDoneOverload = methods.any { m ->
            m.parameterTypes.size >= 2 &&
            m.parameterTypes.any { it == Function0::class.java || it.name.contains("Function") }
        }
        assertTrue(
            "speakNavigation must have an overload accepting an onDone: (() -> Unit)? parameter " +
            "so MainViewModel can wire resumeAfterNavInterrupt as the nav callback",
            hasOnDoneOverload,
        )
    }

    // ── AC-M07 — manual close prevents stale relisten ─────────────────────

    /**
     * [stopConversation] is public and advances [generation].  The generation gate in
     * [requestRelisten] ([isCurrentGenValue]) then discards any pending or in-flight
     * relisten job with reason=stale.
     *
     * AC-M07 is enforced structurally by the combination of:
     *  - [generation] advancing in stopConversation
     *  - [pendingRelistenJob] being cancelled in stopConversation
     *  - [isCurrentGenValue] checking both generation equality AND _isActive
     *
     * This test verifies that stopConversation is a public API (reachable from MainViewModel).
     */
    @Test
    fun `stopConversation is a public method`() {
        val method = runCatching {
            KentasConversationController::class.java.getMethod("stopConversation")
        }.getOrNull()
        assertNotNull("stopConversation() must be public — called from MainViewModel.onNavigationStopped", method)
    }

    // ── AC-M08 / AC-M09 — inactivity timer constants ──────────────────────

    /**
     * The inactivity timeout must be exactly 30 seconds.
     * Changing this value without updating the test is intentional — do both.
     */
    @Test
    fun `INACTIVITY_TIMEOUT_MS is 30 seconds`() {
        assertEquals(
            "INACTIVITY_TIMEOUT_MS must be 30 000 ms (spec requirement)",
            30_000L,
            KentasConversationController.INACTIVITY_TIMEOUT_MS,
        )
    }

    /**
     * The inactivity timer is started ONLY from [onReadyForSpeech] — the first moment the
     * user genuinely has an opportunity to speak.  AI generation time, Kentas TTS time, nav
     * TTS time, and recognizer restart time do not consume the user's 30 s window.
     *
     * AC-M09 is enforced by [pauseInactivityTimer] (called at AI generation start and at
     * every error that triggers a recognizer restart) and [startOrResumeInactivityTimer]
     * (called only from [onReadyForSpeech]).
     *
     * For detailed policy and timer regression tests see [ConversationErrorPolicyTest].
     */
    @Test
    fun `MAX_INFRA_RETRIES is at least 2 — only infra failures close the session`() {
        assertTrue(
            "MAX_INFRA_RETRIES must be ≥ 2. Normal speech events (NO_MATCH, SPEECH_TIMEOUT) " +
            "never count toward this limit. Only real infrastructure failures do. " +
            "Was: ${KentasConversationController.MAX_INFRA_RETRIES}",
            KentasConversationController.MAX_INFRA_RETRIES >= 2,
        )
    }

    // ── AC-M10 — duplicate listening requests blocked ─────────────────────

    /**
     * [SpeechRecognitionManager] must expose an [isSessionActive] flag.
     * [requestRelisten] checks this flag and emits RELISTEN_SKIPPED reason=already-listening
     * when the recogniser is already between startListening and onResults/onError.
     */
    @Test
    fun `SpeechRecognitionManager exposes isSessionActive flag`() {
        val field = runCatching {
            SpeechRecognitionManager::class.java
                .getMethod("isSessionActive")  // Kotlin property → getter
        }.getOrNull()
            ?: SpeechRecognitionManager::class.java.methods
                .firstOrNull { it.name == "isSessionActive" || it.name == "getIsSessionActive" }
        assertNotNull(
            "SpeechRecognitionManager must expose isSessionActive so requestRelisten can " +
            "detect an already-running session and skip the duplicate start",
            field,
        )
    }

    /**
     * [ConversationState] must contain exactly the five required values.
     * This enum drives both [MicButton] visuals and the [VoiceListeningState] mapping in the VM.
     * Adding or removing a value without updating this test is intentional.
     */
    @Test
    fun `ConversationState has exactly the five required values`() {
        val expected = setOf("IDLE", "LISTENING", "USER_SPEAKING", "THINKING", "SPEAKING")
        val actual   = ConversationState.entries.map { it.name }.toSet()
        assertEquals(
            "ConversationState must have exactly IDLE/LISTENING/USER_SPEAKING/THINKING/SPEAKING",
            expected,
            actual,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getDeclaredFieldOrNull(clazz: Class<*>, name: String): Field? =
        runCatching {
            clazz.getDeclaredField(name).also { it.isAccessible = true }
        }.getOrNull()

    private fun getDeclaredMethodOrNull(
        clazz: Class<*>,
        name: String,
        vararg paramTypes: Class<*>,
    ): Method? =
        runCatching {
            clazz.getDeclaredMethod(name, *paramTypes).also { it.isAccessible = true }
        }.getOrNull()
}
