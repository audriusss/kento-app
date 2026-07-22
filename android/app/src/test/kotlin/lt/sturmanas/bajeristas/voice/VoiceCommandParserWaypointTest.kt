package lt.sturmanas.bajeristas.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for waypoint-related voice command parsing in [VoiceCommandParser].
 *
 * Covers:
 * - All [VoiceCommand.AddWaypoint] prefixes map to the correct command.
 * - All [VoiceCommand.RemoveLastWaypoint] patterns fire before [StopNavigation].
 * - All [VoiceCommand.ClearWaypoints] patterns.
 * - All [VoiceCommand.ListWaypoints] patterns fire before [StartNavigation].
 * - All [VoiceCommand.ContinueRoute] patterns.
 * - Regression: pre-existing commands still parse correctly alongside new patterns.
 */
class VoiceCommandParserWaypointTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun parse(text: String) = VoiceCommandParser.parse(text)
    private fun assertAddWaypoint(input: String, expectedPlace: String) {
        val cmd = parse(input)
        assertTrue(
            "Expected AddWaypoint for '$input' but got ${cmd::class.simpleName}",
            cmd is VoiceCommand.AddWaypoint,
        )
        assertEquals(
            "Wrong place for '$input'",
            expectedPlace,
            (cmd as VoiceCommand.AddWaypoint).place,
        )
    }

    // ── AddWaypoint: prefix variations ────────────────────────────────────

    @Test
    fun `pakeliui uzvaziuok i maps to AddWaypoint`() {
        assertAddWaypoint("Pakeliui užvažiuok į Lidl", "Lidl")
    }

    @Test
    fun `no-diacritic pakeliui uzvazuok i maps to AddWaypoint`() {
        assertAddWaypoint("pakeliui uzvazuok i Lidl", "Lidl")
    }

    @Test
    fun `pakeliui vaziuok i maps to AddWaypoint`() {
        assertAddWaypoint("pakeliui važiuok į Lidl Klaipėda", "Lidl Klaipėda")
    }

    @Test
    fun `uzsuk i maps to AddWaypoint`() {
        assertAddWaypoint("Užsuk į degalinę", "degalinę")
    }

    @Test
    fun `no-diacritic uzsuk i maps to AddWaypoint`() {
        assertAddWaypoint("uzsuk i degaline", "degaline")
    }

    @Test
    fun `po to vaziuojam i maps to AddWaypoint`() {
        assertAddWaypoint("Po to važiuojam į kavinę", "kavinę")
    }

    @Test
    fun `po to no-diacritic vaziuojam i maps to AddWaypoint`() {
        assertAddWaypoint("po to vaziuojam i kavine", "kavine")
    }

    @Test
    fun `dar vaziuojam i maps to AddWaypoint`() {
        assertAddWaypoint("Dar važiuojam į Maximą", "Maximą")
    }

    @Test
    fun `dar vaziuojam i no-diacritic maps to AddWaypoint`() {
        assertAddWaypoint("dar vaziuojam i Maxima", "Maxima")
    }

    @Test
    fun `pridek with place maps to AddWaypoint`() {
        assertAddWaypoint("Pridėk degalinę", "degalinę")
    }

    @Test
    fun `pridek no-diacritic with place maps to AddWaypoint`() {
        assertAddWaypoint("pridek degaline", "degaline")
    }

    @Test
    fun `pakeliui i maps to AddWaypoint`() {
        assertAddWaypoint("pakeliui į Rimi", "Rimi")
    }

    @Test
    fun `AddWaypoint strips trailing punctuation from place`() {
        val cmd = parse("Užsuk į Lidl.")
        assertTrue(cmd is VoiceCommand.AddWaypoint)
        assertEquals("Lidl", (cmd as VoiceCommand.AddWaypoint).place)
    }

    @Test
    fun `AddWaypoint preserves multi-word place name`() {
        assertAddWaypoint("Pakeliui užvažiuok į Lidl Šilutės plentas", "Lidl Šilutės plentas")
    }

    @Test
    fun `po X vaziuojam i pattern with intermediate word maps to AddWaypoint`() {
        // "po degalinės važiuojam į X" — intermediate word between 'po' and 'važiuojam į'
        assertAddWaypoint("po degalinės važiuojam į kavinę", "kavinę")
    }

    @Test
    fun `pridek with no place returns GeneralQuestion not AddWaypoint`() {
        // "pridėk" with nothing after — place would be blank, falls through to GeneralQuestion
        val cmd = parse("pridėk")
        // blank place after prefix → parser should not emit AddWaypoint
        assertTrue(
            "Expected GeneralQuestion or Unknown for bare 'pridėk' but got ${cmd::class.simpleName}",
            cmd is VoiceCommand.GeneralQuestion || cmd is VoiceCommand.Unknown,
        )
    }

    // ── RemoveLastWaypoint — checked before StopNavigation ─────────────────

    @Test
    fun `ismesk paskutini maps to RemoveLastWaypoint`() {
        assertEquals(VoiceCommand.RemoveLastWaypoint, parse("Išmesk paskutinį"))
    }

    @Test
    fun `ismesk paskutini sustojima maps to RemoveLastWaypoint`() {
        assertEquals(VoiceCommand.RemoveLastWaypoint, parse("Išmesk paskutinį sustojimą"))
    }

    @Test
    fun `pasalink paskutini sustojima maps to RemoveLastWaypoint`() {
        assertEquals(VoiceCommand.RemoveLastWaypoint, parse("Pašalink paskutinį sustojimą"))
    }

    @Test
    fun `pasalink paskutini maps to RemoveLastWaypoint`() {
        assertEquals(VoiceCommand.RemoveLastWaypoint, parse("pašalink paskutinį"))
    }

    @Test
    fun `REGRESSION atsauk paskutini maps to RemoveLastWaypoint not StopNavigation`() {
        // Critical regression: "atšauk paskutinį" must NOT fire StopNavigation.
        // RemoveLastWaypoint patterns are checked before StopNavigation.
        val cmd = parse("atšauk paskutinį")
        assertEquals(
            "REGRESSION: 'atšauk paskutinį' incorrectly maps to ${cmd::class.simpleName}",
            VoiceCommand.RemoveLastWaypoint,
            cmd,
        )
    }

    @Test
    fun `atsauk paskutini sustojima maps to RemoveLastWaypoint not StopNavigation`() {
        assertEquals(VoiceCommand.RemoveLastWaypoint, parse("atšauk paskutinį sustojimą"))
    }

    @Test
    fun `istrink paskutini maps to RemoveLastWaypoint`() {
        assertEquals(VoiceCommand.RemoveLastWaypoint, parse("ištrink paskutinį"))
    }

    @Test
    fun `no-diacritic ismesk paskutini maps to RemoveLastWaypoint`() {
        assertEquals(VoiceCommand.RemoveLastWaypoint, parse("ismesk paskutini"))
    }

    // ── ClearWaypoints ─────────────────────────────────────────────────────

    @Test
    fun `pasalink visus sustojimus maps to ClearWaypoints`() {
        assertEquals(VoiceCommand.ClearWaypoints, parse("Pašalink visus sustojimus"))
    }

    @Test
    fun `isvalyk sustojimus maps to ClearWaypoints`() {
        assertEquals(VoiceCommand.ClearWaypoints, parse("išvalyk sustojimus"))
    }

    @Test
    fun `ismesk visus sustojimus maps to ClearWaypoints`() {
        assertEquals(VoiceCommand.ClearWaypoints, parse("Išmesk visus sustojimus"))
    }

    @Test
    fun `no-diacritic pasalink visus sustojimus maps to ClearWaypoints`() {
        assertEquals(VoiceCommand.ClearWaypoints, parse("pasalink visus sustojimus"))
    }

    // ── ListWaypoints — checked before StartNavigation ─────────────────────

    @Test
    fun `rodyk sustojimus maps to ListWaypoints`() {
        assertEquals(VoiceCommand.ListWaypoints, parse("Rodyk sustojimus"))
    }

    @Test
    fun `REGRESSION rodyk sustojimus must not map to StartNavigation`() {
        // Critical regression: "rodyk sustojimus" used to fire StartNavigation("sustojimus")
        // because "rodyk" is a StartNavigation prefix.
        // ListWaypoints patterns are checked first.
        val cmd = parse("Rodyk sustojimus")
        assertEquals(
            "REGRESSION: 'Rodyk sustojimus' incorrectly maps to ${cmd::class.simpleName}",
            VoiceCommand.ListWaypoints,
            cmd,
        )
    }

    @Test
    fun `parodyk sustojimus maps to ListWaypoints`() {
        assertEquals(VoiceCommand.ListWaypoints, parse("Parodyk sustojimus"))
    }

    @Test
    fun `kokie sustojimai maps to ListWaypoints`() {
        assertEquals(VoiceCommand.ListWaypoints, parse("kokie sustojimai"))
    }

    @Test
    fun `kiek sustojimu maps to ListWaypoints`() {
        assertEquals(VoiceCommand.ListWaypoints, parse("kiek sustojimų"))
    }

    // ── ContinueRoute ──────────────────────────────────────────────────────

    @Test
    fun `tesk marsruta maps to ContinueRoute`() {
        assertEquals(VoiceCommand.ContinueRoute, parse("Tęsk maršrutą"))
    }

    @Test
    fun `tesk kelione maps to ContinueRoute`() {
        assertEquals(VoiceCommand.ContinueRoute, parse("Tęsk kelionę"))
    }

    @Test
    fun `tesk navigacija maps to ContinueRoute`() {
        assertEquals(VoiceCommand.ContinueRoute, parse("tęsk navigaciją"))
    }

    @Test
    fun `bare tesk maps to ContinueRoute`() {
        assertEquals(VoiceCommand.ContinueRoute, parse("tęsk"))
    }

    @Test
    fun `no-diacritic tesk marsruta maps to ContinueRoute`() {
        assertEquals(VoiceCommand.ContinueRoute, parse("tesk marsruta"))
    }

    // ── Regressions: pre-existing StartNavigation still works ──────────────

    @Test
    fun `REGRESSION vaziuojam i X still maps to StartNavigation`() {
        // "važiuojam į X" without "dar" prefix → StartNavigation, not AddWaypoint.
        // WAYPOINT_ADD_REGEX requires one of the explicit waypoint prefixes before the place.
        val cmd = parse("važiuojam į Akropolį")
        assertEquals(VoiceCommand.StartNavigation::class, cmd::class)
        assertEquals("Akropolį", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test
    fun `REGRESSION rask kelia i still maps to StartNavigation`() {
        val cmd = parse("Rask kelią į Klaipėdą")
        assertEquals(VoiceCommand.StartNavigation::class, cmd::class)
        assertEquals("Klaipėdą", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test
    fun `REGRESSION i still maps to StartNavigation`() {
        val cmd = parse("į Klaipėdą")
        assertEquals(VoiceCommand.StartNavigation::class, cmd::class)
        assertEquals("Klaipėdą", (cmd as VoiceCommand.StartNavigation).destination)
    }

    // ── Regressions: pre-existing StopNavigation still works ───────────────

    @Test
    fun `REGRESSION sustabdyk navigacija still maps to StopNavigation`() {
        assertEquals(VoiceCommand.StopNavigation, parse("Sustabdyk navigaciją"))
    }

    @Test
    fun `REGRESSION atsauk marsruta still maps to StopNavigation`() {
        assertEquals(VoiceCommand.StopNavigation, parse("atšauk maršrutą"))
    }

    @Test
    fun `REGRESSION bare atsauk without paskutini maps to StopNavigation`() {
        // "atšauk" alone (no "paskutinį") → StopNavigation
        assertEquals(VoiceCommand.StopNavigation, parse("atšauk"))
    }

    // ── Regressions: disambiguation ordinals still work ────────────────────

    @Test
    fun `REGRESSION pirma still maps to SelectCandidate 1`() {
        assertEquals(VoiceCommand.SelectCandidate(1), parse("pirmą"))
    }

    @Test
    fun `REGRESSION antra still maps to SelectCandidate 2`() {
        assertEquals(VoiceCommand.SelectCandidate(2), parse("antrą"))
    }

    @Test
    fun `REGRESSION trecia still maps to SelectCandidate 3`() {
        assertEquals(VoiceCommand.SelectCandidate(3), parse("trečią"))
    }

    // ── Regressions: distance/time/mute still work ─────────────────────────

    @Test
    fun `REGRESSION kiek liko still maps to RemainingDistance`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("Kiek liko?"))
    }

    @Test
    fun `REGRESSION kada atvyksime still maps to RemainingTime`() {
        assertEquals(VoiceCommand.RemainingTime, parse("Kada atvyksime?"))
    }

    @Test
    fun `REGRESSION nutildyk still maps to MuteVoice`() {
        assertEquals(VoiceCommand.MuteVoice, parse("Nutildyk"))
    }

    @Test
    fun `REGRESSION pakartok still maps to RepeatInstruction`() {
        assertEquals(VoiceCommand.RepeatInstruction, parse("Pakartok"))
    }
}
