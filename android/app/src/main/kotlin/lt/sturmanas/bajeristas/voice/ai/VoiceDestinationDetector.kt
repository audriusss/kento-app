package lt.sturmanas.bajeristas.voice.ai

/**
 * Detects navigation/destination intent in voice transcripts and extracts
 * the raw destination query for the Places autocomplete API.
 *
 * ## Separation of concerns
 * - [isNavigationCommand] operates on **normalized** text (diacritics stripped,
 *   lowercase, punctuation removed) produced by `AIConversationController.normalizeText()`.
 * - [extractQuery] operates on the **original** transcript (diacritics preserved)
 *   to give the Places API the best possible query.
 *
 * ## False-positive guard
 * Normal sentences that mention a place without an explicit navigation verb ("nuvesk",
 * "surask", "važiuojam į", etc.) do NOT match.  "Buvau Akropolyje" will not trigger
 * navigation; "Nuvesk į Akropolį" will.
 *
 * No Android imports — fully testable on a plain JVM.
 */
object VoiceDestinationDetector {

    // ── Detection (normalized text: diacritics stripped, lowercase) ───────────

    /**
     * Single-word nav verbs.  Present in the normalized transcript ⇒ nav intent.
     * "parodyk" alone could be ambiguous ("parodyk nuotrauką"), but is included
     * because the full phrase "parodyk kelią" is the common pattern; the extractor
     * then finds "kelią į X" and captures just X, so the worst case is a pointless
     * Places search on an unlikely phrase.
     */
    private val NAV_KEYWORDS = listOf(
        "nuvesk",    // "nuvesk į X"
        "surask",    // "surask [artimiausią] X"
        "parodyk",   // "parodyk kelią į X"
        "eime",      // "eime į X"  (careful: short word, requires "į" in phrase check below)
        "eikime",    // "eikime į X"
    )

    /**
     * Multi-word nav phrases (normalized).
     * "eime i" has a trailing space so standalone "eime" in a non-nav sentence
     * (e.g. "eime į kiną vakar") is still caught — the space ensures the "i" is
     * a standalone token, not a substring of another word.
     */
    private val NAV_PHRASES = listOf(
        "vazuojam i ",     // "važiuojam į X"
        "rask artimiausia",// "rask artimiausią X"
        "marsrutas i ",    // "maršrutas į X"
        "navigacija i ",   // "navigacija į X"
        "eime i ",         // "eime į X"  (phrase version with trailing space)
        "eikime i ",       // "eikime į X"
    )

    /**
     * Returns true when [normText] contains a clear navigation/destination intent.
     *
     * @param normText Normalized (diacritics stripped, lowercase, no punct) text
     *                 from `AIConversationController.normalizeText()`.
     */
    fun isNavigationCommand(normText: String): Boolean =
        NAV_KEYWORDS.any { normText.contains(it) } ||
        NAV_PHRASES.any  { normText.contains(it) }

    // ── Extraction (original text, diacritics preserved) ─────────────────────

    /**
     * Patterns applied to `originalText.lowercase()` (diacritics preserved,
     * only case folded).  Each pattern has exactly **one capture group** for
     * the destination query.  Listed from most specific to least.
     *
     * Kotlin `String.lowercase()` is a 1-to-1 mapping for Lithuanian characters
     * (no length change), so `group.range` indexes map directly back into
     * [originalText] to restore original casing and diacritics.
     */
    private val EXTRACTION_PATTERNS = listOf(
        // "nuvesk [mus] į X" / "nuvesk X"
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:nuvesk|nuvek)(?:\s+mus)?\s+[įi]\s+(.+)$"""),
        // "važiuojam į X" (with and without diacritics)
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:važiuojam|vazuojam)\s+[įi]\s+(.+)$"""),
        // "surask [artimiausią] X"
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?surask\s+(?:artimiausi[aą]\s+)?(.+)$"""),
        // "rask artimiausią X"  (requires "artimiausią" to avoid bare "rask" false positives)
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?rask\s+artimiausi[aą]\s+(.+)$"""),
        // "parodyk kelią į X"
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?parodyk\s+keli[aą]\s+[įi]\s+(.+)$"""),
        // "maršrutas į X" / "navigacija į X"
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:maršrutas|marsrutas|navigacija)\s+[įi]\s+(.+)$"""),
        // "eime į X" / "eikime į X"
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:eime|eikime)\s+[įi]\s+(.+)$"""),
    )

    /**
     * Extracts the destination query from [originalText], preserving diacritics.
     *
     * Examples (Lithuanian STT output → Places query):
     * ```
     * "Kentai, nuvesk į Senukus"     → "Senukus"
     * "Nuvesk į vaistinę"            → "vaistinę"
     * "Surask artimiausią degalinę"  → "degalinę"
     * "Važiuojam į Akropolį"         → "Akropolį"
     * "Nuvesk į Pietinės gatvę 17"   → "Pietinės gatvę 17"
     * ```
     *
     * Falls back to stripping only the wake-word prefix when no pattern matches.
     */
    fun extractQuery(originalText: String): String {
        val lower = originalText.lowercase()   // same length as originalText
        for (pattern in EXTRACTION_PATTERNS) {
            val match = pattern.find(lower) ?: continue
            val group = match.groups[1] ?: continue
            // Slice originalText to recover diacritics and original casing.
            return originalText.substring(group.range.first, group.range.last + 1).trim()
        }
        // Fallback: remove wake-word prefix ("Kentai, " etc.) and return the rest.
        return originalText
            .replace(Regex("^(?:kentai?|kente)[,\\s]+", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
