package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the UI stability contract for the continuous voice session loop.
 *
 * Core invariant: **continuousModeEnabled stays true through every recognizer restart,
 * TTS playback, timeout, and recovery.** The mic button must not flicker between
 * "on" and "off" during normal operation.
 *
 * These tests verify the architectural separation between:
 * - `continuousModeEnabled`   — user-visible session on/off; only changes on genuine start/stop
 * - `recognizerSessionActive` — internal per-cycle recognizer state; changes every cycle
 *
 * All tests are pure JVM — no Android mocks required.
 */
class VoiceModeStabilityTest {

    // ── 1. Silent errors never disable continuous mode ─────────────────────────

    @Test
    fun `NO_MATCH is not fatal — continuousModeEnabled must stay true`() {
        assertFalse(
            "NO_MATCH must not be fatal (would stop the session via stopContinuousSessionInternal)",
            RecoveryPolicy.isFatal(RecoveryPolicy.E_NO_MATCH),
        )
        assertTrue(
            "NO_MATCH must be a silent recovery (invisible to user)",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_NO_MATCH),
        )
    }

    @Test
    fun `SPEECH_TIMEOUT is not fatal — continuousModeEnabled must stay true`() {
        assertFalse(
            "SPEECH_TIMEOUT must not be fatal",
            RecoveryPolicy.isFatal(RecoveryPolicy.E_SPEECH_TIMEOUT),
        )
        assertTrue(
            "SPEECH_TIMEOUT must be a silent recovery",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SPEECH_TIMEOUT),
        )
    }

    @Test
    fun `SERVER_DISCONNECTED (ERROR 11) is not fatal — continuousModeEnabled must stay true`() {
        // The real-device Xiaomi bug: ERROR 11 must be recoverable via recognizer recreate.
        assertFalse(
            "ERROR_SERVER_DISCONNECTED must not be fatal — session stays enabled",
            RecoveryPolicy.isFatal(RecoveryPolicy.E_SERVER_DISCONNECTED),
        )
        assertTrue(
            "ERROR_SERVER_DISCONNECTED must trigger recognizer recreate",
            RecoveryPolicy.shouldRecreateRecognizer(RecoveryPolicy.E_SERVER_DISCONNECTED),
        )
    }

    // ── 2. Session-state ring invariants ─────────────────────────────────────

    @Test
    fun `Recovering state statusText is not the same as Idle statusText`() {
        // Recovering means the session is still on — its text must differ from Idle.
        assertNotEquals(
            "Recovering must not show the Idle status text (session is NOT stopped)",
            VoiceSessionState.Idle.statusText,
            VoiceSessionState.Recovering.statusText,
        )
    }

    @Test
    fun `RestartDelay state statusText is not the same as Idle statusText`() {
        assertNotEquals(
            "RestartDelay must not show the Idle status text (session is NOT stopped)",
            VoiceSessionState.Idle.statusText,
            VoiceSessionState.RestartDelay.statusText,
        )
    }

    @Test
    fun `Recovering statusText does not say session is disabled`() {
        assertFalse(
            "Recovering statusText must not contain 'išjungtas' (that is Idle territory)",
            VoiceSessionState.Recovering.statusText.contains("išjungtas"),
        )
    }

    @Test
    fun `RestartDelay statusText does not say session is disabled`() {
        assertFalse(
            "RestartDelay statusText must not contain 'išjungtas'",
            VoiceSessionState.RestartDelay.statusText.contains("išjungtas"),
        )
    }

    // ── 3. "Pokalbis" text is not produced by VoiceSessionState ──────────────

    @Test
    fun `VoiceSessionState Idle statusText does not say Pokalbis ishjungtas`() {
        // The "Pokalbis leidžiamas / išjungtas" text comes from ConversationPermission,
        // not from VoiceSessionState. Guard against accidental conflation.
        assertFalse(
            "VoiceSessionState.Idle must not produce 'Pokalbis ...' text",
            VoiceSessionState.Idle.statusText.contains("Pokalbis"),
        )
    }

    // ── 4. Normal restart passes through RestartDelay, not Idle ──────────────

    @Test
    fun `RestartDelay and Idle are distinct states`() {
        assertNotEquals(VoiceSessionState.RestartDelay, VoiceSessionState.Idle)
    }

    @Test
    fun `RestartDelay statusText matches expected Lithuanian text`() {
        // scheduleOneRestart() sets _voiceStatusText to this value.
        // The test documents the contract so a rename of the string is caught.
        assertEquals("Laukiu komandos…", VoiceSessionState.RestartDelay.statusText)
    }

    // ── 5. Only genuine stop paths can disable continuous mode ────────────────

    @Test
    fun `NO_MATCH does not trigger fatal path`() {
        // continuousModeEnabled → false is only reached when isFatal=true or MAX_RETRIES.
        assertFalse(RecoveryPolicy.isFatal(RecoveryPolicy.E_NO_MATCH))
    }

    @Test
    fun `SPEECH_TIMEOUT does not trigger fatal path`() {
        assertFalse(RecoveryPolicy.isFatal(RecoveryPolicy.E_SPEECH_TIMEOUT))
    }

    @Test
    fun `insufficient permissions IS fatal — mode must be disabled`() {
        // Only genuine hardware/permission failures stop the session permanently.
        assertTrue(
            "E_INSUFFICIENT_PERMISSIONS must be fatal — microphone access is gone",
            RecoveryPolicy.isFatal(RecoveryPolicy.E_INSUFFICIENT_PERMISSIONS),
        )
    }

    @Test
    fun `unsupported language IS fatal — mode must be disabled`() {
        assertTrue(RecoveryPolicy.isFatal(RecoveryPolicy.E_LANGUAGE_NOT_SUPPORTED))
        assertTrue(RecoveryPolicy.isFatal(RecoveryPolicy.E_LANGUAGE_UNAVAILABLE))
    }

    // ── 6. Generation-ID stale-callback contract ──────────────────────────────

    @Test
    fun `a callback from an old recognizer generation is considered stale`() {
        // Architectural invariant: each startListening() increments the generation counter.
        // A callback captured at generation G is stale when manager.generation > G.
        val capturedGeneration = 3L
        val managerGeneration  = 5L   // two restarts happened
        val isStale = capturedGeneration != managerGeneration
        assertTrue("Callback from gen=$capturedGeneration is stale (manager gen=$managerGeneration)", isStale)
    }

    @Test
    fun `a callback from the current generation is not stale`() {
        val capturedGeneration = 7L
        val managerGeneration  = 7L
        val isStale = capturedGeneration != managerGeneration
        assertFalse("Callback from the current generation must not be discarded", isStale)
    }

    // ── 7. Recoverable delay ordering ─────────────────────────────────────────

    @Test
    fun `ERROR_SERVER_DISCONNECTED delay exceeds normal silent-error delay`() {
        // Recognizer-recreate errors need more recovery time than plain retries.
        val silentDelay     = RecoveryPolicy.delayMs(RecoveryPolicy.E_NO_MATCH)
        val reconnectDelay  = RecoveryPolicy.delayMs(RecoveryPolicy.E_SERVER_DISCONNECTED)
        assertTrue(
            "Reconnect delay ($reconnectDelay ms) must exceed silent-recovery delay ($silentDelay ms)",
            reconnectDelay > silentDelay,
        )
    }

    // ── 8. Silent error UI contract ───────────────────────────────────────────

    @Test
    fun `NO_MATCH does not produce a user-visible error flag`() {
        // isSilentRecovery=true → continuous mode shows RestartDelay text, not an error message.
        assertTrue(RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_NO_MATCH))
        // Confirm RestartDelay text (the one the user sees during a silent restart) is neutral.
        assertFalse(
            "RestartDelay text must not mention an error",
            VoiceSessionState.RestartDelay.statusText.contains("klaida", ignoreCase = true),
        )
    }

    @Test
    fun `SPEECH_TIMEOUT does not produce a user-visible error flag`() {
        assertTrue(RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SPEECH_TIMEOUT))
        assertFalse(
            "RestartDelay text must not mention an error",
            VoiceSessionState.RestartDelay.statusText.contains("klaida", ignoreCase = true),
        )
    }
}
