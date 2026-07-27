package lt.sturmanas.bajeristas.voice.navigation

import lt.sturmanas.bajeristas.navigation.ManeuverType

/**
 * Deterministic Lithuanian phrase formatter for Kentas-style navigation.
 */
class KentasNavigationPhraseFormatter {

    enum class SpeechStage {
        FAR,       // 700-1000m
        MEDIUM,    // 250-400m
        IMMEDIATE, // 40-120m
        ARRIVED    // Destination reached
    }

    /**
     * Formats a maneuver into a Kentas-style phrase.
     */
    fun format(
        maneuver: ManeuverType,
        distanceMeters: Int,
        stage: SpeechStage,
        variantIndex: Int = 0,
        exitNumber: Int? = null
    ): String {
        return when (stage) {
            SpeechStage.FAR -> formatFar(maneuver, distanceMeters, variantIndex % 3)
            SpeechStage.MEDIUM -> formatMedium(maneuver, distanceMeters, variantIndex % 3, exitNumber)
            SpeechStage.IMMEDIATE -> formatImmediate(maneuver, variantIndex % 2, exitNumber)
            SpeechStage.ARRIVED -> formatArrived(variantIndex % 2)
        }
    }

    private fun formatFar(maneuver: ManeuverType, dist: Int, variant: Int): String {
        val d = roundDistance(dist)
        return when (maneuver) {
            ManeuverType.STRAIGHT -> listOf(
                "Pradedam, važiuojam tiesiai dar $d metrų, kapitone.",
                "Važiuojam tiesiai, dar $d metrų, nepasimesk.",
                "Laikom kursą tiesiai po $d metrų matysim."
            )[variant]
            ManeuverType.TURN_LEFT -> listOf(
                "Kapitone, už $d metrų sukam kairėn.",
                "Po $d metrų rikiuokis kairėn.",
                "Už $d metrų suksim kairėn, negrybaujam."
            )[variant]
            ManeuverType.TURN_RIGHT -> listOf(
                "Kapitone, už $d metrų sukam dešinėn.",
                "Po $d metrų reikės sukti dešinėn.",
                "Už $d metrų sukam dešinėn, nepramiegok."
            )[variant]
            ManeuverType.UTURN -> "Už $d metrų apsisukam."
            ManeuverType.ROUNDABOUT -> listOf(
                "Už $d metrų bus žiedas.",
                "Po $d metrų žiedas, nepasimesk.",
                "Ruoškis žiedui už $d metrų."
            )[variant]
            ManeuverType.ARRIVE -> "Už $d metrų būsim vietoj."
            else -> "Už $d metrų darysim manevrą."
        }
    }

    private fun formatMedium(maneuver: ManeuverType, dist: Int, variant: Int, exitNumber: Int?): String {
        val d = roundDistance(dist)
        return when (maneuver) {
            ManeuverType.TURN_LEFT -> listOf(
                "Už $d metrų sukam kairėn.",
                "Ruoškis kairėn po $d metrų.",
                "Sukam kairėn už $d metrų, kapitone."
            )[variant]
            ManeuverType.TURN_RIGHT -> listOf(
                "Už $d metrų sukam dešinėn.",
                "Ruoškis dešinėn po $d metrų.",
                "Sukam dešinėn už $d metrų, kapitone."
            )[variant]
            ManeuverType.UTURN -> "Apsisukam už $d metrų."
            ManeuverType.ROUNDABOUT -> {
                val exitText = exitNumberToLithuanian(exitNumber)
                listOf(
                    "Žiede imam $exitText išvažiavimą.",
                    "Artėja žiedas, suksim per $exitText.",
                    "Lendam į žiedą ir imam $exitText."
                )[variant]
            }
            else -> "Už $d metrų manevras."
        }
    }

    private fun formatImmediate(maneuver: ManeuverType, variant: Int, exitNumber: Int?): String {
        return when (maneuver) {
            ManeuverType.STRAIGHT -> listOf(
                "Važiuojam tiesiai, kapitone.",
                "Laikom kursą tiesiai."
            )[variant]
            ManeuverType.TURN_LEFT -> listOf("Dabar kairėn.", "Sukam kairėn.")[variant]
            ManeuverType.TURN_RIGHT -> listOf("Dabar dešinėn.", "Sukam dešinėn.")[variant]
            ManeuverType.UTURN -> "Apsisukam dabar."
            ManeuverType.ROUNDABOUT -> {
                val exitText = exitNumberToLithuanian(exitNumber)
                listOf("Lendam per $exitText.", "Dabar per $exitText.")[variant]
            }
            ManeuverType.ARRIVE -> "Atvažiavom."
            else -> "Dabar."
        }
    }

    private fun formatArrived(variant: Int): String {
        return listOf(
            "Atvažiavom. Gali atsegti nervus.",
            "Tikslas pasiektas, kapitone."
        )[variant]
    }

    private fun roundDistance(dist: Int): Int {
        return if (dist >= 1000) (dist / 100) * 100 else (dist / 50) * 50
    }

    private fun exitNumberToLithuanian(num: Int?): String = when (num) {
        1 -> "pirmą"
        2 -> "antrą"
        3 -> "trečią"
        4 -> "ketvirtą"
        5 -> "penktą"
        else -> "reikiamą"
    }
}
