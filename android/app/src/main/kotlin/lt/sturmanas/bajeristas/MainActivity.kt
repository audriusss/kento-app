package lt.sturmanas.bajeristas

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.sturmanas.bajeristas.navigation.GoogleNavigationEngine
import lt.sturmanas.bajeristas.navigation.LocationPermissionHelper
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.MockNavigationEngine
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.personality.SessionConfig
import lt.sturmanas.bajeristas.personality.formatDistance
import lt.sturmanas.bajeristas.safety.SafetyController
import lt.sturmanas.bajeristas.ui.NavigationScreen
import lt.sturmanas.bajeristas.ui.StartScreen
import lt.sturmanas.bajeristas.ui.theme.SturmanasTheme
import lt.sturmanas.bajeristas.voice.AudioController
import lt.sturmanas.bajeristas.voice.VoiceNavAction
import lt.sturmanas.bajeristas.voice.VoiceSessionController

class MainActivity : ComponentActivity() {

    companion object {
        /** Logcat tag for high-level user-action flow. Filter on this tag to trace the full
         *  address-search and navigation-start journey across all layers. */
        const val FLOW_TAG = "KentasFlow"
    }

    // ── Engine selection ──────────────────────────────────────────────────
    // GoogleNavigationEngine is used when GOOGLE_MAPS_API_KEY is set in local.properties.
    // MockNavigationEngine is the automatic fallback — safe to build and run
    // without any API key. No code change needed to switch; add the key and rebuild.
    private val engine by lazy {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) {
            Log.d(FLOW_TAG, "engine: GoogleNavigationEngine selected (API key present)")
            GoogleNavigationEngine()
        } else {
            Log.d(FLOW_TAG, "engine: MockNavigationEngine selected (no API key)")
            MockNavigationEngine()
        }
    }

    // Survives screen rotation — holds TtsManager and SpeechRecognitionManager.
    private val viewModel: MainViewModel by viewModels()

    private val navigationController by lazy { NavigationController(engine) }
    private val safetyController = SafetyController()
    private val voiceSessionController = VoiceSessionController()
    private val audioController = AudioController()

    // ── Mutable state observable by Compose ───────────────────────────────
    private val engineReady = mutableStateOf(false)
    private val engineError = mutableStateOf<String?>(null)
    private val permissionState = mutableStateOf<PermissionState>(PermissionState.Checking)

    // ── Location permission launcher ──────────────────────────────────────
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionState.value = PermissionState.Granted
            initializeNavigation()
        } else {
            permissionState.value = PermissionState.Denied
            engineError.value = "Vietos leidimas atmestas. Atidarykite nustatymus ir suteikite „Šturmanas Bajeristas" prieigą prie vietos."
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(FLOW_TAG, "onCreate")

        if (LocationPermissionHelper.hasLocationPermission(this)) {
            permissionState.value = PermissionState.Granted
            initializeNavigation()
        } else {
            Log.d(FLOW_TAG, "onCreate: location permission missing — requesting")
            locationPermissionLauncher.launch(LocationPermissionHelper.LOCATION_PERMISSION)
        }

        setContent {
            SturmanasTheme {
                SturmanasApp(
                    navigationController = navigationController,
                    safetyController = safetyController,
                    voiceSessionController = voiceSessionController,
                    audioController = audioController,
                    viewModel = viewModel,
                    engineReady = engineReady.value,
                    engineError = engineError.value,
                    permissionDenied = permissionState.value == PermissionState.Denied,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(FLOW_TAG, "onDestroy: releasing all resources")
        audioController.release()
        voiceSessionController.stopSession()
        navigationController.onDestroy()   // full engine teardown — Navigator + NavigationView
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun initializeNavigation() {
        Log.d(FLOW_TAG, "initializeNavigation: engine=${engine::class.simpleName}")
        navigationController.initialize(
            activity = this,
            onReady = {
                Log.d(FLOW_TAG, "engine ready")
                engineReady.value = true
            },
            onError = { msg ->
                Log.e(FLOW_TAG, "engine init error: $msg")
                engineError.value = msg
                engineReady.value = false
            },
        )
    }

    private sealed class PermissionState {
        object Checking : PermissionState()
        object Granted : PermissionState()
        object Denied : PermissionState()
    }
}

// ── Root composable ───────────────────────────────────────────────────────────

@Composable
private fun SturmanasApp(
    navigationController: NavigationController,
    safetyController: SafetyController,
    voiceSessionController: VoiceSessionController,
    audioController: AudioController,
    viewModel: MainViewModel,
    engineReady: Boolean,
    engineError: String?,
    permissionDenied: Boolean,
) {
    val context = LocalContext.current
    val navState by navigationController.state.collectAsStateWithLifecycle()
    var isNavigating by remember { mutableStateOf(false) }
    var sessionConfig by remember { mutableStateOf(SessionConfig()) }
    var isMuted by remember { mutableStateOf(false) }
    var aiStatusMessage by remember { mutableStateOf("") }
    var startScreenError by remember { mutableStateOf<String?>(null) }

    // Voice state from ViewModel
    val voiceListeningState by viewModel.voiceListeningState.collectAsStateWithLifecycle()
    val voiceStatusText by viewModel.voiceStatusText.collectAsStateWithLifecycle()
    val pendingRecognizedText by viewModel.pendingRecognizedText.collectAsStateWithLifecycle()
    val pendingNavAction by viewModel.pendingNavAction.collectAsStateWithLifecycle()

    // Voice status takes priority over generic AI status message in the display.
    val effectiveStatus = voiceStatusText.ifBlank { aiStatusMessage }

    // ── RECORD_AUDIO permission launcher ──────────────────────────────────
    // Requests the mic permission at runtime (API 23+).
    // On grant, immediately starts the SpeechRecognitionManager.
    val audioPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.d(MainActivity.FLOW_TAG, "RECORD_AUDIO permission result: granted=$granted")
        if (granted) {
            viewModel.onMicPressed()
        } else {
            aiStatusMessage = "Norint naudoti balso komandas, reikia suteikti mikrofono leidimą."
        }
    }

    // Helper called by both screens' mic button.
    fun onMicPress() {
        Log.d(MainActivity.FLOW_TAG, "mic press — checking RECORD_AUDIO permission")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onMicPressed()
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── Voice recognition result handler ──────────────────────────────────
    // When SpeechRecognitionManager delivers a final result, pendingRecognizedText
    // becomes non-null. We pass current nav state and session config to the ViewModel
    // so it can execute the command. Execution logic lives in the ViewModel, not here.
    LaunchedEffect(pendingRecognizedText) {
        val text = pendingRecognizedText ?: return@LaunchedEffect
        Log.d(MainActivity.FLOW_TAG, "pendingRecognizedText: '$text' — executing command")
        viewModel.executeVoiceCommand(
            text = text,
            navState = navState,
            sessionConfig = sessionConfig,
            isMuted = isMuted,
        )
        viewModel.clearPendingRecognizedText()
    }

    // ── Voice navigation action handler ───────────────────────────────────
    // ViewModel emits VoiceNavAction for commands that need composable-level state
    // changes (isNavigating, isMuted) that the ViewModel cannot modify directly.
    LaunchedEffect(pendingNavAction) {
        val action = pendingNavAction ?: return@LaunchedEffect
        Log.d(MainActivity.FLOW_TAG, "pendingNavAction: ${action::class.simpleName}")
        when (action) {
            is VoiceNavAction.StopNavigation -> {
                navigationController.stopNavigation()
                voiceSessionController.stopSession()
                audioController.release()
                viewModel.ttsManager.stop()
                isNavigating = false
                isMuted = false
                aiStatusMessage = ""
                startScreenError = null
            }
            is VoiceNavAction.StartNavigation -> {
                startScreenError = null
                aiStatusMessage = ""
                if (!engineReady) {
                    startScreenError = engineError ?: "Navigacija neparuošta. Palaukite…"
                } else {
                    Log.d(MainActivity.FLOW_TAG, "voice StartNavigation: dest='${action.destination}'")
                    isNavigating = true
                    navigationController.startNavigation(
                        context = context,
                        destination = action.destination,
                        onError = { msg ->
                            Log.e(MainActivity.FLOW_TAG, "voice startNavigation onError: $msg")
                            isNavigating = false
                            startScreenError = msg
                            if (!isMuted) {
                                viewModel.ttsManager.speak(
                                    "Nepavyko rasti arba apskaičiuoti maršruto. Patikrinkite adresą."
                                )
                            }
                        },
                    )
                }
            }
            is VoiceNavAction.Mute -> {
                isMuted = true
                aiStatusMessage = "AI nutildytas"
            }
            is VoiceNavAction.Unmute -> {
                isMuted = false
                aiStatusMessage = ""
            }
        }
        viewModel.clearPendingNavAction()
    }

    val permission = safetyController.getPermission(navState)

    // Safety rule — navigation always takes priority over AI audio.
    // TTS is checked independently because AudioController is a Phase 1 stub
    // (isAiPlaying is always false until Phase 3 PCM playback is wired up).
    if (safetyController.shouldInterruptAudio(navState) &&
        (audioController.isAiPlaying || viewModel.ttsManager.isSpeaking)
    ) {
        audioController.interruptAiAudio()
        viewModel.ttsManager.stop()
        aiStatusMessage = "Navigacija perėmė garsą"
    }

    // ── Navigation maneuver announcements ─────────────────────────────────
    // Speak the next maneuver at three closing distances: 500 m, 200 m, 50 m.
    //
    // announcedThresholds is a plain MutableSet (not Compose state) so mutations
    // inside LaunchedEffect do not trigger recomposition — only TTS side-effects.
    val announcedThresholds = remember { mutableSetOf<Int>() }
    var lastManeuverKey by remember { mutableStateOf("") }
    val maneuverKey = "${navState.maneuverType}_${navState.nextRoadName}"

    // Maneuver changed — clear the set so all thresholds can fire again.
    LaunchedEffect(navState.maneuverType, navState.nextRoadName) {
        if (maneuverKey != lastManeuverKey) {
            announcedThresholds.clear()
            lastManeuverKey = maneuverKey
        }
    }

    val maneuverDist = navState.distanceToNextManeuverMeters
        .takeIf { it != Int.MAX_VALUE } ?: 0

    LaunchedEffect(maneuverDist) {
        // Do not speak while mic is listening — prevents feedback loop.
        if (isMuted || !navState.isNavigating || maneuverDist <= 0) return@LaunchedEffect
        if (viewModel.isSpeechBlocked) return@LaunchedEffect
        val threshold = listOf(500, 200, 50).firstOrNull { t ->
            maneuverDist <= t && t !in announcedThresholds
        } ?: return@LaunchedEffect
        announcedThresholds.add(threshold)
        val instruction = buildNavInstruction(navState, maneuverDist)
        if (instruction.isNotBlank()) {
            viewModel.recordSpokenInstruction(instruction)   // store for RepeatInstruction
            viewModel.ttsManager.speak(instruction)
        }
    }

    // ── Route-start TTS confirmation ──────────────────────────────────────
    // Fires once when the navigation phase transitions to NAVIGATING (route ready,
    // guidance started). Uses the resolved address from navState so the spoken name
    // matches what the geocoder returned, not the raw typed string.
    var previousPhase by remember { mutableStateOf(NavigationPhase.IDLE) }
    LaunchedEffect(navState.phase) {
        if (navState.phase == NavigationPhase.NAVIGATING &&
            previousPhase != NavigationPhase.NAVIGATING
        ) {
            if (!isMuted) {
                val dest = navState.resolvedAddress.ifBlank { navState.destinationName }.ifBlank { "tikslą" }
                viewModel.ttsManager.speak("Maršrutas į $dest paruoštas. Pradedame kelionę.")
            }
        }
        previousPhase = navState.phase
    }

    // Propagate navState error back to start screen if navigation is not yet active.
    if (!isNavigating && navState.errorMessage != null) {
        startScreenError = navState.errorMessage
    }

    when {
        !isNavigating -> {
            val displayError = startScreenError
                ?: if (permissionDenied) engineError else null

            StartScreen(
                errorMessage = displayError,
                engineReady = engineReady,
                voiceListeningState = voiceListeningState,
                voiceStatusText = voiceStatusText,
                onMicPress = { onMicPress() },
                onStartNavigation = { destination, config ->
                    Log.d(MainActivity.FLOW_TAG, "navigation button pressed: destination='$destination' engineReady=$engineReady isNavigating=$isNavigating")
                    startScreenError = null
                    aiStatusMessage = ""
                    sessionConfig = config

                    if (!engineReady) {
                        val errMsg = engineError ?: "Navigacija neparuošta. Palaukite…"
                        Log.w(MainActivity.FLOW_TAG, "engine not ready — aborting: $errMsg")
                        startScreenError = errMsg
                        return@StartScreen
                    }

                    Log.d(MainActivity.FLOW_TAG, "isNavigating: false → true (showing NavigationScreen)")
                    isNavigating = true

                    Log.d(MainActivity.FLOW_TAG, "calling navigationController.startNavigation('$destination')")
                    navigationController.startNavigation(
                        context = context,
                        destination = destination,
                        onError = { msg ->
                            Log.e(MainActivity.FLOW_TAG, "startNavigation onError: $msg → isNavigating false")
                            isNavigating = false
                            startScreenError = msg
                            if (!isMuted) {
                                viewModel.ttsManager.speak(
                                    "Nepavyko rasti arba apskaičiuoti maršruto. Patikrinkite adresą."
                                )
                            }
                        },
                    )
                    // Phase 3: voiceSessionController.startSession(PersonaPrompts.systemPrompt(config))
                },
            )
        }

        else -> {
            NavigationScreen(
                navigationState = navState,
                navigationController = navigationController,
                conversationPermission = permission,
                aiStatusMessage = effectiveStatus,
                isMuted = isMuted,
                voiceListeningState = voiceListeningState,
                onMicPress = { onMicPress() },
                onMuteToggle = {
                    isMuted = !isMuted
                    if (isMuted) {
                        audioController.interruptAiAudio()
                        viewModel.ttsManager.stop()
                        aiStatusMessage = "AI nutildytas"
                    } else {
                        aiStatusMessage = ""
                    }
                },
                onEnableStandardVoice = {
                    navigationController.enableStandardVoice()
                    voiceSessionController.stopSession()
                    audioController.interruptAiAudio()
                    isMuted = true
                    aiStatusMessage = "Įprastas navigacijos balsas įjungtas"
                },
                onStopNavigation = {
                    Log.d(MainActivity.FLOW_TAG, "onStopNavigation: user pressed stop → isNavigating false")
                    navigationController.stopNavigation()
                    voiceSessionController.stopSession()
                    audioController.release()
                    viewModel.ttsManager.stop()
                    isNavigating = false
                    isMuted = false
                    aiStatusMessage = ""
                    startScreenError = null
                },
            )
        }
    }
}

// ── Navigation instruction builder ────────────────────────────────────────────

/**
 * Builds a spoken Lithuanian instruction for an upcoming maneuver in [navState].
 *
 * Returns an empty string for passive maneuvers (NONE, STRAIGHT, UNKNOWN) — no
 * announcement is needed when the driver just continues straight ahead.
 *
 * @param distanceMeters Actual distance to the maneuver at the moment of the call.
 *   Used to choose between "Dabar" (≤ 50 m) and "Po [distance]" phrasing.
 */
private fun buildNavInstruction(navState: NavigationState, distanceMeters: Int): String {
    val action = when (navState.maneuverType) {
        ManeuverType.TURN_LEFT        -> "sukite kairėn"
        ManeuverType.TURN_RIGHT       -> "sukite dešinėn"
        ManeuverType.SLIGHT_LEFT      -> "šiek tiek kairėn"
        ManeuverType.SLIGHT_RIGHT     -> "šiek tiek dešinėn"
        ManeuverType.SHARP_LEFT       -> "staigiai kairėn"
        ManeuverType.SHARP_RIGHT      -> "staigiai dešinėn"
        ManeuverType.UTURN            -> "apsisukite"
        ManeuverType.ROUNDABOUT       -> "įvažiuokite į žiedą"
        ManeuverType.MOTORWAY_EXIT    -> "važiuokite į išvažiavimą"
        ManeuverType.ARRIVE           -> return "Atvykote į tikslą!"
        ManeuverType.NONE,
        ManeuverType.STRAIGHT,
        ManeuverType.UNKNOWN,
        ManeuverType.LANE_CHANGE,
        ManeuverType.COMPLEX_JUNCTION,
        ManeuverType.MERGE,
        ManeuverType.FORK             -> return ""
    }

    val road = navState.nextRoadName.ifBlank { navState.currentRoadName }
    val roadSuffix = if (road.isNotBlank()) " į $road" else ""

    return if (distanceMeters <= 50) {
        "Dabar $action$roadSuffix."
    } else {
        "Po ${formatDistance(distanceMeters)} $action$roadSuffix."
    }
}
