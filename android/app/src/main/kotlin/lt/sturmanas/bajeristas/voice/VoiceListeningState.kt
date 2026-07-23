package lt.sturmanas.bajeristas.voice

/**
 * Drives the mic button visual state, the status-text overlay, and the
 * [MainViewModel.isSpeechBlocked] flag that suppresses maneuver TTS while
 * the microphone is active.
 *
 * ## State machine (continuous mode, happy path)
 *
 * ```
 * IDLE
 *  → STARTING       (startListening called; onStartRequested fired)
 *  → LISTENING      (onReadyForSpeech — recognizer genuinely active)
 *  → USER_SPEAKING  (onBeginningOfSpeech — user is speaking)
 *  → FINALIZING     (onEndOfSpeech — waiting for onResults)
 *  → PROCESSING     (utterance delivered to command parser)
 *  → THINKING       (AI/OpenAI call in flight)
 *  → SPEAKING       (TTS is playing Kentas's reply)
 *  → RESTART_WAIT   (post-TTS/command cooldown before next start)
 *  → STARTING       (loop back)
 * ```
 *
 * ## Key invariants
 *
 * - "Kentas klauso" MUST only be shown for [LISTENING] and [USER_SPEAKING].
 * - [MainViewModel.isSpeechBlocked] is true for [LISTENING], [USER_SPEAKING],
 *   and [FINALIZING] — maneuver TTS must not fire during any of these.
 * - [IDLE] is written ONLY when the session is explicitly stopped (user toggle,
 *   fatal error, permission loss). It must NEVER appear between normal
 *   continuous-mode phases.
 */
enum class VoiceListeningState {

    /** Mic is idle — no session running. Mic button shows idle (not red). */
    IDLE,

    /**
     * [android.speech.SpeechRecognizer.startListening] was called but
     * [android.speech.RecognitionListener.onReadyForSpeech] has NOT yet fired.
     * The microphone is NOT yet hot. UI shows "Kentas ruošiasi klausyti…".
     */
    STARTING,

    /**
     * [android.speech.RecognitionListener.onReadyForSpeech] received —
     * the recognizer is genuinely active and ready for speech.
     * This is the ONLY state where "Kentas klauso…" is appropriate.
     * Mic button is red and pulsing.
     */
    LISTENING,

    /**
     * [android.speech.RecognitionListener.onBeginningOfSpeech] received —
     * the user is actively speaking.
     * Mic button is red and pulsing (same appearance as [LISTENING]).
     * [MainViewModel.isSpeechBlocked] is true — maneuver TTS must not fire.
     */
    USER_SPEAKING,

    /**
     * [android.speech.RecognitionListener.onEndOfSpeech] received — the user
     * has stopped speaking but [android.speech.RecognitionListener.onResults]
     * has not yet arrived.
     *
     * The recognizer must NOT be restarted while in this state.
     * Mic button stays red/pulsing (no visible flicker).
     * [MainViewModel.isSpeechBlocked] is true — maneuver TTS must not fire.
     */
    FINALIZING,

    /**
     * Utterance delivered; voice command is being parsed / address is being
     * resolved. Mic button shows a spinner.
     */
    PROCESSING,

    /**
     * AI (OpenAI / Kentas personality) call is in flight.
     * Mic button shows a spinner.
     */
    THINKING,

    /**
     * TTS is currently playing Kentas's response.
     * Mic button shows the neutral primary colour (not red).
     */
    SPEAKING,

    /**
     * Brief pause between TTS completion (or a silent-command completion)
     * and the next [startListening] call.
     * UI shows "Kentas ruošiasi klausyti…" (honest — not "klauso").
     * Mic button is neutral primary colour (not red).
     *
     * This state must NEVER transition to [IDLE] during a normal continuous-mode loop.
     */
    RESTART_WAIT,

    /** Recognition or audio error; error message shown in voiceStatusText. */
    ERROR,
}
