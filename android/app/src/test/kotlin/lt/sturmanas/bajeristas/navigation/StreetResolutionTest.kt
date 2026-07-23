package lt.sturmanas.bajeristas.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM regression tests covering:
 *
 *  ISSUE 1 — Street + house-number multi-query fallback and result validation
 *   - [DestinationResolver.buildStreetCandidateQueries] generates the correct ordered candidate list
 *   - [GoogleNavigationEngine.isValidStreetResult] correctly accepts / rejects geocoder results
 *
 *  ISSUE 2 — Lithuanian TTS distance speech (no "koma")
 *   - [distanceSpeech] produces natural Lithuanian phrases for all distance ranges
 *
 * All tests are pure JVM — no Android context or Robolectric required.
 */
class StreetResolutionTest {

    // ─────────────────────────────────────────────────────────────────────────
    // ISSUE 1A — buildStreetCandidateQueries: query generation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `known prospektas stem generates spoken abbr and expanded candidates`() {
        // "Taikos" is in STREET_EXPANSIONS → "Taikos prospektas"
        val candidates = DestinationResolver.buildStreetCandidateQueries("Taikos", "61", "Klaipėda")
        // Must start with the spoken form
        assertEquals("Taikos 61, Klaipėda, Lithuania", candidates[0])
        // Must include the "pr." abbreviation
        assertTrue("Expected 'pr.' abbreviation candidate",
            candidates.any { "Taikos pr. 61" in it })
        // Must include the fully expanded form
        assertTrue("Expected expanded 'Taikos prospektas' candidate",
            candidates.any { "Taikos prospektas 61" in it })
        // Must NOT include "gatvė" (expansion is already known)
        assertFalse("Unexpected 'gatvė' in prospektas query",
            candidates.any { "gatvė" in it })
    }

    @Test
    fun `lowercase stem is normalised to the correct expansion`() {
        // Speech recogniser may emit "taikos 61" in all lowercase; step C trims and title-cases
        // via the regex group, but the stem lookup is always lowercased.
        val candidates = DestinationResolver.buildStreetCandidateQueries("taikos", "61", "Klaipėda")
        assertTrue("Expected spoken-form candidate",
            candidates.any { "taikos 61, Klaipėda, Lithuania" == it })
        // Expanded form should use the canonical casing from STREET_EXPANSIONS
        assertTrue("Expected expanded candidate for lowercased input",
            candidates.any { "Taikos prospektas 61" in it || "taikos prospektas 61" in it.lowercase() })
    }

    @Test
    fun `unknown street appends gave with lowercase input`() {
        // "Pietinė" is NOT in STREET_EXPANSIONS; the fallback should add " gatvė"
        val candidates = DestinationResolver.buildStreetCandidateQueries("Pietinė", "17", "Klaipėda")
        assertEquals("Pietinė 17, Klaipėda, Lithuania", candidates[0])
        assertTrue("Expected 'gatvė' fallback candidate for unknown street",
            candidates.any { "Pietinė gatvė 17" in it })
        // No "pr." or expansion entries expected
        assertFalse(candidates.any { " pr. " in it })
    }

    @Test
    fun `without city suffix is Lithuania only`() {
        val candidates = DestinationResolver.buildStreetCandidateQueries("Taikos", "61", null)
        assertTrue("Expected no-city spoken form",
            candidates.any { it == "Taikos 61, Lithuania" })
        assertTrue("Expected no-city expanded form",
            candidates.any { "Taikos prospektas 61, Lithuania" == it })
        assertFalse("Must not invent a city",
            candidates.any { "Klaipėda" in it })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ISSUE 1B — isValidStreetResult: geocoder result validation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `city-only result is rejected when thoroughfare is blank`() {
        // A classic city-centre fallback returns no thoroughfare
        val accepted = GoogleNavigationEngine.isValidStreetResult(
            thoroughfare     = null,      // ← city-only
            subThoroughfare  = null,
            locality         = "Klaipėda",
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertFalse("City-only result (no thoroughfare) must be rejected", accepted)
    }

    @Test
    fun `result missing house number is rejected when number was expected`() {
        val accepted = GoogleNavigationEngine.isValidStreetResult(
            thoroughfare     = "Taikos prospektas",
            subThoroughfare  = null,           // ← no house number
            locality         = "Klaipėda",
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertFalse("Result without house number must be rejected when number was requested", accepted)
    }

    @Test
    fun `result in wrong city is rejected`() {
        val accepted = GoogleNavigationEngine.isValidStreetResult(
            thoroughfare     = "Taikos prospektas",
            subThoroughfare  = "61",
            locality         = "Vilnius",       // ← wrong city
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertFalse("Result in wrong city must be rejected", accepted)
    }

    @Test
    fun `exact street and number result is accepted`() {
        val accepted = GoogleNavigationEngine.isValidStreetResult(
            thoroughfare     = "Taikos prospektas",
            subThoroughfare  = "61",
            locality         = "Klaipėda",
            expectedNumber   = "61",
            expectedLocality = "Klaipėda",
        )
        assertTrue("Matching thoroughfare, house number, and city must be accepted", accepted)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ISSUE 2 — distanceSpeech: Lithuanian TTS, no "koma"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `500 m produces natural metres phrase`() {
        assertEquals("Liko apie 500 metrų.", distanceSpeech(500))
    }

    @Test
    fun `1000 m produces singular kilometre accusative`() {
        // 1 000 m → rounded to 1 000 m → km=1, half=false → "kilometrą"
        assertEquals("Liko apie 1 kilometrą.", distanceSpeech(1000))
    }

    @Test
    fun `1200 m rounds to 1 km singular`() {
        // 1 200 m → r500 = ((1200+250)/500)*500 = 1 000 → km=1
        assertEquals("Liko apie 1 kilometrą.", distanceSpeech(1200))
    }

    @Test
    fun `2000 m produces plural kilometre accusative`() {
        assertEquals("Liko apie 2 kilometrus.", distanceSpeech(2000))
    }

    @Test
    fun `5600 m rounds to 5 and a half km`() {
        // 5 600 m → r500 = ((5600+250)/500)*500 = 5 500 → km=5, half=true
        assertEquals("Liko apie 5 su puse kilometro.", distanceSpeech(5600))
    }

    @Test
    fun `10000 m produces genitive plural`() {
        assertEquals("Liko apie 10 kilometrų.", distanceSpeech(10000))
    }

    @Test
    fun `zero or negative metres produces fallback phrase`() {
        assertEquals("Liko labai mažai.", distanceSpeech(0))
        assertEquals("Liko labai mažai.", distanceSpeech(-100))
    }
}
