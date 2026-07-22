package lt.sturmanas.bajeristas.voice

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [VoiceSessionState] — pure sealed-class logic with no Android
 * dependencies, so these run as plain JVM tests.
 *
 * Covers:
 * - Each state produces the correct Lithuanian [VoiceSessionState.statusText].
 * - [VoiceSessionState.Error] preserves [isFatal] and its message in [statusText].
 * - New states added for the ERROR-11 fix: [VoiceSessionState.Starting],
 *   [VoiceSessionState.ListeningReady], [VoiceSessionState.UserSpeaking],
 *   [VoiceSessionState.Recovering].
 * - Equality and identity for object states.
 * - Key invariant: "Kentas klauso…" appears ONLY in [ListeningReady.statusText],
 *   not in [Starting.statusText].
 */
class VoiceSessionStateTest {

    // ── Core states ───────────────────────────────────────────────────────

    @Test
    fun `Idle statusText is correct`() {
        assertEquals("Kentas išjungtas", VoiceSessionState.Idle.statusText)
    }

    @Test
    fun `Starting statusText does NOT say Kentas klauso`() {
        // "Kentas klauso…" must only appear after onReadyForSpeech (ListeningReady).
        // Before that (Starting), we show "Įjungiamas klausymas…".
        val text = VoiceSessionState.Starting.statusText
        assertFalse(
            "Starting must not claim 'Kentas klauso…' before SR is ready; got: '$text'",
            text.contains("Kentas klauso")
        )
        assertTrue("Starting should indicate readying state; got: '$text'",
            text.contains("klausymas") || text.contains("Įjungiamas"))
    }

    @Test
    fun `ListeningReady statusText is Kentas klauso`() {
        // This is the ONLY state where "Kentas klauso…" is correct.
        assertEquals("Kentas klauso…", VoiceSessionState.ListeningReady.statusText)
    }

    @Test
    fun `UserSpeaking statusText indicates active listening`() {
        val text = VoiceSessionState.UserSpeaking.statusText
        assertTrue("UserSpeaking should show active-listening text; got: '$text'",
            text.contains("Klausau"))
    }

    @Test
    fun `Processing statusText is correct`() {
        assertEquals("Atpažįstu…", VoiceSessionState.Processing.statusText)
    }

    @Test
    fun `Speaking statusText is correct`() {
        assertEquals("Kentas kalba…", VoiceSessionState.Speaking.statusText)
    }

    @Test
    fun `RestartDelay statusText is correct`() {
        assertEquals("Laukiu komandos…", VoiceSessionState.RestartDelay.statusText)
    }

    @Test
    fun `Recovering statusText signals recovery in progress`() {
        val text = VoiceSessionState.Recovering.statusText
        assertTrue("Recovering statusText should mention recovery; got: '$text'",
            text.contains("Atkuriamas") || text.contains("atpažinimas"))
    }

    // ── Error state ───────────────────────────────────────────────────────

    @Test
    fun `Error statusText returns the message`() {
        val err = VoiceSessionState.Error("Testas klaida", isFatal = false)
        assertEquals("Testas klaida", err.statusText)
    }

    @Test
    fun `Error with isFatal true preserves flag`() {
        val err = VoiceSessionState.Error("Fatal", isFatal = true)
        assertTrue(err.isFatal)
    }

    @Test
    fun `Error with isFatal false preserves flag`() {
        val err = VoiceSessionState.Error("Recoverable", isFatal = false)
        assertFalse(err.isFatal)
    }

    @Test
    fun `Error data class equality works`() {
        val a = VoiceSessionState.Error("msg", isFatal = false)
        val b = VoiceSessionState.Error("msg", isFatal = false)
        assertEquals(a, b)
    }

    @Test
    fun `Error data class inequality on different message`() {
        val a = VoiceSessionState.Error("msg1", isFatal = false)
        val b = VoiceSessionState.Error("msg2", isFatal = false)
        assertNotEquals(a, b)
    }

    @Test
    fun `Error data class inequality on different isFatal`() {
        val a = VoiceSessionState.Error("msg", isFatal = false)
        val b = VoiceSessionState.Error("msg", isFatal = true)
        assertNotEquals(a, b)
    }

    // ── Singleton identity ────────────────────────────────────────────────

    @Test
    fun `object states are singletons`() {
        assertSame(VoiceSessionState.Idle, VoiceSessionState.Idle)
        assertSame(VoiceSessionState.Starting, VoiceSessionState.Starting)
        assertSame(VoiceSessionState.ListeningReady, VoiceSessionState.ListeningReady)
        assertSame(VoiceSessionState.UserSpeaking, VoiceSessionState.UserSpeaking)
        assertSame(VoiceSessionState.Processing, VoiceSessionState.Processing)
        assertSame(VoiceSessionState.Speaking, VoiceSessionState.Speaking)
        assertSame(VoiceSessionState.RestartDelay, VoiceSessionState.RestartDelay)
        assertSame(VoiceSessionState.Recovering, VoiceSessionState.Recovering)
    }

    // ── Key invariant: Only ListeningReady shows "Kentas klauso…" ─────────

    @Test
    fun `only ListeningReady statusText contains Kentas klauso`() {
        val allStates: List<VoiceSessionState> = listOf(
            VoiceSessionState.Idle,
            VoiceSessionState.Starting,
            VoiceSessionState.ListeningReady,
            VoiceSessionState.UserSpeaking,
            VoiceSessionState.Processing,
            VoiceSessionState.Speaking,
            VoiceSessionState.RestartDelay,
            VoiceSessionState.Recovering,
            VoiceSessionState.Error("err", false),
            @Suppress("DEPRECATION") VoiceSessionState.Listening,
        )
        val statesWithKentasKlauso = allStates.filter {
            it.statusText.contains("Kentas klauso")
        }
        // ListeningReady AND the deprecated Listening alias — both are acceptable.
        assertTrue(
            "Expected only ListeningReady (and its compat alias) to say 'Kentas klauso…', " +
            "but found: ${statesWithKentasKlauso.map { it::class.simpleName }}",
            statesWithKentasKlauso.all {
                it is VoiceSessionState.ListeningReady ||
                @Suppress("DEPRECATION") it is VoiceSessionState.Listening
            }
        )
    }

    @Test
    fun `Starting statusText is different from ListeningReady statusText`() {
        // Ensures the two pre-listening states are visually distinct.
        assertNotEquals(VoiceSessionState.Starting.statusText,
            VoiceSessionState.ListeningReady.statusText)
    }

    @Test
    fun `Recovering statusText is different from Idle statusText`() {
        // Recovering must not give the impression the session is off.
        assertNotEquals(VoiceSessionState.Recovering.statusText,
            VoiceSessionState.Idle.statusText)
    }

    @Test
    fun `all state statusTexts are non-blank`() {
        val allStates: List<VoiceSessionState> = listOf(
            VoiceSessionState.Idle,
            VoiceSessionState.Starting,
            VoiceSessionState.ListeningReady,
            VoiceSessionState.UserSpeaking,
            VoiceSessionState.Processing,
            VoiceSessionState.Speaking,
            VoiceSessionState.RestartDelay,
            VoiceSessionState.Recovering,
            VoiceSessionState.Error("x", false),
        )
        allStates.forEach { state ->
            assertTrue(
                "State ${state::class.simpleName} has blank statusText",
                state.statusText.isNotBlank()
            )
        }
    }
}
