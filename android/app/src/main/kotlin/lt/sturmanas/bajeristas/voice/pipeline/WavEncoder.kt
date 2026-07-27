package lt.sturmanas.bajeristas.voice.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes raw PCM audio into an in-memory WAV (RIFF/WAVE) byte array.
 *
 * Only PCM 16-bit little-endian is supported, which matches [AudioRecordSource].
 * The produced bytes are safe to pass directly to [TranscriptionClient.transcribe].
 */
object WavEncoder {

    private const val HEADER_SIZE = 44
    private const val PCM_FORMAT: Short = 1  // Linear PCM

    /**
     * Encode [pcm] as a WAV file.
     *
     * @param pcm           Raw signed 16-bit little-endian PCM samples.
     * @param sampleRate    Samples per second (default: 16 000 Hz).
     * @param channels      Number of audio channels (default: 1 — mono).
     * @param bitsPerSample Bits per sample (default: 16).
     * @return Complete WAV byte array (44-byte header + PCM data).
     */
    fun encode(
        pcm: ByteArray,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataChunkSize = pcm.size
        val riffChunkSize = HEADER_SIZE - 8 + dataChunkSize  // total file size - 8

        val buf = ByteBuffer.allocate(HEADER_SIZE + dataChunkSize)
            .order(ByteOrder.LITTLE_ENDIAN)

        // ── RIFF chunk descriptor ────────────────────────────────────────
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))       // ChunkID
        buf.putInt(riffChunkSize)                            // ChunkSize
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))       // Format

        // ── fmt sub-chunk ────────────────────────────────────────────────
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))       // Subchunk1ID
        buf.putInt(16)                                       // Subchunk1Size (PCM → 16)
        buf.putShort(PCM_FORMAT)                             // AudioFormat
        buf.putShort(channels.toShort())                     // NumChannels
        buf.putInt(sampleRate)                               // SampleRate
        buf.putInt(byteRate)                                 // ByteRate
        buf.putShort(blockAlign)                             // BlockAlign
        buf.putShort(bitsPerSample.toShort())                // BitsPerSample

        // ── data sub-chunk ───────────────────────────────────────────────
        buf.put("data".toByteArray(Charsets.US_ASCII))       // Subchunk2ID
        buf.putInt(dataChunkSize)                            // Subchunk2Size
        buf.put(pcm)                                         // PCM samples

        return buf.array()
    }

    // ── Convenience helpers used by tests ────────────────────────────────

    /**
     * Duration of the PCM payload in milliseconds.
     *
     * @param pcmBytes  Number of raw PCM bytes (NOT including the WAV header).
     * @param sampleRate Samples per second.
     * @param channels   Number of channels.
     * @param bitsPerSample Bits per sample.
     */
    fun durationMs(
        pcmBytes: Int,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): Long {
        val bytesPerMs = sampleRate.toLong() * channels * bitsPerSample / 8 / 1000
        return if (bytesPerMs == 0L) 0L else pcmBytes / bytesPerMs
    }
}
