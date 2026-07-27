package lt.sturmanas.bajeristas.voice.coordination

import android.util.Log

/**
 * Coordinates priority between Navigation voice and AI conversation.
 * Navigation always wins and interrupts AI.
 */
class ConversationCoordinator {

    private var onPauseRequest: (() -> Unit)? = null
    private var onResumeRequest: (() -> Unit)? = null // Reserved for future use
    private var onStopListeningRequest: ((String) -> Unit)? = null
    private var onStartListeningRequest: ((String) -> Unit)? = null

    fun setTriggers(
        onPause: () -> Unit,
        onResume: () -> Unit,
        onStopListening: (String) -> Unit,
        onStartListening: (String) -> Unit
    ) {
        this.onPauseRequest = onPause
        this.onResumeRequest = onResume
        this.onStopListeningRequest = onStopListening
        this.onStartListeningRequest = onStartListening
    }

    fun onNavigationSpeechStarted(utteranceId: String) {
        Log.i("ConversationCoord", "NAV_VOICE_STARTED id=$utteranceId")
        onStopListeningRequest?.invoke(utteranceId)
        onPauseRequest?.invoke()
    }

    fun onNavigationSpeechFinished(utteranceId: String) {
        Log.i("ConversationCoord", "NAV_VOICE_FINISHED id=$utteranceId")
        onStartListeningRequest?.invoke(utteranceId)
    }

    fun startListening() {}
    fun startThinking()  {}
    fun startSpeaking()  {}
    fun setIdle()       {}
}
