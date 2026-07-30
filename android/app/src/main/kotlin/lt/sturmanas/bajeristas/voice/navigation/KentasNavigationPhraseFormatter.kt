package lt.sturmanas.bajeristas.voice.navigation

import lt.sturmanas.bajeristas.navigation.ManeuverType

/**
 * Deterministic Lithuanian phrase formatter for Kentas-style navigation TTS.
 *
 * ## Distance formatting
 * Common round distances are spoken as Lithuanian words rather than bare digits
 * so the TTS engine renders them naturally (e.g. "už šimto metrų" instead of
 * "už 100 metrų", which some voices mispronounce).
 *
 * ## STRAIGHT suppression
 * STRAIGHT announcements at FAR and MEDIUM stages are not formatted here —
 * [NavigationVoiceController] skips those stages for STRAIGHT entirely.
 * STRAIGHT at IMMEDIATE is a short confirmation phrase so the driver knows
 * the current leg is a straight run.
 */
class KentasNavigationPhraseFormatter {

    enum class SpeechStage {
        FAR,       // 700–1000 m advance warning
        MEDIUM,    // 250–400 m preparation
        IMMEDIATE, // 0–120 m execute now
        ARRIVED,   // Destination reached
    }

    /**
     * Format a maneuver into a Kentas-style Lithuanian TTS phrase.
     *
     * @param maneuver      Internal maneuver type.
     * @param distanceMeters Distance in metres to the maneuver.
     * @param stage         Which announcement stage this is.
     * @param variantIndex  Rotates between phrasing variants to avoid repetition.
     * @param exitNumber    Roundabout exit number (1-based); null when unknown.
     */
    fun format(
        maneuver: ManeuverType,
        distanceMeters: Int,
        stage: SpeechStage,
        variantIndex: Int = 0,
        exitNumber: Int? = null,
    ): String = when (stage) {
        SpeechStage.FAR       -> formatFar(maneuver, distanceMeters, variantIndex % 2)
        SpeechStage.MEDIUM    -> formatMedium(maneuver, distanceMeters, variantIndex % 2, exitNumber)
        SpeechStage.IMMEDIATE -> formatImmediate(maneuver, variantIndex % 2, exitNumber)
        SpeechStage.ARRIVED   -> formatArrived(variantIndex % 2)
    }

    // ── FAR stage (700–1000 m) ─────────────────────────────────────────────

    private fun formatFar(maneuver: ManeuverType, dist: Int, variant: Int): String {
        val d = formatDistanceWords(dist)
        return when (maneuver) {
            ManeuverType.TURN_RIGHT -> if (variant == 0)
                "Už $d suksim dešinėn."
            else
                "Ruoškis dešinėn — už $d."

            ManeuverType.TURN_LEFT -> if (variant == 0)
                "Už $d suksim kairėn."
            else
                "Ruoškis kairėn — už $d."

            ManeuverType.SLIGHT_RIGHT -> "Už $d laikykis dešiniau."
            ManeuverType.SLIGHT_LEFT  -> "Už $d laikykis kairiau."

            ManeuverType.SHARP_RIGHT  -> "Už $d aštrus posūkis dešinėn."
            ManeuverType.SHARP_LEFT   -> "Už $d aštrus posūkis kairėn."

            ManeuverType.UTURN -> "Už $d reikės apsisukti."

            ManeuverType.ROUNDABOUT -> if (variant == 0)
                "Už $d bus žiedas."
            else
                "Po $d — žiedas."

            ManeuverType.MOTORWAY_EXIT -> "Už $d nuvaziuojam nuo magistralės."
            ManeuverType.MERGE         -> "Už $d siunčiuosi į eismą."
            ManeuverType.FORK          -> "Už $d kelias šakojasi."

            ManeuverType.ARRIVE -> "Už $d būsim vietoj."

            // STRAIGHT, NONE, UNKNOWN — NavigationVoiceController filters these out
            else -> ""
        }
    }

    // ── MEDIUM stage (250–400 m) ───────────────────────────────────────────

    private fun formatMedium(maneuver: ManeuverType, dist: Int, variant: Int, exitNumber: Int?): String {
        val d = formatDistanceWords(dist)
        return when (maneuver) {
            ManeuverType.TURN_RIGHT -> if (variant == 0)
                "Tuoj suksim dešinėn, už $d."
            else
                "Dešinėn už $d."

            ManeuverType.TURN_LEFT -> if (variant == 0)
                "Tuoj suksim kairėn, už $d."
            else
                "Kairėn už $d."

            ManeuverType.SLIGHT_RIGHT -> "Švelniai dešinėn už $d."
            ManeuverType.SLIGHT_LEFT  -> "Švelniai kairėn už $d."
            ManeuverType.SHARP_RIGHT  -> "Aštrus posūkis dešinėn už $d."
            ManeuverType.SHARP_LEFT   -> "Aštrus posūkis kairėn už $d."

            ManeuverType.UTURN -> "Apsisukam už $d."

            ManeuverType.ROUNDABOUT -> {
                val exit = exitOrdinal(exitNumber)
                if (variant == 0)
                    "Žiede imk $exit išvažiavimą."
                else
                    "Artėja žiedas — $exit išvažiavimas."
            }

            ManeuverType.MOTORWAY_EXIT -> "Nuvažiuojam nuo magistralės už $d."
            ManeuverType.MERGE         -> "Jungiamės į eismą už $d."
            ManeuverType.FORK          -> "Kelias šakojasi už $d."

            else -> ""
        }
    }

    // ── IMMEDIATE stage (0–120 m) ──────────────────────────────────────────

    private fun formatImmediate(maneuver: ManeuverType, variant: Int, exitNumber: Int?): String {
        return when (maneuver) {
            ManeuverType.STRAIGHT -> if (variant == 0)
                "Tiesiai varom."
            else
                "Laikykis tiesiai."

            ManeuverType.TURN_RIGHT -> if (variant == 0)
                "Va dabar dešinėn."
            else
                "Dešinėn."

            ManeuverType.TURN_LEFT -> if (variant == 0)
                "Va dabar kairėn."
            else
                "Kairėn."

            ManeuverType.SLIGHT_RIGHT -> if (variant == 0)
                "Laikykis dešiniau."
            else
                "Švelniai dešinėn."

            ManeuverType.SLIGHT_LEFT -> if (variant == 0)
                "Laikykis kairiau."
            else
                "Švelniai kairėn."

            ManeuverType.SHARP_RIGHT  -> "Staigiai dešinėn."
            ManeuverType.SHARP_LEFT   -> "Staigiai kairėn."

            ManeuverType.UTURN -> if (variant == 0)
                "Kai bus galima, apsisuk."
            else
                "Apsisuk."

            ManeuverType.ROUNDABOUT -> {
                val exit = exitOrdinal(exitNumber)
                "Žiede imk $exit išvažiavimą."
            }

            ManeuverType.MOTORWAY_EXIT -> "Nuvažiuojam nuo magistralės."
            ManeuverType.MERGE         -> "Jungiamės į eismą."
            ManeuverType.FORK          -> "Kelias šakojasi — rink tinkamą pusę."

            ManeuverType.ARRIVE -> "Atvažiavom."

            else -> ""
        }
    }

    // ── ARRIVED ───────────────────────────────────────────────────────────

    private fun formatArrived(variant: Int): String = if (variant == 0)
        "Nu va, privažiavom."
    else
        "Štai ir vietoj. Gali atsegti nervus."

    // ── Distance formatting ────────────────────────────────────────────────

    /**
     * Converts a distance in metres to a natural Lithuanian phrase fragment
     * like "šimto metrų" or "dviejų kilometrų".
     *
     * Values are first rounded to the nearest meaningful step, then mapped
     * to words for the most common round distances.  Unmapped values fall
     * back to digits so TTS can at least read them.
     */
    fun formatDistanceWords(distMeters: Int): String {
        val r = roundDistance(distMeters)
        return when {
            r >= 5_000 -> "${r / 1_000} kilometrų"
            r >= 4_000 -> "keturių kilometrų"
            r >= 3_000 -> "trijų kilometrų"
            r >= 2_000 -> "dviejų kilometrų"
            r >= 1_500 -> "pusantro kilometro"
            r >= 1_000 -> "kilometro"
            r >= 900  -> "devynių šimtų metrų"
            r >= 800  -> "aštuonių šimtų metrų"
            r >= 700  -> "septynių šimtų metrų"
            r >= 600  -> "šešių šimtų metrų"
            r >= 500  -> "penkių šimtų metrų"
            r >= 400  -> "keturių šimtų metrų"
            r >= 300  -> "trijų šimtų metrų"
            r >= 200  -> "dviejų šimtų metrų"
            r >= 150  -> "šimto penkiasdešimt metrų"
            r >= 100  -> "šimto metrų"
            r >= 50   -> "penkiasdešimt metrų"
            r > 0     -> "$r metrų"
            else      -> "kelių metrų"
        }
    }

    /** Rounds to the nearest 50 m below 1 km; to the nearest 100 m above. */
    fun roundDistance(dist: Int): Int =
        if (dist >= 1_000) (dist / 100) * 100 else (dist / 50) * 50

    // ── Ordinal helpers ───────────────────────────────────────────────────

    /**
     * Lithuanian accusative ordinal for roundabout exit number.
     * "imk **pirmą** išvažiavimą" — accusative case is required with *imk*.
     */
    fun exitOrdinal(num: Int?): String = when (num) {
        1    -> "pirmą"
        2    -> "antrą"
        3    -> "trečią"
        4    -> "ketvirtą"
        5    -> "penktą"
        6    -> "šeštą"
        else -> "reikiamą"
    }
}
