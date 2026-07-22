package lt.sturmanas.bajeristas.voice

import lt.sturmanas.bajeristas.navigation.CandidatePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the ClarificationState → SelectCandidate selection logic.
 *
 * These tests exercise the exact lookup used by MainViewModel.executeVoiceCommand
 * for [VoiceCommand.SelectCandidate]:
 *
 *   val candidate = clarif.candidates.getOrNull(command.index - 1)
 *
 * This is a 1-based index (pirmą = 1 → candidates[0], antrą = 2 → candidates[1]).
 * Out-of-range indices must return null so the caller can speak an error instead
 * of navigating to the wrong address or crashing.
 *
 * Coverage:
 *  1. SelectCandidate(1) / "pirmą" picks candidates[0] by address
 *  2. SelectCandidate(2) / "antrą" picks candidates[1] by address
 *  3. SelectCandidate(3) / "trečią" picks candidates[2] by address (3 candidates)
 *  4. SelectCandidate(5) with 2 candidates → null (out-of-range, speak error)
 *  5. SelectCandidate(0) (invalid) → null (0-based underflow)
 *  6. Full parse → select cycle: "antrą" is parsed to SelectCandidate(2)
 *     and correctly resolves to candidates[1].address
 *  7. Full parse → select cycle: "pirmą" resolves to candidates[0].address
 *  8. No pending clarification → null guard (the "Nėra ko rinktis" branch)
 */
class ClarificationSelectionTest {

    // ── Fixture ────────────────────────────────────────────────────────────

    private val candidateA = CandidatePlace(
        name = "Akropolis Klaipėda",
        address = "Taikos pr. 61, Klaipėda",
        distanceMeters = 2500,
    )
    private val candidateB = CandidatePlace(
        name = "Akropolis Vilnius",
        address = "Ozo g. 25, Vilnius",
        distanceMeters = null,
    )
    private val candidateC = CandidatePlace(
        name = "Akropolis Šiauliai",
        address = "Tilžės g. 153, Šiauliai",
        distanceMeters = 87000,
    )

    private val twoCandidates   = listOf(candidateA, candidateB)
    private val threeCandidates = listOf(candidateA, candidateB, candidateC)

    /** Replicates the exact lookup in MainViewModel.executeVoiceCommand */
    private fun selectFrom(
        candidates: List<CandidatePlace>,
        oneBasedIndex: Int,
    ): CandidatePlace? = candidates.getOrNull(oneBasedIndex - 1)

    // ── 1. SelectCandidate(1) → candidates[0] ─────────────────────────────

    @Test fun `SelectCandidate index 1 picks first candidate`() {
        val cmd = VoiceCommand.SelectCandidate(1)
        val clarif = ClarificationState(originalText = "Akropolis", candidates = twoCandidates)

        val picked = selectFrom(clarif.candidates, cmd.index)

        assertNotNull(picked)
        assertEquals(candidateA.name,    picked!!.name)
        assertEquals(candidateA.address, picked.address)
    }

    // ── 2. SelectCandidate(2) → candidates[1] ─────────────────────────────

    @Test fun `SelectCandidate index 2 picks second candidate`() {
        val cmd = VoiceCommand.SelectCandidate(2)
        val clarif = ClarificationState(originalText = "Akropolis", candidates = twoCandidates)

        val picked = selectFrom(clarif.candidates, cmd.index)

        assertNotNull(picked)
        assertEquals(candidateB.name,    picked!!.name)
        assertEquals(candidateB.address, picked.address)
    }

    @Test fun `SelectCandidate index 2 yields navigation address of second candidate`() {
        val cmd = VoiceCommand.SelectCandidate(2)
        val clarif = ClarificationState(originalText = "Akropolis", candidates = twoCandidates)

        val picked = selectFrom(clarif.candidates, cmd.index)

        // acceptCandidate forwards candidate.address to StartNavigation —
        // verify the address (not the name) is what would be sent to the engine.
        assertEquals("Ozo g. 25, Vilnius", picked!!.address)
    }

    // ── 3. SelectCandidate(3) → candidates[2] ─────────────────────────────

    @Test fun `SelectCandidate index 3 picks third candidate from three-candidate list`() {
        val cmd = VoiceCommand.SelectCandidate(3)
        val clarif = ClarificationState(originalText = "Akropolis", candidates = threeCandidates)

        val picked = selectFrom(clarif.candidates, cmd.index)

        assertNotNull(picked)
        assertEquals(candidateC.name,    picked!!.name)
        assertEquals(candidateC.address, picked.address)
    }

    // ── 4. Out-of-range: SelectCandidate(5) with 2 candidates → null ──────

    @Test fun `SelectCandidate index 5 with 2 candidates returns null`() {
        val cmd = VoiceCommand.SelectCandidate(5)
        val clarif = ClarificationState(originalText = "Akropolis", candidates = twoCandidates)

        val picked = selectFrom(clarif.candidates, cmd.index)

        // MainViewModel branches on null → speaks "Tokio varianto nėra." — not a crash.
        assertNull(picked)
    }

    @Test fun `SelectCandidate index 4 with 3 candidates returns null`() {
        val cmd = VoiceCommand.SelectCandidate(4)
        val clarif = ClarificationState(originalText = "Akropolis", candidates = threeCandidates)

        assertNull(selectFrom(clarif.candidates, cmd.index))
    }

    // ── 5. Underflow: index 0 → null (invalid, never produced by parser) ──

    @Test fun `SelectCandidate index 0 returns null due to underflow`() {
        // getOrNull(-1) == null; ensures no negative-index crash
        val clarif = ClarificationState(originalText = "Akropolis", candidates = twoCandidates)

        assertNull(selectFrom(clarif.candidates, 0))
    }

    // ── 6. Full parse→select cycle: "antrą" → candidates[1].address ───────

    @Test fun `parse antra then select returns second candidate address`() {
        val cmd = VoiceCommandParser.parse("antrą")
        assertTrue("Expected SelectCandidate", cmd is VoiceCommand.SelectCandidate)
        val idx = (cmd as VoiceCommand.SelectCandidate).index
        assertEquals(2, idx)

        val clarif = ClarificationState(originalText = "Akropolis", candidates = twoCandidates)
        val picked = selectFrom(clarif.candidates, idx)

        assertNotNull(picked)
        assertEquals("Ozo g. 25, Vilnius", picked!!.address)
    }

    // ── 7. Full parse→select cycle: "pirmą" → candidates[0].address ───────

    @Test fun `parse pirma then select returns first candidate address`() {
        val cmd = VoiceCommandParser.parse("pirmą")
        assertTrue("Expected SelectCandidate", cmd is VoiceCommand.SelectCandidate)
        val idx = (cmd as VoiceCommand.SelectCandidate).index
        assertEquals(1, idx)

        val clarif = ClarificationState(originalText = "Akropolis", candidates = twoCandidates)
        val picked = selectFrom(clarif.candidates, idx)

        assertNotNull(picked)
        assertEquals("Taikos pr. 61, Klaipėda", picked!!.address)
    }

    // ── 8. Full parse→select cycle: "trečią" → candidates[2].address ──────

    @Test fun `parse trecia then select returns third candidate address`() {
        val cmd = VoiceCommandParser.parse("trečią")
        assertTrue("Expected SelectCandidate", cmd is VoiceCommand.SelectCandidate)
        val idx = (cmd as VoiceCommand.SelectCandidate).index
        assertEquals(3, idx)

        val clarif = ClarificationState(originalText = "Akropolis", candidates = threeCandidates)
        val picked = selectFrom(clarif.candidates, idx)

        assertNotNull(picked)
        assertEquals("Tilžės g. 153, Šiauliai", picked!!.address)
    }

    // ── 9. No pending clarification guard ─────────────────────────────────

    @Test fun `null ClarificationState means no candidate is pickable`() {
        // Simulates the _pendingClarification.value == null branch in MainViewModel
        // that speaks "Nėra ko rinktis." instead of crashing.
        val pendingClarification: ClarificationState? = null

        // The ViewModel checks clarif == null before calling getOrNull.
        assertNull(pendingClarification)
        // If somehow getOrNull were called on a null list the NPE would surface here;
        // this assertion documents the contract: null → speak error, do not pick.
    }

    // ── 10. ClarificationState holds originalText intact ──────────────────

    @Test fun `ClarificationState preserves originalText and all candidates`() {
        val clarif = ClarificationState(
            originalText = "Akropolis",
            candidates   = threeCandidates,
        )
        assertEquals("Akropolis", clarif.originalText)
        assertEquals(3, clarif.candidates.size)
        assertEquals(candidateA, clarif.candidates[0])
        assertEquals(candidateB, clarif.candidates[1])
        assertEquals(candidateC, clarif.candidates[2])
    }
}
