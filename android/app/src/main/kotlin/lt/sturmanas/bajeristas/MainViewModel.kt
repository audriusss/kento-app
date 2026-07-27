package lt.sturmanas.bajeristas

import android.app.Application
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import lt.sturmanas.bajeristas.navigation.LocationProvider
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.voice.ai.AIConversationController
import lt.sturmanas.bajeristas.voice.coordination.ConversationCoordinator
import lt.sturmanas.bajeristas.voice.navigation.NavigationVoiceController
import lt.sturmanas.bajeristas.voice.ai.KentasChat
import lt.sturmanas.bajeristas.voice.pipeline.OpenAiTranscriptionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Basic navigation ViewModel.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "KentasVM"
    }

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = LocationProvider.locationFlow

    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    private val _engineError = MutableStateFlow<String?>(null)
    val engineError: StateFlow<String?> = _engineError.asStateFlow()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val _aiStatus = MutableStateFlow("IDLE")
    val aiStatus: StateFlow<String> = _aiStatus.asStateFlow()

    private val voiceController = NavigationVoiceController(application)
    val conversationCoordinator = ConversationCoordinator()
    private var aiController: AIConversationController? = null

    init {
        retryLocationUpdates()
        Log.d(TAG, "MainViewModel initialised")
    }

    fun initAI(context: Context) {
        Log.d(TAG, "initAI invoked: aiController is null? ${aiController == null}")
        if (aiController != null) return
        Log.i(TAG, "AI_INITIALIZING")
        // Route all AI calls through the backend proxy — the API key never lives in the APK.
        KentasChat.init(BuildConfig.BACKEND_URL)
        val sessionId = java.util.UUID.randomUUID().toString()
        val transcriptionClient = OpenAiTranscriptionClient(
            backendUrl = BuildConfig.BACKEND_URL,
            sessionId  = sessionId,
        )
        val controller = AIConversationController(
            context = context,
            getNextManeuverDist = { voiceController.getLatestDistance() },
            onStateChanged = { status: String ->
                _aiStatus.value = status
                when (status) {
                    "Klausau..."       -> conversationCoordinator.startListening()
                    "Kentas galvoja..." -> {}
                    "Kentas kalba..."  -> {}
                    else               -> {}
                }
            },
            transcriptionClient = transcriptionClient,
        )
        aiController = controller
        
        conversationCoordinator.setTriggers(
            onPause = { controller.stop() },
            onResume = { /* Engine handles resume */ },
            onStopListening = { id -> controller.onNavigationStarted(id) },
            onStartListening = { id -> controller.onNavigationFinished(id) }
        )
        
        voiceController.listener = object : NavigationVoiceController.NavigationSpeechListener {
            override fun onNavigationSpeechStarted(utteranceId: String) {
                handler.post {
                    aiController?.resetIdleTimer()
                    conversationCoordinator.onNavigationSpeechStarted(utteranceId)
                }
            }
            override fun onNavigationSpeechFinished(utteranceId: String) {
                handler.post {
                    conversationCoordinator.onNavigationSpeechFinished(utteranceId)
                }
            }
        }
    }

    /**
     * Entry point for the navigation controller to be injected.
     * Starts observing navigation state for voice triggers.
     */
    fun startObserving(navigationController: NavigationController) {
        Log.i(TAG, "NAV_VOICE_OBSERVER_ATTACHED")
        viewModelScope.launch {
            navigationController.state.collect { state ->
                onNavigationStateChanged(state)
                
                if (state.isNavigating) {
                    aiController?.startListening()
                }
            }
        }
    }

    fun onNavigationStateChanged(state: NavigationState) {
        voiceController.speak(state)
    }

    fun stopNavigationVoice() {
        voiceController.stop()
    }

    fun setEngineReady(ready: Boolean) {
        _engineReady.value = ready
    }

    fun setEngineError(error: String?) {
        _engineError.value = error
    }

    fun retryLocationUpdates() {
        try {
            LocationProvider.startUpdates(getApplication())
            Log.d(TAG, "Location updates started")
        } catch (e: Exception) {
            Log.w(TAG, "retryLocationUpdates failed: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceController.release()
        aiController?.release()
        LocationProvider.stopUpdates(getApplication())
    }
}
