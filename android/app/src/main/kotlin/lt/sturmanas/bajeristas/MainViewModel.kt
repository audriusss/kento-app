package lt.sturmanas.bajeristas

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.voice.RecoveryPolicy
import lt.sturmanas.bajeristas.navigation.CandidatePlace
import lt.sturmanas.bajeristas.navigation.distanceSpeech
import lt.sturmanas.bajeristas.navigation.minuteSpeech
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
import lt.sturmanas.bajeristas.voice.VoiceSessionState
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
        private const val TAG            = "KentasVoice"
        private const val DEST_TAG       = "KentasDestination"
        private const val WP_TAG         = "KentasWaypoint"
        private const val LIFECYCLE_TAG  = "KentasSpeechLifecycle"
        private const val STABILITY_TAG  = "KentasVoiceStability"
        /** Maximum consecutive recoverable SR errors before the session loop stops itself. */
        internal const val MAX_SESSION_RETRIES = 3
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

    // ── Continuous voice session ───────────────────────────────────────────

    /**
     * True while hands-free continuous mode is on.
     * Stays true through every recognizer restart, TTS playback, timeout, and recovery.
     * Only goes false on: user toggle off, explicit stop-listening command, fatal error,
     * MAX_SESSION_RETRIES exhausted, or permission loss.
     *
     * Drives the mic button active ring and "Pokalbis leidžiamas" indicator.
     * Use this — not [_sessionState] or [_voiceListeningState] — to determine if the
     * user has continuous mode on.
     */
    private val _continuousModeEnabled = MutableStateFlow(false)
    val continuousModeEnabled: StateFlow<Boolean> = _continuousModeEnabled.asStateFlow()

    private val _sessionState = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
    /**
     * Current phase of the voice session loop. Drives the [MicButton] status text
     * when a continuous session is active.
     */
    val sessionState: StateFlow<VoiceSessionState> = _sessionState.asStateFlow()

    /** Retry counter — reset to 0 each time a new continuous session starts. */
    @Volatile private var sessionRetryCount = 0

    /**
     * The single pending restart job.
     * MUST be cancelled before scheduling a new one — this is the primary defence
     * against multiple overlapping [SpeechRecognitionManager.startListening] calls.
     */
    private var pendingRestartJob: Job? = null

    /**
     * Delays displaying the "Atkuriamas balso atpažinimas…" text for non-silent errors.
     * Cancelled in [scheduleOneRestart] if recovery completes within 400 ms, keeping
     * brief glitch recoveries invisible to the user.
     */
    private var recoveringTextJob: Job? = null

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
        // TTS completion → single restart path. 500ms post-TTS gap prevents the
        // recognizer from picking up reverberation from the speaker.
        ttsManager.onDone = {
            Log.d(LIFECYCLE_TAG,
                "TTS onDone: sessionActive=${_continuousModeEnabled.value}")
            if (_continuousModeEnabled.value) {
                scheduleOneRestart(500L)
            }
        }
    }

    // ── Location caching ──────────────────────────────────────────────────

    /**
     * Starts (or re-starts) continuous location updates so [resolveAndNavigate] can
     * read a cached fix immediately, even on the very first voice command after a
     * cold launch.
     *
     * Safe to call multiple times — [LocationProvider.startUpdates] removes any
     * existing listener before registering a new one, so this is idempotent.
     *
     * Called from [init] and from [MainActivity] whenever the location permission
     * transitions from denied → granted (either via the system dialog or after the
     * user grants it in Settings and returns to the app).
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

    private fun setupRecognitionCallbacks() {

        // Fired immediately when startListening() is called (before onReadyForSpeech).
        // UI shows "Įjungiamas klausymas…" — not "Kentas klauso…".
        speechRecognitionManager.onStartRequested = {
            val gen = speechRecognitionManager.generation
            Log.d(LIFECYCLE_TAG, "SR: START_REQUESTED gen=$gen")
            Log.d(STABILITY_TAG, "state=Starting modeEnabled=${_continuousModeEnabled.value} gen=$gen")
            _sessionState.value = VoiceSessionState.Starting
            // Do NOT write IDLE here — in continuous mode the mic must stay visually active
            // (LISTENING) through restart cycles. IDLE is only written in single-press mode
            // (continuousModeEnabled=false) where the mic isn't active yet.
            if (!_continuousModeEnabled.value) {
                _voiceListeningState.value = VoiceListeningState.IDLE
            }
            _voiceStatusText.value = "Įjungiamas klausymas…"
        }

        // Fired on onReadyForSpeech — the recognizer is genuinely active.
        // ONLY now may the UI show "Kentas klauso…".
        speechRecognitionManager.onReadyForSpeech = {
            Log.d(LIFECYCLE_TAG, "SR: LISTENING_READY gen=${speechRecognitionManager.generation}")
            _sessionState.value = VoiceSessionState.ListeningReady
            _voiceListeningState.value = VoiceListeningState.LISTENING
            _voiceStatusText.value = "Kentas klauso…"
            // A successful onReadyForSpeech counts as session established → reset retries.
            sessionRetryCount = 0
        }

        speechRecognitionManager.onBeginningOfSpeech = {
            Log.d(LIFECYCLE_TAG, "SR: USER_SPEAKING gen=${speechRecognitionManager.generation}")
            Log.d(STABILITY_TAG, "state=UserSpeaking modeEnabled=${_continuousModeEnabled.value}")
            _sessionState.value = VoiceSessionState.UserSpeaking
            _voiceStatusText.value = "Girdžiu…"
        }

        speechRecognitionManager.onPartialResult = { partial ->
            Log.d(LIFECYCLE_TAG, "SR: PARTIAL='$partial'")
            _voiceStatusText.value = "Klausau: $partial…"
        }

        speechRecognitionManager.onResult = { text ->
            Log.d(LIFECYCLE_TAG,
                "SR: RESULT='$text' gen=${speechRecognitionManager.generation} " +
                "retries=$sessionRetryCount")
            _sessionState.value = VoiceSessionState.Processing
            _voiceListeningState.value = VoiceListeningState.PROCESSING
            _voiceStatusText.value = "Išgirdau: $text"
            _pendingRecognizedText.value = text
            // Session produced a result → reset retry counter.
            sessionRetryCount = 0
        }

        speechRecognitionManager.onError = { msg ->
            // Reached only when onRecoverableError / onFatalError are not set
            // (e.g. during single-press mode outside the continuous loop).
            Log.e(LIFECYCLE_TAG, "SR: ERROR (legacy) '$msg'")
            _voiceListeningState.value = VoiceListeningState.ERROR
            _voiceStatusText.value = msg
            scheduleStatusClear(4_000)
        }

        speechRecognitionManager.onListeningStopped = {
            Log.d(LIFECYCLE_TAG, "SR: LISTENING_STOPPED")
            Log.d(STABILITY_TAG, "LISTENING_STOPPED modeEnabled=${_continuousModeEnabled.value} listState=${_voiceListeningState.value}")
            // In continuous mode a stopped cycle is immediately followed by a restart —
            // keep the mic visually active (LISTENING) so the UI does not flicker.
            if (!_continuousModeEnabled.value &&
                _voiceListeningState.value == VoiceListeningState.LISTENING) {
                _voiceListeningState.value = VoiceListeningState.IDLE
            }
        }

        speechRecognitionManager.onRecoverableError = recoverableError@{ errorCode ->
            val name    = RecoveryPolicy.errorName(errorCode)
            val silent  = RecoveryPolicy.isSilentRecovery(errorCode)
            val delayMs = RecoveryPolicy.delayMs(errorCode)
            Log.w(LIFECYCLE_TAG,
                "SR: RECOVERABLE_ERROR code=$errorCode ($name) silent=$silent " +
                "retries=$sessionRetryCount modeEnabled=${_continuousModeEnabled.value}")
            Log.d(STABILITY_TAG,
                "RECOVERABLE_ERROR code=$errorCode name=$name silent=$silent " +
                "modeEnabled=${_continuousModeEnabled.value} listState=${_voiceListeningState.value}")

            if (!_continuousModeEnabled.value) {
                // Single-press mode — drop to IDLE, show brief message, no retry.
                _voiceListeningState.value = VoiceListeningState.IDLE
                val msg = if (silent) "Nieko neišgirdau. Pabandykite dar kartą."
                          else RecoveryPolicy.statusText(errorCode)
                _voiceStatusText.value = msg
                scheduleStatusClear(3_000)
                return@recoverableError
            }

            // ── Continuous mode ────────────────────────────────────────────────────
            // The mic stays visually active (LISTENING) — do NOT write IDLE here.
            // Silent recoveries (NO_MATCH, SPEECH_TIMEOUT) are completely invisible:
            // no state change, no status text, no TTS — user sees "Laukiu komandos…"
            // via RestartDelay set inside scheduleOneRestart().

            if (sessionRetryCount < MAX_SESSION_RETRIES) {
                sessionRetryCount++

                if (silent) {
                    // NO_MATCH / SPEECH_TIMEOUT — normal in continuous mode.
                    // Phase 4: invisible to the user. scheduleOneRestart sets RestartDelay.
                    Log.d(LIFECYCLE_TAG,
                        "SR: silent timeout — retry $sessionRetryCount/$MAX_SESSION_RETRIES in ${delayMs}ms")
                    scheduleOneRestart(delayMs)
                } else {
                    // Real error (server disconnect, audio, etc.) — show Recovering state,
                    // but debounce the status text by 400 ms so brief glitches are invisible.
                    _sessionState.value = VoiceSessionState.Recovering
                    recoveringTextJob?.cancel()
                    recoveringTextJob = viewModelScope.launch {
                        delay(400)
                        if (_sessionState.value is VoiceSessionState.Recovering) {
                            _voiceStatusText.value = "Atkuriamas balso atpažinimas…"
                        }
                    }
                    Log.d(LIFECYCLE_TAG,
                        "SR: retry $sessionRetryCount/$MAX_SESSION_RETRIES in ${delayMs}ms")
                    scheduleOneRestart(delayMs)
                }
            } else {
                val errMsg = "Balso atpažinimas laikinai neveikia. Paliesk mikrofoną, kad bandytum dar kartą."
                Log.w(LIFECYCLE_TAG,
                    "SR: MAX_RETRIES reached ($MAX_SESSION_RETRIES) — stopping session")
                Log.d(STABILITY_TAG, "MAX_RETRIES → stopping modeEnabled=false")
                _sessionState.value = VoiceSessionState.Error(message = errMsg, isFatal = false)
                stopContinuousSessionInternal()
                _voiceListeningState.value = VoiceListeningState.ERROR
                _voiceStatusText.value = errMsg
                scheduleStatusClear(6_000)
            }
        }

        speechRecognitionManager.onFatalError = { msg ->
            Log.e(LIFECYCLE_TAG, "SR: FATAL_ERROR '$msg'")
            stopContinuousSessionInternal()
            _voiceListeningState.value = VoiceListeningState.ERROR
            _sessionState.value = VoiceSessionState.Error(message = msg, isFatal = true)
            _voiceStatusText.value = msg
            scheduleStatusClear(5_000)
        }
    }

    // ── Public API — Mic / Session ────────────────────────────────────────

    /**
     * Toggle the continuous voice session loop on or off.
     *
     * - If no session is running: starts hands-free mode — TTS responses automatically
     *   restart the listening window so the user never has to tap again.
     * - If a session is running: stops it cleanly (does NOT stop navigation).
     *
     * Called from the composable `onMicPress()` after RECORD_AUDIO permission is confirmed.
     * Must be called on the Main thread (SpeechRecognizer requirement).
     */
    fun toggleSession() {
        Log.d("KentasFlow", "toggleSession: modeEnabled=${_continuousModeEnabled.value}")
        Log.d(STABILITY_TAG, "toggleSession modeEnabled=${_continuousModeEnabled.value} → ${!_continuousModeEnabled.value}")
        if (_continuousModeEnabled.value) {
            stopContinuousSession()
        } else {
            startContinuousSession()
        }
    }

    /**
     * Single-press mic: start one listening session without the automatic restart loop.
     * Kept for backward compatibility and for use by the RECORD_AUDIO permission grant
     * callback path in MainActivity. Calls [toggleSession] so that the session can still
     * be stopped with a second tap.
     *
     * Must be called on the Main thread.
     */
    fun onMicPressed() {
        Log.d("KentasFlow", "mic button pressed — stopping TTS, starting SR")
        toggleSession()
    }

    private fun startContinuousSession() {
        Log.d(LIFECYCLE_TAG, "startContinuousSession: beginning session loop")
        Log.d(STABILITY_TAG, "MODE_ENABLED → true; mic=LISTENING immediately")
        pendingRestartJob?.cancel()
        pendingRestartJob = null
        recoveringTextJob?.cancel()
        recoveringTextJob = null
        sessionRetryCount = 0
        _continuousModeEnabled.value = true
        // Set LISTENING immediately so the mic button goes red+active before the recognizer
        // finishes initialising. Prevents a brief IDLE flash at the start of every session.
        _voiceListeningState.value = VoiceListeningState.LISTENING
        ttsManager.stop()
        speechRecognitionManager.startListening()   // onStartRequested → Starting state
    }

    /**
     * Stop the continuous voice session loop without stopping navigation.
     * Safe to call from any context; cancels any pending restart job first.
     */
    fun stopContinuousSession() {
        Log.d(LIFECYCLE_TAG, "stopContinuousSession: user-initiated stop")
        stopContinuousSessionInternal()
    }

    /**
     * Internal stop — used by error handlers and [stopContinuousSession].
     * Cancels pending restart job, cancels the recognizer, and resets state.
     */
    private fun stopContinuousSessionInternal() {
        recoveringTextJob?.cancel()
        recoveringTextJob = null
        pendingRestartJob?.cancel()
        pendingRestartJob = null
        _continuousModeEnabled.value = false
        _sessionState.value = VoiceSessionState.Idle
        speechRecognitionManager.cancel()
        _voiceListeningState.value = VoiceListeningState.IDLE
        _voiceStatusText.value = ""
        Log.d(LIFECYCLE_TAG, "stopContinuousSessionInternal: session stopped")
        Log.d(STABILITY_TAG, "MODE_ENABLED → false; mic=IDLE")
    }

    /**
     * The SINGLE restart entry point for the continuous session loop.
     *
     * Cancels any previously scheduled restart job before scheduling a new one.
     * This is the primary guard against overlapping [SpeechRecognitionManager.startListening]
     * calls — the root cause of "stuck listening" and race-condition errors.
     *
     * Callers:
     * - [ttsManager.onDone] — after TTS completes
     * - [onRecoverableError] — after a recoverable SR error
     * - [notifyCommandDone] — when a command produces no TTS (muted / silent command)
     */
    private fun scheduleOneRestart(delayMs: Long) {
        if (!_continuousModeEnabled.value) return
        recoveringTextJob?.cancel()   // fast recovery — suppress "Atkuriamas…" text
        recoveringTextJob = null
        pendingRestartJob?.cancel()
        Log.d(LIFECYCLE_TAG, "scheduleOneRestart: delay=${delayMs}ms gen=${speechRecognitionManager.generation}")
        Log.d(STABILITY_TAG, "scheduleOneRestart delay=${delayMs}ms gen=${speechRecognitionManager.generation} modeEnabled=${_continuousModeEnabled.value}")
        _sessionState.value = VoiceSessionState.RestartDelay
        _voiceStatusText.value = "Laukiu komandos…"
        pendingRestartJob = viewModelScope.launch {
            delay(delayMs)
            pendingRestartJob = null
            if (!_continuousModeEnabled.value) {
                Log.d(LIFECYCLE_TAG, "scheduleOneRestart: session stopped during delay — abort")
                return@launch
            }
            // If TTS started speaking during the delay (e.g. a maneuver announcement),
            // do NOT interrupt it. onDone will schedule the next restart.
            if (ttsManager.isSpeaking) {
                Log.d(LIFECYCLE_TAG, "scheduleOneRestart: TTS speaking during delay — defer to onDone")
                return@launch
            }
            Log.d(LIFECYCLE_TAG, "scheduleOneRestart: firing startListening")
            Log.d(STABILITY_TAG, "scheduleOneRestart: startListening gen=${speechRecognitionManager.generation + 1}")
            ttsManager.stop()
            speechRecognitionManager.startListening()
        }
    }

    /**
     * Called at the end of every command execution path.
     *
     * - If TTS is speaking: [ttsManager.onDone] will call [scheduleOneRestart] — nothing to do.
     * - If TTS is not speaking (command was silent, or [isMuted]=true): schedule a restart now.
     *
     * This replaces the previous [scheduleRestartIfSessionActive] + [scheduleSessionRestart]
     * dual-path design that allowed two restarts to fire for the same command.
     */
    private fun notifyCommandDone() {
        if (!_continuousModeEnabled.value) return
        if (ttsManager.isSpeaking) {
            // TTS is playing — onDone is the restart trigger. Do NOT also schedule here.
            Log.d(LIFECYCLE_TAG, "notifyCommandDone: TTS speaking → defer to onDone")
            return
        }
        Log.d(LIFECYCLE_TAG, "notifyCommandDone: silent command → scheduleOneRestart(300)")
        scheduleOneRestart(300L)
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
                // In continuous mode keep the mic visually active; IDLE only in single-press.
                if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = ""
                _pendingNavAction.value = VoiceNavAction.Mute
                notifyCommandDone()   // no TTS produced — silent-path restart
                return
            }

            is VoiceCommand.UnmuteVoice -> {
                Log.d("KentasFlow", "command: UnmuteVoice")
                _pendingNavAction.value = VoiceNavAction.Unmute
                viewModelScope.launch {
                    delay(150)
                    speakAndIdle("Balsas įjungtas.", isMuted = false)
                    // onDone handles restart when TTS plays; notifyCommandDone() handles
                    // disabled-TTS / failed-utterance fallback (isSpeaking=false at that point).
                    notifyCommandDone()
                }
                return
            }

            is VoiceCommand.StopListening -> {
                Log.d("KentasFlow", "command: StopListening")
                // Stop the session loop but do NOT stop navigation.
                stopContinuousSession()
                _voiceStatusText.value = "Klausymasis sustabdytas."
                scheduleStatusClear(3_000)
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
                // acceptCandidate / speakAndIdle may or may not speak; guard both paths.
                notifyCommandDone()
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
                    if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
                    if (!isMuted) ttsManager.speak(reply)
                    scheduleStatusClear(6_000)
                    notifyCommandDone()
                }
                return
            }

            is VoiceCommand.Unknown -> {
                Log.d("KentasFlow", "command: Unknown '${command.text}'")
                if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Komandos neišgirdau."
            }
        }

        scheduleStatusClear(4_000)
        // Covers all synchronous commands that fall through here (Distance, Time,
        // DestinationInfo, Repeat, waypoint management, Unknown).
        notifyCommandDone()
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
        if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
        _voiceStatusText.value = ""
        // Resume the continuous loop if active; otherwise this is a no-op.
        notifyCommandDone()
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
        recoveringTextJob?.cancel()
        recoveringTextJob = null
        pendingRestartJob?.cancel()
        pendingRestartJob = null
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
                if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Rasta: ${resolution.query}"
                if (!isMuted) ttsManager.speak("Keliaujame į ${resolution.query}.")
                _pendingNavAction.value = VoiceNavAction.StartNavigation(resolution.query)
                scheduleStatusClear(5_000)
            }

            is DestinationResolution.PlaceSearch -> {
                Log.d(DEST_TAG, "PlaceSearch: '${resolution.query}'")
                waypointManager.setFinalDestination(StopoverEntry(rawDestination, resolution.query))
                _finalDestinationName.value = rawDestination
                if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Kentas ieško: ${resolution.query}"
                _pendingNavAction.value = VoiceNavAction.StartNavigation(resolution.query)
                scheduleStatusClear(5_000)
            }

            is DestinationResolution.SavedPlace -> {
                Log.d(DEST_TAG, "SavedPlace: '${resolution.name}' → '${resolution.address}'")
                waypointManager.setFinalDestination(StopoverEntry(resolution.name, resolution.address))
                _finalDestinationName.value = resolution.name
                if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Rasta: ${resolution.name}"
                if (!isMuted) ttsManager.speak("Keliaujame ${resolution.name.lowercase()}.")
                _pendingNavAction.value = VoiceNavAction.StartNavigation(resolution.address)
                scheduleStatusClear(5_000)
            }

            is DestinationResolution.NeedsClarification -> {
                Log.d(DEST_TAG, "NeedsClarification: ${resolution.suggestions.size} candidates")
                if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
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
        notifyCommandDone()
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
        if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE

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
            notifyCommandDone()
            return
        }

        val added = waypointManager.addStopover(entry)
        if (!added) {
            val msg = "Šis sustojimas jau yra maršrute."
            _voiceStatusText.value = msg
            if (!isMuted) ttsManager.speak(msg)
            scheduleStatusClear(4_000)
            notifyCommandDone()
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
        notifyCommandDone()
    }

    private fun acceptCandidate(candidate: CandidatePlace, isMuted: Boolean) {
        Log.d(DEST_TAG, "acceptCandidate: '${candidate.name}' → '${candidate.address}'")
        waypointManager.setFinalDestination(StopoverEntry(candidate.name, candidate.address))
        _finalDestinationName.value = candidate.name
        _pendingClarification.value = null
        if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
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
        // In continuous mode the mic stays visually active (LISTENING) while TTS plays.
        if (!_continuousModeEnabled.value) _voiceListeningState.value = VoiceListeningState.IDLE
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

    /**
     * Delegates to [distanceSpeech] — the single authoritative Lithuanian distance
     * formatter. Never says "koma"; rounds to nearest 500 m bracket.
     */
    internal fun buildDistanceResponse(meters: Int): String = distanceSpeech(meters)

    internal fun buildTimeResponse(seconds: Int): String = when {
        seconds <= 0 -> "Atvykimo laikas nežinomas."
        else -> {
            val mins = maxOf(1, (seconds + 30) / 60)   // round to nearest minute, min 1
            "Kelionė truks ${minuteSpeech(mins)}."
        }
    }

    internal fun sustojimasForm(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11         -> "sustojimas"
        n % 10 in 2..9 && n % 100 !in 11..19 -> "sustojimai"
        else                                   -> "sustojimų"
    }
}
