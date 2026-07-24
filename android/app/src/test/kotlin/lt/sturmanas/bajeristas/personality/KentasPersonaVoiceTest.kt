package lt.sturmanas.bajeristas.personality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level regression tests for the Kentas personality prompt.
 *
 * ## Why source-level?
 * The prompt is a plain Kotlin string constant evaluated at construction time.
 * These tests verify the *content* of [KentasPersona.systemPrompt] — they are
 * cheap, deterministic, and catch accidental regressions without requiring an
 * LLM or Android runtime.
 *
 * ## What is tested
 *  1. No childish humour directives — prompt must not tell Kentas to joke or be cute.
 *  2. No forced-joke structure — no command to be witty on every reply.
 *  3. Direct disagreement — prompt must instruct Kentas to say things as they are.
 *  4. Mild colloquial language allowed — prompt must permit natural rough words.
 *  5. Criticise idea, not person — safety rule must be present verbatim.
 *  6. No fake street persona — prompt must forbid performing a character.
 *  7. No constant swearing — prompt must say colloquial words are rare, not stacked.
 *  8. Answer-first rule — direct answer before opinion before remark.
 *  9. No generic politeness — soft hedges must be explicitly forbidden.
 * 10. No assistant wording — forbidden phrases list must be present.
 */
class KentasPersonaVoiceTest {

    private val prompt: String = KentasPersona.systemPrompt

    // ── AC-V01 — no childish humour directive ────────────────────────────

    /**
     * The prompt must NOT tell Kentas to "šmaikštauk" (be witty/jokey) as a standing
     * instruction.  The old coreIdentity() had "Šmaikštauk subtiliai." — this drove
     * childish, formulaic humour on every reply.  Kentas's humour should emerge naturally,
     * not from a directive.
     */
    @Test
    fun `prompt does not contain standing joke directive`() {
        assertFalse(
            "The prompt must not contain 'Šmaikštauk' as a standing instruction. " +
            "That directive produced formulaic humour in every reply. " +
            "Humour should emerge naturally, not be commanded.",
            prompt.contains("Šmaikštauk", ignoreCase = true),
        )
    }

    // ── AC-V02 — no forced-joke structure ────────────────────────────────

    /**
     * The prompt must forbid forced jokes, cartoonish humour, and dad jokes.
     * The PERSONAŽO TAISYKLĖS section must explicitly call out these categories.
     */
    @Test
    fun `prompt explicitly forbids dad jokes and cartoonish humour`() {
        assertTrue(
            "The prompt must explicitly forbid dad jokes (tėčių anekdotų) and " +
            "cartoonish humour (animacinio humoro) so the model does not produce " +
            "childish punchlines.",
            prompt.contains("tėčių anekdotų", ignoreCase = true) &&
            prompt.contains("animacinio humoro", ignoreCase = true),
        )
    }

    // ── AC-V03 — direct disagreement ─────────────────────────────────────

    /**
     * The prompt must instruct Kentas to say things as they are, not to soften opinions
     * with hedging phrases.  The "sakyk kaip yra" (say it as it is) rule must be present.
     */
    @Test
    fun `prompt instructs Kentas to say things as they are`() {
        assertTrue(
            "The prompt must contain 'sakyk kaip yra' (or equivalent direct-disagreement " +
            "rule) so Kentas gives clear opinions instead of hedging with " +
            "'Galbūt vertėtų…' or 'Tai priklauso…'.",
            prompt.contains("sakyk kaip yra", ignoreCase = true),
        )
    }

    // ── AC-V04 — soft hedging phrases explicitly forbidden ───────────────

    /**
     * Weak hedges that make Kentas sound like a customer-support assistant must be
     * listed in the prompt as examples of what NOT to say.
     */
    @Test
    fun `prompt lists soft hedging phrases as forbidden`() {
        assertTrue(
            "The prompt must list 'Galbūt vertėtų' as a forbidden hedge so the model " +
            "avoids vague, non-committal answers.",
            prompt.contains("Galbūt vertėtų", ignoreCase = true),
        )
        assertTrue(
            "The prompt must list 'Abu variantai' as a forbidden hedge — " +
            "'Both options have merits' is assistant-speak, not Kentas.",
            prompt.contains("Abu variantai", ignoreCase = true),
        )
    }

    // ── AC-V05 — mild colloquial language explicitly allowed ─────────────

    /**
     * The coreIdentity must name specific mild colloquial words and explicitly permit
     * their occasional use.  This gives the model license to sound natural without
     * guessing what "rough" means.
     */
    @Test
    fun `prompt explicitly permits mild colloquial words`() {
        assertTrue(
            "The prompt must name 'grybą pjauni' as a permitted colloquial expression " +
            "so the model knows that mild rough language is allowed.",
            prompt.contains("grybą pjauni", ignoreCase = true),
        )
        assertTrue(
            "The prompt must permit 'cirkas' as a mild colloquial word.",
            prompt.contains("cirkas", ignoreCase = true),
        )
        assertTrue(
            "The prompt must permit 'nesąmonė' as a mild colloquial word.",
            prompt.contains("nesąmonė", ignoreCase = true),
        )
    }

    // ── AC-V06 — colloquial words must be rare, not stacked ──────────────

    /**
     * Permitting rough language without a restraint would produce constant swearing.
     * The prompt must explicitly say colloquial words are used rarely ("niekada krūvomis" —
     * never piled up).
     */
    @Test
    fun `prompt says colloquial words must not be stacked`() {
        assertTrue(
            "The prompt must say colloquial words are used 'niekada krūvomis' (never stacked). " +
            "Without this guard the model may start every reply with rough language.",
            prompt.contains("krūvomis", ignoreCase = true),
        )
    }

    // ── AC-V07 — criticise idea, not person ──────────────────────────────

    /**
     * The safety rule "Kritikuoji idėją, ne žmogų" (criticise the idea, not the person)
     * must be present verbatim.  This is the boundary between acceptable bluntness and
     * personal insult.
     */
    @Test
    fun `prompt contains the criticise-idea-not-person safety rule`() {
        assertTrue(
            "The prompt must contain 'Kritikuoji idėją, ne žmogų' verbatim. " +
            "This is the explicit boundary between Kentas being direct and being insulting.",
            prompt.contains("Kritikuoji idėją, ne žmogų", ignoreCase = true),
        )
    }

    // ── AC-V08 — no fake street persona ──────────────────────────────────

    /**
     * The prompt must forbid "performing" a character (nesuvaidink charakterio).
     * A forced "street" act — trying too hard to sound tough — is itself a form of
     * childish behaviour the spec explicitly prohibits.
     */
    @Test
    fun `prompt forbids performing a character`() {
        assertTrue(
            "The prompt must say 'Nesuvaidink charakterio' (don't perform a character). " +
            "This prevents the model from putting on a forced 'street tough' act.",
            prompt.contains("Nesuvaidink charakterio", ignoreCase = true),
        )
    }

    // ── AC-V09 — answer-first rule ────────────────────────────────────────

    /**
     * The prompt must make the answer-first structure explicit: direct answer, then
     * opinion, then remark — never a joke before the answer.
     */
    @Test
    fun `prompt mandates answer before opinion before remark`() {
        assertTrue(
            "The prompt must say 'Atsakymas pirmas' (answer first) so Kentas never " +
            "buries the actual reply behind a witty remark or long preamble.",
            prompt.contains("Atsakymas pirmas", ignoreCase = true),
        )
    }

    // ── AC-V10 — no assistant forbidden phrases list present ─────────────

    /**
     * The full list of forbidden AI-assistant phrases must remain in the prompt.
     * Spot-checks for the most egregious ones.
     */
    @Test
    fun `prompt retains the forbidden assistant-phrase list`() {
        assertTrue(
            "Forbidden phrase 'Žinoma!' must still be in the prompt.",
            prompt.contains("Žinoma!", ignoreCase = true),
        )
        assertTrue(
            "Forbidden phrase 'Puikus klausimas!' must still be in the prompt.",
            prompt.contains("Puikus klausimas!", ignoreCase = true),
        )
        assertTrue(
            "Forbidden phrase 'Atsiprašau.' must still be in the prompt.",
            prompt.contains("Atsiprašau.", ignoreCase = true),
        )
        assertTrue(
            "Forbidden phrase 'Džiaugiuosi galėdamas padėti.' must still be in the prompt.",
            prompt.contains("Džiaugiuosi galėdamas padėti.", ignoreCase = true),
        )
    }
}
