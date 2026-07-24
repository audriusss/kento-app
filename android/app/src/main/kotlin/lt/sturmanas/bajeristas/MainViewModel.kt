package lt.sturmanas.bajeristas

import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
 * ## Conversation flow
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
 * ## Location state — two separate signals
 *
 * [currentLocation] — the actual fused location fix. NEVER set to non-null by a timeout.
 * A non-null value always means a real GPS/network/Wi-Fi fix is available. Use this
 * for route calculation validation.
 *
 * [locationLoading] — spinner controller. True while no fix has arrived AND the 10 s
 * graceful timeout has not yet fired. Goes false when either condition is met so the
 * loading badge disappears. Does NOT imply a real fix exists.
 *
 * [locationServicesDisabled] — true when the device Location switch is off. Shown as
 * an action banner prompting the user to enable Location Services.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "KentasVM"

        /**
         * How long we show the "Gaunama GPS vieta…" loading badge while waiting for
         * the first fused fix. After this delay [locationLoading] goes false regardless
         * of whether a fix arrived, so the spinner doesn't block the user forever.
         * A missing fix is expressed by [currentLocation] remaining null — not by a fake
         * non-null value.
         */
        internal const val LOCATION_READY_TIMEOUT_MS = 10_000L
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

    // ── Location state ────────────────────────────────────────────────────

    /**
     * The actual fused location fix. Directly mirrors [LocationProvider.locationFlow].
     *
     * NEVER non-null due to a timeout. A non-null value always represents a real
     * GPS / Wi-Fi / cell fix received from the fused provider.
     *
     * Use this when you need to validate that a real location is available before
     * routing (e.g. a null check guards against routing from coordinates (0, 0)).
     */
    val currentLocation: StateFlow<Location?> = LocationProvider.locationFlow

    /**
     * True while location is still loading (no fix yet AND timeout not yet fired).
     *
     * When true, the "Gaunama GPS vieta…" spinner badge is shown on StartScreen.
     * Goes false when either:
     *  - the first fused fix arrives ([currentLocation] becomes non-null), OR
     *  - [LOCATION_READY_TIMEOUT_MS] (10 s) elapses (no fix, but we stop blocking the UI).
     *
     * A false value here does NOT mean a fix is available — check [currentLocation].
     */
    private val _locationLoadingTimeout = MutableStateFlow(false)

    val locationLoading: StateFlow<Boolean> =
        combine(LocationProvider.locationFlow, _locationLoadingTimeout) { loc, timedOut ->
            // Still loading if: no fix AND timeout has not yet fired.
            loc == null && !timedOut
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            // Initial value: loading if there is no fix right now.
            initialValue = LocationProvider.locationFlow.value == null,
        )

    /**
     * True when the device's Location Services switch is disabled.
     * Shown as a banner on StartScreen prompting the user to enable Location.
     * Accurate only after [startLocationUpdates] (or [retryLocationUpdates]) has been called.
     */
    val locationServicesDisabled: StateFlow<Boolean> =
        LocationProvider.locationServicesEnabled
            .map { enabled -> !enabled }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = false)

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        speechRecognitionManager.initialize()
        startLocationUpdates()

        // Arm the graceful 10 s fallback so the spinner never blocks the user permanently.
        // This timeout only hides the spinner — it does NOT set currentLocation to non-null.
        viewModelScope.launch {
            delay(LOCATION_READY_TIMEOUT_MS)
            if (!_locationLoadingTimeout.value) {
                _locationLoadingTimeout.value = true
                val hasRealFix = LocationProvider.locationFlow.value != null
                Log.d(TAG,
                    "locationLoading: ${LOCATION_READY_TIMEOUT_MS}ms timeout fired" +
                    " hasRealFix=$hasRealFix — hiding spinner regardless")
            }
        }
        Log.d(TAG, "MainViewModel initialised")
    }

    // ── Location updates ──────────────────────────────────────────────────

    /**
     * Start (or re-start) fused location updates.
     * Called from [init] and from MainActivity when location permission transitions
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
            Log.d(TAG, "speakNavInstruction: interrupting conv for maneuver TTS")
            speechCoordinator.speakNavigation(phrase) {
                conversationController.resumeAfterNavInterrupt()
            }
        }
    }

    /**
     * Speak a route-start announcement.
     * Called from MainActivity when navigation phase transitions to NAVIGATING.
     */
    fun speakRouteReady(destinationName: String) {
        if (isSpeechBlocked) return
        val dest = destinationName.ifBlank { "tikslą" }
        speechCoordinator.speakNavigation("Radau maršrutą. Važiuojam į $dest.") {
            conversationController.resumeAfterNavInterrupt()
        }
    }

    /**
     * Speak the arrival prompt.
     * Called once per session from MainActivity when [NavigationState.hasArrived] becomes true.
     *
     * The phrase is hardcoded to match the on-screen dialog text so the user hears exactly
     * what they see.  The [KentasNavigationPhraseFormatter] ARRIVE phrase is a simple
     * "Atvykote!" — it does not invite the confirmation the dialog requires.
     */
    fun speakArrival() {
        if (isSpeechBlocked) return
        speechCoordinator.speakNavigation("Atrodo, jau atvykome. Baigti maršrutą?")
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
