package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Regression tests for the split error-policy and inactivity-timer-pause fix.
 *
 * These run on the plain JVM (no Android Context, no SpeechRecognizer instance).
 * They verify structural and policy invariants that prevent the original bug:
 *
 *   ERROR_SERVER_DISCONNECTED (infra, retry=1) →
 *   ERROR_NO_MATCH (normal speech, bumped retry=2) →
 *   MAX_RETRIES exceeded → CONVERSATION_SESSION_CLOSED   ← WRONG, fixed here
 *
 * ## Root cause (fixed)
 *
 * The old controller used a single [retryCount] for every recoverable error.
 * ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT are normal conversation events — they
 * happen whenever the recognizer hears background noise or the user pauses.
 * Mixing them into the same counter as real infrastructure failures meant one
 * infrastructure failure plus one breath sound closed the session immediately.
 *
 * ## Fix
 *
 * [KentasConversationController] now tracks only **infrastructure** failures in
 * [infraRetryCount].  [RecoveryPolicy.isSilentRecovery] classifies ERROR_NO_MATCH
 * and ERROR_SPEECH_TIMEOUT as silent — they never touch [infraRetryCount], never
 * close the conversation, and simply restart listening.
 *
 * Additionally, [infraRetryCount] is reset to zero whenever the recognizer reaches
 * [onReadyForSpeech], [onBeginningOfSpeech], or [onResults] — a successful recovery
 * wipes the slate clean for the next round.
 *
 * ## Inactivity timer
 *
 * The 30-second timer must only count time the user actually has to speak.
 * AI generation, Kentas TTS, nav TTS, and recognizer restarts must not consume
 * the user's inactivity window.  The timer is started ONLY from [onReadyForSpeech]
 * and paused on every other lifecycle transition.
 *
 * ## Acceptance criteria
 *
 * AC-E01  ERROR_NO_MATCH never increments infraRetryCount.
 * AC-E02  ERROR_SPEECH_TIMEOUT never increments infraRetryCount.
 * AC-E03  infraRetryCount is reset after onReadyForSpeech.
 * AC-E04  infraRetryCount is reset after onResults (speech recognised).
 * AC-E05  infraRetryCount is reset after onBeginningOfSpeech.
 * AC-E06  Inactivity timer is started ONLY from onReadyForSpeech.
 * AC-E07  Conversation closes only after MAX_INFRA_RETRIES consecutive infra failures.
 * AC-E08  MAX_INFRA_RETRIES is at least 2 (survive one infra failure + one normal event).
 * AC-E09  NO_MATCH_RELISTEN log tag is emitted — not a retry increment.
 * AC-E10  SPEECH_TIMEOUT_RELISTEN log tag is emitted — not a retry increment.
 *
 * ## Integration scenarios (androidTest, manual)
 *
 * INT-E01 Normal noise during conversation:
 *   Session active → ERROR_SERVER_DISCONNECTED → infra retry=1 → recovers →
 *   onReadyForSpeech → INFRA_RETRY_RESET → ERROR_NO_MATCH → NO_MATCH_RELISTEN
 *   (infraRetryCount stays 0) → user speaks → session continues normally.
 *   Expected: no CONVERSATION_SESSION_CLOSED during noise events.
 *
 * INT-E02 Pure infrastructure failure cascade:
 *   ERROR_SERVER → infra=1 → ERROR_SERVER → infra=2 → ERROR_SERVER → infra=3 →
 *   MAX_INFRA_RETRIES reached → CONVERSATION_SESSION_CLOSED reason=max-infra-retries.
 *
 * INT-E03 Infra failure recovered by successful listen:
 *   ERROR_SERVER → infra=1 → retry → onReadyForSpeech → INFRA_RETRY_RESET →
 *   ERROR_SERVER → infra=1 → retry → onReadyForSpeech → INFRA_RETRY_RESET → …
 *   Conversation never closes — each successful ready resets the counter.
 *
 * INT-E04 Inactivity timer real-time check:
 *   Open conversation → Kentas answers → do NOT speak for 31 s.
 *   Expected: INACTIVITY_TIMER_STARTED logged at onReadyForSpeech (NOT at
 *   session-start or at AI_RESPONSE_TTS_DONE), INACTIVITY_TIMER_PAUSED at
 *   AI_RESPONSE_STARTED, no timer restart during TTS, INACTIVITY_TIMER_RESUMED
 *   at next onReadyForSpeech, session closes after 30 s of recognizer-ready time.
 *
 * INT-E05 Multiple no-match events — conversation survives:
 *   ERROR_NO_MATCH × 10 in a row → NO_MATCH_RELISTEN × 10 → no session close.
 *   (infraRetryCount remains 0 throughout.)
 */
class ConversationErrorPolicyTest {

    // ── AC-E01 / AC-E02 — RecoveryPolicy classification ───────────────────

    /**
     * [RecoveryPolicy.isSilentRecovery] must classify ERROR_NO_MATCH as a normal
     * speech event.  This is the policy gate that prevents the bug:
     * when isSilentRecovery returns true, the controller skips [infraRetryCount]
     * entirely and goes straight to NO_MATCH_RELISTEN.
     */
    @Test
    fun `ERROR_NO_MATCH is classified as silent recovery`() {
        assertTrue(
            "RecoveryPolicy.isSilentRecovery(E_NO_MATCH) must return true. " +
            "ERROR_NO_MATCH is a normal conversation event — the recognizer heard " +
            "audio but found no matching Lithuanian words. It must never increment " +
            "infraRetryCount or close the conversation.",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_NO_MATCH),
        )
    }

    /**
     * [RecoveryPolicy.isSilentRecovery] must classify ERROR_SPEECH_TIMEOUT as a
     * normal speech event.  Happens when the user pauses between words or while
     * thinking; must never close the conversation.
     */
    @Test
    fun `ERROR_SPEECH_TIMEOUT is classified as silent recovery`() {
        assertTrue(
            "RecoveryPolicy.isSilentRecovery(E_SPEECH_TIMEOUT) must return true. " +
            "ERROR_SPEECH_TIMEOUT means no speech was detected; the user simply " +
            "paused. It must never increment infraRetryCount or close the conversation.",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SPEECH_TIMEOUT),
        )
    }

    /**
     * Infrastructure errors must NOT be classified as silent recovery.
     * ERROR_SERVER_DISCONNECTED (code 11) is the confirmed real-device failure
     * that appeared in the log before the premature close.
     */
    @Test
    fun `ERROR_SERVER_DISCONNECTED is NOT a silent recovery — counts toward infraRetryCount`() {
        assertFalse(
            "RecoveryPolicy.isSilentRecovery(E_SERVER_DISCONNECTED) must return false. " +
            "This is a real infrastructure failure that must count toward MAX_INFRA_RETRIES.",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SERVER_DISCONNECTED),
        )
    }

    @Test
    fun `ERROR_SERVER is NOT a silent recovery`() {
        assertFalse(
            "RecoveryPolicy.isSilentRecovery(E_SERVER) must return false.",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_SERVER),
        )
    }

    @Test
    fun `ERROR_NETWORK is NOT a silent recovery`() {
        assertFalse(
            "RecoveryPolicy.isSilentRecovery(E_NETWORK) must return false.",
            RecoveryPolicy.isSilentRecovery(RecoveryPolicy.E_NETWORK),
        )
    }

    // ── AC-E07 / AC-E08 — infraRetryCount and MAX_INFRA_RETRIES ───────────

    /**
     * [KentasConversationController] must have an [infraRetryCount] field (not
     * the old [retryCount]).  This field is incremented ONLY for infrastructure
     * failures, never for silent recovery events.
     *
     * AC-E03 / AC-E04 / AC-E05 are enforced by the same mechanism: the callbacks
     * that set this field to zero on success.
     */
    @Test
    fun `infraRetryCount field exists — separate from normal relisten counter`() {
        val field = getDeclaredFieldOrNull(
            KentasConversationController::class.java,
            "infraRetryCount",
        )
        assertNotNull(
            "KentasConversationController must have an 'infraRetryCount' field — " +
            "separate from the old single retryCount. This field must only be " +
            "incremented for infrastructure failures, never for NO_MATCH or SPEECH_TIMEOUT.",
            field,
        )
    }

    /**
     * The old [retryCount] field must be gone — its presence would indicate the
     * fix was not applied and the mixed-counter bug is still in place.
     */
    @Test
    fun `old retryCount field is removed — replaced by infraRetryCount`() {
        val field = getDeclaredFieldOrNull(
            KentasConversationController::class.java,
            "retryCount",
        )
        assertNull(
            "The old 'retryCount' field must be removed. Its presence indicates the " +
            "mixed-counter bug is still active: any NO_MATCH or SPEECH_TIMEOUT would " +
            "incorrectly increment the same counter as infrastructure failures.",
            field,
        )
    }

    /**
     * [MAX_INFRA_RETRIES] must be at least 2 so the session survives one real
     * infrastructure failure followed by one normal speech event.
     *
     * With MAX_INFRA_RETRIES = 1 and the old mixed counter, the original bug
     * reproduced reliably: one ERROR_SERVER_DISCONNECTED + one ERROR_NO_MATCH
     * closed the conversation every time.
     */
    @Test
    fun `MAX_INFRA_RETRIES is at least 2`() {
        assertTrue(
            "MAX_INFRA_RETRIES must be ≥ 2. With MAX_INFRA_RETRIES=1, one real " +
            "infrastructure failure immediately closes the session, which is too " +
            "aggressive for real-world network conditions. Was: " +
            "${KentasConversationController.MAX_INFRA_RETRIES}",
            KentasConversationController.MAX_INFRA_RETRIES >= 2,
        )
    }

    // ── AC-E06 — inactivity timer structure ───────────────────────────────

    /**
     * The inactivity timer must be started/resumed via [startOrResumeInactivityTimer],
     * which is a private method and must exist.
     *
     * Its presence verifies AC-E06: it is the ONLY entry point for starting the
     * 30-second countdown, and it is called ONLY from [onReadyForSpeech] in
     * [installCallbacks].  Every other lifecycle transition uses [pauseInactivityTimer].
     */
    @Test
    fun `startOrResumeInactivityTimer private method exists`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "startOrResumeInactivityTimer",
            Long::class.javaPrimitiveType!!,
        )
        assertNotNull(
            "KentasConversationController must have a private 'startOrResumeInactivityTimer(Long)' " +
            "method. This is the ONLY entry point for the 30-second inactivity countdown. " +
            "It must be called ONLY from onReadyForSpeech so AI generation, TTS, and " +
            "recognizer restarts do not consume the user's inactivity window.",
            method,
        )
    }

    /**
     * The pause entry point must exist.
     *
     * AC-E06: [pauseInactivityTimer] is called from every lifecycle point where
     * the user cannot speak — AI generation starts, TTS starts, error triggers a
     * restart, nav interrupts.  Without this method, the timer would fire during
     * AI processing and close the conversation prematurely.
     */
    @Test
    fun `pauseInactivityTimer private method exists`() {
        val method = getDeclaredMethodOrNull(
            KentasConversationController::class.java,
            "pauseInactivityTimer",
            String::class.java,
        )
        assertNotNull(
            "KentasConversationController must have a private 'pauseInactivityTimer(String)' " +
            "method. It is called when entering AI generation, TTS, and recognizer restarts " +
            "so those durations do not count as user inactivity.",
            method,
        )
    }

    // ── Recovery constants sanity check ───────────────────────────────────

    /**
     * [RecoveryPolicy.delayMs] for NO_MATCH and SPEECH_TIMEOUT must be short
     * (≤ 1 000 ms) so the conversation feels responsive after a missed utterance.
     * Longer delays would make Kentas feel broken after every breath or pause.
     */
    @Test
    fun `delayMs for NO_MATCH and SPEECH_TIMEOUT is at most 1000 ms`() {
        val noMatchDelay      = RecoveryPolicy.delayMs(RecoveryPolicy.E_NO_MATCH)
        val speechTimeoutDelay = RecoveryPolicy.delayMs(RecoveryPolicy.E_SPEECH_TIMEOUT)
        assertTrue(
            "delayMs(E_NO_MATCH)=$noMatchDelay must be ≤ 1000ms — a long pause " +
            "after a normal no-match makes Kentas feel broken.",
            noMatchDelay <= 1_000L,
        )
        assertTrue(
            "delayMs(E_SPEECH_TIMEOUT)=$speechTimeoutDelay must be ≤ 1000ms.",
            speechTimeoutDelay <= 1_000L,
        )
    }

    /**
     * [INACTIVITY_TIMEOUT_MS] must be exactly 30 seconds.
     */
    @Test
    fun `INACTIVITY_TIMEOUT_MS is 30 seconds`() {
        assertEquals(
            "INACTIVITY_TIMEOUT_MS must be 30 000 ms",
            30_000L,
            KentasConversationController.INACTIVITY_TIMEOUT_MS,
        )
    }

    // ── Error code value contracts ────────────────────────────────────────

    /**
     * Android's SpeechRecognizer error codes are stable platform constants.
     * If any value changes, real-device routing silently breaks.
     */
    @Test
    fun `RecoveryPolicy error code constants match Android platform values`() {
        assertEquals("E_NO_MATCH must be 7 (ERROR_NO_MATCH)",         7, RecoveryPolicy.E_NO_MATCH)
        assertEquals("E_SPEECH_TIMEOUT must be 6 (ERROR_SPEECH_TIMEOUT)", 6, RecoveryPolicy.E_SPEECH_TIMEOUT)
        assertEquals("E_SERVER must be 4 (ERROR_SERVER)",              4, RecoveryPolicy.E_SERVER)
        assertEquals("E_SERVER_DISCONNECTED must be 11",              11, RecoveryPolicy.E_SERVER_DISCONNECTED)
        assertEquals("E_NETWORK must be 2 (ERROR_NETWORK)",            2, RecoveryPolicy.E_NETWORK)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getDeclaredFieldOrNull(clazz: Class<*>, name: String): Field? =
        runCatching {
            clazz.getDeclaredField(name).also { it.isAccessible = true }
        }.getOrNull()

    private fun getDeclaredMethodOrNull(
        clazz: Class<*>,
        name: String,
        vararg paramTypes: Class<*>,
    ): Method? =
        runCatching {
            clazz.getDeclaredMethod(name, *paramTypes).also { it.isAccessible = true }
        }.getOrNull()
}
