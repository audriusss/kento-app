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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import lt.sturmanas.bajeristas.ui.SettingsScreen
import lt.sturmanas.bajeristas.ui.StartScreen
import lt.sturmanas.bajeristas.ui.theme.SturmanasTheme
import lt.sturmanas.bajeristas.voice.AudioController
import lt.sturmanas.bajeristas.voice.ClarificationState
import lt.sturmanas.bajeristas.voice.VoiceListeningState
import lt.sturmanas.bajeristas.voice.VoiceNavAction
import lt.sturmanas.bajeristas.voice.VoiceSessionController

class MainActivity : ComponentActivity() {

    companion object {
        /** Logcat tag for high-level user-action flow. */
        const val FLOW_TAG = "KentasFlow"
    }

    private val engine by lazy {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) {
            Log.d(FLOW_TAG, "engine: GoogleNavigationEngine selected")
            GoogleNavigationEngine()
        } else {
            Log.d(FLOW_TAG, "engine: MockNavigationEngine selected (no API key)")
            MockNavigationEngine()
        }
    }

    private val viewModel: MainViewModel by viewModels()

    private val navigationController by lazy { NavigationController(engine) }
    private val safetyController     = SafetyController()
    private val voiceSessionController = VoiceSessionController()
    private val audioController      = AudioController()

    private val engineReady      = mutableStateOf(false)
    private val engineError      = mutableStateOf<String?>(null)
    private val permissionState  = mutableStateOf<PermissionState>(PermissionState.Checking)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionState.value = PermissionState.Granted
            // Re-subscribe to location updates now that permission is available.
            // startLocationUpdates() was called in MainViewModel.init but silently
            // failed (SecurityException) because the permission was not yet granted.
            viewModel.retryLocationUpdates()
            initializeNavigation()
        } else {
            permissionState.value = PermissionState.Denied
            engineError.value =
                "Vietos leidimas atmestas. Atidarykite nustatymus ir suteikite programai Šturmanas Bajeristas prieigą prie vietos."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(FLOW_TAG, "onCreate")

        // Wire VoiceSessionController → ViewModel so existing stop call sites
        // (onDestroy, StopNavigation, onEnableStandardVoice) still stop the
        // continuous SR session loop.
        voiceSessionController.setStopCallback { viewModel.stopContinuousSession() }

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
                    navigationController  = navigationController,
                    safetyController      = safetyController,
                    voiceSessionController = voiceSessionController,
                    audioController       = audioController,
                    viewModel             = viewModel,
                    engineReady           = engineReady.value,
                    engineError           = engineError.value,
                    permissionDenied      = permissionState.value == PermissionState.Denied,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user previously denied the permission dialog but then granted it
        // from the system Settings and returned to the app, the launcher callback
        // never fires again. Re-check here and re-subscribe so the next nearby
        // voice command ("artimiausia …") sees a real GPS fix instead of null.
        if (permissionState.value == PermissionState.Denied &&
            LocationPermissionHelper.hasLocationPermission(this)
        ) {
            Log.d(FLOW_TAG, "onResume: permission now granted — re-subscribing to location")
            permissionState.value = PermissionState.Granted
            viewModel.retryLocationUpdates()
            initializeNavigation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(FLOW_TAG, "onDestroy: releasing all resources")
        audioController.release()
        voiceSessionController.stopSession()
        navigationController.onDestroy()
    }

    private fun initializeNavigation() {
        Log.d(FLOW_TAG, "initializeNavigation: engine=${engine::class.simpleName}")
        navigationController.initialize(
            activity = this,
            onReady  = {
                Log.d(FLOW_TAG, "engine ready")
                engineReady.value = true
            },
            onError  = { msg ->
                Log.e(FLOW_TAG, "engine init error: $msg")
                engineError.value = msg
                engineReady.value = false
            },
        )
    }

    private sealed class PermissionState {
        object Checking : PermissionState()
        object Granted  : PermissionState()
        object Denied   : PermissionState()
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

    var isNavigating  by remember { mutableStateOf(false) }
    var sessionConfig by remember { mutableStateOf(SessionConfig()) }
    var isMuted       by remember { mutableStateOf(false) }
    var aiStatusMessage by remember { mutableStateOf("") }
    var startScreenError by remember { mutableStateOf<String?>(null) }
    var showSettings  by remember { mutableStateOf(false) }

    // Voice state from ViewModel
    val voiceListeningState  by viewModel.voiceListeningState.collectAsStateWithLifecycle()
    val voiceStatusText      by viewModel.voiceStatusText.collectAsStateWithLifecycle()
    val pendingRecognizedText by viewModel.pendingRecognizedText.collectAsStateWithLifecycle()
    val pendingNavAction     by viewModel.pendingNavAction.collectAsStateWithLifecycle()
    val pendingClarification by viewModel.pendingClarification.collectAsStateWithLifecycle()
    val homeAddress          by viewModel.homeAddress.collectAsStateWithLifecycle()
    val workAddress          by viewModel.workAddress.collectAsStateWithLifecycle()

    // Waypoint state from ViewModel
    val stopovers            by viewModel.stopovers.collectAsStateWithLifecycle()
    val finalDestinationName by viewModel.finalDestinationName.collectAsStateWithLifecycle()

    // Session loop state
    val continuousSessionActive by viewModel.continuousSessionActive.collectAsStateWithLifecycle()

    // Voice status takes priority over generic AI status message in the display.
    val effectiveStatus = voiceStatusText.ifBlank { aiStatusMessage }

    // ── RECORD_AUDIO permission ───────────────────────────────────────────

    val audioPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.d(MainActivity.FLOW_TAG, "RECORD_AUDIO: granted=$granted")
        if (granted) viewModel.onMicPressed()
        else aiStatusMessage = "Norint naudoti balso komandas, reikia suteikti mikrofono leidimą."
    }

    fun onMicPress() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.toggleSession()
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── Voice recognition result handler ──────────────────────────────────

    LaunchedEffect(pendingRecognizedText) {
        val text = pendingRecognizedText ?: return@LaunchedEffect
        Log.d(MainActivity.FLOW_TAG, "pendingRecognizedText: '$text'")
        viewModel.executeVoiceCommand(
            text          = text,
            navState      = navState,
            sessionConfig = sessionConfig,
            isMuted       = isMuted,
        )
        viewModel.clearPendingRecognizedText()
    }

    // ── Voice navigation action handler ───────────────────────────────────

    LaunchedEffect(pendingNavAction) {
        val action = pendingNavAction ?: return@LaunchedEffect
        Log.d(MainActivity.FLOW_TAG, "pendingNavAction: ${action::class.simpleName}")
        when (action) {
            is VoiceNavAction.StopNavigation -> {
                navigationController.stopNavigation()
                voiceSessionController.stopSession()
                audioController.release()
                viewModel.ttsManager.stop()
                viewModel.onNavigationStopped()
                isNavigating = false; isMuted = false
                aiStatusMessage = ""; startScreenError = null
            }
            is VoiceNavAction.StartNavigation -> {
                startScreenError = null; aiStatusMessage = ""
                if (!engineReady) {
                    startScreenError = engineError ?: "Navigacija neparuošta. Palaukite…"
                } else {
                    Log.d(MainActivity.FLOW_TAG, "voice StartNavigation: '${action.destination}'")
                    isNavigating = true
                    navigationController.startNavigation(
                        context     = context,
                        destination = action.destination,
                        onError     = { msg ->
                            Log.e(MainActivity.FLOW_TAG, "voice startNavigation onError: $msg")
                            isNavigating = false; startScreenError = msg
                            if (!isMuted) viewModel.ttsManager.speak(
                                "Nepavyko rasti arba apskaičiuoti maršruto. Patikrinkite adresą."
                            )
                        },
                    )
                }
            }
            is VoiceNavAction.Mute -> {
                isMuted = true; aiStatusMessage = "AI nutildytas"
            }
            is VoiceNavAction.Unmute -> {
                isMuted = false; aiStatusMessage = ""
            }
        }
        viewModel.clearPendingNavAction()
    }

    // ── Waypoint arrival chaining ─────────────────────────────────────────
    // When the SDK fires the arrival event and there are still intermediate stops
    // queued, onWaypointArrived() emits a new StartNavigation action for the next
    // stop. When no stopovers remain this is a no-op (normal final-destination flow).

    LaunchedEffect(navState.hasArrived) {
        if (navState.hasArrived) {
            Log.d(MainActivity.FLOW_TAG, "hasArrived=true — checking waypoints")
            viewModel.onWaypointArrived()
        }
    }

    // ── Safety: navigation audio priority ────────────────────────────────

    val permission = safetyController.getPermission(navState)
    if (safetyController.shouldInterruptAudio(navState) &&
        (audioController.isAiPlaying || viewModel.ttsManager.isSpeaking)
    ) {
        audioController.interruptAiAudio()
        viewModel.ttsManager.stop()
        aiStatusMessage = "Navigacija perėmė garsą"
    }

    // ── Maneuver announcements ─────────────────────────────────────────────

    val announcedThresholds = remember { mutableSetOf<Int>() }
    var lastManeuverKey by remember { mutableStateOf("") }
    val maneuverKey = "${navState.maneuverType}_${navState.nextRoadName}"

    LaunchedEffect(navState.maneuverType, navState.nextRoadName) {
        if (maneuverKey != lastManeuverKey) { announcedThresholds.clear(); lastManeuverKey = maneuverKey }
    }

    val maneuverDist = navState.distanceToNextManeuverMeters.takeIf { it != Int.MAX_VALUE } ?: 0

    LaunchedEffect(maneuverDist) {
        if (isMuted || !navState.isNavigating || maneuverDist <= 0) return@LaunchedEffect
        if (viewModel.isSpeechBlocked) return@LaunchedEffect
        val threshold = listOf(500, 200, 50).firstOrNull { t ->
            maneuverDist <= t && t !in announcedThresholds
        } ?: return@LaunchedEffect
        announcedThresholds.add(threshold)
        val instruction = buildNavInstruction(navState, maneuverDist)
        if (instruction.isNotBlank()) {
            viewModel.recordSpokenInstruction(instruction)
            viewModel.ttsManager.speak(instruction)
        }
    }

    var previousPhase by remember { mutableStateOf(NavigationPhase.IDLE) }
    LaunchedEffect(navState.phase) {
        if (navState.phase == NavigationPhase.NAVIGATING && previousPhase != NavigationPhase.NAVIGATING) {
            if (!isMuted) {
                val dest = navState.resolvedAddress.ifBlank { navState.destinationName }.ifBlank { "tikslą" }
                viewModel.ttsManager.speak("Maršrutas į $dest paruoštas. Pradedame kelionę.")
            }
        }
        previousPhase = navState.phase
    }

    if (!isNavigating && navState.errorMessage != null) {
        startScreenError = navState.errorMessage
    }

    // ── Screen selection ──────────────────────────────────────────────────

    when {
        showSettings -> {
            SettingsScreen(
                homeAddress = homeAddress,
                workAddress = workAddress,
                onSaveHome  = { viewModel.setHomeAddress(it) },
                onSaveWork  = { viewModel.setWorkAddress(it) },
                onClearHome = { viewModel.clearHomeAddress() },
                onClearWork = { viewModel.clearWorkAddress() },
                onBack      = { showSettings = false },
            )
        }

        !isNavigating -> {
            val displayError = startScreenError ?: if (permissionDenied) engineError else null
            StartScreen(
                errorMessage        = displayError,
                engineReady         = engineReady,
                voiceListeningState = voiceListeningState,
                voiceStatusText     = voiceStatusText,
                sessionActive       = continuousSessionActive,
                onMicPress          = { onMicPress() },
                onOpenSettings      = { showSettings = true },
                onStartNavigation   = { destination, config ->
                    Log.d(MainActivity.FLOW_TAG, "start button: destination='$destination'")
                    startScreenError = null; aiStatusMessage = ""
                    sessionConfig = config
                    if (!engineReady) {
                        startScreenError = engineError ?: "Navigacija neparuošta. Palaukite…"
                        return@StartScreen
                    }
                    isNavigating = true
                    navigationController.startNavigation(
                        context     = context,
                        destination = destination,
                        onError     = { msg ->
                            Log.e(MainActivity.FLOW_TAG, "startNavigation onError: $msg")
                            isNavigating = false; startScreenError = msg
                            if (!isMuted) viewModel.ttsManager.speak(
                                "Nepavyko rasti arba apskaičiuoti maršruto. Patikrinkite adresą."
                            )
                        },
                    )
                },
            )
        }

        else -> {
            NavigationScreen(
                navigationState      = navState,
                navigationController = navigationController,
                conversationPermission = permission,
                aiStatusMessage      = effectiveStatus,
                isMuted              = isMuted,
                voiceListeningState  = voiceListeningState,
                sessionActive        = continuousSessionActive,
                stopovers            = stopovers,
                finalDestinationName = finalDestinationName,
                onRemoveStopover     = { index -> viewModel.removeStopoverAt(index, isMuted) },
                onMicPress           = { onMicPress() },
                onMuteToggle         = {
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
                    Log.d(MainActivity.FLOW_TAG, "onStopNavigation: user pressed stop")
                    navigationController.stopNavigation()
                    voiceSessionController.stopSession()
                    audioController.release()
                    viewModel.ttsManager.stop()
                    viewModel.onNavigationStopped()
                    isNavigating = false; isMuted = false
                    aiStatusMessage = ""; startScreenError = null
                },
            )
        }
    }

    // ── Clarification dialog (overlay on all screens) ─────────────────────

    pendingClarification?.let { clarif ->
        ClarificationDialog(
            clarification = clarif,
            onSelect      = { index -> viewModel.onClarificationAnswer(index) },
            onCancel      = { viewModel.cancelClarification() },
        )
    }
}

// ── Clarification dialog ──────────────────────────────────────────────────────

@Composable
private fun ClarificationDialog(
    clarification: ClarificationState,
    onSelect: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val ordinalLabels = listOf("1. Pirmas", "2. Antras", "3. Trečias")

    AlertDialog(
        onDismissRequest = { /* require explicit selection or cancel */ },
        title = { Text("Radau kelis variantus") },
        text = {
            Column {
                Text(
                    text = "Kurį renkamės? Pasakykite \"pirmą\", \"antrą\" arba \"trečią\", " +
                           "arba paspauskite.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                clarification.candidates.take(3).forEachIndexed { i, candidate ->
                    val dist = candidate.distanceMeters?.let { m ->
                        if (m < 1000) " · $m m" else " · ${m / 1000} km"
                    } ?: ""
                    TextButton(
                        onClick = { onSelect(i + 1) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${ordinalLabels[i]}$dist",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = candidate.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (candidate.address.isNotBlank()) {
                                Text(
                                    text = candidate.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Atšaukti") }
        },
    )
}

// ── Navigation instruction builder ────────────────────────────────────────────

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
    return if (distanceMeters <= 50) "Dabar $action$roadSuffix."
           else "Po ${formatDistance(distanceMeters)} $action$roadSuffix."
}
