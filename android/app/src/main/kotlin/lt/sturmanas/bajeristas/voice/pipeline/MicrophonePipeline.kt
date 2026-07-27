package lt.sturmanas.bajeristas.voice.pipeline

/**
 * Contract for the production microphone capture pipeline.
 *
 * Extracted as an interface so that [lt.sturmanas.bajeristas.voice.ai.AIConversationController]
 * can be tested with a [FakeMicrophonePipeline] without requiring Android hardware.
 *
 * ## Lifecycle contract
 * - [start] is idempotent: calling it when already running is a no-op.
 * - [stop] is idempotent: calling it when not running is a no-op.
 * - [AudioRecord] must remain alive while the voice system is enabled.
 *   Use [mute]/[unmute] for phase transitions; call [stop] only on teardown.
 *
 * ## Thread safety
 * All methods are safe to call from any thread.
 */
interface MicrophonePipeline {

    /**
     * Start continuous capture.  No-op if already running.
     *
     * [android.Manifest.permission.RECORD_AUDIO] must be granted before calling.
     */
    fun start()

    /**
     * Stop all capture and release hardware resources.
     * Safe to call when not running.
     */
    fun stop()

    /**
     * Mute the pipeline.  In-progress utterance buffers are discarded; audio
     * frames continue to be read from [android.media.AudioRecord] (so the
     * hardware resource stays warm) but are not sent to the STT backend.
     *
     * Call before AI or navigation TTS begins to prevent self-recognition.
     */
    fun mute()

    /**
     * Unmute after a previous [mute] call.
     *
     * The pre-roll buffer is cleared on unmute so TTS audio captured just
     * before the mute completes cannot contaminate the first utterance.
     */
    fun unmute()

    /**
     * Reset internal VAD LSTM state and segmenter state machine to SILENCE.
     *
     * Called at TTS boundaries (before and after) to prevent speaker echo
     * from influencing VAD probability scoring.  Safe to call while muted.
     */
    fun resetVadAndSegmenter()
}
