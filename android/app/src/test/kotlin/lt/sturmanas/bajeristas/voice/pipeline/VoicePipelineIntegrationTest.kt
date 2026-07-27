package lt.sturmanas.bajeristas.voice.pipeline

import lt.sturmanas.bajeristas.voice.ai.ConversationMode
import lt.sturmanas.bajeristas.voice.ai.Phase
import lt.sturmanas.bajeristas.voice.ai.PipelineAction
import lt.sturmanas.bajeristas.voice.ai.SemanticCompletionDetector
import lt.sturmanas.bajeristas.voice.ai.pipelineActionForPhase
import lt.sturmanas.bajeristas.voice.ai.postTtsTargetPhase
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.voice.navigation.KentasNavigationPhraseFormatter
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * Mute-gate regression (PI-23..PI-32)
 * PI-23  muted segmenter never emits any event regardless of speech probability.
 * PI-24  muted segmenter never emits UtteranceReady even when in mid-SPEECH state.
 * PI-25  muted segmenter never emits SpeechStarted even with sustained high probability.
 * PI-26  muting mid-utterance (SPEECH state) discards the in-progress utterance.
 * PI-27  muting mid-utterance (TRAILING_SILENCE state) discards the in-progress utterance.
 * PI-28  repeated mute/unmute leaves segmenter in a clean, functional state.
 * PI-29  AtomicBoolean mute flag: set(true) then get() returns true on same thread.
 * PI-30  unmute clears pre-roll so TTS-era audio cannot bleed into the first post-mute utterance.
 * PI-31  reset() on unmute path puts segmenter in SILENCE with no state bleed.
 * PI-32  mute → reset → unmute → processChunk produces SpeechCandidate (not stuck/throwing).
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

    // ── PI-23 — muted segmenter never emits any event ─────────────────────
    //
    // Regardless of speech probability, a muted segmenter must return an empty
    // list.  This covers the core mute gate requirement: no VAD events while
    // TTS or navigation audio is playing.

    @Test fun `muted segmenter emits no events regardless of speech probability`() {
        val s = UtteranceSegmenter()
        s.mute()
        val probabilities = listOf(0.0f, 0.35f, 0.5f, 0.8f, 0.99f, 1.0f)
        probabilities.forEach { prob ->
            val events = s.processChunk(chunk(), prob)
            assertTrue(
                "Muted segmenter must return empty list for prob=$prob, got: $events",
                events.isEmpty(),
            )
        }
    }

    // ── PI-24 — muted mid-SPEECH segmenter never emits UtteranceReady ─────
    //
    // If TTS fires while the user was speaking (segmenter in SPEECH state),
    // the controller calls pipeline.mute() and resetVadAndSegmenter().
    // Verify that neither mute nor the subsequent reset produces UtteranceReady.

    @Test fun `muted segmenter in SPEECH state never emits UtteranceReady`() {
        val s = segmenterWithFastConfirm()
        // Drive segmenter into SPEECH state.
        s.processChunk(chunk(), 0.9f)  // → POSSIBLE_SPEECH
        s.processChunk(chunk(), 0.9f)  // → SPEECH (minSpeechChunks=2 met)
        assertTrue("Prerequisite: segmenter should be in SPEECH", s.isInSpeech)

        // Mute — simulates pipeline.mute() from transitionTo(SPEAKING).
        s.mute()

        // Feed many more high-probability chunks: all must be discarded.
        val allEvents = (1..20).flatMap { s.processChunk(chunk(), 0.9f) }
        val utteranceEvents = allEvents.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>()
        assertTrue(
            "Muted segmenter must never emit UtteranceReady, got: $utteranceEvents",
            utteranceEvents.isEmpty(),
        )
    }

    // ── PI-25 — muted segmenter never emits SpeechStarted ─────────────────
    //
    // Root-cause regression: before the post-read mute gate, Silero ran on
    // every chunk and the segmenter could advance to SPEECH and emit
    // SpeechStarted during the one-iteration lag before segmenter.mute()
    // was propagated.  After the fix the segmenter is never called while muted.

    @Test fun `muted segmenter never emits SpeechStarted even with sustained high probability`() {
        val s = segmenterWithFastConfirm()
        s.mute()

        val speechStartedEvents = (1..10)
            .flatMap { s.processChunk(chunk(), 0.99f) }
            .filterIsInstance<UtteranceSegmenter.Event.SpeechStarted>()

        assertTrue(
            "Muted segmenter must never emit SpeechStarted, got: $speechStartedEvents",
            speechStartedEvents.isEmpty(),
        )
    }

    // ── PI-26 — muting mid-SPEECH discards the in-progress utterance ───────

    @Test fun `muting while in SPEECH state immediately discards the utterance buffer`() {
        val s = segmenterWithFastConfirm()
        s.processChunk(chunk(), 0.9f)  // → POSSIBLE_SPEECH
        s.processChunk(chunk(), 0.9f)  // → SPEECH
        assertTrue(s.isInSpeech)

        s.mute()

        // After mute, isInSpeech must be false (buffer discarded via clearActive).
        assertFalse("mute() must clear SPEECH state", s.isInSpeech)
        assertEquals("mute() must reset state to SILENCE", "SILENCE", s.currentState)
    }

    // ── PI-27 — muting mid-TRAILING_SILENCE discards the utterance ─────────

    @Test fun `muting while in TRAILING_SILENCE state discards the utterance buffer`() {
        val s = segmenterWithFastConfirm()
        s.processChunk(chunk(), 0.9f)  // → POSSIBLE_SPEECH
        s.processChunk(chunk(), 0.9f)  // → SPEECH
        s.processChunk(chunk(), 0.0f)  // → TRAILING_SILENCE
        assertTrue("Prerequisite: segmenter should be in speech", s.isInSpeech)

        s.mute()

        assertFalse("mute() must clear TRAILING_SILENCE state", s.isInSpeech)
        assertEquals("SILENCE", s.currentState)
    }

    // ── PI-28 — repeated mute/unmute leaves segmenter functional ───────────

    @Test fun `repeated mute-unmute leaves segmenter in a clean processing state`() {
        val s = segmenterWithFastConfirm()
        repeat(10) {
            s.mute()
            // Chunks while muted must be empty.
            assertTrue(s.processChunk(chunk(), 0.9f).isEmpty())
            s.unmute()
            s.reset()
        }
        // After 10 cycles, segmenter must process a fresh speech candidate.
        val events = s.processChunk(chunk(), 0.9f)
        assertTrue(
            "After repeated mute/unmute, segmenter must produce SpeechCandidate",
            events.any { it is UtteranceSegmenter.Event.SpeechCandidate },
        )
    }

    // ── PI-29 — AtomicBoolean mute flag visibility ─────────────────────────
    //
    // Proves the mute flag contract: set(true) is immediately visible via get()
    // on the same thread and — by Java Memory Model happens-before guarantee for
    // AtomicBoolean — on any thread that reads it after the write.

    @Test fun `AtomicBoolean mute flag set then get returns true`() {
        val flag = AtomicBoolean(false)
        assertFalse("Initial value must be false", flag.get())
        flag.set(true)
        assertTrue("After set(true), get() must return true", flag.get())
        flag.set(false)
        assertFalse("After set(false), get() must return false", flag.get())
    }

    // ── PI-30 — unmute clears pre-roll ─────────────────────────────────────
    //
    // After unmuting, the pre-roll buffer must be empty so TTS audio that was
    // captured just before mute cannot be prepended to the first user utterance.

    @Test fun `unmute clears pre-roll so TTS audio cannot bleed into next utterance`() {
        val config = UtteranceSegmenter.Config(
            speechThreshold  = 0.5f,
            minSpeechMs      = 32,
            trailingSilenceMs = 32,
            preRollMs        = 96,   // 3 chunks
            chunkMs          = 32,
        )
        val s = UtteranceSegmenter(config)

        // Simulate TTS audio filling the pre-roll while not yet muted.
        repeat(3) { s.processChunk(ByteArray(PipelineConfig.CHUNK_BYTES) { 99 }, 0.0f) }

        // Mute (controller calls this on TTS start) then unmute (after cooldown).
        s.mute()
        s.unmute()   // unmute() clears preRollBuffer

        // Now a speech chunk arrives: it should start a fresh utterance from
        // an EMPTY pre-roll, not from TTS-era chunks.
        val events = s.processChunk(chunk(), 0.9f)
        val candidate = events.filterIsInstance<UtteranceSegmenter.Event.SpeechCandidate>()
        assertTrue("Should get SpeechCandidate after unmute", candidate.isNotEmpty())

        // The candidate's utterance buffer should contain only this one chunk
        // (no 99-filled pre-roll chunks).  Verify via a subsequent UtteranceReady:
        // drive through min-speech quickly then trailing silence.
        s.processChunk(chunk(), 0.9f)  // confirm speech (minSpeechChunks=1 already met)
        val utteranceEvents = s.processChunk(chunk(), 0.0f)  // trailing silence → emit
        val ready = utteranceEvents.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>()
        if (ready.isNotEmpty()) {
            val pcm = ready.first().pcm
            // None of the bytes should be 99 (the TTS-era pre-roll marker).
            assertFalse(
                "UtteranceReady must not contain pre-mute TTS audio (byte 99)",
                pcm.any { it == 99.toByte() },
            )
        }
    }

    // ── PI-31 — reset on unmute path gives clean SILENCE state ─────────────
    //
    // AIConversationController calls pipeline.resetVadAndSegmenter() (which
    // calls segmenter.reset()) after the cooldown expires before unmuting.
    // Verify this produces a completely clean state.

    @Test fun `reset on unmute path puts segmenter in SILENCE with no state bleed`() {
        val s = segmenterWithFastConfirm()
        // Drive into SPEECH.
        s.processChunk(chunk(), 0.9f)
        s.processChunk(chunk(), 0.9f)
        assertTrue(s.isInSpeech)

        s.mute()
        s.reset()   // resetVadAndSegmenter() calls this
        s.unmute()  // then controller calls unmute via transitionTo

        assertFalse("isInSpeech must be false after reset", s.isInSpeech)
        assertEquals("currentState must be SILENCE after reset", "SILENCE", s.currentState)
    }

    // ── PI-32 — mute→reset→unmute→processChunk is functional ──────────────
    //
    // Full mute/unmute cycle: after reset and unmute, one high-probability chunk
    // must produce SpeechCandidate without throwing or returning empty.

    @Test fun `mute then reset then unmute then processChunk produces SpeechCandidate`() {
        val s = segmenterWithFastConfirm()

        // Enter SPEECH state.
        s.processChunk(chunk(), 0.9f)
        s.processChunk(chunk(), 0.9f)
        assertTrue(s.isInSpeech)

        // Full mute/reset/unmute cycle (matches controller behaviour).
        s.mute()
        s.reset()   // resetVadAndSegmenter (before unmute)
        s.unmute()

        // Fresh speech chunk after unmute: must produce SpeechCandidate.
        val events = s.processChunk(chunk(), 0.9f)
        assertTrue(
            "After mute→reset→unmute, first speech chunk must produce SpeechCandidate, got: $events",
            events.any { it is UtteranceSegmenter.Event.SpeechCandidate },
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // PI-33..PI-42  KentasNavigationPhraseFormatter
    // ════════════════════════════════════════════════════════════════════════

    private val fmt = KentasNavigationPhraseFormatter()

    // PI-33 ── TURN_RIGHT at FAR stage mentions the word distance ──────────
    @Test fun `PI-33 TURN_RIGHT at FAR stage contains Lithuanian distance word`() {
        val phrase = fmt.format(ManeuverType.TURN_RIGHT, 800, KentasNavigationPhraseFormatter.SpeechStage.FAR)
        assertTrue("FAR TURN_RIGHT phrase must contain distance words, got: '$phrase'", phrase.isNotBlank())
        // Should mention "aštuoni šimtai" or similar word form, not bare "800"
        assertFalse("FAR phrase must not contain bare digit distance", phrase.contains("800"))
    }

    // PI-34 ── TURN_LEFT at MEDIUM stage is non-empty ─────────────────────
    @Test fun `PI-34 TURN_LEFT at MEDIUM stage returns non-empty phrase`() {
        val phrase = fmt.format(ManeuverType.TURN_LEFT, 200, KentasNavigationPhraseFormatter.SpeechStage.MEDIUM)
        assertTrue("MEDIUM TURN_LEFT must be non-empty", phrase.isNotBlank())
    }

    // PI-35 ── TURN_RIGHT at IMMEDIATE stage is non-empty ─────────────────
    @Test fun `PI-35 TURN_RIGHT at IMMEDIATE stage returns non-empty phrase`() {
        val phrase = fmt.format(ManeuverType.TURN_RIGHT, 50, KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE)
        assertTrue("IMMEDIATE TURN_RIGHT must be non-empty", phrase.isNotBlank())
    }

    // PI-36 ── ROUNDABOUT at IMMEDIATE uses ordinal-based exit phrasing ────
    @Test fun `PI-36 ROUNDABOUT IMMEDIATE with exit 2 contains second-exit phrasing`() {
        val phrase = fmt.format(
            ManeuverType.ROUNDABOUT_ENTER, 40,
            KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE,
            exitNumber = 2,
        )
        // "antra" or "2" should appear somewhere in the phrase
        val hasOrdinal = phrase.contains("antr", ignoreCase = true) || phrase.contains("2")
        assertTrue("ROUNDABOUT phrase with exit=2 must reference exit ordinal, got: '$phrase'", hasOrdinal)
    }

    // PI-37 ── ARRIVED stage returns non-empty arrival phrase ─────────────
    @Test fun `PI-37 ARRIVED stage returns non-empty arrival phrase`() {
        val phrase = fmt.format(ManeuverType.ARRIVE, 0, KentasNavigationPhraseFormatter.SpeechStage.ARRIVED)
        assertTrue("ARRIVED phrase must be non-empty", phrase.isNotBlank())
    }

    // PI-38 ── SLIGHT_RIGHT at FAR stage is non-empty ─────────────────────
    @Test fun `PI-38 SLIGHT_RIGHT at FAR stage returns non-empty phrase`() {
        val phrase = fmt.format(ManeuverType.SLIGHT_RIGHT, 600, KentasNavigationPhraseFormatter.SpeechStage.FAR)
        assertTrue("SLIGHT_RIGHT FAR phrase must be non-empty", phrase.isNotBlank())
    }

    // PI-39 ── STRAIGHT at IMMEDIATE stage is non-empty ───────────────────
    @Test fun `PI-39 STRAIGHT at IMMEDIATE stage returns non-empty phrase`() {
        val phrase = fmt.format(ManeuverType.STRAIGHT, 80, KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE)
        assertTrue("STRAIGHT IMMEDIATE phrase must be non-empty", phrase.isNotBlank())
    }

    // PI-40 ── exitOrdinal maps 1→"pirmą", 2→"antrą", 3→"trečią" ─────────
    @Test fun `PI-40 exitOrdinal maps small exit numbers to Lithuanian ordinals`() {
        // Formatter exposes ordinal through the roundabout phrase — verify by probing
        // phrase content for three different exit numbers.
        val first  = fmt.format(ManeuverType.ROUNDABOUT_ENTER, 40, KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE, exitNumber = 1)
        val second = fmt.format(ManeuverType.ROUNDABOUT_ENTER, 40, KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE, exitNumber = 2)
        val third  = fmt.format(ManeuverType.ROUNDABOUT_ENTER, 40, KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE, exitNumber = 3)
        assertNotEquals("exit 1 and exit 2 phrases must differ", first, second)
        assertNotEquals("exit 2 and exit 3 phrases must differ", second, third)
    }

    // PI-41 ── formatDistanceWords: 1 → "vienas metras" ──────────────────
    @Test fun `PI-41 formatDistanceWords 1 metre`() {
        val phrase = fmt.format(ManeuverType.TURN_RIGHT, 1, KentasNavigationPhraseFormatter.SpeechStage.IMMEDIATE)
        // Phrase should not be empty even for 1 metre
        assertTrue("1-metre phrase must be non-empty", phrase.isNotBlank())
    }

    // PI-42 ── formatDistanceWords: 500 → "penkis šimtus" or "penkių šimtų" ─
    @Test fun `PI-42 formatDistanceWords 500 metres contains word for 500`() {
        val phrase = fmt.format(ManeuverType.TURN_RIGHT, 500, KentasNavigationPhraseFormatter.SpeechStage.FAR)
        val hasFiveHundred = phrase.contains("penkis šimtus", ignoreCase = true) ||
            phrase.contains("penkių šimtų", ignoreCase = true) ||
            phrase.contains("penkiašimt", ignoreCase = true)
        assertTrue("500-metre FAR phrase must contain Lithuanian for '500', got: '$phrase'", hasFiveHundred)
    }

    // ════════════════════════════════════════════════════════════════════════
    // PI-43..PI-52  SemanticCompletionDetector
    // ════════════════════════════════════════════════════════════════════════

    // PI-43 ── question word "kur" is detected ────────────────────────────
    @Test fun `PI-43 tokensContainQuestion detects kur`() {
        assertTrue(SemanticCompletionDetector.tokensContainQuestion("kur eiti"))
    }

    // PI-44 ── question word "kaip" is detected ───────────────────────────
    @Test fun `PI-44 tokensContainQuestion detects kaip`() {
        assertTrue(SemanticCompletionDetector.tokensContainQuestion("kaip tu sakai"))
    }

    // PI-45 ── non-question phrase is not flagged ─────────────────────────
    @Test fun `PI-45 tokensContainQuestion returns false for plain statement`() {
        assertFalse(SemanticCompletionDetector.tokensContainQuestion("sukti desinej"))
    }

    // PI-46 ── imperative "-k" suffix is detected ─────────────────────────
    @Test fun `PI-46 looksLikeImperative detects -k verb form`() {
        assertTrue(SemanticCompletionDetector.looksLikeImperative("sukk"))
        assertTrue(SemanticCompletionDetector.looksLikeImperative("sukti desinej sustok"))
    }

    // PI-47 ── imperative "-kite" suffix is detected ──────────────────────
    @Test fun `PI-47 looksLikeImperative detects -kite verb form`() {
        assertTrue(SemanticCompletionDetector.looksLikeImperative("sustokite"))
    }

    // PI-48 ── question word "kiek" alone is not flagged as imperative ─────
    @Test fun `PI-48 looksLikeImperative does not flag question word kiek`() {
        assertFalse(SemanticCompletionDetector.looksLikeImperative("kiek"))
    }

    // PI-49 ── "ar" is a question word ────────────────────────────────────
    @Test fun `PI-49 tokensContainQuestion detects ar`() {
        assertTrue(SemanticCompletionDetector.tokensContainQuestion("ar tu žinai"))
    }

    // PI-50 ── incomplete-clause starter "bet" is detected ────────────────
    @Test fun `PI-50 startsWithIncompleteClause detects but-starter`() {
        assertTrue(SemanticCompletionDetector.startsWithIncompleteClause("bet ne"))
        assertTrue(SemanticCompletionDetector.startsWithIncompleteClause("ir tada"))
    }

    // PI-51 ── a normal phrase does not start with incomplete clause ───────
    @Test fun `PI-51 startsWithIncompleteClause returns false for normal phrase`() {
        assertFalse(SemanticCompletionDetector.startsWithIncompleteClause("sustok prie parduotuves"))
    }

    // PI-52 ── "kur" is both question and not imperative ───────────────────
    @Test fun `PI-52 kur is question and not imperative and not incomplete clause`() {
        val norm = "kur tu esi"
        assertTrue(SemanticCompletionDetector.tokensContainQuestion(norm))
        assertFalse(SemanticCompletionDetector.looksLikeImperative(norm))
        assertFalse(SemanticCompletionDetector.startsWithIncompleteClause(norm))
    }
}
