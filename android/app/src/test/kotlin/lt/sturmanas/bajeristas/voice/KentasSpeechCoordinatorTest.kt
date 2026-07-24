package lt.sturmanas.bajeristas.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [KentasSpeechCoordinator] constants and contract.
 *
 * Full integration tests require Android SDK TtsManager; these tests
 * cover the coordinator's public contract spec points.
 *
 * Spec acceptance criteria covered:
 *   AC-S01  WATCHDOG_MS constant is 12 seconds
 *   AC-S02  ConversationState to VoiceListeningState mapping covers all states
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KentasSpeechCoordinatorTest {

    // ── AC-S01 ────────────────────────────────────────────────────────────

    @Test
    fun `watchdog constant is 12 seconds`() {
        assertEquals(
            "TTS watchdog must fire after 12 000 ms",
            12_000L,
            KentasSpeechCoordinator.WATCHDOG_MS,
        )
    }

    // ── AC-S02 — mapping from ConversationState to VoiceListeningState ────

    @Test
    fun `every ConversationState maps to a distinct VoiceListeningState`() {
        // Verify that the mapping in MainViewModel is exhaustive by checking
        // that every ConversationState has a corresponding VoiceListeningState name.
        val convStates = ConversationState.entries.map { it.name }.toSet()
        val voiceStates = VoiceListeningState.entries.map { it.name }.toSet()

        // Every ConversationState must have a VoiceListeningState with the same name.
        // This ensures the `when` expression in MainViewModel stays exhaustive.
        for (cs in convStates) {
            assertTrue(
                "ConversationState.$cs must have a matching VoiceListeningState",
                voiceStates.contains(cs),
            )
        }
    }

    @Test
    fun `VoiceListeningState count matches ConversationState count`() {
        assertEquals(
            "VoiceListeningState and ConversationState must have same number of values",
            ConversationState.entries.size,
            VoiceListeningState.entries.size,
        )
    }
}
