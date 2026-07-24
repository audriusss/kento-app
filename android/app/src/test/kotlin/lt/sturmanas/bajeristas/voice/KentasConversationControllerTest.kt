package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [KentasConversationController] constants and contract.
 *
 * Full integration tests (that instantiate the controller with real SR and TTS
 * fakes) live in androidTest/ because they require an Android Context.
 * These tests verify the spec-required constants and enum contracts that are
 * checkable on a plain JVM.
 *
 * Spec acceptance criteria covered:
 *   AC-C01  ConversationState enum contains exactly the five required values
 *   AC-C02  INACTIVITY_TIMEOUT_MS is 30 seconds
 *   AC-C03  MAX_RETRIES is 1
 *   AC-C04  isSpeechBlocked extension is true only for LISTENING and USER_SPEAKING
 *   AC-C05  VoiceListeningState has exactly the five simplified values
 */
class KentasConversationControllerTest {

    // ── AC-C01 ────────────────────────────────────────────────────────────

    @Test
    fun `ConversationState enum contains exactly the five required values`() {
        val states = ConversationState.entries.map { it.name }
        assertTrue(states.contains("IDLE"))
        assertTrue(states.contains("LISTENING"))
        assertTrue(states.contains("USER_SPEAKING"))
        assertTrue(states.contains("THINKING"))
        assertTrue(states.contains("SPEAKING"))
    }

    // ── AC-C05 — generation logic (pure logic test) ───────────────────────

    @Test
    fun `ConversationState enum has exactly the five expected values`() {
        val expected = setOf("IDLE", "LISTENING", "USER_SPEAKING", "THINKING", "SPEAKING")
        val actual = ConversationState.entries.map { it.name }.toSet()
        assertEquals(
            "ConversationState must have exactly IDLE/LISTENING/USER_SPEAKING/THINKING/SPEAKING",
            expected,
            actual,
        )
    }

    // ── AC-C06 — constants ────────────────────────────────────────────────

    @Test
    fun `INACTIVITY_TIMEOUT_MS is 30 seconds`() {
        assertEquals(
            "inactivity timeout must be 30 000 ms",
            30_000L,
            KentasConversationController.INACTIVITY_TIMEOUT_MS,
        )
    }

    @Test
    fun `MAX_RETRIES is 1`() {
        assertEquals(
            "max retries must be exactly 1",
            1,
            KentasConversationController.MAX_RETRIES,
        )
    }

    // ── AC-C08 — VoiceListeningState mapping ──────────────────────────────

    @Test
    fun `isSpeechBlocked is true only for LISTENING and USER_SPEAKING`() {
        assertTrue(VoiceListeningState.LISTENING.isSpeechBlocked)
        assertTrue(VoiceListeningState.USER_SPEAKING.isSpeechBlocked)

        assertFalse(VoiceListeningState.IDLE.isSpeechBlocked)
        assertFalse(VoiceListeningState.THINKING.isSpeechBlocked)
        assertFalse(VoiceListeningState.SPEAKING.isSpeechBlocked)
    }

    @Test
    fun `VoiceListeningState has exactly the five expected values`() {
        val expected = setOf("IDLE", "LISTENING", "USER_SPEAKING", "THINKING", "SPEAKING")
        val actual = VoiceListeningState.entries.map { it.name }.toSet()
        assertEquals(
            "VoiceListeningState must have exactly the five simplified states",
            expected,
            actual,
        )
    }
}
