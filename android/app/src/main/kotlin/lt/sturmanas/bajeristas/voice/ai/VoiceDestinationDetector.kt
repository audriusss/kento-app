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
 * ## Ordinal words ('pirma', 'antra', 'trecia') are NOT treated as nav commands.
 * [isNavigationCommand] excludes them, so they are never routed through Places search
 * when the pending-choices gate in AIConversationController handles them first.
 *
 * No Android imports — fully testable on a plain JVM.
 */
object VoiceDestinationDetector {

    // ── Detection (normalized text: diacritics stripped, lowercase) ───────────

    /**
     * Single-word nav verbs.  Present in the normalized transcript ⇒ nav intent.
     */
    private val NAV_KEYWORDS = listOf(
        "nuvesk",    // "nuvesk į X"
        "surask",    // "surask [artimiausią] X"
        "parodyk",   // "parodyk kelią į X"
        "eime",      // "eime į X"
        "eikime",    // "eikime į X"
    )

    /**
     * Multi-word nav phrases (normalized).
     */
    private val NAV_PHRASES = listOf(
        "vazuojam i ",      // "važiuojam į X"
        "rask artimiausia", // "rask artimiausią X"
        "marsrutas i ",     // "maršrutas į X"
        "navigacija i ",    // "navigacija į X"
        "eime i ",          // "eime į X"  (phrase version with trailing space)
        "eikime i ",        // "eikime į X"
    )

    /**
     * Returns true when [normText] contains a clear navigation/destination intent.
     * Ordinal words ("pirma", "antra", "trecia") are explicitly excluded so that
     * a user saying "pirmą" during a pending choice does not trigger a new search.
     *
     * @param normText Normalized (diacritics stripped, lowercase, no punct) text.
     */
    fun isNavigationCommand(normText: String): Boolean {
        // Guard: pure ordinal/confirmation/cancellation utterances must never be
        // treated as a new nav search — they are handled by the pending-choices gate.
        if (isSelectionCommand(normText) || isCancellationCommand(normText) ||
            isConfirmationCommand(normText)) return false

        return NAV_KEYWORDS.any { normText.contains(it) } ||
               NAV_PHRASES.any  { normText.contains(it) }
    }

    // ── Extraction (original text, diacritics preserved) ─────────────────────

    /**
     * Patterns applied to `originalText.lowercase()` (diacritics preserved,
     * only case folded).  Each pattern has exactly **one capture group** for
     * the destination query.  Listed from most specific to least.
     */
    private val EXTRACTION_PATTERNS = listOf(
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:nuvesk|nuvek)(?:\s+mus)?\s+[įi]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:važiuojam|vazuojam)\s+[įi]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?surask\s+(?:artimiausi[aą]\s+)?(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?rask\s+artimiausi[aą]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?parodyk\s+keli[aą]\s+[įi]\s+(.+)$"""),
        Regex("""^(?:(?:kentai?|kente)[,\s]+)?(?:maršrutas|marsrutas|navigacija)\s+[įi]\s+(.+)$"""),
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
     * ```
     *
     * Falls back to stripping only the wake-word prefix when no pattern matches.
     */
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

    // ── Pending-choice selection ───────────────────────────────────────────────

    /**
     * Normalized ordinal forms for positions 1, 2, 3 (index 0, 1, 2).
     * Diacritics are already stripped by normalizeText before these are compared.
     */
    private val ORDINALS_BY_INDEX: List<Set<String>> = listOf(
        // index 0 — first
        setOf("pirma", "pirmas", "pirmoji", "pirmasis", "pirm", "viena", "vienas"),
        // index 1 — second
        setOf("antra", "antras", "antroji", "antrasis", "antr"),
        // index 2 — third
        setOf("trecia", "trecias", "trecioji", "treciasis", "trec", "trys"),
    )

    /**
     * Returns true when [normText] is a standalone ordinal selection utterance
     * ("pirmą", "antras", "trečias", etc.).  Used to guard [isNavigationCommand].
     */
    fun isSelectionCommand(normText: String): Boolean =
        extractSelectionIndex(normText) != null

    /**
     * Returns the 0-based index of the chosen suggestion (0=first, 1=second, 2=third),
     * or null if [normText] contains no recognisable ordinal word.
     *
     * Matches whole tokens (split on whitespace) to avoid false positives on words
     * that merely contain ordinal substrings.
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
     * [normalizedPrimaryTexts] contains already-normalized primary text of each suggestion.
     *
     * Strategy: a word from the prediction name (≥4 chars) present in [normText] is
     * treated as a match.  Returns the first matching index, or null.
     */
    fun matchesNameIndex(normText: String, normalizedPrimaryTexts: List<String>): Int? {
        for ((idx, predNorm) in normalizedPrimaryTexts.withIndex()) {
            val words = predNorm.split(Regex("\\s+")).filter { it.length >= 4 }
            if (words.any { normText.contains(it) }) return idx
        }
        return null
    }

    // ── Cancellation & confirmation ───────────────────────────────────────────

    /** Normalized cancellation tokens. */
    private val CANCEL_TOKENS = setOf(
        "atsauk",       // atšauk
        "nieko",        // nieko
        "nebereikia",   // nebereikia
        "nebenoriu",    // nebenoriu
        "atsisakau",    // atsisakau
        "ne",           // terse "ne"
    )

    /**
     * Returns true when [normText] is a cancellation command
     * ("atšauk", "nieko", "nebereikia", etc.).
     */
    fun isCancellationCommand(normText: String): Boolean {
        val tokens = normText.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.any { CANCEL_TOKENS.contains(it) }
    }

    /** Normalized confirmation tokens for the single-result "Važiuojam?" prompt. */
    private val CONFIRM_TOKENS = setOf(
        "taip",         // taip
        "vazuojam",     // važiuojam
        "vazuojame",    // važiuojame
        "gerai",        // gerai
        "ok",           // ok
        "einam",        // einam
        "eisim",        // eisim
        "puiku",        // puiku
    )

    /**
     * Returns true when [normText] is a confirmation reply
     * ("taip", "gerai", "važiuojam", etc.).
     */
    fun isConfirmationCommand(normText: String): Boolean {
        val tokens = normText.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.any { CONFIRM_TOKENS.contains(it) }
    }
}
