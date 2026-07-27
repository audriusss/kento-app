package lt.sturmanas.bajeristas.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver

/**
 * Pure mapping from the Navigation SDK 7.8.0 TBT maneuver integer to the internal
 * [ManeuverType] enum.
 *
 * API facts for com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver:
 *  - Maneuver is a Java @IntDef annotation/interface, not an enum or class.
 *  - Its constants are plain int fields (e.g. Maneuver.STRAIGHT is an Int).
 *  - Maneuver? and Maneuver.values() do not exist; all parameters are Int.
 *  - Null or absent maneuver values must be handled by the caller before reaching
 *    [fromSdk]; use [fromSdkNullable] for a null-safe entry point.
 *
 * Constants absent from SDK 7.8.0 TBT Maneuver (removed from this mapper):
 *  - UTURN_LEFT / UTURN_RIGHT     — do not exist in this package
 *
 * Rules:
 *  - Every confirmed SDK 7.8.0 constant maps to a named [ManeuverType].
 *  - Any unrecognised integer maps to [ManeuverType.UNKNOWN] — never throws.
 *  - No business logic here; [SafetyController] decides what to do with the result.
 *
 * Note: This mapper is used by GoogleNavigationEngine to convert real-time
 * integers from the TBT feed (via NavInfoService) into internal types.
 */
object ManeuverMapper {

    /**
     * Convert a raw SDK maneuver [Int] to the internal [ManeuverType].
     *
     * The parameter must be one of the [Maneuver] int constants. Null or missing
     * values must be resolved by the caller before this is invoked — use
     * [fromSdkNullable] for the null-safe variant.
     *
     * Returns [ManeuverType.UNKNOWN] for any unrecognised integer.
     */
    fun fromSdk(@Maneuver maneuver: Int): ManeuverType = when (maneuver) {

        Maneuver.STRAIGHT,
        Maneuver.DEPART,
        -> ManeuverType.STRAIGHT

        Maneuver.TURN_LEFT -> ManeuverType.TURN_LEFT
        Maneuver.TURN_RIGHT -> ManeuverType.TURN_RIGHT

        Maneuver.TURN_KEEP_LEFT,
        Maneuver.TURN_SLIGHT_LEFT -> ManeuverType.SLIGHT_LEFT

        Maneuver.TURN_KEEP_RIGHT,
        Maneuver.TURN_SLIGHT_RIGHT -> ManeuverType.SLIGHT_RIGHT

        Maneuver.TURN_SHARP_LEFT -> ManeuverType.SHARP_LEFT
        Maneuver.TURN_SHARP_RIGHT -> ManeuverType.SHARP_RIGHT

        Maneuver.TURN_U_TURN_CLOCKWISE,
        Maneuver.TURN_U_TURN_COUNTERCLOCKWISE,
        Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
        Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
        Maneuver.OFF_RAMP_U_TURN_CLOCKWISE,
        Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE,
        Maneuver.ROUNDABOUT_U_TURN_CLOCKWISE,
        Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
        -> ManeuverType.UTURN

        Maneuver.ROUNDABOUT_CLOCKWISE,
        Maneuver.ROUNDABOUT_COUNTERCLOCKWISE,
        Maneuver.ROUNDABOUT_EXIT_CLOCKWISE,
        Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE,

        Maneuver.ROUNDABOUT_LEFT_CLOCKWISE,
        Maneuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE,
        Maneuver.ROUNDABOUT_RIGHT_CLOCKWISE,
        Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE,

        Maneuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE,
        Maneuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE,
        Maneuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE,
        Maneuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE,

        Maneuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE,
        Maneuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE,
        Maneuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE,
        Maneuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,

        Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE,
        Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
            -> ManeuverType.ROUNDABOUT

        Maneuver.OFF_RAMP_LEFT,
        Maneuver.OFF_RAMP_RIGHT,
        Maneuver.OFF_RAMP_SLIGHT_LEFT,
        Maneuver.OFF_RAMP_SLIGHT_RIGHT,
        -> ManeuverType.MOTORWAY_EXIT

        Maneuver.ON_RAMP_LEFT,
        Maneuver.ON_RAMP_RIGHT,
        Maneuver.ON_RAMP_SLIGHT_LEFT,
        Maneuver.ON_RAMP_SLIGHT_RIGHT,
        Maneuver.ON_RAMP_SHARP_LEFT,
        Maneuver.ON_RAMP_SHARP_RIGHT,
        Maneuver.MERGE_LEFT,
        Maneuver.MERGE_RIGHT,
        -> ManeuverType.MERGE

        Maneuver.FORK_LEFT,
        Maneuver.FORK_RIGHT,
        -> ManeuverType.FORK

        Maneuver.DESTINATION,
        Maneuver.DESTINATION_LEFT,
        Maneuver.DESTINATION_RIGHT,
        -> ManeuverType.ARRIVE

        Maneuver.FERRY_BOAT,
        -> ManeuverType.COMPLEX_JUNCTION

        else -> ManeuverType.UNKNOWN
    }

    /**
     * Null-safe entry point. A null maneuver (absent from the SDK step) maps to
     * [ManeuverType.UNKNOWN], consistent with the "unknown / no data" contract.
     * Use this when the SDK may not supply a maneuver integer.
     */
    fun fromSdkNullable(maneuver: Int?): ManeuverType =
        if (maneuver == null) ManeuverType.UNKNOWN else fromSdk(maneuver)

    /**
     * Test helper: pass an arbitrary integer directly to the mapper.
     * Since [Maneuver] is an @IntDef (not an enum), the ordinal IS the integer value.
     * Out-of-range integers safely return [ManeuverType.UNKNOWN] via the else branch.
     */
    fun fromSdkOrdinal(ordinal: Int): ManeuverType = fromSdk(ordinal)
}
