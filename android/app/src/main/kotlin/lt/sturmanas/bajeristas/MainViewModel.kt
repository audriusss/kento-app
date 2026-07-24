package lt.sturmanas.bajeristas

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import lt.sturmanas.bajeristas.community.CommunityMarkerRepository
import lt.sturmanas.bajeristas.navigation.LocationProvider
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.voice.ConversationState
import lt.sturmanas.bajeristas.voice.KentasConversationController
import lt.sturmanas.bajeristas.voice.KentasNavigationPhraseFormatter
import lt.sturmanas.bajeristas.voice.KentasSpeechCoordinator
import lt.sturmanas.bajeristas.voice.SavedPlacesRepository
import lt.sturmanas.bajeristas.voice.SpeechRecognitionManager
import lt.sturmanas.bajeristas.voice.TtsManager
import lt.sturmanas.bajeristas.voice.VoiceListeningState

/**
 * Single ViewModel for the entire app — survives screen rotation.
 *
 * Owns [KentasSpeechCoordinator] (TTS + watchdog), [SpeechRecognitionManager]
 * (SR wrapper), [KentasConversationController] (conversation loop),
 * [KentasNavigationPhraseFormatter] (deterministic nav phrases), and
 * [SavedPlacesRepository] (home/work addresses).
 *
 * ## Conversation flow (new simplified architecture)
 *
 * 1. User taps mic button → [toggleConversation] → [KentasConversationController].
 * 2. SR fires → AI call via [askKentas] → TTS via [KentasSpeechCoordinator].
 * 3. After TTS: re-listen. After 30 s of silence: conversation stops automatically.
 *
 * ## Navigation TTS flow
 *
 * MainActivity observes navigation state changes and calls [speakNavInstruction]
 * when distance crosses an announcement threshold.  [KentasSpeechCoordinator]
 * guarantees navigation audio always interrupts conversation audio.
 *
 * ## Voice destination entry
 *
 * Removed. Destination is typed only; [navigationController.startNavigation] is
 * called directly from the UI layer with the text-field contents.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "KentasVM"
    }

    // ── Audio output ──────────────────────────────────────────────────────

    /** Owned by speechCoordinator — do NOT call ttsManager directly from outside the VM. */
    private val ttsManager: TtsManager = TtsManager(application).also { it.initialize() }

    val speechCoordinator: KentasSpeechCoordinator =
        KentasSpeechCoordinator(ttsManager, viewModelScope)

    // ── Audio input ───────────────────────────────────────────────────────

    val speechRecognitionManager = SpeechRecognitionManager(application)

    // ── Navigation phrase formatter ────────────────────────────────────────

    private val phraseFormatter = KentasNavigationPhraseFormatter()

    // ── Conversation controller ───────────────────────────────────────────

    val conversationController = KentasConversationController(
        scope             = viewModelScope,
        speechManager     = speechRecognitionManager,
        speechCoordinator = speechCoordinator,
        apiKey            = BuildConfig.OPENAI_API_KEY,
    )

    /** True while a conversation session is active. Drives the mic button ring. */
    val isConversationActive: StateFlow<Boolean> = conversationController.isActive

    /** Granular conversation state mapped to the legacy VoiceListeningState enum for MicButton. */
    val voiceListeningState: StateFlow<VoiceListeningState> = conversationController.state
        .map { s ->
            when (s) {
                ConversationState.IDLE         -> VoiceListeningState.IDLE
                ConversationState.LISTENING    -> VoiceListeningState.LISTENING
                ConversationState.USER_SPEAKING -> VoiceListeningState.USER_SPEAKING
                ConversationState.THINKING     -> VoiceListeningState.THINKING
                ConversationState.SPEAKING     -> VoiceListeningState.SPEAKING
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VoiceListeningState.IDLE)

    /** True while the microphone is hot — maneuver TTS must not fire. */
    val isSpeechBlocked: Boolean
        get() = voiceListeningState.value.let {
            it == VoiceListeningState.LISTENING || it == VoiceListeningState.USER_SPEAKING
        }

    // ── Saved places ──────────────────────────────────────────────────────

    private val savedPlacesRepository = SavedPlacesRepository(application)

    private val _homeAddress = MutableStateFlow(savedPlacesRepository.getHomeAddress() ?: "")
    val homeAddress: StateFlow<String> = _homeAddress.asStateFlow()

    private val _workAddress = MutableStateFlow(savedPlacesRepository.getWorkAddress() ?: "")
    val workAddress: StateFlow<String> = _workAddress.asStateFlow()

    // ── Community markers ─────────────────────────────────────────────────

    val markerRepository = CommunityMarkerRepository(application)

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        speechRecognitionManager.initialize()
        startLocationUpdates()
        Log.d(TAG, "MainViewModel initialised")
    }

    // ── Location updates ──────────────────────────────────────────────────

    /**
     * Start (or re-start) continuous location updates.
     * Called from init and from MainActivity when location permission transitions
     * from denied → granted.
     */
    fun retryLocationUpdates() {
        try {
            LocationProvider.startUpdates(getApplication())
            Log.d(TAG, "Location updates started / re-subscribed")
        } catch (e: Exception) {
            Log.w(TAG, "retryLocationUpdates failed: ${e.message}")
        }
    }

    private fun startLocationUpdates() = retryLocationUpdates()

    // ── Conversation control ──────────────────────────────────────────────

    /** Toggle the conversation on or off (called from the mic button). */
    fun toggleConversation() {
        conversationController.toggleConversation()
    }

    /** Stop conversation — called when navigation is stopped or on fatal error. */
    fun stopConversation() {
        conversationController.stopConversation()
    }

    // ── Navigation TTS ────────────────────────────────────────────────────

    /**
     * Speak a deterministic Lithuanian navigation phrase for [navState] at
     * [distanceMeters] to the next maneuver.
     *
     * ARRIVE is handled separately by [speakArrival] so this method returns early
     * for that maneuver type. Returns early if speech is blocked by an active mic.
     */
    fun speakNavInstruction(navState: NavigationState, distanceMeters: Int) {
        if (isSpeechBlocked) {
            Log.d(TAG, "speakNavInstruction: skipped — mic is active")
            return
        }
        if (navState.maneuverType == ManeuverType.ARRIVE) return
        val phrase = phraseFormatter.format(
            maneuver      = navState.maneuverType,
            distanceMeters = distanceMeters,
            nextRoadName  = navState.nextRoadName,
        )
        if (phrase.isNotBlank()) {
            speechCoordinator.speakNavigation(phrase)
        }
    }

    /**
     * Speak a route-start announcement.
     * Called from MainActivity when navigation phase transitions to NAVIGATING.
     */
    fun speakRouteReady(destinationName: String) {
        if (isSpeechBlocked) return
        val dest = destinationName.ifBlank { "tikslą" }
        speechCoordinator.speakNavigation("Maršrutas į $dest paruoštas. Pradedame kelionę.")
    }

    /**
     * Speak the arrival announcement.
     * Called from MainActivity when [NavigationState.hasArrived] becomes true.
     */
    fun speakArrival() {
        if (isSpeechBlocked) return
        val phrase = phraseFormatter.format(
            maneuver       = ManeuverType.ARRIVE,
            distanceMeters = 0,
            nextRoadName   = "",
        )
        speechCoordinator.speakNavigation(phrase.ifBlank { "Atvykote į tikslą!" })
    }

    /**
     * Called when the user stops navigation.
     * Stops conversation and all TTS.
     */
    fun onNavigationStopped() {
        stopConversation()
        speechCoordinator.stop()
        Log.d(TAG, "onNavigationStopped: conversation and TTS stopped")
    }

    /**
     * Update the navigation state visible to the conversation controller so the
     * AI always has current road / maneuver context.
     */
    fun updateConversationNavState(navState: NavigationState) {
        conversationController.latestNavState = navState
    }

    // ── Saved places ──────────────────────────────────────────────────────

    fun setHomeAddress(addr: String) {
        savedPlacesRepository.setHomeAddress(addr)
        _homeAddress.value = addr.trim()
    }

    fun setWorkAddress(addr: String) {
        savedPlacesRepository.setWorkAddress(addr)
        _workAddress.value = addr.trim()
    }

    fun clearHomeAddress() {
        savedPlacesRepository.clearHomeAddress()
        _homeAddress.value = ""
    }

    fun clearWorkAddress() {
        savedPlacesRepository.clearWorkAddress()
        _workAddress.value = ""
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared: releasing resources")
        conversationController.release()
        speechRecognitionManager.release()
        speechCoordinator.release()
        LocationProvider.stopUpdates(getApplication())
    }
}
