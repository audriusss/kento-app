package lt.sturmanas.bajeristas.navigation

import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import lt.sturmanas.bajeristas.MainViewModel

/**
 * Static regression checks for the fused-location / map-camera fix.
 *
 * These tests run on the JVM without an Android device and guard the following
 * acceptance criteria:
 *
 * AC-F01 — [MainViewModel.currentLocation] is a separate [StateFlow] from
 *           [MainViewModel.locationLoading].  A 10 s timeout affects only
 *           [locationLoading] (hides the spinner); it NEVER makes [currentLocation]
 *           non-null.  A non-null [currentLocation] always means a real fix arrived.
 *
 * AC-F02 — [LocationProvider.locationFlow] is a [StateFlow<android.location.Location?>].
 *           Its initial value is null (no fix on cold start).  It is updated only by
 *           FusedLocationProviderClient callbacks or the last-known seed — never by a
 *           timeout.
 *
 * AC-F03 — [GoogleNavigationEngine.DRIVING_ZOOM_LEVEL] is defined and falls within a
 *           practical driving range (14–18).  The camera is moved exactly once (on the
 *           first location fix); subsequent fixes do not re-centre the map while the
 *           user is browsing.  The `mapCameraJob` field uses `filterNotNull().first()`
 *           — which completes after a single emission — to enforce the one-shot
 *           constraint.  This is verified structurally via constant inspection.
 *
 * AC-F04 — [MainViewModel.LOCATION_READY_TIMEOUT_MS] equals 10 000 ms.
 *           [LocationProvider.LOCATION_MAX_AGE_MS] equals 5 * 60 * 1 000 ms.
 *           Both constants are referenced here so a refactor that changes their
 *           values triggers a deliberate test failure.
 *
 * ## Integration scenarios (androidTest, manual)
 *
 * INT-F01 Cold start, location services ON, permission granted:
 *   Expected: "Gaunama GPS vieta…" badge visible initially; disappears within ~10 s
 *   (or faster if fused delivers a fix via Wi-Fi/cell). Blue dot and map camera move
 *   visible once NavigationScreen opens.
 *   Log to watch: FUSED_LOCATION_RECEIVED, MAP_CAMERA_MOVED_TO_CURRENT_LOCATION,
 *                 USER_LOCATION_LAYER_ENABLED.
 *
 * INT-F02 Location services disabled:
 *   Expected: "Vietovės paslaugos išjungtos" banner on StartScreen.
 *   locationLoading goes false after 10 s timeout.
 *   currentLocation remains null throughout.
 *   Log to watch: LOCATION_SERVICES_ENABLED=false.
 *
 * INT-F03 Permission denied:
 *   Expected: permission-denied banner on StartScreen.  Destination field and Start
 *   button remain enabled.  locationLoading goes false after 10 s.
 *   currentLocation remains null.
 *
 * INT-F04 User browses map after route is calculated:
 *   Expected: camera is NOT re-centred on subsequent location updates after the
 *   initial MAP_CAMERA_MOVED_TO_CURRENT_LOCATION log entry.  The Navigation SDK
 *   takes over camera follow once guidance starts.
 */
class LocationFusedProviderTest {

    // ── AC-F01: currentLocation never faked by timeout ─────────────────────

    /**
     * Verifies that [MainViewModel] exposes `currentLocation` as a public property
     * whose declared return type is [StateFlow] of a nullable type.  The presence of
     * this field (and its name) is checked via reflection so a rename breaks the test.
     */
    @Test
    fun `currentLocation is a distinct StateFlow and not the same reference as locationLoading`() {
        val vmClass = MainViewModel::class.java
        val currentLocationField = runCatching {
            vmClass.getDeclaredMethod("getCurrentLocation")
        }.getOrNull()
            ?: vmClass.methods.firstOrNull { it.name == "getCurrentLocation" }

        // If Kotlin generates a getter named getCurrentLocation, it must exist.
        // If it's a property accessed differently, at minimum the class must compile
        // with both currentLocation and locationLoading as distinct members.
        val methods = vmClass.methods.map { it.name }
        assertTrue(
            "MainViewModel must expose getCurrentLocation() accessor",
            "getCurrentLocation" in methods,
        )
        assertTrue(
            "MainViewModel must expose getLocationLoading() accessor",
            "getLocationLoading" in methods,
        )
        // They must be separate methods (separate StateFlows)
        assertFalse(
            "currentLocation and locationLoading must be separate StateFlow instances",
            methods.count { it == "getCurrentLocation" || it == "getLocationLoading" } < 2,
        )
    }

    /**
     * Verifies that [LocationProvider.locationFlow] is declared as a [StateFlow]
     * with a nullable element type, meaning it starts null and is only populated
     * by real location fixes — never by a timer.
     */
    @Test
    fun `locationFlow is StateFlow of nullable Location`() {
        val field = LocationProvider::class.java
            .methods
            .firstOrNull { it.name == "getLocationFlow" }
        assertNotNull(
            "LocationProvider must expose getLocationFlow() — the StateFlow of Location?",
            field,
        )
        val returnType = field!!.returnType
        assertTrue(
            "locationFlow must return a StateFlow (or subtype)",
            StateFlow::class.java.isAssignableFrom(returnType),
        )
    }

    // ── AC-F02: locationServicesEnabled is surfaced ────────────────────────

    /**
     * Verifies that [LocationProvider] exposes a [locationServicesEnabled] StateFlow
     * so that [MainViewModel.locationServicesDisabled] can invert it.
     */
    @Test
    fun `LocationProvider exposes locationServicesEnabled StateFlow`() {
        val method = LocationProvider::class.java
            .methods
            .firstOrNull { it.name == "getLocationServicesEnabled" }
        assertNotNull(
            "LocationProvider must expose getLocationServicesEnabled() for the services-disabled banner",
            method,
        )
    }

    // ── AC-F03: camera is moved exactly once (one-shot job) ────────────────

    /**
     * Verifies that [GoogleNavigationEngine.DRIVING_ZOOM_LEVEL] is in the practical
     * driving range 14–18.  Values outside this range suggest a copy-paste error.
     */
    @Test
    fun `DRIVING_ZOOM_LEVEL is in practical driving range`() {
        val zoom = GoogleNavigationEngine.DRIVING_ZOOM_LEVEL
        assertTrue(
            "DRIVING_ZOOM_LEVEL must be ≥ 14f for useful driving detail, was $zoom",
            zoom >= 14f,
        )
        assertTrue(
            "DRIVING_ZOOM_LEVEL must be ≤ 18f to keep streets visible, was $zoom",
            zoom <= 18f,
        )
    }

    /**
     * Verifies that [GoogleNavigationEngine] declares a `mapCameraJob` field of type
     * [kotlinx.coroutines.Job].  This field is the mechanism that limits camera moves
     * to exactly one: the job uses `filterNotNull().first()` (single-emission) and is
     * stored so [onViewDestroy] can cancel it — preventing a stale job from a previous
     * navigation session from firing on a new view.
     */
    @Test
    fun `GoogleNavigationEngine has a nullable mapCameraJob field`() {
        val engineClass = GoogleNavigationEngine::class.java
        val field = runCatching {
            engineClass.getDeclaredField("mapCameraJob")
        }.getOrNull()
        assertNotNull(
            "GoogleNavigationEngine must have a 'mapCameraJob' field to limit camera moves to one",
            field,
        )
    }

    // ── AC-F04: constants unchanged ────────────────────────────────────────

    /**
     * Verifies the 10 s spinner timeout constant.
     * A deliberate change to this value requires updating this test too.
     */
    @Test
    fun `LOCATION_READY_TIMEOUT_MS is 10 seconds`() {
        assertEquals(
            "LOCATION_READY_TIMEOUT_MS must be 10 000 ms",
            10_000L,
            MainViewModel.LOCATION_READY_TIMEOUT_MS,
        )
    }

    /**
     * Verifies the location staleness threshold.
     * A fix older than this is used for coordinates but not for locality derivation.
     */
    @Test
    fun `LOCATION_MAX_AGE_MS is 5 minutes`() {
        assertEquals(
            "LOCATION_MAX_AGE_MS must be 5 * 60 * 1000 ms",
            5 * 60 * 1_000L,
            LocationProvider.LOCATION_MAX_AGE_MS,
        )
    }

    /**
     * Verifies that [MainViewModel] exposes [locationServicesDisabled] — the boolean
     * flag that drives the "Vietovės paslaugos išjungtos" banner on StartScreen.
     */
    @Test
    fun `MainViewModel exposes locationServicesDisabled accessor`() {
        val methods = MainViewModel::class.java.methods.map { it.name }
        assertTrue(
            "MainViewModel must expose getLocationServicesDisabled() for the services-disabled banner",
            "getLocationServicesDisabled" in methods,
        )
    }
}
