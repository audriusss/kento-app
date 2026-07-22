package lt.sturmanas.bajeristas.voice

/** Drives the mic button visual state and the status-text overlay. */
enum class VoiceListeningState {
    /** Mic is idle — ready to press. */
    IDLE,

    /** SpeechRecognizer is actively listening for speech. */
    LISTENING,

    /** Speech was received; command is being parsed / AI call is in flight. */
    PROCESSING,

    /** Recognition or audio error; message shown in voiceStatusText. */
    ERROR,
}
