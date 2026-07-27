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
 * [AudioRecord] keeps recording while muted; audio frames are discarded at the
 * [UtteranceSegmenter] level so no transcription work is started.
 * Muting also increments [generationId] so any HTTP response still in-flight
 * from before the mute is silently discarded on arrival.
 *
 * ## Thread safety
 * [start], [stop], [mute], [unmute], [resetVadAndSegmenter] are all safe to
 * call from any thread.  [onTranscriptReady] is invoked from a background
 * coroutine — callers must post to the main thread themselves if needed.
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

    @Volatile private var muteRequested = false

    /**
     * Generation counter — incremented every time the pipeline is muted or
     * stopped.  Each transcription coroutine captures its generation at launch
     * and discards its result if the counter has advanced by the time the HTTP
     * response arrives, preventing stale transcripts from reaching the
     * controller after a mute or release.
     */
    @Volatile private var generationId = 0

    // ── MicrophonePipeline ─────────────────────────────────────────────────

    /**
     * Start continuous capture + VAD.  No-op if already running.
     * [android.Manifest.permission.RECORD_AUDIO] must be granted before calling.
     */
    override fun start() {
        if (!running.compareAndSet(false, true)) return   // already running — no-op
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
        Log.i(TAG, "MIC_PIPELINE_STOPPED")
    }

    /**
     * Mute the pipeline.  In-progress utterances are discarded; [AudioRecord]
     * keeps recording so no hardware warm-up delay is needed on unmute.
     * Also increments [generationId] to discard any transcript currently
     * in-flight over HTTP.
     */
    override fun mute() {
        muteRequested = true
        generationId++                                    // discard in-flight transcripts
    }

    /** Resume after [mute]. Pre-roll is cleared on unmute to purge TTS echo. */
    override fun unmute() {
        muteRequested = false
    }

    /**
     * Reset Silero VAD LSTM state and [UtteranceSegmenter] state machine to SILENCE.
     *
     * Called at TTS boundaries so residual speaker echo cannot influence VAD
     * scoring.  Safe to call while the pipeline is muted.
     *
     * Note: called from the main thread while the capture loop runs on IO.
     * Both [SileroVadProcessor.reset] and [UtteranceSegmenter.reset] perform
     * simple field assignments; the pipeline is muted when this is called so
     * no utterance is in flight, making the benign data race inconsequential.
     */
    override fun resetVadAndSegmenter() {
        segmenter?.reset()
        vad?.reset()
        Log.d(TAG, "MIC_VAD_SEGMENTER_RESET")
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

        var lastMuteState = false

        try {
            while (currentCoroutineContext().isActive && running.get()) {
                // ── Mute gate ─────────────────────────────────────────────
                val nowMuted = muteRequested
                if (nowMuted != lastMuteState) {
                    if (nowMuted) segmenterInstance.mute() else segmenterInstance.unmute()
                    lastMuteState = nowMuted
                }

                // ── Read one chunk (blocking) ─────────────────────────────
                val chunk = source.readChunk() ?: continue

                // ── VAD inference ─────────────────────────────────────────
                val prob = try {
                    vadInstance.process(chunk)
                } catch (e: Exception) {
                    Log.e(TAG, "VAD_PROCESS_ERROR ${e.message}")
                    continue
                }

                if (prob >= PipelineConfig.LOG_CANDIDATE_FLOOR) {
                    Log.v(TAG, "VAD_SPEECH_CANDIDATE probability=${"%.3f".format(prob)}")
                }

                // ── Segmenter ─────────────────────────────────────────────
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

                // Reset VAD LSTM state between utterances.
                vadProc.reset()

                val pcmSnapshot = event.pcm
                val durationMs = event.durationMs
                // Capture the generation at utterance completion.  If the pipeline
                // is muted or stopped before the HTTP response arrives, generationId
                // will have advanced and the transcript will be silently dropped.
                val capturedGeneration = generationId

                // Bounded fire-and-forget transcription.
                if (activeTranscriptions.incrementAndGet() <= PipelineConfig.MAX_CONCURRENT_TRANSCRIPTIONS) {
                    scope.launch {
                        try {
                            transcribeAndDeliver(pcmSnapshot, durationMs, capturedGeneration)
                        } finally {
                            activeTranscriptions.decrementAndGet()
                        }
                    }
                } else {
                    activeTranscriptions.decrementAndGet()
                    Log.w(TAG, "VAD_UTTERANCE_DISCARDED reason=transcription_queue_full")
                }
            }

            is UtteranceSegmenter.Event.Discarded -> {
                Log.d(TAG, "VAD_UTTERANCE_DISCARDED reason=${event.reason}")
            }
        }
    }

    private suspend fun transcribeAndDeliver(pcm: ByteArray, durationMs: Long, generation: Int) {
        val wav = WavEncoder.encode(pcm)
        Log.i(TAG, "STT_UPLOAD_STARTED wavBytes=${wav.size} generation=$generation")
        val startMs = System.currentTimeMillis()

        transcriptionClient.transcribe(wav, language = "lt")
            .onSuccess { transcript ->
                // Check generation before delivering: if generationId has advanced
                // since this coroutine was launched, the pipeline was muted or
                // stopped while the HTTP request was in-flight.  Drop silently.
                if (generationId != generation) {
                    Log.d(
                        TAG,
                        "STT_TRANSCRIPT_DISCARDED reason=stale_generation " +
                        "captured=$generation current=$generationId",
                    )
                    return@onSuccess
                }
                val latencyMs = System.currentTimeMillis() - startMs
                Log.i(
                    TAG,
                    "STT_UPLOAD_COMPLETED latencyMs=$latencyMs " +
                    "transcriptLength=${transcript.length}",
                )
                Log.i(TAG, "STT_TRANSCRIPT text=\"$transcript\"")
                onTranscriptReady(transcript)
                Log.d(TAG, "STT_TRANSCRIPT_DELIVERED transcriptLength=${transcript.length}")
            }
            .onFailure { err ->
                Log.e(TAG, "STT_UPLOAD_FAILED reason=${err.message}")
            }
    }
}
