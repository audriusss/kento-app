package lt.sturmanas.bajeristas.voice.pipeline

/**
 * Central configuration for the AudioRecord → Silero VAD → STT pipeline.
 *
 * All tuning constants live here.  Other classes reference these values rather
 * than declaring their own, ensuring a single point of change per parameter.
 */
object PipelineConfig {

    // ── Audio capture ──────────────────────────────────────────────────────

    /** Sample rate required by the Silero VAD v4 model. */
    const val SAMPLE_RATE: Int = 16_000

    /** Samples per VAD inference chunk: 512 samples = 32 ms at 16 kHz. */
    const val CHUNK_SAMPLES: Int = 512

    /** Bytes per chunk: 2 bytes per int16 sample. */
    const val CHUNK_BYTES: Int = CHUNK_SAMPLES * 2

    /** Duration of one chunk in milliseconds (CHUNK_SAMPLES × 1000 / SAMPLE_RATE). */
    const val CHUNK_MS: Int = CHUNK_SAMPLES * 1000 / SAMPLE_RATE  // 32 ms

    // ── VAD / Segmenter ────────────────────────────────────────────────────

    /** VAD probability above which a chunk is classified as speech. */
    const val SPEECH_THRESHOLD: Float = 0.50f

    /**
     * Minimum sustained speech duration before an utterance is confirmed.
     * Shorter bursts are rejected as noise (default: 250 ms).
     */
    const val MIN_SPEECH_MS: Int = 250

    /**
     * How long silence must persist after speech ends before the utterance
     * is emitted (default: 600 ms).  Tuned for driving pauses mid-sentence.
     */
    const val TRAILING_SILENCE_MS: Int = 600

    /**
     * Hard cap on utterance length; the buffer is flushed even if speech is
     * still ongoing (default: 15 000 ms).
     */
    const val MAX_UTTERANCE_MS: Int = 15_000

    /**
     * Audio retained before speech onset so the first syllable is never
     * clipped (default: 200 ms).
     */
    const val PRE_ROLL_MS: Int = 200

    // ── Pipeline concurrency ───────────────────────────────────────────────

    /**
     * Maximum number of transcription coroutines running concurrently.
     * Utterances that arrive when the limit is reached are dropped and logged.
     */
    const val MAX_CONCURRENT_TRANSCRIPTIONS: Int = 2

    /** Minimum VAD probability that produces a candidate Logcat entry. */
    const val LOG_CANDIDATE_FLOOR: Float = 0.35f

    // ── TTS self-recognition protection ───────────────────────────────────

    /**
     * Milliseconds to wait after TTS finishes before unmuting the mic.
     *
     * The cooldown allows speaker echo to decay so the VAD does not trigger
     * on residual TTS audio immediately after playback ends.
     */
    const val POST_TTS_COOLDOWN_MS: Long = 200L

    // ── Transcription ──────────────────────────────────────────────────────

    /**
     * OpenAI speech-to-text model used for Lithuanian transcription.
     *
     * gpt-4o-transcribe (March 2025) supersedes whisper-1 across all
     * languages, including low-resource ones such as Lithuanian.
     *
     * Change this constant to switch models project-wide.
     */
    const val TRANSCRIPTION_MODEL: String = "gpt-4o-transcribe"
}
