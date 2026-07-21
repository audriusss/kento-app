package lt.sturmanas.bajeristas.personality

/**
 * Immutable snapshot of all user-selected personality configuration for one
 * navigation session.
 *
 * Created on the Start screen, held in-memory for the session, passed to
 * [PersonaPrompts] when building the system prompt (Phase 3).
 * Not persisted to disk — resets each time the app is launched.
 */
data class SessionConfig(
    val conversationMode: ConversationMode = ConversationMode.SOFT,
    val tripMode: TripMode = TripMode.SOLO,
    val humorIntensity: HumorIntensity = HumorIntensity.NORMAL,
    val humorFormat: HumorFormat = HumorFormat.SITUATIONAL,
)
