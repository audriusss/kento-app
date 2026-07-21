package lt.sturmanas.bajeristas.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.personality.PersonaPrompts
import lt.sturmanas.bajeristas.personality.SessionConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CHAT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
private const val MODEL = "gpt-4o-mini"

// Kentas's responses are ≤ 12 words by persona rule; 80 tokens is a generous cap.
private const val MAX_TOKENS = 80

/**
 * Sends [userText] to OpenAI Chat Completions as a single Kentas turn and returns
 * the reply string.
 *
 * The call is fire-and-forget from the caller's perspective: it never throws — all
 * failures are caught and returned as a Lithuanian-language error string so they can
 * be displayed directly in [aiStatusMessage] without crashing.
 *
 * Uses [HttpURLConnection] from the Android SDK — no additional Gradle dependency.
 *
 * @param userText  The Lithuanian phrase recognised by SpeechRecognizer.
 * @param config    Session personality config (ConversationMode, TripMode, HumorIntensity…).
 * @param navState  Current navigation state — used to build the navigation context block
 *                  prepended to every user turn so Kentas knows what is happening on the road.
 * @param apiKey    OpenAI API key from [BuildConfig.OPENAI_API_KEY], compiled in from
 *                  local.properties. Must never be hardcoded here or committed to source control.
 */
suspend fun askKentas(
    userText: String,
    config: SessionConfig,
    navState: NavigationState,
    apiKey: String,
): String = withContext(Dispatchers.IO) {

    if (apiKey.isBlank()) {
        return@withContext "OpenAI raktas nenurodytas — pridėkite OPENAI_API_KEY į local.properties"
    }

    try {
        // ── Build prompt ──────────────────────────────────────────────────
        val systemPrompt = PersonaPrompts.systemPrompt(config)

        val distanceMeters = navState.distanceToNextManeuverMeters
            .let { if (it == Int.MAX_VALUE) 0 else it }
        val street = navState.nextRoadName.ifBlank { navState.currentRoadName }.ifBlank { "nežinoma" }

        val navContext = PersonaPrompts.navigationContext(
            nextManeuver = navState.maneuverType.name,
            street = street,
            distanceToManeuverMeters = distanceMeters,
            remainingDistanceMeters = navState.remainingDistanceMeters,
            remainingSeconds = navState.remainingDurationSeconds,
        )

        // Navigation context is prepended so Kentas knows what is on the road ahead,
        // even if the driver's message contains no navigation reference.
        val userMessage = "$navContext\n\nVairuotojas: $userText"

        // ── Build JSON body ───────────────────────────────────────────────
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
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
                // Drain and discard the error body so the connection can be reused;
                // the status code alone is enough to produce the right Lithuanian message.
                conn.errorStream?.use { it.readBytes() }
                return@withContext when (responseCode) {
                    401 -> "OpenAI: neteisingas API raktas"
                    429 -> "OpenAI: per daug užklausų — bandykite vėliau"
                    500, 503 -> "OpenAI laikinai neprieinamas"
                    else -> "OpenAI klaida (HTTP $responseCode)"
                }
            }

            // ── Parse reply ───────────────────────────────────────────────
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
        // Surface enough info to diagnose without exposing internals.
        "Tinklo klaida: ${e.message?.take(50) ?: e.javaClass.simpleName}"
    }
}
