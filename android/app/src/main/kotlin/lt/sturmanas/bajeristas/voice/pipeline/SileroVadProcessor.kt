package lt.sturmanas.bajeristas.voice.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Voice Activity Detection using the Silero VAD v4 ONNX model.
 *
 * ## Verified ONNX tensor contract (Silero VAD v4, silero_vad.onnx)
 *
 * ### Inputs
 * | Name    | Type      | Shape       | Description                              |
 * |---------|-----------|-------------|------------------------------------------|
 * | `input` | float32   | [1, 512]    | Normalised PCM samples in [-1.0, 1.0]   |
 * | `sr`    | int64     | [1]         | Sample rate — must be 16000             |
 * | `h`     | float32   | [2, 1, 64]  | LSTM hidden state (zeros on first call) |
 * | `c`     | float32   | [2, 1, 64]  | LSTM cell state  (zeros on first call)  |
 *
 * ### Outputs
 * | Name     | Type      | Shape       | Description                              |
 * |----------|-----------|-------------|------------------------------------------|
 * | `output` | float32   | [1, 1]      | Speech probability in [0.0, 1.0]        |
 * | `hn`     | float32   | [2, 1, 64]  | New LSTM hidden state (carry forward)   |
 * | `cn`     | float32   | [2, 1, 64]  | New LSTM cell state  (carry forward)    |
 *
 * ### Chunk size
 * 512 samples at 16 kHz = exactly 32 ms per inference call.
 *
 * ### State management
 * LSTM state (`h`, `c`) must be carried between consecutive chunks for the
 * model to exploit context across chunks.  Call [reset] between utterances
 * so state from one speaker turn does not bleed into the next.
 *
 * ## Thread safety
 * NOT thread-safe.  Call [process] from a single background thread only.
 *
 * @param modelBytes Raw bytes of `silero_vad.onnx` (loaded from assets).
 */
class SileroVadProcessor(modelBytes: ByteArray) : Closeable {

    companion object {
        private const val TAG = "SileroVad"

        /** Required sample rate for the v4 model. */
        const val SAMPLE_RATE = 16_000

        /** Samples per inference chunk: 512 = 32 ms at 16 kHz. */
        const val CHUNK_SAMPLES = 512

        /** Bytes per chunk: 512 × 2 (int16 = 2 bytes per sample). */
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2

        // LSTM state tensor total elements: 2 × 1 × 64 = 128.
        private const val STATE_SIZE = 2 * 1 * 64

        // Fixed tensor shapes.
        private val INPUT_SHAPE = longArrayOf(1, CHUNK_SAMPLES.toLong())
        private val SR_SHAPE    = longArrayOf(1)
        private val STATE_SHAPE = longArrayOf(2, 1, 64)
        private val SR_VALUE    = longArrayOf(SAMPLE_RATE.toLong())

        // Normalisation divisor for signed int16 → float32.
        private const val INT16_MAX = 32768.0f
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes)

    // Mutable LSTM state carried between [process] calls.
    // Flat representation of shape [2, 1, 64] in row-major order.
    private var h = FloatArray(STATE_SIZE)
    private var c = FloatArray(STATE_SIZE)

    /**
     * Run one VAD inference step.
     *
     * @param pcm Exactly [CHUNK_BYTES] bytes of signed 16-bit little-endian
     *            PCM audio captured at [SAMPLE_RATE] Hz.
     * @return Speech probability in [0.0, 1.0].
     * @throws IllegalArgumentException if [pcm] has the wrong length.
     * @throws ai.onnxruntime.OrtException on ONNX Runtime errors.
     */
    fun process(pcm: ByteArray): Float {
        require(pcm.size == CHUNK_BYTES) {
            "SileroVadProcessor expects $CHUNK_BYTES bytes per chunk, got ${pcm.size}"
        }

        // ── Convert int16 little-endian PCM → float32 in [-1.0, 1.0] ────
        val samples = FloatArray(CHUNK_SAMPLES) { i ->
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()       // sign-extends on left-shift
            val s16 = ((hi shl 8) or lo).toShort()
            s16 / INT16_MAX
        }

        // ── Build input tensors ───────────────────────────────────────────
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), INPUT_SHAPE)
        val srTensor    = OnnxTensor.createTensor(env, LongBuffer.wrap(SR_VALUE), SR_SHAPE)
        val hTensor     = OnnxTensor.createTensor(env, FloatBuffer.wrap(h.copyOf()), STATE_SHAPE)
        val cTensor     = OnnxTensor.createTensor(env, FloatBuffer.wrap(c.copyOf()), STATE_SHAPE)

        // Build a Map<String, OnnxTensorLike> for session.run().
        // OnnxTensor implements OnnxTensorLike; the explicit type annotation
        // satisfies the Java wildcard bound Map<String, ? extends OnnxTensorLike>.
        val inputs = linkedMapOf<String, ai.onnxruntime.OnnxTensorLike>(
            "input" to inputTensor,
            "sr"    to srTensor,
            "h"     to hTensor,
            "c"     to cTensor,
        )

        try {
            session.run(inputs).use { result ->
                // OrtSession.Result.get(String) returns Optional<OnnxValue> in ORT 1.18.
                // .get() on the Optional throws NoSuchElementException if the tensor is
                // absent — that would indicate a corrupt model, not a recoverable error.

                // ── Read speech probability ───────────────────────────────
                val prob = (result.get("output").get() as OnnxTensor).floatBuffer.get(0)

                // ── Carry LSTM state forward ──────────────────────────────
                (result.get("hn").get() as OnnxTensor).floatBuffer.get(h)
                (result.get("cn").get() as OnnxTensor).floatBuffer.get(c)

                return prob
            }
        } finally {
            // Input tensors are NOT released by OrtSession.Result.close() — release them here.
            inputTensor.close()
            srTensor.close()
            hTensor.close()
            cTensor.close()
        }
    }

    /**
     * Reset LSTM state to zeros.
     *
     * Must be called between utterances so context from one turn does not
     * carry over to the next.
     */
    fun reset() {
        h = FloatArray(STATE_SIZE)
        c = FloatArray(STATE_SIZE)
        Log.d(TAG, "VAD_STATE_RESET")
    }

    override fun close() {
        try { session.close() } catch (_: Exception) {}
    }
}
