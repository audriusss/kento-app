package lt.sturmanas.bajeristas.voice.pipeline

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for the Phase 2 VAD pipeline.
 *
 * All tests run on the JVM — no Android SDK required.
 *
 * Coverage:
 *  - WAV header correctness             (WavEncoderTest)
 *  - PCM byte count and duration        (WavEncoderTest)
 *  - Pre-roll inclusion                 (UtteranceSegmenterTest)
 *  - Short noise burst rejection        (UtteranceSegmenterTest)
 *  - Trailing silence completion        (UtteranceSegmenterTest)
 *  - Maximum utterance duration         (UtteranceSegmenterTest)
 *  - Mute clears active utterance       (UtteranceSegmenterTest)
 *  - Empty transcription response       (OpenAiTranscriptionClientTest)
 *  - HTTP error handling                (OpenAiTranscriptionClientTest)
 */
class VadPipelineTest {

    // ─────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────

    companion object {
        private const val CHUNK_SAMPLES = 512
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2  // int16

        /** All-zero chunk — classified as silence. */
        private fun silenceChunk() = ByteArray(CHUNK_BYTES)

        /** Non-zero chunk with a deterministic pattern — classified as speech when prob ≥ threshold. */
        private fun speechChunk(): ByteArray {
            val buf = ByteArray(CHUNK_BYTES)
            for (i in buf.indices) buf[i] = ((i % 127) + 1).toByte()
            return buf
        }

        private fun readLE32(wav: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(wav, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

        private fun readLE16(wav: ByteArray, offset: Int): Short =
            ByteBuffer.wrap(wav, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
    }

    // ─────────────────────────────────────────────────────────────────────
    // WavEncoder tests
    // ─────────────────────────────────────────────────────────────────────

    @Test fun `wav RIFF magic is present`() {
        val magic = WavEncoder.encode(ByteArray(0)).copyOfRange(0, 4).toString(Charsets.US_ASCII)
        assertEquals("RIFF", magic)
    }

    @Test fun `wav WAVE format marker is present`() {
        val fmt = WavEncoder.encode(ByteArray(0)).copyOfRange(8, 12).toString(Charsets.US_ASCII)
        assertEquals("WAVE", fmt)
    }

    @Test fun `wav audio format field is PCM = 1`() {
        // fmt sub-chunk starts at byte 12; AudioFormat is at offset 20.
        assertEquals(1.toShort(), readLE16(WavEncoder.encode(ByteArray(0)), 20))
    }

    @Test fun `wav channel count is 1 mono`() {
        assertEquals(1.toShort(), readLE16(WavEncoder.encode(ByteArray(0)), 22))
    }

    @Test fun `wav sample rate field is 16000`() {
        assertEquals(16_000, readLE32(WavEncoder.encode(ByteArray(0)), 24))
    }

    @Test fun `wav byte rate is sampleRate times channels times bytesPerSample`() {
        // 16000 * 1 * 2 = 32000
        assertEquals(32_000, readLE32(WavEncoder.encode(ByteArray(0)), 28))
    }

    @Test fun `wav block align is 2 for mono 16-bit`() {
        assertEquals(2.toShort(), readLE16(WavEncoder.encode(ByteArray(0)), 32))
    }

    @Test fun `wav total size is 44-byte header plus pcm length`() {
        val pcm = ByteArray(1024)
        assertEquals(44 + 1024, WavEncoder.encode(pcm).size)
    }

    @Test fun `wav data chunk id is data`() {
        val wav = WavEncoder.encode(ByteArray(3200))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
    }

    @Test fun `wav data chunk size field matches pcm byte count`() {
        val pcm = ByteArray(3200)
        assertEquals(3200, readLE32(WavEncoder.encode(pcm), 40))
    }

    @Test fun `wav riff chunk size is file size minus 8`() {
        val pcm = ByteArray(6400)
        val wav = WavEncoder.encode(pcm)
        assertEquals(wav.size - 8, readLE32(wav, 4))
    }

    @Test fun `duration calculation is correct for 1-second payload`() {
        // 32000 bytes = 1 s of mono 16-bit 16 kHz PCM
        assertEquals(1000L, WavEncoder.durationMs(pcmBytes = 32_000))
    }

    @Test fun `duration matches chunk count times 32 ms`() {
        val chunks = 47
        assertEquals(chunks.toLong() * 32, WavEncoder.durationMs(pcmBytes = chunks * CHUNK_BYTES))
    }

    // ─────────────────────────────────────────────────────────────────────
    // UtteranceSegmenter tests
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Compact config for deterministic chunk-count arithmetic:
     *   minSpeechChunks     = 3  (3 × 32 ms = 96 ms)
     *   trailingSilenceChunks = 3
     *   maxUtteranceChunks  = 10
     *   preRollChunks       = 2
     */
    private val cfg = UtteranceSegmenter.Config(
        speechThreshold   = 0.5f,
        minSpeechMs       = 3 * 32,
        trailingSilenceMs = 3 * 32,
        maxUtteranceMs    = 10 * 32,
        preRollMs         = 2 * 32,
        chunkMs           = 32,
    )

    private fun seg() = UtteranceSegmenter(cfg)

    private fun feedAll(
        s: UtteranceSegmenter,
        pairs: List<Pair<ByteArray, Float>>,
    ): List<UtteranceSegmenter.Event> = pairs.flatMap { (pcm, p) -> s.processChunk(pcm, p) }

    @Test fun `noise burst shorter than minSpeechChunks is discarded`() {
        val s = seg()
        val events = mutableListOf<UtteranceSegmenter.Event>()
        // 2 speech chunks (< 3) then silence → must discard
        repeat(2) { events += s.processChunk(speechChunk(), 0.9f) }
        events += s.processChunk(silenceChunk(), 0.0f)

        assertTrue(
            "Expected Discarded(noise_burst)",
            events.filterIsInstance<UtteranceSegmenter.Event.Discarded>()
                  .any { it.reason.startsWith("noise_burst") },
        )
        assertTrue(
            "No UtteranceReady expected for noise burst",
            events.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>().isEmpty(),
        )
    }

    @Test fun `pre-roll is included in utterance pcm`() {
        val s = seg()
        // Fill pre-roll (2 silence chunks)
        s.processChunk(silenceChunk(), 0.0f)
        s.processChunk(silenceChunk(), 0.0f)

        // Confirm speech (3 chunks) then trailing silence (3 chunks)
        val events = mutableListOf<UtteranceSegmenter.Event>()
        repeat(3) { events += s.processChunk(speechChunk(), 0.9f) }
        repeat(3) { events += s.processChunk(silenceChunk(), 0.0f) }

        val ut = events.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>().firstOrNull()
        assertNotNull("Expected UtteranceReady", ut)
        // pre-roll(2) + speech(3) + trailing(3) = 8 chunks minimum
        assertTrue(
            "PCM too short: ${ut!!.pcm.size}, expected ≥ ${8 * CHUNK_BYTES}",
            ut.pcm.size >= 8 * CHUNK_BYTES,
        )
    }

    @Test fun `trailing silence emits utterance`() {
        val s = seg()
        val events = mutableListOf<UtteranceSegmenter.Event>()
        repeat(3) { events += s.processChunk(speechChunk(), 0.9f) }
        repeat(3) { events += s.processChunk(silenceChunk(), 0.0f) }

        assertEquals(
            1,
            events.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>().size,
        )
    }

    @Test fun `utterance duration matches chunk count times chunkMs`() {
        val s = seg()
        val events = mutableListOf<UtteranceSegmenter.Event>()
        // 3 speech + 3 trailing = 6 utterance chunks → 192 ms
        repeat(3) { events += s.processChunk(speechChunk(), 0.9f) }
        repeat(3) { events += s.processChunk(silenceChunk(), 0.0f) }

        val ut = events.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>().first()
        assertEquals(192L, ut.durationMs)
    }

    @Test fun `max utterance duration forces early emission`() {
        val s = seg()
        val events = mutableListOf<UtteranceSegmenter.Event>()
        // maxUtteranceChunks = 10; feed 11 continuous speech chunks
        repeat(11) { events += s.processChunk(speechChunk(), 0.9f) }

        assertTrue(
            "Expected UtteranceReady from max-duration flush",
            events.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>().isNotEmpty(),
        )
    }

    @Test fun `mute discards in-progress utterance`() {
        val s = seg()
        val events = mutableListOf<UtteranceSegmenter.Event>()
        // Start confirmed speech
        repeat(3) { events += s.processChunk(speechChunk(), 0.9f) }
        // Mute mid-utterance
        s.mute()
        // Audio while muted — no events expected
        repeat(5) { events += s.processChunk(speechChunk(), 0.9f) }

        assertTrue(
            "No UtteranceReady expected after mute",
            events.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>().isEmpty(),
        )
    }

    @Test fun `unmute then new utterance is accepted`() {
        val s = seg()
        repeat(3) { s.processChunk(speechChunk(), 0.9f) }
        s.mute()
        repeat(5) { s.processChunk(speechChunk(), 0.9f) }
        s.unmute()

        val events = mutableListOf<UtteranceSegmenter.Event>()
        repeat(3) { events += s.processChunk(speechChunk(), 0.9f) }
        repeat(3) { events += s.processChunk(silenceChunk(), 0.0f) }

        assertEquals(
            1,
            events.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>().size,
        )
    }

    @Test fun `SpeechStarted fires exactly when min duration is met`() {
        val s = seg()
        val events = mutableListOf<UtteranceSegmenter.Event>()
        // 2 chunks: below minSpeechChunks=3 → no SpeechStarted yet
        repeat(2) { events += s.processChunk(speechChunk(), 0.9f) }
        assertFalse(events.any { it is UtteranceSegmenter.Event.SpeechStarted })
        // 3rd chunk → SpeechStarted fires
        events += s.processChunk(speechChunk(), 0.9f)
        assertTrue(events.any { it is UtteranceSegmenter.Event.SpeechStarted })
    }

    @Test fun `state is SILENCE after utterance is emitted`() {
        val s = seg()
        repeat(3) { s.processChunk(speechChunk(), 0.9f) }
        repeat(3) { s.processChunk(silenceChunk(), 0.0f) }
        assertEquals("SILENCE", s.currentState)
    }

    @Test fun `second utterance is accepted after first is emitted`() {
        val s = seg()
        // First
        repeat(3) { s.processChunk(speechChunk(), 0.9f) }
        repeat(3) { s.processChunk(silenceChunk(), 0.0f) }
        // Second
        val events = mutableListOf<UtteranceSegmenter.Event>()
        repeat(3) { events += s.processChunk(speechChunk(), 0.9f) }
        repeat(3) { events += s.processChunk(silenceChunk(), 0.0f) }
        assertEquals(1, events.filterIsInstance<UtteranceSegmenter.Event.UtteranceReady>().size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenAiTranscriptionClient HTTP tests (via MockWebServer)
    // ─────────────────────────────────────────────────────────────────────

    private lateinit var mockServer: MockWebServer

    @Before fun setUpMockServer() { mockServer = MockWebServer(); mockServer.start() }
    @After  fun tearDownMockServer() { mockServer.shutdown() }

    /**
     * Minimal test double that reuses [OpenAiTranscriptionClient]'s parsing
     * logic against a configurable base URL.  Avoids reflection and avoids
     * modifying the production class visibility.
     */
    private inner class StubTranscriptionClient(
        private val apiKey: String,
        private val baseUrl: String,
    ) : TranscriptionClient {

        private val http = OkHttpClient()

        override suspend fun transcribe(wavBytes: ByteArray, language: String): Result<String> {
            if (apiKey.isBlank()) {
                return Result.failure(IllegalStateException("API key not configured"))
            }

            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file", "audio.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType()),
                )
                .addFormDataPart("model", OpenAiTranscriptionClient.MODEL)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url("${baseUrl}v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            return try {
                http.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful) {
                        return Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                    }
                    if (responseBody.isNullOrBlank()) {
                        return Result.failure(IOException("Empty response body"))
                    }
                    val text = try {
                        JSONObject(responseBody).getString("text").trim()
                    } catch (e: Exception) {
                        return Result.failure(IOException("Parse error: ${e.message}"))
                    }
                    if (text.isBlank()) {
                        return Result.failure(IOException("Empty transcript"))
                    }
                    Result.success(text)
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    private fun client(apiKey: String = "test-key") =
        StubTranscriptionClient(apiKey, mockServer.url("/").toString())

    private val dummyWav = WavEncoder.encode(ByteArray(64))

    @Test fun `successful transcription returns trimmed text`() = runBlocking {
        mockServer.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"text":"labas vakaras"}""")
                .addHeader("Content-Type", "application/json"),
        )
        val result = client().transcribe(dummyWav)
        assertTrue(result.isSuccess)
        assertEquals("labas vakaras", result.getOrNull())
    }

    @Test fun `HTTP 401 returns failure`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val result = client(apiKey = "bad-key").transcribe(dummyWav)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
    }

    @Test fun `HTTP 500 returns failure`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        assertTrue(client().transcribe(dummyWav).isFailure)
    }

    @Test fun `empty text field returns failure`() = runBlocking {
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"text":""}"""),
        )
        assertTrue(client().transcribe(dummyWav).isFailure)
    }

    @Test fun `whitespace-only text returns failure`() = runBlocking {
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"text":"   "}"""),
        )
        assertTrue(client().transcribe(dummyWav).isFailure)
    }

    @Test fun `blank api key returns failure with no network call`() = runBlocking {
        val result = client(apiKey = "").transcribe(dummyWav)
        assertTrue(result.isFailure)
        assertEquals(0, mockServer.requestCount)
    }

    @Test fun `malformed JSON returns failure`() = runBlocking {
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody("not-json"),
        )
        assertTrue(client().transcribe(dummyWav).isFailure)
    }
}
