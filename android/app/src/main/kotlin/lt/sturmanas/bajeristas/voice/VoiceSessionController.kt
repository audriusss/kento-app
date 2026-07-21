package lt.sturmanas.bajeristas.voice

/**
 * Manages push-to-talk voice sessions with the OpenAI Realtime API.
 *
 * Phase 1: stub only — no network calls, no audio.
 * Phase 3 implementation plan:
 *   1. POST /api/realtime-session to the Replit backend → receive { client_secret }.
 *   2. Open OkHttp WebSocket to wss://api.openai.com/v1/realtime
 *      with Authorization: Bearer <client_secret>.
 *   3. Send session.update with the system prompt.
 *   4. On mic press → capture PCM via AudioRecord.
 *   5. Stream base64-encoded chunks via input_audio_buffer.append.
 *   6. On mic release → send input_audio_buffer.commit + response.create.
 *   7. Receive response.audio.delta → pass PCM to AudioController.
 *   8. On SafetyController.shouldInterruptAudio() → send response.cancel.
 *
 * The API key must never appear in this file or anywhere in the APK.
 */
class VoiceSessionController {

    var isSessionActive: Boolean = false
        private set

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
        isSessionActive = false
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

    /**
     * Close the session gracefully, releasing the WebSocket and any resources.
     * Safe to call when already stopped.
     */
    fun stopSession() {
        isSessionActive = false
        // TODO Phase 3: send session.close, close OkHttp WebSocket
    }
}
