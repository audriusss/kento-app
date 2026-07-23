package lt.sturmanas.bajeristas.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the personality / navigation command classification fix.
 *
 * Requirements verified:
 * 1. NAVIGATION_COMMAND phrases → deterministic VoiceCommand, never GeneralQuestion.
 * 2. CASUAL_CONVERSATION phrases → GeneralQuestion (→ AI personality path).
 * 3. DESTINATION_COMMAND phrases → StartNavigation (→ DestinationResolver path).
 * 4. Destination resolution heuristic (looksLikeDestination) is unchanged for
 *    known address and POI inputs.
 * 5. Casual responses are not blocked by navigation mode — classification is
 *    purely textual and independent of nav state.
 *
 * All tests are pure JVM — no Android context, no coroutines, no Robolectric.
 */
class CommandClassificationTest {

    // ─────────────────────────────────────────────────────────────────────────
    // 1. NAVIGATION_COMMAND — must NOT reach the AI personality path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `kiek liko does not use AI`() {
        val cmd = VoiceCommandParser.parse("kiek liko")
        assertTrue(
            "Expected RemainingDistance, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.RemainingDistance,
        )
        assertFalse("Navigation command must not be GeneralQuestion", cmd is VoiceCommand.GeneralQuestion)
    }

    @Test
    fun `kada atvyksime does not use AI`() {
        val cmd = VoiceCommandParser.parse("kada atvyksime")
        assertTrue(
            "Expected RemainingTime, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.RemainingTime,
        )
        assertFalse(cmd is VoiceCommand.GeneralQuestion)
    }

    @Test
    fun `pakartok nurodymą does not use AI`() {
        val cmd = VoiceCommandParser.parse("pakartok nurodymą")
        assertTrue(
            "Expected RepeatInstruction, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.RepeatInstruction,
        )
        assertFalse(cmd is VoiceCommand.GeneralQuestion)
    }

    @Test
    fun `sustabdyk navigaciją does not use AI`() {
        val cmd = VoiceCommandParser.parse("sustabdyk navigaciją")
        assertTrue(
            "Expected StopNavigation, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.StopNavigation,
        )
        assertFalse(cmd is VoiceCommand.GeneralQuestion)
    }

    @Test
    fun `dar toli does not use AI`() {
        val cmd = VoiceCommandParser.parse("dar toli")
        assertTrue(
            "Expected RemainingDistance, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.RemainingDistance,
        )
        assertFalse(cmd is VoiceCommand.GeneralQuestion)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. CASUAL_CONVERSATION — must reach the AI personality path (GeneralQuestion)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `papasakok bajerį uses personality AI`() {
        val cmd = VoiceCommandParser.parse("papasakok bajerį")
        assertTrue(
            "Expected GeneralQuestion, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.GeneralQuestion,
        )
    }

    @Test
    fun `pralinksmink uses personality AI`() {
        val cmd = VoiceCommandParser.parse("pralinksmink")
        assertTrue(
            "Expected GeneralQuestion for 'pralinksmink', got ${cmd::class.simpleName}",
            cmd is VoiceCommand.GeneralQuestion,
        )
    }

    @Test
    fun `kaip gyveni uses personality AI`() {
        val cmd = VoiceCommandParser.parse("kaip gyveni")
        assertTrue(
            "Expected GeneralQuestion, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.GeneralQuestion,
        )
    }

    @Test
    fun `ką veiki uses personality AI`() {
        val cmd = VoiceCommandParser.parse("ką veiki")
        assertTrue(
            "Expected GeneralQuestion for 'ką veiki', got ${cmd::class.simpleName}",
            cmd is VoiceCommand.GeneralQuestion,
        )
    }

    @Test
    fun `kaip sekasi uses personality AI`() {
        val cmd = VoiceCommandParser.parse("kaip sekasi")
        assertTrue(
            "Expected GeneralQuestion, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.GeneralQuestion,
        )
    }

    @Test
    fun `anekdotą uses personality AI`() {
        val cmd = VoiceCommandParser.parse("papasakok anekdotą")
        assertTrue(
            "Expected GeneralQuestion, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.GeneralQuestion,
        )
    }

    @Test
    fun `casual response is not blocked by navigation mode`() {
        // Classification is purely textual — it does not depend on NavigationState.
        // Verify that several different casual inputs all route to GeneralQuestion
        // regardless of what nav state might look like.
        val casuals = listOf("pralinksmink", "ką veiki", "kaip gyveni", "papasakok bajerį")
        for (phrase in casuals) {
            val cmd = VoiceCommandParser.parse(phrase)
            assertTrue(
                "Casual phrase '$phrase' must reach AI regardless of nav mode: got ${cmd::class.simpleName}",
                cmd is VoiceCommand.GeneralQuestion,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. DESTINATION_COMMAND — must reach StartNavigation (DestinationResolver)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Taikos 61 routes to destination resolver`() {
        val cmd = VoiceCommandParser.parse("Taikos 61")
        assertTrue(
            "Expected StartNavigation, got ${cmd::class.simpleName}",
            cmd is VoiceCommand.StartNavigation,
        )
        assertEquals("Taikos 61", (cmd as VoiceCommand.StartNavigation).destination)
    }

    @Test
    fun `Lidl routes to destination resolver`() {
        val cmd = VoiceCommandParser.parse("Lidl")
        assertTrue(
            "Expected StartNavigation for brand 'Lidl', got ${cmd::class.simpleName}",
            cmd is VoiceCommand.StartNavigation,
        )
    }

    @Test
    fun `Akropolis routes to destination resolver`() {
        // Proper noun, single word starting with uppercase — should still be a destination
        val cmd = VoiceCommandParser.parse("Akropolis")
        assertTrue(
            "Expected StartNavigation for 'Akropolis', got ${cmd::class.simpleName}",
            cmd is VoiceCommand.StartNavigation,
        )
    }

    @Test
    fun `degalinė routes to destination resolver`() {
        val cmd = VoiceCommandParser.parse("degalinė")
        assertTrue(
            "Expected StartNavigation for category POI 'degalinė', got ${cmd::class.simpleName}",
            cmd is VoiceCommand.StartNavigation,
        )
    }

    @Test
    fun `lowercase single word verb does not route to destination resolver`() {
        // "pralinksmink" is an imperative verb, not a place name.
        // Before the fix it was mis-classified as StartNavigation.
        val cmd = VoiceCommandParser.parse("pralinksmink")
        assertFalse(
            "Lowercase verb 'pralinksmink' must NOT route to StartNavigation",
            cmd is VoiceCommand.StartNavigation,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. looksLikeDestination boundary cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `looksLikeDestination uppercase single word is destination`() {
        assertTrue(VoiceCommandParser.looksLikeDestination("Lazdynai", "lazdynai"))
    }

    @Test
    fun `looksLikeDestination lowercase single word is not destination`() {
        assertFalse(VoiceCommandParser.looksLikeDestination("pralinksmink", "pralinksmink"))
    }

    @Test
    fun `looksLikeDestination address with digit is always destination`() {
        assertTrue(VoiceCommandParser.looksLikeDestination("Taikos 61", "taikos 61"))
        assertTrue(VoiceCommandParser.looksLikeDestination("taikos 61", "taikos 61"))
    }
}
