package lt.sturmanas.bajeristas.voice.ai

/**
 * Conversation state types and the routing decision for post-TTS phase transitions.
 *
 * Extracted from [AIConversationController] into a standalone file so the routing
 * invariants can be tested in a pure JVM environment without Android framework deps.
 */

enum class ConversationMode { IDLE, ACTIVE, MUTED }

enum class Phase {
    IDLE,
    LISTENING,
    COLLECTING,
    WAITING_FOR_CONTINUATION,
    THINKING,
    SPEAKING,
    PAUSED_BY_NAVIGATION,
    MUTED,
}

/**
 * Phase to enter after AI TTS (and its [lt.sturmanas.bajeristas.voice.pipeline.PipelineConfig.POST_TTS_COOLDOWN_MS]
 * cooldown) completes.
 *
 * Key invariant: when [mode] is [ConversationMode.MUTED], the controller must
 * return to [Phase.MUTED] — never to [Phase.IDLE] — so the mode-level mute guard
 * in processPacket() remains active.
 *
 * This is a pure function with no Android dependencies; it is tested directly in
 * `VoicePipelineIntegrationTest`.
 */
internal fun postTtsTargetPhase(mode: ConversationMode): Phase = when (mode) {
    ConversationMode.ACTIVE -> Phase.LISTENING
    ConversationMode.MUTED  -> Phase.MUTED
    ConversationMode.IDLE   -> Phase.IDLE
}

/** Whether a phase transition should mute or unmute the microphone pipeline. */
enum class PipelineAction { MUTE, UNMUTE }

/**
 * Maps each [Phase] to the appropriate [PipelineAction] for the microphone pipeline.
 *
 * Key invariants:
 * - [Phase.MUTED]: pipeline must stay **UNMUTED** so the driver can speak an unmute
 *   command and have it delivered as a transcript.  The mode-level guard in
 *   `processPacket()` (reject all non-unmute text when mode==MUTED) is the actual
 *   mute enforcement; the hardware mic does NOT stop recording.
 * - [Phase.SPEAKING] / [Phase.THINKING] / [Phase.PAUSED_BY_NAVIGATION]: pipeline
 *   muted to prevent self-recognition of TTS audio.
 *
 * This is a pure function with no Android dependencies; it is tested directly in
 * `VoicePipelineIntegrationTest`.
 */
internal fun pipelineActionForPhase(phase: Phase): PipelineAction = when (phase) {
    Phase.IDLE,
    Phase.LISTENING,
    Phase.COLLECTING,
    Phase.WAITING_FOR_CONTINUATION,
    Phase.MUTED,                // Keep mic open — unmute commands must still arrive.
    -> PipelineAction.UNMUTE

    Phase.THINKING,
    Phase.SPEAKING,
    Phase.PAUSED_BY_NAVIGATION,
    -> PipelineAction.MUTE
}

/**
 * Returns true when a new transcript must be dropped because a user turn is already active.
 *
 * [Phase.THINKING] means an AI HTTP request is in flight.
 * [Phase.SPEAKING] means Kentas TTS is playing.
 *
 * In both phases the pipeline is hardware-muted by [pipelineActionForPhase], so any
 * transcript that arrives here was captured just before the mute took effect.  Dropping
 * it prevents a second AI request from being triggered by a residual in-flight delivery.
 *
 * This is a pure function with no Android dependencies; it is tested directly in
 * `TurnGateTest`.
 */
internal fun isTurnBlocked(phase: Phase): Boolean =
    phase == Phase.THINKING || phase == Phase.SPEAKING

/**
 * Returns true when an incoming transcript must contain a wake-word before it is
 * forwarded to the AI.  Only applies when [ConversationMode] is [ConversationMode.IDLE]
 * (no active conversation window).
 *
 * Immediate commands (mute/unmute/stop) are handled in processPacket() before this
 * gate is consulted and are always allowed regardless of mode.
 *
 * This is a pure function with no Android dependencies; it is tested directly in
 * `TurnGateTest`.
 */
internal fun requiresWakeWord(mode: ConversationMode): Boolean =
    mode == ConversationMode.IDLE
