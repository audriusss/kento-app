package lt.sturmanas.bajeristas.voice

/** Drives the mic button visual state and the status-text overlay. */
enum class VoiceListeningState {
    /** Mic is idle — ready to press. */
    IDLE,

    /** SpeechRecognizer is actively listening for speech. */
    LISTENING,

    /**
     * [android.speech.RecognitionListener.onEndOfSpeech] received — the user has stopped
     * speaking but [android.speech.RecognitionListener.onResults] has not yet arrived.
     *
     * The recognizer must NOT be restarted while in this state. The UI shows the same
     * red/active appearance as [LISTENING] so there is no visible flicker between the
     * moment the user stops speaking and the moment the transcript is confirmed.
     */
    FINALIZING,

    /** Speech was received; command is being parsed / AI call is in flight. */
    PROCESSING,

    /** Recognition or audio error; message shown in voiceStatusText. */
    ERROR,
}
