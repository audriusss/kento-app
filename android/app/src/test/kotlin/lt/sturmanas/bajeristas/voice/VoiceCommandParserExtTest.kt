package lt.sturmanas.bajeristas.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extension tests for [VoiceCommandParser] covering:
 *
 *  - New NAV_PREFIX_REGEX prefixes: `į`, `važiuojam` (without į),
 *    `nuvežk į`, `vežk į`, `rask`, `rodyk`, `artimiausia`, `artimiausias`
 *  - [VoiceCommand.SelectCandidate] ordinals: pirmą / antrą / trečią
 *    (including no-diacritic and full-word alternatives)
 *  - Regression: "kiek liko" must NOT match StartNavigation
 *  - Regression: "kalbėk" must NOT match StartNavigation (UnmuteVoice instead)
 *  - More-specific prefix beats less-specific (rask kelią į vs rask)
 */
class VoiceCommandParserExtTest {

    // ── StartNavigation — "į" prefix ──────────────────────────────────────

    @Test fun `į Kauną returns StartNavigation Kauną`() {
        val cmd = VoiceCommandParser.parse("į Kauną")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("Kauną", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `į centrą returns StartNavigation centrą`() {
        val cmd = VoiceCommandParser.parse("į centrą")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("centrą", (cmd as VoiceCommand.StartNavigation).destination)
    }

    // ── StartNavigation — "važiuojam" without į ───────────────────────────

    @Test fun `važiuojam namo returns StartNavigation namo`() {
        val cmd = VoiceCommandParser.parse("važiuojam namo")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("namo", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `važiuojam Šiaulius returns StartNavigation Šiaulius`() {
        val cmd = VoiceCommandParser.parse("važiuojam Šiaulius")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("Šiaulius", (cmd as VoiceCommand.StartNavigation).destination)
    }

    // ── StartNavigation — "nuvežk į" prefix ──────────────────────────────

    @Test fun `nuvežk į Vilnių returns StartNavigation Vilnių`() {
        val cmd = VoiceCommandParser.parse("nuvežk į Vilnių")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("Vilnių", (cmd as VoiceCommand.StartNavigation).destination)
    }

    // ── StartNavigation — "vežk į" prefix ────────────────────────────────

    @Test fun `vežk į Šiaulius returns StartNavigation Šiaulius`() {
        val cmd = VoiceCommandParser.parse("vežk į Šiaulius")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("Šiaulius", (cmd as VoiceCommand.StartNavigation).destination)
    }

    // ── StartNavigation — "rask" prefix ───────────────────────────────────

    @Test fun `rask Akropolį returns StartNavigation Akropolį`() {
        val cmd = VoiceCommandParser.parse("rask Akropolį")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("Akropolį", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `rask kelią į Kauną uses more-specific prefix`() {
        val cmd = VoiceCommandParser.parse("rask kelią į Kauną")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("Kauną", (cmd as VoiceCommand.StartNavigation).destination)
    }

    // ── StartNavigation — "rodyk" prefix ──────────────────────────────────

    @Test fun `rodyk Maximą returns StartNavigation Maximą`() {
        val cmd = VoiceCommandParser.parse("rodyk Maximą")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("Maximą", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `rodyk kelią į Klaipėdą uses more-specific prefix`() {
        val cmd = VoiceCommandParser.parse("rodyk kelią į Klaipėdą")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("Klaipėdą", (cmd as VoiceCommand.StartNavigation).destination)
    }

    // ── StartNavigation — "artimiausia / artimiausias" prefix ────────────

    @Test fun `artimiausia degalinė returns StartNavigation degalinė`() {
        val cmd = VoiceCommandParser.parse("artimiausia degalinė")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("degalinė", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test fun `artimiausias bankomatas returns StartNavigation bankomatas`() {
        val cmd = VoiceCommandParser.parse("artimiausias bankomatas")
        assertTrue(cmd is VoiceCommand.StartNavigation)
        assertEquals("bankomatas", (cmd as VoiceCommand.StartNavigation).destination)
    }

    // ── SelectCandidate ordinals ───────────────────────────────────────────

    @Test fun `pirmą returns SelectCandidate index 1`() {
        val cmd = VoiceCommandParser.parse("pirmą")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(1, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `pirmas returns SelectCandidate index 1`() {
        val cmd = VoiceCommandParser.parse("pirmas")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(1, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `antrą returns SelectCandidate index 2`() {
        val cmd = VoiceCommandParser.parse("antrą")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(2, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `trečią returns SelectCandidate index 3`() {
        val cmd = VoiceCommandParser.parse("trečią")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(3, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `trecia no-diacritic returns SelectCandidate index 3`() {
        val cmd = VoiceCommandParser.parse("trecia")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(3, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `pirmą variantą starts-with match returns SelectCandidate index 1`() {
        val cmd = VoiceCommandParser.parse("pirmą variantą")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(1, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `antras no-diacritic returns SelectCandidate index 2`() {
        val cmd = VoiceCommandParser.parse("antras")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(2, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `du returns SelectCandidate index 2`() {
        val cmd = VoiceCommandParser.parse("du")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(2, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `trys returns SelectCandidate index 3`() {
        val cmd = VoiceCommandParser.parse("trys")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(3, (cmd as VoiceCommand.SelectCandidate).index)
    }

    @Test fun `vieną returns SelectCandidate index 1`() {
        val cmd = VoiceCommandParser.parse("vieną")
        assertTrue(cmd is VoiceCommand.SelectCandidate)
        assertEquals(1, (cmd as VoiceCommand.SelectCandidate).index)
    }


    // ── Regression: "kiek liko" must NOT become StartNavigation ──────────

    @Test fun `kiek liko returns RemainingDistance not StartNavigation`() {
        val cmd = VoiceCommandParser.parse("kiek liko")
        assertTrue(
            "Expected RemainingDistance but got ${cmd::class.simpleName}",
            cmd is VoiceCommand.RemainingDistance,
        )
        assertFalse(cmd is VoiceCommand.StartNavigation)
    }

    @Test fun `kiek liko važiuoti returns RemainingDistance`() {
        val cmd = VoiceCommandParser.parse("kiek liko važiuoti")
        assertTrue(cmd is VoiceCommand.RemainingDistance)
    }

    // ── Regression: "kalbėk" must return UnmuteVoice ─────────────────────

    @Test fun `kalbėk returns UnmuteVoice not StartNavigation`() {
        val cmd = VoiceCommandParser.parse("kalbėk")
        assertEquals(VoiceCommand.UnmuteVoice, cmd)
        assertFalse(cmd is VoiceCommand.StartNavigation)
    }

    // ── Edge: blank destination after prefix → GeneralQuestion / Unknown ──

    @Test fun `į alone without destination does not return StartNavigation`() {
        // "į" with no space+word after it won't match the prefix regex ("į\s+")
        val cmd = VoiceCommandParser.parse("į")
        assertFalse(cmd is VoiceCommand.StartNavigation)
    }
}
