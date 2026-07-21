package lt.sturmanas.bajeristas.personality

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [formatDistance].
 *
 * The function is [internal], so tests in the same package access it directly.
 * These tests must pass before any prompt or navigation-context change ships.
 *
 * Required cases from spec:
 *   2280  → "apie 2,3 kilometro"
 *   4189  → "apie 4 kilometrus"
 *   150   → "150 metrų"
 */
class DistanceFormatterTest {

    // ── Required spec cases ───────────────────────────────────────────────

    @Test
    fun `2280m formats as decimal kilometres`() {
        // 2280 / 100 = 22.8 → rounds to 23 tenths → whole=2, decimal=3
        assertEquals("apie 2,3 kilometro", formatDistance(2280))
    }

    @Test
    fun `4189m formats as whole kilometres`() {
        // 4189 ≥ 3000 → 4189 / 1000 = 4.189 → rounds to 4
        assertEquals("apie 4 kilometrus", formatDistance(4189))
    }

    @Test
    fun `150m formats as metres`() {
        assertEquals("150 metrų", formatDistance(150))
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Test
    fun `0m returns zero metres, not zero kilometres`() {
        // The 0 km bug: integer division of 0 / 1000 = 0 previously produced "~0 km".
        // The fixed implementation must return metres for any value under 1000.
        assertEquals("0 metrų", formatDistance(0))
    }

    @Test
    fun `999m is the last value shown as metres`() {
        assertEquals("999 metrų", formatDistance(999))
    }

    @Test
    fun `1000m is the first value shown as kilometres`() {
        // 1000 / 100 = 10 tenths → whole=1, decimal=0 → no decimal shown
        assertEquals("apie 1 kilometrus", formatDistance(1000))
    }

    @Test
    fun `2950m rounds up to 3 at the 1-decimal boundary`() {
        // 2950 / 100 = 29.5 → rounds to 30 tenths → whole=3, decimal=0 → whole km form
        assertEquals("apie 3 kilometrus", formatDistance(2950))
    }

    @Test
    fun `exactly 3000m enters the whole-km branch`() {
        assertEquals("apie 3 kilometrus", formatDistance(3000))
    }

    @Test
    fun `large distance rounds correctly`() {
        // 14600 / 1000 = 14.6 → rounds to 15
        assertEquals("apie 15 kilometrus", formatDistance(14600))
    }
}
