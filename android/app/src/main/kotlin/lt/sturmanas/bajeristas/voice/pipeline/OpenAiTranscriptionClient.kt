package lt.sturmanas.bajeristas.voice.pipeline

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [TranscriptionClient] that proxies audio through the Šturmanas Bajeristas
 * backend (`POST /api/transcribe`) rather than calling OpenAI directly.
 *
 * ## Why a backend proxy?
 * Embedding the OpenAI API key in the APK would expose it to anyone who
 * decompiles the app.  The backend holds the key in an environment variable
 * and performs all privileged provider calls server-side.
 *
 * ## Request format
 *   - Method:        POST
 *   - URL:           `$backendUrl/api/transcribe?lang=$language`
 *   - Content-Type:  audio/wav
 *   - Body:          raw WAV bytes
 *   - Header:        X-Session-Id: <session UUID>
 *
 * ## Response
 *   { "text": "transcribed text" }
 */
class OpenAiTranscriptionClient(
    private val backendUrl: String,
    private val sessionId: String = "unset",
    private val client: OkHttpClient = defaultClient(),
) : TranscriptionClient {

    companion object {
        private const val TAG = "OpenAiStt"

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
        if (backendUrl.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Backend URL not configured (BACKEND_URL in local.properties)")
            )
        }

        val url = "$backendUrl/api/transcribe?lang=$language"

        val request = Request.Builder()
            .url(url)
            .addHeader("X-Session-Id", sessionId)
            .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
            .build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "STT_HTTP_ERROR code=${response.code} body=${body?.take(200)}")
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
