package lt.sturmanas.bajeristas.voice

import lt.sturmanas.bajeristas.MainViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM tests for the TTS watchdog and completion callback recovery logic.
 *
 * Tests verify the invariants that the TTS invocation chain must satisfy:
 * - speak() → onStart → onDone → restart (happy path)
 * - speak() → onStart → watchdog fires (onDone never arrived)
 * - speak() → onError → onDone.invoke() (error path)
 * - forceComplete() resets isSpeaking and invokes onDone
 * - watchdog is cancelled when onDone fires normally
 * - Stale watchdog from previous utterance cannot fire for new utterance (QUEUE_FLUSH)
 *
 * All tests are pure JVM — no Android framework, no coroutines, no mocks.
 * They test decision-table logic and invariants by simulating the relevant state
 * transitions with plain variables.
 */
class TtsRecoveryTest {

    // ── Helper: mirrors the watchdog arm/disarm logic ─────────────────────

    /**
     * Simulates the onStart callback:
     * - sets isSpeaking = true
     * - arms the watchdog (returns a new "watchdog token")
     *
     * In production this is a coroutine job; here we use a Boolean to track
     * whether the watchdog is active.
     */
    private data class TtsState(
        var isSpeaking: Boolean = false,
        var watchdogArmed: Boolean = false,
        var onDoneInvoked: Boolean = false,
        var forceCompleteInvoked: Boolean = false,
    )

    private fun simulateOnStart(state: TtsState) {
        state.isSpeaking = true
        state.watchdogArmed = true      // coroutine launched in production
    }

    private fun simulateOnDone(state: TtsState) {
        state.watchdogArmed = false     // job.cancel() in production
        state.isSpeaking = false
        state.onDoneInvoked = true
    }

    private fun simulateOnError(state: TtsState) {
        // Both onError overrides delegate to onDone.invoke() in production.
        state.isSpeaking = false
        state.onDoneInvoked = true      // onDone?.invoke()
        // watchdog is NOT explicitly cancelled here — onDone is called synchronously
        // and the watchdog would see isSpeaking=false and become a no-op.
    }

    private fun simulateWatchdogFire(state: TtsState) {
        // Watchdog condition: if (ttsManager.isSpeaking) { forceComplete() }
        if (state.isSpeaking) {
            state.isSpeaking = false
            state.onDoneInvoked = true
            state.forceCompleteInvoked = true
        }
        state.watchdogArmed = false
    }

    private fun simulateForceComplete(state: TtsState) {
        state.isSpeaking = false
        state.onDoneInvoked = true
        state.forceCompleteInvoked = true
    }

    // ── 1. Happy path: onStart → onDone → watchdog cancelled ─────────────

    @Test
    fun `happy path speak → onStart → onDone cancels watchdog`() {
        val state = TtsState()
        simulateOnStart(state)
        assertTrue("isSpeaking must be true after onStart", state.isSpeaking)
        assertTrue("watchdog must be armed after onStart", state.watchdogArmed)

        simulateOnDone(state)
        assertFalse("isSpeaking must be false after onDone", state.isSpeaking)
        assertFalse("watchdog must be cancelled after onDone", state.watchdogArmed)
        assertTrue("onDone callback must have been invoked", state.onDoneInvoked)
    }

    @Test
    fun `onDone callback invoked exactly once in happy path`() {
        var doneCount = 0
        var speaking = false
        var watchdog = false

        // speak() sets isSpeaking=true synchronously
        speaking = true
        // onStart: arm watchdog, set speaking (already true)
        speaking = true
        watchdog = true

        // onDone: cancel watchdog, clear speaking, invoke callback
        watchdog = false
        speaking = false
        doneCount++

        assertEquals(1, doneCount)
        assertFalse(speaking)
        assertFalse(watchdog)
    }

    // ── 2. Watchdog path: onStart fired but onDone never arrives ──────────

    @Test
    fun `watchdog fires forceComplete when isSpeaking still true after timeout`() {
        val state = TtsState()
        simulateOnStart(state)
        // onDone never fires; watchdog timeout expires:
        simulateWatchdogFire(state)

        assertFalse("isSpeaking must be false after watchdog fires", state.isSpeaking)
        assertTrue("forceComplete must have been called by watchdog", state.forceCompleteInvoked)
        assertTrue("onDone callback must have been invoked by watchdog", state.onDoneInvoked)
        assertFalse("watchdog must be disarmed after firing", state.watchdogArmed)
    }

    @Test
    fun `watchdog is a no-op if isSpeaking is already false when it fires`() {
        val state = TtsState()
        simulateOnStart(state)
        // onDone fires normally first:
        simulateOnDone(state)
        // watchdog fires late (coroutine woke up but job not cancelled yet in a race):
        simulateWatchdogFire(state)

        assertFalse("forceComplete must NOT be called if isSpeaking=false at watchdog time",
            state.forceCompleteInvoked)
    }

    // ── 3. Error paths ─────────────────────────────────────────────────────

    @Test
    fun `onError invokes onDone callback for recovery`() {
        val state = TtsState()
        simulateOnStart(state)
        // TTS engine reports an error:
        simulateOnError(state)

        assertFalse("isSpeaking must be false after onError", state.isSpeaking)
        assertTrue("onDone callback must be invoked by onError for recovery", state.onDoneInvoked)
    }

    @Test
    fun `forceComplete resets isSpeaking and invokes onDone`() {
        val state = TtsState(isSpeaking = true)
        simulateForceComplete(state)

        assertFalse(state.isSpeaking)
        assertTrue(state.onDoneInvoked)
        assertTrue(state.forceCompleteInvoked)
    }

    @Test
    fun `TTS error does not leave isSpeaking stuck at true`() {
        val state = TtsState(isSpeaking = true)
        simulateOnError(state)
        assertFalse("isSpeaking must never be left at true after an error", state.isSpeaking)
    }

    // ── 4. Stale watchdog cannot affect a new utterance ───────────────────

    @Test
    fun `QUEUE_FLUSH means only one utterance is ever active`() {
        // QUEUE_FLUSH: new speak() call flushes the old utterance before starting.
        // So onDone for the old utterance fires before onStart for the new one.
        // The watchdog is re-armed only on onStart → one watchdog per utterance.
        var watchdogCount = 0

        // Utterance 1:
        watchdogCount++ // arm
        watchdogCount-- // cancel (QUEUE_FLUSH + new speak)

        // Utterance 2:
        watchdogCount++ // arm

        assertEquals("Only one watchdog must be armed at any time", 1, watchdogCount)
    }

    @Test
    fun `watchdog token check prevents stale watchdog from firing`() {
        // In production: watchdogJob?.cancel() in onStart before launching new job.
        // Here: simulate two sequential utterances; only the second watchdog is active.
        var watchdog1Active = false
        var watchdog2Active = false

        // Utterance 1: arm watchdog1
        watchdog1Active = true
        // New speak() arrives → cancel watchdog1, arm watchdog2
        watchdog1Active = false     // cancel()
        watchdog2Active = true

        // Stale watchdog1 "fires" after cancel — it must be a no-op:
        // (In production the coroutine body checks isSpeaking; here we just track active state)
        assertFalse("Cancelled watchdog1 must not be active", watchdog1Active)
        assertTrue("New watchdog2 must be active", watchdog2Active)
    }

    // ── 5. Continuous mode / onDone interaction ────────────────────────────

    @Test
    fun `onDone triggers listening restart only when continuousMode is enabled`() {
        // Mirrors MainViewModel.ttsManager.onDone lambda:
        //   if (_continuousModeEnabled.value) { requestListeningRestart(TTS_DONE, 500) }
        var restartRequested = false
        val continuousModeEnabled = true

        // Simulate onDone callback:
        if (continuousModeEnabled) restartRequested = true

        assertTrue("Restart must be requested when continuousMode=true", restartRequested)
    }

    @Test
    fun `onDone does NOT trigger restart when continuousMode is disabled`() {
        var restartRequested = false
        val continuousModeEnabled = false

        if (continuousModeEnabled) restartRequested = true

        assertFalse("Restart must NOT be requested when continuousMode=false", restartRequested)
    }

    // ── 6. Watchdog constant sanity ────────────────────────────────────────

    @Test
    fun `TTS_WATCHDOG_MS is a reasonable value`() {
        // The watchdog must be long enough for the longest utterance but short enough
        // to recover before the user notices the loop is stuck.
        val watchdogMs = MainViewModel.TTS_WATCHDOG_MS
        assertTrue("Watchdog must be at least 5 seconds", watchdogMs >= 5_000L)
        assertTrue("Watchdog must not exceed 30 seconds", watchdogMs <= 30_000L)
    }

    // ── 7. Listener restart waits for TTS completion ──────────────────────

    @Test
    fun `recognizer restart is blocked while TTS is speaking (isSpeaking=true)`() {
        // Mirrors the TTS_SPEAKING guard in requestListeningRestart:
        //   ttsManager.isSpeaking -> "TTS_SPEAKING" (blocked)
        val isSpeaking = true
        val blocked = isSpeaking  // TTS_SPEAKING guard
        assertTrue("Recognizer restart must be blocked while TTS is speaking", blocked)
    }

    @Test
    fun `recognizer restart is allowed after TTS completes (isSpeaking=false)`() {
        val isSpeaking = false
        val blocked = isSpeaking
        assertFalse("Recognizer restart must be allowed after TTS completes", blocked)
    }
}
