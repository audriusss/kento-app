package lt.sturmanas.bajeristas.navigation

/**
 * All maneuver types recognised by the application.
 * Phase 2: values map 1-to-1 to Google Navigation SDK's Maneuver enum.
 */
enum class ManeuverType {
    NONE,
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    /** Roundabout — always complex; blocks AI audio regardless of distance. */
    ROUNDABOUT,
    /** Motorway / highway exit — always complex. */
    MOTORWAY_EXIT,
    /** Lane change required — always complex. */
    LANE_CHANGE,
    /** Multi-step or unusual junction — always complex. */
    COMPLEX_JUNCTION,
    /** U-turn — always complex. */
    UTURN,
}

/**
 * Immutable snapshot of the current navigation state.
 *
 * Phase 1: populated by [NavigationController] with mock data.
 * Phase 2: populated from Google Navigation SDK callbacks.
 *
 * This is the only source of navigation truth passed to [SafetyController].
 * No AI logic may modify or override these values.
 */
data class NavigationState(
    val isNavigating: Boolean = false,
    val destination: String = "",
    val currentStreet: String = "",
    val nextManeuver: ManeuverType = ManeuverType.NONE,
    /** Distance to the next maneuver in metres. Int.MAX_VALUE when unknown. */
    val distanceToManeuverMeters: Int = Int.MAX_VALUE,
    /** Estimated remaining travel time in seconds. */
    val remainingTimeSeconds: Int = 0,
)
