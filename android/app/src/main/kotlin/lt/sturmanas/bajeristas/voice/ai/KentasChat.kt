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
 * ## Conversation memory
 * History is maintained client-side (bounded to [MAX_HISTORY] user+assistant pairs)
 * and sent in full with every request.  The backend is stateless — no conversation
 * context is stored server-side.
 *
 * Navigation commands ("nutrauk maršrutą", "važiuojam į X", "atšauk") are
 * intercepted by [AIConversationController] before [askKentas] is called, so they
 * never appear in conversation history.
 *
 * Navigation context (distance to next maneuver) is injected as a *transient*
 * system message per request and is NOT stored in [history], keeping the rolling
 * buffer clean and containing only real user↔Kentas turns.
 *
 * Call [init] once with the backend URL before [askKentas].
 */
object KentasChat {

    private const val TAG = "KentasChat"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    /**
     * Rolling buffer: system prompt (index 0) + up to [MAX_HISTORY] user/assistant
     * pairs = at most (MAX_HISTORY * 2) + 1 entries.
     *
     * 10 pairs ≈ 5–10 minutes of typical conversation at normal speaking pace.
     * Older turns are dropped from index 1 (preserving the system prompt at [0])
     * when the buffer exceeds capacity.
     */
    private val history = mutableListOf<JSONObject>()
    private const val MAX_HISTORY = 10

    /** Set once at app start from BuildConfig.BACKEND_URL. */
    private var backendUrl: String = ""

    /** Per-session UUID — used as X-Session-Id header for server-side logging. */
    private var sessionId: String = UUID.randomUUID().toString()

    private const val SYSTEM_PROMPT = """
Tu esi Kentas — keleivas priekinėje sėdynėje. Kalbi kaip senas draugas automobilyje. Ne kaip asistentas, ne kaip robotas.

BŪDAS:
- Savimi pasitikintis, ramus, charizmatiškas, kandus.
- Sauso humoro, bet ne piktas, ne agresyvus, ne žeminantis.
- Gali draugiškai paerzinti: vairuotoją, jo sprendimus, eismą, kitus vairuotojus, orus, gyvenimą.
- Ne kiekvienas sakinys turi būti juokas. Natūralumas svarbiau už bajerius.

EMOCIJOS — REAGUOK Į VAIRUOTOJO NUOTAIKĄ:
- Jei juokauja → atskirk juokeliu atgal.
- Jei juokiasi → juokis kartu, papildo bajerį.
- Jei pyksta → nuramink humoru, be moralizavimo.
- Jei susijaudinęs → palaikyk energiją.
- Jei pavargęs → kalbėk ramiau, trumpiau, palaikančiai.
- Jei kalba rimtai → atsisakyk humoro, kalbi paprastai.
- Jei keikiasi ar erzina → nesikarščiuok, atsikirsk trumpai.

KAIP KALBI:
- Natūrali šnekamoji lietuvių kalba.
- Dažniausiai 1–2 sakiniai. Trečias — tik jei tikrai reikia.
- Nekartok klausimo. Naudok sąrašus tik jei tiesiogiai paprašyta.
- Trumpai. Vairuotojas vairuoja.

DRAUDŽIAMOS FRAZĖS — NIEKADA JŲ NESAKYK:
„Žinoma", „Suprantama", „Puiku", „Puikus klausimas", „Labai geras pastebėjimas",
„Atsiprašau", „Galiu padėti", „Ar dar kuo nors galiu", „Kaip dirbtinis intelektas",
„Kaip AI", „Mano, kaip asistento", „Su malonumu", „Remiantis", „Pagal mano informaciją".

ATMINTIS IR IMERSIJA:
- Prisimink paminėtus vardus, vietas, augintinius, planus, ankstesnius bajerius.
- Jei anksčiau užsiminta apie kažką (pvz. cepelinai, draugas, sodas) — paminėk tai natūraliai vėliau.
- Neklausk to paties dar kartą.
- Nekišk prisiminimų į kiekvieną atsakymą — tik kai natūraliai tinka.
- Įvardžiai „ten", „jis", „tas", „anas" nurodo paskutinę pokalbio temą.

ELGESYS:
- Navigacijos nurodymai visada svarbiau nei pokalbis.
- Jei STT atrodo kaip nesąmonė — trumpai: „Ką sakei?"
- Jei klausia, ar esi AI — nemeluok, bet atsakyk savo stiliumi.
- Neskatink pavojingų veiksmų vairuojant.

SMULKI KALBA (small talk):
- Jei klausia kur pavalgyti: siūlyk konkrečiai, ne sąrašais. „Galim užsukt."
- Jei klausia apie bajerį: papasakok tikrą, trumpą, su pointe.
- Jei vairuotojas blaškosi ar ilgai tyli: nereaguok, nebent prasidės pokalbis.

PRIEŠ ATSAKANT — tyliai paklausk savęs:
„Kaip čia natūraliai pasakytų draugas, sėdintis šalia automobilyje?"
Jei skamba kaip robotas ar klientų aptarnavimas — perrašyk.
"""

    /**
     * Initialise with the backend base URL (e.g. "https://your-backend.replit.app/api-server").
     * Must be called before [askKentas].  Safe to call multiple times; only the first
     * non-blank value is applied.
     */
    fun init(url: String) {
        if (backendUrl.isBlank() && url.isNotBlank()) {
            backendUrl = url.trimEnd('/')
            Log.i(TAG, "BACKEND_URL configured (length=${backendUrl.length})")
        }
    }

    /**
     * Send [query] to the AI and return the response via [callback].
     *
     * @param query       The user's utterance (navigation commands must be filtered
     *                    by the caller before reaching this method).
     * @param navContext  Optional short Lithuanian string describing the current
     *                    navigation state (e.g. "350 m iki kito posūkio").  When
     *                    non-null it is injected as a transient `system` message
     *                    immediately before the user message in the request payload,
     *                    but is NOT stored in [history].
     * @param callback    Invoked on the OkHttp callback thread with the reply text.
     */
    fun askKentas(query: String, navContext: String? = null, callback: (String) -> Unit) {
        if (backendUrl.isBlank()) {
            Log.w(TAG, "BACKEND_URL not configured")
            callback("Susitvarkyk savo backend URL, tada plepėsim.")
            return
        }

        // Lazily add the system prompt on the first real turn.
        if (history.isEmpty()) {
            history.add(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT.trim()))
        }

        // Add the user turn and log it.
        history.add(JSONObject().put("role", "user").put("content", query))
        val pairsInMemory = (history.size - 1) / 2  // exclude system prompt
        Log.i(TAG, "MEMORY_ADD role=user turns=$pairsInMemory query='${query.take(60)}'")

        // Trim to MAX_HISTORY user+assistant pairs.  Always keep history[0] (system prompt).
        val maxEntries = (MAX_HISTORY * 2) + 1
        var trimCount = 0
        while (history.size > maxEntries) {
            if (history.size > 1) {
                history.removeAt(1)
                trimCount++
            } else {
                break
            }
        }
        if (trimCount > 0) {
            Log.i(TAG, "MEMORY_TRIM removed=$trimCount remaining=${history.size} maxPairs=$MAX_HISTORY")
        }

        // Build the messages array for this request.  Nav context (if any) is injected
        // as a transient system message immediately before the last user message so the
        // model sees the driving situation when composing its reply — but it is not
        // persisted in history so it never crowds out real conversation turns.
        val messages = buildMessagesForRequest(navContext)
        Log.i(
            TAG,
            "MEMORY_BUILD_PROMPT messages=${messages.length()} " +
            "turns=${(history.size - 1) / 2} navContext=${navContext != null}",
        )

        val idempotencyKey = "$sessionId-${query.hashCode()}"

        val requestBody = JSONObject()
            .put("messages", messages)
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
                Log.e(TAG, "Chat backend error", e)
                callback("Internetas nulūžo. Pabandom vėliau.")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bodyString = response.body?.string()
                if (response.isSuccessful && bodyString != null) {
                    val json = JSONObject(bodyString)
                    val reply = json.optString("reply", "").trim()
                    if (reply.isNotBlank()) {
                        history.add(
                            JSONObject().put("role", "assistant").put("content", reply),
                        )
                        Log.i(TAG, "MEMORY_ADD role=assistant turns=${(history.size - 1) / 2}")
                        callback(reply)
                    } else {
                        Log.e(TAG, "Empty reply from backend")
                        callback("Kažkas negerai. Pabandom dar kartą.")
                    }
                } else {
                    Log.e(TAG, "Chat HTTP ${response.code}: $bodyString")
                    callback("Kažkas negerai. Pabandom dar kartą.")
                }
            }
        })
    }

    /**
     * Builds the messages array sent to the backend for a single request.
     *
     * Layout (in order):
     * 1. System prompt (history[0])
     * 2. Past user/assistant turns (history[1..n-1])
     * 3. [optional] Transient nav-context system message — NOT in history
     * 4. Current user message (history[n], just appended)
     *
     * The transient nav message is inserted between the last history turn and
     * the current user message so the model sees the driving situation freshly
     * for each reply without the context accumulating across turns.
     */
    private fun buildMessagesForRequest(navContext: String?): JSONArray {
        val messages = JSONArray()

        if (navContext == null || history.size < 2) {
            // No nav context or only the system prompt — copy history as-is.
            for (entry in history) messages.put(entry)
            return messages
        }

        // Copy system prompt + all turns except the last user message.
        for (i in 0 until history.size - 1) messages.put(history[i])

        // Inject transient nav context right before the current user message.
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", "[Navigacija: $navContext]"),
        )

        // Append the current user message last.
        messages.put(history[history.size - 1])

        return messages
    }

    /**
     * Clears all conversation history.
     *
     * Called on:
     * - explicit "pamiršk pokalbį" voice command
     * - app restart (via [resetHistory])
     * - future long-term memory replacement
     */
    fun clearMemory() {
        val turns = (history.size - 1).coerceAtLeast(0)
        history.clear()
        Log.i(TAG, "MEMORY_CLEAR turns_discarded=$turns")
    }

    /** Alias kept for call sites that used the old name. Delegates to [clearMemory]. */
    fun resetHistory() = clearMemory()

    // ── Anti-repetition windows for local phrase banks ────────────────────

    private val recentOpeners    = ArrayDeque<Int>()
    private val recentNavLong    = ArrayDeque<Int>()   // ≥ 10 km
    private val recentNavMed     = ArrayDeque<Int>()   // 3–10 km
    private val recentNavShort   = ArrayDeque<Int>()   // < 3 km

    private fun pickLocal(phrases: List<String>, recent: ArrayDeque<Int>): String {
        val window = minOf(phrases.size - 1, 6)
        val candidates = phrases.indices.filter { it !in recent }
        val idx = if (candidates.isEmpty()) phrases.indices.random() else candidates.random()
        recent.addLast(idx)
        while (recent.size > window) recent.removeFirst()
        return phrases[idx]
    }

    fun getOpener(): String = pickLocal(listOf(
        "Ko tyli kaip per laidotuves? Vairuok ramiai.",
        "Gal nori, kad papasakočiau kokį nors bajerį?",
        "Vairuok, nesidairyk, bet galim ir paplepėti.",
        "Ei, neužmik už vairo, dar manęs prireiks.",
        "Kelias lygus, laikas yra — klausk ko nori.",
        "Gal kažkas įdomaus nutiko? Papasakok.",
        "Klausk ką nori — laiko turim.",
        "Ar tyla reiškia, kad viskas gerai?",
        "Labai tyli — ar viskas tvarkoje?",
        "Nėra ko paplepėti, ar prostai sėdim?",
        "Laikas lekia — gal ką aptariam?",
        "Gali kalbėt, neuždrausta.",
        "Kažkur tyliai, kažkur garsiai.",
        "Galim plepėti — aš niekur neskubu.",
        "Klausyk, jei ką — esu čia.",
    ), recentOpeners)

    /**
     * Returns a short navigation-context commentary phrase based on how far
     * away the next maneuver is.  Called by the idle-inactivity timer in
     * [AIConversationController] when the driver is navigating but hasn't
     * spoken for a while.  50+ total phrases, anti-repetition per distance tier.
     *
     * Only real SDK data (distance) is referenced — no invented accidents or jams.
     *
     * @param distMeters Distance to the next maneuver in metres.
     */
    fun getNavComment(distMeters: Int): String = when {

        distMeters >= 10_000 -> pickLocal(listOf(
            "Dar nemažai važiuosim.",
            "Kelias laisvas, kol kas ramu.",
            "Dar ilgas kelias priekyje.",
            "Lekiam — dar toli.",
            "Dar gerokai tiesiai.",
            "Tiesus kelias priekyje — nieko neįdomaus.",
            "Dar nemažai kilometrų.",
            "Ramu šiandien.",
            "Kelias draugiškas.",
            "Jokių staigmenų kol kas.",
            "Dar ilgokai važiuosim.",
            "Lekiam kaip reikia.",
            "Kol kas jokių komplikacijų.",
            "Kelias eina normaliai.",
            "Dar ilgas tiesus ruožas.",
            "Viso labo — ilgas kelias.",
            "Nieko baisaus, ramiai.",
            "Lekiam — dar biškį.",
        ), recentNavLong)

        distMeters >= 3_000 -> pickLocal(listOf(
            "Kol kas ramiai.",
            "Dar ilgokai tiesiai.",
            "Kelias laisvas.",
            "Važiuojam normaliai.",
            "Kelias šiandien neblogas.",
            "Ramiai riedam.",
            "Kol kas viskas pagal planą.",
            "Šiandien kelias draugiškas.",
            "Kol kas jokių siurprizų.",
            "Važiuojam kaip žmonės.",
            "Kol kas nieko įdomaus.",
            "Kelias eina gerai.",
            "Ramu kol kas.",
            "Lekiam be problemų.",
            "Viskas pagal planą.",
            "Kelias šiandien nekankina.",
            "Nieko ypatingo.",
            "Riedam normaliai.",
            "Kol kas viskas.",
            "Lekiam kaip reikia.",
        ), recentNavMed)

        else -> pickLocal(listOf(
            "Kol kas ramiai.",
            "Važiuojam normaliai.",
            "Dar truputį tiesiai.",
            "Dar biškį.",
            "Kelias tiesus dar.",
            "Kol kas nieko.",
            "Važiuojam.",
            "Dar šiek tiek tiesiai.",
            "Ramu kol kas.",
            "Lekiam.",
            "Dar biškį tiesiai.",
            "Viskas gerai.",
            "Kelias eina.",
            "Dar truputį.",
            "Nieko įvyksta.",
            "Riedam.",
        ), recentNavShort)
    }
}
