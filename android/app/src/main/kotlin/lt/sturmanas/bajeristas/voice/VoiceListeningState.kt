package lt.sturmanas.bajeristas.voice

/**
 * Visual state for [MicButton] — mapped from [ConversationState] in [MainViewModel].
 *
 * Simplified to match the new push-to-talk conversation model:
 *   - No STARTING / FINALIZING / PROCESSING / RESTART_WAIT intermediate states.
 *   - No continuous-mode session ring (replaced by [KentasConversationController.isActive]).
 *
 * ## Mapping from [ConversationState]
 * | ConversationState  | VoiceListeningState |
 * |--------------------|---------------------|
 * | IDLE               | IDLE                |
 * | LISTENING          | LISTENING           |
 * | USER_SPEAKING      | USER_SPEAKING       |
 * | THINKING           | THINKING            |
 * | SPEAKING           | SPEAKING            |
 *
 * [isSpeechBlocked] is true for LISTENING and USER_SPEAKING — maneuver TTS must
 * not fire while the microphone is active.
 */
enum class VoiceListeningState {
    /** No conversation active — mic button is idle. */
    IDLE,

    /**
     * Microphone is hot and waiting for user speech.
     * Button is red and pulsing. "Kentas klauso…" may be shown.
     * [isSpeechBlocked] = true.
     */
    LISTENING,

    /**
     * User is actively speaking.
     * Button stays red and pulsing.
     * [isSpeechBlocked] = true.
     */
    USER_SPEAKING,

    /**
     * AI call is in flight.
     * Button shows a spinner.
     */
    THINKING,

    /**
     * TTS is playing Kentas's response.
     * Button shows mic icon in primary colour.
     */
    SPEAKING,
}

/**
 * True while the microphone is active and maneuver TTS must not fire.
 * Navigation instructions that fire while this is true will be skipped.
 */
val VoiceListeningState.isSpeechBlocked: Boolean
    get() = this == VoiceListeningState.LISTENING || this == VoiceListeningState.USER_SPEAKING
