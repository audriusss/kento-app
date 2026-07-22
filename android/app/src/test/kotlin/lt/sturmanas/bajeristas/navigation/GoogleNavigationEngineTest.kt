package lt.sturmanas.bajeristas.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [GoogleNavigationEngine] helper logic.
 *
 * [GoogleNavigationEngine] depends on the Android Navigation SDK and cannot be
 * instantiated in a JVM unit test.  The logic under test is therefore exposed
 * through the companion object so it can be verified independently.
 *
 * Coverage:
 *   A. stripLocalitySuffix — locality present → stripped prefix returned
 *   B. stripLocalitySuffix — no locality → null returned
 *   C. stripLocalitySuffix — edge cases (blank prefix, near-coords queries)
 *
 * These tests pin the behaviour that drives the locality-stripped retry path
 * in [GoogleNavigationEngine.resolveAddress]: when a PlaceSearch query like
 * "degalinė, Klaipėda" finds nothing, the engine retries with just "degalinė"
 * before reporting failure to the user.
 */
class GoogleNavigationEngineTest {

    // ── A. Locality present ────────────────────────────────────────────────

    @Test fun `stripLocalitySuffix with city suffix returns POI name only`() {
        assertEquals(
            "degalinė",
            GoogleNavigationEngine.stripLocalitySuffix("degalinė, Klaipėda"),
        )
    }

    @Test fun `stripLocalitySuffix strips only the first comma-space segment`() {
        // "Akropolis, Vilnius" → "Akropolis"  (not "Vilnius" or empty)
        assertEquals(
            "Akropolis",
            GoogleNavigationEngine.stripLocalitySuffix("Akropolis, Vilnius"),
        )
    }

    @Test fun `stripLocalitySuffix with multi-word POI and city returns POI`() {
        assertEquals(
            "artimiausia vaistinė",
            GoogleNavigationEngine.stripLocalitySuffix("artimiausia vaistinė, Kaunas"),
        )
    }

    @Test fun `stripLocalitySuffix with Lietuva suffix returns query before it`() {
        assertEquals(
            "kavinė",
            GoogleNavigationEngine.stripLocalitySuffix("kavinė, Lietuva"),
        )
    }

    // ── B. No locality present → null ─────────────────────────────────────

    @Test fun `stripLocalitySuffix with no comma returns null`() {
        assertNull(GoogleNavigationEngine.stripLocalitySuffix("degalinė"))
    }

    @Test fun `stripLocalitySuffix with blank input returns null`() {
        assertNull(GoogleNavigationEngine.stripLocalitySuffix(""))
    }

    @Test fun `stripLocalitySuffix with single word returns null`() {
        assertNull(GoogleNavigationEngine.stripLocalitySuffix("Maxima"))
    }

    // ── C. Edge cases ─────────────────────────────────────────────────────

    @Test fun `stripLocalitySuffix near-coords query has no comma-space so returns null`() {
        // "parkingas near 54.68,25.27" — the comma is inside the coordinate pair,
        // not followed by a space, so it should not be treated as a locality separator.
        assertNull(GoogleNavigationEngine.stripLocalitySuffix("parkingas near 54.68,25.27"))
    }

    @Test fun `stripLocalitySuffix does not return blank prefix`() {
        // Edge: ", Vilnius" — nothing before the separator → null, not ""
        assertNull(GoogleNavigationEngine.stripLocalitySuffix(", Vilnius"))
    }

    @Test fun `stripLocalitySuffix trims whitespace from the returned prefix`() {
        assertEquals(
            "kavinė",
            GoogleNavigationEngine.stripLocalitySuffix("kavinė , Šiauliai"),
        )
    }
}
