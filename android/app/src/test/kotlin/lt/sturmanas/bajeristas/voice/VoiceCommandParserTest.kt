package lt.sturmanas.bajeristas.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoiceCommandParser].
 *
 * These run on the JVM (src/test) — no Android device or emulator required.
 *
 * Coverage:
 *   - All command types: RemainingDistance, RemainingTime, DestinationInfo,
 *     RepeatInstruction, MuteVoice, UnmuteVoice, StopNavigation, StartNavigation,
 *     GeneralQuestion, Unknown
 *   - Normalisation: uppercase, punctuation, extra whitespace
 *   - Lithuanian characters preserved in StartNavigation destination
 *   - Blank input → Unknown
 *   - Several phrasing variants per command
 */
class VoiceCommandParserTest {

    private fun parse(input: String) = VoiceCommandParser.parse(input)

    // ── RemainingDistance ─────────────────────────────────────────────────

    @Test fun `kiek liko returns RemainingDistance`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("kiek liko"))
    }

    @Test fun `kiek liko uppercase returns RemainingDistance`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("Kiek Liko"))
    }

    @Test fun `kiek liko with punctuation returns RemainingDistance`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("kiek liko?"))
    }

    @Test fun `kiek kilometrų liko returns RemainingDistance`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("kiek kilometrų liko"))
    }

    @Test fun `dar toli returns RemainingDistance`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("dar toli"))
    }

    @Test fun `koks likęs atstumas with Lithuanian chars returns RemainingDistance`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("koks likęs atstumas"))
    }

    @Test fun `kiek liko važiuoti returns RemainingDistance`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("kiek liko važiuoti"))
    }

    // ── RemainingTime ─────────────────────────────────────────────────────

    @Test fun `kada atvyksime returns RemainingTime`() {
        assertEquals(VoiceCommand.RemainingTime, parse("kada atvyksime"))
    }

    @Test fun `kada atvyksime uppercase and punctuation returns RemainingTime`() {
        assertEquals(VoiceCommand.RemainingTime, parse("Kada atvyksime?"))
    }

    @Test fun `kiek laiko liko returns RemainingTime`() {
        assertEquals(VoiceCommand.RemainingTime, parse("kiek laiko liko"))
    }

    @Test fun `kada busim without diacritics returns RemainingTime`() {
        assertEquals(VoiceCommand.RemainingTime, parse("kada busim"))
    }

    @Test fun `kiek minučių liko returns RemainingTime`() {
        assertEquals(VoiceCommand.RemainingTime, parse("kiek minučių liko"))
    }

    // ── DestinationInfo ───────────────────────────────────────────────────

    @Test fun `kur važiuojame returns DestinationInfo`() {
        assertEquals(VoiceCommand.DestinationInfo, parse("kur važiuojame"))
    }

    @Test fun `koks tikslas returns DestinationInfo`() {
        assertEquals(VoiceCommand.DestinationInfo, parse("koks tikslas"))
    }

    // ── RepeatInstruction ─────────────────────────────────────────────────

    @Test fun `pakartok nurodymą returns RepeatInstruction`() {
        assertEquals(VoiceCommand.RepeatInstruction, parse("pakartok nurodymą"))
    }

    @Test fun `pakartok alone returns RepeatInstruction`() {
        assertEquals(VoiceCommand.RepeatInstruction, parse("pakartok"))
    }

    @Test fun `dar kartą returns RepeatInstruction`() {
        assertEquals(VoiceCommand.RepeatInstruction, parse("dar kartą"))
    }

    // ── MuteVoice ─────────────────────────────────────────────────────────

    @Test fun `nutildyk balsą returns MuteVoice`() {
        assertEquals(VoiceCommand.MuteVoice, parse("nutildyk balsą"))
    }

    @Test fun `nutildyk balsą uppercase returns MuteVoice`() {
        assertEquals(VoiceCommand.MuteVoice, parse("NUTILDYK BALSĄ"))
    }

    @Test fun `nekalbėk returns MuteVoice`() {
        assertEquals(VoiceCommand.MuteVoice, parse("nekalbėk"))
    }

    @Test fun `išjunk balsą returns MuteVoice`() {
        assertEquals(VoiceCommand.MuteVoice, parse("išjunk balsą"))
    }

    @Test fun `tildyk returns MuteVoice`() {
        assertEquals(VoiceCommand.MuteVoice, parse("tildyk"))
    }

    // ── UnmuteVoice ───────────────────────────────────────────────────────

    @Test fun `įjunk balsą returns UnmuteVoice`() {
        assertEquals(VoiceCommand.UnmuteVoice, parse("įjunk balsą"))
    }

    @Test fun `ijunk balsa without diacritics returns UnmuteVoice`() {
        assertEquals(VoiceCommand.UnmuteVoice, parse("ijunk balsa"))
    }

    @Test fun `kalbėk returns UnmuteVoice`() {
        assertEquals(VoiceCommand.UnmuteVoice, parse("kalbėk"))
    }

    // ── StopNavigation ────────────────────────────────────────────────────

    @Test fun `sustabdyk navigaciją returns StopNavigation`() {
        assertEquals(VoiceCommand.StopNavigation, parse("sustabdyk navigaciją"))
    }

    @Test fun `atšauk maršrutą returns StopNavigation`() {
        assertEquals(VoiceCommand.StopNavigation, parse("atšauk maršrutą"))
    }

    @Test fun `baik navigaciją returns StopNavigation`() {
        assertEquals(VoiceCommand.StopNavigation, parse("baik navigaciją"))
    }

    @Test fun `nebevažiuojam returns StopNavigation`() {
        assertEquals(VoiceCommand.StopNavigation, parse("nebevažiuojam"))
    }

    @Test fun `sustabdyk alone returns StopNavigation`() {
        assertEquals(VoiceCommand.StopNavigation, parse("sustabdyk"))
    }

    // ── StartNavigation ───────────────────────────────────────────────────

    @Test fun `važiuojam į extracts destination`() {
        val result = parse("važiuojam į Taikos prospektą 61, Klaipėda")
        assertTrue("Expected StartNavigation", result is VoiceCommand.StartNavigation)
        assertEquals("Taikos prospektą 61, Klaipėda", (result as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `rask kelią į extracts destination`() {
        val result = parse("rask kelią į Vilniaus gatvę 1, Vilnius")
        assertTrue(result is VoiceCommand.StartNavigation)
        assertEquals("Vilniaus gatvę 1, Vilnius", (result as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `naviguok į extracts destination`() {
        val result = parse("naviguok į Kauną")
        assertTrue(result is VoiceCommand.StartNavigation)
        assertEquals("Kauną", (result as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `rodyk kelią į extracts destination`() {
        val result = parse("rodyk kelią į Gedimino prospektą, Vilnius")
        assertTrue(result is VoiceCommand.StartNavigation)
        assertEquals("Gedimino prospektą, Vilnius", (result as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `StartNavigation preserves Lithuanian characters in destination`() {
        val result = parse("važiuojam į Žirmūnų gatvę 68a, Vilnius")
        assertTrue(result is VoiceCommand.StartNavigation)
        assertEquals("Žirmūnų gatvę 68a, Vilnius", (result as VoiceCommand.StartNavigation).destination)
    }

    // ── GeneralQuestion ───────────────────────────────────────────────────

    @Test fun `unknown phrase returns GeneralQuestion`() {
        val result = parse("labas rytas")
        assertTrue("Expected GeneralQuestion but got $result", result is VoiceCommand.GeneralQuestion)
    }

    @Test fun `weather question returns GeneralQuestion`() {
        assertTrue(parse("koks oras Klaipėdoje") is VoiceCommand.GeneralQuestion)
    }

    // ── Unknown ───────────────────────────────────────────────────────────

    @Test fun `blank input returns Unknown`() {
        assertTrue(parse("") is VoiceCommand.Unknown)
    }

    @Test fun `whitespace-only input returns Unknown`() {
        assertTrue(parse("   ") is VoiceCommand.Unknown)
    }

    // ── Normalisation edge cases ──────────────────────────────────────────

    @Test fun `extra spaces around known phrase returns correct command`() {
        assertEquals(VoiceCommand.RemainingDistance, parse("  kiek liko  "))
    }

    @Test fun `punctuation stripped before matching`() {
        assertEquals(VoiceCommand.StopNavigation, parse("sustabdyk navigaciją!!!"))
    }

    @Test fun `mixed case command recognized`() {
        assertEquals(VoiceCommand.RemainingTime, parse("KADA ATVYKSIME"))
    }

    @Test fun `normalize internal helper lowercases and strips punctuation`() {
        val result = VoiceCommandParser.normalize("Kiek Liko?!")
        assertEquals("kiek liko", result)
    }

    @Test fun `normalize collapses multiple spaces`() {
        val result = VoiceCommandParser.normalize("kiek   liko")
        assertEquals("kiek liko", result)
    }
}
