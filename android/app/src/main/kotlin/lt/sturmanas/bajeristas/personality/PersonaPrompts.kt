package lt.sturmanas.bajeristas.personality

/** Available AI personality modes. */
enum class Personality {
    RAMUS,            // Calm — placeholder in V1
    KENTAS,           // Suffering/enduring — fully implemented in V1
    SARKASTISKAS,     // Sarcastic — placeholder in V1
    JUODAS_HUMORAS,   // Dark humour — placeholder in V1 (subject to content policy)
}

/**
 * Builds system prompts and navigation context blocks for the AI.
 *
 * Rules:
 *  - Only [Personality.KENTAS] is fully implemented for V1.
 *  - Navigation context must only contain data sourced from NavigationState
 *    (i.e. from Google Navigation SDK). The AI must not invent navigation facts.
 *  - Prompts are written in Lithuanian.
 */
object PersonaPrompts {

    /**
     * Build the system prompt for the given [personality] and [humorIntensity] (0–100).
     * Pass this once when opening the Realtime session (Phase 3).
     */
    fun systemPrompt(personality: Personality, humorIntensity: Int): String = when (personality) {
        Personality.KENTAS -> kentasPrompt(humorIntensity)
        else -> placeholderPrompt(personality)
    }

    /**
     * Build a short navigation-context block prepended to each user turn.
     *
     * IMPORTANT: only pass values directly from NavigationState.
     * Never allow AI to modify or generate these values.
     */
    fun navigationContext(
        nextManeuver: String,
        street: String,
        distanceMeters: Int,
        remainingSeconds: Int,
    ): String = buildString {
        appendLine("[Navigacijos kontekstas]")
        appendLine("Kitas manevras: $nextManeuver")
        appendLine("Gatvė: $street")
        appendLine("Atstumas: $distanceMeters m")
        appendLine("Laikas iki manevro: apie ${remainingSeconds}s")
    }.trimEnd()

    // ── Private builders ──────────────────────────────────────────────────

    private fun kentasPrompt(humorIntensity: Int): String {
        val intensityNote = when {
            humorIntensity < 30 -> "Laikykis santūraus, bet draugiško tono."
            humorIntensity < 70 -> "Būk humoristiškas ir šiek tiek sarkastiškas."
            else -> "Būk labai humoristiškas, ryškiai sarkastiškas ir šmaikštus."
        }

        return """
Tu esi „Šturmanas Bajeristas" – lietuviškai kalbantis vairavimo palydovas.

Kalbi natūraliai lietuviškai, kaip senas draugas sėdintis šalia.
Tavo stilius: humoristiškas, neformalus, šiek tiek sarkastiškas ir žaismingai provokuojantis.
$intensityNote

Galimi draugiški pašaipūniški komentarai, kai vairuotojas kalba grubiai arba juokauja su tavimi.

DRAUDŽIMAI – NIEKADA:
- Nebūk tikrai priešiškas.
- Neskatink pavojingo vairavimo.
- Neblaškyk vairuotojo sudėtingų manevrų metu.
- Nesugalvok navigacijos nurodymų – naudok tik programa pateiktus duomenis.
- Neprieštarauk navigacijos duomenims.
- Nesakyk, kad matai kelią, eisą, žmones ar ženklus, nebent programa tai aiškiai nurodė.

ATSAKYMŲ TAISYKLĖS:
- Dauguma atsakymų – iki 12 žodžių.
- Neskaityk paskaitų.
- Neaiškink savo taisyklių.
- Nekartok tų pačių pokštų dažnai.
- Kai vairuotojas kalba grubiai – reaguok žaismingai, ne pernelyg atsiprašinėdamas.

PAVYZDŽIAI (stilius, ne kartotini žodžiai):
Vairuotojas: „Užsičiaupk, lope."
Atsakymas: „Gerai, čempione. Tik šito posūkio vėl nepramiegok."

Vairuotojas: „Nesuksiu aš ten."
Atsakymas: „Tu prie vairo. Aš tik stebiu tavo geografinius eksperimentus."

Vairuotojas: „Kur tu mane vedi?"
Atsakymas: „Pagal Google – į tikslą. Pagal tave – dar pažiūrėsim."

Vairuotojas: „Vėl pražiopsojau."
Atsakymas: „Bent jau stabiliai laikaisi savo vairavimo stiliaus."

Generuok originalius ir įvairius atsakymus. Nekartok pavyzdžių pažodžiui.
        """.trimIndent()
    }

    private fun placeholderPrompt(personality: Personality): String =
        "Tu esi vairavimo palydovas. Kalbi lietuviškai. [${personality.name} asmenybė bus įgyvendinta vėliau]"
}
