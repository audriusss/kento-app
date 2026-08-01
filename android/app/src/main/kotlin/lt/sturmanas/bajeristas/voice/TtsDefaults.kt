package lt.sturmanas.bajeristas.voice

import java.util.Locale

/**
 * Shared TTS configuration and text normalization logic for both Kentas and
 * navigation guidance.
 */
object TtsDefaults {

    /** Default pitch for a natural character voice. */
    const val PITCH = 1.0f

    /** Slightly slowed speech rate for better clarity in Lithuanian. */
    const val SPEECH_RATE = 0.92f

    /** Authority for all Lithuanian TTS utterances. */
    val LOCALE = Locale("lt", "LT")

    /**
     * Cleans up raw text for better TTS performance:
     * 1. Trims leading/trailing whitespace.
     * 2. Collapses multiple spaces into one.
     * 3. Ensures single space after commas, periods, etc.
     */
    fun normalizeForTts(text: String): String {
        if (text.isBlank()) return ""
        
        return text
            .trim()
            // Collapse whitespace
            .replace(Regex("\\s+"), " ")
            // Ensure space after punctuation if missing (e.g. "Kentas,labas" -> "Kentas, labas")
            .replace(Regex("([,.!?:])(?=[^\\s])"), "$1 ")
            // Remove space before punctuation (e.g. "Kentas , labas" -> "Kentas, labas")
            .replace(Regex("\\s+([,.!?:])"), "$1")
    }

    /**
     * Splits text into speakable chunks.
     * Only splits by sentence terminators, and further splits extremely long
     * sentences (> 180 chars) at the first comma or space.
     */
    fun splitForSpeaking(text: String): List<String> {
        val normalized = normalizeForTts(text)
        if (normalized.isBlank()) return emptyList()

        // 1. Initial split by sentence terminators
        val sentences = normalized.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

        // 2. Further split only extremely long sentences (> 180 chars)
        val result = mutableListOf<String>()
        for (s in sentences) {
            if (s.length <= 180) {
                result.add(s)
            } else {
                // Find a break point near the middle (comma preferred)
                val mid = s.length / 2
                var breakIdx = s.indexOf(',', mid)
                if (breakIdx == -1 || breakIdx > mid + 40) {
                    breakIdx = s.lastIndexOf(',', mid)
                }
                if (breakIdx == -1) {
                    breakIdx = s.indexOf(' ', mid)
                }
                
                if (breakIdx != -1) {
                    result.add(s.substring(0, breakIdx + 1).trim())
                    result.add(s.substring(breakIdx + 1).trim())
                } else {
                    result.add(s)
                }
            }
        }
        return result
    }
}
