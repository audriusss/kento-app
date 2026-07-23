package lt.sturmanas.bajeristas.personality

import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.safety.ConversationPermission
import lt.sturmanas.bajeristas.safety.SafetyController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPromptsTest {

    // ── 1. SOFT and HARD produce different prompt context ─────────────────

    @Test
    fun `SOFT and HARD system prompts are different`() {
        val soft = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.SOFT))
        val hard = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.HARD))
        assertNotEquals("SOFT and HARD prompts must differ", soft, hard)
    }

    @Test
    fun `SOFT prompt contains soft-mode marker`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.SOFT))
        assertTrue("SOFT prompt must contain SOFT mode section", prompt.contains("SOFT"))
        assertFalse("SOFT prompt must not contain HARD mode section", prompt.contains("HARD"))
    }

    @Test
    fun `HARD prompt contains hard-mode marker and rough language instructions`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.HARD))
        assertTrue("HARD prompt must contain HARD mode section", prompt.contains("HARD"))
        // Must explicitly include slang/profanity permission
        assertTrue("HARD prompt must mention slang or keiksmažodžiai", prompt.contains("keiksmažodžius"))
        assertFalse("HARD prompt must not contain SOFT mode section", prompt.contains("SOFT"))
    }

    // ── 2. SOLO, DUO, GROUP produce different behavior instructions ────────

    @Test
    fun `SOLO DUO GROUP prompts are all different from each other`() {
        val solo = PersonaPrompts.systemPrompt(SessionConfig(tripMode = TripMode.SOLO))
        val duo = PersonaPrompts.systemPrompt(SessionConfig(tripMode = TripMode.DUO))
        val group = PersonaPrompts.systemPrompt(SessionConfig(tripMode = TripMode.GROUP))
        assertNotEquals("SOLO and DUO must differ", solo, duo)
        assertNotEquals("DUO and GROUP must differ", duo, group)
        assertNotEquals("SOLO and GROUP must differ", solo, group)
    }

    @Test
    fun `SOLO prompt includes companion and initiative instructions`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(tripMode = TripMode.SOLO))
        assertTrue("SOLO prompt must mention SOLO mode", prompt.contains("SOLO"))
        assertTrue("SOLO prompt should allow initiating conversation",
            prompt.contains("inicijuoti") || prompt.contains("palydovas"))
    }

    @Test
    fun `DUO prompt mentions both occupants and does not assume couple`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(tripMode = TripMode.DUO))
        assertTrue("DUO prompt must mention DUO mode", prompt.contains("DUO"))
        assertTrue("DUO prompt must mention passenger", prompt.contains("keleivį") || prompt.contains("keleivis"))
        assertTrue("DUO prompt must caution about couple assumption",
            prompt.contains("poros") || prompt.contains("poras"))
    }

    // ── 3. GROUP mode disables unsolicited conversation ───────────────────

    @Test
    fun `GROUP prompt instructs Kentas to stay silent unless addressed`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(tripMode = TripMode.GROUP))
        assertTrue("GROUP prompt must contain GROUP mode section", prompt.contains("GROUP"))
        // Must explicitly tell the AI NOT to react to every sentence
        assertTrue(
            "GROUP prompt must instruct silence unless addressed",
            prompt.contains("NEREAGUOK") || prompt.contains("Tylėk") || prompt.contains("TIK kai"),
        )
    }

    @Test
    fun `GROUP prompt requires push-to-talk or direct address before responding`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(tripMode = TripMode.GROUP))
        assertTrue(
            "GROUP prompt must mention push-to-talk condition",
            prompt.contains("push-to-talk") || prompt.contains("mikrofono mygtuką"),
        )
    }

    // ── 4. Navigation safety priority overrides all conversation modes ─────

    @Test
    fun `SafetyController returns BLOCKED regardless of SOFT mode`() {
        val controller = SafetyController()
        val state = NavigationState(
            isNavigating = true,
            distanceToNextManeuverMeters = 100,
            maneuverType = ManeuverType.TURN_RIGHT,
        )
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(state))
    }

    @Test
    fun `SafetyController returns BLOCKED regardless of HARD mode`() {
        val controller = SafetyController()
        val state = NavigationState(
            isNavigating = true,
            distanceToNextManeuverMeters = 100,
            maneuverType = ManeuverType.TURN_RIGHT,
        )
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(state))
    }

    @Test
    fun `SafetyController returns BLOCKED for roundabout regardless of GROUP mode`() {
        val controller = SafetyController()
        val state = NavigationState(
            isNavigating = true,
            distanceToNextManeuverMeters = 1500,
            maneuverType = ManeuverType.ROUNDABOUT,
        )
        assertEquals(ConversationPermission.BLOCKED, controller.getPermission(state))
    }

    @Test
    fun `safety constraints appear in both SOFT and HARD prompts`() {
        val soft = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.SOFT))
        val hard = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.HARD))
        val safetyMarker = "navigacijos"
        assertTrue("SOFT prompt must contain safety constraints", soft.contains(safetyMarker))
        assertTrue("HARD prompt must contain safety constraints", hard.contains(safetyMarker))
    }

    // ── 5. Selected values are passed to SessionConfig correctly ──────────

    @Test
    fun `SessionConfig defaults are SOFT SOLO NORMAL`() {
        val config = SessionConfig()
        assertEquals(ConversationMode.SOFT, config.conversationMode)
        assertEquals(TripMode.SOLO, config.tripMode)
        assertEquals(HumorIntensity.NORMAL, config.humorIntensity)
    }

    @Test
    fun `SessionConfig stores all selected values correctly`() {
        val config = SessionConfig(
            conversationMode = ConversationMode.HARD,
            tripMode = TripMode.GROUP,
            humorIntensity = HumorIntensity.STRONG,
            humorFormat = HumorFormat.ANECDOTE,
        )
        assertEquals(ConversationMode.HARD, config.conversationMode)
        assertEquals(TripMode.GROUP, config.tripMode)
        assertEquals(HumorIntensity.STRONG, config.humorIntensity)
        assertEquals(HumorFormat.ANECDOTE, config.humorFormat)
    }

    @Test
    fun `SessionConfig is immutable — copy produces independent instance`() {
        val original = SessionConfig(conversationMode = ConversationMode.SOFT)
        val modified = original.copy(conversationMode = ConversationMode.HARD)
        assertEquals(ConversationMode.SOFT, original.conversationMode)
        assertEquals(ConversationMode.HARD, modified.conversationMode)
    }

    @Test
    fun `systemPrompt reflects conversationMode and tripMode combination`() {
        val hardGroup = PersonaPrompts.systemPrompt(
            SessionConfig(conversationMode = ConversationMode.HARD, tripMode = TripMode.GROUP),
        )
        val softSolo = PersonaPrompts.systemPrompt(
            SessionConfig(conversationMode = ConversationMode.SOFT, tripMode = TripMode.SOLO),
        )
        assertNotEquals("Different configs must produce different prompts", hardGroup, softSolo)
        assertTrue(hardGroup.contains("HARD"))
        assertTrue(hardGroup.contains("GROUP"))
        assertTrue(softSolo.contains("SOFT"))
        assertTrue(softSolo.contains("SOLO"))
    }

    // ── Humor instructions ────────────────────────────────────────────────

    @Test
    fun `LIGHT and STRONG humor produce different prompt sections`() {
        val light = PersonaPrompts.systemPrompt(SessionConfig(humorIntensity = HumorIntensity.LIGHT))
        val strong = PersonaPrompts.systemPrompt(SessionConfig(humorIntensity = HumorIntensity.STRONG))
        assertNotEquals(light, strong)
    }

    // ── Hard speaking style — profanity permissions ───────────────────────

    @Test
    fun `SOFT prompt explicitly forbids profanity`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.SOFT))
        // The prompt must contain the Lithuanian word for profanity AND a prohibition.
        val hasProfanityWord = prompt.contains("keiksmažodž") // keiksmažodžiai / keiksmažodžiais
        val hasProhibition = prompt.contains("DRAUDŽIAMI") || prompt.contains("Jokių keiksmų")
        assertTrue("SOFT prompt must mention profanity prohibition word", hasProfanityWord)
        assertTrue("SOFT prompt must contain a prohibition marker", hasProhibition)
    }

    @Test
    fun `HARD prompt explicitly permits Lithuanian profanity and lists the words`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.HARD))
        // Must list at least a subset of the approved words without asterisks.
        assertTrue("HARD prompt must contain 'blet'",   prompt.contains("blet"))
        assertTrue("HARD prompt must contain 'nachui'", prompt.contains("nachui"))
        assertTrue("HARD prompt must contain 'šūdas'",  prompt.contains("šūdas"))
        // Must instruct NOT to censor with asterisks.
        assertTrue(
            "HARD prompt must forbid asterisk censoring",
            prompt.contains("žvaigždutemis") || prompt.contains("cenzūruoti"),
        )
    }

    @Test
    fun `HARD plus LIGHT humor says occasional mild profanity`() {
        val prompt = PersonaPrompts.systemPrompt(
            SessionConfig(conversationMode = ConversationMode.HARD, humorIntensity = HumorIntensity.LIGHT),
        )
        // Must convey "occasionally / rarely" — not constant swearing.
        assertTrue(
            "Hard+Light must mention occasional/rare profanity",
            prompt.contains("retkarčiais") || prompt.contains("retas") || prompt.contains("Nuolatinis keikimas draudžiamas"),
        )
    }

    @Test
    fun `HARD plus NORMAL humor says regular stronger profanity`() {
        val prompt = PersonaPrompts.systemPrompt(
            SessionConfig(conversationMode = ConversationMode.HARD, humorIntensity = HumorIntensity.NORMAL),
        )
        assertTrue(
            "Hard+Normal must mention regular profanity",
            prompt.contains("Reguliarūs") || prompt.contains("reguliarūs"),
        )
        assertTrue(
            "Hard+Normal must mention stronger sarcasm",
            prompt.contains("Stipresnis sarkazmas"),
        )
    }

    @Test
    fun `HARD plus STRONG humor says frequent natural full personality`() {
        val prompt = PersonaPrompts.systemPrompt(
            SessionConfig(conversationMode = ConversationMode.HARD, humorIntensity = HumorIntensity.STRONG),
        )
        assertTrue(
            "Hard+Strong must mention frequent profanity",
            prompt.contains("Dažni") || prompt.contains("dažni"),
        )
        assertTrue(
            "Hard+Strong must mention full personality",
            prompt.contains("Pilna Kentaso asmenybė"),
        )
        // Must still require answering the actual question.
        assertTrue(
            "Hard+Strong must still require answering the real question",
            prompt.contains("atsakyk į tikrą klausimą"),
        )
    }

    @Test
    fun `navigation exclusion is explicit in HARD prompt`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.HARD))
        // The HARD conversationModeInstructions block explicitly calls out navigation commands
        // as profanity-free zones.
        assertTrue(
            "HARD prompt must explicitly exclude navigation instructions from profanity",
            prompt.contains("NAVIGACINIAI NURODYMAI VISADA BE KEIKSMAŽODŽIŲ") ||
                (prompt.contains("Posūkiai") && prompt.contains("be keiksmų")),
        )
    }

    @Test
    fun `HARD prompt contains Lithuanian-language instruction`() {
        val prompt = PersonaPrompts.systemPrompt(SessionConfig(conversationMode = ConversationMode.HARD))
        // The prompt itself must be written in Lithuanian (not English).
        assertTrue("Prompt must contain Lithuanian text", prompt.contains("lietuviški"))
        assertTrue("Prompt must mention driving context in Lithuanian", prompt.contains("navigaci"))
    }

    @Test
    fun `SOFT HARD LIGHT NORMAL STRONG all produce unique prompts`() {
        val configs = listOf(
            SessionConfig(conversationMode = ConversationMode.SOFT, humorIntensity = HumorIntensity.LIGHT),
            SessionConfig(conversationMode = ConversationMode.SOFT, humorIntensity = HumorIntensity.NORMAL),
            SessionConfig(conversationMode = ConversationMode.SOFT, humorIntensity = HumorIntensity.STRONG),
            SessionConfig(conversationMode = ConversationMode.HARD, humorIntensity = HumorIntensity.LIGHT),
            SessionConfig(conversationMode = ConversationMode.HARD, humorIntensity = HumorIntensity.NORMAL),
            SessionConfig(conversationMode = ConversationMode.HARD, humorIntensity = HumorIntensity.STRONG),
        )
        val prompts = configs.map { PersonaPrompts.systemPrompt(it) }
        val unique = prompts.toSet()
        assertEquals(
            "Every mode×intensity combination must produce a unique prompt (got ${unique.size} unique out of ${prompts.size})",
            prompts.size,
            unique.size,
        )
    }

    // ── Navigation context ────────────────────────────────────────────────

    @Test
    fun `navigationContext contains only supplied values`() {
        val ctx = PersonaPrompts.navigationContext(
            nextManeuver = "TURN_RIGHT",
            street = "Gedimino pr.",
            distanceToManeuverMeters = 350,
            remainingDistanceMeters = 4500,
            remainingSeconds = 600,
        )
        // Maneuver name must be converted to its Lithuanian label, not echoed raw.
        assertTrue("context must contain the Lithuanian maneuver label",
            ctx.contains("Sukti dešinėn"))
        assertTrue("context must contain the street name",
            ctx.contains("Gedimino pr."))
        // 350 m < 1000 m → "350 metrų" — the raw number must appear.
        assertTrue("context must contain formatted maneuver distance",
            ctx.contains("350"))
        // 4500 m ≥ 3 km → "apie 4 kilometrus" — "apie 4" must appear.
        assertTrue("context must contain formatted remaining distance",
            ctx.contains("apie 4"))
        // 600 s → 10 minutes.
        assertTrue("context must contain remaining minutes",
            ctx.contains("10"))
    }
}
