package lt.sturmanas.bajeristas.voice.pipeline

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [TranscriptionClient] backed by the OpenAI audio transcription API.
 *
 * The implementation is deliberately isolated: it shares no state with
 * [lt.sturmanas.bajeristas.voice.ai.KentasChat] and creates its own
 * [OkHttpClient] with transcription-appropriate timeouts.
 *
 * ## Model
 * [MODEL] is the single configuration point.  Change it here to switch
 * providers or model versions without touching any other class.
 *
 * ## Security
 * - The API key is never logged.
 * - Raw audio bytes are never logged or written to disk.
 */
class OpenAiTranscriptionClient(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
) : TranscriptionClient {

    companion object {
        private const val TAG = "OpenAiStt"

        /**
         * Current recommended OpenAI speech-to-text model.
         *
         * gpt-4o-transcribe (released March 2025) supersedes whisper-1 in
         * accuracy across all languages, including Lithuanian.  It supports
         * the same /v1/audio/transcriptions endpoint and all existing
         * parameters (language, prompt, response_format).
         *
         * Update this constant to switch models project-wide.
         */
        const val MODEL = "gpt-4o-transcribe"

        private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"

        /**
         * Lithuanian-specific prompt.
         *
         * Whisper-family models use the prompt to bias the vocabulary toward
         * expected terms before decoding begins.  For a navigation assistant
         * the most valuable seeds are proper nouns (cities, road names) and
         * common driving commands.  The prompt is intentionally short; longer
         * prompts reduce, not improve, accuracy in practice.
         */
        private const val LT_PROMPT =
            "Kentas, navigacija, Lietuva, Vilnius, Kaunas, Klaipėda, " +
            "sukite, pasukite, važiuokite, sustokite, tiesiog."

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }

    override suspend fun transcribe(
        wavBytes: ByteArray,
        language: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("OpenAI API key is not configured")
            )
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = "audio.wav",
                body = wavBytes.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", language)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("prompt", LT_PROMPT)
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "STT_HTTP_ERROR code=${response.code}")
                    return@use Result.failure(
                        IOException("HTTP ${response.code}: ${response.message}")
                    )
                }

                if (body.isNullOrBlank()) {
                    return@use Result.failure(
                        IOException("Empty response body from transcription API")
                    )
                }

                val text = try {
                    JSONObject(body).getString("text").trim()
                } catch (e: Exception) {
                    return@use Result.failure(
                        IOException("Failed to parse transcription response: ${e.message}")
                    )
                }

                if (text.isBlank()) {
                    return@use Result.failure(
                        IOException("Empty transcript returned by model")
                    )
                }

                Result.success(text)
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IOException("Transcription request failed: ${e.message}", e))
        }
    }
}
