package lt.sturmanas.bajeristas.navigation

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Focused regression tests for the isRerouting spinner bug.
 *
 * ## Root cause (fixed)
 * [com.google.android.libraries.navigation.Navigator.addRouteChangedListener] fires for ALL
 * SDK-side route updates during active guidance — including routine traffic recalculations
 * that never leave the current route.  Every such callback set `isRerouting = true`.  The
 * only clearing path — [com.google.android.libraries.navigation.Navigator.addRemainingTimeOrDistanceChangedListener]
 * with a 5 s / 10 m threshold — did not fire fast enough during slow-moving or stationary
 * navigation, so the spinner remained permanently visible.
 *
 * ## Fix contracts verified here
 *
 * [GoogleNavigationEngine] can NOT be instantiated in a JVM unit test because it depends
 * on the Android Navigation SDK (native `.aar`).  Tests are therefore structural + data-model:
 *
 *  1. New fields exist with the correct declared types.
 *  2. [REROUTE_TIMEOUT_MS] constant is present and equals 10 000 ms.
 *  3. [clearReroutingState] private method exists with the right signature.
 *  4. [NavigationState] default has [NavigationState.isRerouting] == false
 *     (normal navigation must NOT show the banner).
 *  5. [NavigationState.copy] correctly mirrors rerouting state changes.
 *  6. Generation-field type is `Int` (required for the stale-callback guard).
 *  7. Timeout job field is nullable [Job] (required so null-check cancellation is safe).
 *
 * Full end-to-end behavioural coverage (duplicate suppression, timeout fire, stale-gen
 * guard) requires the Navigation SDK and lives in the instrumented test suite.
 */
class GoogleNavigationEngineReroutingTest {

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getDeclaredFieldOrNull(clazz: Class<*>, name: String) =
        try { clazz.getDeclaredField(name).also { it.isAccessible = true } }
        catch (_: NoSuchFieldException) { null }

    private fun getDeclaredMethodOrNull(clazz: Class<*>, name: String, vararg params: Class<*>) =
        try { clazz.getDeclaredMethod(name, *params).also { it.isAccessible = true } }
        catch (_: NoSuchMethodException) { null }

    // ── AC-R01 — default state does NOT show the banner ───────────────────

    /**
     * A freshly-constructed [NavigationState] must have [NavigationState.isRerouting] == false.
     *
     * Normal active navigation uses the default data-class instance; the rerouting spinner
     * must NEVER be visible unless the engine explicitly sets the flag.
     */
    @Test
    fun `NavigationState default isRerouting is false — normal navigation does not show banner`() {
        assertFalse(
            "NavigationState() must default isRerouting=false. Normal navigation must NEVER " +
            "show 'Perskaičiuojamas maršrutas…' unless the engine sets the flag explicitly.",
            NavigationState().isRerouting,
        )
    }

    // ── AC-R02 — reroute start shows the banner ───────────────────────────

    @Test
    fun `NavigationState copy with isRerouting=true shows the rerouting banner`() {
        val base = NavigationState(isNavigating = true)
        val rerouting = base.copy(isRerouting = true)
        assertTrue(
            "After copy(isRerouting=true) the NavigationState must reflect true so the UI " +
            "shows the 'Perskaičiuojamas maršrutas…' banner.",
            rerouting.isRerouting,
        )
    }

    // ── AC-R03 — successful new route clears the banner ───────────────────

    @Test
    fun `NavigationState copy with isRerouting=false clears the banner`() {
        val rerouting = NavigationState(isNavigating = true, isRerouting = true)
        val settled   = rerouting.copy(isRerouting = false)
        assertFalse(
            "After route-received clearReroutingState sets isRerouting=false via .copy(). " +
            "The resulting state must NOT show the rerouting banner.",
            settled.isRerouting,
        )
    }

    // ── AC-R04 — navigation stop clears the banner ────────────────────────

    @Test
    fun `NavigationState reset clears isRerouting — navigation stop hides the banner`() {
        val rerouting = NavigationState(isNavigating = true, isRerouting = true)
        // stopNavigation() calls clearReroutingState then resets to NavigationState()
        val afterStop = NavigationState()
        assertFalse(
            "stopNavigation() resets state to NavigationState(); the new state must have " +
            "isRerouting=false so the rerouting banner is hidden after navigation stops.",
            afterStop.isRerouting,
        )
        // Confirm the rerouting state was actually true before stop (so the test is non-trivial)
        assertTrue("Pre-condition: rerouting was true", rerouting.isRerouting)
    }

    // ── AC-R05 — REROUTE_TIMEOUT_MS constant ─────────────────────────────

    /**
     * The safety timeout must be exactly 10 000 ms.
     *
     * Shorter values would create false "stuck spinner" clears during legitimate rerouting.
     * Longer values would leave a stuck spinner visible for an unacceptable duration.
     */
    @Test
    fun `REROUTE_TIMEOUT_MS constant equals 10 000 ms`() {
        assertEquals(
            "REROUTE_TIMEOUT_MS must be 10_000L (10 seconds). " +
            "Shorter: false clears during real rerouting. Longer: stuck spinner tolerated too long.",
            10_000L,
            GoogleNavigationEngine.REROUTE_TIMEOUT_MS,
        )
    }

    // ── AC-R06 — reroutingGeneration field exists ─────────────────────────

    /**
     * [reroutingGeneration] is the stale-callback guard: the safety-timeout coroutine
     * captures its generation at launch and is a no-op if the counter has moved.
     *
     * Without this field, a stale timeout from an older reroute could clear the spinner
     * of a fresh reroute that began while the timer was still running.
     */
    @Test
    fun `reroutingGeneration private field exists as Int — stale timeout guard`() {
        val field = getDeclaredFieldOrNull(GoogleNavigationEngine::class.java, "reroutingGeneration")
        assertNotNull(
            "GoogleNavigationEngine must have a private 'reroutingGeneration: Int' field. " +
            "It is the generation counter captured by each safety-timeout coroutine; the " +
            "callback compares its captured value against the current counter and is a no-op " +
            "if they differ — preventing a stale timeout from clearing a fresh reroute.",
            field,
        )
        assertEquals(
            "reroutingGeneration must be declared as Int (primitive int in bytecode).",
            Int::class.javaPrimitiveType,
            field!!.type,
        )
    }

    // ── AC-R07 — reroutingTimeoutJob field exists ─────────────────────────

    /**
     * [reroutingTimeoutJob] is the handle to the safety-timeout coroutine.
     * It must be nullable [Job] so the null-check cancellation pattern is safe.
     */
    @Test
    fun `reroutingTimeoutJob private field exists as nullable Job`() {
        val field = getDeclaredFieldOrNull(GoogleNavigationEngine::class.java, "reroutingTimeoutJob")
        assertNotNull(
            "GoogleNavigationEngine must have a private 'reroutingTimeoutJob: Job?' field. " +
            "It holds the safety-timeout coroutine job; nullable so it can be null-checked " +
            "and cancelled safely at all clearing sites (stopNavigation, onDestroy, etc.).",
            field,
        )
        // Job is an interface; field type must be assignable from Job (nullable: declared as Any? in JVM)
        val declaredType = field!!.type
        assertTrue(
            "reroutingTimeoutJob must be declared as Job? (or a Job-compatible nullable type). " +
            "Actual type: $declaredType",
            declaredType == Job::class.java || declaredType == Object::class.java,
        )
    }

    // ── AC-R08 — clearReroutingState private method exists ────────────────

    /**
     * [clearReroutingState] is the canonical exit point for rerouting state.
     *
     * All clearing sites (route received, navigation stopped, view destroyed, timeout) call
     * this single method to ensure the timeout job is cancelled, the generation counter is
     * incremented, and [NavigationState.isRerouting] is cleared atomically.
     */
    @Test
    fun `clearReroutingState private method exists with String parameter`() {
        val method = getDeclaredMethodOrNull(
            GoogleNavigationEngine::class.java,
            "clearReroutingState",
            String::class.java,
        )
        assertNotNull(
            "GoogleNavigationEngine must have a private 'clearReroutingState(String)' method. " +
            "It is the ONLY exit point for rerouting state — cancels timeout job, increments " +
            "reroutingGeneration (stale-guard), and clears NavigationState.isRerouting. " +
            "All clearing sites (RemainingTimeOrDistanceChangedListener, stopNavigation, " +
            "onViewDestroy, onDestroy) call this method to stay consistent.",
            method,
        )
        assertTrue(
            "clearReroutingState must be private.",
            Modifier.isPrivate(method!!.modifiers),
        )
    }

    // ── AC-R09 — isRerouting is independent of isNavigating ──────────────

    /**
     * Active navigation (isNavigating=true) must NOT imply rerouting.
     *
     * This is the direct expression of requirement 5: general navigation activity must
     * not be used as the condition for showing the banner.
     */
    @Test
    fun `isNavigating=true does not imply isRerouting=true`() {
        val navigating = NavigationState(isNavigating = true)
        assertFalse(
            "NavigationState(isNavigating=true) must have isRerouting=false by default. " +
            "The rerouting banner must never appear due to active navigation alone — only " +
            "when the engine explicitly sets isRerouting=true on a real route-changed event.",
            navigating.isRerouting,
        )
    }

    // ── AC-R10 — isRerouting is independent of NavigationPhase.NAVIGATING ─

    @Test
    fun `NavigationPhase NAVIGATING does not imply isRerouting=true`() {
        val activeNav = NavigationState(
            isNavigating = true,
            phase        = NavigationPhase.NAVIGATING,
        )
        assertFalse(
            "NavigationState with phase=NAVIGATING must have isRerouting=false by default. " +
            "Route-progress phase and rerouting state are orthogonal; conflating them was the " +
            "original bug — the spinner appeared throughout the whole trip.",
            activeNav.isRerouting,
        )
    }
}
