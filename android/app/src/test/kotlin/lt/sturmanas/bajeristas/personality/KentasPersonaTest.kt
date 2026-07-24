package lt.sturmanas.bajeristas.personality

import org.junit.Assert.*
import org.junit.Test

/**
 * Acceptance tests for [KentasPersona] and [formatDistance].
 *
 * All pure-JVM — no Android SDK dependency.
 *
 * Spec acceptance criteria covered:
 *   AC-P01  systemPrompt is non-blank
 *   AC-P02  systemPrompt is written in Lithuanian (spot-check)
 *   AC-P03  systemPrompt does not mention SOFT/HARD/TripMode/HumorIntensity (removed)
 *   AC-P04  navigationContext includes maneuver label and distance
 *   AC-P05  navigationContext warns when distance ≤ 300 m
 *   AC-P06  formatDistance edge cases (mirrors DistanceFormatterTest)
 */
class KentasPersonaTest {

    // ── AC-P01 / AC-P02 ───────────────────────────────────────────────────

    @Test
    fun `systemPrompt is not blank`() {
        assertTrue("systemPrompt must not be blank", KentasPersona.systemPrompt.isNotBlank())
    }

    @Test
    fun `systemPrompt contains core Lithuanian identity text`() {
        assertTrue(
            "systemPrompt must mention Kentas in Lithuanian",
            KentasPersona.systemPrompt.contains("lietuviškai"),
        )
    }

    // ── AC-P03 ───────────────────────────────────────────────────────────

    @Test
    fun `systemPrompt does not reference removed personality configuration`() {
        val prompt = KentasPersona.systemPrompt
        assertFalse("SOFT mode should be removed", prompt.contains("SOFT"))
        assertFalse("HARD mode should be removed", prompt.contains("KALBĖJIMO STILIUS – HARD"))
        assertFalse("TripMode should be removed", prompt.contains("KELIONĖS REŽIMAS"))
        assertFalse("HumorIntensity should be removed", prompt.contains("HUMORO INTENSYVUMAS"))
    }

    // ── AC-P04 ───────────────────────────────────────────────────────────

    @Test
    fun `navigationContext includes maneuver label`() {
        val ctx = KentasPersona.navigationContext(
            nextManeuver             = "TURN_RIGHT",
            street                   = "Taikos",
            distanceToManeuverMeters = 500,
            remainingDistanceMeters  = 3000,
            remainingSeconds         = 240,
        )
        assertTrue("context should contain maneuver label", ctx.contains("Sukti dešinėn"))
        assertTrue("context should contain street name",  ctx.contains("Taikos"))
    }

    @Test
    fun `navigationContext includes distance text`() {
        val ctx = KentasPersona.navigationContext(
            nextManeuver             = "TURN_LEFT",
            street                   = "Gedimino",
            distanceToManeuverMeters = 150,
            remainingDistanceMeters  = 1500,
            remainingSeconds         = 120,
        )
        assertTrue("context should contain distance", ctx.contains("metrų") || ctx.contains("kilometro"))
    }

    @Test
    fun `navigationContext includes remaining time`() {
        val ctx = KentasPersona.navigationContext(
            nextManeuver             = "STRAIGHT",
            street                   = "A1",
            distanceToManeuverMeters = 2000,
            remainingDistanceMeters  = 20000,
            remainingSeconds         = 600,
        )
        // 600 s / 60 = 10 min
        assertTrue("context should include 10 min", ctx.contains("10 min"))
    }

    // ── AC-P05 ───────────────────────────────────────────────────────────

    @Test
    fun `navigationContext warns when maneuver is within 300m`() {
        val ctx = KentasPersona.navigationContext(
            nextManeuver             = "ROUNDABOUT",
            street                   = "Žiedas",
            distanceToManeuverMeters = 250,
            remainingDistanceMeters  = 1000,
            remainingSeconds         = 60,
        )
        assertTrue(
            "context must include maneuver-approaching warning within 300 m",
            ctx.contains("MANEVRAS ARTĖJA"),
        )
    }

    @Test
    fun `navigationContext shows safe conversation marker beyond 300m`() {
        val ctx = KentasPersona.navigationContext(
            nextManeuver             = "TURN_RIGHT",
            street                   = "Laisvės al.",
            distanceToManeuverMeters = 400,
            remainingDistanceMeters  = 5000,
            remainingSeconds         = 300,
        )
        assertTrue(
            "context must show safe conversation indicator beyond 300 m",
            ctx.contains("Saugus pokalbio momentas"),
        )
    }

    // ── AC-P06 — formatDistance (same cases as DistanceFormatterTest) ──────

    @Test fun `formatDistance 2280m returns decimal kilometres`() =
        assertEquals("apie 2,3 kilometro", formatDistance(2280))

    @Test fun `formatDistance 4189m returns whole kilometres`() =
        assertEquals("apie 4 kilometrus", formatDistance(4189))

    @Test fun `formatDistance 150m returns metres`() =
        assertEquals("150 metrų", formatDistance(150))

    @Test fun `formatDistance 0m returns zero metres`() =
        assertEquals("0 metrų", formatDistance(0))

    @Test fun `formatDistance 1000m uses accusative singular kilometrą`() =
        assertEquals("apie 1 kilometrą", formatDistance(1000))
}
