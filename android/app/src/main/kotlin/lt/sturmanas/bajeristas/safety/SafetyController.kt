package lt.sturmanas.bajeristas.safety

import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationState

/**
 * Permission level for AI conversational audio.
 */
enum class ConversationPermission {
    /** AI may respond at normal length. Distance > 500 m, simple maneuver. */
    ALLOWED,
    /** AI must keep response very short; maneuver announcement is imminent. Distance 201–500 m. */
    SHORT_ONLY,
    /** No AI audio. Navigation instruction has full priority. */
    BLOCKED,
}

/**
 * Deterministic rule set for AI audio permissions.
 *
 * Rules (from the spec):
 *   distance > 500 m            → ALLOWED
 *   200 m < distance ≤ 500 m   → SHORT_ONLY
 *   distance ≤ 200 m            → BLOCKED
 *   complex maneuver (any dist) → BLOCKED
 *   not navigating              → ALLOWED (no constraint)
 *
 * Contains NO AI logic. No network calls. No side effects.
 * Designed to be small enough to read in one sitting; keep it that way.
 */
class SafetyController {

    /** Maneuver types that always block AI audio, regardless of distance. */
    private val complexManeuvers: Set<ManeuverType> = setOf(
        ManeuverType.ROUNDABOUT,
        ManeuverType.MOTORWAY_EXIT,
        ManeuverType.LANE_CHANGE,
        ManeuverType.COMPLEX_JUNCTION,
        ManeuverType.UTURN,
    )

    /**
     * Return the current [ConversationPermission] for the given [state].
     *
     * Complex maneuvers take precedence over distance; BLOCKED is returned
     * even when the vehicle is far from the maneuver.
     */
    fun getPermission(state: NavigationState): ConversationPermission {
        if (!state.isNavigating) return ConversationPermission.ALLOWED
        if (isComplexManeuver(state.nextManeuver)) return ConversationPermission.BLOCKED

        return when {
            state.distanceToManeuverMeters > 500 -> ConversationPermission.ALLOWED
            state.distanceToManeuverMeters > 200 -> ConversationPermission.SHORT_ONLY
            else -> ConversationPermission.BLOCKED
        }
    }

    /**
     * Return true if any currently-playing AI audio must be interrupted immediately.
     *
     * Called whenever a new navigation event arrives while AI is speaking.
     * Triggers at ≤ 200 m or on any complex maneuver.
     */
    fun shouldInterruptAudio(state: NavigationState): Boolean {
        if (!state.isNavigating) return false
        return state.distanceToManeuverMeters <= 200 || isComplexManeuver(state.nextManeuver)
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun isComplexManeuver(maneuver: ManeuverType): Boolean =
        maneuver in complexManeuvers
}
