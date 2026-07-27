package lt.sturmanas.bajeristas.voice.pipeline

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Thin wrapper around [AudioRecord] that captures continuous 16 kHz mono
 * PCM 16-bit audio and exposes it as fixed-size chunks.
 *
 * ## Design constraints
 * - Does NOT call setCommunicationDevice.
 * - Does NOT request a specific physical microphone.
 * - All reads are blocking; callers must invoke [readChunk] from a
 *   background thread (e.g. [kotlinx.coroutines.Dispatchers.IO]).
 *
 * ## Audio source
 * [MediaRecorder.AudioSource.VOICE_RECOGNITION] is the recommended source
 * for speech recognition on Android.  It applies noise suppression and
 * echo cancellation tuned for the microphone hardware without needing
 * explicit [android.media.audiofx] effects.
 *
 * @param sampleRate   Samples per second (must match Silero VAD model).
 * @param chunkSamples Number of samples per chunk (must match Silero VAD
 *                     model chunk size: 512 samples = 32 ms at 16 kHz).
 */
class AudioRecordSource(
    val sampleRate: Int = PipelineConfig.SAMPLE_RATE,
    val chunkSamples: Int = PipelineConfig.CHUNK_SAMPLES,
) {

    companion object {
        private const val TAG = "AudioRecordSource"
    }

    /** Bytes per chunk: 2 bytes per int16 sample. */
    val chunkBytes: Int = chunkSamples * 2

    private var audioRecord: AudioRecord? = null

    /**
     * Initialise and start [AudioRecord].
     *
     * Must be called from a background thread.
     *
     * @return `true` on success, `false` if the hardware is unavailable or
     *         RECORD_AUDIO permission has not been granted.
     */
    fun initialize(): Boolean {
        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "MIC_AUDIORECORD_INIT_FAILED reason=getMinBufferSize returned $minBufSize")
            return false
        }

        // Keep a comfortable margin: at least 4× the chunk size so the kernel
        // ring buffer never starves between reads.
        val bufSize = maxOf(minBufSize, chunkBytes * 4)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "MIC_AUDIORECORD_INIT_FAILED reason=STATE=${ar.state}")
            ar.release()
            return false
        }

        ar.startRecording()
        audioRecord = ar
        Log.i(TAG, "MIC_AUDIORECORD_READY sampleRate=$sampleRate bufferSize=$bufSize chunkBytes=$chunkBytes")
        return true
    }

    /**
     * Blocking read of exactly [chunkBytes] bytes.
     *
     * Returns `null` if [AudioRecord] is not initialised or if the read
     * returns fewer bytes than expected (device error or shutdown).
     *
     * Must be called from a background thread.
     */
    fun readChunk(): ByteArray? {
        val ar = audioRecord ?: return null
        val buf = ByteArray(chunkBytes)
        val read = ar.read(buf, 0, chunkBytes)
        return if (read == chunkBytes) buf else null
    }

    /**
     * Stop recording and release hardware resources.
     * Safe to call multiple times.
     */
    fun release() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        Log.i(TAG, "MIC_AUDIORECORD_RELEASED")
    }

    /** `true` while [AudioRecord] is recording. */
    val isRecording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
}
