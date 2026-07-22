package lt.sturmanas.bajeristas.navigation

/** Type alias for the map returned by [SavedPlacesRepository.getAll]. */
typealias SavedPlacesMap = Map<String, String>

/**
 * A single candidate place used during destination disambiguation.
 *
 * @param name          Display name (e.g. "Akropolis Klaipėda").
 * @param address       Full address or query string passed to navigation.
 * @param distanceMeters Straight-line distance from current location, if known.
 */
data class CandidatePlace(
    val name: String,
    val address: String,
    val distanceMeters: Int? = null,
)

/**
 * Output of [DestinationResolver.resolve].
 *
 * Drives both the user-facing feedback (what Kentas speaks) and what query
 * string is forwarded to the existing [NavigationController.startNavigation] flow.
 */
sealed class DestinationResolution {

    /** Input is an unambiguous address or coordinate pair — navigate immediately. */
    data class ExactAddress(val query: String) : DestinationResolution()

    /**
     * Input looks like a POI name or a category (degalinė, parkingas, …).
     * Show "Kentas ieško vietos…" feedback, then forward [query] to navigation.
     */
    data class PlaceSearch(val query: String) : DestinationResolution()

    /** Input matched a user-configured saved-place alias (namai / darbas). */
    data class SavedPlace(val name: String, val address: String) : DestinationResolution()

    /**
     * Multiple plausible results — Kentas reads the options aloud and waits for
     * the user to reply "pirmą", "antrą", or "trečią".
     *
     * Currently triggered by saved-place disambiguation; reserved for future
     * Places API integration that returns multiple candidates.
     */
    data class NeedsClarification(
        val originalText: String,
        val suggestions: List<CandidatePlace>,
    ) : DestinationResolution()

    /** Destination could not be determined. Speak [message] to the user. */
    data class Failure(val message: String) : DestinationResolution()
}
