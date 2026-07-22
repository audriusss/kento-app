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
 * - Equality and identity for object states.
 */
class VoiceSessionStateTest {

    @Test
    fun `Idle statusText is correct`() {
        assertEquals("Kentas išjungtas", VoiceSessionState.Idle.statusText)
    }

    @Test
    fun `Listening statusText is correct`() {
        assertEquals("Klausau…", VoiceSessionState.Listening.statusText)
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
    fun `object states are singletons`() {
        assertSame(VoiceSessionState.Idle, VoiceSessionState.Idle)
        assertSame(VoiceSessionState.Listening, VoiceSessionState.Listening)
        assertSame(VoiceSessionState.Processing, VoiceSessionState.Processing)
        assertSame(VoiceSessionState.Speaking, VoiceSessionState.Speaking)
        assertSame(VoiceSessionState.RestartDelay, VoiceSessionState.RestartDelay)
    }

    @Test
    fun `Idle is not Listening`() {
        assertNotEquals(VoiceSessionState.Idle, VoiceSessionState.Listening)
    }
}
