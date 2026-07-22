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
import android.util.Log
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
import lt.sturmanas.bajeristas.navigation.ManeuverType
import lt.sturmanas.bajeristas.navigation.NavigationState
import lt.sturmanas.bajeristas.personality.formatDistance
import lt.sturmanas.bajeristas.voice.TtsManager
import lt.sturmanas.bajeristas.voice.askKentas
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.sturmanas.bajeristas.navigation.GoogleNavigationEngine
import lt.sturmanas.bajeristas.navigation.LocationPermissionHelper
import lt.sturmanas.bajeristas.navigation.MockNavigationEngine
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.personality.PersonaPrompts
import lt.sturmanas.bajeristas.personality.SessionConfig
import lt.sturmanas.bajeristas.safety.SafetyController
import lt.sturmanas.bajeristas.ui.NavigationScreen
import lt.sturmanas.bajeristas.ui.StartScreen
import lt.sturmanas.bajeristas.ui.theme.SturmanasTheme
import lt.sturmanas.bajeristas.voice.AudioController
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

    // Survives screen rotation — holds TtsManager so TTS is not restarted on every rotation.
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
            engineError.value = "Vietos leidimas atmestas. Atidarykite nustatymus ir suteikite „Šturmanas Bajeristas“ prieigą prie vietos."
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
                    ttsManager = viewModel.ttsManager,
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
    ttsManager: TtsManager,
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
                aiStatusMessage = "„$text“ — Kentas galvoja…"
                coroutineScope.launch {
                    // sessionConfig and navState are Compose state — read fresh on the
                    // main thread at launch time, before dispatching to IO inside askKentas.
                    val reply = askKentas(
                        userText = text,
                        config = sessionConfig,
                        navState = navState,
                        apiKey = BuildConfig.OPENAI_API_KEY,
                    )
                    aiStatusMessage = reply
                    // Speak the reply — stop any navigation announcement already playing.
                    if (!isMuted) ttsManager.speak(reply)
                }
            } else {
                // Recognizer returned OK but an empty transcript (mumble, background noise,
                // silence timeout). Show a rotating Kentas-style fallback — no API call.
                val fallback = KENTAS_FALLBACKS.random()
                aiStatusMessage = fallback
                if (!isMuted) ttsManager.speak(fallback)
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

    // Safety rule — navigation always takes priority over AI audio.
    // TTS is checked independently because AudioController is a Phase 1 stub
    // (isAiPlaying is always false until Phase 3 PCM playback is wired up).
    if (safetyController.shouldInterruptAudio(navState) &&
        (audioController.isAiPlaying || ttsManager.isSpeaking)
    ) {
        audioController.interruptAiAudio()
        ttsManager.stop()
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
        if (isMuted || !navState.isNavigating || maneuverDist <= 0) return@LaunchedEffect
        val threshold = listOf(500, 200, 50).firstOrNull { t ->
            maneuverDist <= t && t !in announcedThresholds
        } ?: return@LaunchedEffect
        announcedThresholds.add(threshold)
        val instruction = buildNavInstruction(navState, maneuverDist)
        if (instruction.isNotBlank()) ttsManager.speak(instruction)
    }

    // ── Route-start TTS confirmation ──────────────────────────────────────
    // Fires once when the navigation phase transitions to NAVIGATING (route ready,
    // guidance started). Uses the resolved address from navState so the spoken name
    // matches what the geocoder returned, not the raw typed string.
    // previousPhase tracks the prior value so we only speak on the transition edge,
    // not every recomposition while the phase remains NAVIGATING.
    var previousPhase by remember { mutableStateOf(NavigationPhase.IDLE) }
    LaunchedEffect(navState.phase) {
        if (navState.phase == NavigationPhase.NAVIGATING &&
            previousPhase != NavigationPhase.NAVIGATING
        ) {
            if (!isMuted) {
                val dest = navState.resolvedAddress.ifBlank { navState.destinationName }.ifBlank { "tikslą" }
                ttsManager.speak("Maršrutas į $dest paruoštas. Pradedame kelionę.")
            }
        }
        previousPhase = navState.phase
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
                            // Speak the failure reason so the driver doesn't have to look at the screen.
                            if (!isMuted) {
                                ttsManager.speak(
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
                        ttsManager.stop()
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
                    ttsManager.stop()
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
        ManeuverType.SLIGHT_LEFT      -> "lenkite kairėn"
        ManeuverType.SLIGHT_RIGHT     -> "lenkite dešinėn"
        ManeuverType.SHARP_LEFT       -> "staigiai kairėn"
        ManeuverType.SHARP_RIGHT      -> "staigiai dešinėn"
        ManeuverType.UTURN            -> "apsisukite"
        ManeuverType.ROUNDABOUT       -> "įvažiuokite į žiedą"
        ManeuverType.MOTORWAY_EXIT    -> "važiuokite į išvažiavimą"
        ManeuverType.LANE_CHANGE      -> "keiskite juostą"
        ManeuverType.COMPLEX_JUNCTION -> "atidžiai į sankryžą"
        ManeuverType.MERGE            -> "įsijunkite į srautą"
        ManeuverType.FORK             -> "laikykitės kelio šakos"
        ManeuverType.ARRIVE           -> return "Atvykote į tikslą."
        else                          -> return ""  // NONE, STRAIGHT, UNKNOWN
    }
    val street = navState.nextRoadName.ifBlank { "" }
    val prefix = if (distanceMeters <= 50) "Dabar" else "Po ${formatDistance(distanceMeters)}"
    return if (street.isNotBlank()) "$prefix, $action į $street." else "$prefix, $action."
}
