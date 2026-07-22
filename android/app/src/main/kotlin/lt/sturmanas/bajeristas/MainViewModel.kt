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
 * and the destination resolver ([DestinationResolver] + [SavedPlacesRepository])
 * that must not be destroyed and recreated on configuration changes.
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
     * The user may tap a button ([onClarificationAnswer]) or speak an ordinal
     * ("pirmą", "antrą", "trečią") which routes through [executeVoiceCommand].
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
     *
     * The update interval is coarse (every 30 s / 100 m) — enough to keep
     * [LocationProvider.cachedLocation] fresh without draining the battery.
     *
     * Failure (no permission, no provider) is silent: [LocationProvider.cachedLocation]
     * stays null and [resolveAndNavigate] degrades to an unbiased search gracefully.
     */
    private fun startLocationUpdates() {
        try {
            LocationProvider.startUpdates(getApplication())
            Log.d(TAG, "Location updates started")
        } catch (e: Exception) {
            // Defensive: permission not yet granted at ViewModel creation time.
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
     *
     * For [VoiceCommand.StartNavigation], calls [DestinationResolver.resolve] to
     * normalise the raw destination before forwarding to the navigation engine.
     *
     * Call [clearPendingRecognizedText] immediately after calling this.
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
                val dest = navState.resolvedAddress.ifBlank { navState.destinationName }
                val msg = if (dest.isBlank()) "Šiuo metu maršrutas nepasirinktas."
                          else "Važiuojame į $dest."
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
     * Call [DestinationResolver], then either navigate, ask for clarification, or
     * speak a failure message. Called from [executeVoiceCommand] inside a coroutine.
     */
    private suspend fun resolveAndNavigate(rawDestination: String, isMuted: Boolean) {
        Log.d(DEST_TAG, "resolveAndNavigate: raw='$rawDestination'")
        val savedPlaces = savedPlacesRepository.getAll()

        // Read the continuously-updated cached fix. getCurrentLocation() now checks
        // LocationProvider.cachedLocation first, so this returns immediately when
        // startLocationUpdates() has already received a fix — no blocking GPS call.
        // All three values degrade to null gracefully when no fix is available yet.
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
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Rasta: ${resolution.query}"
                if (!isMuted) ttsManager.speak("Keliaujame į ${resolution.query}.")
                _pendingNavAction.value = VoiceNavAction.StartNavigation(resolution.query)
                scheduleStatusClear(5_000)
            }

            is DestinationResolution.PlaceSearch -> {
                Log.d(DEST_TAG, "PlaceSearch: '${resolution.query}'")
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Kentas ieško: ${resolution.query}"
                // PlaceSearch query is forwarded to the existing geocoding flow.
                // GoogleNavigationEngine.resolveAddress() handles the actual lookup.
                _pendingNavAction.value = VoiceNavAction.StartNavigation(resolution.query)
                scheduleStatusClear(5_000)
            }

            is DestinationResolution.SavedPlace -> {
                Log.d(DEST_TAG, "SavedPlace: '${resolution.name}' → '${resolution.address}'")
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
                // Status cleared when user picks or cancels.
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

    private fun acceptCandidate(candidate: CandidatePlace, isMuted: Boolean) {
        Log.d(DEST_TAG, "acceptCandidate: '${candidate.name}' → '${candidate.address}'")
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
}
