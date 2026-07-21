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
 * Distance rules (unchanged from Phase 1):
 *   distance > 500 m            → ALLOWED
 *   200 m < distance ≤ 500 m   → SHORT_ONLY
 *   distance ≤ 200 m            → BLOCKED
 *   not navigating              → ALLOWED
 *
 * Complex maneuver set (Phase 2 additions marked ★):
 *   ROUNDABOUT, MOTORWAY_EXIT, LANE_CHANGE, COMPLEX_JUNCTION, UTURN  (Phase 1)
 *   MERGE ★, FORK ★                                                   (Phase 2)
 *
 * Rationale for Phase 2 additions:
 *   MERGE — driver must judge a moving lane gap; no distraction allowed.
 *   FORK  — two-path decision at speed; ambiguity is dangerous.
 *
 * This class contains NO AI logic, no network calls, and no side effects.
 * Override thresholds only after reporting the change.
 */
class SafetyController {

    /** Maneuver types that always block AI audio, regardless of distance. */
    private val complexManeuvers: Set<ManeuverType> = setOf(
        // Phase 1
        ManeuverType.ROUNDABOUT,
        ManeuverType.MOTORWAY_EXIT,
        ManeuverType.LANE_CHANGE,
        ManeuverType.COMPLEX_JUNCTION,
        ManeuverType.UTURN,
        // Phase 2 additions
        ManeuverType.MERGE,
        ManeuverType.FORK,
    )

    /**
     * Return the current [ConversationPermission] for the given [state].
     * Complex maneuvers override distance — BLOCKED even far from the maneuver.
     */
    fun getPermission(state: NavigationState): ConversationPermission {
        if (!state.isNavigating) return ConversationPermission.ALLOWED
        if (isComplexManeuver(state.maneuverType)) return ConversationPermission.BLOCKED

        return when {
            state.distanceToNextManeuverMeters > 500 -> ConversationPermission.ALLOWED
            state.distanceToNextManeuverMeters > 200 -> ConversationPermission.SHORT_ONLY
            else -> ConversationPermission.BLOCKED
        }
    }

    /**
     * Return true if any currently-playing AI audio must be interrupted immediately.
     * Called on every navigation update while AI is speaking.
     */
    fun shouldInterruptAudio(state: NavigationState): Boolean {
        if (!state.isNavigating) return false
        return state.distanceToNextManeuverMeters <= 200 || isComplexManeuver(state.maneuverType)
    }

    private fun isComplexManeuver(maneuver: ManeuverType): Boolean =
        maneuver in complexManeuvers
}
