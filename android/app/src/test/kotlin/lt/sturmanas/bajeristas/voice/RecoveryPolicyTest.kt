package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [RecoveryPolicy] — pure Kotlin, no Android SDK, runs on plain JVM.
 *
 * Covers:
 * - Error classification (fatal vs. recoverable vs. silent)
 * - Recognizer-recreate decisions (including ERROR 11 — the confirmed real-device bug)
 * - Recovery delay bands
 * - Symbolic error names (used in KentasSpeechLifecycle logs)
 * - Lithuanian status-text strings
 * - All code constants have expected integer values
 */
class RecoveryPolicyTest {

    // ── 1. Constant values match stable Android platform values ───────────

    @Test
    fun `E_NO_MATCH constant equals 7`() {
        assertEquals(7, RecoveryPolicy.E_NO_MATCH)
    }

    @Test
    fun `E_RECOGNIZER_BUSY constant equals 8`() {
        assertEquals(8, RecoveryPolicy.E_RECOGNIZER_BUSY)
    }

    @Test
    fun `E_INSUFFICIENT_PERMISSIONS constant equals 9`() {
        assertEquals(9, RecoveryPolicy.E_INSUFFICIENT_PERMISSIONS)
    }

    @Test
    fun `E_SERVER_DISCONNECTED constant equals 11`() {
        // This is the code fired by the confirmed real-device (Xiaomi) failure.
        assertEquals(11, RecoveryPolicy.E_SERVER_DISCONNECTED)
    }

    @Test
    fun `E_TOO_MANY_REQUESTS constant equals 10`() {
        assertEquals(10, RecoveryPolicy.E_TOO_MANY_REQUESTS)
    }

    // ── 2. isFatal classification ─────────────────────────────────────────

    @Test
    fun `isFatal is true for INSUFFICIENT_PERMISSIONS`() {
        assertTrue(RecoveryPolicy.isFatal(RecoveryPolicy.E_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `isFatal is true for LANGUAGE_NOT_SUPPORTED`() {
        assertTrue(RecoveryPolicy.isFatal(RecoveryPolicy.E_LANGUAGE_NOT_SUPPORTED))
    }

    @Test
    fun `isFatal is true for LANGUAGE_UNAVAILABLE`() {
        assertTrue(RecoveryPolicy.isFatal(RecoveryPolicy.E_LANGUAGE_UNAVAILABLE))
    }

    @Test
    fun `isFatal is false for ERROR_SERVER_DISCONNECTED`() {
        // ERROR 11 must be RECOVERABLE, not fatal — this was the real-device bug.
        assertFalse(RecoveryPolicy.isFatal(RecoveryPolicy.E_SERVER_DISCONNECTED))
    }

    @Test
    fun `isFatal is false for NO_MATCH`() {
        assertFalse(RecoveryPolicy.isFatal(RecoveryPolicy.E_NO_MATCH))
    }

    @Test
    fun `isFatal is false for NETWORK`() {
        assertFalse(RecoveryPolicy.isFatal(RecoveryPolicy.E_NETWORK))
    }

    // ── 3. shouldRecreateRecognizer ───────────────────────────────────────

    @Test
    fun `shouldRecreateRecognizer is true for RECOGNIZER_BUSY`() {
        assertTrue(RecoveryPolicy.shouldRecreateRecognizer(RecoveryPolicy.E_RECOGNIZER_BUSY))
    }

    @Test
    fun `shouldRecreateRecognizer is true for SERVER_DISCONNECTED code 11`() {
        // The recognizer object is in a broken state after ERROR 11 — must recreate.
        assertTrue(RecoveryPolicy.shouldRecreateRecognizer(RecoveryPolicy.E_SERVER_DISCONNECTED))
    }

    @Test
    fun `shouldRecreateRecognizer is true for CLIENT error`() {
        assertTrue(RecoveryPolicy.shouldRecreateRecognizer(RecoveryPolicy.E_CLIENT))
    }

    @Test
    fun `shouldRecreateRecognizer is false for NO_MATCH`() {
        // NO_MATCH is a plain retry — no need to destroy the recognizer.
        assertFalse(RecoveryPolicy.shouldRecreateRecognizer(RecoveryPolicy.E_NO_MATCH))
    }

    @Test
    fun `shouldRecreateRecognizer is false for NETWORK`() {
        assertFalse(RecoveryPolicy.shouldRecreateRecognizer(RecoveryPolicy.E_NETWORK))
    }

    // ── 4. delayMs bands ──────────────────────────────────────────────────

    @Test
    fun `delayMs for NO_MATCH is 500ms`() {
        assertEquals(500L, RecoveryPolicy.delayMs(RecoveryPolicy.E_NO_MATCH))
    }

    @Test
    fun `delayMs for SPEECH_TIMEOUT is 500ms`() {
        assertEquals(500L, RecoveryPolicy.delayMs(RecoveryPolicy.E_SPEECH_TIMEOUT))
    }

    @Test
    fun `delayMs for RECOGNIZER_BUSY is 800ms`() {
        assertEquals(800L, RecoveryPolicy.delayMs(RecoveryPolicy.E_RECOGNIZER_BUSY))
    }

    @Test
    fun `delayMs for SERVER_DISCONNECTED is 1200ms`() {
        // Longer delay for ERROR 11 to give Google servers time to recover.
        assertEquals(1200L, RecoveryPolicy.delayMs(RecoveryPolicy.E_SERVER_DISCONNECTED))
    }

    @Test
    fun `delayMs for TOO_MANY_REQUESTS is 5000ms`() {
        assertEquals(5000L, RecoveryPolicy.delayMs(RecoveryPolicy.E_TOO_MANY_REQUESTS))
    }

    @Test
    fun `delayMs for NETWORK is at least 1000ms`() {
        assertTrue(RecoveryPolicy.delayMs(RecoveryPolicy.E_NETWORK) >= 1000L)
    }

    @Test
    fun `delayMs for unknown code returns a positive fallback`() {
        val delay = RecoveryPolicy.delayMs(999)
        assertTrue("Expected positive delay, got $delay", delay > 0L)
    }

    // ── 5. isSilentRecovery ───────────────────────────────────────────────

    @Test
    fun `isSilentRecovery is true for NO_MATCH`() {
        // User simply didn't speak — no error TTS should play.
        assertTrue(RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_NO_MATCH))
    }

    @Test
    fun `isSilentRecovery is true for SPEECH_TIMEOUT`() {
        assertTrue(RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SPEECH_TIMEOUT))
    }

    @Test
    fun `isSilentRecovery is false for SERVER_DISCONNECTED`() {
        // ERROR 11 shows "Atkuriamas balso atpažinimas…" — not silent.
        assertFalse(RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SERVER_DISCONNECTED))
    }

    @Test
    fun `isSilentRecovery is false for RECOGNIZER_BUSY`() {
        assertFalse(RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_RECOGNIZER_BUSY))
    }

    // ── 6. Symbolic error names for logging ───────────────────────────────

    @Test
    fun `errorName for SERVER_DISCONNECTED is symbolic string`() {
        val name = RecoveryPolicy.errorName(RecoveryPolicy.E_SERVER_DISCONNECTED)
        assertEquals("ERROR_SERVER_DISCONNECTED", name)
    }

    @Test
    fun `errorName for NO_MATCH is symbolic string`() {
        assertEquals("ERROR_NO_MATCH", RecoveryPolicy.errorName(RecoveryPolicy.E_NO_MATCH))
    }

    @Test
    fun `errorName for RECOGNIZER_BUSY is symbolic string`() {
        assertEquals("ERROR_RECOGNIZER_BUSY",
            RecoveryPolicy.errorName(RecoveryPolicy.E_RECOGNIZER_BUSY))
    }

    @Test
    fun `errorName for unknown code does not throw`() {
        val name = RecoveryPolicy.errorName(999)
        assertNotNull(name)
        assertTrue(name.isNotBlank())
    }

    // ── 7. Lithuanian status texts ────────────────────────────────────────

    @Test
    fun `statusText for SERVER_DISCONNECTED is recovery text`() {
        val text = RecoveryPolicy.statusText(RecoveryPolicy.E_SERVER_DISCONNECTED)
        assertEquals("Atkuriamas balso atpažinimas…", text)
    }

    @Test
    fun `statusText for NO_MATCH mentions retry`() {
        val text = RecoveryPolicy.statusText(RecoveryPolicy.E_NO_MATCH)
        assertTrue("Expected retry mention, got: $text", text.contains("Nieko neišgirdau"))
    }

    @Test
    fun `statusText for NETWORK error is meaningful Lithuanian string`() {
        val text = RecoveryPolicy.statusText(RecoveryPolicy.E_NETWORK)
        assertTrue("Expected non-blank text", text.isNotBlank())
    }

    // ── 8. Fatal TTS messages ─────────────────────────────────────────────

    @Test
    fun `fatalTtsMessage for INSUFFICIENT_PERMISSIONS mentions permission`() {
        val msg = RecoveryPolicy.fatalTtsMessage(RecoveryPolicy.E_INSUFFICIENT_PERMISSIONS)
        assertTrue("Expected permission mention, got: $msg",
            msg.contains("leidim", ignoreCase = true))
    }

    @Test
    fun `fatalTtsMessage for unknown fatal code returns non-blank string`() {
        val msg = RecoveryPolicy.fatalTtsMessage(999)
        assertTrue(msg.isNotBlank())
    }

    // ── 9. Cross-property consistency ─────────────────────────────────────

    @Test
    fun `fatal errors are never in shouldRecreateRecognizer set`() {
        val fatalCodes = listOf(
            RecoveryPolicy.E_INSUFFICIENT_PERMISSIONS,
            RecoveryPolicy.E_LANGUAGE_NOT_SUPPORTED,
            RecoveryPolicy.E_LANGUAGE_UNAVAILABLE,
        )
        // Fatal errors stop the session; recreating is pointless.
        fatalCodes.forEach { code ->
            assertFalse("Fatal code $code should not trigger recreate",
                RecoveryPolicy.shouldRecreateRecognizer(code))
        }
    }

    @Test
    fun `all recreate codes have a positive delay greater than or equal to 800ms`() {
        val recreateCodes = listOf(
            RecoveryPolicy.E_RECOGNIZER_BUSY,
            RecoveryPolicy.E_SERVER_DISCONNECTED,
            RecoveryPolicy.E_CLIENT,
        )
        recreateCodes.forEach { code ->
            val delay = RecoveryPolicy.delayMs(code)
            assertTrue("Recreate code $code delay $delay should be >= 800ms", delay >= 800L)
        }
    }
}
