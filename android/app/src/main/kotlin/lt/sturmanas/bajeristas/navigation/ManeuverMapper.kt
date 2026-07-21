package lt.sturmanas.bajeristas.navigation

import com.google.android.libraries.navigation.Maneuver

/**
 * Pure mapping from Google Navigation SDK [Maneuver] values to the internal
 * [ManeuverType] enum.
 *
 * Rules:
 *  - Every known SDK value must map to a named [ManeuverType].
 *  - Any unknown or future SDK value must map to [ManeuverType.UNKNOWN] — never throw.
 *  - No business logic here; [SafetyController] decides what to do with the result.
 */
object ManeuverMapper {

    /**
     * Convert a SDK [Maneuver] to the internal [ManeuverType].
     * Returns [ManeuverType.UNKNOWN] for any unrecognised value.
     */
    fun fromSdk(maneuver: Maneuver?): ManeuverType = when (maneuver) {
        null -> ManeuverType.NONE

        Maneuver.STRAIGHT,
        Maneuver.DEPART,
        Maneuver.NAME_CHANGE,
        -> ManeuverType.STRAIGHT

        Maneuver.TURN_LEFT -> ManeuverType.TURN_LEFT
        Maneuver.TURN_RIGHT -> ManeuverType.TURN_RIGHT

        Maneuver.TURN_SLIGHT_LEFT,
        Maneuver.KEEP_LEFT,
        -> ManeuverType.SLIGHT_LEFT

        Maneuver.TURN_SLIGHT_RIGHT,
        Maneuver.KEEP_RIGHT,
        -> ManeuverType.SLIGHT_RIGHT

        Maneuver.TURN_SHARP_LEFT -> ManeuverType.SHARP_LEFT
        Maneuver.TURN_SHARP_RIGHT -> ManeuverType.SHARP_RIGHT

        Maneuver.UTURN_LEFT,
        Maneuver.UTURN_RIGHT,
        -> ManeuverType.UTURN

        Maneuver.ROUNDABOUT_LEFT,
        Maneuver.ROUNDABOUT_RIGHT,
        Maneuver.ROUNDABOUT_SHARP_LEFT,
        Maneuver.ROUNDABOUT_SHARP_RIGHT,
        Maneuver.ROUNDABOUT_SLIGHT_LEFT,
        Maneuver.ROUNDABOUT_SLIGHT_RIGHT,
        Maneuver.ROUNDABOUT_U_TURN,
        -> ManeuverType.ROUNDABOUT

        Maneuver.OFF_RAMP_LEFT,
        Maneuver.OFF_RAMP_RIGHT,
        Maneuver.OFF_RAMP_SLIGHT_LEFT,
        Maneuver.OFF_RAMP_SLIGHT_RIGHT,
        Maneuver.OFF_RAMP_UNSPECIFIED,
        -> ManeuverType.MOTORWAY_EXIT

        Maneuver.ON_RAMP_LEFT,
        Maneuver.ON_RAMP_RIGHT,
        Maneuver.ON_RAMP_SLIGHT_LEFT,
        Maneuver.ON_RAMP_SLIGHT_RIGHT,
        Maneuver.ON_RAMP_SHARP_LEFT,
        Maneuver.ON_RAMP_SHARP_RIGHT,
        Maneuver.ON_RAMP_UNSPECIFIED,
        Maneuver.MERGE_LEFT,
        Maneuver.MERGE_RIGHT,
        Maneuver.MERGE_UNSPECIFIED,
        -> ManeuverType.MERGE

        Maneuver.FORK_LEFT,
        Maneuver.FORK_RIGHT,
        -> ManeuverType.FORK

        Maneuver.DESTINATION,
        Maneuver.DESTINATION_LEFT,
        Maneuver.DESTINATION_RIGHT,
        -> ManeuverType.ARRIVE

        Maneuver.FERRY,
        Maneuver.FERRY_TRAIN,
        -> ManeuverType.COMPLEX_JUNCTION

        else -> ManeuverType.UNKNOWN
    }

    /**
     * Convenience overload for integer maneuver ordinals, used in tests and
     * SDK versions that return raw ints instead of enum instances.
     */
    fun fromSdkOrdinal(ordinal: Int): ManeuverType =
        runCatching { fromSdk(Maneuver.values()[ordinal]) }.getOrDefault(ManeuverType.UNKNOWN)
}
