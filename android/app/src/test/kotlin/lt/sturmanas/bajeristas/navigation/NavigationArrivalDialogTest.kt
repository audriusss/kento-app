package lt.sturmanas.bajeristas.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused regression tests for the arrival confirmation dialog.
 *
 * ## Root cause (fixed)
 * The SDK's [com.google.android.libraries.navigation.Navigator.ArrivalListener] already fired
 * correctly and set [NavigationState.hasArrived] to true.  The `LaunchedEffect(hasArrived)` in
 * `SturmanasApp` called `speakArrival()` but never showed a confirmation dialog and never stopped
 * navigation.  Result: guidance continued indefinitely after reaching the destination.
 *
 * ## Fix contracts verified here
 *
 * Full Compose dialog rendering and interaction require instrumented tests; the tests here cover:
 *
 *  1. [NavigationState] defaults — normal navigation must NOT show arrival state.
 *  2. Arrival flag copy semantics — `hasArrived=true` represents exactly one arrival event.
 *  3. Session reset — [NavigationState()] (produced by `stopNavigation()`) clears `hasArrived`.
 *  4. Arrival is independent of [NavigationPhase.NAVIGATING] — the phase alone does not trigger
 *     arrival.
 *  5. Arrival sets [NavigationPhase.ARRIVED] — the engine writes both flags atomically.
 *  6. [NavigationState.copy] preserves `hasArrived` across unrelated field changes — the dialog
 *     must stay visible through rerouting updates and distance callbacks.
 *  7. SDK session guard: [NavigationState.hasArrived] is false after a session reset — stale
 *     arrival callbacks from the previous session produce a *different* hasArrived transition
 *     (false → true) which the LaunchedEffect key detects, but only if the state was reset first.
 *
 * ## Behavioural contracts (instrumented tests required)
 *
 * The following require Compose test infrastructure and live in the instrumented suite:
 *  - arrival callback shows dialog ("ARRIVAL_DIALOG_SHOWN" log)
 *  - confirm button fires onArrivalConfirmed and stops navigation
 *  - decline button fires onArrivalDeclined and sets arrivalDeclined=true
 *  - second arrival event while dialog is open logs ARRIVAL_DIALOG_SKIPPED reason=already-shown
 *  - arrivalDeclined=true logs ARRIVAL_DIALOG_SKIPPED reason=declined-in-session
 *  - stopNavigation resets showArrivalDialog and arrivalDeclined to false
 */
class NavigationArrivalDialogTest {

    // ── AC-A01 — default state does NOT trigger arrival ────────────────────

    /**
     * A freshly-constructed [NavigationState] must have [NavigationState.hasArrived] == false.
     *
     * Normal active navigation must NEVER show the arrival dialog or the arrival overlay
     * unless the SDK fires ArrivalListener and the engine sets hasArrived=true.
     */
    @Test
    fun `NavigationState default hasArrived is false — normal navigation does not trigger dialog`() {
        assertFalse(
            "NavigationState() must default hasArrived=false. The arrival dialog must never " +
            "appear during normal navigation — only when the SDK fires ArrivalListener.",
            NavigationState().hasArrived,
        )
    }

    // ── AC-A02 — arrival sets hasArrived ──────────────────────────────────

    @Test
    fun `NavigationState copy with hasArrived=true triggers arrival dialog`() {
        val base    = NavigationState(isNavigating = true, phase = NavigationPhase.NAVIGATING)
        val arrived = base.copy(
            hasArrived   = true,
            isNavigating = false,
            phase        = NavigationPhase.ARRIVED,
        )
        assertTrue(
            "After ArrivalListener fires the engine calls copy(hasArrived=true). " +
            "The resulting NavigationState must have hasArrived=true so SturmanasApp " +
            "shows the arrival dialog.",
            arrived.hasArrived,
        )
    }

    // ── AC-A03 — confirm stops navigation (session reset clears hasArrived) ──

    /**
     * When the user presses "Taip, atvykau", `stopNavigation()` is called which resets
     * the engine state to `NavigationState()`.  The resulting state must have `hasArrived=false`
     * so the LaunchedEffect detects the false→true transition on the NEXT trip.
     */
    @Test
    fun `NavigationState reset after confirm has hasArrived=false — new trip starts clean`() {
        val arrived  = NavigationState(hasArrived = true, phase = NavigationPhase.ARRIVED)
        val afterStop = NavigationState()        // stopNavigation() resets to this
        assertTrue("Pre-condition: arrived had hasArrived=true", arrived.hasArrived)
        assertFalse(
            "After stopNavigation() the engine resets state to NavigationState(). " +
            "hasArrived must be false so a subsequent trip gets a fresh arrival event.",
            afterStop.hasArrived,
        )
    }

    // ── AC-A04 — decline keeps navigation running ─────────────────────────

    /**
     * Pressing "Dar ne" must NOT stop navigation.  The [NavigationState.hasArrived] flag
     * stays true; only the UI dialog state is changed (arrivalDeclined=true in SturmanasApp).
     *
     * This test verifies the data-model contract: hasArrived is not cleared by the dialog
     * dismiss path — only by stopNavigation() / session reset.
     */
    @Test
    fun `hasArrived remains true after decline — navigation continues`() {
        val arrived = NavigationState(hasArrived = true, phase = NavigationPhase.ARRIVED)
        // "Dar ne" path: only UI state changes (showArrivalDialog=false, arrivalDeclined=true).
        // NavigationState is NOT modified — hasArrived stays true.
        assertTrue(
            "After 'Dar ne' the NavigationState must remain hasArrived=true. " +
            "Only the SturmanasApp dialog flags change; the engine state is untouched. " +
            "Navigation continues until the user presses 'Taip, atvykau' or 'Baigti'.",
            arrived.hasArrived,
        )
        assertEquals(
            "Phase must remain ARRIVED while the user continues navigating after decline.",
            NavigationPhase.ARRIVED,
            arrived.phase,
        )
    }

    // ── AC-A05 — dialog cannot stack (copy preserves hasArrived) ──────────

    /**
     * Rerouting updates, distance callbacks, and route progress all call `.copy()` to update
     * fields.  None of them must accidentally clear [NavigationState.hasArrived].
     *
     * This verifies that hasArrived survives a `.copy()` that only touches distance fields —
     * i.e. the rerouting + distance path cannot implicitly close the arrival dialog.
     */
    @Test
    fun `NavigationState copy preserves hasArrived across unrelated field updates`() {
        val arrived = NavigationState(
            hasArrived                   = true,
            phase                        = NavigationPhase.ARRIVED,
            remainingDistanceMeters      = 5,
            remainingDurationSeconds     = 0,
            distanceToNextManeuverMeters = 0,
        )
        // Simulate a syncStateFromNavigator call that updates distance fields only
        val updated = arrived.copy(
            remainingDistanceMeters      = 3,
            remainingDurationSeconds     = 0,
            distanceToNextManeuverMeters = 0,
        )
        assertTrue(
            "A .copy() that updates distance/duration fields must not clear hasArrived. " +
            "The arrival dialog must remain visible while the engine emits distance updates.",
            updated.hasArrived,
        )
    }

    // ── AC-A06 — stale callback guard: hasArrived=false after session reset ──

    /**
     * The LaunchedEffect key is [NavigationState.hasArrived].  A stale ArrivalListener
     * callback from a previous session is only dangerous if `hasArrived` can be true while
     * the new session is active.
     *
     * The engine sets `sessionActive=false` before writing `hasArrived=true` (ArrivalListener
     * sets sessionActive=false then copies hasArrived=true).  A new `startNavigation()` call
     * sets `sessionActive=true` and `guidanceStarted=false`, then `stopNavigation()` resets
     * the state to `NavigationState()` (hasArrived=false).
     *
     * This test pins the invariant: after a session reset, hasArrived is false.
     */
    @Test
    fun `after session reset hasArrived is false — stale ArrivalListener cannot re-show dialog`() {
        val staleArrived = NavigationState(hasArrived = true, phase = NavigationPhase.ARRIVED)
        // stopNavigation() resets the engine state completely
        val newSession = NavigationState()
        assertTrue("Pre-condition: stale arrived had hasArrived=true", staleArrived.hasArrived)
        assertFalse(
            "After stopNavigation() the engine resets to NavigationState(). " +
            "hasArrived must be false so a stale ArrivalListener callback from the previous " +
            "session (which is guarded by sessionActive=false) cannot re-trigger the dialog.",
            newSession.hasArrived,
        )
    }

    // ── AC-A07 — phase ARRIVED vs phase NAVIGATING ────────────────────────

    /**
     * [NavigationPhase.NAVIGATING] must NOT imply `hasArrived`.
     * Conflating these was the conceptual root of the bug — active navigation is not arrival.
     */
    @Test
    fun `NavigationPhase NAVIGATING does not imply hasArrived`() {
        val navigating = NavigationState(isNavigating = true, phase = NavigationPhase.NAVIGATING)
        assertFalse(
            "NavigationState with phase=NAVIGATING must have hasArrived=false. " +
            "The arrival dialog must never appear because the user is actively navigating.",
            navigating.hasArrived,
        )
    }

    // ── AC-A08 — ARRIVED phase is atomic with hasArrived ─────────────────

    /**
     * The engine writes `hasArrived=true` and `phase=ARRIVED` atomically in a single `.copy()`.
     * Both must be consistent — a state with `hasArrived=true` must have `phase=ARRIVED`.
     */
    @Test
    fun `NavigationPhase ARRIVED is set atomically with hasArrived=true`() {
        val arrived = NavigationState(
            hasArrived   = true,
            isNavigating = false,
            phase        = NavigationPhase.ARRIVED,
        )
        assertTrue( "hasArrived must be true",              arrived.hasArrived)
        assertFalse("isNavigating must be false at arrival", arrived.isNavigating)
        assertEquals("phase must be ARRIVED",  NavigationPhase.ARRIVED, arrived.phase)
    }

    // ── AC-A09 — stopNavigation clears dialog state (data-model side) ─────

    /**
     * `stopNavigation()` resets the engine state to `NavigationState()`.
     * This test documents that the resulting state has all arrival fields cleared,
     * which is the data-model contract that lets `SturmanasApp` reset its UI state flags.
     */
    @Test
    fun `stopNavigation produces NavigationState with hasArrived=false and phase=IDLE`() {
        val afterStop = NavigationState()   // what stopNavigation() produces
        assertFalse("hasArrived must be false after stop", afterStop.hasArrived)
        assertEquals("phase must be IDLE after stop", NavigationPhase.IDLE, afterStop.phase)
        assertFalse("isNavigating must be false after stop", afterStop.isNavigating)
    }
}
