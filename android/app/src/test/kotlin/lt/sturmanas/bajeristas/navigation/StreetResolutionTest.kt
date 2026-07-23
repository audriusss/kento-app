package lt.sturmanas.bajeristas.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused regression tests for the final stabilization pass.
 *
 * Coverage (18 tests):
 *   CANDIDATE GENERATION — [DestinationResolver.buildStreetCandidateQueries]
 *   RESULT SCORING       — [GoogleNavigationEngine.scoreStreetResult]
 *   DISTANCE SPEECH      — [distanceSpeech]
 *   ETA SPEECH           — [minuteSpeech] / [minuteNumeralLt]
 *
 * All tests run on the JVM — no Android context, no Robolectric.
 */
class StreetResolutionTest {

    // ─────────────────────────────────────────────────────────────────────────
    // CANDIDATE GENERATION
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Taikos with city generates spoken abbreviation and expanded candidates`() {
        val c = DestinationResolver.buildStreetCandidateQueries("Taikos", "61", "Klaipėda")
        assertEquals("Taikos 61, Klaipėda, Lithuania", c[0])
        assertTrue("missing pr. abbreviation", c.any { "Taikos pr. 61" in it })
        assertTrue("missing expanded form",    c.any { "Taikos prospektas 61" in it })
        assertFalse("unexpected gatvė",        c.any { "gatvė" in it })
    }

    @Test
    fun `lowercase taikos is resolved via STREET_EXPANSIONS`() {
        val c = DestinationResolver.buildStreetCandidateQueries("taikos", "61", "Klaipėda")
        // First candidate is the spoken (lowercase) form
        assertEquals("taikos 61, Klaipėda, Lithuania", c[0])
        // Expanded form uses canonical casing from the map
        assertTrue("missing expanded form", c.any { "Taikos prospektas 61" in it })
    }

    @Test
    fun `Taikos 61A number suffix is preserved verbatim`() {
        val c = DestinationResolver.buildStreetCandidateQueries("Taikos", "61A", "Klaipėda")
        assertEquals("Taikos 61A, Klaipėda, Lithuania", c[0])
        assertTrue(c.any { "Taikos prospektas 61A" in it })
    }

    @Test
    fun `Minijos with dash-number generates gatvė variant`() {
        // "Minijos" is in STREET_EXPANSIONS → "Minijos gatvė"
        val c = DestinationResolver.buildStreetCandidateQueries("Minijos", "12-2", "Klaipėda")
        assertEquals("Minijos 12-2, Klaipėda, Lithuania", c[0])
        assertTrue("missing g. abbreviation", c.any { "Minijos g. 12-2" in it })
        assertTrue("missing expanded form",   c.any { "Minijos gatvė 12-2" in it })
    }

    @Test
    fun `Šilutės pl with slash-number is not padded with extra gatvė`() {
        // "pl." abbreviation already present → STREET_ABBREV_REGEX should suppress gatvė variants
        val c = DestinationResolver.buildStreetCandidateQueries("Šilutės pl.", "5/7", "Klaipėda")
        assertEquals("Šilutės pl. 5/7, Klaipėda, Lithuania", c[0])
        assertFalse("unexpected gatvė appended after pl.", c.any { "gatvė" in it })
    }

    @Test
    fun `Pietinė generates genitive g and full gatvė variants`() {
        // "Pietinė" not in STREET_EXPANSIONS; ends in -ė → genitive "Pietinės"
        val c = DestinationResolver.buildStreetCandidateQueries("Pietinė", "17", "Klaipėda")
        assertEquals("Pietinė 17, Klaipėda, Lithuania", c[0])
        assertTrue("missing Pietinės g. variant", c.any { "Pietinės g. 17" in it })
        assertTrue("missing Pietinė g. variant",  c.any { "Pietinė g. 17" in it })
        assertTrue("missing Pietinė gatvė variant", c.any { "Pietinė gatvė 17" in it })
    }

    @Test
    fun `no city appends only Lithuania`() {
        val c = DestinationResolver.buildStreetCandidateQueries("Taikos", "61", null)
        assertTrue(c.any { it == "Taikos 61, Lithuania" })
        assertTrue(c.any { "Taikos prospektas 61, Lithuania" == it })
        assertFalse("must not invent a city", c.any { "Klaipėda" in it })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT SCORING
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `exact street number and city is accepted`() {
        val score = GoogleNavigationEngine.scoreStreetResult(
            thoroughfare     = "Taikos prospektas",
            subThoroughfare  = "61",
            locality         = "Klaipėda",
            subAdminArea     = null,
            featureName      = null,
            formattedAddress = "Taikos prospektas 61, Klaipėda, Lithuania",
            expectedStreet   = "Taikos",
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertTrue("expected score >= ${GoogleNavigationEngine.SCORE_THRESHOLD}, got $score",
            score >= GoogleNavigationEngine.SCORE_THRESHOLD)
    }

    @Test
    fun `city-only result with no thoroughfare is rejected`() {
        val score = GoogleNavigationEngine.scoreStreetResult(
            thoroughfare     = null,
            subThoroughfare  = null,
            locality         = "Klaipėda",
            subAdminArea     = null,
            featureName      = null,
            formattedAddress = "Klaipėda, Lithuania",
            expectedStreet   = "Taikos",
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertTrue("city-only result must score below threshold, got $score",
            score < GoogleNavigationEngine.SCORE_THRESHOLD)
    }

    @Test
    fun `result in wrong city is rejected`() {
        val score = GoogleNavigationEngine.scoreStreetResult(
            thoroughfare     = "Taikos prospektas",
            subThoroughfare  = "61",
            locality         = "Vilnius",
            subAdminArea     = null,
            featureName      = null,
            formattedAddress = null,
            expectedStreet   = "Taikos",
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertTrue("wrong-city result must score below threshold, got $score",
            score < GoogleNavigationEngine.SCORE_THRESHOLD)
    }

    @Test
    fun `number found in formattedAddress is accepted when subThoroughfare is empty`() {
        // Geocoder sometimes omits subThoroughfare but includes the number in the formatted line.
        val score = GoogleNavigationEngine.scoreStreetResult(
            thoroughfare     = "Taikos prospektas",
            subThoroughfare  = null,
            locality         = "Klaipėda",
            subAdminArea     = null,
            featureName      = null,
            formattedAddress = "Taikos prospektas 61, Klaipėda, Lithuania",
            expectedStreet   = "Taikos",
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertTrue("number in formattedAddress must count, got score=$score",
            score >= GoogleNavigationEngine.SCORE_THRESHOLD)
    }

    @Test
    fun `street suffix variation is accepted via normalised match`() {
        // thoroughfare = "Taikos prospektas"; expectedStreet = "Taikos" →
        // after stripping "prospektas", both normalise to "taikos".
        val score = GoogleNavigationEngine.scoreStreetResult(
            thoroughfare     = "Taikos prospektas",
            subThoroughfare  = "61",
            locality         = "Klaipėda",
            subAdminArea     = null,
            featureName      = null,
            formattedAddress = null,
            expectedStreet   = "Taikos",
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertTrue("suffix-stripped match must be accepted, got score=$score",
            score >= GoogleNavigationEngine.SCORE_THRESHOLD)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DISTANCE SPEECH
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `distance speech covers all required forms and never contains koma`() {
        val cases = mapOf(
            350   to "Liko apie 350 metrų.",
            1000  to "Liko apie vieną kilometrą.",
            2000  to "Liko apie du kilometrus.",
            5600  to "Liko apie penkis su puse kilometro.",
            10000 to "Liko apie dešimt kilometrų.",
        )
        for ((meters, expected) in cases) {
            val result = distanceSpeech(meters)
            assertEquals("distanceSpeech($meters)", expected, result)
            assertFalse("'koma' must not appear in distance speech for $meters m",
                "koma" in result)
        }
    }

    @Test
    fun `above 20 km uses digit rounding to nearest 1 km`() {
        // 25 600 m → rounds to 26 km
        val result = distanceSpeech(25_600)
        assertTrue("Expected digit km form, got: $result", result.contains("26"))
        assertFalse("koma must not appear", "koma" in result)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ETA SPEECH
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `minute forms are correct for 1 2 10 21 22`() {
        assertEquals("apie vieną minutę",            minuteSpeech(1))
        assertEquals("apie dvi minutes",             minuteSpeech(2))
        assertEquals("apie dešimt minučių",          minuteSpeech(10))
        assertEquals("apie dvidešimt vieną minutę",  minuteSpeech(21))
        assertEquals("apie dvidešimt dvi minutes",   minuteSpeech(22))
    }

    @Test
    fun `combined distance and eta response contains no joke text`() {
        val distance = distanceSpeech(5_600)
        // buildTimeResponse uses minuteSpeech; 600 s = 10 min
        val eta = "Kelionė truks ${minuteSpeech(10)}."
        val combined = "$distance $eta"
        assertFalse("joke word 'šypso' must not appear",    "šypso"    in combined)
        assertFalse("joke word 'sveiki'  must not appear",  "sveiki"   in combined)
        assertFalse("joke word 'pietūs'  must not appear",  "pietūs"   in combined)
        assertFalse("koma must not appear",                 "koma"     in combined)
        assertTrue("distance part present",  distance in combined)
        assertTrue("eta part present",       eta      in combined)
    }
}
