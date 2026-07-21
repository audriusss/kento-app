package lt.sturmanas.bajeristas.voice

/**
 * Manages audio playback priorities between AI responses and navigation audio.
 *
 * Invariant: navigation audio always wins. When navigation speaks,
 * AI audio is interrupted immediately and not resumed until
 * SafetyController grants permission again.
 *
 * Phase 1: stub only — no real audio playback.
 * Phase 3 implementation plan:
 *   - Request AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK for AI audio.
 *   - Play decoded PCM via AudioTrack (direct) or MediaPlayer (file-backed).
 *   - On interruptAiAudio(): stop playback, abandon audio focus.
 *   - Listen for AudioManager.OnAudioFocusChangeListener to detect
 *     when navigation TTS takes focus and pause automatically.
 */
class AudioController {

    var isAiPlaying: Boolean = false
        private set

    /**
     * Begin playback of an AI audio response.
     * Must not be called when SafetyController returns BLOCKED.
     *
     * Phase 1: no-op.
     */
    fun playAiResponse(audioData: ByteArray) {
        // TODO Phase 3: decode PCM, request audio focus, play via AudioTrack
        isAiPlaying = true
    }

    /**
     * Immediately stop any playing AI audio.
     *
     * Called by the UI layer whenever SafetyController.shouldInterruptAudio()
     * returns true. Must return quickly — it may be called from a coroutine
     * that is already on the main thread.
     *
     * Phase 1: no-op.
     */
    fun interruptAiAudio() {
        isAiPlaying = false
        // TODO Phase 3: stop AudioTrack/MediaPlayer, abandon audio focus
    }

    /**
     * Release all resources. Call from Activity.onDestroy().
     */
    fun release() {
        interruptAiAudio()
        // TODO Phase 3: release AudioTrack/MediaPlayer instances
    }
}
