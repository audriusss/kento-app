package lt.sturmanas.bajeristas.navigation

/**
 * Lifecycle phase of an active (or pending) navigation session.
 *
 * Drives UI overlays in [NavigationScreen] and TTS confirmations in [MainActivity].
 * The engine updates this field; the UI only reads it.
 */
enum class NavigationPhase {
    /** No active navigation session — [StartScreen] is visible. */
    IDLE,
    /** Address string is being resolved to coordinates via Geocoder. */
    RESOLVING_ADDRESS,
    /** Waypoint submitted to SDK; route calculation is in progress. */
    CALCULATING_ROUTE,
    /** Route calculated, guidance started — driver is navigating. */
    NAVIGATING,
    /** Driver has reached the destination. */
    ARRIVED,
}

/**
 * All maneuver types recognised by the application.
 * Maps 1-to-1 to the internal representation; [ManeuverMapper] converts
 * Google Navigation SDK [com.google.android.libraries.navigation.Maneuver]
 * values into these types.
 *
 * Complex maneuvers (listed in [SafetyController]) block AI audio regardless
 * of distance. Unknown SDK values must always map to [UNKNOWN] — never crash.
 */
enum class ManeuverType {
    NONE,
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    /** U-turn — always complex. */
    UTURN,
    /** Roundabout entry — always complex. */
    ROUNDABOUT,
    /** Motorway / highway exit — always complex. */
    MOTORWAY_EXIT,
    /** Lane change required — always complex. */
    LANE_CHANGE,
    /** Multi-step or unusual junction — always complex. */
    COMPLEX_JUNCTION,
    /** Merge onto a road — always complex. */
    MERGE,
    /** Road split / fork — always complex. */
    FORK,
    /** Driver has arrived at the destination. */
    ARRIVE,
    /** Unrecognised SDK maneuver value — treated as simple; never crashes. */
    UNKNOWN,
}

/**
 * Immutable snapshot of the current navigation state.
 *
 * Source of truth: Google Navigation SDK callbacks → [NavigationEngine] →
 * [NavigationController] → this class → [SafetyController] + UI.
 *
 * No AI code may write to or override any field here.
 * SDK-specific objects must never appear in this class.
 */
data class NavigationState(
    val isNavigating: Boolean = false,
    /** Human-readable destination name or address. */
    val destinationName: String = "",
    /**
     * Geocoder-resolved display address (may differ from the raw typed string).
     * Blank until geocoding completes successfully.
     */
    val resolvedAddress: String = "",
    /** Current road the vehicle is on. */
    val currentRoadName: String = "",
    /** Road name after the next maneuver. */
    val nextRoadName: String = "",
    /** Next maneuver the driver must perform. */
    val maneuverType: ManeuverType = ManeuverType.NONE,
    /** Distance to the next maneuver in metres. [Int.MAX_VALUE] when unknown. */
    val distanceToNextManeuverMeters: Int = Int.MAX_VALUE,
    /** Total remaining route distance in metres. */
    val remainingDistanceMeters: Int = 0,
    /** Total remaining route duration in seconds. */
    val remainingDurationSeconds: Int = 0,
    /** True while the SDK is recalculating the route. */
    val isRerouting: Boolean = false,
    /** True once the driver has reached the destination. */
    val hasArrived: Boolean = false,
    /** Non-null when a setup or runtime error has occurred. */
    val errorMessage: String? = null,
    /** Current lifecycle phase of the navigation session. */
    val phase: NavigationPhase = NavigationPhase.IDLE,
)
