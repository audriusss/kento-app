package lt.sturmanas.bajeristas.voice

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [VoiceCommandParser.looksLikeDestination] and the end-to-end
 * routing of plain-text inputs through [VoiceCommandParser.parse].
 *
 * These tests guard the regression where "Akropolis", "Taikos 61", "Lidl", and
 * "degalinė" were falling to [VoiceCommand.GeneralQuestion] / OpenAI instead
 * of being classified as [VoiceCommand.StartNavigation].
 */
class DestinationClassifierTest {

    private val parser = VoiceCommandParser

    // ── looksLikeDestination direct tests ──────────────────────────────────

    @Test
    fun `digit in input is a destination`() {
        assertTrue(parser.looksLikeDestination("Taikos 61", parser.normalize("Taikos 61")))
    }

    @Test
    fun `street address with house number is a destination`() {
        assertTrue(parser.looksLikeDestination("Gedimino pr 3", parser.normalize("Gedimino pr 3")))
    }

    @Test
    fun `known brand Lidl is a destination`() {
        assertTrue(parser.looksLikeDestination("Lidl", parser.normalize("Lidl")))
    }

    @Test
    fun `known brand Maxima is a destination`() {
        assertTrue(parser.looksLikeDestination("Maxima", parser.normalize("Maxima")))
    }

    @Test
    fun `known brand Akropolis is a destination`() {
        assertTrue(parser.looksLikeDestination("Akropolis", parser.normalize("Akropolis")))
    }

    @Test
    fun `category word degaline is a destination`() {
        val raw = "degalinė"
        assertTrue(parser.looksLikeDestination(raw, parser.normalize(raw)))
    }

    @Test
    fun `category word kavine is a destination`() {
        val raw = "kavinė"
        assertTrue(parser.looksLikeDestination(raw, parser.normalize(raw)))
    }

    @Test
    fun `category word vaistine is a destination`() {
        val raw = "vaistinė"
        assertTrue(parser.looksLikeDestination(raw, parser.normalize(raw)))
    }

    @Test
    fun `single place name without conversational pattern is a destination`() {
        assertTrue(parser.looksLikeDestination("Lazdynai", parser.normalize("Lazdynai")))
    }

    @Test
    fun `two-word place name is a destination`() {
        assertTrue(parser.looksLikeDestination("Gedimino prospektas", parser.normalize("Gedimino prospektas")))
    }

    @Test
    fun `three-word place name is a destination`() {
        assertTrue(parser.looksLikeDestination("Senamiestis Vilniuje", parser.normalize("Senamiestis Vilniuje")))
    }

    @Test
    fun `conversational question is NOT a destination`() {
        val raw = "kas yra kelio ženklai"
        assertFalse(parser.looksLikeDestination(raw, parser.normalize(raw)))
    }

    @Test
    fun `labas greeting is NOT a destination`() {
        assertFalse(parser.looksLikeDestination("Labas", parser.normalize("Labas")))
    }

    @Test
    fun `aciu is NOT a destination`() {
        assertFalse(parser.looksLikeDestination("Ačiū", parser.normalize("Ačiū")))
    }

    @Test
    fun `long conversational phrase is NOT a destination`() {
        val raw = "ar galime sustoti pailsėti"
        assertFalse(parser.looksLikeDestination(raw, parser.normalize(raw)))
    }

    // ── End-to-end parse() routing tests ───────────────────────────────────

    @Test
    fun `parse Akropolis returns StartNavigation`() {
        val cmd = parser.parse("Akropolis")
        assertTrue("Expected StartNavigation, got $cmd", cmd is VoiceCommand.StartNavigation)
        assertEquals("Akropolis", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test
    fun `parse Lidl returns StartNavigation`() {
        val cmd = parser.parse("Lidl")
        assertTrue("Expected StartNavigation, got $cmd", cmd is VoiceCommand.StartNavigation)
    }

    @Test
    fun `parse address with number returns StartNavigation`() {
        val cmd = parser.parse("Taikos 61")
        assertTrue("Expected StartNavigation, got $cmd", cmd is VoiceCommand.StartNavigation)
    }

    @Test
    fun `parse degaline returns StartNavigation`() {
        val cmd = parser.parse("degalinė")
        assertTrue("Expected StartNavigation, got $cmd", cmd is VoiceCommand.StartNavigation)
    }

    @Test
    fun `parse kiek liko does NOT route to StartNavigation`() {
        val cmd = parser.parse("kiek liko")
        assertFalse("Should not be StartNavigation", cmd is VoiceCommand.StartNavigation)
        assertTrue(cmd is VoiceCommand.RemainingDistance)
    }

    @Test
    fun `parse ką sakei does NOT route to StartNavigation`() {
        val cmd = parser.parse("ką sakei")
        assertFalse("Should not be StartNavigation", cmd is VoiceCommand.StartNavigation)
        assertTrue(cmd is VoiceCommand.RepeatInstruction)
    }

    @Test
    fun `parse nustok klausyti returns StopListening`() {
        val cmd = parser.parse("nustok klausyti")
        assertTrue("Expected StopListening, got $cmd", cmd is VoiceCommand.StopListening)
    }

    @Test
    fun `parse ishjunk mikrofona returns StopListening not MuteVoice`() {
        val cmd = parser.parse("išjunk mikrofoną")
        assertTrue("Expected StopListening, got $cmd", cmd is VoiceCommand.StopListening)
        assertFalse("Must not be MuteVoice", cmd is VoiceCommand.MuteVoice)
    }

    @Test
    fun `parse baik klausytis returns StopListening`() {
        val cmd = parser.parse("baik klausytis")
        assertTrue("Expected StopListening, got $cmd", cmd is VoiceCommand.StopListening)
    }
}
