package lt.sturmanas.bajeristas.voice.pipeline

/**
 * Provider-independent interface for speech-to-text transcription.
 *
 * Implementations receive a complete utterance as a WAV file and return the
 * transcript.  The language tag follows BCP-47 (ISO 639-1 two-letter code).
 *
 * Callers are responsible for encoding PCM data to WAV before calling
 * [transcribe].  See [WavEncoder].
 */
interface TranscriptionClient {

    /**
     * Transcribe [wavBytes] into text.
     *
     * @param wavBytes  Complete, in-memory WAV file (RIFF/PCM 16-bit mono
     *                  16 kHz).  The bytes must include the 44-byte header.
     * @param language  BCP-47 language tag sent to the STT backend
     *                  (default: "lt" for Lithuanian).
     * @return [Result.success] containing the non-blank transcript, or
     *         [Result.failure] with a descriptive [Exception] for every
     *         non-recoverable error (HTTP failure, empty transcript, parse
     *         error, timeout).
     */
    suspend fun transcribe(
        wavBytes: ByteArray,
        language: String = "lt",
    ): Result<String>
}
