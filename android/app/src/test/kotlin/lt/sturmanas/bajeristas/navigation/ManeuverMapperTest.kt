package lt.sturmanas.bajeristas.navigation

import com.google.android.libraries.navigation.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for [ManeuverMapper].
 *
 * These tests import [com.google.android.libraries.navigation.Maneuver] from the Navigation SDK
 * (added as an `implementation` dependency). They compile and run on the JVM as unit tests because
 * [Maneuver] is a plain Kotlin enum with no Android-runtime dependency.
 *
 * If tests fail with ClassNotFoundException, the Navigation SDK is not in the JVM classpath —
 * run them as instrumented tests in Android Studio instead.
 */
class ManeuverMapperTest {

    // ── Null and NONE ─────────────────────────────────────────────────────

    @Test
    fun `null maps to NONE`() {
        assertEquals(ManeuverType.NONE, ManeuverMapper.fromSdk(null))
    }

    // ── Simple straight-ahead maneuvers ───────────────────────────────────

    @Test
    fun `STRAIGHT maps to STRAIGHT`() {
        assertEquals(ManeuverType.STRAIGHT, ManeuverMapper.fromSdk(Maneuver.STRAIGHT))
    }

    @Test
    fun `DEPART maps to STRAIGHT`() {
        assertEquals(ManeuverType.STRAIGHT, ManeuverMapper.fromSdk(Maneuver.DEPART))
    }

    // ── Turns ─────────────────────────────────────────────────────────────

    @Test
    fun `TURN_LEFT maps to TURN_LEFT`() {
        assertEquals(ManeuverType.TURN_LEFT, ManeuverMapper.fromSdk(Maneuver.TURN_LEFT))
    }

    @Test
    fun `TURN_RIGHT maps to TURN_RIGHT`() {
        assertEquals(ManeuverType.TURN_RIGHT, ManeuverMapper.fromSdk(Maneuver.TURN_RIGHT))
    }

    @Test
    fun `TURN_SLIGHT_LEFT maps to SLIGHT_LEFT`() {
        assertEquals(ManeuverType.SLIGHT_LEFT, ManeuverMapper.fromSdk(Maneuver.TURN_SLIGHT_LEFT))
    }

    @Test
    fun `TURN_SLIGHT_RIGHT maps to SLIGHT_RIGHT`() {
        assertEquals(ManeuverType.SLIGHT_RIGHT, ManeuverMapper.fromSdk(Maneuver.TURN_SLIGHT_RIGHT))
    }

    @Test
    fun `KEEP_LEFT maps to SLIGHT_LEFT`() {
        assertEquals(ManeuverType.SLIGHT_LEFT, ManeuverMapper.fromSdk(Maneuver.KEEP_LEFT))
    }

    @Test
    fun `KEEP_RIGHT maps to SLIGHT_RIGHT`() {
        assertEquals(ManeuverType.SLIGHT_RIGHT, ManeuverMapper.fromSdk(Maneuver.KEEP_RIGHT))
    }

    @Test
    fun `TURN_SHARP_LEFT maps to SHARP_LEFT`() {
        assertEquals(ManeuverType.SHARP_LEFT, ManeuverMapper.fromSdk(Maneuver.TURN_SHARP_LEFT))
    }

    @Test
    fun `TURN_SHARP_RIGHT maps to SHARP_RIGHT`() {
        assertEquals(ManeuverType.SHARP_RIGHT, ManeuverMapper.fromSdk(Maneuver.TURN_SHARP_RIGHT))
    }

    // ── U-turns ───────────────────────────────────────────────────────────

    @Test
    fun `UTURN_LEFT maps to UTURN`() {
        assertEquals(ManeuverType.UTURN, ManeuverMapper.fromSdk(Maneuver.UTURN_LEFT))
    }

    @Test
    fun `UTURN_RIGHT maps to UTURN`() {
        assertEquals(ManeuverType.UTURN, ManeuverMapper.fromSdk(Maneuver.UTURN_RIGHT))
    }

    // ── Roundabouts ───────────────────────────────────────────────────────

    @Test
    fun `ROUNDABOUT_LEFT maps to ROUNDABOUT`() {
        assertEquals(ManeuverType.ROUNDABOUT, ManeuverMapper.fromSdk(Maneuver.ROUNDABOUT_LEFT))
    }

    @Test
    fun `ROUNDABOUT_RIGHT maps to ROUNDABOUT`() {
        assertEquals(ManeuverType.ROUNDABOUT, ManeuverMapper.fromSdk(Maneuver.ROUNDABOUT_RIGHT))
    }

    // ── Motorway exits ────────────────────────────────────────────────────

    @Test
    fun `OFF_RAMP_LEFT maps to MOTORWAY_EXIT`() {
        assertEquals(ManeuverType.MOTORWAY_EXIT, ManeuverMapper.fromSdk(Maneuver.OFF_RAMP_LEFT))
    }

    @Test
    fun `OFF_RAMP_RIGHT maps to MOTORWAY_EXIT`() {
        assertEquals(ManeuverType.MOTORWAY_EXIT, ManeuverMapper.fromSdk(Maneuver.OFF_RAMP_RIGHT))
    }

    // ── Merges ────────────────────────────────────────────────────────────

    @Test
    fun `MERGE_LEFT maps to MERGE`() {
        assertEquals(ManeuverType.MERGE, ManeuverMapper.fromSdk(Maneuver.MERGE_LEFT))
    }

    @Test
    fun `MERGE_RIGHT maps to MERGE`() {
        assertEquals(ManeuverType.MERGE, ManeuverMapper.fromSdk(Maneuver.MERGE_RIGHT))
    }

    @Test
    fun `ON_RAMP_LEFT maps to MERGE`() {
        assertEquals(ManeuverType.MERGE, ManeuverMapper.fromSdk(Maneuver.ON_RAMP_LEFT))
    }

    // ── Forks ─────────────────────────────────────────────────────────────

    @Test
    fun `FORK_LEFT maps to FORK`() {
        assertEquals(ManeuverType.FORK, ManeuverMapper.fromSdk(Maneuver.FORK_LEFT))
    }

    @Test
    fun `FORK_RIGHT maps to FORK`() {
        assertEquals(ManeuverType.FORK, ManeuverMapper.fromSdk(Maneuver.FORK_RIGHT))
    }

    // ── Arrival ───────────────────────────────────────────────────────────

    @Test
    fun `DESTINATION maps to ARRIVE`() {
        assertEquals(ManeuverType.ARRIVE, ManeuverMapper.fromSdk(Maneuver.DESTINATION))
    }

    @Test
    fun `DESTINATION_LEFT maps to ARRIVE`() {
        assertEquals(ManeuverType.ARRIVE, ManeuverMapper.fromSdk(Maneuver.DESTINATION_LEFT))
    }

    @Test
    fun `DESTINATION_RIGHT maps to ARRIVE`() {
        assertEquals(ManeuverType.ARRIVE, ManeuverMapper.fromSdk(Maneuver.DESTINATION_RIGHT))
    }

    // ── Safety-relevant: MERGE and FORK are complex ───────────────────────

    @Test
    fun `MERGE and FORK are safety-blocked by SafetyController`() {
        // Confirm ManeuverMapper + SafetyController integration:
        // MERGE → ManeuverType.MERGE → complex → BLOCKED regardless of distance
        val safety = lt.sturmanas.bajeristas.safety.SafetyController()
        val mergeState = NavigationState(
            isNavigating = true,
            maneuverType = ManeuverMapper.fromSdk(Maneuver.MERGE_RIGHT),
            distanceToNextManeuverMeters = 2000,
        )
        val forkState = NavigationState(
            isNavigating = true,
            maneuverType = ManeuverMapper.fromSdk(Maneuver.FORK_LEFT),
            distanceToNextManeuverMeters = 2000,
        )
        assertEquals(
            lt.sturmanas.bajeristas.safety.ConversationPermission.BLOCKED,
            safety.getPermission(mergeState),
        )
        assertEquals(
            lt.sturmanas.bajeristas.safety.ConversationPermission.BLOCKED,
            safety.getPermission(forkState),
        )
    }

    // ── Unknown / future SDK value safety ─────────────────────────────────

    @Test
    fun `FERRY maps to COMPLEX_JUNCTION — unusual road type`() {
        assertEquals(ManeuverType.COMPLEX_JUNCTION, ManeuverMapper.fromSdk(Maneuver.FERRY))
    }

    @Test
    fun `fromSdkOrdinal with out-of-range ordinal returns UNKNOWN — never throws`() {
        assertEquals(ManeuverType.UNKNOWN, ManeuverMapper.fromSdkOrdinal(Int.MAX_VALUE))
    }

    @Test
    fun `TURN_LEFT and TURN_RIGHT map to different internal types`() {
        assertNotEquals(
            ManeuverMapper.fromSdk(Maneuver.TURN_LEFT),
            ManeuverMapper.fromSdk(Maneuver.TURN_RIGHT),
        )
    }

    @Test
    fun `all roundabout variants map to ROUNDABOUT`() {
        listOf(
            Maneuver.ROUNDABOUT_LEFT,
            Maneuver.ROUNDABOUT_RIGHT,
            Maneuver.ROUNDABOUT_SHARP_LEFT,
            Maneuver.ROUNDABOUT_SHARP_RIGHT,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT,
            Maneuver.ROUNDABOUT_U_TURN,
        ).forEach { m ->
            assertEquals("$m should map to ROUNDABOUT", ManeuverType.ROUNDABOUT, ManeuverMapper.fromSdk(m))
        }
    }
}
