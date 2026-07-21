package lt.sturmanas.bajeristas.navigation

import com.google.android.libraries.navigation.Maneuver

/**
 * Pure mapping from Google Navigation SDK [Maneuver] values to the internal
 * [ManeuverType] enum.
 *
 * Rules:
 *  - Every known SDK 7.8.0 value must map to a named [ManeuverType].
 *  - Any unknown or future SDK value must map to [ManeuverType.UNKNOWN] — never throw.
 *  - No business logic here; [SafetyController] decides what to do with the result.
 *
 * SDK 7.8.0 removals vs. original implementation:
 *  - Maneuver.NAME_CHANGE         — does not exist in SDK 7.8.0; was in STRAIGHT group.
 *  - Maneuver.ROUNDABOUT_U_TURN   — does not exist in SDK 7.8.0; was in ROUNDABOUT group.
 *  - Maneuver.OFF_RAMP_UNSPECIFIED — does not exist in SDK 7.8.0; was in MOTORWAY_EXIT group.
 *  - Maneuver.ON_RAMP_UNSPECIFIED  — does not exist in SDK 7.8.0; was in MERGE group.
 *  - Maneuver.MERGE_UNSPECIFIED    — does not exist in SDK 7.8.0; was in MERGE group.
 *  - Maneuver.FERRY_TRAIN          — does not exist in SDK 7.8.0; was in COMPLEX_JUNCTION group.
 *  All of these are safely handled by the else → ManeuverType.UNKNOWN branch.
 *
 * Note: ManeuverMapper.fromSdk() is currently called with ManeuverType.UNKNOWN from
 * GoogleNavigationEngine because navigator.currentStep does not exist in SDK 7.8.0.
 * The mapper is retained for future use once the correct step-info API is confirmed,
 * and for unit-test coverage of the mapping table itself.
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

        Maneuver.FERRY,
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
