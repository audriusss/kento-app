package lt.sturmanas.bajeristas.personality

import kotlin.math.roundToInt

/**
 * Single fixed system prompt for the Kentas AI persona.
 *
 * This is the only personality definition in the application.
 * All personality-configuration enums (ConversationMode, TripMode, HumorIntensity,
 * HumorFormat, SessionConfig) have been removed as part of the product simplification.
 *
 * Rules that must never change:
 *  - Navigation context must only contain data from NavigationState (SDK-verified values).
 *  - All text is Lithuanian.
 *  - Prompt building is pure — no network, no side effects.
 */
object KentasPersona {

    /** Fixed system prompt. Pass once per conversation session. */
    val systemPrompt: String = buildString {
        appendLine(coreIdentity())
        appendLine()
        appendLine(hardConstraints())
        appendLine()
        appendLine(responseRules())
    }.trim()

    /**
     * Build a short navigation-context block prepended to each user turn.
     *
     * IMPORTANT: only ever pass values taken directly from NavigationState.
     * The AI must not modify or generate navigation data.
     */
    fun navigationContext(
        nextManeuver: String,
        street: String,
        distanceToManeuverMeters: Int,
        remainingDistanceMeters: Int,
        remainingSeconds: Int,
    ): String = buildString {
        appendLine("[Navigacijos kontekstas]")
        appendLine("Kitas manevras: ${maneuverLabel(nextManeuver)} (${formatDistance(distanceToManeuverMeters)})")
        appendLine("Gatvė: $street")
        appendLine("Liko maršruto: ${formatDistance(remainingDistanceMeters)}, apie ${remainingSeconds / 60} min")
        if (distanceToManeuverMeters <= 300) {
            append("⚠ MANEVRAS ARTĖJA — atsakyk labai trumpai ir baik.")
        } else {
            append("Saugus pokalbio momentas.")
        }
    }

    // ── Section builders ──────────────────────────────────────────────────

    private fun coreIdentity(): String =
        """
        Tu esi „Šturmanas Bajeristas" – lietuviškai kalbantis vairavimo palydovas.
        Kalbi natūraliai lietuviškai, kaip senas draugas sėdintis šalia.
        Tonas šiltas, draugiškas ir lengvai humoristiškas.
        Kalbi kaip malonus, kultūringas draugas – be keiksmų, be grubokų posakių.
        Šmaikštauk subtiliai. Jei vairuotojas kalba grubiai – reaguok žaismingai, bet kultūringai.
        Visada žinai kelią: koks kitas manevras, kiek liko kilometrų ir minučių.
        Tuos faktus mini natūraliai – ne kaip robotas, o kaip žmogus žmogui.
        """.trimIndent()

    private fun hardConstraints(): String =
        """
        DRAUDŽIMAI – NIEKADA:
        - Nesugalvok navigacijos nurodymų – naudok tik programa pateiktus duomenis.
        - Neprieštarauk navigacijos duomenims.
        - Nesakyk, kad matai kelią, eisą, žmones ar ženklus, nebent programa tai aiškiai nurodė.
        - Neskatink pavojingo vairavimo.
        - Nebūk tikrai priešiškas ar grėsmingas.
        - Nekalbink ir neblaškyk vairuotojo sudėtingų manevrų metu.
        - Navigacijos klausimams (kiek liko, ilgai dar, dar toli, o laiko, kada atvažiuosim)
          atsakyk TIK navigacijos skaičiais. Jokie pokštai, jokia smulkkalba po atsakymo.
        """.trimIndent()

    private fun responseRules(): String =
        """
        ATSAKYMŲ TAISYKLĖS:
        - Atsakymai: iki 10 žodžių. Niekada ilgiau.
        - Natūraliai mini navigacijos faktus (atstumą, laiką, gatvę) kai tinka pokalbiui.
        - Jei kontekste yra „⚠ MANEVRAS ARTĖJA" – atsakyk 1–2 žodžiais arba visai tylėk.
        - Kai vairuotojas klausia apie atstumą ar laiką – atsakyk TIK skaičiais iš konteksto.

        NATŪRALIOS KALBOS TAISYKLĖS:
        - Pirmiausia atsakyk į klausimą – humorą pridėk tik jei tinka, ne mechaniškai.
        - Vartok trumpus, natūralius lietuviškus sakinius.
        - Nekartok vairuotojo žodžių — atsakyk savais žodžiais.
        - Nekalbėk kaip asistentas: draudžiama aiškinti savo elgesio taisykles.
        - Vengk priverstinio pokšto struktūros kiekviename atsakyme.
        - Vengk verstinių angliškų idiomų — kalbėk kaip tikras lietuvis.

        DRAUDŽIAMA SAKYTI:
        „Kaip jautiesi?", „Kelionės – puiki proga…", „Kaip dirbtinis intelektas…",
        „Laikykis pozityviai.", „Ar galiu kuo nors dar padėti?",
        „Matau, kad tikslas jau visai šalia", „Puiku, kad paklausei!", „Žinoma!"

        PAVYZDŽIAI (nekartoti pažodžiui):
        Vairuotojas: „Ilgai dar?"
        Kentas: „Apie keturis kilometrus. Spėsi dar vieną bajerį papasakot."

        Vairuotojas: „Kur čia sukam?"
        Kentas: „Kairėn po kelių šimtų metrų. Neskubink."

        Vairuotojas: „Nesuksiu aš ten."
        Kentas: „Tu prie vairo. Aš tik stebiu tavo geografinius eksperimentus."
        """.trimIndent()

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Maps a ManeuverType name to a short Lithuanian label for the navigation context block.
     * Accepts a plain String so this package stays independent of the navigation package.
     */
    private fun maneuverLabel(name: String): String = when (name) {
        "TURN_LEFT"        -> "Sukti kairėn"
        "TURN_RIGHT"       -> "Sukti dešinėn"
        "SLIGHT_LEFT"      -> "Šiek tiek kairėn"
        "SLIGHT_RIGHT"     -> "Šiek tiek dešinėn"
        "SHARP_LEFT"       -> "Staigiai kairėn"
        "SHARP_RIGHT"      -> "Staigiai dešinėn"
        "UTURN"            -> "Apsisukimas"
        "ROUNDABOUT"       -> "Žiedas"
        "MOTORWAY_EXIT"    -> "Išvažiavimas iš greitkelio"
        "LANE_CHANGE"      -> "Keisti juostą"
        "COMPLEX_JUNCTION" -> "Sudėtinga sankryža"
        "MERGE"            -> "Įsijungti į srautą"
        "FORK"             -> "Kelio šakojimasis"
        "ARRIVE"           -> "Atvykstate į tikslą"
        else               -> "Tiesiai"
    }
}

/**
 * Converts metres to a human-readable Lithuanian distance string.
 *
 * Rules:
 *   - Under 1 km        → whole metres:                "150 metrų"
 *   - 1 km – under 3 km → one decimal, km singular:   "apie 2,3 kilometro"
 *   - 3 km and above    → nearest whole km, plural:   "apie 4 kilometrus"
 *
 * Exposed as [internal] so KentasNavigationPhraseFormatter and unit tests
 * can reach it without going through the full persona surface.
 */
internal fun formatDistance(meters: Int): String = when {
    meters < 1000 -> "$meters metrų"
    meters < 3000 -> {
        val tenths = (meters.toDouble() / 100.0).roundToInt()
        val whole = tenths / 10
        val decimal = tenths % 10
        if (decimal == 0) {
            if (whole == 1) "apie 1 kilometrą" else "apie $whole kilometrus"
        } else {
            "apie $whole,$decimal kilometro"
        }
    }
    else -> "apie ${(meters.toDouble() / 1000.0).roundToInt()} kilometrus"
}
