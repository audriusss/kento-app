package lt.sturmanas.bajeristas.voice.pipeline

import lt.sturmanas.bajeristas.voice.ai.ConversationMode
import lt.sturmanas.bajeristas.voice.ai.Phase
import lt.sturmanas.bajeristas.voice.ai.PipelineAction
import lt.sturmanas.bajeristas.voice.ai.pipelineActionForPhase
import lt.sturmanas.bajeristas.voice.ai.postTtsTargetPhase
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests for the Phase 3 VAD pipeline migration.
 *
 * All tests run on a plain JVM — no Android SDK, no Robolectric.
 *
 * ## What is tested
 *
 * PI-01  [UtteranceSegmenter.reset] on a fresh instance leaves state as SILENCE.
 * PI-02  [UtteranceSegmenter.reset] after a noise burst (POSSIBLE_SPEECH) returns to SILENCE.
 * PI-03  [UtteranceSegmenter.reset] after confirmed SPEECH clears [isInSpeech].
 * PI-04  [UtteranceSegmenter.reset] clears the mute flag set by [UtteranceSegmenter.mute].
 * PI-05  [UtteranceSegmenter.reset] clears pre-roll so old audio cannot contaminate next utterance.
 * PI-06  mute → unmute → reset yields a clean SILENCE state with no state bleed.
 * PI-07  [PipelineConfig.SAMPLE_RATE] is 16 000.
 * PI-08  [PipelineConfig.POST_TTS_COOLDOWN_MS] is 200 ms.
 * PI-09  [PipelineConfig.TRANSCRIPTION_MODEL] is "gpt-4o-transcribe".
 * PI-10  [PipelineConfig.CHUNK_MS] equals CHUNK_SAMPLES × 1000 / SAMPLE_RATE.
 * PI-11  [MicrophonePipeline] interface declares all five required methods.
 *
 * Mute regression (PI-12..PI-14)
 * PI-12  postTtsTargetPhase(MUTED) returns MUTED — mic stays muted after confirmation TTS.
 * PI-13  postTtsTargetPhase(ACTIVE) returns LISTENING — normal listen resumes after AI TTS.
 * PI-14  postTtsTargetPhase(IDLE) returns IDLE — no mic open in idle mode after TTS.
 *
 * Pipeline action / mute invariants (PI-15..PI-17)
 * PI-15  pipelineActionForPhase(MUTED) == UNMUTE — mic stays open for voice unmute detection.
 * PI-16  pipelineActionForPhase(SPEAKING) == MUTE — mic silenced during AI TTS.
 * PI-17  pipelineActionForPhase(LISTENING) == UNMUTE — mic open when ready to listen.
 * PI-18  pipelineActionForPhase(THINKING) == MUTE — nav update must not unmute during AI think.
 * PI-19  pipelineActionForPhase(PAUSED_BY_NAVIGATION) == MUTE — nav audio owns the pipe.
 *
 * Exhaustive coverage (PI-20..PI-22)
 * PI-20  All MUTE-action phases are covered — catches a new Phase added without updating pipelineActionForPhase.
 * PI-21  All UNMUTE-action phases are covered.
 * PI-22  postTtsTargetPhase covers all ConversationMode values.
 */
class VoicePipelineIntegrationTest {

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun chunk(size: Int = PipelineConfig.CHUNK_BYTES) = ByteArray(size) { 0 }

    private fun segmenterWithFastConfirm() = UtteranceSegmenter(
        UtteranceSegmenter.Config(
            speechThreshold = 0.5f,
            minSpeechMs     = 64,   // 2 chunks at 32 ms each → confirmed after 2nd speech chunk
            trailingSilenceMs = 32,
            chunkMs         = 32,
        ),
    )

    // ── PI-01 ─────────────────────────────────────────────────────────────

    @Test fun `reset on fresh instance leaves state as SILENCE`() {
        val s = UtteranceSegmenter()
        s.reset()
        assertEquals("SILENCE", s.currentState)
        assertFalse(s.isInSpeech)
    }

    // ── PI-02 ─────────────────────────────────────────────────────────────

    @Test fun `reset after noise burst (POSSIBLE_SPEECH) returns to SILENCE`() {
        // Default minSpeechMs=250 → minSpeechChunks=7; one speech chunk → POSSIBLE_SPEECH only.
        val s = UtteranceSegmenter()
        s.processChunk(chunk(), 0.9f)           // SILENCE → POSSIBLE_SPEECH
        assertEquals("POSSIBLE_SPEECH", s.currentState)
        s.reset()
        assertEquals("SILENCE", s.currentState)
        assertFalse(s.isInSpeech)
    }

    // ── PI-03 ─────────────────────────────────────────────────────────────

    @Test fun `reset after confirmed SPEECH clears isInSpeech`() {
        // minSpeechMs=64, chunkMs=32 → minSpeechChunks=2.
        // Chunk 1: SILENCE → POSSIBLE_SPEECH (speechChunkCount=1)
        // Chunk 2: POSSIBLE_SPEECH → SPEECH    (speechChunkCount=2 ≥ 2)
        val s = segmenterWithFastConfirm()
        s.processChunk(chunk(), 0.9f)   // → POSSIBLE_SPEECH
        s.processChunk(chunk(), 0.9f)   // → SPEECH
        assertTrue("should be in SPEECH", s.isInSpeech)
        s.reset()
        assertEquals("SILENCE", s.currentState)
        assertFalse("isInSpeech must be false after reset", s.isInSpeech)
    }

    // ── PI-04 ─────────────────────────────────────────────────────────────

    @Test fun `reset clears mute flag so subsequent chunks are processed`() {
        val s = UtteranceSegmenter()
        s.mute()
        // While muted, chunks return empty list.
        assertTrue(s.processChunk(chunk(), 0.9f).isEmpty())
        s.reset()
        // After reset the mute flag must be cleared.
        val events = s.processChunk(chunk(), 0.9f)
        assertFalse("After reset, chunks must be processed (mute flag must be cleared)", events.isEmpty())
    }

    // ── PI-05 ─────────────────────────────────────────────────────────────

    @Test fun `reset clears pre-roll so old silence chunks cannot contaminate next utterance`() {
        // Fill pre-roll with 3 large silence chunks, then reset.
        // After reset, the first speech chunk should produce a SpeechCandidate event
        // without dragging the old large pre-roll chunks into the utterance.
        val config = UtteranceSegmenter.Config(
            speechThreshold = 0.5f,
            minSpeechMs     = 32,   // 1 chunk confirms POSSIBLE_SPEECH
            trailingSilenceMs = 32,
            preRollMs       = 96,   // 3 chunks
            chunkMs         = 32,
        )
        val s = UtteranceSegmenter(config)
        repeat(3) { s.processChunk(ByteArray(PipelineConfig.CHUNK_BYTES) { 42 }, 0.0f) }
        s.reset()
        // First speech chunk after reset: must start a fresh utterance (SpeechCandidate).
        val events = s.processChunk(chunk(), 0.9f)
        val hasCandidateOrStarted = events.any {
            it is UtteranceSegmenter.Event.SpeechCandidate ||
            it is UtteranceSegmenter.Event.SpeechStarted
        }
        assertTrue("Expected SpeechCandidate or SpeechStarted after reset, got: $events", hasCandidateOrStarted)
    }

    // ── PI-06 ─────────────────────────────────────────────────────────────

    @Test fun `mute then unmute then reset yields clean SILENCE with fresh utterance possible`() {
        val s = segmenterWithFastConfirm()
        s.processChunk(chunk(), 0.9f)   // → POSSIBLE_SPEECH
        s.processChunk(chunk(), 0.9f)   // → SPEECH
        assertTrue(s.isInSpeech)
        s.mute()
        s.unmute()
        s.reset()
        assertEquals("SILENCE", s.currentState)
        // After a clean reset, one speech chunk should trigger SpeechCandidate.
        val events = s.processChunk(chunk(), 0.9f)
        assertTrue(
            "Expected SpeechCandidate after full reset, got: $events",
            events.any { it is UtteranceSegmenter.Event.SpeechCandidate },
        )
    }

    // ── PI-07 ─────────────────────────────────────────────────────────────

    @Test fun `PipelineConfig SAMPLE_RATE is 16000`() {
        assertEquals(16_000, PipelineConfig.SAMPLE_RATE)
    }

    // ── PI-08 ─────────────────────────────────────────────────────────────

    @Test fun `PipelineConfig POST_TTS_COOLDOWN_MS is 200`() {
        assertEquals(200L, PipelineConfig.POST_TTS_COOLDOWN_MS)
    }

    // ── PI-09 ─────────────────────────────────────────────────────────────

    @Test fun `PipelineConfig TRANSCRIPTION_MODEL is gpt-4o-transcribe`() {
        assertEquals("gpt-4o-transcribe", PipelineConfig.TRANSCRIPTION_MODEL)
    }

    // ── PI-10 ─────────────────────────────────────────────────────────────

    @Test fun `PipelineConfig CHUNK_MS equals CHUNK_SAMPLES times 1000 divided by SAMPLE_RATE`() {
        val expected = PipelineConfig.CHUNK_SAMPLES * 1000 / PipelineConfig.SAMPLE_RATE
        assertEquals(
            "CHUNK_MS must be CHUNK_SAMPLES*1000/SAMPLE_RATE = $expected ms",
            expected,
            PipelineConfig.CHUNK_MS,
        )
    }

    // ── PI-11 ─────────────────────────────────────────────────────────────

    @Test fun `MicrophonePipeline interface declares all five required methods`() {
        val methods = MicrophonePipeline::class.java.methods.map { it.name }.toSet()
        listOf("start", "stop", "mute", "unmute", "resetVadAndSegmenter").forEach { name ->
            assertTrue("MicrophonePipeline is missing required method: '$name'", methods.contains(name))
        }
    }

    // ── PI-12 — mute regression ───────────────────────────────────────────
    //
    // Regression: when mode=MUTED, postTtsCooldownRunnable used to route to Phase.IDLE
    // which called pipeline.unmute() and re-opened the mic after the confirmation TTS.
    // The fix: postTtsTargetPhase(MUTED) must return Phase.MUTED.

    @Test fun `postTtsTargetPhase MUTED returns MUTED so mic stays closed after confirmation TTS`() {
        val result = postTtsTargetPhase(ConversationMode.MUTED)
        assertEquals(
            "Mute-command confirmation TTS must leave phase as MUTED, not ${result.name}",
            Phase.MUTED,
            result,
        )
        assertNotEquals(
            "postTtsTargetPhase(MUTED) must NOT return IDLE (that would reopen the mic)",
            Phase.IDLE,
            result,
        )
        assertNotEquals(
            "postTtsTargetPhase(MUTED) must NOT return LISTENING (that would reopen the mic)",
            Phase.LISTENING,
            result,
        )
    }

    // ── PI-13 ─────────────────────────────────────────────────────────────

    @Test fun `postTtsTargetPhase ACTIVE returns LISTENING so mic reopens after normal AI TTS`() {
        assertEquals(Phase.LISTENING, postTtsTargetPhase(ConversationMode.ACTIVE))
    }

    // ── PI-14 ─────────────────────────────────────────────────────────────

    @Test fun `postTtsTargetPhase IDLE returns IDLE so mic stays closed in idle mode after TTS`() {
        assertEquals(Phase.IDLE, postTtsTargetPhase(ConversationMode.IDLE))
    }

    // ── PI-15 — unmute-while-muted regression ─────────────────────────────
    //
    // Regression: Phase.MUTED previously called pipeline.mute(), making MUTED a
    // terminal state with no voice escape.  The fix: Phase.MUTED must keep the
    // pipeline UNMUTED so the driver can speak an unmute command.
    // The mode-level guard in processPacket() blocks all non-unmute content.

    @Test fun `Phase MUTED keeps pipeline UNMUTED so driver can speak a voice unmute command`() {
        val action = pipelineActionForPhase(Phase.MUTED)
        assertEquals(
            "Phase.MUTED must not mute the pipeline — driver needs mic open to say 'kalbek'",
            PipelineAction.UNMUTE,
            action,
        )
        assertNotEquals(
            "Phase.MUTED must NOT mute the pipeline (that makes MUTED a terminal state)",
            PipelineAction.MUTE,
            action,
        )
    }

    // ── PI-16 ─────────────────────────────────────────────────────────────

    @Test fun `Phase SPEAKING mutes the pipeline to prevent self-recognition of TTS audio`() {
        assertEquals(PipelineAction.MUTE, pipelineActionForPhase(Phase.SPEAKING))
    }

    // ── PI-17 ─────────────────────────────────────────────────────────────

    @Test fun `Phase LISTENING unmutes the pipeline so transcripts can arrive`() {
        assertEquals(PipelineAction.UNMUTE, pipelineActionForPhase(Phase.LISTENING))
    }

    // ── PI-18 — startListening() regression ──────────────────────────────
    //
    // Regression: startListening() lacked guards for THINKING/SPEAKING, so periodic
    // nav-state updates from MainViewModel.startObserving() could force-unmute the mic
    // during TTS playback, risking self-recognition echo.
    // Fix: startListening() gates on pipelineActionForPhase(phase) != MUTE.

    @Test fun `Phase THINKING returns MUTE so startListening cannot unmute during AI think`() {
        assertEquals(
            "Nav update during THINKING must not bypass pipelineActionForPhase gate",
            PipelineAction.MUTE,
            pipelineActionForPhase(Phase.THINKING),
        )
    }

    // ── PI-19 ─────────────────────────────────────────────────────────────

    @Test fun `Phase PAUSED_BY_NAVIGATION returns MUTE so nav audio owns the pipeline`() {
        assertEquals(PipelineAction.MUTE, pipelineActionForPhase(Phase.PAUSED_BY_NAVIGATION))
    }

    // ── PI-20 — exhaustive MUTE-action phases ─────────────────────────────
    //
    // Catches a newly added Phase enum value that was not mapped in
    // pipelineActionForPhase (would cause a compile-time when-exhaustive error,
    // but this test documents the expected set explicitly).

    @Test fun `all expected phases that MUTE the pipeline are enumerated`() {
        val expectedMutePhases = setOf(
            Phase.THINKING,
            Phase.SPEAKING,
            Phase.PAUSED_BY_NAVIGATION,
        )
        expectedMutePhases.forEach { phase ->
            assertEquals(
                "Phase.$phase should map to MUTE",
                PipelineAction.MUTE,
                pipelineActionForPhase(phase),
            )
        }
    }

    // ── PI-21 — exhaustive UNMUTE-action phases ────────────────────────────

    @Test fun `all expected phases that UNMUTE the pipeline are enumerated`() {
        val expectedUnmutePhases = setOf(
            Phase.IDLE,
            Phase.LISTENING,
            Phase.COLLECTING,
            Phase.WAITING_FOR_CONTINUATION,
            Phase.MUTED,    // mic stays open for voice unmute detection
        )
        expectedUnmutePhases.forEach { phase ->
            assertEquals(
                "Phase.$phase should map to UNMUTE",
                PipelineAction.UNMUTE,
                pipelineActionForPhase(phase),
            )
        }
    }

    // ── PI-22 — exhaustive postTtsTargetPhase coverage ────────────────────

    @Test fun `postTtsTargetPhase covers all ConversationMode values`() {
        // ACTIVE → LISTENING: mic opens after AI TTS finishes.
        assertEquals(Phase.LISTENING, postTtsTargetPhase(ConversationMode.ACTIVE))
        // MUTED → MUTED: confirmation TTS must not reopen the mic.
        assertEquals(Phase.MUTED, postTtsTargetPhase(ConversationMode.MUTED))
        // IDLE → IDLE: no listening in pure idle mode after TTS.
        assertEquals(Phase.IDLE, postTtsTargetPhase(ConversationMode.IDLE))

        // Exhaustiveness: every ConversationMode value must be handled.
        ConversationMode.values().forEach { mode ->
            // Just calling the function must not throw (exhaustive when).
            val result = postTtsTargetPhase(mode)
            assertNotNull("postTtsTargetPhase($mode) must return a non-null Phase", result)
        }
    }
}
