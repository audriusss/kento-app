package lt.sturmanas.bajeristas.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for [ManeuverMapper].
 *
 * Maneuver is a Java @IntDef annotation from the Navigation SDK 7.8.0 TBT model:
 *   com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
 *
 * Its constants (e.g. Maneuver.STRAIGHT) are plain Int fields, not enum instances.
 * Every call to [ManeuverMapper.fromSdk] passes an Int — [Maneuver.CONSTANT] resolves
 * to the integer value at the call site.
 *
 * Constants absent from SDK 7.8.0 (tests removed):
 *   KEEP_LEFT, KEEP_RIGHT, UTURN_LEFT, UTURN_RIGHT
 *
 * Null handling is tested via [ManeuverMapper.fromSdkNullable], which is the
 * null-safe entry point. [ManeuverMapper.fromSdk] only accepts non-null Int.
 */
class ManeuverMapperTest {

    // ── Null / missing maneuver ───────────────────────────────────────────

    @Test
    fun `null maneuver maps to UNKNOWN via fromSdkNullable`() {
        // Null means the SDK did not supply a maneuver value — treated as UNKNOWN,
        // not NONE, because NONE is reserved for "not navigating at all".
        assertEquals(ManeuverType.UNKNOWN, ManeuverMapper.fromSdkNullable(null))
    }

    // ── Straight-ahead ────────────────────────────────────────────────────

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

    // KEEP_LEFT / KEEP_RIGHT removed: constants do not exist in SDK 7.8.0 TBT Maneuver.

    @Test
    fun `TURN_SHARP_LEFT maps to SHARP_LEFT`() {
        assertEquals(ManeuverType.SHARP_LEFT, ManeuverMapper.fromSdk(Maneuver.TURN_SHARP_LEFT))
    }

    @Test
    fun `TURN_SHARP_RIGHT maps to SHARP_RIGHT`() {
        assertEquals(ManeuverType.SHARP_RIGHT, ManeuverMapper.fromSdk(Maneuver.TURN_SHARP_RIGHT))
    }

    // ── U-turns ───────────────────────────────────────────────────────────
    // UTURN_LEFT / UTURN_RIGHT removed: constants do not exist in SDK 7.8.0 TBT Maneuver.
    // U-turn intent is expressed by ROUNDABOUT_U_TURN (tested in the roundabout group).

    // ── Roundabouts ───────────────────────────────────────────────────────

    @Test
    fun `ROUNDABOUT_LEFT maps to ROUNDABOUT`() {
        assertEquals(ManeuverType.ROUNDABOUT, ManeuverMapper.fromSdk(Maneuver.ROUNDABOUT_LEFT))
    }

    @Test
    fun `ROUNDABOUT_RIGHT maps to ROUNDABOUT`() {
        assertEquals(ManeuverType.ROUNDABOUT, ManeuverMapper.fromSdk(Maneuver.ROUNDABOUT_RIGHT))
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

    // ── Safety integration: MERGE and FORK are complex ────────────────────

    @Test
    fun `MERGE and FORK are safety-blocked by SafetyController`() {
        // ManeuverMapper.fromSdk returns MERGE / FORK → SafetyController marks BLOCKED
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
    fun `FERRY maps to COMPLEX_JUNCTION`() {
        assertEquals(ManeuverType.COMPLEX_JUNCTION, ManeuverMapper.fromSdk(Maneuver.FERRY))
    }

    @Test
    fun `out-of-range integer returns UNKNOWN — never throws`() {
        // Maneuver is an @IntDef, not an enum; fromSdkOrdinal passes the int directly.
        // An integer that matches no Maneuver constant hits else → UNKNOWN.
        assertEquals(ManeuverType.UNKNOWN, ManeuverMapper.fromSdkOrdinal(Int.MAX_VALUE))
    }

    @Test
    fun `TURN_LEFT and TURN_RIGHT map to different internal types`() {
        assertNotEquals(
            ManeuverMapper.fromSdk(Maneuver.TURN_LEFT),
            ManeuverMapper.fromSdk(Maneuver.TURN_RIGHT),
        )
    }
}
