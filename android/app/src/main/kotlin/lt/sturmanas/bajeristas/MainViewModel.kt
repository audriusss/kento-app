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
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.personality.SessionConfig
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
 * Owns all audio-output ([TtsManager]) and audio-input ([SpeechRecognitionManager])
 * resources that must not be destroyed and recreated on configuration changes.
 *
 * ## Voice command flow
 *
 * 1. User presses mic → composable calls [onMicPressed] (after RECORD_AUDIO check).
 * 2. [SpeechRecognitionManager] recognizes speech and calls back into this VM.
 * 3. VM stores result in [pendingRecognizedText].
 * 4. [SturmanasApp] LaunchedEffect observes [pendingRecognizedText] and calls
 *    [executeVoiceCommand], passing the current [NavigationState] and [SessionConfig].
 * 5. Non-navigation commands (distance, time, destination, repeat, general question)
 *    are handled entirely here — no composable involvement.
 * 6. Navigation-level actions (start/stop nav, mute/unmute) are emitted via
 *    [pendingNavAction] and consumed by [SturmanasApp] through a second LaunchedEffect.
 *
 * ## TTS / microphone coordination
 *
 * [onMicPressed] stops TTS before starting the recognizer, preventing Kentas's own
 * voice from being transcribed.  While [voiceListeningState] is LISTENING, the
 * composable disables outgoing TTS announcements via the [isSpeechBlocked] flag.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "KentasVoice"
    }

    // ── Audio output ──────────────────────────────────────────────────────

    /** Lithuanian text-to-speech. Accessed directly by [SturmanasApp] for maneuver TTS. */
    val ttsManager: TtsManager = TtsManager(application).also { it.initialize() }

    // ── Audio input ───────────────────────────────────────────────────────

    val speechRecognitionManager = SpeechRecognitionManager(application)

    // ── Voice state ───────────────────────────────────────────────────────

    private val _voiceListeningState = MutableStateFlow(VoiceListeningState.IDLE)
    /** Drives the [MicButton] visual and blocks maneuver TTS while LISTENING. */
    val voiceListeningState: StateFlow<VoiceListeningState> = _voiceListeningState.asStateFlow()

    private val _voiceStatusText = MutableStateFlow("")
    /** "Kentas klauso…" / "Išgirdau: …" / error messages. Displayed below the mic button. */
    val voiceStatusText: StateFlow<String> = _voiceStatusText.asStateFlow()

    // ── Pending recognized text ───────────────────────────────────────────

    /**
     * Non-null when the recognizer has a final result ready to process.
     *
     * [SturmanasApp] observes this; when non-null it calls [executeVoiceCommand]
     * passing the current [NavigationState] and [SessionConfig], then calls
     * [clearPendingRecognizedText].  This two-step design lets the VM own command
     * execution while the composable provides the nav state it cannot see directly.
     */
    private val _pendingRecognizedText = MutableStateFlow<String?>(null)
    val pendingRecognizedText: StateFlow<String?> = _pendingRecognizedText.asStateFlow()

    // ── Pending navigation action ─────────────────────────────────────────

    /**
     * Non-null when a voice command requires a composable-level state change
     * (isNavigating, isMuted).  [SturmanasApp] observes this, acts on it via
     * its existing lambdas, then calls [clearPendingNavAction].
     */
    private val _pendingNavAction = MutableStateFlow<VoiceNavAction?>(null)
    val pendingNavAction: StateFlow<VoiceNavAction?> = _pendingNavAction.asStateFlow()

    // ── Latest spoken instruction (for RepeatInstruction command) ─────────

    /**
     * The last maneuver instruction spoken by Kentas.
     * Updated via [recordSpokenInstruction] from the maneuver LaunchedEffect
     * in [SturmanasApp] just before [ttsManager].speak is called.
     */
    private var latestInstruction: String = ""

    // ── True while microphone is listening ────────────────────────────────

    /** True while LISTENING — blocks new TTS maneuver announcements that would feed back. */
    val isSpeechBlocked: Boolean
        get() = _voiceListeningState.value == VoiceListeningState.LISTENING

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        speechRecognitionManager.initialize()
        setupRecognitionCallbacks()
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
            // Only revert to IDLE if we haven't already moved to PROCESSING.
            if (_voiceListeningState.value == VoiceListeningState.LISTENING) {
                _voiceListeningState.value = VoiceListeningState.IDLE
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Called when the user taps the mic button.
     *
     * Stops TTS first — prevents Kentas from recognising its own speech.
     * Then starts the [SpeechRecognitionManager].
     *
     * Must be called on the Main thread (SpeechRecognizer requirement).
     * The composable checks RECORD_AUDIO permission before calling this.
     */
    fun onMicPressed() {
        Log.d("KentasFlow", "mic button pressed — stopping TTS, starting SR")
        ttsManager.stop()
        Log.d(TAG, "TTS stopped before listening")
        speechRecognitionManager.startListening()
    }

    /**
     * Parse [text] (final SR output) and execute the resulting [VoiceCommand].
     *
     * Non-navigation commands are handled entirely here (TTS response).
     * Navigation commands emit a [VoiceNavAction] via [pendingNavAction].
     *
     * Call [clearPendingRecognizedText] immediately after calling this.
     *
     * @param text          Recognized Lithuanian text from [SpeechRecognitionManager].
     * @param navState      Current navigation state — read for distance/time/destination.
     * @param sessionConfig Current session config — passed to OpenAI for persona.
     * @param isMuted       If true, do not speak TTS responses for this command.
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
                Log.d("KentasFlow", "command execution: RemainingDistance (${navState.remainingDistanceMeters} m)")
                val msg = buildDistanceResponse(navState.remainingDistanceMeters)
                Log.d("KentasFlow", "command result: '$msg'")
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.RemainingTime -> {
                Log.d("KentasFlow", "command execution: RemainingTime (${navState.remainingDurationSeconds} s)")
                val msg = buildTimeResponse(navState.remainingDurationSeconds)
                Log.d("KentasFlow", "command result: '$msg'")
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.DestinationInfo -> {
                Log.d("KentasFlow", "command execution: DestinationInfo")
                val dest = navState.resolvedAddress.ifBlank { navState.destinationName }
                val msg = if (dest.isBlank()) "Šiuo metu maršrutas nepasirinktas."
                          else "Važiuojame į $dest."
                Log.d("KentasFlow", "command result: '$msg'")
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.RepeatInstruction -> {
                Log.d("KentasFlow", "command execution: RepeatInstruction latestInstruction='$latestInstruction'")
                val msg = if (latestInstruction.isBlank())
                    "Dar neturiu nurodymo, kurį galėčiau pakartoti."
                else
                    latestInstruction
                Log.d("KentasFlow", "command result: '$msg'")
                speakAndIdle(msg, isMuted)
            }

            is VoiceCommand.MuteVoice -> {
                Log.d("KentasFlow", "command execution: MuteVoice")
                ttsManager.stop()
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = ""
                // Do not speak confirmation — that would contradict muting.
                _pendingNavAction.value = VoiceNavAction.Mute
                return
            }

            is VoiceCommand.UnmuteVoice -> {
                Log.d("KentasFlow", "command execution: UnmuteVoice")
                _pendingNavAction.value = VoiceNavAction.Unmute
                // Speak confirmation after the composable has cleared isMuted (~1 recompose cycle).
                viewModelScope.launch {
                    delay(150)
                    Log.d("KentasFlow", "command result: Balsas įjungtas.")
                    speakAndIdle("Balsas įjungtas.", isMuted = false)
                }
                return
            }

            is VoiceCommand.StopNavigation -> {
                Log.d("KentasFlow", "command execution: StopNavigation")
                speakAndIdle("Navigacija sustabdyta.", isMuted)
                _pendingNavAction.value = VoiceNavAction.StopNavigation
                return
            }

            is VoiceCommand.StartNavigation -> {
                Log.d("KentasFlow", "command execution: StartNavigation dest='${command.destination}'")
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = ""
                _pendingNavAction.value = VoiceNavAction.StartNavigation(command.destination)
                return
            }

            is VoiceCommand.GeneralQuestion -> {
                Log.d("KentasFlow", "command execution: GeneralQuestion → OpenAI '${command.text}'")
                _voiceListeningState.value = VoiceListeningState.PROCESSING
                viewModelScope.launch {
                    val reply = askKentas(
                        userText = command.text,
                        config = sessionConfig,
                        navState = navState,
                        apiKey = BuildConfig.OPENAI_API_KEY,
                    )
                    Log.d("KentasFlow", "command result: OpenAI='$reply'")
                    _voiceStatusText.value = reply
                    _voiceListeningState.value = VoiceListeningState.IDLE
                    if (!isMuted) ttsManager.speak(reply)
                    scheduleStatusClear(6_000)
                }
                return  // coroutine handles status clear
            }

            is VoiceCommand.Unknown -> {
                Log.d("KentasFlow", "command execution: Unknown '${command.text}'")
                _voiceListeningState.value = VoiceListeningState.IDLE
                _voiceStatusText.value = "Komandos neišgirdau."
            }
        }

        scheduleStatusClear(4_000)
    }

    /**
     * Store the instruction that was just spoken, for [VoiceCommand.RepeatInstruction].
     * Called from [SturmanasApp]'s maneuver announcement [LaunchedEffect] before speaking.
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
        speechRecognitionManager.release()
        ttsManager.release()
    }

    // ── Private helpers ───────────────────────────────────────────────────

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
        n % 10 == 1 && n % 100 != 11                    -> "kilometras"
        n % 10 in 2..9 && n % 100 !in 11..19            -> "kilometrai"
        else                                              -> "kilometrų"
    }

    private fun minutesForm(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11                    -> "minutės"
        n % 10 in 2..9 && n % 100 !in 11..19            -> "minučių"
        else                                              -> "minučių"
    }
}
