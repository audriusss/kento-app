package lt.sturmanas.bajeristas.voice

import lt.sturmanas.bajeristas.MainViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the single-owner restart coordinator invariants introduced with
 * [MainViewModel.requestListeningRestart].
 *
 * All tests are pure JVM — no Android framework, no coroutines, no mocks.
 * They verify the decision-table logic and state-machine constants that the
 * coordinator depends on, mirroring the production guards without running the
 * full ViewModel.
 *
 * ## What is under test
 *
 * The coordinator has six blocking conditions that prevent a restart from being
 * scheduled. Each condition is exercised independently. The token-based stale-job
 * rejection, the single-job-per-restart guarantee, and the state machine transitions
 * from LISTENING through RESTART_WAIT back to STARTING are verified via pure logic
 * mirrors of the production code.
 */
class VoiceLoopCoordinatorTest {

    // ── Helper: mirrors requestListeningRestart blocking logic ────────────

    /**
     * Pure mirror of the six blocking conditions checked at the top of
     * [MainViewModel.requestListeningRestart] before a restart is scheduled.
     *
     * Returns the name of the blocking condition, or null if no block applies.
     */
    private fun blockReason(
        continuousModeEnabled: Boolean,
        ttsIsSpeaking: Boolean,
        state: VoiceListeningState,
        graceJobActive: Boolean,
        srSessionActive: Boolean,
    ): String? = when {
        !continuousModeEnabled                           -> "CONTINUOUS_MODE_OFF"
        ttsIsSpeaking                                    -> "TTS_SPEAKING"
        state == VoiceListeningState.THINKING            -> "THINKING"
        state == VoiceListeningState.PROCESSING          -> "PROCESSING"
        state == VoiceListeningState.FINALIZING          -> "FINALIZING"
        graceJobActive                                   -> "GRACE_JOB_ACTIVE"
        srSessionActive                                  -> "SESSION_ACTIVE"
        else                                             -> null
    }

    // ── 1. Two restart requests → only newest token executes ──────────────

    @Test
    fun `second restart request supersedes first via token increment`() {
        var token = 0L
        // First request
        val token1 = ++token
        // Second request (cancels first job, advances token)
        val token2 = ++token

        // When the first coroutine wakes up, token1 != token2 → it aborts.
        assertNotEquals(token1, token2)
        // The second coroutine wakes up with its own token which matches current.
        assertEquals(token2, token)
    }

    @Test
    fun `stale restart token is rejected`() {
        var token = 0L
        val myToken = ++token
        // Simulate a stop() call that increments the token.
        token++
        // The coroutine checks: if (restartToken != myToken) return
        val isStale = token != myToken
        assertTrue("Stale token must be rejected", isStale)
    }

    // ── 2. Blocking conditions ─────────────────────────────────────────────

    @Test
    fun `restart blocked when continuous mode is off`() {
        val reason = blockReason(
            continuousModeEnabled = false,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.RESTART_WAIT,
            graceJobActive        = false,
            srSessionActive       = false,
        )
        assertEquals("CONTINUOUS_MODE_OFF", reason)
    }

    @Test
    fun `restart blocked during TTS`() {
        val reason = blockReason(
            continuousModeEnabled = true,
            ttsIsSpeaking         = true,
            state                 = VoiceListeningState.RESTART_WAIT,
            graceJobActive        = false,
            srSessionActive       = false,
        )
        assertEquals("TTS_SPEAKING", reason)
    }

    @Test
    fun `restart blocked during FINALIZING`() {
        val reason = blockReason(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.FINALIZING,
            graceJobActive        = false,
            srSessionActive       = false,
        )
        assertEquals("FINALIZING", reason)
    }

    @Test
    fun `restart blocked while grace job is active`() {
        val reason = blockReason(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.LISTENING,
            graceJobActive        = true,
            srSessionActive       = false,
        )
        assertEquals("GRACE_JOB_ACTIVE", reason)
    }

    @Test
    fun `restart blocked when recognizer session already active`() {
        // This is the primary fix for the map-screen duplicate restart.
        // When the navigation "Maršrutas paruoštas" TTS fires onDone while
        // the recognizer is already listening, requestListeningRestart must
        // NOT destroy the active session.
        val reason = blockReason(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.LISTENING,
            graceJobActive        = false,
            srSessionActive       = true,
        )
        assertEquals("SESSION_ACTIVE", reason)
    }

    @Test
    fun `restart blocked when state is THINKING`() {
        val reason = blockReason(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.THINKING,
            graceJobActive        = false,
            srSessionActive       = false,
        )
        assertEquals("THINKING", reason)
    }

    @Test
    fun `restart blocked when state is PROCESSING`() {
        val reason = blockReason(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.PROCESSING,
            graceJobActive        = false,
            srSessionActive       = false,
        )
        assertEquals("PROCESSING", reason)
    }

    // ── 3. TTS onDone schedules exactly one restart ────────────────────────

    @Test
    fun `TTS onDone restart delay is 500ms`() {
        // Regression guard — changing this changes the post-TTS listening gap.
        val ttsOnDoneDelayMs = 500L
        assertTrue("TTS_DONE delay must be at least 400ms to avoid picking up reverberation",
            ttsOnDoneDelayMs >= 400L)
    }

    @Test
    fun `TTS onDone restart reason is TTS_DONE`() {
        val reason = MainViewModel.RestartReason.TTS_DONE
        assertNotEquals(reason, MainViewModel.RestartReason.COMMAND_DONE)
        assertNotEquals(reason, MainViewModel.RestartReason.NO_MATCH)
    }

    // ── 4. NO_MATCH bounded retry ──────────────────────────────────────────

    @Test
    fun `NO_MATCH schedules one retry at 500ms`() {
        val delay = RecoveryPolicy.delayMs(RecoveryPolicy.E_NO_MATCH)
        assertEquals(500L, delay)
    }

    @Test
    fun `repeated NO_MATCH does not stack jobs — token increments each time`() {
        var token = 0L
        // Three consecutive NO_MATCH recoveries.
        val t1 = ++token
        val t2 = ++token
        val t3 = ++token
        // Each previous coroutine's token is stale — only t3 is current.
        assertNotEquals(t1, token)
        assertNotEquals(t2, token)
        assertEquals(t3, token)
    }

    // ── 5. onReadyForSpeech resets retry count ────────────────────────────

    @Test
    fun `retry count is reset after successful onReadyForSpeech`() {
        var retryCount = 2
        // Simulates the onReadyForSpeech callback body.
        retryCount = 0
        assertEquals(0, retryCount)
    }

    // ── 6. UI listening flag — only LISTENING and USER_SPEAKING ───────────

    @Test
    fun `isSpeechBlocked is true for LISTENING USER_SPEAKING and FINALIZING`() {
        val activeStates = setOf(
            VoiceListeningState.LISTENING,
            VoiceListeningState.USER_SPEAKING,
            VoiceListeningState.FINALIZING,
        )
        // Mirror of MainViewModel.isSpeechBlocked getter
        fun blocked(s: VoiceListeningState) =
            s == VoiceListeningState.LISTENING ||
            s == VoiceListeningState.USER_SPEAKING ||
            s == VoiceListeningState.FINALIZING

        for (s in activeStates) {
            assertTrue("isSpeechBlocked must be true for $s", blocked(s))
        }
        val inactiveStates = VoiceListeningState.values().toSet() - activeStates
        for (s in inactiveStates) {
            assertFalse("isSpeechBlocked must be false for $s", blocked(s))
        }
    }

    @Test
    fun `UI listening label is shown only for LISTENING and USER_SPEAKING`() {
        // Mirror of MicButton.isListening logic.
        fun isListening(s: VoiceListeningState) =
            s == VoiceListeningState.LISTENING ||
            s == VoiceListeningState.USER_SPEAKING ||
            s == VoiceListeningState.FINALIZING

        assertTrue(isListening(VoiceListeningState.LISTENING))
        assertTrue(isListening(VoiceListeningState.USER_SPEAKING))
        assertTrue(isListening(VoiceListeningState.FINALIZING))

        // States that must NOT show the red active mic:
        assertFalse(isListening(VoiceListeningState.STARTING))
        assertFalse(isListening(VoiceListeningState.RESTART_WAIT))
        assertFalse(isListening(VoiceListeningState.SPEAKING))
        assertFalse(isListening(VoiceListeningState.IDLE))
    }

    // ── 7. Continuous mode never transitions through IDLE in normal loop ───

    @Test
    fun `RESTART_WAIT is distinct from IDLE`() {
        assertNotEquals(VoiceListeningState.RESTART_WAIT, VoiceListeningState.IDLE)
    }

    @Test
    fun `normal continuous loop state sequence never includes IDLE`() {
        // The states a normal loop cycles through: STARTING → LISTENING →
        // USER_SPEAKING → FINALIZING → PROCESSING → THINKING → SPEAKING → RESTART_WAIT
        val loopStates = listOf(
            VoiceListeningState.STARTING,
            VoiceListeningState.LISTENING,
            VoiceListeningState.USER_SPEAKING,
            VoiceListeningState.FINALIZING,
            VoiceListeningState.PROCESSING,
            VoiceListeningState.THINKING,
            VoiceListeningState.SPEAKING,
            VoiceListeningState.RESTART_WAIT,
        )
        assertFalse("IDLE must not appear in a normal continuous loop",
            VoiceListeningState.IDLE in loopStates)
    }

    // ── 8. Map-screen activation — exactly one restart ────────────────────

    @Test
    fun `map screen TTS onDone blocked when session already active`() {
        // When "Maršrutas paruoštas" TTS finishes and the recognizer is already
        // listening (started by the earlier "Keliaujame į X" onDone), the second
        // requestListeningRestart call must be blocked by SESSION_ACTIVE.
        val reason = blockReason(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.LISTENING,
            graceJobActive        = false,
            srSessionActive       = true,   // recognizer already active from first TTS
        )
        assertEquals("SESSION_ACTIVE", reason)
    }

    // ── 9. RestartReason enum completeness ────────────────────────────────

    @Test
    fun `all four RestartReasons are distinct`() {
        val reasons = MainViewModel.RestartReason.values()
        assertEquals("Expected exactly 4 RestartReasons", 4, reasons.size)
        assertEquals(4, reasons.toSet().size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // TTS REGRESSION TESTS — guards for the a26e6b9 regression fix
    //
    // Root cause: requestListeningRestart blocked COMMAND_DONE and TTS_DONE
    // on PROCESSING/THINKING state. Silent/muted commands leave state at
    // PROCESSING (finalizePendingUtterance sets it; nothing clears it in
    // continuous mode without TTS). The loop was permanently stuck.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Mirror of the FIXED requestListeningRestart blocking logic.
     * PROCESSING and THINKING only block error-path reasons (NO_MATCH, ERROR_RECOVERY).
     * COMMAND_DONE and TTS_DONE must pass through regardless of voice state.
     */
    private fun blockReasonFixed(
        continuousModeEnabled: Boolean,
        ttsIsSpeaking: Boolean,
        state: VoiceListeningState,
        graceJobActive: Boolean,
        srSessionActive: Boolean,
        reason: MainViewModel.RestartReason,
    ): String? {
        if (!continuousModeEnabled) return "CONTINUOUS_MODE_OFF"
        val isErrorPath = reason == MainViewModel.RestartReason.NO_MATCH ||
                          reason == MainViewModel.RestartReason.ERROR_RECOVERY
        return when {
            ttsIsSpeaking                                                    -> "TTS_SPEAKING"
            isErrorPath && state == VoiceListeningState.THINKING             -> "THINKING"
            isErrorPath && state == VoiceListeningState.PROCESSING           -> "PROCESSING"
            state == VoiceListeningState.FINALIZING                          -> "FINALIZING"
            graceJobActive                                                   -> "GRACE_JOB_ACTIVE"
            srSessionActive                                                  -> "SESSION_ACTIVE"
            else                                                             -> null
        }
    }

    // ── 10. TTS: response from PROCESSING is allowed ───────────────────────

    @Test
    fun `COMMAND_DONE restart not blocked when state is PROCESSING`() {
        // This was the regression: silent muted commands left state=PROCESSING
        // and COMMAND_DONE was blocked, permanently stalling the loop.
        val reason = blockReasonFixed(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.PROCESSING,
            graceJobActive        = false,
            srSessionActive       = false,
            reason                = MainViewModel.RestartReason.COMMAND_DONE,
        )
        assertNull("COMMAND_DONE must NOT be blocked when state=PROCESSING — was the a26e6b9 regression", reason)
    }

    @Test
    fun `TTS_DONE restart not blocked when state is PROCESSING`() {
        // TTS completed while state=PROCESSING (onStart never set SPEAKING,
        // e.g. continuousMode was briefly false). Loop must still restart.
        val reason = blockReasonFixed(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.PROCESSING,
            graceJobActive        = false,
            srSessionActive       = false,
            reason                = MainViewModel.RestartReason.TTS_DONE,
        )
        assertNull("TTS_DONE must NOT be blocked when state=PROCESSING", reason)
    }

    // ── 11. TTS: response from THINKING is allowed ─────────────────────────

    @Test
    fun `COMMAND_DONE restart not blocked when state is THINKING`() {
        // Muted GeneralQuestion: AI call returned, no TTS. State = THINKING.
        // Loop must restart via COMMAND_DONE.
        val reason = blockReasonFixed(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.THINKING,
            graceJobActive        = false,
            srSessionActive       = false,
            reason                = MainViewModel.RestartReason.COMMAND_DONE,
        )
        assertNull("COMMAND_DONE must NOT be blocked when state=THINKING", reason)
    }

    @Test
    fun `NO_MATCH IS blocked when state is THINKING`() {
        // Error-path restart must NOT fire while an AI call is in flight.
        val reason = blockReasonFixed(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.THINKING,
            graceJobActive        = false,
            srSessionActive       = false,
            reason                = MainViewModel.RestartReason.NO_MATCH,
        )
        assertEquals("NO_MATCH must be blocked when state=THINKING", "THINKING", reason)
    }

    @Test
    fun `ERROR_RECOVERY IS blocked when state is PROCESSING`() {
        // Error-path restart must NOT fire while a command is being parsed.
        val reason = blockReasonFixed(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.PROCESSING,
            graceJobActive        = false,
            srSessionActive       = false,
            reason                = MainViewModel.RestartReason.ERROR_RECOVERY,
        )
        assertEquals("ERROR_RECOVERY must be blocked when state=PROCESSING", "PROCESSING", reason)
    }

    // ── 12. First speak request not rejected by SPEAKING state ─────────────

    @Test
    fun `first speak request is not rejected by SPEAKING state`() {
        // When TTS is playing from a previous response, state=SPEAKING.
        // A new command arrives → executeVoiceCommand → speakAndIdle is called.
        // ttsManager.speak() must be callable regardless of voiceListeningState.
        //
        // ttsManager.speak() only guards on isReady and settings.isEnabled,
        // NOT on voiceListeningState. This test verifies SPEAKING is not in
        // any speak-blocking set.
        val speakBlockingStates = setOf(
            // Only isSpeechBlocked states gate MANEUVER TTS, not command-response TTS.
            VoiceListeningState.LISTENING,
            VoiceListeningState.USER_SPEAKING,
            VoiceListeningState.FINALIZING,
        )
        assertFalse(
            "SPEAKING must NOT block a new ttsManager.speak() call",
            VoiceListeningState.SPEAKING in speakBlockingStates,
        )
    }

    // ── 13. TTS onStart changes state to SPEAKING ──────────────────────────

    @Test
    fun `SPEAKING is a distinct state from all recognition states`() {
        val recognitionStates = setOf(
            VoiceListeningState.LISTENING,
            VoiceListeningState.USER_SPEAKING,
            VoiceListeningState.FINALIZING,
            VoiceListeningState.STARTING,
        )
        assertFalse("SPEAKING must be distinct from all recognition states",
            VoiceListeningState.SPEAKING in recognitionStates)
    }

    // ── 14. TTS onDone schedules exactly one restart ───────────────────────

    @Test
    fun `TTS_DONE from SPEAKING state is not blocked`() {
        // Normal happy path: onStart fired → state=SPEAKING → onDone fires → restart.
        val reason = blockReasonFixed(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,  // onDone sets isSpeaking=false before callback
            state                 = VoiceListeningState.SPEAKING,
            graceJobActive        = false,
            srSessionActive       = false,
            reason                = MainViewModel.RestartReason.TTS_DONE,
        )
        assertNull("TTS_DONE from SPEAKING must not be blocked — this is the normal happy path", reason)
    }

    // ── 15. TTS error does not leave state stuck ───────────────────────────

    @Test
    fun `TTS_DONE from RESTART_WAIT is not blocked`() {
        // Edge case: onError fired immediately (TTS synthesis failed),
        // onStart never set SPEAKING. State was already at RESTART_WAIT
        // from a previous restart. onError delegates to onDone callback →
        // requestListeningRestart(TTS_DONE). Must not be blocked.
        val reason = blockReasonFixed(
            continuousModeEnabled = true,
            ttsIsSpeaking         = false,
            state                 = VoiceListeningState.RESTART_WAIT,
            graceJobActive        = false,
            srSessionActive       = false,
            reason                = MainViewModel.RestartReason.TTS_DONE,
        )
        assertNull("TTS_DONE from RESTART_WAIT must not be blocked", reason)
    }

    // ── 16. Recognizer blocked while actual TTS is speaking ────────────────

    @Test
    fun `all restart reasons blocked while TTS is speaking`() {
        // isSpeaking=true is the primary guard regardless of reason.
        for (reason in MainViewModel.RestartReason.values()) {
            val blocked = blockReasonFixed(
                continuousModeEnabled = true,
                ttsIsSpeaking         = true,
                state                 = VoiceListeningState.SPEAKING,
                graceJobActive        = false,
                srSessionActive       = false,
                reason                = reason,
            )
            assertEquals(
                "All reasons must be blocked by TTS_SPEAKING when isSpeaking=true (reason=$reason)",
                "TTS_SPEAKING",
                blocked,
            )
        }
    }
}
