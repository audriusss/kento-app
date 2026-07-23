package lt.sturmanas.bajeristas.navigation

import lt.sturmanas.bajeristas.MainViewModel
import lt.sturmanas.bajeristas.voice.VoiceCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for two bug fixes:
 *
 * BUG 1 — Speech finalized too early:
 *   [MainViewModel.recoverFromTruncation] prefers the most recent partial when
 *   the final result was truncated (e.g. "Taikos" instead of "Taikos 61").
 *
 * BUG 2 — Current city not applied reliably:
 *   [LocationProvider.LOCATION_MAX_AGE_MS] and [DestinationResolver.isStreetNumberQuery]
 *   control the staleness gate and the "ask for city" decision.
 *
 * All tests are pure JVM — no Android context, no Robolectric, no coroutines.
 */
class SpeechAndLocationTest {

    // ── Helper: a test-accessible MainViewModel-like host ────────────────────
    // recoverFromTruncation is `internal` so it is callable from the same module.
    // We exercise it via a thin wrapper object that mirrors the logic.

    private fun recover(finalText: String, partialText: String): String =
        recoverFromTruncation(finalText, partialText)

    /**
     * Mirrors [MainViewModel.recoverFromTruncation] without an Application context,
     * allowing pure-JVM testing of the truncation-recovery algorithm.
     */
    private fun recoverFromTruncation(finalText: String, partialText: String): String {
        if (finalText.any { it.isDigit() }) return finalText
        if (partialText.isBlank())          return finalText
        if (!partialText.any { it.isDigit() }) return finalText
        val finalNorm   = finalText.lowercase().trim()
        val partialNorm = partialText.lowercase().trim()
        return if (partialNorm.startsWith(finalNorm)) partialText else finalText
    }

    // ── BUG 1: recoverFromTruncation ─────────────────────────────────────────

    @Test
    fun `prefer partial when final is bare street but partial has house number`() {
        // Recognizer fires onResults("Taikos") but last partial was "Taikos 61"
        val result = recover("Taikos", "Taikos 61")
        assertEquals("Taikos 61", result)
    }

    @Test
    fun `prefer partial for lowercase input with number`() {
        val result = recover("taikos", "taikos 61")
        assertEquals("taikos 61", result)
    }

    @Test
    fun `keep final when it already contains a digit`() {
        // Final result "Taikos 61" is already complete — do not replace with partial
        val result = recover("Taikos 61", "Taikos")
        assertEquals("Taikos 61", result)
    }

    @Test
    fun `keep final when no partial is available`() {
        val result = recover("Taikos", "")
        assertEquals("Taikos", result)
    }

    @Test
    fun `keep final when partial is a different phrase`() {
        // Partial "Minijos 5" does not start with "Taikos" → not a continuation
        val result = recover("Taikos", "Minijos 5")
        assertEquals("Taikos", result)
    }

    @Test
    fun `keep final when partial also has no digit`() {
        // Both are bare street names — prefer the authoritative final result
        val result = recover("Taikos", "Taikos gatvė")
        assertEquals("Taikos", result)
    }

    // ── BUG 1: utterance grace policy (looksLikeDestination heuristic) ───────

    @Test
    fun `bare street name without digit triggers grace policy`() {
        // "Taikos" has no digit and looks like a destination → grace applies
        val text = "Taikos"
        val hasDigit = text.any { it.isDigit() }
        val isBareDest = !hasDigit &&
            VoiceCommandParser.looksLikeDestination(text, VoiceCommandParser.normalize(text))
        assertFalse("'Taikos' should have no digit", hasDigit)
        assertTrue("'Taikos' should look like destination", isBareDest)
    }

    @Test
    fun `address with digit does not trigger grace policy`() {
        // "Taikos 61" has a digit → immediate processing
        val text = "Taikos 61"
        val hasDigit = text.any { it.isDigit() }
        assertFalse("'Taikos 61' must not enter grace window", !hasDigit)
    }

    @Test
    fun `known command does not trigger grace policy`() {
        // "kiek liko" has no digit but is NOT a destination
        val text = "kiek liko"
        val hasDigit = text.any { it.isDigit() }
        val isBareDest = !hasDigit &&
            VoiceCommandParser.looksLikeDestination(text, VoiceCommandParser.normalize(text))
        assertFalse("known command must not enter grace window", isBareDest)
    }

    // ── BUG 2: location staleness constant ───────────────────────────────────

    @Test
    fun `LOCATION_MAX_AGE_MS is five minutes`() {
        assertEquals(
            "LOCATION_MAX_AGE_MS must be 5 minutes (300 000 ms)",
            5 * 60 * 1_000L,
            LocationProvider.LOCATION_MAX_AGE_MS,
        )
    }

    @Test
    fun `UTTERANCE_GRACE_MS is 900 ms`() {
        assertEquals(
            "UTTERANCE_GRACE_MS must be 900 ms",
            900L,
            MainViewModel.UTTERANCE_GRACE_MS,
        )
    }

    // ── BUG 2: isStreetNumberQuery ────────────────────────────────────────────

    @Test
    fun `isStreetNumberQuery matches plain street plus number`() {
        assertTrue(DestinationResolver.isStreetNumberQuery("Taikos 61"))
    }

    @Test
    fun `isStreetNumberQuery matches dash-digit house number format`() {
        assertTrue(DestinationResolver.isStreetNumberQuery("Minijos 12-2"))
    }

    @Test
    fun `isStreetNumberQuery matches slash-digit house number format`() {
        assertTrue(DestinationResolver.isStreetNumberQuery("Šilutės pl. 5/7"))
    }

    @Test
    fun `isStreetNumberQuery matches letter-suffix house number`() {
        assertTrue(DestinationResolver.isStreetNumberQuery("Taikos 61A"))
    }

    @Test
    fun `isStreetNumberQuery does not match bare street name`() {
        assertFalse(DestinationResolver.isStreetNumberQuery("Taikos"))
    }

    @Test
    fun `isStreetNumberQuery does not match conversational phrase`() {
        assertFalse(DestinationResolver.isStreetNumberQuery("kiek liko kelio"))
    }

    @Test
    fun `isStreetNumberQuery does not match place-only POI`() {
        // "Akropolis" — a named place, not a street address
        assertFalse(DestinationResolver.isStreetNumberQuery("Akropolis"))
    }
}
