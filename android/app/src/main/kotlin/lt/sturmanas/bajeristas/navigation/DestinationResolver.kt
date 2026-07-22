package lt.sturmanas.bajeristas.navigation

import android.util.Log

/**
 * Converts natural Lithuanian destination text into the best possible navigation query.
 *
 * Sits between [lt.sturmanas.bajeristas.voice.VoiceCommandParser] and
 * [NavigationController.startNavigation]. Pure deterministic logic — no network
 * calls, no Android context required beyond Log. Fully testable on the JVM.
 *
 * ## Resolution order
 *
 * A. Saved-place alias       "namai" / "darbas" → stored address
 * B. Coordinate pair         "54.68,25.27" → ExactAddress unchanged
 * B2. Contains comma          "Taikos 61, Klaipėda" → ExactAddress unchanged
 * F. City-centre shorthand   "centras" → "centras, <locality>"
 * E. Category phrase         "degalinė", "kavos" → PlaceSearch
 * D. Known brand / POI       "Akropolis", "Maxima" → PlaceSearch
 * C. Street + number         "Taikos 61" → "Taikos prospektas 61[, locality]"
 * G. Multi-word fallback     2+ words → PlaceSearch [with locality]
 * H. Failure                 single unknown word with no context
 *
 * Logging uses the `KentasDestination` Logcat tag.
 */
object DestinationResolver {

    private const val TAG = "KentasDestination"

    // ── Saved-place alias triggers ──────────────────────────────────────────

    private val HOME_ALIASES = setOf(
        "namai", "namo", "namus", "namie", "į namus", "namų",
    )
    private val WORK_ALIASES = setOf(
        "darbas", "į darbą", "darbą", "darba", "darbui", "darbe",
    )

    // ── City-centre shorthands ─────────────────────────────────────────────

    private val CENTRE_ALIASES = setOf(
        "centras", "į centrą", "centro", "centra",
        "miesto centras", "centre",
    )

    // ── Street name stem → canonical expanded name (without number) ────────
    // Keys: lowercase, diacritics preserved where possible but no-diacritic
    // fallbacks are included so SpeechRecognizer omissions are handled too.

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

    // ── Street-type suffix detection ───────────────────────────────────────
    // If the street part already contains one of these, skip expansion.

    private val STREET_TYPE_REGEX = Regex(
        """\b(gatvė|gatvę|gatve|prospektas|prospektą|prospekta|alėja|alėją|aleja|aikštė|aikštę|kelias|kelio|plentas|plentą|bulvaras|krantinė|skveras)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── Street + number pattern ────────────────────────────────────────────
    // Matches: one or more words starting with a capital + whitespace + number[letter]
    // Requires no comma (comma means city is already specified → handled by B2).

    private val STREET_NUMBER_REGEX = Regex(
        """^([A-ZĄČĘĖĮŠŲŪŽ][^,\d]*?)\s+(\d+[a-zA-Z]?)\s*$""",
    )

    // ── Coordinate pair ───────────────────────────────────────────────────

    private val COORD_REGEX = Regex(
        """^-?\d{1,3}\.?\d*\s*,\s*-?\d{1,3}\.?\d*$""",
    )

    // ── Known brands and named POIs ───────────────────────────────────────
    // Lowercase substrings. Checked via String.contains.

    private val BRAND_KEYWORDS = listOf(
        "akropolis", "akropoli",
        "ozas", "mega", "panorama", "forum palace",
        "maxima", "lidl", "rimi", "iki", "norfa", "senukai",
        "barbora", "ikea", "spar", "moki",
        "mcdonalds", "mcdonald", "kfc", "hesburger",
        "coffee inn", "starbucks",
        "telia", "tele2", "bitė", "bite",
        "swedbank", "seb", "luminor", "citadele",
        "primark", "zara", "reserved",
        "žuvininkystės", "zuvininkystes",
    )

    // ── Category keywords → canonical search term ─────────────────────────
    // Keys: lowercase substrings the user might say (with or without diacritics).

    private val CATEGORY_KEYWORDS = mapOf(
        "degalinė"        to "degalinė",
        "degaline"        to "degalinė",
        "benzinė"         to "degalinė",
        "benzine"         to "degalinė",
        "kavinė"          to "kavinė",
        "kavine"          to "kavinė",
        "kavos"           to "kavinė",
        "kava"            to "kavinė",
        "kavą"            to "kavinė",
        "restoranas"      to "restoranas",
        "restorana"       to "restoranas",
        "restoran"        to "restoranas",
        "valgykla"        to "valgykla",
        "parduotuvė"      to "parduotuvė",
        "parduotuve"      to "parduotuvė",
        "parkingas"       to "parkingas",
        "parkinga"        to "parkingas",
        "parking"         to "parkingas",
        "stovėjimo"       to "parkingas",
        "stovejimo"       to "parkingas",
        "vaistinė"        to "vaistinė",
        "vaistine"        to "vaistinė",
        "ligoninė"        to "ligoninė",
        "ligonine"        to "ligoninė",
        "bankomatas"      to "bankomatas",
        "bankomat"        to "bankomatas",
        "viešbutis"       to "viešbutis",
        "viesbutis"       to "viešbutis",
        "mokykla"         to "mokykla",
        "darželis"        to "darželis",
        "darzel"          to "darželis",
        "paštas"          to "paštas",
        "pastas"          to "paštas",
        "sporto"          to "sporto salė",
        "kirpykla"        to "kirpykla",
        "biblioteka"      to "biblioteka",
        "viešoji"         to "biblioteka",
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Resolve [rawText] (destination extracted by the voice parser) into the best
     * possible navigation query or place search.
     *
     * @param rawText        Destination text from [VoiceCommandParser.parse].
     * @param currentLat     Device latitude — used to bias location queries.
     * @param currentLng     Device longitude — used to bias location queries.
     * @param currentLocality City/town name from reverse geocoding. When provided,
     *                        local addresses are appended with this city name.
     * @param savedPlaces    Home and work address map from [SavedPlacesRepository.getAll].
     */
    suspend fun resolve(
        rawText: String,
        currentLat: Double? = null,
        currentLng: Double? = null,
        currentLocality: String? = null,
        savedPlaces: SavedPlacesMap = emptyMap(),
    ): DestinationResolution {
        val trimmed = rawText.trim().trimEnd('.', ',', '!', '?').trim()
        val lower = trimmed.lowercase()

        Log.d(TAG, "resolve: raw='$trimmed' locality='$currentLocality' lat=$currentLat lng=$currentLng")

        // Early guard: blank input
        if (trimmed.isBlank()) {
            Log.d(TAG, "resolve[H]: blank → Failure")
            return DestinationResolution.Failure("Tikslo adresas tuščias.")
        }

        // ── A. Saved-place aliases ─────────────────────────────────────────
        if (HOME_ALIASES.any { lower.contains(it) }) {
            val addr = savedPlaces["namai"]
            return if (addr != null) {
                Log.d(TAG, "resolve[A]: home alias → SavedPlace '$addr'")
                DestinationResolution.SavedPlace("Namai", addr)
            } else {
                Log.d(TAG, "resolve[A]: home alias, no address → Failure")
                DestinationResolution.Failure("Namų adresas dar nenustatytas. Nustatykite jį Nustatymuose.")
            }
        }
        if (WORK_ALIASES.any { lower.contains(it) }) {
            val addr = savedPlaces["darbas"]
            return if (addr != null) {
                Log.d(TAG, "resolve[A]: work alias → SavedPlace '$addr'")
                DestinationResolution.SavedPlace("Darbas", addr)
            } else {
                Log.d(TAG, "resolve[A]: work alias, no address → Failure")
                DestinationResolution.Failure("Darbo adresas dar nenustatytas. Nustatykite jį Nustatymuose.")
            }
        }

        // ── B. Coordinate pair passthrough ────────────────────────────────
        if (COORD_REGEX.matches(lower.replace(" ", ""))) {
            Log.d(TAG, "resolve[B]: coordinate pair → ExactAddress '$trimmed'")
            return DestinationResolution.ExactAddress(trimmed)
        }

        // ── B2. Comma present → treat as already-qualified address ────────
        if (trimmed.contains(",")) {
            Log.d(TAG, "resolve[B2]: comma → ExactAddress '$trimmed'")
            return DestinationResolution.ExactAddress(trimmed)
        }

        // ── F. City-centre shorthands ─────────────────────────────────────
        if (CENTRE_ALIASES.any { lower == it || lower.contains(it) }) {
            val query = if (currentLocality != null) "centras, $currentLocality" else "city centre"
            Log.d(TAG, "resolve[F]: city-centre → ExactAddress '$query'")
            return DestinationResolution.ExactAddress(query)
        }

        // ── E. Category phrase ─────────────────────────────────────────────
        val category = CATEGORY_KEYWORDS.entries
            .firstOrNull { (k, _) -> lower.contains(k) }?.value
        if (category != null) {
            val query = buildLocationQuery(category, currentLocality, currentLat, currentLng)
            Log.d(TAG, "resolve[E]: category='$category' → PlaceSearch '$query'")
            return DestinationResolution.PlaceSearch(query)
        }

        // ── D. Known brand / named POI ────────────────────────────────────
        val brand = BRAND_KEYWORDS.firstOrNull { lower.contains(it) }
        if (brand != null) {
            val query = buildLocationQuery(trimmed, currentLocality, currentLat, currentLng)
            Log.d(TAG, "resolve[D]: brand='$brand' → PlaceSearch '$query'")
            return DestinationResolution.PlaceSearch(query)
        }

        // ── C. Street + number pattern ────────────────────────────────────
        val streetMatch = STREET_NUMBER_REGEX.find(trimmed)
        if (streetMatch != null) {
            val streetPart = streetMatch.groupValues[1].trim()
            val numberPart = streetMatch.groupValues[2].trim()
            val expanded  = expandStreetName(streetPart)
            val query = if (currentLocality != null) {
                "$expanded $numberPart, $currentLocality"
            } else {
                "$expanded $numberPart"
            }
            Log.d(TAG, "resolve[C]: '$streetPart' → '$expanded' number='$numberPart' query='$query'")
            return DestinationResolution.ExactAddress(query)
        }

        // ── G. Multi-word fallback ─────────────────────────────────────────
        val wordCount = trimmed.split(Regex("\\s+")).size
        if (wordCount >= 2) {
            val query = if (currentLocality != null) "$trimmed, $currentLocality" else trimmed
            Log.d(TAG, "resolve[G]: $wordCount-word → PlaceSearch '$query'")
            return DestinationResolution.PlaceSearch(query)
        }

        // ── H. Failure ────────────────────────────────────────────────────
        Log.d(TAG, "resolve[H]: single word, no context → Failure")
        return DestinationResolution.Failure(
            "Nepavyko rasti tikslo \"$trimmed\". Ar galite pasakyti pilną adresą?"
        )
    }

    // ── Package-internal helpers (accessible to tests) ─────────────────────

    /**
     * Expand a street-name stem to its canonical form.
     * Returns [streetPart] unchanged if it already contains a type suffix
     * (gatvė, prospektas, alėja, …) or if no expansion is found.
     */
    internal fun expandStreetName(streetPart: String): String {
        if (STREET_TYPE_REGEX.containsMatchIn(streetPart)) return streetPart
        val lower = streetPart.lowercase().trim()
        return STREET_EXPANSIONS[lower] ?: streetPart
    }

    /** Build a location-biased query. Locality takes priority over raw coordinates. */
    internal fun buildLocationQuery(
        query: String,
        locality: String?,
        lat: Double?,
        lng: Double?,
    ): String = when {
        locality != null             -> "$query, $locality"
        lat != null && lng != null   -> "$query near $lat,$lng"
        else                         -> query
    }
}
