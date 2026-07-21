package lt.sturmanas.bajeristas.safety

import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SafetyControllerTest {

    private lateinit var controller: SafetyController

    @Before
    fun setUp() {
        controller = SafetyController()
    }

    // ── Distance threshold tests ──────────────────────────────────────────

    @Test
    fun `distance above 500m — ALLOWED`() {
        assertEquals(ConversationPermission.ALLOWED, controller.getPermission(nav(501, ManeuverType.TURN_RIGHT)))
    }

    @Test
    fun `distance exactly 500m — boundary is ALLOWED`() {
        assertEquals(ConversationPermission.ALLOWED, controller.getPermission(nav(500, ManeuverType.STRAIGHT)))
    }

    @Test
    fun `distance at 499m — SHORT_ONLY`() {
        assertEquals(ConversationPermission.SHORT_ONLY, controller.getPermission(nav(499, ManeuverType.TURN_LEFT)))
    }

    @Test
    fun `distance at 201m — SHORT_ONLY`() {
        assertEquals(ConversationPermission.SHORT_ONLY, controller.getPermission(nav(201, ManeuverType.TURN_RIGHT)))
    }

    @Test
    fun `distance exactly 200m — boundary is BLOCKED`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(200, ManeuverType.STRAIGHT)))
    }

    @Test
    fun `distance below 200m — BLOCKED`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(50, ManeuverType.TURN_RIGHT)))
    }

    // ── Complex maneuver priority tests (Phase 1 maneuvers) ──────────────

    @Test
    fun `roundabout at 1000m — BLOCKED (complex maneuver overrides distance)`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(1000, ManeuverType.ROUNDABOUT)))
    }

    @Test
    fun `motorway exit at 800m — BLOCKED`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(800, ManeuverType.MOTORWAY_EXIT)))
    }

    @Test
    fun `lane change at 600m — BLOCKED`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(600, ManeuverType.LANE_CHANGE)))
    }

    @Test
    fun `complex junction at 999m — BLOCKED`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(999, ManeuverType.COMPLEX_JUNCTION)))
    }

    @Test
    fun `uturn at 700m — BLOCKED`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(700, ManeuverType.UTURN)))
    }

    // ── Phase 2 complex maneuvers: MERGE and FORK ─────────────────────────

    @Test
    fun `merge at 1500m — BLOCKED (complex, driver must judge moving lane gap)`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(1500, ManeuverType.MERGE)))
    }

    @Test
    fun `fork at 800m — BLOCKED (complex, two-path decision at speed)`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(800, ManeuverType.FORK)))
    }

    @Test
    fun `merge at short distance — still BLOCKED`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(50, ManeuverType.MERGE)))
    }

    @Test
    fun `fork interrupts audio at 900m`() {
        assertTrue(controller.shouldInterruptAudio(nav(900, ManeuverType.FORK)))
    }

    @Test
    fun `merge interrupts audio at 2000m`() {
        assertTrue(controller.shouldInterruptAudio(nav(2000, ManeuverType.MERGE)))
    }

    // ── Phase 2 non-complex maneuvers — simple at distance ────────────────

    @Test
    fun `slight left at 600m — ALLOWED`() {
        assertEquals(ConversationPermission.ALLOWED, controller.getPermission(nav(600, ManeuverType.SLIGHT_LEFT)))
    }

    @Test
    fun `sharp right at 300m — SHORT_ONLY`() {
        assertEquals(ConversationPermission.SHORT_ONLY, controller.getPermission(nav(300, ManeuverType.SHARP_RIGHT)))
    }

    @Test
    fun `arrive maneuver at 0m — BLOCKED by distance`() {
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(nav(0, ManeuverType.ARRIVE)))
    }

    @Test
    fun `unknown maneuver at 600m — ALLOWED (treated as simple)`() {
        assertEquals(ConversationPermission.ALLOWED, controller.getPermission(nav(600, ManeuverType.UNKNOWN)))
    }

    // ── shouldInterruptAudio tests ────────────────────────────────────────

    @Test
    fun `interrupt — false when distance above 200m and simple maneuver`() {
        assertFalse(controller.shouldInterruptAudio(nav(250, ManeuverType.TURN_RIGHT)))
    }

    @Test
    fun `interrupt — true at exactly 200m`() {
        assertTrue(controller.shouldInterruptAudio(nav(200, ManeuverType.TURN_RIGHT)))
    }

    @Test
    fun `interrupt — true below 200m`() {
        assertTrue(controller.shouldInterruptAudio(nav(100, ManeuverType.TURN_LEFT)))
    }

    @Test
    fun `interrupt — true for roundabout at long distance`() {
        assertTrue(controller.shouldInterruptAudio(nav(1500, ManeuverType.ROUNDABOUT)))
    }

    @Test
    fun `interrupt — true for motorway exit regardless of distance`() {
        assertTrue(controller.shouldInterruptAudio(nav(900, ManeuverType.MOTORWAY_EXIT)))
    }

    // ── Not-navigating tests ──────────────────────────────────────────────

    @Test
    fun `not navigating — always ALLOWED regardless of distance`() {
        val state = NavigationState(isNavigating = false, distanceToNextManeuverMeters = 10)
        assertEquals(ConversationPermission.ALLOWED, controller.getPermission(state))
    }

    @Test
    fun `not navigating — shouldInterruptAudio always false`() {
        val state = NavigationState(isNavigating = false, distanceToNextManeuverMeters = 10)
        assertFalse(controller.shouldInterruptAudio(state))
    }

    // ── AI independence tests ─────────────────────────────────────────────

    @Test
    fun `SafetyController is deterministic — same input always produces same output`() {
        val state = nav(850, ManeuverType.TURN_RIGHT)
        val first = controller.getPermission(state)
        val second = controller.getPermission(state)
        assertEquals("SafetyController must be deterministic", first, second)
    }

    @Test
    fun `navigation continues independent of AI state — SafetyController has no AI dependency`() {
        val state = nav(1000, ManeuverType.STRAIGHT)
        assertEquals(ConversationPermission.ALLOWED, controller.getPermission(state))
    }

    @Test
    fun `BLOCKED state is audio-only — NavigationState data is unchanged`() {
        val state = nav(100, ManeuverType.TURN_RIGHT)
        val permission = controller.getPermission(state)
        assertEquals(ConversationPermission.BLOCKED, permission)
        // NavigationState is immutable; BLOCKED does not modify it
        assertEquals(100, state.distanceToNextManeuverMeters)
        assertEquals(ManeuverType.TURN_RIGHT, state.maneuverType)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun nav(distance: Int, maneuver: ManeuverType) = NavigationState(
        isNavigating = true,
        distanceToNextManeuverMeters = distance,
        maneuverType = maneuver,
    )
}
