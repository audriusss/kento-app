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

    // ── Complex maneuver priority tests ───────────────────────────────────

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
        val state = NavigationState(isNavigating = false, distanceToManeuverMeters = 10)
        assertEquals(ConversationPermission.ALLOWED, controller.getPermission(state))
    }

    @Test
    fun `not navigating — shouldInterruptAudio always false`() {
        val state = NavigationState(isNavigating = false, distanceToManeuverMeters = 10)
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
        // SafetyController must function without any AI involvement.
        // Simply verifying it returns correct results with no AI objects injected.
        val state = nav(1000, ManeuverType.STRAIGHT)
        assertEquals(ConversationPermission.ALLOWED, controller.getPermission(state))
    }

    @Test
    fun `standard navigation voice fallback — BLOCKED state does not prevent navigation`() {
        // When BLOCKED, the AI is silent but navigation must continue unaffected.
        // SafetyController does not touch NavigationController — this test
        // confirms BLOCKED is an audio-only decision.
        val state = nav(100, ManeuverType.TURN_RIGHT)
        val permission = controller.getPermission(state)
        assertEquals(ConversationPermission.BLOCKED, permission)
        // NavigationState itself is unchanged — navigation data is intact.
        assertEquals(100, state.distanceToManeuverMeters)
        assertEquals(ManeuverType.TURN_RIGHT, state.nextManeuver)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun nav(distance: Int, maneuver: ManeuverType) = NavigationState(
        isNavigating = true,
        distanceToManeuverMeters = distance,
        nextManeuver = maneuver,
    )
}
