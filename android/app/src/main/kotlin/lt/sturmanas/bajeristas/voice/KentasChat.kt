package lt.sturmanas.bajeristas.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.personality.KentasPersona
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CHAT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
private const val MODEL = "gpt-4o-mini"

// Kentas's responses are ≤ 10 words by persona rule; 80 tokens is a generous cap.
private const val MAX_TOKENS = 80

/**
 * Sends [userText] to OpenAI Chat Completions and returns Kentas's reply.
 *
 * [history] — recent (role, content) pairs maintained by [KentasConversationController] —
 * is spliced between the system prompt and the current user turn so the model maintains
 * conversational context across push-to-talk presses within one drive.
 * Pass [emptyList] for the very first turn.
 *
 * Uses [KentasPersona.systemPrompt] (single fixed prompt — no SessionConfig).
 *
 * Never throws — all failures are returned as a Lithuanian error string that the
 * conversation controller will speak via TTS.
 *
 * @param userText  Lithuanian phrase recognised by SpeechRecognizer.
 * @param navState  Current navigation state prepended to the user turn as context.
 * @param apiKey    OpenAI API key from BuildConfig. Never hardcode.
 * @param history   Recent (role, content) pairs from KentasConversationController.
 */
suspend fun askKentas(
    userText: String,
    navState: NavigationState,
    apiKey: String,
    history: List<Pair<String, String>> = emptyList(),
): String = withContext(Dispatchers.IO) {

    if (apiKey.isBlank()) {
        return@withContext "OpenAI raktas nenurodytas — pridėkite OPENAI_API_KEY į local.properties"
    }

    try {
        // ── Navigation context ────────────────────────────────────────────
        val distMeters = navState.distanceToNextManeuverMeters
            .let { if (it == Int.MAX_VALUE) 0 else it }
        val street = navState.nextRoadName.ifBlank { navState.currentRoadName }.ifBlank { "nežinoma" }

        val navContext = KentasPersona.navigationContext(
            nextManeuver = navState.maneuverType.name,
            street = street,
            distanceToManeuverMeters = distMeters,
            remainingDistanceMeters = if (navState.remainingDistanceMeters > 0)
                navState.remainingDistanceMeters else distMeters,
            remainingSeconds = navState.remainingDurationSeconds,
        )

        val userMessage = "$navContext\n\nVairuotojas: $userText"

        // ── Request body ──────────────────────────────────────────────────
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", KentasPersona.systemPrompt)
                })
                for ((role, content) in history) {
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
        }.toString()

        // ── HTTP call ─────────────────────────────────────────────────────
        val conn = (URL(CHAT_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
        }

        try {
            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val responseText = if (responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } else {
                conn.errorStream?.use { it.readBytes() }
                return@withContext when (responseCode) {
                    401 -> "OpenAI: neteisingas API raktas"
                    429 -> "OpenAI: per daug užklausų — bandykite vėliau"
                    500, 503 -> "OpenAI laikinai neprieinamas"
                    else -> "OpenAI klaida (HTTP $responseCode)"
                }
            }

            val reply = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            if (reply.isBlank()) "…" else reply

        } finally {
            conn.disconnect()
        }

    } catch (e: Exception) {
        "Tinklo klaida: ${e.message?.take(50) ?: e.javaClass.simpleName}"
    }
}
