package lt.sturmanas.bajeristas.navigation

/**
 * Output of [DestinationResolver.resolve].
 *
 * Drives what query string is forwarded to the existing
 * [NavigationController.startNavigation] flow.
 */
sealed class DestinationResolution {

    /** Input is an unambiguous address or coordinate pair — navigate immediately. */
    data class ExactAddress(val query: String) : DestinationResolution()

    /**
     * Input looks like a POI name or a category (degalinė, parkingas, …).
     */
    data class PlaceSearch(val query: String) : DestinationResolution()

    /** Destination could not be determined. */
    data class Failure(val message: String) : DestinationResolution()
}
