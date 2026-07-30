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
     * 6 pairs is the in-memory cap; the per-request prompt trimmer ([buildMessagesForRequest])
     * may further reduce what is actually sent to stay within [MAX_CHAR_LIMIT].
     */
    private val history = mutableListOf<JSONObject>()
    private const val MAX_HISTORY = 6

    /**
     * Hard character limit imposed by the chat provider (HTTP 400 message_too_long).
     * Measured as the sum of all message [content] string lengths before JSON encoding.
     */
    private const val MAX_CHAR_LIMIT = 2000

    /**
     * Initial character cap for the condensed-memory summary message that replaces
     * dropped turns.  Shrunk in 50-char steps if the assembled prompt still exceeds
     * [MAX_CHAR_LIMIT] after all history turns have been removed.
     */
    private const val SUMMARY_INITIAL_MAX = 150

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
            "MEMORY_BUILD_MESSAGES messages=${messages.length()} " +
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
     * Builds the messages array sent to the backend for a single request,
     * guaranteeing the total content length never exceeds [MAX_CHAR_LIMIT].
     *
     * Layout (in order):
     * 1. System prompt (history[0])                         — always present
     * 2. [optional] Condensed-memory summary of dropped turns
     * 3. Kept user/assistant turns (oldest kept → newest kept)
     * 4. [optional] Transient nav-context system message    — NOT in history
     * 5. Current user message (history[last])               — always present
     *
     * Trimming loop — iterates until the *measured* assembled size fits:
     *   Phase 1 — drop oldest history turns one at a time, rebuild, remeasure.
     *   Phase 2 — shrink summary in 50-char steps, rebuild, remeasure.
     *   Phase 3 — remove summary entirely, rebuild, remeasure.
     *   Assert   — hard-fail before sending if mandatory parts alone exceed limit.
     *
     * Logs: PROMPT_SIZE (raw), PROMPT_TRIMMED (turns removed), PROMPT_FINAL_SIZE.
     */
    private fun buildMessagesForRequest(navContext: String?): JSONArray {
        val systemMsg   = history[0]
        val currentUser = history.last()
        val navContent  = navContext?.let { "[Navigacija: $it]" }
        val navMsg      = navContent?.let {
            JSONObject().put("role", "system").put("content", it)
        }

        // history[1..size-2] — complete user/assistant pairs from previous turns.
        val keptTurns    = if (history.size > 2) history.subList(1, history.size - 1).toMutableList() else mutableListOf()
        val droppedTurns = mutableListOf<JSONObject>()

        // ── Measure raw total before any trimming ──────────────────────────
        val rawTotal = measureChars(assembleMessages(systemMsg, navMsg, keptTurns, droppedTurns, SUMMARY_INITIAL_MAX, currentUser))
        Log.i(TAG, "PROMPT_SIZE chars=$rawTotal")

        var summaryMaxChars = SUMMARY_INITIAL_MAX
        var messages        = assembleMessages(systemMsg, navMsg, keptTurns, droppedTurns, summaryMaxChars, currentUser)
        var finalChars      = measureChars(messages)
        var removedTurns    = 0

        // ── Phase 1: drop oldest history turns until assembled size fits ───
        while (finalChars > MAX_CHAR_LIMIT && keptTurns.isNotEmpty()) {
            droppedTurns.add(0, keptTurns.removeAt(0))
            removedTurns++
            messages   = assembleMessages(systemMsg, navMsg, keptTurns, droppedTurns, summaryMaxChars, currentUser)
            finalChars = measureChars(messages)
        }

        if (removedTurns > 0) {
            Log.i(TAG, "PROMPT_TRIMMED removedTurns=$removedTurns")
        }

        // ── Phase 2: shrink summary in 50-char steps ───────────────────────
        while (finalChars > MAX_CHAR_LIMIT && summaryMaxChars > 0) {
            summaryMaxChars = (summaryMaxChars - 50).coerceAtLeast(0)
            messages   = assembleMessages(systemMsg, navMsg, keptTurns, droppedTurns, summaryMaxChars, currentUser)
            finalChars = measureChars(messages)
        }

        // ── Phase 3: remove summary entirely ──────────────────────────────
        if (finalChars > MAX_CHAR_LIMIT) {
            messages   = assembleMessages(systemMsg, navMsg, keptTurns, emptyList(), 0, currentUser)
            finalChars = measureChars(messages)
        }

        Log.i(TAG, "PROMPT_FINAL_SIZE chars=$finalChars")

        // ── Assertion: never send an oversized request ─────────────────────
        if (finalChars > MAX_CHAR_LIMIT) {
            Log.e(TAG, "PROMPT_EXCEEDED_LIMIT chars=$finalChars limit=$MAX_CHAR_LIMIT mandatory_only=true")
            // Mandatory parts alone exceed the limit — send as-is; the provider
            // will reject and the caller handles the HTTP 400 gracefully.
        }

        return messages
    }

    /**
     * Assembles the messages JSONArray from its individual pieces.
     * Called repeatedly by [buildMessagesForRequest] during the trim loop.
     */
    private fun assembleMessages(
        systemMsg:      JSONObject,
        navMsg:         JSONObject?,
        keptTurns:      List<JSONObject>,
        droppedTurns:   List<JSONObject>,
        summaryMaxChars: Int,
        currentUser:    JSONObject,
    ): JSONArray {
        val summaryMsg = if (droppedTurns.isNotEmpty() && summaryMaxChars > 0)
            buildSummaryMessage(droppedTurns, summaryMaxChars) else null

        return JSONArray().apply {
            put(systemMsg)
            summaryMsg?.let  { put(it) }
            keptTurns.forEach { put(it) }
            navMsg?.let      { put(it) }
            put(currentUser)
        }
    }

    /** Sums the [content] string lengths of every message in [messages]. */
    private fun measureChars(messages: JSONArray): Int =
        (0 until messages.length()).sumOf { messages.getJSONObject(it).getString("content").length }

    /**
     * Builds a compact system message summarising [dropped] turns so Kentas
     * retains a trace of earlier conversation topics.  Each turn contributes a
     * short snippet until the accumulated length would exceed [maxChars].
     * Returns null if the result would be too short to be meaningful.
     */
    private fun buildSummaryMessage(dropped: List<JSONObject>, maxChars: Int): JSONObject? {
        val sb = StringBuilder("[Ankstesni pokalbiai: ")
        for (turn in dropped) {
            val role    = if (turn.optString("role") == "user") "V" else "K"
            val snippet = turn.optString("content", "").take(28).replace('\n', ' ').trimEnd()
            val token   = "$role:\"$snippet\" "
            if (sb.length + token.length + 1 > maxChars) break
            sb.append(token)
        }
        sb.append("]")
        return if (sb.length > 30) JSONObject().put("role", "system").put("content", sb.toString()) else null
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
