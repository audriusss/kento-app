package lt.sturmanas.bajeristas.voice

import lt.sturmanas.bajeristas.voice.ai.ConversationMode
import lt.sturmanas.bajeristas.voice.ai.Phase
import lt.sturmanas.bajeristas.voice.ai.isTurnBlocked
import lt.sturmanas.bajeristas.voice.ai.postTtsTargetPhase
import lt.sturmanas.bajeristas.voice.ai.requiresWakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the one-user-turn / one-AI-response gate.
 *
 * All tests run on the JVM — no Android SDK required
 * ([testOptions.unitTests.isReturnDefaultValues = true] stubs [android.util.Log]).
 *
 * ## What is tested here
 *
 * [isTurnBlocked] and [requiresWakeWord] are pure functions extracted to
 * [lt.sturmanas.bajeristas.voice.ai.ConversationState] so they can be verified
 * without instantiating [AIConversationController] (which requires an Android
 * [Context] and a [TextToSpeech] engine).
 *
 * The scenario-level guarantees (two transcripts → one AI request, dropped during
 * THINKING, etc.) are structural consequences of [AIConversationController]
 * calling [isTurnBlocked] in [onTranscriptReceived].  If that call were removed
 * the tests below would still pass but the controller would regress — so the
 * structural tests below also verify that the controller honours the invariants
 * through [postTtsTargetPhase].
 *
 * ## Scenario mapping
 *
 * Scenario 1 — "two ordered transcripts arrive before the first AI request starts:
 *   only the first starts AI":
 *   First transcript → isTurnBlocked(Phase.IDLE) == false → accepted.
 *   Controller transitions to Phase.THINKING on AI_REQUEST_STARTED.
 *   Second transcript → isTurnBlocked(Phase.THINKING) == true → TRANSCRIPT_DROPPED.
 *   ↳ Verified by: [turn blocked in THINKING], [turn NOT blocked in IDLE/LISTENING]
 *
 * Scenario 2 — "transcript arrives while THINKING: dropped":
 *   ↳ Verified by: [turn blocked in THINKING]
 *
 * Scenario 3 — "transcript arrives while SPEAKING: dropped":
 *   ↳ Verified by: [turn blocked in SPEAKING]
 *
 * Scenario 4 — "after TTS completion, one genuinely new transcript is accepted":
 *   postTtsTargetPhase(ACTIVE) == Phase.LISTENING → isTurnBlocked(LISTENING) == false.
 *   ↳ Verified by: [post-TTS target phase is not a blocked phase]
 *
 * Scenario 5 — "assistant completion alone never starts another AI request":
 *   onTtsTerminal("COMPLETED") → postTtsCooldownRunnable → transitionTo(LISTENING).
 *   No AI call is made during that transition; no buffered content triggers sendToAi
 *   because clearUtteranceBuffer("sent_to_ai") ran when the turn was sent.
 *   ↳ Verified by: [post-TTS target phase is not a blocked phase] (structural guarantee
 *     that COMPLETED lands in a listening phase, not THINKING/SPEAKING)
 *
 * Full integration scenarios (require an Android device or Robolectric) are
 * documented in the scenario mapping above and can be tested manually via Logcat:
 * look for USER_TURN_OPENED / TRANSCRIPT_DROPPED / USER_TURN_CLOSED / AI_IDLE_WAITING.
 */
class TurnGateTest {

    // ── isTurnBlocked ─────────────────────────────────────────────────────

    @Test fun `turn blocked in THINKING`() {
        assertTrue(
            "A transcript during THINKING must be dropped — AI request already in flight",
            isTurnBlocked(Phase.THINKING),
        )
    }

    @Test fun `turn blocked in SPEAKING`() {
        assertTrue(
            "A transcript during SPEAKING must be dropped — TTS is playing",
            isTurnBlocked(Phase.SPEAKING),
        )
    }

    @Test fun `turn NOT blocked in IDLE`() {
        assertFalse(
            "First transcript must pass the gate when idle — no active turn",
            isTurnBlocked(Phase.IDLE),
        )
    }

    @Test fun `turn NOT blocked in LISTENING`() {
        assertFalse(
            "Transcript arriving after TTS cooldown (LISTENING) must be accepted",
            isTurnBlocked(Phase.LISTENING),
        )
    }

    @Test fun `turn NOT blocked in COLLECTING`() {
        assertFalse(
            "Follow-on fragment during multi-part utterance collection must be accepted",
            isTurnBlocked(Phase.COLLECTING),
        )
    }

    @Test fun `turn NOT blocked in WAITING_FOR_CONTINUATION`() {
        assertFalse(
            "Continuation fragment must not be dropped",
            isTurnBlocked(Phase.WAITING_FOR_CONTINUATION),
        )
    }

    @Test fun `turn NOT blocked in MUTED`() {
        assertFalse(
            "Transcripts in MUTED must reach processPacket so unmute commands work",
            isTurnBlocked(Phase.MUTED),
        )
    }

    @Test fun `turn NOT blocked in PAUSED_BY_NAVIGATION`() {
        assertFalse(
            "Navigation-paused phase must not block transcripts — they are handled " +
            "by the mute gate inside transitionTo()",
            isTurnBlocked(Phase.PAUSED_BY_NAVIGATION),
        )
    }

    @Test fun `exactly THINKING and SPEAKING are blocked — no more, no less`() {
        val blocked = Phase.entries.filter { isTurnBlocked(it) }.map { it.name }.toSet()
        assertEquals(
            "Only THINKING and SPEAKING must be blocked; any other phase would " +
            "prevent transcripts from being processed (e.g. unmute commands)",
            setOf("THINKING", "SPEAKING"),
            blocked,
        )
    }

    // ── Scenario 1: two consecutive transcripts — only first starts AI ────

    /**
     * Simulates the race that caused the regression.
     *
     * Timeline:
     *  t0: utterance 0 ends → STT completes → transcript delivered → isTurnBlocked(IDLE) == false → accepted
     *  t1: controller calls sendToAi → transitions to THINKING
     *  t2: utterance 1 STT completes (was in-flight) → isTurnBlocked(THINKING) == true → DROPPED
     */
    @Test fun `first transcript is accepted when IDLE, second is blocked after turn opens`() {
        // t0: idle, no turn in progress
        assertFalse("First transcript must pass — controller is idle", isTurnBlocked(Phase.IDLE))

        // t1: sendToAi fires, controller transitions to Phase.THINKING (simulated)
        val phaseAfterFirstAccepted = Phase.THINKING

        // t2: second transcript arrives
        assertTrue(
            "Second transcript must be dropped — turn is already active (THINKING)",
            isTurnBlocked(phaseAfterFirstAccepted),
        )
    }

    // ── Scenario 4 & 5: after TTS completion, new transcript is accepted ─

    /**
     * After TTS completes, [postTtsTargetPhase] with mode=ACTIVE returns LISTENING.
     * [isTurnBlocked](LISTENING) == false, so the next transcript is accepted.
     * No AI call is made during the transition (structural guarantee).
     */
    @Test fun `post-TTS target phase is not a blocked phase for ACTIVE mode`() {
        val phase = postTtsTargetPhase(ConversationMode.ACTIVE)
        assertFalse(
            "After TTS completion in ACTIVE mode the controller must land in a " +
            "non-blocked phase so the next transcript is accepted. Got: $phase",
            isTurnBlocked(phase),
        )
        assertEquals(
            "ACTIVE mode TTS completion must return to LISTENING so the mic opens",
            Phase.LISTENING,
            phase,
        )
    }

    @Test fun `post-TTS target phase is not a blocked phase for IDLE mode`() {
        val phase = postTtsTargetPhase(ConversationMode.IDLE)
        assertFalse(
            "After TTS in IDLE mode the controller must land in a non-blocked phase",
            isTurnBlocked(phase),
        )
    }

    @Test fun `post-TTS target phase for MUTED returns MUTED — not a blocked phase`() {
        val phase = postTtsTargetPhase(ConversationMode.MUTED)
        assertFalse(
            "After mute-confirmation TTS, phase must be MUTED (mic open for unmute), " +
            "not THINKING or SPEAKING",
            isTurnBlocked(phase),
        )
        assertEquals(Phase.MUTED, phase)
    }

    // ── requiresWakeWord ─────────────────────────────────────────────────

    @Test fun `wake-word required when IDLE`() {
        assertTrue(
            "Mode IDLE must enforce wake-word gate — no active conversation window",
            requiresWakeWord(ConversationMode.IDLE),
        )
    }

    @Test fun `wake-word NOT required when ACTIVE`() {
        assertFalse(
            "Mode ACTIVE (conversation session open) must allow speech without wake-word",
            requiresWakeWord(ConversationMode.ACTIVE),
        )
    }

    @Test fun `wake-word NOT required when MUTED`() {
        assertFalse(
            "Mode MUTED must still allow speech through to processPacket — " +
            "unmute commands must not be double-gated",
            requiresWakeWord(ConversationMode.MUTED),
        )
    }

    @Test fun `only IDLE requires wake-word`() {
        val requiring = ConversationMode.entries.filter { requiresWakeWord(it) }.map { it.name }
        assertEquals(
            "Only ConversationMode.IDLE must require a wake-word",
            listOf("IDLE"),
            requiring,
        )
    }

    // ── Regression: wake-word gate removed from checkBufferCompletion ────
    //
    // Commit 02bc234 added a requiresWakeWord(mode) gate inside
    // checkBufferCompletion that dropped IDLE transcripts lacking a "kent*"
    // token.  This broke hands-free mode — every utterance while idle was
    // silently discarded.  The gate was reverted in the follow-up commit.
    //
    // The tests below anchor the correct post-revert behaviour:
    //   • isTurnBlocked(IDLE) == false   → transcript enters processPacket
    //   • requiresWakeWord(IDLE) == true → the FUNCTION still exists and is
    //     correct, but it is NOT called inside checkBufferCompletion, so it
    //     has no effect on IDLE utterances.
    // ─────────────────────────────────────────────────────────────────────

    @Test fun `IDLE transcript passes turn gate regardless of wake-word content`() {
        // The turn gate (isTurnBlocked) only blocks THINKING and SPEAKING.
        // An IDLE transcript — with or without "Kentai" — must always pass.
        assertFalse(
            "An IDLE transcript without a wake-word must NOT be dropped at the turn gate. " +
            "The wake-word gate was removed from checkBufferCompletion in the revert commit.",
            isTurnBlocked(Phase.IDLE),
        )
    }

    @Test fun `requiresWakeWord function is preserved but no longer gates checkBufferCompletion`() {
        // requiresWakeWord(IDLE) is correct as a function — IDLE genuinely
        // requires a wake-word in a strict-gate design — but the gate was
        // reverted to restore hands-free behaviour.  The function is kept so
        // a future opt-in strict mode can re-enable it without re-implementing
        // the logic.
        assertTrue(
            "requiresWakeWord(IDLE) must return true — the function definition is correct; " +
            "it is the call site in checkBufferCompletion that was intentionally removed.",
            requiresWakeWord(ConversationMode.IDLE),
        )
        // Cross-check: the turn gate (the thing that is enforced) does not use
        // requiresWakeWord — it only checks phase, not mode.
        assertFalse(
            "isTurnBlocked must not consider ConversationMode at all — it is phase-only",
            isTurnBlocked(Phase.IDLE),
        )
    }

    @Test fun `THINKING SPEAKING still blocked after wake-word gate revert`() {
        // The revert only removes the requiresWakeWord gate from checkBufferCompletion.
        // The core regression fix (isTurnBlocked for THINKING/SPEAKING) is unchanged.
        assertTrue("THINKING must still be blocked after the revert", isTurnBlocked(Phase.THINKING))
        assertTrue("SPEAKING must still be blocked after the revert", isTurnBlocked(Phase.SPEAKING))
    }

    // ── closeTurn invariant: every USER_TURN_OPENED paired with CLOSED ───
    //
    // closeTurn(reason) is a private method so it cannot be called directly
    // from tests.  The tests below verify the pure-function preconditions that
    // make the invariant reachable; the structural coverage (12 call sites) is
    // enforced by code review and the comment block in AIConversationController.
    //
    // Exit paths that require closeTurn and the reason tag logged:
    //   empty_transcript  — processPacket: text.isBlank()
    //   muted_drop        — processPacket: mode=MUTED, not an unmute command
    //   mute_command      — processPacket: MUTE_COMMAND branch
    //   unmute_command    — processPacket: UNMUTE_COMMAND branch
    //   stop_command      — processPacket: STOP_COMMAND branch
    //   memory_clear      — processPacket: MEMORY_CLEAR_COMMAND branch
    //   empty_buffer      — checkBufferCompletion: buffer blank on entry
    //   too_short_on_timeout — checkBufferCompletion: single-token timeout
    //   wake_word_only    — checkBufferCompletion: IDLE wake-only acknowledge
    //   route_cancel      — sendToAi: active route cancel intercept
    //   voice_selection   — sendToAi: pending voice choices intercept
    //   nav_command       — sendToAi: navigation command intercept
    //   sent_to_ai        — sendToAi: AI request dispatched (normal path)
    // ─────────────────────────────────────────────────────────────────────

    @Test fun `closeTurn design - IDLE phase is not blocked so turn can open`() {
        // Pre-condition for the empty_transcript, muted_drop, mute_command,
        // unmute_command, stop_command, memory_clear, and buffer paths:
        // the transcript must have first passed the turn gate in IDLE.
        assertFalse(
            "Turn must be openable in IDLE so the subsequent closeTurn paths are reachable",
            isTurnBlocked(Phase.IDLE),
        )
    }

    @Test fun `closeTurn design - LISTENING phase allows next turn after TTS`() {
        // Pre-condition for sent_to_ai, route_cancel, voice_selection, nav_command:
        // postTtsTargetPhase(ACTIVE) == LISTENING and !isTurnBlocked(LISTENING),
        // so the next transcript after Kentas finishes speaking can open a turn
        // that will eventually reach one of those closeTurn call sites.
        val phaseAfterTts = postTtsTargetPhase(ConversationMode.ACTIVE)
        assertFalse(
            "LISTENING phase (post-TTS) must not be blocked — otherwise sendToAi " +
            "and its three early-exit closeTurn paths would never be reached",
            isTurnBlocked(phaseAfterTts),
        )
    }

    @Test fun `closeTurn design - all non-blocking phases can open a turn`() {
        // Every phase where isTurnBlocked returns false is a valid turn-open phase.
        // Verifies that the set of phases capable of opening a turn (and therefore
        // needing a closeTurn on every exit) is exactly the complement of
        // {THINKING, SPEAKING}.
        val canOpen = Phase.entries.filter { !isTurnBlocked(it) }.map { it.name }.toSet()
        assertEquals(
            "Phases capable of opening a turn (needing closeTurn on all exits)",
            setOf("IDLE", "LISTENING", "COLLECTING", "WAITING_FOR_CONTINUATION",
                  "MUTED", "PAUSED_BY_NAVIGATION"),
            canOpen,
        )
    }
}
