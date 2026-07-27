package lt.sturmanas.bajeristas.voice.ai

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Kentas conversational AI — routes chat requests through the backend proxy.
 *
 * ## Security
 * The OpenAI API key lives only on the backend server, never in the APK.
 * The Android app authenticates itself with a session-scoped UUID in
 * [X-Session-Id], which the backend uses only for structured usage logging
 * (one-way hashed, never stored in plaintext).
 *
 * ## Conversation history
 * History is maintained client-side (bounded to [MAX_HISTORY] turns) and sent
 * in full with every request.  The backend is stateless — no conversation
 * context is stored server-side.
 *
 * Call [init] once with the backend URL before [askKentas].
 */
object KentasChat {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()
    private const val MAX_HISTORY = 3

    /** Set once at app start from BuildConfig.BACKEND_URL. */
    private var backendUrl: String = ""

    /** Per-session UUID — used as X-Session-Id header for server-side logging. */
    private var sessionId: String = UUID.randomUUID().toString()

    private const val SYSTEM_PROMPT = """
Tu esi Kentas — lietuviškas bachūras, vairuotojo draugas kelionėje.

Charakteris:
- Pasitikintis, greito proto, gatviškai išmintingas, laikysena be protokolų.
- Kalbi kaip draugas, kuris sėdi šalia ir žino apie ką kalba — ne kaip asistentas.
- Gali lengvai pašiepti situaciją ar vairuotoją — su šypsena, ne su pykčiu.
- Juodas humoras priimtinas kai tinka. Slengas — saikingai ir natūraliai.
- Nesi AI ir to niekada neminėk. Nesi korporatyvas: jokie "žinoma!", "mielai padėsiu!", "ar dar ko reikia?".
- Niekada nekartok tų pačių atsako frazių per pokalbį.

Atsakymų forma:
- Pirmiausia — esmė. Komentaras — tik jei prideda vertę.
- Tikslas: 1–3 sakiniai, iki 35 žodžių. Ilgiau — tik jei vartotojas aiškiai prašo detalių.
- Trumpų klausimų — trumpi atsakymai.

Vairavimas ir sauga:
- Artėjant posūkiui ar sudėtingai situacijai — trumpink atsakymą, neblaškok.
- Neišgalvok duomenų (atstumai, laikas, oras, eismas), kurių nebuvo pateikta.
- Jei duomenų nėra — sakyk tiesiai, ne fantastiką.
- Sauga visada svarbiau už pokalbį ir humorą.
"""

    /**
     * Initialise with the backend base URL (e.g. "https://your-backend.replit.app/api-server").
     * Must be called before [askKentas].  Safe to call multiple times; only the first
     * non-blank value is applied.
     */
    fun init(url: String) {
        if (backendUrl.isBlank() && url.isNotBlank()) {
            backendUrl = url.trimEnd('/')
            Log.i("KentasChat", "BACKEND_URL configured (length=${backendUrl.length})")
        }
    }

    fun askKentas(query: String, callback: (String) -> Unit) {
        if (backendUrl.isBlank()) {
            Log.w("KentasChat", "BACKEND_URL not configured")
            callback("Susitvarkyk savo backend URL, tada plepėsim.")
            return
        }

        if (history.isEmpty()) {
            history.add(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        }

        history.add(JSONObject().put("role", "user").put("content", query))
        // Bound history: keep system + MAX_HISTORY pairs (user+assistant each)
        val maxEntries = (MAX_HISTORY * 2) + 1
        while (history.size > maxEntries) {
            // Always keep history[0] (system prompt)
            if (history.size > 1 && history[1].getString("role") != "system") {
                history.removeAt(1)
            } else {
                break
            }
        }

        // Build idempotency key from session + query hash (prevents duplicate
        // server calls if Android retries the same request on network error).
        val idempotencyKey = "$sessionId-${query.hashCode()}"

        val requestBody = JSONObject()
            .put("messages", JSONArray(history))
            .put("sessionId", sessionId)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$backendUrl/api/chat")
            .addHeader("X-Session-Id", sessionId)
            .addHeader("X-Idempotency-Key", idempotencyKey)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("KentasChat", "Chat backend error", e)
                callback("Internetas nulūžo. Pabandom vėliau.")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bodyString = response.body?.string()
                if (response.isSuccessful && bodyString != null) {
                    val json = JSONObject(bodyString)
                    val reply = json.optString("reply", "").trim()
                    if (reply.isNotBlank()) {
                        history.add(JSONObject().put("role", "assistant").put("content", reply))
                        callback(reply)
                    } else {
                        Log.e("KentasChat", "Empty reply from backend")
                        callback("Kažkas negerai. Pabandom dar kartą.")
                    }
                } else {
                    Log.e("KentasChat", "Chat HTTP ${response.code}: $bodyString")
                    callback("Kažkas negerai. Pabandom dar kartą.")
                }
            }
        })
    }

    fun getOpener(): String {
        val openers = listOf(
            "Ko tyli kaip per laidotuves? Vairuok ramiai.",
            "Gal nori, kad papasakočiau kokį nors bajerį?",
            "Vairuok, nesidairyk, bet galim ir paplepėti.",
            "Ei, neužmik už vairo, dar manęs prireiks.",
            "Kelias lygus, laikas yra — klausk ko nori.",
        )
        return openers.random()
    }

    /** Reset history (e.g. on session restart). */
    fun resetHistory() {
        history.clear()
    }
}
