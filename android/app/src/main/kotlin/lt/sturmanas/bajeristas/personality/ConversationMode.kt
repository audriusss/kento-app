package lt.sturmanas.bajeristas.personality

/**
 * Controls the language register and content boundaries of AI responses.
 *
 * [SOFT] — natural, friendly, lightly humorous Lithuanian. Warm everyday tone,
 *          mild wit, no profanity.
 *
 * [HARD] — clearly adult, rough colloquial Lithuanian friend style. Supports
 *          strong everyday slang, profanity when contextually appropriate,
 *          crude folk comparisons, sharp playful banter, and natural mirroring
 *          of the driver's own speaking style.
 *
 *          HARD must never encourage road rage, confrontation, dangerous driving,
 *          threaten real people, target protected characteristics, or produce
 *          safety-critical distraction.
 */
enum class ConversationMode {
    SOFT,
    HARD,
}
