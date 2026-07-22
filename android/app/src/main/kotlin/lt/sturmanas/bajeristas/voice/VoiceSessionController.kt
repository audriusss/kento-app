package lt.sturmanas.bajeristas.voice

/**
 * Lightweight lifecycle coordinator for the continuous voice session loop.
 *
 * Holds a reference to the ViewModel's stop callback so that the Activity can
 * cleanly shut down the session on [onDestroy] without needing a direct ViewModel
 * reference in the teardown path.
 *
 * Phase 1 (current): delegates all real session control to [MainViewModel.toggleSession] /
 * the stop callback provided via [setStopCallback].
 *
 * Phase 3 (future): will additionally manage the OpenAI Realtime WebSocket:
 *   1. POST /api/realtime-session → receive { client_secret }.
 *   2. Open OkHttp WebSocket to wss://api.openai.com/v1/realtime.
 *   3. Send session.update with system prompt.
 *   4. Stream PCM via input_audio_buffer.append / commit.
 *   5. Receive response.audio.delta → AudioController.
 *   6. On SafetyController.shouldInterruptAudio() → response.cancel.
 *
 * The API key must never appear in this file or anywhere in the APK.
 */
class VoiceSessionController {

    /** True while a continuous voice session is running. */
    var isSessionActive: Boolean = false
        private set

    /** Callback set by [MainViewModel] to stop the session loop cleanly. */
    private var stopCallback: (() -> Unit)? = null

    /**
     * Register the ViewModel callback that stops the continuous session.
     * Called once during ViewModel initialization.
     */
    fun setStopCallback(callback: () -> Unit) {
        stopCallback = callback
    }

    /**
     * Stop the current session gracefully.
     * Delegates to the ViewModel stop callback if one is registered; otherwise no-op.
     * Safe to call when already stopped.
     */
    fun stopSession() {
        isSessionActive = false
        stopCallback?.invoke()
        // TODO Phase 3: send session.close, close OkHttp WebSocket
    }

    /**
     * Mark session as active. Called internally by [MainViewModel] when a session starts.
     * Not intended for external callers.
     */
    internal fun markActive() {
        isSessionActive = true
    }

    /**
     * Mark session as inactive. Called internally by [MainViewModel] when a session ends.
     */
    internal fun markInactive() {
        isSessionActive = false
    }

    // ── Phase 3 stubs ──────────────────────────────────────────────────────

    /**
     * Fetch an ephemeral token from the backend and open a Realtime session.
     * [systemPrompt] is sent as the session's initial instructions.
     *
     * Phase 1: no-op.
     * Phase 3: implement network + WebSocket logic here.
     */
    fun startSession(systemPrompt: String) {
        // TODO Phase 3: POST /api/realtime-session → ephemeral client_secret
        // TODO Phase 3: open OkHttp WebSocket, send session.update with systemPrompt
    }

    /**
     * Send recorded audio PCM to the Realtime API.
     * Call repeatedly while the mic button is held.
     *
     * Phase 1: no-op.
     */
    fun sendAudio(pcmData: ByteArray) {
        // TODO Phase 3: base64-encode, send input_audio_buffer.append event
    }

    /**
     * Commit the audio buffer and request an AI response.
     * Call when the mic button is released.
     *
     * Phase 1: no-op.
     */
    fun commitAndRespond() {
        // TODO Phase 3: send input_audio_buffer.commit + response.create
    }
}
