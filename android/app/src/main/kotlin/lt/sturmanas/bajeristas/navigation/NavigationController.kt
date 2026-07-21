package lt.sturmanas.bajeristas.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single owner of [NavigationState].
 *
 * Phase 1: exposes mock data only — no real SDK calls.
 * Phase 2: will wrap Google Navigation SDK's NavigationApi, RouteCallbacks,
 *           and RoadSnappedLocationProvider. All SDK callbacks will call
 *           [updateState] so the rest of the app stays unchanged.
 *
 * The AI must never write to this controller; it is read-only from the AI's
 * perspective. Navigation data flows one way: SDK → controller → UI / SafetyController.
 */
class NavigationController {

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────

    /** Start navigation to [destination] using mock data (Phase 1). */
    fun startNavigation(destination: String) {
        _state.value = NavigationState(
            isNavigating = true,
            destination = destination,
            currentStreet = "Gedimino prospektas",
            nextManeuver = ManeuverType.TURN_RIGHT,
            distanceToManeuverMeters = 850,
            remainingTimeSeconds = 720,
        )
    }

    /** Stop navigation and reset to idle state. */
    fun stopNavigation() {
        _state.value = NavigationState()
    }

    /**
     * Push a new state snapshot.
     * Called by SDK callbacks in Phase 2; also used directly in tests.
     */
    fun updateState(state: NavigationState) {
        _state.value = state
    }

    /**
     * Enable the standard Google navigation voice guidance.
     * Phase 1: stub. Phase 2: calls NavigationApi.navigator.setAudioGuidance(…).
     */
    fun enableStandardVoice() {
        // TODO Phase 2:
        // NavApi.navigator.setAudioGuidance(NavigationAudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
    }

    /**
     * Disable the standard Google navigation voice guidance (AI handles speaking).
     * Phase 1: stub. Phase 2: calls NavigationApi.navigator.setAudioGuidance(…).
     */
    fun disableStandardVoice() {
        // TODO Phase 2:
        // NavApi.navigator.setAudioGuidance(NavigationAudioGuidance.NO_GUIDANCE)
    }
}
