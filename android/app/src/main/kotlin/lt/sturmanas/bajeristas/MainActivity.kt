package lt.sturmanas.bajeristas

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.voice.askKentas
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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

// ── Voice recognition fallbacks ──────────────────────────────────────────────
//
// Shown when SpeechRecognizer returns RESULT_OK but an empty or null transcript.
// A small rotating list keeps Kentas sounding alive rather than giving a flat error.
// No API call is made for an empty transcript — these are local strings only.
private val KENTAS_FALLBACKS = listOf(
    "Pakartok žmonių kalba.",
    "Nieko negirdėjau. Ar šneki, ar miegai?",
    "Ką, ką? Pabandyk dar kartą.",
    "Girdi mane? Tai aš tavęs negirdėjau.",
)

// ── Voice recognition helper ──────────────────────────────────────────────────

/**
 * Builds a Lithuanian [RecognizerIntent] and launches it via [launcher].
 *
 * Checks [SpeechRecognizer.isRecognitionAvailable] first; calls [onError] with a
 * Lithuanian message if the device has no recognizer or if the intent cannot be
 * resolved ([ActivityNotFoundException]).  The caller is responsible for ensuring
 * the RECORD_AUDIO permission is already granted before calling this function.
 */
private fun launchSpeechIntent(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
    onError: (String) -> Unit,
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onError("Kalbos atpažinimas neprieinamas šiame įrenginyje")
        return
    }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "lt-LT")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "lt-LT")
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "lt-LT")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Kalbėkite lietuviškai…")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    try {
        launcher.launch(intent)
    } catch (e: ActivityNotFoundException) {
        onError("Kalbos atpažinimo programa nerasta")
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

    val coroutineScope = rememberCoroutineScope()

    // ── Voice recognition ─────────────────────────────────────────────────
    // speechLauncher — handles the result from the system RecognizerIntent dialog.
    // Recognised text is forwarded to askKentas(); the reply appears in aiStatusMessage.
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                // Show the recognized phrase while the API call runs (typically 2–5 s).
                // The driver can confirm what Kentas heard before the reply arrives.
                aiStatusMessage = "„$text" — Kentas galvoja…"
                coroutineScope.launch {
                    // sessionConfig and navState are Compose state — read fresh on the
                    // main thread at launch time, before dispatching to IO inside askKentas.
                    aiStatusMessage = askKentas(
                        userText = text,
                        config = sessionConfig,
                        navState = navState,
                        apiKey = BuildConfig.OPENAI_API_KEY,
                    )
                }
            } else {
                // Recognizer returned OK but an empty transcript (mumble, background noise,
                // silence timeout). Show a rotating Kentas-style fallback — no API call.
                aiStatusMessage = KENTAS_FALLBACKS.random()
            }
        } else {
            // RESULT_CANCELED = user dismissed without speaking; clear quietly.
            aiStatusMessage = ""
        }
    }

    // audioPermLauncher — requests RECORD_AUDIO at runtime (required API 23+).
    // On grant, immediately launches speech recognition.
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            aiStatusMessage = "Klausau…"
            launchSpeechIntent(context, speechLauncher) { aiStatusMessage = it }
        } else {
            aiStatusMessage = "Mikrofono leidimas atmestas"
        }
    }

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
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        aiStatusMessage = "Klausau…"
                        launchSpeechIntent(context, speechLauncher) { aiStatusMessage = it }
                    } else {
                        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
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
