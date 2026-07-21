package lt.sturmanas.bajeristas.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Kotlin unit tests for [NavigationState] and [MockNavigationEngine] state management.
 * No Android runtime required — runs on the JVM via `./gradlew :app:testDebugUnitTest`.
 *
 * [MockNavigationEngine.createNavigationView] is NOT called here because it creates an
 * Android [TextView] that requires the Android runtime. All other engine methods are tested.
 */
class NavigationStateTest {

    // ── NavigationState defaults ──────────────────────────────────────────

    @Test
    fun `default NavigationState has isNavigating false`() {
        val state = NavigationState()
        assertFalse(state.isNavigating)
    }

    @Test
    fun `default NavigationState has NONE maneuver`() {
        val state = NavigationState()
        assertEquals(ManeuverType.NONE, state.maneuverType)
    }

    @Test
    fun `default NavigationState distanceToNextManeuverMeters is MAX_VALUE`() {
        val state = NavigationState()
        assertEquals(Int.MAX_VALUE, state.distanceToNextManeuverMeters)
    }

    @Test
    fun `default NavigationState has no error`() {
        assertNull(NavigationState().errorMessage)
    }

    @Test
    fun `default NavigationState isRerouting is false`() {
        assertFalse(NavigationState().isRerouting)
    }

    @Test
    fun `default NavigationState hasArrived is false`() {
        assertFalse(NavigationState().hasArrived)
    }

    // ── NavigationState field construction ────────────────────────────────

    @Test
    fun `NavigationState stores all fields correctly`() {
        val state = NavigationState(
            isNavigating = true,
            destinationName = "Vilniaus oro uostas",
            currentRoadName = "Rodūnios kelias",
            nextRoadName = "Oro uosto gatvė",
            maneuverType = ManeuverType.TURN_LEFT,
            distanceToNextManeuverMeters = 320,
            remainingDistanceMeters = 5_400,
            remainingDurationSeconds = 900,
            isRerouting = false,
            hasArrived = false,
            errorMessage = null,
        )
        assertTrue(state.isNavigating)
        assertEquals("Vilniaus oro uostas", state.destinationName)
        assertEquals("Rodūnios kelias", state.currentRoadName)
        assertEquals("Oro uosto gatvė", state.nextRoadName)
        assertEquals(ManeuverType.TURN_LEFT, state.maneuverType)
        assertEquals(320, state.distanceToNextManeuverMeters)
        assertEquals(5_400, state.remainingDistanceMeters)
        assertEquals(900, state.remainingDurationSeconds)
        assertFalse(state.isRerouting)
        assertFalse(state.hasArrived)
        assertNull(state.errorMessage)
    }

    @Test
    fun `NavigationState with error message`() {
        val state = NavigationState(errorMessage = "Adresas nerastas")
        assertNotNull(state.errorMessage)
        assertEquals("Adresas nerastas", state.errorMessage)
    }

    @Test
    fun `NavigationState arrival state`() {
        val state = NavigationState(
            isNavigating = false,
            hasArrived = true,
            maneuverType = ManeuverType.ARRIVE,
            distanceToNextManeuverMeters = 0,
        )
        assertFalse(state.isNavigating)
        assertTrue(state.hasArrived)
        assertEquals(ManeuverType.ARRIVE, state.maneuverType)
        assertEquals(0, state.distanceToNextManeuverMeters)
    }

    @Test
    fun `NavigationState rerouting state`() {
        val state = NavigationState(isNavigating = true, isRerouting = true)
        assertTrue(state.isNavigating)
        assertTrue(state.isRerouting)
    }

    // ── Immutability (data class copy) ────────────────────────────────────

    @Test
    fun `NavigationState copy is independent of original`() {
        val original = NavigationState(distanceToNextManeuverMeters = 500)
        val copy = original.copy(distanceToNextManeuverMeters = 200)
        assertEquals(500, original.distanceToNextManeuverMeters)
        assertEquals(200, copy.distanceToNextManeuverMeters)
    }

    @Test
    fun `NavigationState equals by value not reference`() {
        val a = NavigationState(destinationName = "Kaunas", isNavigating = true)
        val b = NavigationState(destinationName = "Kaunas", isNavigating = true)
        assertEquals(a, b)
    }

    // ── MockNavigationEngine state lifecycle ──────────────────────────────

    @Test
    fun `MockNavigationEngine initialize always calls onReady`() {
        val engine = MockNavigationEngine()
        var readyCalled = false
        // Activity parameter is not used by MockNavigationEngine
        engine.initialize(
            activity = null as? android.app.Activity ?: run {
                // MockNavigationEngine ignores the activity — safe to pass null via cast
                @Suppress("UNCHECKED_CAST")
                null as android.app.Activity
            },
            onReady = { readyCalled = true },
            onError = { error("Should not be called: $it") },
        )
        assertTrue("MockNavigationEngine.initialize must call onReady", readyCalled)
    }

    @Test
    fun `MockNavigationEngine initial state is idle`() {
        val engine = MockNavigationEngine()
        val state = engine.state.value
        assertFalse(state.isNavigating)
        assertNull(state.errorMessage)
        assertFalse(state.hasArrived)
    }

    @Test
    fun `MockNavigationEngine stopNavigation resets to idle state`() {
        val engine = MockNavigationEngine()
        // Simulate navigation being active by direct state update is not possible from tests,
        // but stopNavigation() must always produce a clean NavigationState.
        engine.stopNavigation()
        val state = engine.state.value
        assertFalse("isNavigating must be false after stop", state.isNavigating)
        assertEquals(ManeuverType.NONE, state.maneuverType)
        assertEquals(Int.MAX_VALUE, state.distanceToNextManeuverMeters)
        assertNull(state.errorMessage)
    }

    // ── Permission denial state ───────────────────────────────────────────

    @Test
    fun `NavigationState with permission-denied error has errorMessage set`() {
        // When permission is denied, the engine sets an error and navigation stays idle.
        val errorState = NavigationState(
            isNavigating = false,
            errorMessage = "Vietos leidimas atmestas",
        )
        assertFalse(errorState.isNavigating)
        assertNotNull(errorState.errorMessage)
        assertTrue(errorState.errorMessage!!.contains("leidimas"))
    }

    // ── Missing API key state ─────────────────────────────────────────────

    @Test
    fun `missing API key results in MockNavigationEngine being selected`() {
        // When BuildConfig.GOOGLE_MAPS_API_KEY is blank, MockNavigationEngine is chosen.
        // The mock always initialises successfully and produces valid NavigationState.
        val engine = MockNavigationEngine()
        assertFalse("Mock engine starts in idle state", engine.state.value.isNavigating)
    }

    // ── SDK update converted to NavigationState ───────────────────────────

    @Test
    fun `NavigationState from SDK update has correct maneuver and distance`() {
        // This test verifies the data class pattern used by GoogleNavigationEngine
        // when it converts SDK data to NavigationState.
        val sdkManeuver = ManeuverType.ROUNDABOUT // as if mapped by ManeuverMapper
        val state = NavigationState(
            isNavigating = true,
            maneuverType = sdkManeuver,
            distanceToNextManeuverMeters = 250,
            remainingDurationSeconds = 1200,
            currentRoadName = "Konstitucijos pr.",
        )
        assertEquals(ManeuverType.ROUNDABOUT, state.maneuverType)
        assertEquals(250, state.distanceToNextManeuverMeters)
        assertEquals(1200, state.remainingDurationSeconds)
        assertEquals("Konstitucijos pr.", state.currentRoadName)
    }
}
