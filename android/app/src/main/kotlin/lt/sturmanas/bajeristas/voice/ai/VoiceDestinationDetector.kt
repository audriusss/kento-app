package lt.sturmanas.bajeristas.voice.ai

/**
 * Lightweight result type shared by all voice-destination search paths
 * (Autocomplete, Nearby Search, Text Search).
 *
 * Kept in this file so [VoiceDestinationDetector] and [AIConversationController]
 * can use it without any Android imports — fully testable on a plain JVM.
 */
data class VoiceDestinationChoice(
    val placeId: String,
    /** Primary display name (pharmacy name, address, POI title, etc.). */
    val name: String,
    /** Short address fragment (street + city, Lithuania/Lietuva suffix stripped). */
    val shortAddress: String,
)

/**
 * Detects navigation/destination intent in voice transcripts, extracts the raw
 * destination query, and classifies it as a generic category, a known chain name,
 * or a free-text query for Places Autocomplete.
 *
 * ## Separation of concerns
 * - All functions that take *normText* expect text pre-processed by
 *   `AIConversationController.normalizeText()`: lowercase, diacritics stripped,
 *   punctuation removed.
 * - [extractQuery] takes the **original** transcript (diacritics preserved) so the
 *   Places API receives the highest-quality query string.
 *
 * ## Ordinal/cancel/confirm words are excluded from nav detection.
 * [isNavigationCommand] returns false for selection, cancellation, and confirmation
 * utterances so they are always handled by the pending-choices gate first.
 *
 * No Android imports — fully testable on a plain JVM.
 */
object VoiceDestinationDetector {

    // ── Navigation intent detection ────────────────────────────────────────────

    private val NAV_KEYWORDS = listOf(
        "nuvesk",   // "nuvesk į X"
        "surask",   // "surask [artimiausią] X"
        "parodyk",  // "parodyk kelią į X"
        "eime",     // "eime į X"
        "eikime",   // "eikime į X"
    )

    private val NAV_PHRASES = listOf(
        "vazuojam i ",      // "važiuojam į X"
        "rask artimiausia", // "rask artimiausią X"
        "marsrutas i ",     // "maršrutas į X"
        "navigacija i ",    // "navigacija į X"
        "eime i ",          // "eime į X"
        "eikime i ",        // "eikime į X"
    )

    /**
     * Returns true when [normText] expresses a navigation/destination intent.
     * Selection, cancellation, and confirmation utterances are explicitly excluded.
     */
    fun isNavigationCommand(normText: String): Boolean {
        if (isSelectionCommand(normText) || isCancellationCommand(normText) ||
            isConfirmationCommand(normText)) return false
        return NAV_KEYWORDS.any { normText.contains(it) } ||
               NAV_PHRASES.any  { normText.contains(it) }
    }

    // ── Query extraction ───────────────────────────────────────────────────────

    private val EXTRACTION_PATTERNS = listOf(
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:nuvesk|nuvek)(?:\s+mus)?\s+[įi]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:važiuojam|vazuojam)\s+[įi]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?surask\s+(?:artimiausi[aą]\s+)?(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?rask\s+artimiausi[aą]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?parodyk\s+keli[aą]\s+[įi]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:maršrutas|marsrutas|navigacija)\s+[įi]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:eime|eikime)\s+[įi]\s+(.+)$"""),
    )

    /** Extracts the destination query from [originalText], preserving diacritics. */
    fun extractQuery(originalText: String): String {
        val lower = originalText.lowercase()
        for (pattern in EXTRACTION_PATTERNS) {
            val match = pattern.find(lower) ?: continue
            val group = match.groups[1] ?: continue
            return originalText.substring(group.range.first, group.range.last + 1).trim()
        }
        return originalText
            .replace(Regex("^(?:kentai?|kente)[,\\s]+", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    // ── Category detection (normalized query → Google Place type) ─────────────

    /**
     * Maps normalized Lithuanian category words to Google Places types.
     * Keys are the exact post-normalization strings that the user would say.
     */
    private val CATEGORY_TOKENS: Map<String, String> = mapOf(
        // pharmacy
        "vaistine"          to "pharmacy",
        "vaistines"         to "pharmacy",
        "vaistiniu"         to "pharmacy",
        "vaistinele"        to "pharmacy",
        "apteka"            to "pharmacy",
        // gas station
        "degaline"          to "gas_station",
        "degalines"         to "gas_station",
        "degaliniu"         to "gas_station",
        "kuro kolonele"     to "gas_station",
        "degaliu stotele"   to "gas_station",
        "degaliu"           to "gas_station",
        // supermarket / grocery
        "parduotuve"        to "supermarket",
        "parduotuves"       to "supermarket",
        "maisto parduotuve" to "supermarket",
        "supermarketas"     to "supermarket",
        "prekyba"           to "supermarket",
        // restaurant
        "restoranas"        to "restaurant",
        "restoranai"        to "restaurant",
        "restoranu"         to "restaurant",
        // cafe
        "kavine"            to "cafe",
        "kavines"           to "cafe",
        "kavinu"            to "cafe",
        "kavinele"          to "cafe",
        "kavineles"         to "cafe",
        "kava"              to "cafe",
        // hospital
        "ligonine"          to "hospital",
        "ligonines"         to "hospital",
        "ligoniu"           to "hospital",
        "klinika"           to "hospital",
        "klinikos"          to "hospital",
        // ATM
        "bankomatas"        to "atm",
        "bankomatai"        to "atm",
        "grynuju"           to "atm",
    )

    /**
     * Returns a Google Place type if [normQuery] is a pure category utterance
     * (e.g. "vaistine", "degaline"), or null for named-place or free-text queries.
     *
     * Strips optional "artimiausia/artimiausias" prefix before matching so that
     * "surask artimiausią degalinę" (normQuery = "artimiausia degaline") still
     * resolves to "gas_station".
     */
    fun detectCategoryType(normQuery: String): String? {
        val stripped = normQuery.trim()
            .removePrefix("artimiausia ")
            .removePrefix("artimiausias ")
            .removePrefix("artimiausia")
            .trim()
        // Exact match (handles both single-word and two-word entries like "kuro kolonele")
        return CATEGORY_TOKENS[stripped]
    }

    // ── Chain name normalization ───────────────────────────────────────────────

    /**
     * Maps normalized Lithuanian inflections of known chain brands to their canonical
     * display name (used as the text query in Text Search).
     *
     * Only exact single-token matches are considered (after normalizeText + trim).
     * "Gintarinė vaistinė" is a multi-token query and will not match here.
     */
    private val CHAIN_VARIANTS: Map<String, String> = mapOf(
        // Maxima
        "maxima"      to "Maxima",
        "maximos"     to "Maxima",
        "maximoje"    to "Maxima",
        "maximoje"    to "Maxima",
        // Senukai
        "senukai"     to "Senukai",
        "senukus"     to "Senukai",
        "senuku"      to "Senukai",
        "senukuose"   to "Senukai",
        // Lidl
        "lidl"        to "Lidl",
        "lidlo"       to "Lidl",
        "lidluje"     to "Lidl",
        "lidlui"      to "Lidl",
        // Rimi
        "rimi"        to "Rimi",
        "rimio"       to "Rimi",
        "rimyje"      to "Rimi",
        // IKI
        "iki"         to "IKI",
        "ikiuke"      to "IKI",
        "ikiu"        to "IKI",
        // Norfa
        "norfa"       to "Norfa",
        "norfos"      to "Norfa",
        "norfoje"     to "Norfa",
        // Barbora / Netto
        "barbora"     to "Barbora",
        "netto"       to "Netto",
        "neto"        to "Netto",
        // Petrol / Circle K / Virpi / Lukoil
        "circlek"     to "Circle K",
        "viada"       to "Viada",
        "neste"       to "Neste",
        "lukoil"      to "Lukoil",
        "lukoilo"     to "Lukoil",
    )

    /**
     * Returns the canonical chain name for a single-word normalized query, or null
     * if [normQuery] does not match any known chain inflection.
     *
     * Also strips "artimiausia/artimiausias" prefix for queries like
     * "surask artimiausią Maximą".
     */
    fun normalizeChainName(normQuery: String): String? {
        val stripped = normQuery.trim()
            .removePrefix("artimiausia ")
            .removePrefix("artimiausias ")
            .trim()
        // Single-token check (chain names are always one word)
        val tokens = stripped.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size == 1) return CHAIN_VARIANTS[tokens[0]]
        // Two-token check for "circle k" style
        if (tokens.size == 2) return CHAIN_VARIANTS[tokens.joinToString("")]
        return null
    }

    // ── Pending-choice selection ───────────────────────────────────────────────

    private val ORDINALS_BY_INDEX: List<Set<String>> = listOf(
        setOf("pirma", "pirmas", "pirmoji", "pirmasis", "viena", "vienas"),  // 0 = first
        setOf("antra", "antras", "antroji", "antrasis"),                      // 1 = second
        setOf("trecia", "trecias", "trecioji", "treciasis", "trys"),          // 2 = third
    )

    /** Returns true when [normText] is a standalone ordinal selection utterance. */
    fun isSelectionCommand(normText: String): Boolean =
        extractSelectionIndex(normText) != null

    /**
     * Returns the 0-based index of the chosen suggestion (0=first, 1=second, 2=third),
     * or null if [normText] contains no recognisable ordinal word.
     */
    fun extractSelectionIndex(normText: String): Int? {
        val tokens = normText.split(Regex("\\s+")).filter { it.isNotBlank() }
        for ((idx, candidates) in ORDINALS_BY_INDEX.withIndex()) {
            if (tokens.any { candidates.contains(it) }) return idx
        }
        return null
    }

    /**
     * Tries to match [normText] against a spoken place name.
     * [normalizedNames] contains already-normalized primary names of each suggestion.
     * A word from the prediction name (≥4 chars) present in [normText] is treated
     * as a match. Returns the first matching index, or null.
     */
    fun matchesNameIndex(normText: String, normalizedNames: List<String>): Int? {
        for ((idx, predNorm) in normalizedNames.withIndex()) {
            val words = predNorm.split(Regex("\\s+")).filter { it.length >= 4 }
            if (words.any { normText.contains(it) }) return idx
        }
        return null
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    /**
     * Cancellation tokens (post-normalization).
     * Checked FIRST when pendingVoiceChoices is non-null — before any other gate.
     * Covers all forms listed in the spec plus common STT variants.
     */
    private val CANCEL_TOKENS: Set<String> = setOf(
        "atsauk",       // atšauk
        "atsaukti",     // atšaukti
        "nebereikia",   // nebereikia
        "nieko",        // nieko (covers "nieko nereikia" via token match)
        "nereikia",     // nereikia
        "nebenoriu",    // nebenoriu
        "atsisakau",    // atsisakau
        "palik",        // palik
        "stop",         // stop
        "uzbaik",       // užbaik
        "nutrauk",      // nutrauk
    )

    /**
     * Returns true when [normText] contains a cancellation word.
     * "ne" alone is intentionally excluded (too short; causes false positives on
     * Lithuanian sentences that start with "ne" as a negation prefix).
     */
    fun isCancellationCommand(normText: String): Boolean {
        val tokens = normText.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.any { CANCEL_TOKENS.contains(it) }
    }

    // ── Confirmation ──────────────────────────────────────────────────────────

    private val CONFIRM_TOKENS: Set<String> = setOf(
        "taip",         // taip
        "vazuojam",     // važiuojam
        "vazuojame",    // važiuojame
        "gerai",        // gerai
        "ok",           // ok
        "einam",        // einam
        "eisim",        // eisim
        "puiku",        // puiku
        "labai",        // "labai gerai" → "labai" alone would match; acceptable
    )

    /** Returns true when [normText] is a confirmation reply. */
    fun isConfirmationCommand(normText: String): Boolean {
        val tokens = normText.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.any { CONFIRM_TOKENS.contains(it) }
    }
}
