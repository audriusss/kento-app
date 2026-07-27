package lt.sturmanas.bajeristas.voice.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Isolated Proof-of-Concept: AudioRecord → Silero VAD → UtteranceSegmenter
 * → WavEncoder → OpenAI Transcription → Logcat.
 *
 * ## What this class does
 * 1. Opens the microphone with [AudioRecordSource] (silent, no beeps).
 * 2. Passes 32 ms PCM chunks through [SileroVadProcessor].
 * 3. Feeds probabilities to [UtteranceSegmenter] to detect speech boundaries.
 * 4. On utterance completion: encodes PCM as WAV, sends to [TranscriptionClient],
 *    logs the Lithuanian transcript.
 *
 * ## What this class does NOT do
 * - It does NOT touch AIConversationController.
 * - It does NOT call processPacket() or KentasChat.
 * - It does NOT modify NavigationVoiceController or ConversationCoordinator.
 * - It does NOT use SpeechRecognizer.
 *
 * ## Concurrency
 * The capture loop runs on [Dispatchers.IO].  Transcription requests are
 * launched as independent child coroutines in the same scope, bounded to
 * [MAX_CONCURRENT_TRANSCRIPTIONS] concurrent calls.  If the limit is reached
 * the utterance is dropped and logged as discarded.
 *
 * ## Lifecycle
 * Call [start] to begin.  Call [stop] to cancel all work cleanly.  Both are
 * safe to call from any thread.  Do NOT re-use an instance after [stop].
 *
 * @param context              Application or Activity context (for asset loading).
 *                             Not stored beyond [start]; no leak risk.
 * @param transcriptionClient  STT backend.
 * @param segmenterConfig      VAD segmenter tuning (see [UtteranceSegmenter.Config]).
 */
class ContinuousMicrophonePoc(
    private val context: Context,
    private val transcriptionClient: TranscriptionClient,
    private val segmenterConfig: UtteranceSegmenter.Config = UtteranceSegmenter.Config(),
) {

    companion object {
        private const val TAG = "MicPoc"

        /**
         * Maximum number of transcription coroutines running concurrently.
         * If a new utterance arrives while [MAX_CONCURRENT_TRANSCRIPTIONS]
         * are already in flight, it is dropped with a logged reason.
         */
        private const val MAX_CONCURRENT_TRANSCRIPTIONS = 2

        /**
         * Minimum VAD probability logged as a candidate (below the
         * confirmed-speech threshold).  Avoids flooding Logcat with every
         * near-silence chunk while still showing candidate activations.
         */
        private const val LOG_CANDIDATE_FLOOR = 0.35f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTranscriptions = AtomicInteger(0)

    @Volatile private var running = false
    private var captureJob: Job? = null

    // ── Public control ────────────────────────────────────────────────────

    /**
     * Start continuous capture + VAD.  No-op if already running.
     *
     * The RECORD_AUDIO permission must be granted before calling [start].
     */
    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "MIC_POC_STARTED")
        captureJob = scope.launch { runPipeline() }
    }

    /**
     * Stop all capture, VAD, and pending transcription work.
     * Blocks until the capture coroutine is cancelled.
     */
    fun stop() {
        running = false
        captureJob?.cancel()
        scope.coroutineContext.cancelChildren()
        Log.i(TAG, "MIC_POC_STOPPED")
    }

    /**
     * Mute the pipeline.  In-progress utterances are discarded; no audio is
     * sent to the STT backend while muted.  Call [unmute] to resume.
     *
     * Intended for future navigation pause integration.
     */
    fun mute() {
        // segmenter mute is applied inside the capture loop via a flag.
        // Exposed here for future wiring to ConversationCoordinator.
        muteRequested = true
    }

    /** Resume after [mute]. */
    fun unmute() {
        muteRequested = false
    }

    @Volatile private var muteRequested = false

    // ── Pipeline ──────────────────────────────────────────────────────────

    private suspend fun runPipeline() {
        val modelBytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }

        val vad = SileroVadProcessor(modelBytes)
        val segmenter = UtteranceSegmenter(segmenterConfig)
        val source = AudioRecordSource()

        if (!source.initialize()) {
            Log.e(TAG, "MIC_AUDIORECORD_INIT_FAILED")
            running = false
            return
        }

        Log.i(
            TAG,
            "MIC_AUDIORECORD_READY sampleRate=${source.sampleRate} " +
            "bufferSize=${source.chunkBytes}"
        )

        var lastMuteState = false

        try {
            while (isActive && running) {
                // ── Mute gate ─────────────────────────────────────────────
                val nowMuted = muteRequested
                if (nowMuted != lastMuteState) {
                    if (nowMuted) segmenter.mute() else segmenter.unmute()
                    lastMuteState = nowMuted
                }

                // ── Read one chunk (blocking) ─────────────────────────────
                val chunk = source.readChunk() ?: continue

                // ── VAD inference ─────────────────────────────────────────
                val prob = try {
                    vad.process(chunk)
                } catch (e: Exception) {
                    Log.e(TAG, "VAD_PROCESS_ERROR ${e.message}")
                    continue
                }

                if (prob >= LOG_CANDIDATE_FLOOR) {
                    Log.v(TAG, "VAD_SPEECH_CANDIDATE probability=${"%.3f".format(prob)}")
                }

                // ── Segmenter ─────────────────────────────────────────────
                val events = segmenter.processChunk(chunk, prob)
                for (event in events) {
                    handleSegmenterEvent(event, segmenter, vad)
                }
            }
        } finally {
            source.release()
            vad.close()
            Log.i(TAG, "MIC_POC_PIPELINE_TORN_DOWN")
        }
    }

    private fun handleSegmenterEvent(
        event: UtteranceSegmenter.Event,
        segmenter: UtteranceSegmenter,
        vad: SileroVadProcessor,
    ) {
        when (event) {
            is UtteranceSegmenter.Event.SpeechCandidate -> {
                // Already logged by the VAD_SPEECH_CANDIDATE line above.
            }

            is UtteranceSegmenter.Event.SpeechStarted -> {
                Log.i(TAG, "VAD_SPEECH_STARTED")
            }

            is UtteranceSegmenter.Event.UtteranceReady -> {
                Log.i(
                    TAG,
                    "VAD_SPEECH_ENDED durationMs=${event.durationMs} " +
                    "pcmBytes=${event.pcm.size}"
                )

                // Reset VAD state between utterances.
                vad.reset()

                val pcmSnapshot = event.pcm
                val durationMs = event.durationMs

                // Bounded fire-and-forget transcription.
                if (activeTranscriptions.incrementAndGet() <= MAX_CONCURRENT_TRANSCRIPTIONS) {
                    scope.launch {
                        try {
                            transcribeAndLog(pcmSnapshot, durationMs)
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

    private suspend fun transcribeAndLog(pcm: ByteArray, durationMs: Long) {
        val wav = WavEncoder.encode(pcm)
        Log.i(TAG, "STT_UPLOAD_STARTED wavBytes=${wav.size}")
        val startMs = System.currentTimeMillis()

        transcriptionClient.transcribe(wav, language = "lt")
            .onSuccess { transcript ->
                val latencyMs = System.currentTimeMillis() - startMs
                Log.i(
                    TAG,
                    "STT_UPLOAD_COMPLETED latencyMs=$latencyMs " +
                    "transcriptLength=${transcript.length}"
                )
                Log.i(TAG, "STT_TRANSCRIPT text=\"$transcript\"")
            }
            .onFailure { err ->
                Log.e(TAG, "STT_UPLOAD_FAILED reason=${err.message}")
            }
    }
}
