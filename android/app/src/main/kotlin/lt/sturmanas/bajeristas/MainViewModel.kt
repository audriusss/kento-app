package lt.sturmanas.bajeristas

import android.app.Application
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import lt.sturmanas.bajeristas.navigation.LocationProvider
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.voice.ai.AIConversationController
import lt.sturmanas.bajeristas.voice.coordination.ConversationCoordinator
import lt.sturmanas.bajeristas.voice.navigation.NavigationVoiceController
import lt.sturmanas.bajeristas.voice.navigation.TrafficEventMonitor
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

    /** True while StartScreen destination STT is active. Observed by [SturmanasApp]. */
    private val _isDestinationListening = MutableStateFlow(false)
    val isDestinationListening: StateFlow<Boolean> = _isDestinationListening.asStateFlow()

    /**
     * Set to true by [performFullNavigationCleanup] after arrival so [SturmanasApp]
     * can set isNavigationScreenVisible = false on the next composition.
     * Reset by [consumeExitToStartScreen] immediately after the UI reacts.
     */
    private val _exitToStartScreen = MutableStateFlow(false)
    val exitToStartScreen: StateFlow<Boolean> = _exitToStartScreen.asStateFlow()

    /** Reference stored in [startObserving] so arrival cleanup can call stopNavigation. */
    private var navControllerRef: NavigationController? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val _aiStatus = MutableStateFlow("IDLE")
    val aiStatus: StateFlow<String> = _aiStatus.asStateFlow()

    private val voiceController = NavigationVoiceController(application)

    /**
     * Monitors [NavigationState] for significant ETA worsening and rerouting events.
     * Routed through [NavigationVoiceController.speakTrafficComment] so maneuver guidance
     * retains priority and the AI pause/resume mechanism is not bypassed.
     */
    private val trafficMonitor = TrafficEventMonitor { text ->
        voiceController.speakTrafficComment(text)
    }

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
        
        // After the arrival phrase ("Nu va, privažiavom.") finishes, run the same
        // full cleanup pipeline used by the manual "Baigti navigaciją" button.
        voiceController.onArrivalSpeechCompleted = {
            Log.i(TAG, "ARRIVAL_SPEECH_COMPLETED")
            Log.i(TAG, "ARRIVAL_CLEANUP_POSTPONED")
            // After arrival speech finishes, we stop Kentas monologues but stay on the map screen.
            // Full navigation cleanup (including exit to StartScreen) is now triggered
            // ONLY when the user manually taps "Baigti navigaciją" in the UI.
            handler.post { aiController?.stop() }
        }

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

    /** Resets the one-shot exit flag after [SturmanasApp] has acted on it. */
    fun consumeExitToStartScreen() {
        _exitToStartScreen.value = false
    }

    /**
     * Runs the full cleanup pipeline shared by the manual "Baigti navigaciją" button
     * and automatic arrival cleanup: stops navigation, nav voice, AI speech, and
     * signals [SturmanasApp] to return to StartScreen.
     */
    private fun performFullNavigationCleanup() {
        Log.i(TAG, "NAVIGATION_FULL_CLEANUP_STARTED")
        navControllerRef?.stopNavigation()
        stopNavigationVoice()
        stopKentasSpeech()
        // Stop the MicrophonePipeline so no VAD or STT activity continues on StartScreen.
        // Must come after stopKentasSpeech() (which cuts TTS) so the pipeline is not
        // stopped while TTS is still in mid-utterance.
        aiController?.stopNavigationMicPipeline()
        _exitToStartScreen.value = true
        Log.i(TAG, "NAVIGATION_FULL_CLEANUP_COMPLETED")
    }

    fun startObserving(navigationController: NavigationController) {
        navControllerRef = navigationController
        Log.i(TAG, "NAV_VOICE_OBSERVER_ATTACHED")

        // Wire voice-destination navigation: Kentas can now start navigation by voice.
        // The callback receives a pre-resolved "lat,lng" string from PlacesAutocompleteClient
        // and forwards it to the existing startNavigation coordinate branch — no engine change.
        val appContext = getApplication<Application>()
        aiController?.onNavigateToDestination = { destination ->
            Log.i(TAG, "VOICE_NAV_START dest='$destination'")
            navigationController.startNavigation(appContext, destination) { error ->
                Log.e(TAG, "VOICE_NAV_ERROR: $error")
                // Speak the error on the main thread — speak() must not be called off-thread.
                handler.post { aiController?.speak("Nepavyko rasti maršruto.") }
            }
        }

        // Wire active-route query so AIConversationController can distinguish
        // "cancel pending choices" from "cancel an active route".
        aiController?.isNavigationActive = {
            navigationController.state.value.phase != NavigationPhase.IDLE
        }

        // Wire full NavigationState for compact nav-context prompt building.
        aiController?.getNavState = { navigationController.state.value }

        // Wire voice route cancellation: stops guidance and voice; the UI returns to
        // StartScreen automatically via the LaunchedEffect in MainActivity that resets
        // isNavigating when the engine phase returns to IDLE.
        aiController?.onStopNavigation = {
            Log.i(TAG, "VOICE_NAV_ROUTE_STOPPED")
            navigationController.stopNavigation()
            handler.post { stopNavigationVoice() }
        }

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
        trafficMonitor.onStateUpdate(state)
    }

    fun stopNavigationVoice() {
        voiceController.stop()
        trafficMonitor.onNavigationStopped()
    }

    /**
     * Stops any in-flight Kentas TTS immediately and clears the conversation session.
     *
     * Called on both manual route stop and arrival cleanup so the next trip starts
     * with a clean slate.  Calls [AIConversationController.stop] directly (no
     * handler.post) so TTS is cut synchronously before any screen transition.
     *
     * Delegates to [AIConversationController.stop] which:
     *  1. Calls tts.stop() to cut playback mid-sentence.
     *  2. Cancels the TTS watchdog.
     *  3. Clears any interrupted-response state.
     */
    fun stopKentasSpeech() {
        Log.i(TAG, "MANUAL_NAV_STOP_SPEECH_STOPPED")
        aiController?.stop()
        // Ensure the microphone pipeline is stopped so Kentas does not listen on StartScreen.
        aiController?.stopNavigationMicPipeline()
        KentasChat.clearMemory()
    }

    /**
     * Speaks the reroute confirmation phrase through [AIConversationController]'s TTS.
     * Called by [MainActivity] immediately before starting the replacement route so the
     * announcement plays while the engine resolves the new destination.
     */
    fun announceReroute() {
        handler.post { aiController?.speak("Gerai, keičiu maršrutą.") }
    }

    /**
     * Starts a one-shot destination voice input session for StartScreen.
     * Routes the first transcript to [onVoiceResult] via [AIConversationController.startDestinationInput].
     * Second tap while active cancels the session (toggle behaviour).
     * No-op if [aiController] is not yet initialised.
     */
    fun startDestinationVoiceInput(onVoiceResult: (String) -> Unit) {
        Log.i(TAG, "STARTSCREEN_VOICE_REQUESTED")
        val ctrl = aiController
        if (ctrl == null) {
            Log.w(TAG, "STARTSCREEN_VOICE_ERROR reason=aiController_null")
            return
        }
        if (_isDestinationListening.value) {
            // Second tap = toggle off (cancel)
            stopDestinationVoiceInput()
            return
        }
        _isDestinationListening.value = true
        handler.post {
            ctrl.startDestinationInput(
                onResult = { text ->
                    Log.i(TAG, "STARTSCREEN_VOICE_RESULT text='$text'")
                    _isDestinationListening.value = false
                    onVoiceResult(text)
                },
                onEnd = {
                    Log.i(TAG, "STARTSCREEN_VOICE_ENDED")
                    _isDestinationListening.value = false
                },
            )
        }
    }

    /**
     * Stops any active destination voice input session.
     * Called when navigation starts so STT is cleanly terminated before
     * the Kentas navigation conversation mode takes over.
     */
    fun stopDestinationVoiceInput() {
        if (!_isDestinationListening.value) return
        Log.i(TAG, "STARTSCREEN_VOICE_ENDED reason=stopped_externally")
        _isDestinationListening.value = false
        handler.post { aiController?.stopDestinationInput() }
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
        trafficMonitor.onNavigationStopped()
        LocationProvider.stopUpdates(getApplication())
    }
}
