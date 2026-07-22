package lt.sturmanas.bajeristas.voice

/**
 * Sealed class representing every possible state of the continuous voice session loop.
 *
 * Used by [MainViewModel] to drive the [MicButton] visual and by the session
 * scheduler to decide whether to restart listening after TTS completes.
 *
 * [statusText] maps each state to a Lithuanian user-facing string so callers
 * can display it without a when-expression.
 */
sealed class VoiceSessionState {

    /** No session running — mic button shows idle. */
    object Idle : VoiceSessionState()

    /** Recognizer is active and waiting for speech. */
    object Listening : VoiceSessionState()

    /** Speech has ended; recognizer is processing. */
    object Processing : VoiceSessionState()

    /** TTS is currently speaking Kentas's response. */
    object Speaking : VoiceSessionState()

    /** Brief pause between TTS completion and the next listening window. */
    object RestartDelay : VoiceSessionState()

    /**
     * Session encountered an error.
     * [isFatal] true → session has been stopped; false → retry is in progress.
     */
    data class Error(val message: String, val isFatal: Boolean) : VoiceSessionState()

    /** Lithuanian status label shown below [MicButton]. */
    val statusText: String
        get() = when (this) {
            is Idle         -> "Kentas išjungtas"
            is Listening    -> "Klausau…"
            is Processing   -> "Atpažįstu…"
            is Speaking     -> "Kentas kalba…"
            is RestartDelay -> "Laukiu komandos…"
            is Error        -> message
        }
}
