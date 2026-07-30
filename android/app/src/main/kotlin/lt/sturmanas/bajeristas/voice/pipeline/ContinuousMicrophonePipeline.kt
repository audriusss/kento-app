package lt.sturmanas.bajeristas.voice.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production microphone pipeline: AudioRecord → Silero VAD → UtteranceSegmenter
 * → WavEncoder → OpenAI Transcription → [onTranscriptReady].
 *
 * This class implements [MicrophonePipeline] and is the sole microphone input
 * source for the production voice system.
 *
 * ## Lifecycle
 * - [start] is idempotent; [stop] is safe to call when not running.
 * - [AudioRecord] stays alive through all mute/unmute transitions.
 * - Only call [stop] on teardown (i.e. from `release()`).
 *
 * ## Mute/unmute
 * [AudioRecord] keeps recording while muted; the mute gate is checked
 * **after** each [AudioRecord] read so the hardware buffer is always drained.
 * A muted chunk is discarded immediately — Silero inference, VAD logging,
 * and [UtteranceSegmenter] processing are all skipped.  This ensures that
 * neither VAD_SPEECH_CANDIDATE nor VAD_SPEECH_STARTED can fire while muted.
 *
 * Muting also increments [generationId] so any HTTP response still in-flight
 * from before the mute is silently discarded on arrival.
 *
 * On [unmute] the caller (AIConversationController) resets Silero and the
 * segmenter via [resetVadAndSegmenter] before the gate is re-opened.
 *
 * ## Thread safety
 * [start], [stop], [mute], [unmute], [resetVadAndSegmenter] are all safe to
 * call from any thread.  [muteRequested] is an [AtomicBoolean] — writes from
 * the main thread are immediately visible to the IO capture loop.
 * [onTranscriptReady] is invoked from a background coroutine — callers must
 * post to the main thread themselves if needed.
 *
 * @param context             Any [Context]; [applicationContext] is extracted
 *                            immediately to avoid Activity leaks.
 * @param transcriptionClient STT backend.
 * @param onTranscriptReady   Called with every non-blank transcript.  Invoked
 *                            on an IO coroutine — post to main thread as needed.
 * @param segmenterConfig     VAD segmenter tuning (defaults from [PipelineConfig]).
 */
class ContinuousMicrophonePipeline(
    context: Context,
    private val transcriptionClient: TranscriptionClient,
    private val onTranscriptReady: (String) -> Unit,
    private val segmenterConfig: UtteranceSegmenter.Config = UtteranceSegmenter.Config(),
) : MicrophonePipeline {

    companion object {
        private const val TAG = "MicPipeline"

        /**
         * Log a MIC_AUDIO_DISCARDED_WHILE_MUTED line every N discarded chunks
         * to keep Logcat readable during long TTS segments.
         * 100 chunks × 32 ms/chunk = one log line every ~3.2 s.
         */
        private const val MUTED_DISCARD_LOG_INTERVAL = 100L
    }

    // applicationContext extracted immediately — prevents Activity leak.
    private val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTranscriptions = AtomicInteger(0)

    // AtomicBoolean makes start() race-safe: compareAndSet ensures only one
    // concurrent caller can transition false → true and launch captureJob.
    private val running = AtomicBoolean(false)
    private var captureJob: Job? = null

    // Held as fields so resetVadAndSegmenter() can reach them from any thread.
    // Both are set inside runPipeline() and nulled on teardown.
    @Volatile private var segmenter: UtteranceSegmenter? = null
    @Volatile private var vad: SileroVadProcessor? = null

    /**
     * Mute flag.  Written from the main thread, read from the IO capture loop.
     * AtomicBoolean guarantees immediate cross-thread visibility without the
     * subtle read-reordering risk of a plain @Volatile Boolean.
     *
     * The capture loop reads this flag **after** each AudioRecord read so the
     * hardware buffer is never stalled.
     */
    private val muteRequested = AtomicBoolean(false)

    /**
     * Generation counter — incremented every time the pipeline is muted or
     * stopped.  Each transcription coroutine captures its generation at launch
     * and the delivery queue discards results whose generation no longer
     * matches, preventing stale transcripts from reaching the controller after
     * a mute or release.
     *
     * Written only on the main thread (in [mute] and [stop]); read on IO
     * threads from transcription coroutines.  @Volatile is sufficient because
     * there is a single writer.
     */
    @Volatile private var generationId = 0

    /**
     * Per-utterance sequence counter.  Incremented at [UtteranceSegmenter.Event.UtteranceReady]
     * and captured so each transcription coroutine knows its position in the
     * utterance stream.  The [deliveryQueue] uses this ID to re-order results
     * that arrive out of sequence from the STT backend.
     *
     * Reset to 0 in [start] before any capture coroutines are launched.
     */
    private val utteranceSeq = AtomicInteger(0)

    /**
     * Serialises transcript delivery in utterance order.  When two uploads are
     * in-flight and the later one completes first, the queue buffers it until
     * its predecessor arrives, then drains both in order.
     */
    private val deliveryQueue = SttDeliveryQueue(onTranscriptReady)

    // ── MicrophonePipeline ─────────────────────────────────────────────────

    /**
     * Start continuous capture + VAD.  No-op if already running.
     * [android.Manifest.permission.RECORD_AUDIO] must be granted before calling.
     */
    override fun start() {
        if (!running.compareAndSet(false, true)) return   // already running — no-op
        // Reset utterance sequencing so IDs start from 0 for each new session.
        // Safe here because no capture coroutines are active yet (running was false).
        utteranceSeq.set(0)
        deliveryQueue.reset()
        Log.i(TAG, "MIC_PIPELINE_STARTED")
        captureJob = scope.launch { runPipeline() }
    }

    /**
     * Stop all capture, VAD, and pending transcription work.
     * Safe to call when not running.
     */
    override fun stop() {
        running.set(false)
        generationId++                                    // discard any in-flight transcripts
        captureJob?.cancel()
        scope.coroutineContext.cancelChildren()
        Log.i(TAG, "MIC_PIPELINE_STOPPED generation=$generationId")
    }

    /**
     * Mute the pipeline.  In-progress utterances are discarded; [AudioRecord]
     * keeps recording so no hardware warm-up delay is needed on unmute.
     * Also increments [generationId] to discard any transcript currently
     * in-flight over HTTP.
     *
     * The mute takes effect on the very next chunk read after this call
     * because the capture loop checks [muteRequested] immediately after each
     * [AudioRecord] read.  No Silero inference or segmenter processing occurs
     * on discarded chunks.
     */
    override fun mute() {
        val gen = ++generationId                          // discard in-flight transcripts
        muteRequested.set(true)
        Log.i(TAG, "MIC_PIPELINE_MUTED generation=$gen")
    }

    /**
     * Resume after [mute].
     *
     * The caller must call [resetVadAndSegmenter] before or after this so
     * Silero LSTM state and segmenter state machine start clean.
     * AIConversationController always does this via [postTtsCooldownRunnable]
     * and [resumeAfterNavigation].
     */
    override fun unmute() {
        muteRequested.set(false)
        Log.i(TAG, "MIC_PIPELINE_UNMUTED generation=$generationId")
    }

    /**
     * Reset Silero VAD LSTM state and [UtteranceSegmenter] state machine to SILENCE.
     *
     * Called at TTS boundaries so residual speaker echo cannot influence VAD
     * scoring.  Safe to call while the pipeline is muted.
     *
     * The capture loop is gated on [muteRequested] so this call races with a
     * loop iteration that has already passed the mute check at most once.
     * Both [SileroVadProcessor.reset] and [UtteranceSegmenter.reset] perform
     * simple field assignments, making the benign data race inconsequential.
     */
    override fun resetVadAndSegmenter() {
        segmenter?.reset()
        vad?.reset()
        Log.d(TAG, "MIC_VAD_SEGMENTER_RESET generation=$generationId")
    }

    // ── Capture loop ───────────────────────────────────────────────────────

    private suspend fun runPipeline() {
        val modelBytes = appContext.assets.open("silero_vad.onnx").use { it.readBytes() }

        val vadInstance = SileroVadProcessor(modelBytes)
        val segmenterInstance = UtteranceSegmenter(segmenterConfig)
        vad = vadInstance
        segmenter = segmenterInstance

        val source = AudioRecordSource()

        if (!source.initialize()) {
            Log.e(TAG, "MIC_AUDIORECORD_INIT_FAILED")
            running.set(false)
            vad = null
            segmenter = null
            return
        }

        Log.i(
            TAG,
            "MIC_AUDIORECORD_READY sampleRate=${source.sampleRate} " +
            "bufferSize=${source.chunkBytes}",
        )

        // Discard counter for rate-limited muted-chunk logging.
        var discardedChunks = 0L
        // True while the previous chunk was muted; used to emit MIC_SELF_AUDIO_BLOCKED
        // exactly once per mute window (on the first discarded chunk).
        var wasMutedLastChunk = false

        try {
            while (currentCoroutineContext().isActive && running.get()) {

                // ── Always drain AudioRecord ───────────────────────────────
                // The blocking read is unconditional so the hardware buffer
                // never stalls or overflows while the pipeline is muted.
                val chunk = source.readChunk() ?: continue

                // ── Mute gate (post-read) ──────────────────────────────────
                // Checked AFTER the read: the main thread may set muteRequested
                // at any moment during the blocking readChunk() call.  Because
                // the flag is read here — after the hardware data is safely in
                // hand — there is no window where a muted chunk slips through
                // to Silero, VAD logging, or the segmenter.
                if (muteRequested.get()) {
                    discardedChunks++
                    if (!wasMutedLastChunk) {
                        // First chunk of a new mute window: log self-audio block.
                        Log.i(TAG, "MIC_SELF_AUDIO_BLOCKED generation=$generationId")
                        wasMutedLastChunk = true
                    } else if (discardedChunks % MUTED_DISCARD_LOG_INTERVAL == 0L) {
                        Log.d(TAG, "MIC_AUDIO_DISCARDED_WHILE_MUTED chunks=$discardedChunks")
                    }
                    continue
                }

                // Transitioning from muted → unmuted: reset discard tracking.
                if (wasMutedLastChunk) {
                    Log.d(TAG, "MIC_AUDIO_DISCARDED_WHILE_MUTED chunks=$discardedChunks")
                    discardedChunks = 0L
                    wasMutedLastChunk = false
                }

                // ── VAD inference (only when not muted) ───────────────────
                val prob = try {
                    vadInstance.process(chunk)
                } catch (e: Exception) {
                    Log.e(TAG, "VAD_PROCESS_ERROR ${e.message}")
                    continue
                }

                if (prob >= PipelineConfig.LOG_CANDIDATE_FLOOR) {
                    Log.v(TAG, "VAD_SPEECH_CANDIDATE probability=${"%.3f".format(prob)}")
                }

                // ── Segmenter (only when not muted) ───────────────────────
                val events = segmenterInstance.processChunk(chunk, prob)
                for (event in events) {
                    handleSegmenterEvent(event, segmenterInstance, vadInstance)
                }
            }
        } finally {
            source.release()
            vadInstance.close()
            vad = null
            segmenter = null
            Log.i(TAG, "MIC_PIPELINE_TORN_DOWN")
        }
    }

    private fun handleSegmenterEvent(
        event: UtteranceSegmenter.Event,
        seg: UtteranceSegmenter,
        vadProc: SileroVadProcessor,
    ) {
        when (event) {
            is UtteranceSegmenter.Event.SpeechCandidate -> {
                // Logged by the VAD_SPEECH_CANDIDATE line in the capture loop.
            }

            is UtteranceSegmenter.Event.SpeechStarted -> {
                Log.i(TAG, "VAD_SPEECH_STARTED")
            }

            is UtteranceSegmenter.Event.UtteranceReady -> {
                Log.i(
                    TAG,
                    "VAD_SPEECH_ENDED durationMs=${event.durationMs} " +
                    "pcmBytes=${event.pcm.size}",
                )
                Log.i(TAG, "USER_SPEECH_ENDED speechDurationMs=${event.durationMs}")

                // Reset VAD LSTM state between utterances.
                vadProc.reset()

                val pcmSnapshot = event.pcm
                val durationMs = event.durationMs
                // Capture generation and utterance ID atomically at utterance completion.
                // generationId guards against mute/stop invalidation; utteranceId
                // preserves delivery order when two uploads complete out of sequence.
                val capturedGeneration = generationId
                val utteranceId = utteranceSeq.getAndIncrement()

                // Bounded fire-and-forget transcription.
                if (activeTranscriptions.incrementAndGet() <= PipelineConfig.MAX_CONCURRENT_TRANSCRIPTIONS) {
                    scope.launch {
                        try {
                            transcribeAndDeliver(pcmSnapshot, durationMs, capturedGeneration, utteranceId)
                        } finally {
                            activeTranscriptions.decrementAndGet()
                        }
                    }
                } else {
                    activeTranscriptions.decrementAndGet()
                    Log.w(
                        TAG,
                        "VAD_UTTERANCE_DISCARDED reason=transcription_queue_full " +
                        "generation=$capturedGeneration utteranceId=$utteranceId",
                    )
                    // Advance the delivery queue so subsequent utterances are not blocked.
                    scope.launch {
                        deliveryQueue.skip(utteranceId) { generationId }
                    }
                }
            }

            is UtteranceSegmenter.Event.Discarded -> {
                Log.d(TAG, "VAD_UTTERANCE_DISCARDED reason=${event.reason}")
            }
        }
    }

    private suspend fun transcribeAndDeliver(
        pcm: ByteArray,
        durationMs: Long,
        generation: Int,
        utteranceId: Int,
    ) {
        val wav = WavEncoder.encode(pcm)
        Log.i(TAG, "STT_UPLOAD_STARTED wavBytes=${wav.size} generation=$generation utteranceId=$utteranceId")
        val startMs = System.currentTimeMillis()

        val result = transcriptionClient.transcribe(wav, language = "lt")

        val transcript = result.getOrNull()
        if (transcript != null) {
            val latencyMs = System.currentTimeMillis() - startMs
            Log.i(
                TAG,
                "STT_UPLOAD_COMPLETED latencyMs=$latencyMs " +
                "transcriptLength=${transcript.length} " +
                "generation=$generation utteranceId=$utteranceId",
            )
            Log.i(TAG, "STT_TRANSCRIPT text=\"$transcript\"")
            // Offer to the delivery queue: handles ordering and stale-generation checks.
            // Even if this generation is stale, the queue still advances past this ID
            // so that any already-buffered higher-ID results can drain correctly.
            deliveryQueue.offer(utteranceId, generation, transcript) { generationId }
        } else {
            Log.e(
                TAG,
                "STT_UPLOAD_FAILED reason=${result.exceptionOrNull()?.message} " +
                "generation=$generation utteranceId=$utteranceId",
            )
            // Skip advances the queue past this ID without delivering.
            deliveryQueue.skip(utteranceId) { generationId }
        }
    }
}
