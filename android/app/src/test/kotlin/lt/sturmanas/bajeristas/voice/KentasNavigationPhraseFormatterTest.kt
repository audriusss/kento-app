package lt.sturmanas.bajeristas.voice

import lt.sturmanas.bajeristas.navigation.ManeuverType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [KentasNavigationPhraseFormatter].
 *
 * All pure-JVM — no Android SDK dependency.
 *
 * Spec acceptance criteria covered:
 *   AC-F01  All navigation ManeuverTypes that need phrases produce non-blank output
 *   AC-F02  NONE / STRAIGHT / UNKNOWN produce blank output (no announcement)
 *   AC-F03  {dist} placeholder is replaced with formatted distance
 *   AC-F04  {road} placeholder is replaced when nextRoadName is non-blank
 *   AC-F05  {road} is empty when nextRoadName is blank
 *   AC-F06  Phrases rotate (second call returns different text for multi-phrase types)
 *   AC-F07  ARRIVE produces non-blank output with no distance
 *   AC-F08  Rotation resets after full cycle (wraps around to first phrase)
 */
class KentasNavigationPhraseFormatterTest {

    private lateinit var formatter: KentasNavigationPhraseFormatter

    @Before
    fun setUp() {
        formatter = KentasNavigationPhraseFormatter()
    }

    // ── AC-F01 ────────────────────────────────────────────────────────────

    @Test
    fun `TURN_LEFT produces a non-blank phrase`() {
        val phrase = formatter.format(ManeuverType.TURN_LEFT, 500, "Laisvės al.")
        assertTrue("phrase must not be blank for TURN_LEFT", phrase.isNotBlank())
    }

    @Test
    fun `TURN_RIGHT produces a non-blank phrase`() {
        val phrase = formatter.format(ManeuverType.TURN_RIGHT, 200, "Gedimino g.")
        assertTrue(phrase.isNotBlank())
    }

    @Test
    fun `UTURN produces a non-blank phrase`() {
        val phrase = formatter.format(ManeuverType.UTURN, 150, "")
        assertTrue(phrase.isNotBlank())
    }

    @Test
    fun `ROUNDABOUT produces a non-blank phrase`() {
        assertTrue(formatter.format(ManeuverType.ROUNDABOUT, 300, "").isNotBlank())
    }

    @Test
    fun `MOTORWAY_EXIT produces a non-blank phrase`() {
        assertTrue(formatter.format(ManeuverType.MOTORWAY_EXIT, 400, "").isNotBlank())
    }

    @Test
    fun `MERGE produces a non-blank phrase`() {
        assertTrue(formatter.format(ManeuverType.MERGE, 200, "").isNotBlank())
    }

    @Test
    fun `FORK produces a non-blank phrase`() {
        assertTrue(formatter.format(ManeuverType.FORK, 300, "").isNotBlank())
    }

    @Test
    fun `ARRIVE produces a non-blank phrase without requiring distance`() {
        val phrase = formatter.format(ManeuverType.ARRIVE, 0, "")
        assertTrue("ARRIVE phrase must not be blank", phrase.isNotBlank())
    }

    // ── AC-F02 ────────────────────────────────────────────────────────────

    @Test
    fun `NONE produces blank output — no announcement`() {
        val phrase = formatter.format(ManeuverType.NONE, 500, "some road")
        assertTrue("NONE must produce blank phrase", phrase.isBlank())
    }

    @Test
    fun `STRAIGHT produces blank output — no announcement`() {
        val phrase = formatter.format(ManeuverType.STRAIGHT, 200, "A1")
        assertTrue("STRAIGHT must produce blank phrase", phrase.isBlank())
    }

    @Test
    fun `UNKNOWN produces blank output — no announcement`() {
        val phrase = formatter.format(ManeuverType.UNKNOWN, 100, "")
        assertTrue("UNKNOWN must produce blank phrase", phrase.isBlank())
    }

    // ── AC-F03 ────────────────────────────────────────────────────────────

    @Test
    fun `distance is embedded in the phrase`() {
        // 500 m → "500 metrų"
        val phrase = formatter.format(ManeuverType.TURN_LEFT, 500, "")
        assertTrue("phrase must contain the distance '500 metrų'", phrase.contains("500 metrų"))
    }

    @Test
    fun `kilometre distance is embedded in the phrase`() {
        // 4000 m → "apie 4 kilometrus"
        val phrase = formatter.format(ManeuverType.TURN_RIGHT, 4000, "")
        assertTrue(
            "phrase must contain formatted km distance",
            phrase.contains("kilometrus") || phrase.contains("kilometrą") || phrase.contains("kilometro"),
        )
    }

    // ── AC-F04 / AC-F05 ───────────────────────────────────────────────────

    @Test
    fun `road name is included when nextRoadName is non-blank`() {
        val phrase = formatter.format(ManeuverType.TURN_LEFT, 300, "Taikos g.")
        assertTrue("phrase must include road name when provided", phrase.contains("Taikos g."))
    }

    @Test
    fun `road suffix is absent when nextRoadName is blank`() {
        val phrase = formatter.format(ManeuverType.TURN_LEFT, 300, "")
        // Should not contain the road-prefix preposition "į" followed by nothing
        assertFalse("blank road must not leave dangling 'į '", phrase.contains("į ."))
        assertTrue("phrase with blank road must still be non-blank", phrase.isNotBlank())
    }

    // ── AC-F06 / AC-F08 ───────────────────────────────────────────────────

    @Test
    fun `phrases rotate for maneuvers with multiple templates`() {
        // TURN_LEFT has 3 templates — three consecutive calls must not all be identical
        val p1 = formatter.format(ManeuverType.TURN_LEFT, 300, "")
        val p2 = formatter.format(ManeuverType.TURN_LEFT, 300, "")
        val p3 = formatter.format(ManeuverType.TURN_LEFT, 300, "")

        // At least two of the three should differ (rotation across 3 templates)
        val distinct = setOf(p1, p2, p3)
        assertTrue(
            "TURN_LEFT should produce at least 2 distinct phrases in 3 calls (rotation)",
            distinct.size >= 2,
        )
    }

    @Test
    fun `phrase rotation wraps back to first after all templates used`() {
        // ARRIVE has 3 templates — call 4 should equal call 1
        val first  = formatter.format(ManeuverType.ARRIVE, 0, "")
        formatter.format(ManeuverType.ARRIVE, 0, "")
        formatter.format(ManeuverType.ARRIVE, 0, "")
        val fourth = formatter.format(ManeuverType.ARRIVE, 0, "")
        assertEquals("rotation must wrap around to the first template", first, fourth)
    }

    // ── Regression ────────────────────────────────────────────────────────

    @Test
    fun `separate maneuver types have independent rotation cursors`() {
        // Rotating TURN_LEFT should not affect TURN_RIGHT counter
        val right1 = formatter.format(ManeuverType.TURN_RIGHT, 200, "")
        formatter.format(ManeuverType.TURN_LEFT, 200, "")
        formatter.format(ManeuverType.TURN_LEFT, 200, "")
        val right2 = formatter.format(ManeuverType.TURN_RIGHT, 200, "")
        // right2 should be the second TURN_RIGHT phrase, not influenced by TURN_LEFT calls
        assertNotEquals("TURN_RIGHT rotation must be independent of TURN_LEFT", right1, right2)
    }
}
