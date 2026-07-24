package lt.sturmanas.bajeristas.voice

import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.personality.formatDistance

/**
 * Generates deterministic Lithuanian navigation phrases for each maneuver type.
 *
 * Phrases rotate through a list per maneuver type so the driver doesn't hear
 * the same sentence every time they approach a turn. No OpenAI call is made —
 * the formatter is pure and fast.
 *
 * Thread safety: rotation indices are only updated from the main thread
 * (maneuver TTS is triggered from a LaunchedEffect on the main dispatcher).
 */
class KentasNavigationPhraseFormatter {

    // Rotation cursor per maneuver type.
    private val rotationIndex = mutableMapOf<ManeuverType, Int>()

    /**
     * Return the next Lithuanian phrase for [maneuver] at [distanceMeters] to
     * [nextRoadName]. Returns an empty string for maneuver types that don't need
     * a spoken announcement (NONE, STRAIGHT, UNKNOWN).
     */
    fun format(
        maneuver: ManeuverType,
        distanceMeters: Int,
        nextRoadName: String,
    ): String {
        val templates = phrasesFor(maneuver).takeIf { it.isNotEmpty() } ?: return ""
        val idx = ((rotationIndex[maneuver] ?: -1) + 1) % templates.size
        rotationIndex[maneuver] = idx

        val dist = formatDistance(distanceMeters)
        val road = if (nextRoadName.isNotBlank()) " į $nextRoadName" else ""
        val roadDot = if (nextRoadName.isNotBlank()) " į $nextRoadName." else "."

        return templates[idx]
            .replace("{dist}", dist)
            .replace("{road}", road)
            .replace("{road.}", roadDot)
    }

    // ── Phrase lists ───────────────────────────────────────────────────────

    private fun phrasesFor(maneuver: ManeuverType): List<String> = when (maneuver) {

        ManeuverType.TURN_LEFT -> listOf(
            "Po {dist} sukite kairėn{road.}",
            "Kairėn po {dist}{road.}",
            "Po {dist} — kairėn{road.}",
        )

        ManeuverType.TURN_RIGHT -> listOf(
            "Po {dist} sukite dešinėn{road.}",
            "Dešinėn po {dist}{road.}",
            "Po {dist} — dešinėn{road.}",
        )

        ManeuverType.SLIGHT_LEFT -> listOf(
            "Po {dist} šiek tiek kairėn{road.}",
            "Kairėn, lengvai, po {dist}{road.}",
        )

        ManeuverType.SLIGHT_RIGHT -> listOf(
            "Po {dist} šiek tiek dešinėn{road.}",
            "Dešinėn, lengvai, po {dist}{road.}",
        )

        ManeuverType.SHARP_LEFT -> listOf(
            "Po {dist} staigiai kairėn{road.}",
            "Aštrus posūkis kairėn po {dist}{road.}",
        )

        ManeuverType.SHARP_RIGHT -> listOf(
            "Po {dist} staigiai dešinėn{road.}",
            "Aštrus posūkis dešinėn po {dist}{road.}",
        )

        ManeuverType.UTURN -> listOf(
            "Po {dist} apsisukite.",
            "Apsisukimas po {dist}.",
        )

        ManeuverType.ROUNDABOUT -> listOf(
            "Po {dist} įvažiuokite į žiedą{road.}",
            "Žiedas po {dist}{road.}",
        )

        ManeuverType.MOTORWAY_EXIT -> listOf(
            "Po {dist} važiuokite į išvažiavimą{road.}",
            "Išvažiavimas po {dist}{road.}",
        )

        ManeuverType.LANE_CHANGE -> listOf(
            "Po {dist} keiskite juostą{road.}",
        )

        ManeuverType.MERGE -> listOf(
            "Po {dist} įsijunkite į srautą{road.}",
        )

        ManeuverType.FORK -> listOf(
            "Po {dist} kelio šakojimasis{road.}",
        )

        ManeuverType.COMPLEX_JUNCTION -> listOf(
            "Po {dist} sudėtinga sankryža{road.}",
        )

        ManeuverType.ARRIVE -> listOf(
            "Atvykote į tikslą!",
            "Esate vietoje!",
            "Tikslas pasiektas.",
        )

        ManeuverType.NONE,
        ManeuverType.STRAIGHT,
        ManeuverType.UNKNOWN -> emptyList()
    }
}
