package lt.sturmanas.bajeristas.voice

/**
 * Authoritative state machine for the continuous voice session loop.
 *
 * ## State transitions (happy path)
 *
 * ```
 * Idle
 *  → Starting          (toggleSession → startListening called)
 *  → ListeningReady    (onReadyForSpeech received — SR is active)
 *  → UserSpeaking      (onBeginningOfSpeech received)
 *  → Processing        (onResults / command parsing)
 *  → Speaking          (TTS speak called)
 *  → RestartDelay      (post-TTS / post-silent-command cooldown)
 *  → ListeningReady    (loop continues)
 * ```
 *
 * ## Error paths
 *
 * ```
 * ListeningReady / UserSpeaking / Processing
 *  → Recovering        (recoverable error — retry in progress)
 *  → ListeningReady    (retry succeeded) or → Error (max retries)
 * ```
 *
 * ## Key invariant
 *
 * The UI text "Kentas klauso…" MUST only be shown in [ListeningReady] — i.e., only
 * after [android.speech.RecognitionListener.onReadyForSpeech] fires, not after
 * [startListening] is called.
 *
 * [statusText] maps each state to a Lithuanian user-facing string for direct use in
 * the status label, so callers need no when-expression.
 */
sealed class VoiceSessionState {

    /** No session running — mic button shows idle. */
    object Idle : VoiceSessionState()

    /**
     * [startListening] was called; waiting for
     * [android.speech.RecognitionListener.onReadyForSpeech].
     * UI must NOT show "Kentas klauso…" in this state.
     */
    object Starting : VoiceSessionState()

    /**
     * [android.speech.RecognitionListener.onReadyForSpeech] received.
     * The recognizer is genuinely active and ready for speech.
     * This is the ONLY state where "Kentas klauso…" is appropriate.
     */
    object ListeningReady : VoiceSessionState()

    /**
     * [android.speech.RecognitionListener.onBeginningOfSpeech] received —
     * user is actively speaking.
     */
    object UserSpeaking : VoiceSessionState()

    /** Speech has ended; command is being parsed / AI call is in flight. */
    object Processing : VoiceSessionState()

    /** TTS is currently speaking Kentas's response. */
    object Speaking : VoiceSessionState()

    /** Brief pause between TTS completion (or silent-command completion) and the next listening window. */
    object RestartDelay : VoiceSessionState()

    /**
     * A recoverable error occurred; the recognizer is being destroyed and recreated.
     * Retry count has not been exhausted.
     */
    object Recovering : VoiceSessionState()

    /**
     * Session encountered an error.
     * [isFatal] true → session has been stopped permanently; false → max retries reached.
     */
    data class Error(val message: String, val isFatal: Boolean) : VoiceSessionState()

    // ── Backward-compat alias ─────────────────────────────────────────────

    /**
     * Alias kept for call sites that previously used [Listening].
     * New code should use [ListeningReady] (only valid after onReadyForSpeech)
     * or [Starting] (immediately after startListening is called).
     *
     * @deprecated Use [ListeningReady] or [Starting] instead.
     */
    @Deprecated("Use ListeningReady or Starting", ReplaceWith("ListeningReady"))
    object Listening : VoiceSessionState()

    /** Lithuanian status label shown below [MicButton]. */
    val statusText: String
        get() = when (this) {
            is Idle          -> "Kentas išjungtas"
            is Starting      -> "Įjungiamas klausymas…"
            is ListeningReady -> "Kentas klauso…"
            is UserSpeaking  -> "Klausau…"
            is Processing    -> "Atpažįstu…"
            is Speaking      -> "Kentas kalba…"
            is RestartDelay  -> "Laukiu komandos…"
            is Recovering    -> "Atkuriamas balso atpažinimas…"
            is Error         -> message
            @Suppress("DEPRECATION")
            is Listening     -> "Kentas klauso…"
        }
}
