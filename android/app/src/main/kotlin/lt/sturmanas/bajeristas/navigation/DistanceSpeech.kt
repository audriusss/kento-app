package lt.sturmanas.bajeristas.navigation

// ─────────────────────────────────────────────────────────────────────────────
// Lithuanian numeral tables for TTS
//
// All forms are accusative (used after "apie N …").
// ─────────────────────────────────────────────────────────────────────────────

/** Accusative forms for whole-km numerals 1–20, used after "apie". */
private val KM_ACCUSATIVE_WORDS = mapOf(
    1  to "vieną",       2  to "du",          3  to "tris",
    4  to "keturis",     5  to "penkis",       6  to "šešis",
    7  to "septynis",    8  to "aštuonis",     9  to "devynis",
    10 to "dešimt",      11 to "vienuolika",   12 to "dvylika",
    13 to "trylika",     14 to "keturiolika",  15 to "penkiolika",
    16 to "šešiolika",   17 to "septyniolika", 18 to "aštuoniolika",
    19 to "devyniolika", 20 to "dvidešimt",
)

/** Accusative feminine forms for minute units 2–9 (used after a tens word). */
private val MINUTE_UNITS = mapOf(
    2 to "dvi",       3 to "tris",    4 to "keturias",
    5 to "penkias",   6 to "šešias",  7 to "septynias",
    8 to "aštuonias", 9 to "devynias",
)

/** Nominative/genitive forms for teen minutes 10–19 (always genitive plural "minučių"). */
private val MINUTE_TEENS = mapOf(
    10 to "dešimt",       11 to "vienuolika",   12 to "dvylika",
    13 to "trylika",      14 to "keturiolika",  15 to "penkiolika",
    16 to "šešiolika",    17 to "septyniolika", 18 to "aštuoniolika",
    19 to "devyniolika",
)

/** Tens words for multiples of 10 (20–120). */
private val MINUTE_TENS = mapOf(
    2  to "dvidešimt",         3  to "trisdešimt",
    4  to "keturiasdešimt",    5  to "penkiasdešimt",
    6  to "šešiasdešimt",      7  to "septyniasdešimt",
    8  to "aštuoniasdešimt",   9  to "devyniasdešimt",
    10 to "šimtą",             11 to "šimtą dešimt",
    12 to "šimtą dvidešimt",
)

// ─────────────────────────────────────────────────────────────────────────────
// Distance
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Converts a remaining-distance value (metres) into a natural Lithuanian TTS phrase.
 *
 * Rounding rules:
 * - Below 1 000 m : nearest 50 m → "metrų"
 * - 1 – 20 km    : nearest 500 m → Lithuanian word form; half-km uses "su puse"
 * - Above 20 km  : nearest 1 km → digits (word forms get unwieldy above 20)
 *
 * Never says "koma". Fully testable on the JVM — no Android imports.
 */
fun distanceSpeech(meters: Int): String = when {
    meters <= 0 || meters == Int.MAX_VALUE -> "Liko labai mažai."

    meters < 1_000 -> {
        val rounded = ((meters + 25) / 50) * 50
        "Liko apie $rounded metrų."
    }

    else -> {
        // Round to nearest 500 m bracket first to check whether km ≤ 20.
        val r500 = ((meters + 250) / 500) * 500
        val km   = r500 / 1_000
        val half = (r500 % 1_000) == 500

        when {
            km > 20 -> {
                // Above 20 km: round to nearest 1 km and use digits.
                val kmRound = (meters + 500) / 1_000
                "Liko apie $kmRound ${kmAccusative(kmRound)}."
            }
            half -> {
                val word = KM_ACCUSATIVE_WORDS[km] ?: km.toString()
                "Liko apie $word su puse kilometro."
            }
            else -> {
                val word = KM_ACCUSATIVE_WORDS[km] ?: km.toString()
                "Liko apie $word ${kmAccusative(km)}."
            }
        }
    }
}

/**
 * Lithuanian accusative noun form for "kilometras" after "apie N".
 *
 * Declension:
 *  1, 21, 31, … → "kilometrą"  (accusative singular)
 *  2–9, 22–29, … → "kilometrus" (accusative plural, excluding -teen)
 *  10–20, 100, … → "kilometrų"  (genitive plural)
 */
internal fun kmAccusative(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11         -> "kilometrą"
    n % 10 in 2..9 && n % 100 !in 11..19 -> "kilometrus"
    else                                   -> "kilometrų"
}

// ─────────────────────────────────────────────────────────────────────────────
// ETA
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns a natural Lithuanian ETA phrase for [minutes] remaining, prefixed with "apie".
 *
 * Examples:
 *   1  → "apie vieną minutę"
 *   2  → "apie dvi minutes"
 *   10 → "apie dešimt minučių"
 *   21 → "apie dvidešimt vieną minutę"
 *   22 → "apie dvidešimt dvi minutes"
 *   60 → "apie šešiasdešimt minučių"
 *  120 → "apie šimtą dvidešimt minučių"
 *
 * Supports 1–120 minutes. Values > 120 fall back to a digit form.
 * Returns "labai mažai" for zero or negative input.
 */
fun minuteSpeech(minutes: Int): String = when {
    minutes <= 0   -> "labai mažai"
    else           -> "apie ${minuteNumeralLt(minutes)}"
}

/**
 * Composes the Lithuanian numeral phrase for [n] minutes (without the "apie" prefix).
 * The caller ([minuteSpeech]) adds the prefix.
 */
internal fun minuteNumeralLt(n: Int): String = when {
    n == 1          -> "vieną minutę"
    n in 2..9       -> "${MINUTE_UNITS[n]} minutes"
    n in 10..19     -> "${MINUTE_TEENS[n]} minučių"
    n % 10 == 0 && n <= 120 -> {
        val tensWord = MINUTE_TENS[n / 10] ?: "$n"
        "$tensWord minučių"
    }
    n <= 120 -> {
        val tensWord = MINUTE_TENS[n / 10] ?: "${(n / 10) * 10}"
        val units    = n % 10
        when (units) {
            1    -> "$tensWord vieną minutę"
            in 2..9 -> "$tensWord ${MINUTE_UNITS[units]} minutes"
            else    -> "$tensWord minučių"
        }
    }
    else -> "${n / 60} val. ${n % 60} min."
}
