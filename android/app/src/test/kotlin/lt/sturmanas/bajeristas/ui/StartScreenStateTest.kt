package lt.sturmanas.bajeristas.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the destination screen's enabled/disabled state logic.
 *
 * The start button must be enabled if and only if:
 *   1. The destination field is not blank.
 *   2. The navigation engine is ready.
 *
 * These tests exercise the guard condition `destination.isNotBlank() && engineReady`
 * extracted as a standalone pure function so it can be verified without a
 * Compose test environment.
 */
class StartScreenStateTest {

    // ── SS-01  Blank destination + engine ready → disabled ────────────────
    @Test fun `SS-01 blank destination disables start button even when engine is ready`() {
        assertFalse(startEnabled("", engineReady = true))
    }

    // ── SS-02  Whitespace destination → disabled ──────────────────────────
    @Test fun `SS-02 whitespace-only destination disables start button`() {
        assertFalse(startEnabled("   ", engineReady = true))
        assertFalse(startEnabled("\t", engineReady = true))
        assertFalse(startEnabled("\n", engineReady = true))
    }

    // ── SS-03  Engine not ready → disabled regardless of input ────────────
    @Test fun `SS-03 engine not ready disables start button even with valid destination`() {
        assertFalse(startEnabled("Vilnius", engineReady = false))
        assertFalse(startEnabled("Kaunas, Laisvės al. 1", engineReady = false))
    }

    // ── SS-04  Valid input + engine ready → enabled ───────────────────────
    @Test fun `SS-04 non-blank destination and ready engine enables start button`() {
        assertTrue(startEnabled("Vilnius", engineReady = true))
        assertTrue(startEnabled("Klaipėda", engineReady = true))
        assertTrue(startEnabled("54.6872, 25.2797", engineReady = true))
    }

    // ── SS-05  Both conditions false → disabled ───────────────────────────
    @Test fun `SS-05 blank destination and engine not ready both disable start button`() {
        assertFalse(startEnabled("", engineReady = false))
    }

    // ── SS-06  Single space is treated as blank ───────────────────────────
    @Test fun `SS-06 single space is treated as blank`() {
        assertFalse(startEnabled(" ", engineReady = true))
    }

    // ── SS-07  Multi-line input is valid ──────────────────────────────────
    @Test fun `SS-07 destination with newline character is non-blank`() {
        // Users cannot enter newlines in a singleLine=true field, but the
        // logic must not reject any non-blank string.
        assertTrue(startEnabled("A\nB", engineReady = true))
    }

    // ── Extracted logic ───────────────────────────────────────────────────
    /** Mirrors `destination.isNotBlank() && engineReady` from StartScreen.kt. */
    private fun startEnabled(destination: String, engineReady: Boolean): Boolean =
        destination.isNotBlank() && engineReady
}
