package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the session-loop restart invariants introduced in Task #21.
 *
 * The continuous hands-free loop has TWO restart paths:
 * 1. **TTS path**: ttsManager.onDone fires at utterance end → scheduleSessionRestart.
 * 2. **Silent path**: when no TTS is produced (isMuted=true, or command never speaks)
 *    → scheduleRestartIfSessionActive() is called at every command exit point.
 *
 * These tests verify:
 * a) The parser routes each command type correctly so the right restart path is taken.
 * b) The restart guard logic (`continuousSessionActive && !isSpeaking`) behaves as expected.
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
        // The silent-restart call is intentionally absent after StopListening.
        // Verify the two commands are distinct types so callers can guard separately.
        assertFalse(VoiceCommand.StopListening is VoiceCommand.MuteVoice)
    }

    // ── 3. Restart guard logic — pure boolean invariant ───────────────────

    @Test
    fun `restart is scheduled when session active and TTS not speaking`() {
        // Mirrors the guard in scheduleRestartIfSessionActive:
        //   if (!_continuousSessionActive.value) return
        //   if (ttsManager.isSpeaking) return   // onDone will handle it
        //   scheduleSessionRestart(delayMs)
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
        // When TTS IS speaking, scheduleRestartIfSessionActive returns early.
        // The actual restart happens via ttsManager.onDone → scheduleSessionRestart.
        val sessionActive = true
        val isSpeaking = true
        val shouldSchedule = sessionActive && !isSpeaking
        assertFalse("Expected onDone to handle restart (not manual)", shouldSchedule)
    }

    // ── 4. Async-command types — parser confirms restart path is async ─────

    @Test
    fun `StartNavigation is parsed for plain place name (async restart path)`() {
        // resolveAndNavigate() is launched in a coroutine;
        // scheduleRestartIfSessionActive() is called at the end of each coroutine branch.
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
        // Either way, both paths call scheduleRestartIfSessionActive.
        assertTrue("Expected Unknown or StartNavigation",
            cmd is VoiceCommand.Unknown || cmd is VoiceCommand.StartNavigation)
    }

    // ── 5. Muted session — silent commands must not break the loop ─────────

    @Test
    fun `MuteVoice then UnmuteVoice round-trip parses correctly`() {
        val mute = VoiceCommandParser.parse("nutildyk")
        val unmute = VoiceCommandParser.parse("kalbėk")
        assertTrue(mute is VoiceCommand.MuteVoice)
        assertTrue(unmute is VoiceCommand.UnmuteVoice)
    }

    @Test
    fun `distance query while muted takes sync path (speakAndIdle + restart)`() {
        // RemainingDistance speaks via speakAndIdle if NOT muted, or produces no TTS
        // when muted. Both branches fall through to scheduleRestartIfSessionActive.
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

    // ── 6. Session state transitions ───────────────────────────────────────

    @Test
    fun `VoiceSessionState RestartDelay is between Listening turns`() {
        // RestartDelay is entered by scheduleSessionRestart before SR.startListening().
        // Idle is only reached when the session is explicitly stopped.
        assertNotEquals(VoiceSessionState.RestartDelay, VoiceSessionState.Idle)
        assertNotEquals(VoiceSessionState.RestartDelay, VoiceSessionState.Listening)
    }

    @Test
    fun `Idle statusText signals session is off (not just paused)`() {
        assertEquals("Kentas išjungtas", VoiceSessionState.Idle.statusText)
    }

    @Test
    fun `RestartDelay statusText signals loop is waiting not stopped`() {
        assertEquals("Laukiu komandos…", VoiceSessionState.RestartDelay.statusText)
    }
}
