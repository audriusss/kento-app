package lt.sturmanas.bajeristas.navigation

import android.util.Log

/**
 * Converts natural Lithuanian destination text into the best possible navigation query.
 */
object DestinationResolver {

    private const val TAG = "KentasDestination"

    // ── Street name stem → canonical expanded name (without number) ────────

    private val STREET_EXPANSIONS = mapOf(
        // Klaipėda
        "taikos"          to "Taikos prospektas",
        "minijos"         to "Minijos gatvė",
        "liepų"           to "Liepų alėja",
        "liepu"           to "Liepų alėja",
        "šilutės"         to "Šilutės plentas",
        "silutes"         to "Šilutės plentas",
        "baltijos"        to "Baltijos prospektas",
        "h. manto"        to "Herkaus Manto gatvė",
        "herkaus manto"   to "Herkaus Manto gatvė",
        "sausio"          to "Sausio 13-osios gatvė",
        "danės"           to "Danės gatvė",
        "danes"           to "Danės gatvė",
        "melnragės"       to "Melnragės gatvė",
        "melnrages"       to "Melnragės gatvė",
        // Vilnius
        "gedimino"        to "Gedimino prospektas",
        "konstitucijos"   to "Konstitucijos prospektas",
        "žirmūnų"         to "Žirmūnų gatvė",
        "zirmunu"         to "Žirmūnų gatvė",
        "ukmergės"        to "Ukmergės gatvė",
        "ukmerges"        to "Ukmergės gatvė",
        "laisvės"         to "Laisvės prospektas",
        "laisves"         to "Laisvės prospektas",
        "vilniaus"        to "Vilniaus gatvė",
        "saltoniškių"     to "Saltoniškių gatvė",
        "saltoniskiu"     to "Saltoniškių gatvė",
        "ozo"             to "Ozo gatvė",
        "žygio"           to "Žygio gatvė",
        "zygio"           to "Žygio gatvė",
        // Kaunas
        "savanorių"       to "Savanorių prospektas",
        "savanoriu"       to "Savanorių prospektas",
        "jonavos"         to "Jonavos gatvė",
        "partizanų"       to "Partizanų gatvė",
        "partizanu"       to "Partizanų gatvė",
        "nemuno"          to "Nemuno gatvė",
        "žalgirio"        to "Žalgirio gatvė",
        "zalgirio"        to "Žalgirio gatvė",
        "kauno"           to "Kauno gatvė",
    )

    private val STREET_TYPE_REGEX = Regex(
        """\b(gatvė|gatvę|gatve|prospektas|prospektą|prospekta|alėja|alėją|aleja|aikštė|aikštę|kelias|kelio|plentas|plentą|bulvaras|krantinė|skveras)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val STREET_NUMBER_REGEX = Regex(
        """^([A-ZĄČĘĖĮŠŲŪŽa-ząčęėįšųūž][^,\d]*?)\s+(\d+(?:[a-zA-ZĄČĘĖĮŠŲŪŽąčęėįšųūž]|[-\/]\d+)?)\s*$""",
    )

    private val STREET_ABBREV_REGEX = Regex(
        """\b(?:g|pr|al|pl|blv|krant)\.""",
        RegexOption.IGNORE_CASE,
    )

    private val COORD_REGEX = Regex(
        """^-?\d{1,3}\.?\d*\s*,\s*-?\d{1,3}\.?\d*$""",
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Resolve [rawText] into the best possible navigation query.
     */
    suspend fun resolve(
        rawText: String,
        currentLocality: String? = null,
    ): DestinationResolution {
        val trimmed = rawText.trim().trimEnd('.', ',', '!', '?').trim()
        val lower = trimmed.lowercase()

        Log.d(TAG, "resolve: raw='$trimmed' locality='$currentLocality'")

        if (trimmed.isBlank()) {
            return DestinationResolution.Failure("Tikslo adresas tuščias.")
        }

        if (COORD_REGEX.matches(lower.replace(" ", ""))) {
            return DestinationResolution.ExactAddress(trimmed)
        }

        if (trimmed.contains(",")) {
            return DestinationResolution.ExactAddress(trimmed)
        }

        val streetMatch = STREET_NUMBER_REGEX.find(trimmed)
        if (streetMatch != null) {
            val streetPart = streetMatch.groupValues[1].trim()
            val numberPart = streetMatch.groupValues[2].trim()
            val candidates = buildStreetCandidateQueries(streetPart, numberPart, currentLocality)
            return DestinationResolution.ExactAddress(candidates.first())
        }

        // ── Default: Treat as query ───────────────────────────────────────
        Log.d(TAG, "resolve: default fallback to PlaceSearch for '$trimmed'")
        return DestinationResolution.PlaceSearch(trimmed)
    }

    internal fun buildStreetCandidateQueries(
        streetPart: String,
        numberPart: String,
        locality: String?,
    ): List<String> {
        val suffix   = if (locality != null) ", $locality, Lithuania" else ", Lithuania"
        val stem     = streetPart.lowercase().trim()
        val expanded = STREET_EXPANSIONS[stem]

        return buildList {
            add("$streetPart $numberPart$suffix")

            if (expanded != null) {
                val abbrev: String? = when {
                    "prospektas" in expanded -> "$streetPart pr. $numberPart$suffix"
                    "alėja"      in expanded -> "$streetPart al. $numberPart$suffix"
                    "plentas"    in expanded -> "$streetPart pl. $numberPart$suffix"
                    "gatvė"      in expanded -> "$streetPart g. $numberPart$suffix"
                    else                     -> null
                }
                if (abbrev != null) add(abbrev)
                add("$expanded $numberPart$suffix")
            } else if (!STREET_TYPE_REGEX.containsMatchIn(streetPart) &&
                       !STREET_ABBREV_REGEX.containsMatchIn(streetPart)) {
                val genitive = genitiveForm(streetPart)
                if (genitive != null) {
                    add("$genitive g. $numberPart$suffix")
                }
                add("$streetPart g. $numberPart$suffix")
                add("$streetPart gatvė $numberPart$suffix")
            }
        }.distinct()
    }

    private fun genitiveForm(stem: String): String? =
        if (stem.endsWith("ė") || stem.endsWith("Ė")) "${stem}s" else null

    internal fun isStreetNumberQuery(text: String): Boolean =
        STREET_NUMBER_REGEX.matches(text.trim())
}
