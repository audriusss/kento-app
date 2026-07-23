package lt.sturmanas.bajeristas.navigation

/**
 * Converts a remaining-distance value (metres) into a natural Lithuanian TTS phrase.
 *
 * Rules
 * - Below 1000 m: rounded to nearest 50 m; expressed as "metrų".
 * - 1 km+: rounded to nearest 500 m; half-km values use "su puse"; whole-km uses
 *   accusative noun form (never says "koma").
 * - Noun declension after "apie":
 *     1 → "kilometrą"   (accusative singular)
 *     2–9 → "kilometrus" (accusative plural)
 *     10+ → "kilometrų"  (genitive plural)
 *
 * Fully testable on the JVM — no Android imports.
 */
fun distanceSpeech(meters: Int): String = when {
    meters <= 0 || meters == Int.MAX_VALUE -> "Liko labai mažai."
    meters < 1000 -> {
        val rounded = ((meters + 25) / 50) * 50
        "Liko apie $rounded metrų."
    }
    else -> {
        // Round to nearest 500 m bracket — absorbs any decimal without saying "koma".
        val r500 = ((meters + 250) / 500) * 500
        val km   = r500 / 1000
        val half = (r500 % 1000) == 500
        if (half) {
            "Liko apie $km su puse kilometro."
        } else {
            "Liko apie $km ${kmAccusative(km)}."
        }
    }
}

/**
 * Lithuanian accusative noun form for "kilometras" used after "apie N".
 *
 * Matches standard Lithuanian declension rules:
 * - 1, 21, 31, … → "kilometrą"  (acc. singular)
 * - 2–9, 22–29, … → "kilometrus" (acc. plural, excluding -1x)
 * - 10–20, 100, 1000, … → "kilometrų"  (gen. plural)
 */
internal fun kmAccusative(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11         -> "kilometrą"
    n % 10 in 2..9 && n % 100 !in 11..19 -> "kilometrus"
    else                                   -> "kilometrų"
}
