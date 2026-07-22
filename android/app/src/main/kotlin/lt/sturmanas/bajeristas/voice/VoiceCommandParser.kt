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
 * 1. [VoiceCommand.StartNavigation] — destination prefix regex (anchored `^`).
 * 2. [VoiceCommand.SelectCandidate] — ordinal words for disambiguation.
 * 3. Remaining distance / time / destination / repeat / mute / unmute / stop.
 * 4. Blank → [VoiceCommand.Unknown].
 * 5. Everything else → [VoiceCommand.GeneralQuestion].
 */
object VoiceCommandParser {

    private const val TAG = "KentasVoice"

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

        // ── 1. StartNavigation — checked first (anchored prefix regex) ─────
        val navMatch = NAV_PREFIX_REGEX.find(trimmed)
        if (navMatch != null) {
            val dest = trimmed.substring(navMatch.value.length).trim().trimEnd('.', ',', '!', '?')
            if (dest.isNotBlank()) {
                Log.d(TAG, "parse: StartNavigation dest='$dest'")
                return VoiceCommand.StartNavigation(dest)
            }
        }

        // ── 2. SelectCandidate ordinals ────────────────────────────────────
        // Exact match or starts-with (e.g. "pirmą variantą" → index 1).
        val ordinalIndex = ORDINAL_MAP.entries.firstOrNull { (k, _) ->
            normalized == k || normalized.startsWith("$k ")
        }?.value
        if (ordinalIndex != null) {
            Log.d(TAG, "parse: SelectCandidate($ordinalIndex)")
            return VoiceCommand.SelectCandidate(ordinalIndex)
        }

        // ── 3. Deterministic keyword patterns ─────────────────────────────
        if (matchesAny(normalized, DISTANCE_PATTERNS))    return VoiceCommand.RemainingDistance
        if (matchesAny(normalized, TIME_PATTERNS))        return VoiceCommand.RemainingTime
        if (matchesAny(normalized, DESTINATION_PATTERNS)) return VoiceCommand.DestinationInfo
        if (matchesAny(normalized, REPEAT_PATTERNS))      return VoiceCommand.RepeatInstruction
        if (matchesAny(normalized, MUTE_PATTERNS))        return VoiceCommand.MuteVoice
        if (matchesAny(normalized, UNMUTE_PATTERNS))      return VoiceCommand.UnmuteVoice
        if (matchesAny(normalized, STOP_PATTERNS))        return VoiceCommand.StopNavigation

        // ── 4. Fallthrough — forward to OpenAI ────────────────────────────
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
}
