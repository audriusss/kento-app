package lt.sturmanas.bajeristas.voice.pipeline

/**
 * State machine that groups raw audio chunks into complete utterance buffers
 * using a speech-probability stream from a VAD model.
 *
 * ## State transitions
 *
 * ```
 * SILENCE
 *   → POSSIBLE_SPEECH  (probability ≥ threshold)
 *
 * POSSIBLE_SPEECH
 *   → SPEECH           (sustained for ≥ minSpeechChunks)  → emits SpeechStarted
 *   → SILENCE          (probability drops before threshold) → emits Discarded(noise_burst)
 *
 * SPEECH
 *   → TRAILING_SILENCE (probability < threshold)
 *   → SILENCE+emit     (utterance length ≥ maxUtteranceChunks)
 *
 * TRAILING_SILENCE
 *   → SPEECH           (probability ≥ threshold again)
 *   → SILENCE+emit     (silence ≥ trailingSilenceChunks)
 *   → SILENCE+emit     (utterance length ≥ maxUtteranceChunks)
 * ```
 *
 * ## Pre-roll
 * A circular buffer retains the last [Config.preRollMs] worth of silence
 * chunks.  When speech is first detected those chunks are prepended to the
 * utterance so the first syllable is never cut off.
 *
 * ## Mute gate
 * When [mute] is called the segmenter discards any in-progress utterance and
 * stops accumulating audio.  [unmute] also clears the pre-roll so TTS audio
 * captured just before the mute completes cannot contaminate the next
 * utterance.
 *
 * ## Thread safety
 * NOT thread-safe.  Call from a single thread (the mic capture coroutine).
 *
 * @param config Tuning parameters; see [Config] for defaults.
 */
class UtteranceSegmenter(val config: Config = Config()) {

    // ── Configuration ────────────────────────────────────────────────────

    /**
     * Tuning knobs for [UtteranceSegmenter].
     *
     * All time values are in milliseconds and are converted to chunk counts
     * internally using [chunkMs].
     *
     * @param speechThreshold   VAD probability above which a chunk is
     *                          classified as speech (default: 0.50).
     * @param minSpeechMs       Minimum sustained speech duration before an
     *                          utterance is confirmed; shorter bursts are
     *                          rejected as noise (default: 250 ms).
     * @param trailingSilenceMs How long silence must persist after speech
     *                          ends before the utterance is emitted
     *                          (default: 600 ms).
     * @param maxUtteranceMs    Hard cap on utterance length; the buffer is
     *                          flushed even if speech is still ongoing
     *                          (default: 15 000 ms).
     * @param preRollMs         Audio retained before speech onset to avoid
     *                          clipping the first syllable (default: 200 ms).
     * @param chunkMs           Duration of one audio chunk in milliseconds.
     *                          Must match the VAD model's chunk size
     *                          (Silero VAD v4: 32 ms).
     */
    data class Config(
        val speechThreshold: Float = PipelineConfig.SPEECH_THRESHOLD,
        val minSpeechMs: Int = PipelineConfig.MIN_SPEECH_MS,
        val trailingSilenceMs: Int = PipelineConfig.TRAILING_SILENCE_MS,
        val maxUtteranceMs: Int = PipelineConfig.MAX_UTTERANCE_MS,
        val preRollMs: Int = PipelineConfig.PRE_ROLL_MS,
        val chunkMs: Int = PipelineConfig.CHUNK_MS,
    )

    // ── Events emitted per chunk ─────────────────────────────────────────

    sealed class Event {
        /** First speech chunk above threshold detected (candidate, not yet confirmed). */
        data class SpeechCandidate(val probability: Float) : Event()

        /** Speech confirmed (minimum duration met). */
        object SpeechStarted : Event()

        /** Complete utterance ready for transcription. */
        data class UtteranceReady(val pcm: ByteArray, val durationMs: Long) : Event()

        /**
         * Audio discarded without transcription.
         * [reason] is one of: noise_burst, max_duration (not emitted as UtteranceReady
         * because the session is still rolling), mute.
         */
        data class Discarded(val reason: String) : Event()
    }

    // ── Internal state ───────────────────────────────────────────────────

    private enum class State { SILENCE, POSSIBLE_SPEECH, SPEECH, TRAILING_SILENCE }

    private var state = State.SILENCE
    private var isMuted = false

    private val minSpeechChunks: Int     get() = (config.minSpeechMs / config.chunkMs).coerceAtLeast(1)
    private val trailingSilenceChunks: Int get() = (config.trailingSilenceMs / config.chunkMs).coerceAtLeast(1)
    private val maxUtteranceChunks: Int  get() = (config.maxUtteranceMs / config.chunkMs).coerceAtLeast(1)
    private val preRollChunks: Int       get() = (config.preRollMs / config.chunkMs).coerceAtLeast(1)

    private var speechChunkCount = 0
    private var silenceChunkCount = 0
    private var utteranceChunkCount = 0

    /** Accumulates chunks for the current utterance. */
    private val utteranceBuffer = mutableListOf<ByteArray>()

    /** Circular buffer: last [preRollChunks] silence chunks. */
    private val preRollBuffer = ArrayDeque<ByteArray>()

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Process one audio chunk.
     *
     * @param pcm               Raw PCM bytes for this chunk.
     * @param speechProbability VAD probability in [0.0, 1.0].
     * @return Zero, one, or more [Event]s produced by this chunk.
     */
    fun processChunk(pcm: ByteArray, speechProbability: Float): List<Event> {
        if (isMuted) return emptyList()

        val isSpeech = speechProbability >= config.speechThreshold
        val events = mutableListOf<Event>()

        when (state) {
            State.SILENCE -> {
                if (isSpeech) {
                    events += Event.SpeechCandidate(speechProbability)
                    state = State.POSSIBLE_SPEECH
                    speechChunkCount = 1
                    // Start utterance: prepend the pre-roll then add this chunk.
                    utteranceBuffer.clear()
                    utteranceBuffer.addAll(preRollBuffer)
                    utteranceBuffer.add(pcm.copyOf())
                    utteranceChunkCount = utteranceBuffer.size
                } else {
                    addToPreRoll(pcm)
                }
            }

            State.POSSIBLE_SPEECH -> {
                if (isSpeech) {
                    speechChunkCount++
                    utteranceBuffer.add(pcm.copyOf())
                    utteranceChunkCount++
                    if (speechChunkCount >= minSpeechChunks) {
                        state = State.SPEECH
                        events += Event.SpeechStarted
                    }
                } else {
                    // Not enough speech — noise burst, discard.
                    events += Event.Discarded("noise_burst speechChunks=$speechChunkCount")
                    resetCounters()
                    state = State.SILENCE
                    addToPreRoll(pcm)
                }
            }

            State.SPEECH -> {
                utteranceBuffer.add(pcm.copyOf())
                utteranceChunkCount++
                if (isSpeech) {
                    silenceChunkCount = 0
                } else {
                    silenceChunkCount = 1
                    state = State.TRAILING_SILENCE
                }
                if (utteranceChunkCount >= maxUtteranceChunks) {
                    events += emitUtterance("max_duration")
                    state = State.SILENCE
                }
            }

            State.TRAILING_SILENCE -> {
                utteranceBuffer.add(pcm.copyOf())
                utteranceChunkCount++
                if (isSpeech) {
                    silenceChunkCount = 0
                    state = State.SPEECH
                } else {
                    silenceChunkCount++
                    when {
                        silenceChunkCount >= trailingSilenceChunks -> {
                            events += emitUtterance("trailing_silence")
                            state = State.SILENCE
                        }
                        utteranceChunkCount >= maxUtteranceChunks -> {
                            events += emitUtterance("max_duration")
                            state = State.SILENCE
                        }
                    }
                }
            }
        }

        return events
    }

    /**
     * Mute the segmenter.
     *
     * Any in-progress utterance is discarded immediately.  Audio frames
     * received while muted are silently dropped so TTS output cannot be
     * recognised as speech.
     */
    fun mute() {
        if (isMuted) return
        isMuted = true
        clearActive()
    }

    /**
     * Unmute the segmenter.
     *
     * The pre-roll buffer is also cleared so TTS audio captured just before
     * muting completes cannot leak into the first utterance after unmuting.
     */
    fun unmute() {
        isMuted = false
        preRollBuffer.clear()
    }

    /** Full reset: clears all state, buffers, and the mute flag. */
    fun reset() {
        isMuted = false
        state = State.SILENCE
        clearActive()
        preRollBuffer.clear()
    }

    /** Current state name (for logging/testing). */
    val currentState: String get() = state.name

    val isInSpeech: Boolean get() = state == State.SPEECH || state == State.TRAILING_SILENCE

    // ── Private helpers ──────────────────────────────────────────────────

    private fun emitUtterance(reason: String): Event {
        val pcm = ByteArray(utteranceBuffer.sumOf { it.size }).also { out ->
            var pos = 0
            utteranceBuffer.forEach { chunk -> chunk.copyInto(out, pos); pos += chunk.size }
        }
        val durationMs = utteranceChunkCount.toLong() * config.chunkMs
        clearActive()
        return Event.UtteranceReady(pcm, durationMs)
    }

    private fun addToPreRoll(pcm: ByteArray) {
        preRollBuffer.addLast(pcm.copyOf())
        while (preRollBuffer.size > preRollChunks) preRollBuffer.removeFirst()
    }

    private fun clearActive() {
        state = State.SILENCE
        utteranceBuffer.clear()
        speechChunkCount = 0
        silenceChunkCount = 0
        utteranceChunkCount = 0
    }

    private fun resetCounters() {
        utteranceBuffer.clear()
        speechChunkCount = 0
        silenceChunkCount = 0
        utteranceChunkCount = 0
    }
}
