package lt.sturmanas.bajeristas.navigation

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Development-only [NavigationEngine] used when no Google Maps API key is
 * configured (i.e. [BuildConfig.GOOGLE_MAPS_API_KEY] is blank).
 *
 * Simulates a navigation session by ticking down distance every 2 seconds.
 * Produces realistic [NavigationState] transitions so the full UI can be
 * exercised without a real API key:
 *
 *   distance > 500 m  → STRAIGHT (ALLOWED)
 *   distance 201–500  → TURN_RIGHT (SHORT_ONLY)
 *   distance ≤ 200    → TURN_RIGHT (BLOCKED)
 *   distance = 0      → ARRIVE + stop
 *
 * Must never be used in production builds that have a real API key.
 */
class MockNavigationEngine : NavigationEngine {

    private val _state = MutableStateFlow(NavigationState())
    override val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var simulationJob: Job? = null

    // ── NavigationEngine impl ─────────────────────────────────────────────

    override fun initialize(activity: Activity, onReady: () -> Unit, onError: (String) -> Unit) {
        // Mock always succeeds immediately.
        onReady()
    }

    override fun startNavigation(context: Context, destination: String, onError: (String) -> Unit) {
        simulationJob?.cancel()
        _state.value = NavigationState(
            isNavigating = true,
            destinationName = destination,
            currentRoadName = "Gedimino prospektas",
            nextRoadName = "Pilies gatvė",
            maneuverType = ManeuverType.STRAIGHT,
            distanceToNextManeuverMeters = 850,
            remainingDistanceMeters = 4_200,
            remainingDurationSeconds = 780,
        )
        startSimulation()
    }

    override fun stopNavigation() {
        simulationJob?.cancel()
        simulationJob = null
        _state.value = NavigationState()
    }

    override fun createNavigationView(context: Context): View {
        return TextView(context).apply {
            text = "[MOCK] Žemėlapis\nNavi SDK raktas nenustatytas\nŽr. local.properties"
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#C8E6C9"))
        }
    }

    override fun enableStandardVoice() { /* no-op in mock */ }
    override fun disableStandardVoice() { /* no-op in mock */ }

    override fun onResume() { /* no lifecycle state in mock */ }
    override fun onPause() { /* no lifecycle state in mock */ }
    override fun onStop() { /* no lifecycle state in mock */ }
    override fun onDestroy() {
        simulationJob?.cancel()
    }

    // ── Simulation ────────────────────────────────────────────────────────

    private fun startSimulation() {
        simulationJob = scope.launch {
            var distance = _state.value.distanceToNextManeuverMeters
            var remaining = _state.value.remainingDurationSeconds

            while (isActive && distance > 0) {
                delay(2_000)

                distance = maxOf(0, distance - 45)
                remaining = maxOf(0, remaining - 2)

                val maneuver = when {
                    distance > 500 -> ManeuverType.STRAIGHT
                    distance > 200 -> ManeuverType.TURN_RIGHT
                    distance > 0 -> ManeuverType.TURN_RIGHT
                    else -> ManeuverType.ARRIVE
                }

                _state.value = _state.value.copy(
                    maneuverType = maneuver,
                    distanceToNextManeuverMeters = distance,
                    remainingDistanceMeters = maxOf(0, _state.value.remainingDistanceMeters - 45),
                    remainingDurationSeconds = remaining,
                    currentRoadName = if (distance <= 200) "Pilies gatvė" else "Gedimino prospektas",
                )
            }

            // Arrival
            if (distance == 0) {
                _state.value = _state.value.copy(
                    hasArrived = true,
                    isNavigating = false,
                    maneuverType = ManeuverType.ARRIVE,
                    distanceToNextManeuverMeters = 0,
                    remainingDurationSeconds = 0,
                )
            }
        }
    }
}
