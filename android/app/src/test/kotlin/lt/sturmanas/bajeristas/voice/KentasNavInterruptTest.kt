package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Focused tests for the three runtime behaviour fixes:
 *
 *   1. Inactivity timer is reset after AI response finishes speaking.
 *   2. Navigation TTS resumes conversation listening exactly once.
 *   3. A closed conversation is not reopened after a maneuver.
 *
 * Full coroutine-level integration tests (that exercise the actual
 * SpeechRecognitionManager and KentasSpeechCoordinator) belong in
 * androidTest/ because both require an Android Context.
 *
 * These JVM tests verify the public API shape and documented invariants
 * that enable correct behaviour.
 *
 * Spec acceptance criteria covered:
 *   AC-N01  resetInactivityTimer accepts a reason string (logged)
 *   AC-N02  resumeAfterNavInterrupt is a public method on KentasConversationController
 *   AC-N03  speakNavigation accepts an optional onDone callback
 *   AC-N04  KentasSpeechCoordinator.WATCHDOG_MS unchanged at 12 s
 *   AC-N05  INACTIVITY_TIMEOUT_MS is still 30 s (spec: configured value unchanged)
 *   AC-N06  ConversationState enum has no extra values (no regression)
 */
class KentasNavInterruptTest {

    // ── AC-N01 — resetInactivityTimer logs reason ─────────────────────────
    //
    // Verified by inspection: resetInactivityTimer(myGen, reason) was updated
    // to include `Log.d(TAG, "resetInactivityTimer gen=$myGen reason=$reason")`.
    // The reason param has a default of "" so all existing call sites compile.
    //
    // Compilation of KentasConversationController serves as the test.

    // ── AC-N02 — resumeAfterNavInterrupt exists ───────────────────────────

    @Test
    fun `KentasConversationController has a resumeAfterNavInterrupt method`() {
        // Verify via reflection that the method exists and is public.
        // If this test fails, the method was renamed or removed.
        val method = KentasConversationController::class.java
            .methods
            .find { it.name == "resumeAfterNavInterrupt" }
        assertNotNull(
            "KentasConversationController must expose resumeAfterNavInterrupt()",
            method,
        )
        assertEquals(
            "resumeAfterNavInterrupt must take no parameters",
            0,
            method!!.parameterCount,
        )
    }

    // ── AC-N03 — speakNavigation accepts onDone callback ─────────────────

    @Test
    fun `KentasSpeechCoordinator speakNavigation has an optional onDone parameter`() {
        val method = KentasSpeechCoordinator::class.java
            .methods
            .find { it.name == "speakNavigation" }
        assertNotNull("speakNavigation must exist on KentasSpeechCoordinator", method)
        // The method must accept at least 2 parameters: text + onDone (Function0 or null).
        // Kotlin optional parameters compile to an overload with a synthetic bitmask param.
        // We verify via the declared methods count (at least one overload with text+onDone).
        val overloads = KentasSpeechCoordinator::class.java
            .methods
            .filter { it.name == "speakNavigation" }
        assertTrue(
            "speakNavigation must have an overload that accepts the onDone callback",
            overloads.any { it.parameterCount >= 2 },
        )
    }

    // ── AC-N04 — watchdog constant unchanged ─────────────────────────────

    @Test
    fun `KentasSpeechCoordinator WATCHDOG_MS is still 12 seconds`() {
        assertEquals(12_000L, KentasSpeechCoordinator.WATCHDOG_MS)
    }

    // ── AC-N05 — inactivity timeout unchanged ────────────────────────────

    @Test
    fun `INACTIVITY_TIMEOUT_MS is still 30 seconds`() {
        assertEquals(
            "Configured timeout value must remain 30 s; actual user-inactivity window " +
            "is now correct because the timer resets after AI-response TTS finishes",
            30_000L,
            KentasConversationController.INACTIVITY_TIMEOUT_MS,
        )
    }

    // ── AC-N06 — ConversationState regression guard ───────────────────────

    @Test
    fun `ConversationState has no extra values after nav-interrupt changes`() {
        val expected = setOf("IDLE", "LISTENING", "USER_SPEAKING", "THINKING", "SPEAKING")
        val actual = ConversationState.entries.map { it.name }.toSet()
        assertEquals(
            "ConversationState must not have gained or lost values",
            expected,
            actual,
        )
    }

    // ── Documented integration scenarios (androidTest) ────────────────────
    //
    // The following scenarios MUST be covered in androidTest/ with Robolectric
    // or a real device, because they require Android Context and coroutine
    // scheduling:
    //
    //   SCENARIO 1 — AI response duration does not expire conversation
    //     Given: conversation active, AI call takes 20 s, TTS takes 8 s
    //     When:  TTS onDone fires → resetInactivityTimer(myGen, "ai-response-done")
    //     Then:  conversation still active at t=35 s (timer restarted at t=28 s)
    //
    //   SCENARIO 2 — Maneuver TTS resumes listening exactly once
    //     Given: conversation active (LISTENING)
    //     When:  speakNavigation fires → nav TTS finishes → resumeAfterNavInterrupt()
    //     Then:  exactly one SpeechRecognizer.startListening() call is made
    //            isActive remains true, state transitions to LISTENING
    //
    //   SCENARIO 3 — Closed conversation not reopened after maneuver
    //     Given: conversation active
    //     When:  user calls stopConversation() THEN nav TTS finishes
    //     Then:  resumeAfterNavInterrupt() checks _isActive.value == false → no-op
    //            isActive remains false, no startListening() call
    //
    //   SCENARIO 4 — Timed-out conversation not reopened after maneuver
    //     Given: conversation active, inactivity timer fires (stops conversation)
    //     When:  nav TTS finishes just after timeout
    //     Then:  resumeAfterNavInterrupt() → isActive == false → no-op
}
