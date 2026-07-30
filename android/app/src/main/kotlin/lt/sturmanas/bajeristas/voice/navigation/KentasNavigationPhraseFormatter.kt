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
 *
 * ## Anti-repetition
 * Each maneuver+stage key maintains a small LRU window of recently used
 * phrase indices.  [pickPhrase] avoids those indices so navigation sounds
 * different on every trip and no phrase repeats back-to-back.
 */
class KentasNavigationPhraseFormatter {

    enum class SpeechStage {
        FAR,       // 700–1000 m advance warning
        MEDIUM,    // 250–400 m preparation
        IMMEDIATE, // 0–120 m execute now
        ARRIVED,   // Destination reached
    }

    // ── Anti-repetition history ────────────────────────────────────────────

    /**
     * Maps a phrase-bank key (maneuver+stage) to a deque of recently used
     * phrase indices.  Window size = min(phrases.size - 1, 6) so there is
     * always at least one fresh phrase available even for small banks.
     */
    private val phraseHistory: HashMap<String, ArrayDeque<Int>> = HashMap()

    /**
     * Picks a phrase from [phrases] that was NOT used recently for [key].
     * Updates the history window before returning.
     */
    private fun <T> pickPhrase(key: String, phrases: List<T>): T {
        val recent = phraseHistory.getOrPut(key) { ArrayDeque() }
        val windowSize = minOf(phrases.size - 1, 6)
        val candidates = phrases.indices.filter { it !in recent }
        val idx = if (candidates.isEmpty()) phrases.indices.random() else candidates.random()
        recent.addLast(idx)
        while (recent.size > windowSize) recent.removeFirst()
        return phrases[idx]
    }

    /**
     * Format a maneuver into a Kentas-style Lithuanian TTS phrase.
     *
     * @param maneuver      Internal maneuver type.
     * @param distanceMeters Distance in metres to the maneuver.
     * @param stage         Which announcement stage this is.
     * @param variantIndex  Legacy parameter kept for call-site compatibility;
     *                      the history-aware picker supersedes it.
     * @param exitNumber    Roundabout exit number (1-based); null when unknown.
     */
    fun format(
        maneuver: ManeuverType,
        distanceMeters: Int,
        stage: SpeechStage,
        variantIndex: Int = 0,
        exitNumber: Int? = null,
    ): String = when (stage) {
        SpeechStage.FAR       -> formatFar(maneuver, distanceMeters)
        SpeechStage.MEDIUM    -> formatMedium(maneuver, distanceMeters, exitNumber)
        SpeechStage.IMMEDIATE -> formatImmediate(maneuver, exitNumber)
        SpeechStage.ARRIVED   -> formatArrived()
    }

    // ── FAR stage (700–1000 m) ─────────────────────────────────────────────

    private fun formatFar(maneuver: ManeuverType, dist: Int): String {
        val d = formatDistanceWords(dist)
        return when (maneuver) {
            ManeuverType.TURN_RIGHT -> pickPhrase("FAR_RIGHT", listOf(
                "Už $d suksim dešinėn.",
                "Ruoškis dešinėn — už $d.",
                "Už $d dešinė.",
                "Po $d — dešinė.",
                "Dešinė už $d, nepražiopsok.",
            ))
            ManeuverType.TURN_LEFT -> pickPhrase("FAR_LEFT", listOf(
                "Už $d suksim kairėn.",
                "Ruoškis kairėn — už $d.",
                "Už $d kairė.",
                "Po $d — kairė.",
                "Kairė už $d, nepražiopsok.",
            ))
            ManeuverType.SLIGHT_RIGHT -> "Už $d laikykis dešiniau."
            ManeuverType.SLIGHT_LEFT  -> "Už $d laikykis kairiau."
            ManeuverType.SHARP_RIGHT  -> pickPhrase("FAR_SHARP_RIGHT", listOf(
                "Už $d aštrus posūkis dešinėn.",
                "Už $d staigiai dešinėn — ruoškis.",
            ))
            ManeuverType.SHARP_LEFT  -> pickPhrase("FAR_SHARP_LEFT", listOf(
                "Už $d aštrus posūkis kairėn.",
                "Už $d staigiai kairėn — ruoškis.",
            ))
            ManeuverType.UTURN -> pickPhrase("FAR_UTURN", listOf(
                "Už $d reikės apsisukti.",
                "Po $d — apsisukam.",
            ))
            ManeuverType.ROUNDABOUT -> pickPhrase("FAR_ROUNDABOUT", listOf(
                "Už $d bus žiedas.",
                "Po $d — žiedas.",
                "Žiedas už $d.",
            ))
            ManeuverType.MOTORWAY_EXIT -> "Už $d nuvažiuojam nuo magistralės."
            ManeuverType.MERGE         -> "Už $d jungiamės į eismą."
            ManeuverType.FORK          -> "Už $d kelias šakojasi."
            ManeuverType.ARRIVE        -> "Už $d būsim vietoj."
            else -> ""
        }
    }

    // ── MEDIUM stage (250–400 m) ───────────────────────────────────────────

    private fun formatMedium(maneuver: ManeuverType, dist: Int, exitNumber: Int?): String {
        val d = formatDistanceWords(dist)
        return when (maneuver) {
            ManeuverType.TURN_RIGHT -> pickPhrase("MED_RIGHT", listOf(
                "Tuoj suksim dešinėn, už $d.",
                "Dešinėn už $d.",
                "Ruoškis — dešinė už $d.",
                "Netrukus dešinė, už $d.",
            ))
            ManeuverType.TURN_LEFT -> pickPhrase("MED_LEFT", listOf(
                "Tuoj suksim kairėn, už $d.",
                "Kairėn už $d.",
                "Ruoškis — kairė už $d.",
                "Netrukus kairė, už $d.",
            ))
            ManeuverType.SLIGHT_RIGHT -> "Švelniai dešinėn už $d."
            ManeuverType.SLIGHT_LEFT  -> "Švelniai kairėn už $d."
            ManeuverType.SHARP_RIGHT  -> "Aštrus posūkis dešinėn už $d."
            ManeuverType.SHARP_LEFT   -> "Aštrus posūkis kairėn už $d."
            ManeuverType.UTURN -> "Apsisukam už $d."
            ManeuverType.ROUNDABOUT -> {
                val exit = exitOrdinal(exitNumber)
                pickPhrase("MED_ROUNDABOUT", listOf(
                    "Žiede imk $exit išvažiavimą.",
                    "Artėja žiedas — $exit išvažiavimas.",
                    "Žiedas už $d — $exit.",
                ))
            }
            ManeuverType.MOTORWAY_EXIT -> "Nuvažiuojam nuo magistralės už $d."
            ManeuverType.MERGE         -> "Jungiamės į eismą už $d."
            ManeuverType.FORK          -> "Kelias šakojasi už $d."
            else -> ""
        }
    }

    // ── IMMEDIATE stage (0–120 m) — 20 variants per maneuver ──────────────

    private fun formatImmediate(maneuver: ManeuverType, exitNumber: Int?): String {
        return when (maneuver) {

            ManeuverType.STRAIGHT -> pickPhrase("IMM_STRAIGHT", listOf(
                "Tiesiai varom.",
                "Laikykis tiesiai.",
                "Tiesiai.",
                "Važiuojam tiesiai.",
                "Vis dar tiesiai.",
                "Tiesiai ir tiesiai.",
                "Nei kairėn, nei dešinėn.",
                "Tiesiog priekin.",
                "Kelias tiesus.",
                "Tiesiai kol kas.",
                "Traukiam tiesiai.",
                "Tiesiai, kaip strėlė.",
                "Tiesiai važiuojam.",
                "Priekin.",
                "Tiesiai dar biškį.",
                "Va, tiesiai.",
                "Laikykis — tiesiai.",
                "Tiesiai — čia paprasta.",
                "Eina tiesiai.",
                "Nesukam.",
            ))

            ManeuverType.TURN_RIGHT -> pickPhrase("IMM_RIGHT", listOf(
                "Va dabar dešinėn.",
                "Metam dešinę.",
                "Imam dešinį.",
                "Šitam posūky dešinė.",
                "Dėk dešinę.",
                "Nu dabar dešinė.",
                "Ruoškis, dešinė.",
                "Va čia.",
                "Dešinė jau.",
                "Nepražiopsok dešinės.",
                "Čia dešinė.",
                "Suk dešinėn.",
                "Dabar dešinė.",
                "Dešinę, prašom.",
                "Va, šita dešinė.",
                "Tau dešinėn.",
                "Opa, dešinė.",
                "Šičia dešinėn.",
                "Dešinę.",
                "Dešinė — va čia.",
            ))

            ManeuverType.TURN_LEFT -> pickPhrase("IMM_LEFT", listOf(
                "Va dabar kairėn.",
                "Metam kairę.",
                "Imam kairį.",
                "Šitam posūky kairė.",
                "Dėk kairę.",
                "Nu dabar kairė.",
                "Ruoškis, kairė.",
                "Va čia kairėn.",
                "Kairė jau.",
                "Nepražiopsok kairės.",
                "Čia kairė.",
                "Suk kairėn.",
                "Dabar kairė.",
                "Kairę, prašom.",
                "Va, šita kairė.",
                "Tau kairėn.",
                "Opa, kairė.",
                "Šičia kairėn.",
                "Kairę.",
                "Kairė — va čia.",
            ))

            ManeuverType.SLIGHT_RIGHT -> pickPhrase("IMM_SLIGHT_RIGHT", listOf(
                "Laikykis dešiniau.",
                "Švelniai dešinėn.",
                "Biški dešiniau.",
                "Dešiniau laikykis.",
                "Nesukink — tik biški dešiniau.",
                "Va dešiniau.",
                "Dešiniau šiek tiek.",
                "Krypk dešiniau.",
                "Dešiniau.",
                "Lenkis dešiniau.",
                "Tik biški į dešinę.",
                "Dešiniau nepastebimai.",
                "Dešiniau — nesmarkiai.",
                "Dešiniau eina kelias.",
                "Laikykis dešinės pusės.",
                "Dešiniau truputį.",
                "Va, dešiniau.",
                "Nesmarkiai dešinėn.",
                "Kelias lenkiasi dešiniau.",
                "Dešiniau šliaužiam.",
            ))

            ManeuverType.SLIGHT_LEFT -> pickPhrase("IMM_SLIGHT_LEFT", listOf(
                "Laikykis kairiau.",
                "Švelniai kairėn.",
                "Biški kairiau.",
                "Kairiau laikykis.",
                "Nesukink — tik biški kairiau.",
                "Va kairiau.",
                "Kairiau šiek tiek.",
                "Krypk kairiau.",
                "Kairiau.",
                "Lenkis kairiau.",
                "Tik biški į kairę.",
                "Kairiau nepastebimai.",
                "Kairiau — nesmarkiai.",
                "Kairiau eina kelias.",
                "Laikykis kairės pusės.",
                "Kairiau truputį.",
                "Va, kairiau.",
                "Nesmarkiai kairėn.",
                "Kelias lenkiasi kairiau.",
                "Kairiau šliaužiam.",
            ))

            ManeuverType.SHARP_RIGHT -> pickPhrase("IMM_SHARP_RIGHT", listOf(
                "Staigiai dešinėn.",
                "Aštrus posūkis dešinėn.",
                "Stipriai dešinėn.",
                "Smarkiai dešinėn — ruoškis.",
                "Čia smarkus dešinys.",
                "Dešinėn — aštrokai.",
                "Kietas dešinys — imk.",
                "Smarkus posūkis dešinėn.",
                "Dešinėn — stipriai.",
                "Nesmulkink — dešinėn.",
                "Pilnas dešinys.",
                "Stiprus dešinys čia.",
                "Imk pilną dešinę.",
                "Smarkiai suk dešinėn.",
                "Aštri dešinė — čia.",
                "Posūkis dešinėn — kietas.",
                "Pilnai dešinėn.",
                "Gerai suk dešinėn.",
                "Dešinys stiprus — suk.",
                "Nepamiršk — stipri dešinė.",
            ))

            ManeuverType.SHARP_LEFT -> pickPhrase("IMM_SHARP_LEFT", listOf(
                "Staigiai kairėn.",
                "Aštrus posūkis kairėn.",
                "Stipriai kairėn.",
                "Smarkiai kairėn — ruoškis.",
                "Čia smarkus kairys.",
                "Kairėn — aštrokai.",
                "Kietas kairys — imk.",
                "Smarkus posūkis kairėn.",
                "Kairėn — stipriai.",
                "Nesmulkink — kairėn.",
                "Pilnas kairys.",
                "Stiprus kairys čia.",
                "Imk pilną kairę.",
                "Smarkiai suk kairėn.",
                "Aštri kairė — čia.",
                "Posūkis kairėn — kietas.",
                "Pilnai kairėn.",
                "Gerai suk kairėn.",
                "Kairys stiprus — suk.",
                "Nepamiršk — stipri kairė.",
            ))

            ManeuverType.UTURN -> pickPhrase("IMM_UTURN", listOf(
                "Kai bus galima, apsisuk.",
                "Apsisuk.",
                "Reikia apsisukti.",
                "Sukam atgal.",
                "Apsigręžiame.",
                "Apsisuk, kai bus tinkama vieta.",
                "Reikės apsisukimą padaryti.",
                "Atgal, apsisukam.",
                "Sukam šimtu aštuoniasdešimt.",
                "Grįžtam atgal — apsisuk.",
                "Eik atgal.",
                "Sukamės atgal.",
                "Apsisuk kur galima.",
                "U posūkis — apsisuk.",
                "Atgal važiuojam — apsisuk.",
                "Sukamės — atgal.",
                "Sukam atgal, kur galima.",
                "Apsigręžk kai bus galimybė.",
                "Atgal.",
                "Čia apsisukam.",
            ))

            ManeuverType.ROUNDABOUT -> {
                val exit = exitOrdinal(exitNumber)
                pickPhrase("IMM_ROUNDABOUT", listOf(
                    "Žiede imk $exit išvažiavimą.",
                    "$exit žiedo išvažiavimas — tavo.",
                    "Žiede — $exit.",
                    "Ratas, imk $exit.",
                    "Žiedas — $exit išvažiavimas.",
                    "Aplink ir $exit išeini.",
                    "Žiede stebėk $exit išvažiavimą.",
                    "Apvažiuoji ir $exit išeini.",
                    "Žiedas — nepraleisk $exit.",
                    "Ratas — $exit.",
                    "Žiede tavo — $exit.",
                    "Imk $exit žiedo.",
                    "Žiede $exit išvažiavimas.",
                    "Ratas priekyje, imk $exit.",
                    "Žiede $exit.",
                    "$exit išvažiavimas — tavo žiede.",
                    "Žiede lauk $exit.",
                    "Aplink — $exit.",
                    "Žiedas, $exit — tavo.",
                    "Ratas — $exit išeini.",
                ))
            }

            ManeuverType.MOTORWAY_EXIT -> pickPhrase("IMM_EXIT", listOf(
                "Nuvažiuojam nuo magistralės.",
                "Išvažiuojam iš magistralės.",
                "Čia mūsų išvažiavimas.",
                "Magistralė baigėsi — išvažiuojam.",
                "Nuvažiuojam.",
            ))

            ManeuverType.MERGE -> pickPhrase("IMM_MERGE", listOf(
                "Jungiamės į eismą.",
                "Liejamės į srautą.",
                "Jungiamės.",
                "Prisijungiam prie eismo.",
                "Į srautą.",
            ))

            ManeuverType.FORK -> pickPhrase("IMM_FORK", listOf(
                "Kelias šakojasi — rink tinkamą pusę.",
                "Šakotis — stebėk kryptį.",
                "Kelias šakojasi.",
                "Čia šakojasi — rink.",
                "Šaka — stebėk.",
            ))

            ManeuverType.ARRIVE -> "Atvažiavom."

            else -> ""
        }
    }

    // ── ARRIVED ───────────────────────────────────────────────────────────

    private fun formatArrived(): String = pickPhrase("ARRIVED", listOf(
        "Nu va, privažiavom.",
        "Esam vietoj.",
        "Va čia ir reikėjo.",
        "Misija baigta.",
        "Štai ir finišas.",
        "Darbas padarytas.",
        "Privažiavom.",
        "Nu ką, atvarėm.",
        "Va čia ir yra.",
        "Čia ir reikėjo.",
        "Štai ir vietoj.",
        "Atvažiavom. Gali atsegti nervus.",
        "Tiksliai vietoj.",
        "Va, atvažiavom.",
        "Nuvarėm.",
        "Štai ir tikslas.",
        "Esam čia.",
        "Vietoj, kaip sakiau.",
        "Čia ir reikėjo būti.",
        "Va — finišas.",
    ))

    /**
     * Returns a random rerouting comment, avoiding recent repeats.
     * Called by [lt.sturmanas.bajeristas.voice.navigation.TrafficEventMonitor]
     * when the SDK signals an active reroute.
     */
    fun formatRerouting(): String = pickPhrase("REROUTING", listOf(
        "Nieko tokio, randam kitą kelią.",
        "Performuojam maršrutą — palaukit.",
        "Kitas kelias — jau ieškome.",
        "Na, maršrutas keičiasi.",
        "Keičiam planą.",
        "Apsiskaičiuojam naują kelią.",
        "Maršrutas perskaičiuojamas.",
        "Kelias keičiasi — nieko baisaus.",
        "Randam kitą išeitį.",
        "Jau žiūrim kur sukti.",
        "Pakeisim maršrutą, nieko tokio.",
        "Kitas planas — radome.",
        "Ieškoma geresnio kelio.",
        "Performuojam.",
        "Maršrutas kinta.",
        "Keičiam kursą.",
        "Ieškome kelio.",
        "Naujas kelias — jau skaičiuojam.",
        "Apsiskaičiuojam.",
        "Kelias radosi — kitoks.",
    ))

    // ── Distance formatting ────────────────────────────────────────────────

    /**
     * Converts a distance in metres to a natural Lithuanian phrase fragment
     * like "šimto metrų" or "dviejų kilometrų".
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
