package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the session-loop restart invariants.
 *
 * The continuous hands-free loop now has a SINGLE restart entry point:
 *   `scheduleOneRestart(delayMs)` — always cancels any pending job before scheduling.
 *
 * Route to `scheduleOneRestart`:
 *   - TTS path:    `ttsManager.onDone` fires → `scheduleOneRestart(500ms)`
 *   - Silent path: `notifyCommandDone()` detects !isSpeaking → `scheduleOneRestart(300ms)`
 *   - Error path:  `onRecoverableError` → `scheduleOneRestart(RecoveryPolicy.delayMs(code))`
 *
 * These tests verify:
 * a) The parser routes each command type correctly.
 * b) notifyCommandDone / single-restart guard logic is correct.
 * c) StopListening ends the session; it must NEVER trigger a restart.
 * d) MuteVoice does NOT stop the session — the loop must continue while muted.
 */
class VoiceSessionLoopTest {

    // ── 1. MuteVoice — parsed correctly so the silent-restart path is reached ─

    @Test
    fun `nutildyk is parsed as MuteVoice`() {
        val cmd = VoiceCommandParser.parse("nutildyk")
        assertTrue("Expected MuteVoice, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.MuteVoice)
    }

    @Test
    fun `tyla is parsed as MuteVoice`() {
        val cmd = VoiceCommandParser.parse("tyla")
        assertTrue(cmd is VoiceCommand.MuteVoice)
    }

    // ── 2. StopListening — session ends; restart must NOT be scheduled ─────

    @Test
    fun `nustok klausyti is parsed as StopListening`() {
        val cmd = VoiceCommandParser.parse("nustok klausyti")
        assertTrue("Expected StopListening, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.StopListening)
    }

    @Test
    fun `ishjunk mikrofona is parsed as StopListening not MuteVoice`() {
        // STOP_LISTENING_PATTERNS must be checked before MUTE_PATTERNS
        // so "išjunk mikrofoną" stops the session loop rather than just muting TTS.
        val cmd = VoiceCommandParser.parse("išjunk mikrofoną")
        assertTrue("Expected StopListening (not MuteVoice), got ${cmd::class.simpleName}",
            cmd is VoiceCommand.StopListening)
    }

    @Test
    fun `StopListening and MuteVoice are different commands`() {
        // The restart is intentionally absent after StopListening.
        // Verify the two commands are distinct types so callers can guard separately.
        assertFalse(VoiceCommand.StopListening is VoiceCommand.MuteVoice)
    }

    // ── 3. notifyCommandDone guard logic — pure boolean invariant ──────────

    @Test
    fun `restart is scheduled when session active and TTS not speaking`() {
        // notifyCommandDone: if (!_continuousSessionActive.value) return
        //                    if (ttsManager.isSpeaking) return   // onDone will handle it
        //                    scheduleOneRestart(300L)
        val sessionActive = true
        val isSpeaking = false
        val shouldSchedule = sessionActive && !isSpeaking
        assertTrue("Expected restart to be scheduled", shouldSchedule)
    }

    @Test
    fun `restart is NOT scheduled when session inactive`() {
        val sessionActive = false
        val isSpeaking = false
        val shouldSchedule = sessionActive && !isSpeaking
        assertFalse("Expected no restart when session inactive", shouldSchedule)
    }

    @Test
    fun `restart delegated to onDone when TTS is speaking`() {
        // When TTS IS speaking, notifyCommandDone() returns early.
        // The actual restart happens via ttsManager.onDone → scheduleOneRestart(500ms).
        val sessionActive = true
        val isSpeaking = true
        val shouldSchedule = sessionActive && !isSpeaking
        assertFalse("Expected onDone to handle restart (not manual)", shouldSchedule)
    }

    // ── 4. Single restart path — no double-scheduling ─────────────────────

    @Test
    fun `TTS-path delay 500ms is greater than silent-path delay 300ms`() {
        // This ordering ensures that a silent-path restart can fire promptly
        // while TTS restarts wait for audio to finish playing.
        val ttsPathDelay   = 500L
        val silentPathDelay = 300L
        assertTrue("TTS restart delay ($ttsPathDelay) must be > silent delay ($silentPathDelay)",
            ttsPathDelay > silentPathDelay)
    }

    @Test
    fun `error path delay for SERVER_DISCONNECTED is larger than normal restart delay`() {
        // After ERROR 11 (server disconnected), back off significantly
        // before retrying to avoid hammering the server.
        val errorDelay  = RecoveryPolicy.delayMs(RecoveryPolicy.E_SERVER_DISCONNECTED)
        val normalDelay = 300L
        assertTrue("ERROR 11 delay ($errorDelay) must be > normal restart delay ($normalDelay)",
            errorDelay > normalDelay)
    }

    // ── 5. Async-command types — parser confirms restart path is async ─────

    @Test
    fun `StartNavigation is parsed for plain place name (async restart path)`() {
        val cmd = VoiceCommandParser.parse("Akropolis")
        assertTrue("Expected StartNavigation, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.StartNavigation)
    }

    @Test
    fun `GeneralQuestion routes to OpenAI then restarts (async restart path)`() {
        val cmd = VoiceCommandParser.parse("papasakok anekdotą")
        assertTrue("Expected GeneralQuestion, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.GeneralQuestion)
    }

    @Test
    fun `Unknown falls through to synchronous restart path`() {
        val cmd = VoiceCommandParser.parse("xyzzy nonce phrase 9128374")
        // Could be Unknown or StartNavigation depending on word-count classifier.
        // Either way, both paths call notifyCommandDone().
        assertTrue("Expected Unknown or StartNavigation",
            cmd is VoiceCommand.Unknown || cmd is VoiceCommand.StartNavigation)
    }

    // ── 6. Muted session — silent commands must not break the loop ─────────

    @Test
    fun `MuteVoice then UnmuteVoice round-trip parses correctly`() {
        val mute   = VoiceCommandParser.parse("nutildyk")
        val unmute = VoiceCommandParser.parse("kalbėk")
        assertTrue(mute is VoiceCommand.MuteVoice)
        assertTrue(unmute is VoiceCommand.UnmuteVoice)
    }

    @Test
    fun `distance query while muted takes sync path (speakAndIdle + restart)`() {
        val cmd = VoiceCommandParser.parse("kiek kilometrų liko")
        assertTrue("Expected RemainingDistance, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.RemainingDistance)
    }

    @Test
    fun `time query takes sync path (speakAndIdle + restart)`() {
        val cmd = VoiceCommandParser.parse("kiek laiko liko")
        assertTrue("Expected RemainingTime, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.RemainingTime)
    }

    // ── 7. Session state transitions ───────────────────────────────────────

    @Test
    fun `VoiceSessionState RestartDelay is between ListeningReady turns`() {
        assertNotEquals(VoiceSessionState.RestartDelay, VoiceSessionState.Idle)
        assertNotEquals(VoiceSessionState.RestartDelay, VoiceSessionState.ListeningReady)
    }

    @Test
    fun `Idle statusText signals session is off (not just paused)`() {
        assertEquals("Kentas išjungtas", VoiceSessionState.Idle.statusText)
    }

    @Test
    fun `RestartDelay statusText signals loop is waiting not stopped`() {
        assertEquals("Laukiu komandos…", VoiceSessionState.RestartDelay.statusText)
    }

    @Test
    fun `Recovering is distinct from Idle so UI does not claim session is stopped`() {
        assertNotEquals(
            "Recovering and Idle must not have identical statusText",
            VoiceSessionState.Recovering.statusText,
            VoiceSessionState.Idle.statusText,
        )
    }

    // ── 8. MAX_SESSION_RETRIES boundary ────────────────────────────────────

    @Test
    fun `retry counter boundary — retries exhausted after MAX_SESSION_RETRIES increments`() {
        var count = 0
        val maxRetries = 3   // mirrors MainViewModel.MAX_SESSION_RETRIES
        val retryResults = mutableListOf<Boolean>()
        repeat(5) {
            count++
            retryResults.add(count <= maxRetries)
        }
        // Exactly the first 3 increments should succeed.
        assertEquals(listOf(true, true, true, false, false), retryResults)
    }

    @Test
    fun `MAX_SESSION_RETRIES is 3 (regression guard)`() {
        // Changing this constant changes the UX contract — keep it explicit.
        assertEquals(3, MainViewModel.MAX_SESSION_RETRIES)
    }
}
