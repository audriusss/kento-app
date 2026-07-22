package lt.sturmanas.bajeristas

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.navigation.CandidatePlace
import lt.sturmanas.bajeristas.navigation.DestinationResolution
import lt.sturmanas.bajeristas.navigation.DestinationResolver
import lt.sturmanas.bajeristas.navigation.LocationProvider
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.navigation.StopoverEntry
import lt.sturmanas.bajeristas.navigation.WaypointManager
import lt.sturmanas.bajeristas.personality.SessionConfig
import lt.sturmanas.bajeristas.voice.ClarificationState
import lt.sturmanas.bajeristas.voice.SavedPlacesRepository
import lt.sturmanas.bajeristas.voice.SpeechRecognitionManager
import lt.sturmanas.bajeristas.voice.TtsManager
import lt.sturmanas.bajeristas.voice.VoiceCommand
import lt.sturmanas.bajeristas.voice.VoiceCommandParser
import lt.sturmanas.bajeristas.voice.VoiceListeningState
import lt.sturmanas.bajeristas.voice.VoiceNavAction
import lt.sturmanas.bajeristas.voice.askKentas

/**
 * Single ViewModel for the entire app — survives screen rotation.
 *
 * Owns all audio-output ([TtsManager]), audio-input ([SpeechRecognitionManager]),
 * the destination resolver ([DestinationResolver] + [SavedPlacesRepository]),
 * and the waypoint manager ([WaypointManager]) that must not be destroyed and
 * recreated on configuration changes.
 *
 * ## Voice command flow
 *
 * 1. User presses mic → composable calls [onMicPressed] (after RECORD_AUDIO check).
 * 2. [SpeechRecognitionManager] recognizes speech → [pendingRecognizedText] becomes non-null.
 * 3. [SturmanasApp] LaunchedEffect calls [executeVoiceCommand] with current nav state.
 * 4. Non-navigation commands (distance, time, destination, repeat, general question)
 *    are handled entirely here — no composable involvement.
 * 5. Navigation-level actions (start/stop nav, mute/unmute) are emitted via
 *    [pendingNavAction] and consumed by [SturmanasApp] through a second LaunchedEffect.
 * 6. For voice-triggered StartNavigation, [DestinationResolver] is called to normalise
 *    the raw destination text before forwarding to the navigation engine.
 * 7. For voice-triggered AddWaypoint, [DestinationResolver] resolves the stop and
 *    [WaypointManager] inserts it. The engine is then re-routed to the next target.
 *
 * ## TTS / microphone coordination
 *
 * [onMicPressed] stops TTS before starting the recognizer, preventing Kentas's own
 * voice from being transcribed. While [voiceListeningState] is LISTENING, the
 * composable disables outgoing TTS announcements via the [isSpeechBlocked] flag.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG      = "KentasVoice"
        private const val DEST_TAG = "KentasDestination"
        private const val WP_TAG   = "KentasWaypoint"
    }

    // ── Audio output ──────────────────────────────────────────────────────

    /** Lithuanian text-to-speech. Accessed directly by [SturmanasApp] for maneuver TTS. */
    val ttsManager: TtsManager = TtsManager(application).also { it.initialize() }

    // ── Audio input ───────────────────────────────────────────────────────

    val speechRecognitionManager = SpeechRecognitionManager(application)

    // ── Saved places ──────────────────────────────────────────────────────

    private val savedPlacesRepository = SavedPlacesRepository(application)

    private val _homeAddress = MutableStateFlow(savedPlacesRepository.getHomeAddress() ?: "")
    /** Currently saved home address. Empty string if not configured. */
    val homeAddress: StateFlow<String> = _homeAddress.asStateFlow()

    private val _workAddress = MutableStateFlow(savedPlacesRepository.getWorkAddress() ?: "")
    /** Currently saved work address. Empty string if not configured. */
    val workAddress: StateFlow<String> = _workAddress.asStateFlow()

    // ── Waypoint manager ──────────────────────────────────────────────────

    /**
     * Tracks intermediate stops and the final destination for the current session.
     * Exposed as package-internal so tests can inspect state directly.
     */
    internal val waypointManager = WaypointManager()

    /**
     * Live list of intermediate stops. Observed by [NavigationScreen] to render
     * the route card and by [SturmanasApp] to pass remove-stop callbacks.
     */
    val stopovers: StateFlow<List<StopoverEntry>> = waypointManager.stopovers

    private val _finalDestinationName = MutableStateFlow("")
    /**
     * Display name of the overall final destination (e.g. "Akropolį", "Lidl").
     * Blank when not navigating. Observed by [NavigationScreen] for the route card.
     */
    val finalDestinationName: StateFlow<String> = _finalDestinationName.asStateFlow()

    // ── Voice state ───────────────────────────────────────────────────────

    private val _voiceListeningState = MutableStateFlow(VoiceListeningState.IDLE)
    /** Drives the [MicButton] visual and blocks maneuver TTS while LISTENING. */
    val voiceListeningState: StateFlow<VoiceListeningState> = _voiceListeningState.asStateFlow()

    private val _voiceStatusText = MutableStateFlow("")
    /** "Kentas klauso…" / "Kentas ieško vietos…" / "Išgirdau: …" / error messages. */
    val voiceStatusText: StateFlow<String> = _voiceStatusText.asStateFlow()

    private val _isSolvingDestination = MutableStateFlow(false)
    /** True while [DestinationResolver] is resolving a voice destination. */
    val isSolvingDestination: StateFlow<Boolean> = _isSolvingDestination.asStateFlow()

    // ── Pending recognized text ───────────────────────────────────────────

    private val _pendingRecognizedText = MutableStateFlow<String?>(null)
    /**
     * Non-null when the recognizer has a final result ready to process.
     * [SturmanasApp] observes this; when non-null calls [executeVoiceCommand],
     * then calls [clearPendingRecognizedText].
     */
    val pendingRecognizedText: StateFlow<String?> = _pendingRecognizedText.asStateFlow()

    // ── Pending navigation action ─────────────────────────────────────────

    private val _pendingNavAction = MutableStateFlow<VoiceNavAction?>(null)
    /**
     * Non-null when a voice command requires a composable-level state change.
     * [SturmanasApp] observes this, acts on it, then calls [clearPendingNavAction].
     */
    val pendingNavAction: StateFlow<VoiceNavAction?> = _pendingNavAction.asStateFlow()

    // ── Clarification state ───────────────────────────────────────────────

    private val _pendingClarification = MutableStateFlow<ClarificationState?>(null)
    /**
     * Non-null when the destination resolver could not pick a single result and needs
     * the user to choose. [SturmanasApp] presents a dialog with up to 3 options.
     */
    val pendingClarification: StateFlow<ClarificationState?> = _pendingClarification.asStateFlow()

    // ── Latest spoken instruction (for RepeatInstruction command) ─────────

    private var latestInstruction: String = ""

    // ── True while microphone is listening ────────────────────────────────

    /** True while LISTENING — blocks new TTS maneuver announcements. */
    val isSpeechBlocked: Boolean
        get() = _voiceListeningState.value == VoiceListeningState.LISTENING

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        speechRecognitionManager.initialize()
        setupRecognitionCallbacks()
        startLocationUpdates()
    }

    // ── Location caching ──────────────────────────────────────────────────

    /**
     * Starts continuous location updates so [resolveAndNavigate] can read a cached
     * fix immediately, even on the very first voice command after a cold launch.
     */
    private fun startLocationUpdates() {
        try {
            LocationProvider.startUpdates(getApplication())
            Log.d(TAG, "Location updates started")
        } catch (e: Exception) {
            Log.w(TAG, "startLocationUpdates failed: ${e.message}")
        }
    }

    private fun setupRecognitionCallbacks() {
        speechRecognitionManager.onListeningStarted = {
            Log.d(TAG, "SR: listening started")
            Log.d("KentasFlow", "SR listening started")
            _voiceListeningState.value = VoiceListeningState.LISTENING
            _voiceStatusText.value = "Kentas klauso…"
        }
        speechRecognitionManager.onPartialResult = { partial ->
            Log.d(TAG, "SR: partial='$partial'")
            _voiceStatusText.value = "Klausau: $partial…"
        }
        speechRecognitionManager.onResult = { text ->
            Log.d(TAG, "SR: final result='$text'")
            Log.d("KentasFlow", "SR final recognition: '$text'")
            _voiceListeningState.value = VoiceListeningState.PROCESSING
            _voiceStatusText.value = "Išgirdau: $text"
            _pendingRecognizedText.value = text
        }
        speechRecognitionManager.onError = { msg ->
            Log.e(TAG, "SR: error='$msg'")
            Log.d("KentasFlow", "SR error: $msg")
            _voiceListeningState.value = VoiceListeningState.ERROR
            _voiceStatusText.value = msg
            scheduleStatusClear(4_000)
        }
        speechRecognitionManager.onListeningStopped = {
            Log.d(TAG, "SR: stopped")
            if (_voiceListeningState.value == VoiceListeningState.LISTENING) {
                _voiceListeningState.value = VoiceListeningState.IDLE
            }
        }
    }

    // ── Public API — Mic ──────────────────────────────────────────────────

    /**
     * Called when the user taps the mic button.
     * Stops TTS first to prevent Kentas from recognising its own speech.
     * Must be called on the Main thread (SpeechRecognizer requirement).
     */
    fun onMicPressed() {
        Log.d("KentasFlow", "mic button pressed — stopping TTS, starting SR")
        ttsManager.stop()
        Log.d(TAG, "TTS stopped before listening")
        speechRecognitionManager.startListening()
    }

    // ── Public API — Command execution ────────────────────────────────────

    /**
     * Parse [text] (final SR output) and execute the resulting [VoiceCommand].
     *
     * Non-navigation commands are handled entirely here via TTS response.
     * Navigation commands emit a [VoiceNavAction] via [pendingNavAction].
     */
    fun executeVoiceCommand(
        text: String,
        navState: NavigationState,
        sessionConfig: SessionConfig,
        isMuted: Boolean,
    ) {
        val command = VoiceCommandParser.parse(text)
        Log.d(TAG, "parsed command: ${command::class.simpleName} from '$text'")
        Log.d("KentasFlow", "voice command parsed: ${command::class.simpleName}")

        when (command) {
            is VoiceCommand.RemainingDistance -> {
                Log.d("KentasFlow", "command: RemainingDistance (${navState.remainingDistanceMeters} m)")
                val msg = buildDistanceResponse(navState.remainingDistanceMeters)
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.RemainingTime -> {
                Log.d("KentasFlow", "command: RemainingTime (${navState.remainingDurationSeconds} s)")
                val msg = buildTimeResponse(navState.remainingDurationSeconds)
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.DestinationInfo -> {
                Log.d("KentasFlow", "command: DestinationInfo")
                val stops = waypointManager.allStops()
                val msg = if (stops.isEmpty()) {
                    val dest = navState.resolvedAddress.ifBlank { navState.destinationName }
                    if (dest.isBlank()) "Šiuo metu maršrutas nepasirinktas."
                    else "Važiuojame į $dest."
                } else {
                    buildRouteDescription(stops)
                }
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.RepeatInstruction -> {
                Log.d("KentasFlow", "command: RepeatInstruction latestInstruction='$latestInstruction'")
                val msg = if (latestInstruction.isBlank())
                    "Dar neturiu nurodymo, kurį galėčiau pakartoti."
                else
                    latestInstruction
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.MuteVoice -> {
                Log.d("KentasFlow", "command: MuteVoice")
                ttsManager.stop()
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = ""
                _pendingNavAction.value = VoiceNavAction.Mute
                return
            }

            is VoiceCommand.UnmuteVoice -> {
                Log.d("KentasFlow", "command: UnmuteVoice")
                _pendingNavAction.value = VoiceNavAction.Unmute
                viewModelScope.launch {
                    delay(150)
                    speakAndIdle("Balsas įjungtas.", isMuted = false)
                }
                return
            }

            is VoiceCommand.StopNavigation -> {
                Log.d("KentasFlow", "command: StopNavigation")
                speakAndIdle("Navigacija sustabdyta.", isMuted)
                _pendingNavAction.value = VoiceNavAction.StopNavigation
                return
            }

            is VoiceCommand.StartNavigation -> {
                Log.d("KentasFlow", "command: StartNavigation dest='${command.destination}'")
                _isSolvingDestination.value = true
                _voiceListeningState.value = VoiceListeningState.PROCESSING
                _voiceStatusText.value = "Kentas ieško vietos…"
                viewModelScope.launch {
                    resolveAndNavigate(command.destination, isMuted)
                }
                return
            }

            is VoiceCommand.SelectCandidate -> {
                Log.d("KentasFlow", "command: SelectCandidate(${command.index})")
                val clarif = _pendingClarification.value
                if (clarif == null) {
                    speakAndIdle("Nėra ko rinktis.", isMuted)
                } else {
                    val candidate = clarif.candidates.getOrNull(command.index - 1)
                    if (candidate == null) {
                        speakAndIdle("Tokio varianto nėra. Pasakykite: pirmą, antrą, ar trečią.", isMuted)
                    } else {
                        acceptCandidate(candidate, isMuted)
                    }
                }
                return
            }

            // ── Waypoint commands ─────────────────────────────────────────

            is VoiceCommand.AddWaypoint -> {
                Log.d(WP_TAG, "command: AddWaypoint place='${command.place}'")
                _isSolvingDestination.value = true
                _voiceListeningState.value = VoiceListeningState.PROCESSING
                _voiceStatusText.value = "Kentas ieško: ${command.place}…"
                viewModelScope.launch {
                    resolveAndAddWaypoint(command.place, isMuted)
                }
                return
            }

            is VoiceCommand.RemoveLastWaypoint -> {
                Log.d(WP_TAG, "command: RemoveLastWaypoint")
                val removed = waypointManager.removeLastStopover()
                val msg = if (removed == null) {
                    "Nėra sustojimų, kurių būtų galima pašalinti."
                } else {
                    val remaining = waypointManager.stopovers.value.size
                    val nextTarget = waypointManager.nextTarget()
                    if (nextTarget != null) {
                        _pendingNavAction.value = VoiceNavAction.StartNavigation(nextTarget.resolvedQuery)
                    }
                    if (remaining == 0) "Pašalinau ${removed.displayName}. Maršrutas perskaičiuotas."
                    else "Pašalinau paskutinį sustojimą. Liko $remaining ${sustojimasForm(remaining)}."
                }
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.ClearWaypoints -> {
                Log.d(WP_TAG, "command: ClearWaypoints")
                val count = waypointManager.stopovers.value.size
                waypointManager.clearStopovers()
                val msg = if (count == 0) {
                    "Nėra sustojimų, kurių būtų galima pašalinti."
                } else {
                    val fd = waypointManager.finalDestination
                    if (fd != null) {
                        _pendingNavAction.value = VoiceNavAction.StartNavigation(fd.resolvedQuery)
                        "Pašalinti visi sustojimai. Važiuojame tiesiai į ${fd.displayName}."
                    } else {
                        "Pašalinti visi sustojimai."
                    }
                }
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.ListWaypoints -> {
                Log.d(WP_TAG, "command: ListWaypoints")
                val stops = waypointManager.allStops()
                val msg = if (stops.isEmpty()) {
                    val dest = navState.resolvedAddress.ifBlank { navState.destinationName }
                    if (dest.isBlank()) "Šiuo metu maršrutas nepasirinktas."
                    else "Važiuojame į $dest. Sustojimų nėra."
                } else {
                    buildRouteDescription(stops)
                }
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.ContinueRoute -> {
                Log.d(WP_TAG, "command: ContinueRoute")
                val next = waypointManager.nextTarget()
                val msg = if (next != null) {
                    "Tęsiame. Sekantis tikslas: ${next.displayName}."
                } else {
                    val dest = navState.resolvedAddress.ifBlank { navState.destinationName }
                    if (dest.isBlank()) "Navigacija nepradėta." else "Tęsiame kelionę į $dest."
                }
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.GeneralQuestion -> {
                Log.d("KentasFlow", "command: GeneralQuestion → OpenAI '${command.text}'")
                _voiceListeningState.value = VoiceListeningState.PROCESSING
                viewModelScope.launch {
                    val reply = askKentas(
                        userText = command.text,
                        config = sessionConfig,
                        navState = navState,
                        apiKey = BuildConfig.OPENAI_API_KEY,
                    )
                    Log.d("KentasFlow", "OpenAI reply: '$reply'")
                    _voiceStatusText.value = reply
                    _voiceListeningState.value = VoiceListeningState.IDLE
                    if (!isMuted) ttsManager.speak(reply)
                    scheduleStatusClear(6_000)
                }
                return
            }

            is VoiceCommand.Unknown -> {
                Log.d("KentasFlow", "command: Unknown '${command.text}'")
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Komandos neišgirdau."
            }
        }

        scheduleStatusClear(4_000)
    }

    // ── Public API — Clarification ────────────────────────────────────────

    /**
     * Called when the user taps a candidate button in the clarification dialog.
     * [index] is 1-based.
     */
    fun onClarificationAnswer(index: Int) {
        val clarif = _pendingClarification.value ?: return
        val candidate = clarif.candidates.getOrNull(index - 1) ?: return
        acceptCandidate(candidate, isMuted = false)
    }

    /** Dismiss the clarification dialog without starting navigation. */
    fun cancelClarification() {
        Log.d(DEST_TAG, "cancelClarification")
        _pendingClarification.value = null
        _voiceListeningState.value = VoiceListeningState.IDLE
        _voiceStatusText.value = ""
    }

    // ── Public API — Waypoints ────────────────────────────────────────────

    /**
     * Called by [SturmanasApp] when [NavigationState.hasArrived] becomes true.
     *
     * If intermediate stops remain, automatically advances to the next stop and
     * emits a [VoiceNavAction.StartNavigation] to re-route the engine.
     * Does nothing when no stopovers are queued (normal final-destination arrival).
     */
    fun onWaypointArrived() {
        if (!waypointManager.hasStopovers()) {
            Log.d(WP_TAG, "onWaypointArrived: no stopovers — final destination, normal arrival")
            return
        }
        val completedName = waypointManager.stopovers.value.firstOrNull()?.displayName ?: "sustojimas"
        val nextTarget = waypointManager.advanceToNextStop()
        if (nextTarget == null) {
            Log.d(WP_TAG, "onWaypointArrived: no next target after advance — unexpected state")
            return
        }
        val remaining = waypointManager.stopovers.value.size
        val remainingMsg = when {
            remaining == 0 -> "Tęsiame į galutinį tikslą, ${nextTarget.displayName}."
            remaining == 1 -> "Liko vienas sustojimas."
            else           -> "Liko $remaining ${sustojimasForm(remaining)}."
        }
        Log.d(WP_TAG, "onWaypointArrived: '$completedName' done → next='${nextTarget.displayName}'")
        ttsManager.speak("Atvykome į $completedName. $remainingMsg")
        _pendingNavAction.value = VoiceNavAction.StartNavigation(nextTarget.resolvedQuery)
    }

    /**
     * Remove the stopover at [index] (0-based) from [WaypointManager] and reroute
     * the engine to the new [nextTarget]. Called when the user taps the × button
     * on a route-card stop.
     */
    fun removeStopoverAt(index: Int, isMuted: Boolean = false) {
        val removed = waypointManager.removeStopoverAt(index) ?: return
        Log.d(WP_TAG, "removeStopoverAt($index): '${removed.displayName}'")
        val next = waypointManager.nextTarget()
        if (next != null) _pendingNavAction.value = VoiceNavAction.StartNavigation(next.resolvedQuery)
        val msg = "Pašalinau ${removed.displayName}. Maršrutas perskaičiuotas."
        _voiceStatusText.value = msg
        if (!isMuted) ttsManager.speak(msg)
        scheduleStatusClear(4_000)
    }

    /**
     * Called when navigation stops (user taps "Baigti" or says "Sustabdyk").
     * Resets waypoint state so the next session starts clean.
     */
    fun onNavigationStopped() {
        Log.d(WP_TAG, "onNavigationStopped: clearing waypoints")
        waypointManager.clear()
        _finalDestinationName.value = ""
    }

    // ── Public API — Saved places ─────────────────────────────────────────

    fun setHomeAddress(addr: String) {
        savedPlacesRepository.setHomeAddress(addr)
        _homeAddress.value = addr.trim()
    }

    fun clearHomeAddress() {
        savedPlacesRepository.clearHomeAddress()
        _homeAddress.value = ""
    }

    fun setWorkAddress(addr: String) {
        savedPlacesRepository.setWorkAddress(addr)
        _workAddress.value = addr.trim()
    }

    fun clearWorkAddress() {
        savedPlacesRepository.clearWorkAddress()
        _workAddress.value = ""
    }

    // ── Public API — Misc ─────────────────────────────────────────────────

    /**
     * Store the instruction that was just spoken, for [VoiceCommand.RepeatInstruction].
     * Called from [SturmanasApp]'s maneuver announcement LaunchedEffect before speaking.
     */
    fun recordSpokenInstruction(instruction: String) {
        if (instruction.isNotBlank()) {
            latestInstruction = instruction
            Log.d(TAG, "recordSpokenInstruction: '$instruction'")
        }
    }

    /** Consume the pending recognized text after calling [executeVoiceCommand]. */
    fun clearPendingRecognizedText() {
        _pendingRecognizedText.value = null
    }

    /** Consume the pending nav action after the composable has acted on it. */
    fun clearPendingNavAction() {
        _pendingNavAction.value = null
    }

    override fun onCleared() {
        super.onCleared()
        LocationProvider.stopUpdates(getApplication())
        speechRecognitionManager.release()
        ttsManager.release()
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Resolve [rawDestination] via [DestinationResolver], record it as the final
     * destination in [WaypointManager], and emit a [VoiceNavAction.StartNavigation].
     *
     * This sets a NEW primary destination — any existing stopovers are cleared
     * by [WaypointManager.setFinalDestination].
     */
    private suspend fun resolveAndNavigate(rawDestination: String, isMuted: Boolean) {
        Log.d(DEST_TAG, "resolveAndNavigate: raw='$rawDestination'")
        val savedPlaces = savedPlacesRepository.getAll()
        val (currentLat, currentLng, currentLocality) =
            LocationProvider.getCurrentLocation(getApplication())
        Log.d(DEST_TAG, "location: lat=$currentLat lng=$currentLng locality='$currentLocality'")

        val resolution = DestinationResolver.resolve(
            rawText = rawDestination,
            currentLat = currentLat,
            currentLng = currentLng,
            currentLocality = currentLocality,
            savedPlaces = savedPlaces,
        )

        Log.d(DEST_TAG, "resolution: ${resolution::class.simpleName}")
        _isSolvingDestination.value = false

        when (resolution) {
            is DestinationResolution.ExactAddress -> {
                Log.d(DEST_TAG, "ExactAddress: '${resolution.query}'")
                waypointManager.setFinalDestination(StopoverEntry(resolution.query, resolution.query))
                _finalDestinationName.value = resolution.query
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Rasta: ${resolution.query}"
                if (!isMuted) ttsManager.speak("Keliaujame į ${resolution.query}.")
                _pendingNavAction.value = VoiceNavAction.StartNavigation(resolution.query)
                scheduleStatusClear(5_000)
            }

            is DestinationResolution.PlaceSearch -> {
                Log.d(DEST_TAG, "PlaceSearch: '${resolution.query}'")
                waypointManager.setFinalDestination(StopoverEntry(rawDestination, resolution.query))
                _finalDestinationName.value = rawDestination
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Kentas ieško: ${resolution.query}"
                _pendingNavAction.value = VoiceNavAction.StartNavigation(resolution.query)
                scheduleStatusClear(5_000)
            }

            is DestinationResolution.SavedPlace -> {
                Log.d(DEST_TAG, "SavedPlace: '${resolution.name}' → '${resolution.address}'")
                waypointManager.setFinalDestination(StopoverEntry(resolution.name, resolution.address))
                _finalDestinationName.value = resolution.name
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Rasta: ${resolution.name}"
                if (!isMuted) ttsManager.speak("Keliaujame ${resolution.name.lowercase()}.")
                _pendingNavAction.value = VoiceNavAction.StartNavigation(resolution.address)
                scheduleStatusClear(5_000)
            }

            is DestinationResolution.NeedsClarification -> {
                Log.d(DEST_TAG, "NeedsClarification: ${resolution.suggestions.size} candidates")
                _voiceListeningState.value = VoiceListeningState.IDLE
                _pendingClarification.value = ClarificationState(
                    originalText = resolution.originalText,
                    candidates = resolution.suggestions,
                )
                val msg = buildClarificationMessage(resolution.suggestions)
                _voiceStatusText.value = "Radau kelis variantus"
                if (!isMuted) ttsManager.speak(msg)
            }

            is DestinationResolution.Failure -> {
                Log.d(DEST_TAG, "Failure: '${resolution.message}'")
                _voiceListeningState.value = VoiceListeningState.ERROR
                _voiceStatusText.value = resolution.message
                if (!isMuted) ttsManager.speak(resolution.message)
                scheduleStatusClear(6_000)
            }
        }
    }

    /**
     * Resolve [rawPlace] via [DestinationResolver] and insert the result as an
     * intermediate stop in [WaypointManager]. Re-routes the engine to [nextTarget].
     *
     * This does NOT replace the final destination or clear existing stopovers.
     */
    private suspend fun resolveAndAddWaypoint(rawPlace: String, isMuted: Boolean) {
        Log.d(WP_TAG, "resolveAndAddWaypoint: raw='$rawPlace'")
        val savedPlaces = savedPlacesRepository.getAll()
        val (currentLat, currentLng, currentLocality) =
            LocationProvider.getCurrentLocation(getApplication())

        val resolution = DestinationResolver.resolve(
            rawText = rawPlace,
            currentLat = currentLat,
            currentLng = currentLng,
            currentLocality = currentLocality,
            savedPlaces = savedPlaces,
        )

        _isSolvingDestination.value = false
        _voiceListeningState.value = VoiceListeningState.IDLE

        Log.d(WP_TAG, "resolveAndAddWaypoint: resolution=${resolution::class.simpleName}")

        val entry: StopoverEntry? = when (resolution) {
            is DestinationResolution.ExactAddress ->
                StopoverEntry(resolution.query, resolution.query)
            is DestinationResolution.PlaceSearch ->
                StopoverEntry(rawPlace, resolution.query)
            is DestinationResolution.SavedPlace ->
                StopoverEntry(resolution.name, resolution.address)
            is DestinationResolution.NeedsClarification ->
                // Auto-pick first candidate for waypoints; no dialog needed.
                resolution.suggestions.firstOrNull()
                    ?.let { StopoverEntry(it.name, it.address) }
            is DestinationResolution.Failure -> null
        }

        if (entry == null) {
            val msg = "Nepavyko pridėti sustojimo. Tęsiame dabartinį maršrutą."
            Log.w(WP_TAG, "resolveAndAddWaypoint: resolution failed → $msg")
            _voiceStatusText.value = msg
            if (!isMuted) ttsManager.speak(msg)
            scheduleStatusClear(5_000)
            return
        }

        val added = waypointManager.addStopover(entry)
        if (!added) {
            val msg = "Šis sustojimas jau yra maršrute."
            _voiceStatusText.value = msg
            if (!isMuted) ttsManager.speak(msg)
            scheduleStatusClear(4_000)
            return
        }

        val count = waypointManager.stopovers.value.size
        val confirmMsg = "Pridėjau ${entry.displayName} kaip tarpinį sustojimą. " +
            "Liko $count ${sustojimasForm(count)}."
        Log.d(WP_TAG, "addStopover confirmed: '${entry.displayName}' — total stopovers: $count")
        _voiceStatusText.value = "Pridėta: ${entry.displayName}"
        if (!isMuted) ttsManager.speak(confirmMsg)

        // Re-route engine to the new first target (could be this new stopover or an earlier one).
        val nextTarget = waypointManager.nextTarget()
        if (nextTarget != null) {
            _pendingNavAction.value = VoiceNavAction.StartNavigation(nextTarget.resolvedQuery)
        }
        scheduleStatusClear(5_000)
    }

    private fun acceptCandidate(candidate: CandidatePlace, isMuted: Boolean) {
        Log.d(DEST_TAG, "acceptCandidate: '${candidate.name}' → '${candidate.address}'")
        waypointManager.setFinalDestination(StopoverEntry(candidate.name, candidate.address))
        _finalDestinationName.value = candidate.name
        _pendingClarification.value = null
        _voiceListeningState.value = VoiceListeningState.IDLE
        _voiceStatusText.value = "Pasirinkta: ${candidate.name}"
        if (!isMuted) ttsManager.speak("Gerai, važiuojame į ${candidate.name}.")
        _pendingNavAction.value = VoiceNavAction.StartNavigation(candidate.address)
        scheduleStatusClear(5_000)
    }

    private fun buildClarificationMessage(candidates: List<CandidatePlace>): String {
        val sb = StringBuilder("Radau kelis variantus. Kurį renkamės? ")
        candidates.take(3).forEachIndexed { i, c ->
            val ordinal = when (i) { 0 -> "Pirmas"; 1 -> "Antras"; else -> "Trečias" }
            val dist = c.distanceMeters?.let { m ->
                if (m < 1000) " (${m} m)" else " (${m / 1000} km)"
            } ?: ""
            sb.append("$ordinal: ${c.name}$dist. ")
        }
        sb.append("Pasakykite: pirmą, antrą, ar trečią.")
        return sb.toString().trim()
    }

    private fun buildRouteDescription(stops: List<StopoverEntry>): String {
        if (stops.isEmpty()) return "Maršrutas nepasirinktas."
        return when (stops.size) {
            1 -> "Važiuojame į ${stops[0].displayName}."
            2 -> "Maršrutas: ${stops[0].displayName}, tada ${stops[1].displayName}."
            else -> {
                val intermediate = stops.dropLast(1).joinToString(", ") { it.displayName }
                "Maršrutas: $intermediate, tada ${stops.last().displayName}."
            }
        }
    }

    private fun speakAndIdle(msg: String, isMuted: Boolean) {
        _voiceListeningState.value = VoiceListeningState.IDLE
        _voiceStatusText.value = msg
        if (!isMuted && msg.isNotBlank()) ttsManager.speak(msg)
    }

    private fun scheduleStatusClear(delayMs: Long) {
        viewModelScope.launch {
            delay(delayMs)
            if (_voiceListeningState.value == VoiceListeningState.IDLE) {
                _voiceStatusText.value = ""
            }
        }
    }

    // ── Response builders ─────────────────────────────────────────────────

    internal fun buildDistanceResponse(meters: Int): String = when {
        meters <= 0 || meters == Int.MAX_VALUE -> "Liko labai mažai."
        meters < 100  -> "Liko apie $meters metrų."
        meters < 1000 -> "Liko apie ${(meters / 50) * 50} metrų."
        else -> {
            val km = meters / 1000
            val rem = (meters % 1000) / 100
            if (rem == 0) "Liko apie $km ${kilometraiForm(km)}."
            else "Liko apie $km koma $rem ${kilometraiForm(km)}."
        }
    }

    internal fun buildTimeResponse(seconds: Int): String = when {
        seconds <= 0  -> "Atvykimo laikas nežinomas."
        seconds < 120 -> "Liko apie minutę."
        else -> {
            val mins = seconds / 60
            "Atvyksime maždaug po $mins ${minutesForm(mins)}."
        }
    }

    private fun kilometraiForm(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11         -> "kilometras"
        n % 10 in 2..9 && n % 100 !in 11..19 -> "kilometrai"
        else                                   -> "kilometrų"
    }

    private fun minutesForm(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11         -> "minutės"
        else                                   -> "minučių"
    }

    internal fun sustojimasForm(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11         -> "sustojimas"
        n % 10 in 2..9 && n % 100 !in 11..19 -> "sustojimai"
        else                                   -> "sustojimų"
    }
}
