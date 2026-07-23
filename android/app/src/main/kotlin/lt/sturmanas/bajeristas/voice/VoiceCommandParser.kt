package lt.sturmanas.bajeristas.voice

import android.util.Log

// ── VoiceCommand sealed class ──────────────────────────────────────────────────

/**
 * Discriminated union representing every voice command Kentas understands.
 *
 * [VoiceCommandParser.parse] maps raw Lithuanian speech-to-text output onto one
 * of these variants using deterministic local keyword matching — no network call.
 *
 * Only [GeneralQuestion] and [Unknown] are forwarded to OpenAI; all others are
 * handled locally in MainViewModel without leaving the device.
 */
sealed class VoiceCommand {
    /** "Kiek liko važiuoti?" / "Kiek kilometrų?" / "Dar toli?" */
    object RemainingDistance : VoiceCommand()

    /** "Kada atvyksime?" / "Kiek laiko liko?" */
    object RemainingTime : VoiceCommand()

    /** "Kur važiuojame?" / "Koks yra mūsų tikslas?" */
    object DestinationInfo : VoiceCommand()

    /** "Pakartok nurodymą." / "Ką sakei?" */
    object RepeatInstruction : VoiceCommand()

    /** "Nutildyk balsą." / "Nekalbėk." */
    object MuteVoice : VoiceCommand()

    /** "Įjunk balsą." / "Kalbėk." */
    object UnmuteVoice : VoiceCommand()

    /** "Sustabdyk navigaciją." / "Atšauk maršrutą." */
    object StopNavigation : VoiceCommand()

    /**
     * "Važiuojam į Taikos prospektą 61, Klaipėda."
     * [destination] is extracted verbatim from the original input after the
     * trigger prefix — Lithuanian characters and capitalisation are preserved.
     *
     * The destination string is passed to [DestinationResolver] for normalisation
     * before being forwarded to [NavigationController.startNavigation].
     */
    data class StartNavigation(val destination: String) : VoiceCommand()

    /**
     * User selected a candidate from an active clarification dialog.
     * [index] is 1-based (1 = first option, 2 = second, 3 = third).
     *
     * Triggered by: "pirmą", "antrą", "trečią" (and no-diacritic variants).
     */
    data class SelectCandidate(val index: Int) : VoiceCommand()

    /**
     * "Pakeliui užvažiuok į Lidl." / "Pridėk degalinę." / "Užsuk į kavinę."
     *
     * [place] is extracted verbatim after the waypoint prefix and is forwarded
     * to [DestinationResolver] then inserted as an intermediate stop via
     * [WaypointManager].
     *
     * Checked BEFORE [StartNavigation] so that "dar važiuojam į X" is
     * correctly classified as a waypoint addition, not a route replacement.
     */
    data class AddWaypoint(val place: String) : VoiceCommand()

    /**
     * "Išmesk paskutinį sustojimą." / "Pašalink paskutinį."
     *
     * Removes the last intermediate stop from [WaypointManager].
     * Checked BEFORE [StopNavigation] patterns so that "atšauk paskutinį"
     * is not mis-routed as a full navigation cancel.
     */
    object RemoveLastWaypoint : VoiceCommand()

    /**
     * "Pašalink visus sustojimus." / "Išvalyk sustojimus."
     *
     * Clears all intermediate stops while keeping the final destination.
     */
    object ClearWaypoints : VoiceCommand()

    /**
     * "Rodyk sustojimus." / "Kokie sustojimai?"
     *
     * Speaks the current waypoint list via TTS and shows it in the status text.
     * Checked BEFORE [StartNavigation] so that "rodyk sustojimus" is not
     * mis-parsed as StartNavigation("sustojimus").
     */
    object ListWaypoints : VoiceCommand()

    /**
     * "Tęsk maršrutą." / "Tęsk kelionę."
     *
     * Confirms the current route and re-speaks the next-stop name.
     */
    object ContinueRoute : VoiceCommand()

    /**
     * "Nustok klausyti." / "Išjunk mikrofoną." / "Baik klausytis."
     *
     * Stops the continuous voice session loop without stopping navigation.
     * Checked BEFORE [MuteVoice] patterns so that "išjunk mikrofoną" is not
     * mis-classified as a general mute command.
     */
    object StopListening : VoiceCommand()

    /**
     * Recognised speech that does not match any deterministic pattern.
     * Forwarded to OpenAI with full navigation context.
     */
    data class GeneralQuestion(val text: String) : VoiceCommand()

    /** Blank input or input that carries no parseable intent. */
    data class Unknown(val text: String) : VoiceCommand()
}

// ── Parser ────────────────────────────────────────────────────────────────────

/**
 * Pure deterministic parser. Thread-safe; holds no mutable state.
 *
 * ## Normalisation pipeline (applied before all pattern matching)
 * 1. Trim leading/trailing whitespace.
 * 2. Lowercase the whole string.
 * 3. Replace punctuation (. , ! ? ; :) with a space.
 * 4. Collapse consecutive whitespace to a single space.
 *
 * Lithuanian characters (ą č ę ė į š ų ū ž) are preserved as-is; the
 * patterns in this file also include no-diacritic alternatives for
 * SpeechRecognizer accuracy on devices without a Lithuanian model.
 *
 * ## Matching order (most-specific first — do NOT reorder)
 * 1. [VoiceCommand.AddWaypoint]       — waypoint prefix regex (anchored `^`).
 * 2. [VoiceCommand.RemoveLastWaypoint] / [ClearWaypoints] / [ListWaypoints] /
 *    [ContinueRoute]                  — waypoint management keyword patterns.
 * 3. [VoiceCommand.StartNavigation]   — destination prefix regex (anchored `^`).
 * 4. [VoiceCommand.SelectCandidate]   — ordinal words for disambiguation.
 * 5. Remaining distance / time / destination / repeat / mute / unmute / stop.
 * 6. Blank → [VoiceCommand.Unknown].
 * 7. Everything else → [VoiceCommand.GeneralQuestion].
 */
object VoiceCommandParser {

    private const val TAG = "KentasVoice"

    // ── AddWaypoint prefixes ───────────────────────────────────────────────
    // Checked BEFORE NAV_PREFIX_REGEX. More-specific alternatives first.
    // "po\s+\S+\s+važiuojam\s+į" handles "po degalinės važiuojam į X".
    private val WAYPOINT_ADD_REGEX = Regex(
        """^(pakeliui\s+užvažiuok\s+į|pakeliui\s+uzvazuok\s+i|pakeliui\s+važiuok\s+į|""" +
        """pakeliui\s+vaziuok\s+i|užsuk\s+į|uzsuk\s+i|po\s+to\s+važiuojam\s+į|""" +
        """po\s+to\s+vaziuojam\s+i|po\s+\S+\s+važiuojam\s+į|po\s+\S+\s+vaziuojam\s+i|""" +
        """dar\s+važiuojam\s+į|dar\s+vaziuojam\s+i|pridėk\s+|pridek\s+|""" +
        """pakeliui\s+į\s+|pakeliui\s+i\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    // ── StartNavigation prefixes ───────────────────────────────────────────
    // More-specific alternatives MUST come before their shorter substrings in
    // the alternation so the regex engine picks the right one.
    // e.g. "rask\s+kelią\s+į" must precede "rask\s+".
    private val NAV_PREFIX_REGEX = Regex(
        """^(rask\s+kelią\s+į|rodyk\s+kelią\s+į|važiuojam\s+į|važiuojame\s+į|naviguok\s+į|naviguokime\s+į|maršrutas\s+į|keliaujam\s+į|keliaujame\s+į|eik\s+į|vyk\s+į|nuvežk\s+į|nuvešk\s+į|vežk\s+į|rask\s+|rodyk\s+|artimiausia\s+|artimiausias\s+|artimiausią\s+|važiuojam\s+|į\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    // ── SelectCandidate ordinals ───────────────────────────────────────────
    // Matched on the normalised string (exact equality or starts-with).
    // 1-based: index 1 = first candidate, etc.
    private val ORDINAL_MAP = mapOf(
        "pirmą"   to 1, "pirma"   to 1, "pirmas"   to 1,
        "vieną"   to 1, "vienas"  to 1,
        "antrą"   to 2, "antra"   to 2, "antras"   to 2,
        "du"      to 2,
        "trečią"  to 3, "trecia"  to 3, "trecias"  to 3,
        "trys"    to 3,
    )

    // ── Pattern tables (all lowercase with Lithuanian chars) ───────────────

    private val DISTANCE_PATTERNS = listOf(
        "kiek liko",
        "kiek kilometrų liko",
        "kiek km liko",
        "koks likęs atstumas",
        "koks likes atstumas",
        "dar toli",
        "kiek dar važiuoti",
        "kiek dar vaziuoti",
        "kiek liko važiuoti",
        "kiek liko vaziuoti",
        "kiek liko kilometrų",
        "kiek liko kilometru",
        "atstumas",
    )

    private val TIME_PATTERNS = listOf(
        "kiek laiko liko",
        "kada atvyksime",
        "kada atviksime",
        "kada būsim",
        "kada busim",
        "koks atvykimo laikas",
        "koks atlikimo laikas",
        "kada atvyksim",
        "kada atviksim",
        "kiek laiko dar",
        "kiek minučių liko",
        "kiek minuciu liko",
        "laikas",
    )

    private val DESTINATION_PATTERNS = listOf(
        "kur važiuojame",
        "kur vaziuojame",
        "kur važiuojam",
        "kur vaziuojam",
        "koks yra tikslas",
        "koks tikslas",
        "kur einame",
        "kur einam",
        "kur keliaujame",
        "kur keliaujam",
        "koks mūsų tikslas",
        "koks musu tikslas",
    )

    private val REPEAT_PATTERNS = listOf(
        "pakartok nurodymą",
        "pakartok nurodyma",
        "pakartok",
        "ką sakei",
        "ka sakei",
        "dar kartą",
        "dar karta",
        "pakartokit",
        "kartok",
        "ką turėjau daryti",
        "ka turėjau daryti",
    )

    private val MUTE_PATTERNS = listOf(
        "nutildyk balsą",
        "nutildyk balsa",
        "nutildyk",
        "nekalbėk",
        "nekalbek",
        "išjunk balsą",
        "isjunk balsa",
        "išjunk",
        "isjunk",
        "tylėk",
        "tylek",
        "tyla",
        "tildyk",
        "negarsiai",
    )

    private val UNMUTE_PATTERNS = listOf(
        "įjunk balsą",
        "ijunk balsa",
        "įjunk",
        "ijunk",
        "kalbėk",
        "kalbek",
        "garsą įjunk",
        "garsa ijunk",
        "garsas",
        "įjunk garsą",
        "ijunk garsa",
    )

    private val STOP_PATTERNS = listOf(
        "sustabdyk navigaciją",
        "sustabdyk navigacija",
        "atšauk maršrutą",
        "atsauk marsruta",
        "baik navigaciją",
        "baik navigacija",
        "nebevažiuojam",
        "nebevaziuojam",
        "sustabdyk",
        "atšauk",
        "atsauk",
        "baigiam",
        "baigiame",
        "išjunk navigaciją",
        "isjunk navigacija",
        "stop navigacija",
        "stop navigaciją",
    )

    // ── StopListening patterns ─────────────────────────────────────────────
    // Checked BEFORE MUTE_PATTERNS so "išjunk mikrofoną" → StopListening,
    // not MuteVoice.
    private val STOP_LISTENING_PATTERNS = listOf(
        "nustok klausyti",
        "nustok klausyti",
        "baik klausytis",
        "baik klausyti",
        "išjunk mikrofoną",
        "isjunk mikrofona",
        "išjunk sessiją",
        "isjunk sesija",
        "sustabdyk klausymą",
        "sustabdyk klausyma",
    )

    // ── Casual / personality patterns ─────────────────────────────────────
    // These phrases express entertainment, emotion, or personal conversation.
    // They are checked in parse() right before looksLikeDestination so that
    // phrases without a clear navigation/address signal reach GeneralQuestion
    // (→ AI personality) rather than being mis-routed to DestinationResolver.
    //
    // Pattern philosophy: err on the side of personality — if a phrase cannot
    // be a place name or navigation command, forward it to Kentas.

    internal val CASUAL_PATTERNS = listOf(
        // Entertainment requests
        "pralinksmink",     // "cheer me up"
        "palinksmin",       // "entertain me"
        "linksmink",
        "prajuokink",
        "juokink",
        "anekdot",          // "tell me a joke"
        "juoką",
        "juokų",
        "juokis",
        "bažer",            // "bajeris" = story/joke (colloquial)
        "bajerį",
        "bajer",
        "istoriją",         // "tell me a story"
        // Personal questions about Kentas
        "ką veiki",         // "what are you doing"
        "ka veiki",
        "kaip gyveni",      // "how are you living"
        "kaip laikais",     // "how are you doing"
        "kaip sekasi",      // "how's it going"
        "kas tu",           // "who are you"
        "kas esi",
        "apie save",        // "tell me about yourself"
        "apie kentas",
        "kokia nuotaika",   // "what's your mood"
        "ką myli",          // "what do you love"
        "ka myli",
        "ką mėgsti",        // "what do you like"
        "ka megsti",
        // Greetings / well-being that miss CONVERSATIONAL_PATTERNS
        "sveikas",          // casual greeting (singular)
        "labas rytas",
        "laba diena",
        "labas vakaras",
        "labos",
    )

    // ── Destination classifier lists ───────────────────────────────────────
    // Used by looksLikeDestination() to distinguish plain place names from
    // conversational questions before routing to GeneralQuestion / OpenAI.

    private val DEST_CLASSIFIER_BRANDS = listOf(
        "lidl", "maxima", "iki", "rimi", "norfa", "norfos", "akropolis", "akropolį",
        "mcdonald", "burger king", "kfc", "pizza", "hesburger", "circle k",
        "lukoil", "viada", "neste", "statoil", "virvi",
    )

    private val DEST_CLASSIFIER_CATEGORIES = listOf(
        "degalinė", "degaline", "vaistinė", "vaistine",
        "parduotuvė", "parduotuve", "kavinė", "kavine",
        "restoranas", "viešbutis", "viesbutis",
        "paštas", "pastas", "ligoninė", "ligonine",
        "mokykla", "universitetas", "biblioteka",
        "autobusų stotis", "autobusu stotis",
        "traukinių stotis", "traukiniu stotis",
        "oro uostas",
    )

    private val CONVERSATIONAL_PATTERNS = listOf(
        "ar ", "kas ", "kodėl", "kodel", "kaip ", "norėčiau", "norečiau",
        "papasakok", "pasakyk man", "informuok",
        "labas", "sveiki", "ačiū", "aciu", "prašau", "prasau",
        "taip", "ne ", "gerai", "puiku", "tikrai", "žinoma", "zinoma",
        "gal ", "turbūt", "turbut",
    )

    // ── Waypoint management patterns ───────────────────────────────────────
    // Checked BEFORE STOP_PATTERNS so "atšauk paskutinį" → RemoveLastWaypoint,
    // not StopNavigation. Checked BEFORE NAV_PREFIX_REGEX so "rodyk sustojimus"
    // → ListWaypoints, not StartNavigation("sustojimus").

    private val REMOVE_WAYPOINT_PATTERNS = listOf(
        "išmesk paskutinį sustojimą",
        "ismesk paskutini sustojima",
        "išmesk paskutinį",
        "ismesk paskutini",
        "pašalink paskutinį sustojimą",
        "pasalink paskutini sustojima",
        "pašalink paskutinį",
        "pasalink paskutini",
        "atšauk paskutinį sustojimą",
        "atsauk paskutini sustojima",
        "atšauk paskutinį",
        "atsauk paskutini",
        "ištrink paskutinį sustojimą",
        "istrink paskutini sustojima",
        "ištrink paskutinį",
        "istrink paskutini",
    )

    private val CLEAR_WAYPOINTS_PATTERNS = listOf(
        "pašalink visus sustojimus",
        "pasalink visus sustojimus",
        "išvalyk sustojimus",
        "isvalyk sustojimus",
        "pašalink sustojimus",
        "pasalink sustojimus",
        "išmesk visus sustojimus",
        "ismesk visus sustojimus",
        "ištrink visus sustojimus",
        "istrink visus sustojimus",
    )

    private val LIST_WAYPOINTS_PATTERNS = listOf(
        "rodyk sustojimus",
        "parodyk sustojimus",
        "kokie sustojimai",
        "kokie yra sustojimai",
        "kiek sustojimų",
        "kiek sustojimu",
        "sustojimų sąrašas",
        "sustojimu sarasas",
        "kokie sustojimų",
        "kokie sustojimu",
    )

    private val CONTINUE_ROUTE_PATTERNS = listOf(
        "tęsk maršrutą",
        "tesk marsruta",
        "tęsk kelionę",
        "tesk kelione",
        "tęsk navigaciją",
        "tesk navigacija",
        "tęsk",
        "tesk",
        "tęsiam",
        "tesiam",
        "tęsiame maršrutą",
        "tesiame marsruta",
        "maršrutą tęsk",
        "marsruta tesk",
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Parse [input] (raw Lithuanian speech) and return the best matching [VoiceCommand].
     *
     * Never throws — blank or unrecognised input returns [VoiceCommand.Unknown] or
     * [VoiceCommand.GeneralQuestion] respectively.
     */
    fun parse(input: String): VoiceCommand {
        if (input.isBlank()) {
            Log.d(TAG, "parse: blank input → Unknown")
            return VoiceCommand.Unknown(input)
        }

        val trimmed = input.trim()
        val normalized = normalize(trimmed)
        Log.d(TAG, "parse: raw='$trimmed' normalized='$normalized'")

        // ── 1. AddWaypoint — checked first (more specific than StartNavigation) ──
        val waypointMatch = WAYPOINT_ADD_REGEX.find(trimmed)
        if (waypointMatch != null) {
            val place = trimmed.substring(waypointMatch.value.length).trim().trimEnd('.', ',', '!', '?')
            if (place.isNotBlank()) {
                Log.d(TAG, "parse: AddWaypoint place='$place'")
                return VoiceCommand.AddWaypoint(place)
            }
        }

        // ── 2. Waypoint management — checked before NAV_PREFIX_REGEX and STOP_PATTERNS ──
        if (matchesAny(normalized, REMOVE_WAYPOINT_PATTERNS)) {
            Log.d(TAG, "parse: RemoveLastWaypoint")
            return VoiceCommand.RemoveLastWaypoint
        }
        if (matchesAny(normalized, CLEAR_WAYPOINTS_PATTERNS)) {
            Log.d(TAG, "parse: ClearWaypoints")
            return VoiceCommand.ClearWaypoints
        }
        if (matchesAny(normalized, LIST_WAYPOINTS_PATTERNS)) {
            Log.d(TAG, "parse: ListWaypoints")
            return VoiceCommand.ListWaypoints
        }
        if (matchesAny(normalized, CONTINUE_ROUTE_PATTERNS)) {
            Log.d(TAG, "parse: ContinueRoute")
            return VoiceCommand.ContinueRoute
        }

        // ── 3. StartNavigation — checked after waypoint prefixes (anchored prefix regex) ──
        val navMatch = NAV_PREFIX_REGEX.find(trimmed)
        if (navMatch != null) {
            val dest = trimmed.substring(navMatch.value.length).trim().trimEnd('.', ',', '!', '?')
            if (dest.isNotBlank()) {
                Log.d(TAG, "parse: StartNavigation dest='$dest'")
                return VoiceCommand.StartNavigation(dest)
            }
        }

        // ── 4. SelectCandidate ordinals ────────────────────────────────────
        // Exact match or starts-with (e.g. "pirmą variantą" → index 1).
        val ordinalIndex = ORDINAL_MAP.entries.firstOrNull { (k, _) ->
            normalized == k || normalized.startsWith("$k ")
        }?.value
        if (ordinalIndex != null) {
            Log.d(TAG, "parse: SelectCandidate($ordinalIndex)")
            return VoiceCommand.SelectCandidate(ordinalIndex)
        }

        // ── 5. Deterministic keyword patterns ─────────────────────────────
        if (matchesAny(normalized, DISTANCE_PATTERNS))    return VoiceCommand.RemainingDistance
        if (matchesAny(normalized, TIME_PATTERNS))        return VoiceCommand.RemainingTime
        if (matchesAny(normalized, DESTINATION_PATTERNS)) return VoiceCommand.DestinationInfo
        if (matchesAny(normalized, REPEAT_PATTERNS))      return VoiceCommand.RepeatInstruction
        // StopListening checked BEFORE MuteVoice — "išjunk mikrofoną" must not fire MuteVoice.
        if (matchesAny(normalized, STOP_LISTENING_PATTERNS)) {
            Log.d(TAG, "parse: StopListening")
            return VoiceCommand.StopListening
        }
        if (matchesAny(normalized, MUTE_PATTERNS))        return VoiceCommand.MuteVoice
        if (matchesAny(normalized, UNMUTE_PATTERNS))      return VoiceCommand.UnmuteVoice
        if (matchesAny(normalized, STOP_PATTERNS))        return VoiceCommand.StopNavigation

        // ── 6. Casual / personality request — before destination classifier ──
        // Phrases with entertainment or personal intent reach Kentas's AI
        // personality regardless of whether navigation is active.
        // CASUAL_PATTERNS is checked here (after all navigation patterns) so
        // it never shadows a real navigation command.
        if (matchesAny(normalized, CASUAL_PATTERNS)) {
            Log.d(TAG, "parse: casual request → GeneralQuestion('$trimmed')")
            return VoiceCommand.GeneralQuestion(trimmed)
        }

        // ── 7. Destination classifier — before OpenAI fallthrough ──────────
        // Plain inputs like "Akropolis", "Taikos 61", "Lidl", "degalinė" are
        // routed to DestinationResolver as StartNavigation instead of OpenAI.
        if (looksLikeDestination(trimmed, normalized)) {
            Log.d("KentasVoiceFlow", "parse: looksLikeDestination → StartNavigation('$trimmed')")
            return VoiceCommand.StartNavigation(trimmed)
        }

        // ── 8. Fallthrough — forward to OpenAI ────────────────────────────
        Log.d(TAG, "parse: no pattern matched → GeneralQuestion")
        return VoiceCommand.GeneralQuestion(trimmed)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * True if [text] contains or equals any of the [patterns].
     * Matching is substring-based so partial phrases also fire.
     */
    internal fun matchesAny(text: String, patterns: List<String>): Boolean =
        patterns.any { text.contains(it) }

    /**
     * Normalise for pattern matching:
     * lowercase → replace punctuation with space → collapse whitespace.
     * Lithuanian characters (ą č ę ė į š ų ū ž) are preserved.
     */
    internal fun normalize(input: String): String =
        input
            .lowercase()
            .replace(Regex("[.,!?;:]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Heuristic classifier: returns true when [trimmed] looks like a destination
     * name that [DestinationResolver] should handle rather than OpenAI.
     *
     * Called only after all deterministic navigation patterns AND [CASUAL_PATTERNS]
     * have been checked (steps 1–6), so known commands and personality requests
     * can never be mis-routed here.
     *
     * ## Logic (checked in order, first match wins)
     * 1. Contains a digit → street address ("Taikos 61", "Gedimino pr. 3").
     * 2. Starts with a known brand keyword → brand POI ("Lidl", "Maxima").
     * 3. Contains a known category word → category POI ("degalinė", "kavinė").
     * 4. Matches a conversational pattern → NOT a destination (return false).
     * 5a. Single word that starts with a lowercase letter → imperative verb or
     *     casual utterance, NOT a destination ("pralinksmink", "ką", …).
     *     Single words that start with an uppercase letter are treated as proper
     *     nouns / place names ("Akropolis", "Lazdynai").
     * 5b. 2–3 words, no conversational signals → generic place name or POI.
     *
     * @param trimmed   The original trimmed input (preserves Lithuanian case).
     * @param normalized The lowercased, punctuation-stripped version for matching.
     */
    internal fun looksLikeDestination(trimmed: String, normalized: String): Boolean {
        // 1. Digit → street address
        if (trimmed.any { it.isDigit() }) return true

        // 2. Known brand keyword
        val normFirst = normalized.substringBefore(" ")
        if (DEST_CLASSIFIER_BRANDS.any { normalized.startsWith(it) || normFirst == it }) return true

        // 3. Known category word
        if (DEST_CLASSIFIER_CATEGORIES.any { normalized.contains(it) }) return true

        // 4. Conversational pattern → definitely not a destination
        if (CONVERSATIONAL_PATTERNS.any { normalized.contains(it) }) return false

        // 5. Short input with no conversational signals.
        val words = normalized.trim().split(Regex("\\s+"))
        return when (words.size) {
            // 5a. Single word: only treat as a destination if it starts with an
            //     uppercase letter in the original speech (proper noun / place name).
            //     Lowercase single words are typically imperative verbs or greetings
            //     ("pralinksmink", "linksmink") and belong in GeneralQuestion.
            1 -> trimmed.firstOrNull()?.isUpperCase() == true
            // 5b. 2–3 words: broad heuristic — if no conversational signal was
            //     detected in step 4, treat as a place name or POI query.
            in 2..3 -> true
            else -> false
        }
    }
}
