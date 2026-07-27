package lt.sturmanas.bajeristas.voice.ai

/**
 * Pure Kotlin helpers for deciding whether a buffered transcript is complete
 * enough to send to the AI immediately or should wait for continuation.
 *
 * All functions operate on **normalised** Lithuanian text (no diacritics,
 * lowercase, punctuation stripped) produced by AIConversationController's
 * normalizeText().  Punctuation checks must be performed on the original text
 * before normalisation; they are handled in the calling code.
 *
 * No Android imports — fully testable on a plain JVM.
 */
object SemanticCompletionDetector {

    /**
     * Lithuanian question words (normalised, no diacritics).
     * A transcript containing any of these is treated as a complete question.
     */
    val questionWords: Set<String> = setOf(
        "kur", "kada", "kodel", "kaip", "kas", "ar", "kiek", "koks", "kokia",
        "kuria", "kuris", "kuri", "kelintas", "kelinta",
    )

    /**
     * Words / conjunctions that typically open an incomplete clause.
     * When the first word of the normalised text is in this set the utterance
     * is likely cut off mid-thought and should wait for continuation.
     */
    private val incompleteClauseStarters: Set<String> = setOf(
        "o", "bet", "ir", "kad", "nes", "tai", "jei", "jeigu",
        "nors", "arba", "taciau", "todel", "tad", "palauk", "na",
        "tik", "kai",
    )

    /**
     * Returns true if any token in [normalizedText] is a question word.
     */
    fun tokensContainQuestion(normalizedText: String): Boolean =
        normalizedText.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .any { questionWords.contains(it) }

    /**
     * Returns true if the **last** content word in [normalizedText] looks like a
     * Lithuanian imperative verb.
     *
     * Lithuanian 2nd-person singular imperatives end in **-k** (e.g. *važiuok*,
     * *papasakok*, *suk*, *atidaryk*).  The 2nd-person plural ends in **-kite**;
     * the 1st-person plural ends in **-kime** or **-kim**.
     *
     * To avoid false positives, question words that happen to end in -k (e.g.
     * *kiek*) are excluded — they are already caught by [tokensContainQuestion].
     */
    fun looksLikeImperative(normalizedText: String): Boolean {
        val last = normalizedText.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .lastOrNull() ?: return false
        if (questionWords.contains(last)) return false   // "kiek" etc.
        return last.endsWith("kite") ||
               last.endsWith("kime") ||
               last.endsWith("kim")  ||
               (last.endsWith("k") && last.length >= 3)
    }

    /**
     * Returns true if the **first** content word of [normalizedText] is a
     * conjunction or filler that strongly suggests the sentence is incomplete,
     * e.g. *"o jeigu mes…"*, *"bet jei…"*, *"palauk, dar…"*.
     */
    fun startsWithIncompleteClause(normalizedText: String): Boolean {
        val first = normalizedText.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .firstOrNull() ?: return false
        return first in incompleteClauseStarters
    }
}
