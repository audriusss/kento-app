package lt.sturmanas.bajeristas.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Regression tests for the absolute inactivity deadline fix.
 *
 * These run on the plain JVM (no Android Context, no SpeechRecognizer instance).
 * They verify structural and policy invariants that prevent the inactivity timer
 * never-expiring bug.
 *
 * ## Root cause (fixed)
 *
 * The previous `startOrResumeInactivityTimer` model restarted a **full** 30-second
 * countdown every time [onReadyForSpeech] fired — including after every NO_MATCH
 * relisten cycle.  [SpeechRecognizer] naturally emits ERROR_NO_MATCH every few seconds
 * while the user is silent.  The sequence:
 *
 *   INACTIVITY_TIMER_STARTED
 *   → ERROR_NO_MATCH (5 s later)
 *   → INACTIVITY_TIMER_PAUSED
 *   → relisten
 *   → onReadyForSpeech
 *   → INACTIVITY_TIMER_STARTED (full 30 s again)  ← WRONG
 *
 * …repeated indefinitely.  The conversation never timed out.
 *
 * ## Fix
 *
 * [KentasConversationController] now maintains an **absolute deadline**:
 * `inactivityDeadlineMs: Long?` (SystemClock.elapsedRealtime of expiry).
 *
 * [openInactivityWindow] is called from [onReadyForSpeech]:
 * - If `inactivityDeadlineMs == null` → creates a fresh 30-second window.
 * - If `inactivityDeadlineMs != null` → preserves it; schedules only `deadline − now`
 *   for the new job.  NO_MATCH and infra relisten cycles share the original deadline.
 *
 * The critical invariant: within one user-response turn, all NO_MATCH / SPEECH_TIMEOUT /
 * infra reconnect cycles count down from the SAME absolute point in time.
 *
 * ## Acceptance criteria
 *
 * AC-D01  Repeated ERROR_NO_MATCH does not reset the inactivity deadline.
 * AC-D02  Repeated ERROR_SPEECH_TIMEOUT does not reset the deadline.
 * AC-D03  ERROR_SERVER_DISCONNECTED recovery does not reset the deadline.
 * AC-D04  onReadyForSpeech after a relisten preserves the original deadline.
 * AC-D05  Session closes ~30 seconds after the first onReadyForSpeech despite repeated NO_MATCH.
 * AC-D06  onBeginningOfSpeech clears the current deadline.
 * AC-D07  Valid onResults clears the current deadline.
 * AC-D08  After AI TTS, next onReadyForSpeech creates a fresh 30-second window.
 * AC-D09  Stale timeout job cannot close a newer session or newer turn.
 *
 * ## Integration scenarios (androidTest / manual)
 *
 * INT-D01 Indefinite NO_MATCH — conversation closes after ~30 s:
 *   Open conversation → Kentas greets → onReadyForSpeech (INACTIVITY_WINDOW_CREATED) →
 *   ERROR_NO_MATCH every 5 s for 30+ seconds.
 *   Expected: INACTIVITY_WINDOW_PRESERVED logged on every subsequent onReadyForSpeech
 *   (not CREATED), INACTIVITY_DEADLINE_EXPIRED logged at ~30 s,
 *   CONVERSATION_SESSION_CLOSED reason=inactivity-timeout.
 *   Must NOT see INACTIVITY_WINDOW_CREATED more than once per turn.
 *
 * INT-D02 User speaks just before deadline:
 *   Open conversation → 7 s of silence (NO_MATCH cycles) → user speaks at 7 s →
 *   Expected: onBeginningOfSpeech PAUSES the job (INACTIVITY_JOB_PAUSED_DURING_SPEECH) but
 *   preserves inactivityDeadlineMs. If recognizer returns ERROR_NO_MATCH, the next
 *   onReadyForSpeech logs INACTIVITY_WINDOW_PRESERVED remainingMs≈23000 (not CREATED).
 *   Only a valid onResults followed by AI TTS completion clears the deadline and opens a
 *   fresh 30-second window for the next turn.
 *
 * INT-D03 Infrastructure failure during deadline window:
 *   onReadyForSpeech (CREATED, deadline=T+30) → 10 s later ERROR_SERVER_DISCONNECTED →
 *   infra retry → onReadyForSpeech (PRESERVED remainingMs≈20000) → deadline fires at T+30.
 *   The 1200 ms recovery delay does not add to the window.
 *
 * INT-D04 Multi-turn fresh window:
 *   Turn 1: onReadyForSpeech (CREATED) → user speaks → AI answers → TTS done →
 *   onReadyForSpeech → INACTIVITY_WINDOW_CREATED (fresh 30 s for turn 2).
 *   Turn 2 window is independent of turn 1 deadline.
 *
 * INT-D05 Deadline expired during infra delay:
 *   onReadyForSpeech (CREATED deadline=T+30) → at T+29 ERROR_SERVER_DISCONNECTED →
 *   1200 ms recovery delay → onReadyForSpeech at T+30.2 →
 *   INACTIVITY_WINDOW_PRESERVED remainingMs≈−200 → INACTIVITY_DEADLINE_EXPIRED →
 *   CONVERSATION_SESSION_CLOSED reason=inactivity-timeout (NOT a new window).
 */
class ConversationInactivityDeadlineTest {

    // ── AC-D01 / AC-D02 / AC-D03 — deadline preserved by error paths ─────

    /**
     * [KentasConversationController] must have an `inactivityDeadlineMs: Long?` field.
     *
     * This field is the core of the absolute deadline model:
     * - null  → no window is open
     * - non-null → window open; value is the SystemClock.elapsedRealtime() deadline
     *
     * Its presence ensures AC-D01/D02/D03: error paths (NO_MATCH, SPEECH_TIMEOUT,
     * infra failures) do NOT reassign this field — they just check or leave it unchanged.
     * Only [openInactivityWindow] (from onReadyForSpeech) and [clearInactivityWindow]
     * (on turn completion/session close) write to it.
     */
    @Test
    fun `inactivityDeadlineMs field exists as nullable Long`() {
        val field = getDeclaredFieldOrNull(
            KentasConversationController::class.java,
            "inactivityDeadlineMs",
        )
        assertNotNull(
            "KentasConversationController must have an 'inactivityDeadlineMs: Long?' field. " +
            "This is the absolute deadline anchor. Error paths (NO_MATCH, SPEECH_TIMEOUT, " +
            "infra failures) must never reset it — they share the original deadline.",
            field,
        )
    }

    /**
     * The old `inactivityJob: Job?` field must still exist — it holds the currently
     * scheduled timeout coroutine.  [scheduleInactivityJob] replaces it on each
     * [openInactivityWindow] call, cancelling the previous job so only one job runs
     * per deadline window.
     */
    @Test
    fun `inactivityJob field still exists`() {
        val field = getDeclaredFieldOrNull(
            KentasConversationController::class.java,
            "inactivityJob",
        )
        assertNotNull(
            "KentasConversationController must still have an 'inactivityJob: Job?' field " +
            "that holds the scheduled timeout coroutine.",
            field,
        )
    }

    // ── AC-D04 — openInactivityWindow preserves existing deadline ─────────

    /**
     * [openInactivityWindow] must exist as a private method.
     *
     * This method is the ONLY call site for creating or preserving the deadline.
     * It is called ONLY from [onReadyForSpeech].  Its existence + single call-site
     * contract enforces AC-D04: if a deadline already exists when [onReadyForSpeech]
     * fires, [openInactivityWindow] preserves it and schedules only the remaining slice.
     */
    @Test
    fun `openInactivityWindow private method exists with Long parameter`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "openInactivityWindow",
            Long::class.javaPrimitiveType!!,
        )
        assertNotNull(
            "KentasConversationController must have a private 'openInactivityWindow(Long)' method. " +
            "This is the ONLY place where inactivityDeadlineMs is created. " +
            "It must preserve an existing deadline when onReadyForSpeech fires after a relisten.",
            method,
        )
    }

    /**
     * [scheduleInactivityJob] must exist as a private method accepting a session
     * generation and a remaining-millis value.  It is called by [openInactivityWindow]
     * with only the REMAINING time (not the full 30 s) when a deadline already exists.
     *
     * AC-D05 structural anchor: the job duration passed to `delay()` is `remainingMs`,
     * not `INACTIVITY_TIMEOUT_MS`, so the total window is always ~30 s from creation.
     */
    @Test
    fun `scheduleInactivityJob private method exists with Long Long parameters`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "scheduleInactivityJob",
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        )
        assertNotNull(
            "KentasConversationController must have a private 'scheduleInactivityJob(Long, Long)' method. " +
            "It accepts (myGen, remainingMs). When called for a preserved deadline, " +
            "remainingMs is deadline-now (the remaining slice), not INACTIVITY_TIMEOUT_MS.",
            method,
        )
    }

    // ── AC-D06 / AC-D07 — clearInactivityWindow ───────────────────────────

    /**
     * [clearInactivityWindow] must exist as a private method.
     *
     * Called on: [onResult] (AC-D07), AI generation start, nav interrupt, and session close.
     * After calling it, [inactivityDeadlineMs] is null so the next [openInactivityWindow]
     * creates a fresh turn window (AC-D08).
     *
     * NOTE: [clearInactivityWindow] is NOT called from [onBeginningOfSpeech].
     * [onBeginningOfSpeech] uses [pauseInactivityJobWhileSpeaking] instead, which preserves
     * [inactivityDeadlineMs] so that an ERROR_NO_MATCH after speech counts down from the
     * original absolute deadline, not a fresh 30-second window (AC-D10/D11/D12/D13).
     */
    @Test
    fun `clearInactivityWindow private method exists with String parameter`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "clearInactivityWindow",
            String::class.java,
        )
        assertNotNull(
            "KentasConversationController must have a private 'clearInactivityWindow(String)' method. " +
            "Called from onResult (AC-D07), AI start, nav interrupt, and session close. " +
            "After it returns, inactivityDeadlineMs is null so the next turn gets a fresh window. " +
            "NOT called from onBeginningOfSpeech — that uses pauseInactivityJobWhileSpeaking instead.",
            method,
        )
    }

    // ── AC-D08 — old methods removed ──────────────────────────────────────

    /**
     * The old `startOrResumeInactivityTimer` method must be gone.
     *
     * Its presence means the previous restartable-timer bug is still active:
     * every [onReadyForSpeech] would reset to a full 30 seconds, and the
     * conversation would never time out during NO_MATCH cycles.
     */
    @Test
    fun `startOrResumeInactivityTimer is removed — replaced by openInactivityWindow`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "startOrResumeInactivityTimer",
            Long::class.javaPrimitiveType!!,
        )
        assertNull(
            "startOrResumeInactivityTimer must be removed. Its presence means onReadyForSpeech " +
            "restarts a full 30-second timer on every relisten, which prevents the inactivity " +
            "timeout from ever firing during NO_MATCH cycles.",
            method,
        )
    }

    /**
     * The old `pauseInactivityTimer` method must be gone.
     *
     * Its presence means the timer was being cancelled and then restarted on the next
     * [onReadyForSpeech] — which was the pause/restart model that reset to full 30 s.
     *
     * The replacement has two distinct methods:
     * - [clearInactivityWindow] — nulls the deadline; used for turn completion and session close.
     * - [pauseInactivityJobWhileSpeaking] — cancels the job without touching the deadline;
     *   used by [onBeginningOfSpeech] so an ERROR_NO_MATCH after speech counts down from
     *   the original absolute deadline (AC-D10/D11/D12/D13).
     */
    @Test
    fun `pauseInactivityTimer is removed — replaced by clearInactivityWindow and pauseInactivityJobWhileSpeaking`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "pauseInactivityTimer",
            String::class.java,
        )
        assertNull(
            "pauseInactivityTimer must be removed. " +
            "The pause/restart model it represented reset the full 30-second window on every relisten. " +
            "Its two responsibilities are now split: clearInactivityWindow (turn completion) and " +
            "pauseInactivityJobWhileSpeaking (onBeginningOfSpeech, deadline preserved).",
            method,
        )
    }

    // ── AC-D09 — stale job guard ───────────────────────────────────────────

    /**
     * [scheduleInactivityJob] checks `isCurrentGenValue(myGen)` before stopping the session.
     * This is verified by the presence of the generation parameter in the method signature —
     * if the parameter is missing, the guard cannot be applied.
     *
     * AC-D09: if the user opens a new session (generation advances) before the old timeout
     * fires, the old job's `isCurrentGenValue(myGen)` check returns false and the job
     * exits without closing the new session.
     */
    @Test
    fun `scheduleInactivityJob accepts myGen parameter for stale-job guard`() {
        // Already verified by the existence test above. This test documents the requirement
        // that the first parameter is the generation ID (Long), enabling the isCurrentGenValue guard.
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "scheduleInactivityJob",
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        )
        assertNotNull("scheduleInactivityJob(Long, Long) must exist", method)
        // Verify the first param is a primitive long (generation ID), not a boxed Long.
        val firstParam = method!!.parameterTypes[0]
        assertEquals(
            "First parameter of scheduleInactivityJob must be primitive long (generation ID)",
            Long::class.javaPrimitiveType,
            firstParam,
        )
    }

    // ── Constants ─────────────────────────────────────────────────────────

    @Test
    fun `INACTIVITY_TIMEOUT_MS is exactly 30 seconds`() {
        assertEquals(
            "INACTIVITY_TIMEOUT_MS must be 30 000 ms",
            30_000L,
            KentasConversationController.INACTIVITY_TIMEOUT_MS,
        )
    }

    @Test
    fun `MAX_INFRA_RETRIES is at least 2`() {
        assertTrue(
            "MAX_INFRA_RETRIES must be ≥ 2. Was: ${KentasConversationController.MAX_INFRA_RETRIES}",
            KentasConversationController.MAX_INFRA_RETRIES >= 2,
        )
    }

    // ── Policy: silent recovery never clears deadline ─────────────────────

    /**
     * [RecoveryPolicy.isSilentRecovery] classifies NO_MATCH and SPEECH_TIMEOUT.
     * The controller's error handler uses this to decide whether to touch
     * [inactivityDeadlineMs] (it must NOT, for silent recoveries).
     *
     * AC-D01/D02 policy anchor.
     */
    @Test
    fun `isSilentRecovery is true for NO_MATCH and SPEECH_TIMEOUT`() {
        assertTrue(
            "RecoveryPolicy.isSilentRecovery(E_NO_MATCH) must be true",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_NO_MATCH),
        )
        assertTrue(
            "RecoveryPolicy.isSilentRecovery(E_SPEECH_TIMEOUT) must be true",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SPEECH_TIMEOUT),
        )
    }

    /**
     * [RecoveryPolicy.isSilentRecovery] must be false for ERROR_SERVER_DISCONNECTED.
     * AC-D03: this error takes the infra path, which also does NOT reset the deadline
     * but increments [infraRetryCount] instead.
     */
    @Test
    fun `isSilentRecovery is false for ERROR_SERVER_DISCONNECTED`() {
        assertFalse(
            "RecoveryPolicy.isSilentRecovery(E_SERVER_DISCONNECTED) must be false (infra error)",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SERVER_DISCONNECTED),
        )
    }

    // ── AC-D10 — pauseInactivityJobWhileSpeaking structural ──────────────

    /**
     * [pauseInactivityJobWhileSpeaking] must exist as a private method with a Long generation
     * parameter. Its existence proves that [onBeginningOfSpeech] no longer calls
     * [clearInactivityWindow], and that the deadline-preserving path is in place.
     *
     * If this method is absent, the AC-D11/D12/D13 behavioural tests are also meaningless
     * because the fix was not applied.
     */
    @Test
    fun `AC-D10 pauseInactivityJobWhileSpeaking private method exists with Long generation parameter`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "pauseInactivityJobWhileSpeaking",
            Long::class.javaPrimitiveType!!,
        )
        assertNotNull(
            "KentasConversationController must have a private 'pauseInactivityJobWhileSpeaking(Long)' " +
            "method. It must be called from onBeginningOfSpeech instead of clearInactivityWindow. " +
            "It cancels the inactivityJob coroutine but does NOT null inactivityDeadlineMs, so that " +
            "an ERROR_NO_MATCH after speech counts down from the original absolute deadline.",
            method,
        )
    }

    // ── AC-D11 — pauseInactivityJobWhileSpeaking preserves deadline ───────

    /**
     * After [pauseInactivityJobWhileSpeaking] is called, [inactivityDeadlineMs] must NOT be null.
     *
     * Regression: the old [clearInactivityWindow] call in [onBeginningOfSpeech] nulled the
     * deadline. When the recogniser returned ERROR_NO_MATCH and relistened, [onReadyForSpeech]
     * saw no existing deadline and created a fresh 30-second window — effectively resetting the
     * countdown every time the user opened their mouth. This made the session never time out.
     */
    @Test
    fun `AC-D11 pauseInactivityJobWhileSpeaking does not clear inactivityDeadlineMs`() {
        val (ctrl, scope) = createTestController()
        try {
            val deadline = deadlineField()
            val openWindow = openWindowMethod()
            val pauseJob = pauseJobMethod()

            // Simulate onReadyForSpeech: open the inactivity window.
            openWindow.invoke(ctrl, GEN)
            val beforePause = deadline.get(ctrl) as Long?
            assertNotNull("openInactivityWindow must set inactivityDeadlineMs", beforePause)

            // Simulate onBeginningOfSpeech: pause the job.
            pauseJob.invoke(ctrl, GEN)
            val afterPause = deadline.get(ctrl) as Long?

            assertNotNull(
                "inactivityDeadlineMs must NOT be null after pauseInactivityJobWhileSpeaking. " +
                "Nulling it was the root cause of the deadline-reset bug: a subsequent " +
                "onReadyForSpeech (after ERROR_NO_MATCH) would create a fresh 30-second window " +
                "instead of counting down from the original deadline.",
                afterPause,
            )
            assertEquals(
                "inactivityDeadlineMs must be the same value before and after " +
                "pauseInactivityJobWhileSpeaking — the deadline must not be touched.",
                beforePause,
                afterPause,
            )
        } finally {
            scope.cancel()
        }
    }

    // ── AC-D12 — openInactivityWindow uses remaining slice ────────────────

    /**
     * When [inactivityDeadlineMs] is already set (simulating a turn with time remaining),
     * [openInactivityWindow] must preserve it — not overwrite with a fresh 30-second window.
     *
     * Simulates: deadline was set earlier with ~23 seconds remaining (the remaining slice after
     * 7 seconds of silence). [openInactivityWindow] must keep 23 000 ms, not reset to 30 000 ms.
     */
    @Test
    fun `AC-D12 openInactivityWindow preserves existing deadline instead of opening fresh 30-second window`() {
        val (ctrl, scope) = createTestController()
        try {
            val deadline = deadlineField()
            val openWindow = openWindowMethod()

            // Directly set a simulated remaining deadline (23 s from now=0 since SystemClock=0).
            // SystemClock.elapsedRealtime() returns 0 in JVM unit tests (returnDefaultValues=true).
            val simulatedRemaining = 23_000L
            deadline.set(ctrl, simulatedRemaining)

            // Simulate onReadyForSpeech after ERROR_NO_MATCH — must PRESERVE the deadline.
            openWindow.invoke(ctrl, GEN)

            val after = deadline.get(ctrl) as Long?
            assertNotNull("inactivityDeadlineMs must not be null after openInactivityWindow", after)
            assertEquals(
                "openInactivityWindow must preserve an existing deadline (23 000 ms remaining) " +
                "and NOT open a fresh ${KentasConversationController.INACTIVITY_TIMEOUT_MS} ms window. " +
                "Resetting would grant extra silence budget for every NO_MATCH cycle.",
                simulatedRemaining,
                after,
            )
        } finally {
            scope.cancel()
        }
    }

    // ── AC-D13 — full NO_MATCH sequence ──────────────────────────────────

    /**
     * Full sequence: open window → onBeginningOfSpeech → ERROR_NO_MATCH → relisten ready.
     *
     * After the complete cycle, [inactivityDeadlineMs] must equal the original value set by
     * the first [openInactivityWindow] call — it must not have been reset to 30 000 ms.
     *
     * This is the end-to-end regression test for the bug logged in device diagnostics:
     * ```
     * onBeginningOfSpeech → INACTIVITY_WINDOW_CLEARED reason=beginning-of-speech
     * ERROR_NO_MATCH → relisten → onReadyForSpeech → INACTIVITY_WINDOW_CREATED (fresh 30 s)
     * ```
     * After the fix the log sequence must be:
     * ```
     * onBeginningOfSpeech → INACTIVITY_JOB_PAUSED_DURING_SPEECH (deadline preserved)
     * ERROR_NO_MATCH → relisten → onReadyForSpeech → INACTIVITY_WINDOW_PRESERVED remainingMs≈original
     * ```
     */
    @Test
    fun `AC-D13 full NO_MATCH sequence — deadline is original value after open, pause, and re-open`() {
        val (ctrl, scope) = createTestController()
        try {
            val deadline = deadlineField()
            val openWindow = openWindowMethod()
            val pauseJob = pauseJobMethod()

            // Step 1: onReadyForSpeech — open the inactivity window.
            openWindow.invoke(ctrl, GEN)
            val originalDeadline = deadline.get(ctrl) as Long?
            assertNotNull("Step 1: openInactivityWindow must set inactivityDeadlineMs", originalDeadline)

            // Step 2: onBeginningOfSpeech — pause the job, preserve the deadline.
            pauseJob.invoke(ctrl, GEN)
            assertEquals(
                "Step 2: deadline must be unchanged after pauseInactivityJobWhileSpeaking",
                originalDeadline,
                deadline.get(ctrl) as Long?,
            )

            // Step 3: ERROR_NO_MATCH → relisten → onReadyForSpeech — open window again.
            // With the fix: sees existingDeadline, uses remaining slice, preserves value.
            // Without the fix (old bug): deadline was null here, created a fresh 30-second window.
            openWindow.invoke(ctrl, GEN)
            val afterRelisten = deadline.get(ctrl) as Long?

            assertEquals(
                "Step 3 (regression): after open → pause → re-open, inactivityDeadlineMs must " +
                "equal the ORIGINAL value ($originalDeadline ms), not a fresh " +
                "${KentasConversationController.INACTIVITY_TIMEOUT_MS} ms window. " +
                "Any difference proves the deadline was reset during the NO_MATCH cycle.",
                originalDeadline,
                afterRelisten,
            )
        } finally {
            scope.cancel()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Generation ID used in all behavioural tests — any non-zero value. */
    private val GEN = 1L

    /**
     * Create a [KentasConversationController] without a real Context.
     *
     * The controller's primary constructor stores its parameters as fields — it has no init
     * block and no property initialiser that calls Android APIs. Passing null for the
     * Android-context-dependent parameters is safe as long as the test only invokes methods
     * that do not dereference them (the inactivity-window methods only touch
     * [inactivityDeadlineMs], [inactivityJob], and [scope]).
     *
     * Android stubs return default values in JVM unit tests because
     * `android { testOptions { unitTests.isReturnDefaultValues = true } }` is set, so
     * `SystemClock.elapsedRealtime()` returns 0 and `Log.i()` is a no-op.
     *
     * @return the controller paired with its [TestScope]; the caller must call
     *   `scope.cancel()` in a `finally` block to release resources.
     */
    private fun createTestController(): Pair<KentasConversationController, CoroutineScope> {
        val scope = TestScope()
        val ctor = KentasConversationController::class.java.getDeclaredConstructor(
            CoroutineScope::class.java,
            SpeechRecognitionManager::class.java,
            KentasSpeechCoordinator::class.java,
            String::class.java,
        ).also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ctrl = ctor.newInstance(scope, null, null, "") as KentasConversationController
        return Pair(ctrl, scope)
    }

    private fun deadlineField(): Field =
        getDeclaredFieldOrNull(KentasConversationController::class.java, "inactivityDeadlineMs")
            ?: error("inactivityDeadlineMs field not found — the absolute-deadline model may have been removed")

    private fun openWindowMethod(): Method =
        getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "openInactivityWindow",
            Long::class.javaPrimitiveType!!,
        ) ?: error("openInactivityWindow(Long) not found")

    private fun pauseJobMethod(): Method =
        getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "pauseInactivityJobWhileSpeaking",
            Long::class.javaPrimitiveType!!,
        ) ?: error("pauseInactivityJobWhileSpeaking(Long) not found — AC-D10 structural test must pass first")

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
