package lt.sturmanas.bajeristas.navigation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [DestinationResolver].
 *
 * All tests use [runBlocking] because [DestinationResolver.resolve] is a suspend fun.
 * No Android context is required — the resolver is a pure Kotlin object.
 *
 * Coverage:
 *   A. Saved-place aliases (home / work)
 *   B. Coordinate-pair passthrough
 *   B2. Comma-separated → ExactAddress unchanged
 *   F. City-centre shorthands
 *   E. Category keywords
 *   D. Brand / named POI
 *   C. Street + number expansion
 *   G. Multi-word fallback
 *   H. Single-word failure
 *   Internal helpers: expandStreetName, buildLocationQuery
 *   Data-class construction: CandidatePlace, NeedsClarification
 */
class DestinationResolverTest {

    // ── A. Saved-place aliases ─────────────────────────────────────────────

    @Test fun `home alias namai with saved address returns SavedPlace`() = runBlocking {
        val result = resolve("namai", savedPlaces = mapOf("namai" to "Taikos pr. 61, Klaipėda"))
        assertTrue(result is DestinationResolution.SavedPlace)
        assertEquals("Namai", (result as DestinationResolution.SavedPlace).name)
        assertEquals("Taikos pr. 61, Klaipėda", result.address)
    }

    @Test fun `home alias namo with saved address returns SavedPlace`() = runBlocking {
        val result = resolve("namo", savedPlaces = mapOf("namai" to "Minijos 5, Klaipėda"))
        assertTrue(result is DestinationResolution.SavedPlace)
    }

    @Test fun `home alias namus with saved address returns SavedPlace`() = runBlocking {
        val result = resolve("į namus", savedPlaces = mapOf("namai" to "Testinė g. 1"))
        assertTrue(result is DestinationResolution.SavedPlace)
    }

    @Test fun `home alias without saved address returns Failure with setup message`() = runBlocking {
        val result = resolve("namai", savedPlaces = emptyMap())
        assertTrue(result is DestinationResolution.Failure)
        val msg = (result as DestinationResolution.Failure).message
        assertTrue("Should mention Nustatymai", msg.contains("Nustatymuose"))
    }

    @Test fun `work alias darbas with saved address returns SavedPlace`() = runBlocking {
        val result = resolve("darbas", savedPlaces = mapOf("darbas" to "Gedimino pr. 9, Vilnius"))
        assertTrue(result is DestinationResolution.SavedPlace)
        assertEquals("Darbas", (result as DestinationResolution.SavedPlace).name)
        assertEquals("Gedimino pr. 9, Vilnius", result.address)
    }

    @Test fun `work alias į darbą with saved address returns SavedPlace`() = runBlocking {
        val result = resolve("į darbą", savedPlaces = mapOf("darbas" to "Manto 1, Klaipėda"))
        assertTrue(result is DestinationResolution.SavedPlace)
    }

    @Test fun `work alias without saved address returns Failure`() = runBlocking {
        val result = resolve("darbas", savedPlaces = emptyMap())
        assertTrue(result is DestinationResolution.Failure)
        val msg = (result as DestinationResolution.Failure).message
        assertTrue("Should mention darbo adresas", msg.contains("Darbo adresas"))
    }

    // ── B. Coordinate pair ─────────────────────────────────────────────────

    @Test fun `coordinate pair returns ExactAddress unchanged`() = runBlocking {
        val result = resolve("54.6872,25.2797")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("54.6872,25.2797", (result as DestinationResolution.ExactAddress).query)
    }

    @Test fun `coordinate pair with spaces returns ExactAddress`() = runBlocking {
        val result = resolve("55.703, 21.134")
        assertTrue(result is DestinationResolution.ExactAddress)
    }

    // ── B2. Comma-containing input ─────────────────────────────────────────

    @Test fun `comma-separated address returns ExactAddress unchanged`() = runBlocking {
        val result = resolve("Taikos 61, Klaipėda")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("Taikos 61, Klaipėda", (result as DestinationResolution.ExactAddress).query)
    }

    @Test fun `fully qualified address with comma is not re-expanded`() = runBlocking {
        val result = resolve("Gedimino prospektas 9, Vilnius")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("Gedimino prospektas 9, Vilnius", (result as DestinationResolution.ExactAddress).query)
    }

    // ── F. City-centre shorthands ─────────────────────────────────────────

    @Test fun `centras with locality returns ExactAddress centras comma city`() = runBlocking {
        val result = resolve("centras", locality = "Klaipėda")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("centras, Klaipėda", (result as DestinationResolution.ExactAddress).query)
    }

    @Test fun `į centrą with locality returns ExactAddress`() = runBlocking {
        val result = resolve("į centrą", locality = "Vilnius")
        assertTrue(result is DestinationResolution.ExactAddress)
        val q = (result as DestinationResolution.ExactAddress).query
        assertTrue("Should contain Vilnius", q.contains("Vilnius"))
    }

    @Test fun `centras without locality returns ExactAddress city centre`() = runBlocking {
        val result = resolve("centras")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("city centre", (result as DestinationResolution.ExactAddress).query)
    }

    // ── E. Category keywords ───────────────────────────────────────────────

    @Test fun `degalinė with locality returns PlaceSearch with city`() = runBlocking {
        val result = resolve("degalinė", locality = "Klaipėda")
        assertTrue(result is DestinationResolution.PlaceSearch)
        val q = (result as DestinationResolution.PlaceSearch).query
        assertTrue("Should contain degalinė", q.contains("degalinė"))
        assertTrue("Should contain Klaipėda", q.contains("Klaipėda"))
    }

    @Test fun `kavos returns PlaceSearch kavinė`() = runBlocking {
        val result = resolve("kavos")
        assertTrue(result is DestinationResolution.PlaceSearch)
        val q = (result as DestinationResolution.PlaceSearch).query
        assertTrue("Should map kavos → kavinė", q.contains("kavinė"))
    }

    @Test fun `parkingas returns PlaceSearch`() = runBlocking {
        val result = resolve("parkingas", locality = "Vilnius")
        assertTrue(result is DestinationResolution.PlaceSearch)
    }

    @Test fun `vaistinė returns PlaceSearch`() = runBlocking {
        val result = resolve("vaistinė")
        assertTrue(result is DestinationResolution.PlaceSearch)
        assertTrue((result as DestinationResolution.PlaceSearch).query.contains("vaistinė"))
    }

    // ── D. Brand / named POI ──────────────────────────────────────────────

    @Test fun `Akropolis with locality returns PlaceSearch with city`() = runBlocking {
        val result = resolve("Akropolis", locality = "Klaipėda")
        assertTrue(result is DestinationResolution.PlaceSearch)
        assertTrue((result as DestinationResolution.PlaceSearch).query.contains("Klaipėda"))
    }

    @Test fun `Maxima returns PlaceSearch`() = runBlocking {
        val result = resolve("Maxima")
        assertTrue(result is DestinationResolution.PlaceSearch)
        // Unbiased fallback: no locality, no coords → bare brand name
        assertEquals("Maxima", (result as DestinationResolution.PlaceSearch).query)
    }

    @Test fun `brand keyword with lat and lng but no locality appends near coords`() = runBlocking {
        val result = resolve("Maxima", lat = 55.71, lng = 21.13)
        assertTrue(result is DestinationResolution.PlaceSearch)
        assertEquals("Maxima near 55.71,21.13", (result as DestinationResolution.PlaceSearch).query)
    }

    @Test fun `Lidl returns PlaceSearch`() = runBlocking {
        val result = resolve("Lidl")
        assertTrue(result is DestinationResolution.PlaceSearch)
    }

    // ── C. Street + number expansion ──────────────────────────────────────

    @Test fun `Taikos 61 with locality expands to Taikos prospektas 61 comma city`() = runBlocking {
        val result = resolve("Taikos 61", locality = "Klaipėda")
        assertTrue(result is DestinationResolution.ExactAddress)
        val q = (result as DestinationResolution.ExactAddress).query
        assertEquals("Taikos prospektas 61, Klaipėda", q)
    }

    @Test fun `Taikos 61 without locality expands without city`() = runBlocking {
        val result = resolve("Taikos 61")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("Taikos prospektas 61", (result as DestinationResolution.ExactAddress).query)
    }

    @Test fun `Minijos 12 expands to Minijos gatvė`() = runBlocking {
        val result = resolve("Minijos 12")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertTrue((result as DestinationResolution.ExactAddress).query.startsWith("Minijos gatvė"))
    }

    @Test fun `already-expanded street name is not double-expanded`() = runBlocking {
        val result = resolve("Taikos prospektas 61")
        assertTrue(result is DestinationResolution.ExactAddress)
        val q = (result as DestinationResolution.ExactAddress).query
        // Should NOT become "Taikos prospektas prospektas 61"
        assertEquals(1, Regex("prospektas").findAll(q).count())
    }

    // ── G. Multi-word fallback ─────────────────────────────────────────────

    @Test fun `two-word unknown phrase with locality returns PlaceSearch with city`() = runBlocking {
        val result = resolve("Paryžiaus aikštė", locality = "Vilnius")
        assertTrue(result is DestinationResolution.PlaceSearch)
        val q = (result as DestinationResolution.PlaceSearch).query
        assertTrue(q.contains("Vilnius"))
    }

    @Test fun `two-word unknown phrase without locality returns PlaceSearch`() = runBlocking {
        val result = resolve("Žvėryno parkas")
        assertTrue(result is DestinationResolution.PlaceSearch)
    }

    // ── H. Single-word failure ─────────────────────────────────────────────

    @Test fun `single unknown word without context returns Failure`() = runBlocking {
        val result = resolve("Zxqwerty")
        assertTrue(result is DestinationResolution.Failure)
    }

    @Test fun `blank input returns Failure`() = runBlocking {
        val result = resolve("")
        assertTrue(result is DestinationResolution.Failure)
    }

    // ── expandStreetName helper ────────────────────────────────────────────

    @Test fun `expandStreetName known street returns canonical form`() {
        assertEquals("Taikos prospektas", DestinationResolver.expandStreetName("Taikos"))
        assertEquals("Minijos gatvė", DestinationResolver.expandStreetName("Minijos"))
        assertEquals("Gedimino prospektas", DestinationResolver.expandStreetName("Gedimino"))
    }

    @Test fun `expandStreetName already contains type suffix returns unchanged`() {
        assertEquals("Taikos prospektas", DestinationResolver.expandStreetName("Taikos prospektas"))
        assertEquals("Taikos prospektą", DestinationResolver.expandStreetName("Taikos prospektą"))
        assertEquals("Minijos gatvė", DestinationResolver.expandStreetName("Minijos gatvė"))
    }

    @Test fun `expandStreetName unknown stem returns input unchanged`() {
        assertEquals("Paryžiaus", DestinationResolver.expandStreetName("Paryžiaus"))
        assertEquals("Saulėtoji", DestinationResolver.expandStreetName("Saulėtoji"))
    }

    // ── expandStreetName — full STREET_EXPANSIONS coverage ────────────────

    // Klaipėda streets

    @Test fun `expandStreetName liepų diacritic expands to Liepų alėja`() {
        assertEquals("Liepų alėja", DestinationResolver.expandStreetName("liepų"))
    }

    @Test fun `expandStreetName liepu no-diacritic expands to Liepų alėja`() {
        assertEquals("Liepų alėja", DestinationResolver.expandStreetName("liepu"))
    }

    @Test fun `expandStreetName šilutės diacritic expands to Šilutės plentas`() {
        assertEquals("Šilutės plentas", DestinationResolver.expandStreetName("šilutės"))
    }

    @Test fun `expandStreetName silutes no-diacritic expands to Šilutės plentas`() {
        assertEquals("Šilutės plentas", DestinationResolver.expandStreetName("silutes"))
    }

    @Test fun `expandStreetName baltijos expands to Baltijos prospektas`() {
        assertEquals("Baltijos prospektas", DestinationResolver.expandStreetName("baltijos"))
    }

    @Test fun `expandStreetName h manto abbreviated expands to Herkaus Manto gatvė`() {
        assertEquals("Herkaus Manto gatvė", DestinationResolver.expandStreetName("h. manto"))
    }

    @Test fun `expandStreetName herkaus manto expands to Herkaus Manto gatvė`() {
        assertEquals("Herkaus Manto gatvė", DestinationResolver.expandStreetName("herkaus manto"))
    }

    @Test fun `expandStreetName sausio expands to Sausio 13-osios gatvė`() {
        assertEquals("Sausio 13-osios gatvė", DestinationResolver.expandStreetName("sausio"))
    }

    @Test fun `expandStreetName danės diacritic expands to Danės gatvė`() {
        assertEquals("Danės gatvė", DestinationResolver.expandStreetName("danės"))
    }

    @Test fun `expandStreetName danes no-diacritic expands to Danės gatvė`() {
        assertEquals("Danės gatvė", DestinationResolver.expandStreetName("danes"))
    }

    @Test fun `expandStreetName melnragės diacritic expands to Melnragės gatvė`() {
        assertEquals("Melnragės gatvė", DestinationResolver.expandStreetName("melnragės"))
    }

    @Test fun `expandStreetName melnrages no-diacritic expands to Melnragės gatvė`() {
        assertEquals("Melnragės gatvė", DestinationResolver.expandStreetName("melnrages"))
    }

    // Vilnius streets

    @Test fun `expandStreetName konstitucijos expands to Konstitucijos prospektas`() {
        assertEquals("Konstitucijos prospektas", DestinationResolver.expandStreetName("konstitucijos"))
    }

    @Test fun `expandStreetName žirmūnų diacritic expands to Žirmūnų gatvė`() {
        assertEquals("Žirmūnų gatvė", DestinationResolver.expandStreetName("žirmūnų"))
    }

    @Test fun `expandStreetName zirmunu no-diacritic expands to Žirmūnų gatvė`() {
        assertEquals("Žirmūnų gatvė", DestinationResolver.expandStreetName("zirmunu"))
    }

    @Test fun `expandStreetName ukmergės diacritic expands to Ukmergės gatvė`() {
        assertEquals("Ukmergės gatvė", DestinationResolver.expandStreetName("ukmergės"))
    }

    @Test fun `expandStreetName ukmerges no-diacritic expands to Ukmergės gatvė`() {
        assertEquals("Ukmergės gatvė", DestinationResolver.expandStreetName("ukmerges"))
    }

    @Test fun `expandStreetName laisvės diacritic expands to Laisvės prospektas`() {
        assertEquals("Laisvės prospektas", DestinationResolver.expandStreetName("laisvės"))
    }

    @Test fun `expandStreetName laisves no-diacritic expands to Laisvės prospektas`() {
        assertEquals("Laisvės prospektas", DestinationResolver.expandStreetName("laisves"))
    }

    @Test fun `expandStreetName vilniaus expands to Vilniaus gatvė`() {
        assertEquals("Vilniaus gatvė", DestinationResolver.expandStreetName("vilniaus"))
    }

    @Test fun `expandStreetName saltoniškių diacritic expands to Saltoniškių gatvė`() {
        assertEquals("Saltoniškių gatvė", DestinationResolver.expandStreetName("saltoniškių"))
    }

    @Test fun `expandStreetName saltoniskiu no-diacritic expands to Saltoniškių gatvė`() {
        assertEquals("Saltoniškių gatvė", DestinationResolver.expandStreetName("saltoniskiu"))
    }

    @Test fun `expandStreetName ozo expands to Ozo gatvė`() {
        assertEquals("Ozo gatvė", DestinationResolver.expandStreetName("ozo"))
    }

    @Test fun `expandStreetName žygio diacritic expands to Žygio gatvė`() {
        assertEquals("Žygio gatvė", DestinationResolver.expandStreetName("žygio"))
    }

    @Test fun `expandStreetName zygio no-diacritic expands to Žygio gatvė`() {
        assertEquals("Žygio gatvė", DestinationResolver.expandStreetName("zygio"))
    }

    // Kaunas streets

    @Test fun `expandStreetName savanorių diacritic expands to Savanorių prospektas`() {
        assertEquals("Savanorių prospektas", DestinationResolver.expandStreetName("savanorių"))
    }

    @Test fun `expandStreetName savanoriu no-diacritic expands to Savanorių prospektas`() {
        assertEquals("Savanorių prospektas", DestinationResolver.expandStreetName("savanoriu"))
    }

    @Test fun `expandStreetName jonavos expands to Jonavos gatvė`() {
        assertEquals("Jonavos gatvė", DestinationResolver.expandStreetName("jonavos"))
    }

    @Test fun `expandStreetName partizanų diacritic expands to Partizanų gatvė`() {
        assertEquals("Partizanų gatvė", DestinationResolver.expandStreetName("partizanų"))
    }

    @Test fun `expandStreetName partizanu no-diacritic expands to Partizanų gatvė`() {
        assertEquals("Partizanų gatvė", DestinationResolver.expandStreetName("partizanu"))
    }

    @Test fun `expandStreetName nemuno expands to Nemuno gatvė`() {
        assertEquals("Nemuno gatvė", DestinationResolver.expandStreetName("nemuno"))
    }

    @Test fun `expandStreetName žalgirio diacritic expands to Žalgirio gatvė`() {
        assertEquals("Žalgirio gatvė", DestinationResolver.expandStreetName("žalgirio"))
    }

    @Test fun `expandStreetName zalgirio no-diacritic expands to Žalgirio gatvė`() {
        assertEquals("Žalgirio gatvė", DestinationResolver.expandStreetName("zalgirio"))
    }

    @Test fun `expandStreetName kauno expands to Kauno gatvė`() {
        assertEquals("Kauno gatvė", DestinationResolver.expandStreetName("kauno"))
    }

    // ── No-double-expand guard for Kaunas streets ─────────────────────────

    @Test fun `already-expanded Savanorių prospektas is not double-expanded`() = runBlocking {
        val result = resolve("Savanorių prospektas 87")
        assertTrue(result is DestinationResolution.ExactAddress)
        val q = (result as DestinationResolution.ExactAddress).query
        assertEquals(1, Regex("prospektas").findAll(q).count())
    }

    @Test fun `already-expanded Jonavos gatvė is not double-expanded`() = runBlocking {
        val result = resolve("Jonavos gatvė 14")
        assertTrue(result is DestinationResolution.ExactAddress)
        val q = (result as DestinationResolution.ExactAddress).query
        assertEquals(1, Regex("gatvė").findAll(q).count())
    }

    @Test fun `already-expanded Partizanų gatvė is not double-expanded`() = runBlocking {
        val result = resolve("Partizanų gatvė 33")
        assertTrue(result is DestinationResolution.ExactAddress)
        val q = (result as DestinationResolution.ExactAddress).query
        assertEquals(1, Regex("gatvė").findAll(q).count())
    }

    // ── Kaunas street + number integration (resolve path C) ───────────────

    @Test fun `Savanorių 87 with Kaunas locality expands correctly`() = runBlocking {
        val result = resolve("Savanorių 87", locality = "Kaunas")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("Savanorių prospektas 87, Kaunas",
            (result as DestinationResolution.ExactAddress).query)
    }

    @Test fun `Savanoriu no-diacritic 87 with Kaunas locality expands correctly`() = runBlocking {
        val result = resolve("Savanoriu 87", locality = "Kaunas")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("Savanorių prospektas 87, Kaunas",
            (result as DestinationResolution.ExactAddress).query)
    }

    @Test fun `Jonavos 14 with Kaunas locality expands correctly`() = runBlocking {
        val result = resolve("Jonavos 14", locality = "Kaunas")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("Jonavos gatvė 14, Kaunas",
            (result as DestinationResolution.ExactAddress).query)
    }

    @Test fun `Partizanų 33 with Kaunas locality expands correctly`() = runBlocking {
        val result = resolve("Partizanų 33", locality = "Kaunas")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("Partizanų gatvė 33, Kaunas",
            (result as DestinationResolution.ExactAddress).query)
    }

    @Test fun `Partizanu no-diacritic 33 with Kaunas locality expands correctly`() = runBlocking {
        val result = resolve("Partizanu 33", locality = "Kaunas")
        assertTrue(result is DestinationResolution.ExactAddress)
        assertEquals("Partizanų gatvė 33, Kaunas",
            (result as DestinationResolution.ExactAddress).query)
    }

    // ── buildLocationQuery helper ──────────────────────────────────────────

    @Test fun `buildLocationQuery with locality appends city`() {
        val q = DestinationResolver.buildLocationQuery("kavinė", "Kaunas", null, null)
        assertEquals("kavinė, Kaunas", q)
    }

    @Test fun `buildLocationQuery with coords appends near coords`() {
        val q = DestinationResolver.buildLocationQuery("parkingas", null, 54.68, 25.27)
        assertEquals("parkingas near 54.68,25.27", q)
    }

    @Test fun `buildLocationQuery with no context returns query unchanged`() {
        val q = DestinationResolver.buildLocationQuery("bankomatas", null, null, null)
        assertEquals("bankomatas", q)
    }

    // ── Data-class construction: NeedsClarification ────────────────────────

    @Test fun `NeedsClarification holds originalText and candidates list`() {
        val candidates = listOf(
            CandidatePlace("Akropolis Klaipėda", "Taikos pr. 61, Klaipėda", 2500),
            CandidatePlace("Akropolis Vilnius", "Ozo g. 25, Vilnius", null),
        )
        val resolution = DestinationResolution.NeedsClarification(
            originalText = "Akropolis",
            suggestions  = candidates,
        )
        assertEquals("Akropolis", resolution.originalText)
        assertEquals(2, resolution.suggestions.size)
        assertEquals("Akropolis Klaipėda", resolution.suggestions[0].name)
        assertEquals(2500, resolution.suggestions[0].distanceMeters)
        assertEquals(null, resolution.suggestions[1].distanceMeters)
    }

    @Test fun `CandidatePlace has all required fields`() {
        val c = CandidatePlace(
            name = "Lidl Šilutė",
            address = "Tilžės 45, Šilutė",
            distanceMeters = 1200,
        )
        assertEquals("Lidl Šilutė", c.name)
        assertEquals("Tilžės 45, Šilutė", c.address)
        assertEquals(1200, c.distanceMeters)
    }

    // ── Helper for concise test calls ──────────────────────────────────────

    private suspend fun resolve(
        rawText: String,
        locality: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        savedPlaces: SavedPlacesMap = emptyMap(),
    ): DestinationResolution = DestinationResolver.resolve(
        rawText          = rawText,
        currentLat       = lat,
        currentLng       = lng,
        currentLocality  = locality,
        savedPlaces      = savedPlaces,
    )
}
