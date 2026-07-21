package lt.sturmanas.bajeristas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.sturmanas.bajeristas.navigation.GoogleNavigationEngine
import lt.sturmanas.bajeristas.navigation.LocationPermissionHelper
import lt.sturmanas.bajeristas.navigation.MockNavigationEngine
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.personality.PersonaPrompts
import lt.sturmanas.bajeristas.personality.SessionConfig
import lt.sturmanas.bajeristas.safety.SafetyController
import lt.sturmanas.bajeristas.ui.NavigationScreen
import lt.sturmanas.bajeristas.ui.StartScreen
import lt.sturmanas.bajeristas.ui.theme.SturmanasTheme
import lt.sturmanas.bajeristas.voice.AudioController
import lt.sturmanas.bajeristas.voice.VoiceSessionController

class MainActivity : ComponentActivity() {

    // ── Engine selection ──────────────────────────────────────────────────
    // GoogleNavigationEngine is used when GOOGLE_MAPS_API_KEY is set in local.properties.
    // MockNavigationEngine is the automatic fallback — safe to build and run
    // without any API key. No code change needed to switch; add the key and rebuild.
    private val engine by lazy {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) {
            GoogleNavigationEngine()
        } else {
            MockNavigationEngine()
        }
    }

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
            engineError.value = "Vietos leidimas atmestas. Atidarykite nustatymus ir suteikite „Šturmanas Bajeristas“ prieigą prie vietos."
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (LocationPermissionHelper.hasLocationPermission(this)) {
            permissionState.value = PermissionState.Granted
            initializeNavigation()
        } else {
            locationPermissionLauncher.launch(LocationPermissionHelper.LOCATION_PERMISSION)
        }

        setContent {
            SturmanasTheme {
                SturmanasApp(
                    navigationController = navigationController,
                    safetyController = safetyController,
                    voiceSessionController = voiceSessionController,
                    audioController = audioController,
                    engineReady = engineReady.value,
                    engineError = engineError.value,
                    permissionDenied = permissionState.value == PermissionState.Denied,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioController.release()
        voiceSessionController.stopSession()
        navigationController.onDestroy()
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun initializeNavigation() {
        navigationController.initialize(
            activity = this,
            onReady = { engineReady.value = true },
            onError = { msg ->
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

    val permission = safetyController.getPermission(navState)

    // Safety rule — navigation always takes priority over AI audio
    if (safetyController.shouldInterruptAudio(navState) && audioController.isAiPlaying) {
        audioController.interruptAiAudio()
        aiStatusMessage = "Navigacija perėmė garsą"
    }

    // Propagate navState error back to start screen if navigation is not yet active
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
                onStartNavigation = { destination, config ->
                    startScreenError = null
                    aiStatusMessage = ""
                    sessionConfig = config

                    if (!engineReady) {
                        startScreenError = engineError ?: "Navigacija neparuošta. Palaukite…"
                        return@StartScreen
                    }

                    navigationController.startNavigation(
                        context = context,
                        destination = destination,
                        onError = { msg ->
                            isNavigating = false
                            startScreenError = msg
                        },
                    )
                    isNavigating = true
                    // Phase 3: voiceSessionController.startSession(PersonaPrompts.systemPrompt(config))
                },
            )
        }

        else -> {
            NavigationScreen(
                navigationState = navState,
                navigationController = navigationController,
                conversationPermission = permission,
                aiStatusMessage = aiStatusMessage,
                isMuted = isMuted,
                onMicPress = {
                    // Phase 3: start AudioRecord → voiceSessionController.sendAudio(…)
                    aiStatusMessage = "Klausau… (3 fazė)"
                },
                onMuteToggle = {
                    isMuted = !isMuted
                    if (isMuted) {
                        audioController.interruptAiAudio()
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
                    navigationController.stopNavigation()
                    voiceSessionController.stopSession()
                    audioController.release()
                    isNavigating = false
                    isMuted = false
                    aiStatusMessage = ""
                    startScreenError = null
                },
            )
        }
    }
}
