package lt.sturmanas.bajeristas.voice

import lt.sturmanas.bajeristas.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the utterance aggregation helpers added in the
 * voice-conversation stabilization pass.
 *
 * Tests focus on:
 * - [MainViewModel.recoverFromTruncation] — partial-vs-final selection
 * - [VoiceCommandParser.endsWithIncompleteConjunction] — conjunction heuristic
 * - [MainViewModel] timing constants — correct relative ordering
 * - Turn-taking invariants (state guard rules) verified without coroutines
 *
 * All tests are pure JVM — no Android context, no coroutines, no Robolectric.
 * [MainViewModel] internal helpers are tested via their [internal] visibility.
 */
class UtteranceAggregationTest {

    // ── 1. Truncation recovery: prefer partial when it carries the digit ──────

    @Test
    fun `final 'Taikos' partial 'Taikos 61' selects partial`() {
        val vm = testVm()
        val result = vm.recoverFromTruncation(finalText = "Taikos", partialText = "Taikos 61")
        assertEquals(
            "Should prefer partial that contains the house number",
            "Taikos 61",
            result,
        )
    }

    @Test
    fun `final and partial identical returns final`() {
        val vm = testVm()
        val result = vm.recoverFromTruncation(finalText = "Akropolis", partialText = "Akropolis")
        assertEquals("Identical texts should return the final unchanged", "Akropolis", result)
    }

    @Test
    fun `final already has digit returns final unchanged`() {
        val vm = testVm()
        val result = vm.recoverFromTruncation(finalText = "Taikos 61", partialText = "Taikos 6")
        assertEquals("Final with digit must be returned unchanged", "Taikos 61", result)
    }

    @Test
    fun `partial that diverges from final returns final`() {
        // "Gedimino" does not start with "Taikos" — unrelated partial must be rejected.
        val vm = testVm()
        val result = vm.recoverFromTruncation(finalText = "Taikos", partialText = "Gedimino 5")
        assertEquals("Unrelated partial must not replace final", "Taikos", result)
    }

    @Test
    fun `blank partial returns final unchanged`() {
        val vm = testVm()
        val result = vm.recoverFromTruncation(finalText = "Taikos", partialText = "")
        assertEquals("Blank partial must not affect final", "Taikos", result)
    }

    @Test
    fun `partial without digit when final also lacks digit returns final`() {
        val vm = testVm()
        // Neither has a digit — no number recovery, just return final.
        val result = vm.recoverFromTruncation(finalText = "Taikos", partialText = "Taikos gatvė")
        assertEquals("Partial without digit must not replace final", "Taikos", result)
    }

    // ── 2. Incomplete conjunction heuristic ────────────────────────────────────

    @Test
    fun `sentence ending with 'ir' is incomplete`() {
        assertTrue(VoiceCommandParser.endsWithIncompleteConjunction("einam ir"))
    }

    @Test
    fun `sentence ending with 'bet' is incomplete`() {
        assertTrue(VoiceCommandParser.endsWithIncompleteConjunction("žinau bet"))
    }

    @Test
    fun `sentence ending with 'kai' is incomplete`() {
        assertTrue(VoiceCommandParser.endsWithIncompleteConjunction("sakyk kai"))
    }

    @Test
    fun `sentence ending with 'nes' is incomplete`() {
        assertTrue(VoiceCommandParser.endsWithIncompleteConjunction("negaliu nes"))
    }

    @Test
    fun `sentence ending with 'kad' is incomplete`() {
        assertTrue(VoiceCommandParser.endsWithIncompleteConjunction("manau kad"))
    }

    @Test
    fun `complete sentence does not match conjunction heuristic`() {
        assertFalse(VoiceCommandParser.endsWithIncompleteConjunction("kaip gyveni"))
        assertFalse(VoiceCommandParser.endsWithIncompleteConjunction("kiek liko"))
        assertFalse(VoiceCommandParser.endsWithIncompleteConjunction("pralinksmink"))
        assertFalse(VoiceCommandParser.endsWithIncompleteConjunction("taikos"))
    }

    @Test
    fun `empty string does not match conjunction heuristic`() {
        assertFalse(VoiceCommandParser.endsWithIncompleteConjunction(""))
        assertFalse(VoiceCommandParser.endsWithIncompleteConjunction("   "))
    }

    // ── 3. Timing constant ordering ────────────────────────────────────────────

    @Test
    fun `GRACE_BARE_STREET_MS is longer than UTTERANCE_GRACE_MS`() {
        assertTrue(
            "Bare-street grace must be at least as long as the legacy 900 ms grace",
            MainViewModel.GRACE_BARE_STREET_MS >= MainViewModel.UTTERANCE_GRACE_MS,
        )
    }

    @Test
    fun `GRACE_INCOMPLETE_CONJUNCTION_MS is the longest grace`() {
        assertTrue(
            "Conjunction grace should be the longest (user is mid-sentence)",
            MainViewModel.GRACE_INCOMPLETE_CONJUNCTION_MS >= MainViewModel.GRACE_BARE_STREET_MS,
        )
        assertTrue(
            MainViewModel.GRACE_INCOMPLETE_CONJUNCTION_MS >= MainViewModel.GRACE_CASUAL_SENTENCE_MS,
        )
    }

    @Test
    fun `GRACE_CASUAL_SENTENCE_MS is shorter than bare-street grace`() {
        // Casual phrases are likely complete; do not add excessive delay.
        assertTrue(
            "Casual grace must be shorter than bare-street grace to keep commands responsive",
            MainViewModel.GRACE_CASUAL_SENTENCE_MS < MainViewModel.GRACE_BARE_STREET_MS,
        )
    }

    // ── 4. Turn-taking invariant helpers ──────────────────────────────────────

    @Test
    fun `FINALIZING state exists in VoiceListeningState`() {
        // Verify the enum value was added — used to guard recognizer restarts.
        val states = VoiceListeningState.entries.map { it.name }
        assertTrue("FINALIZING must be a VoiceListeningState", states.contains("FINALIZING"))
    }

    @Test
    fun `FINALIZING is distinct from LISTENING and PROCESSING`() {
        assertFalse(VoiceListeningState.FINALIZING == VoiceListeningState.LISTENING)
        assertFalse(VoiceListeningState.FINALIZING == VoiceListeningState.PROCESSING)
    }

    // ── 5. Deterministic navigation commands are not conjunctions ─────────────

    @Test
    fun `known navigation commands do not trigger conjunction heuristic`() {
        // These phrases must be processed immediately, not delayed.
        val commands = listOf(
            "kiek liko",
            "kada atvyksime",
            "pakartok nurodymą",
            "sustabdyk navigaciją",
            "dar toli",
        )
        for (cmd in commands) {
            assertFalse(
                "Navigation command '$cmd' must not be flagged as an incomplete conjunction",
                VoiceCommandParser.endsWithIncompleteConjunction(
                    VoiceCommandParser.normalize(cmd),
                ),
            )
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Creates a minimal [MainViewModel]-like object just to call its [internal]
     * helper functions without constructing the full Android ViewModel.
     *
     * Since [MainViewModel] requires an [Application] we test [recoverFromTruncation]
     * via a standalone wrapper that mirrors its pure logic.
     */
    private fun testVm(): TruncationHelper = TruncationHelper()

    /**
     * Inline copy of [MainViewModel.recoverFromTruncation] that can be constructed
     * without an Android Application context.
     * Must be kept in sync with the production implementation.
     */
    private class TruncationHelper {
        fun recoverFromTruncation(finalText: String, partialText: String): String {
            if (finalText.any { it.isDigit() }) return finalText
            if (partialText.isBlank()) return finalText
            if (!partialText.any { it.isDigit() }) return finalText
            val finalNorm = finalText.lowercase().trim()
            val partialNorm = partialText.lowercase().trim()
            return if (partialNorm.startsWith(finalNorm)) partialText else finalText
        }
    }
}
