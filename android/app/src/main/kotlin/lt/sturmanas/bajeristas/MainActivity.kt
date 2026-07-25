package lt.sturmanas.bajeristas

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import lt.sturmanas.bajeristas.safety.ConversationPermission
import lt.sturmanas.bajeristas.safety.SafetyController
import lt.sturmanas.bajeristas.ui.NavigationScreen
import lt.sturmanas.bajeristas.ui.SettingsScreen
import lt.sturmanas.bajeristas.ui.StartScreen
import lt.sturmanas.bajeristas.ui.theme.SturmanasTheme
import lt.sturmanas.bajeristas.voice.VoiceListeningState

class MainActivity : ComponentActivity() {

    companion object {
        const val FLOW_TAG = "KentasFlow"
        /**
         * Navigation-speech execution trace. Filter logcat by this tag to follow the
         * full pipeline from NavState update → LaunchedEffect → speakNavInstruction → TTS.
         */
        const val NAV_TRACE_TAG = "KentasNavTrace"
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
    private val safetyController = SafetyController()

    private val engineReady     = mutableStateOf(false)
    private val engineError     = mutableStateOf<String?>(null)
    private val permissionState = mutableStateOf<PermissionState>(PermissionState.Checking)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            Log.i(FLOW_TAG, "LOCATION_PERMISSION_GRANTED")
            permissionState.value = PermissionState.Granted
            viewModel.retryLocationUpdates()
            initializeNavigation()
        } else {
            Log.w(FLOW_TAG, "location permission denied by user")
            permissionState.value = PermissionState.Denied
            engineError.value =
                "Vietos leidimas atmestas. Atidarykite nustatymus ir suteikite programai Šturmanas Bajeristas prieigą prie vietos."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(FLOW_TAG, "onCreate")

        if (LocationPermissionHelper.hasLocationPermission(this)) {
            Log.i(FLOW_TAG, "LOCATION_PERMISSION_GRANTED (already held on launch)")
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
                    safetyController     = safetyController,
                    viewModel            = viewModel,
                    engineReady          = engineReady.value,
                    engineError          = engineError.value,
                    permissionDenied     = permissionState.value == PermissionState.Denied,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionState.value == PermissionState.Denied &&
            LocationPermissionHelper.hasLocationPermission(this)
        ) {
            Log.i(FLOW_TAG, "onResume: LOCATION_PERMISSION_GRANTED (user enabled in settings)")
            permissionState.value = PermissionState.Granted
            viewModel.retryLocationUpdates()
            initializeNavigation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(FLOW_TAG, "onDestroy: releasing navigation resources")
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
    viewModel: MainViewModel,
    engineReady: Boolean,
    engineError: String?,
    permissionDenied: Boolean,
) {
    val context  = LocalContext.current
    val activity = context as? Activity
    val scope    = androidx.compose.runtime.rememberCoroutineScope()
    val navState by navigationController.state.collectAsStateWithLifecycle()

    var isNavigating     by remember { mutableStateOf(false) }
    var startScreenError by remember { mutableStateOf<String?>(null) }
    var showSettings     by remember { mutableStateOf(false) }

    // ── Arrival dialog state ───────────────────────────────────────────────
    // showArrivalDialog: true while the "Baigti maršrutą?" dialog is on screen.
    // arrivalDeclined:   set when the user presses "Dar ne"; prevents the dialog
    //                    from reappearing for the remainder of this navigation session.
    //                    Both flags are reset to false by stopNavigation and by a new trip.
    var showArrivalDialog by remember { mutableStateOf(false) }
    var arrivalDeclined   by remember { mutableStateOf(false) }

    val voiceListeningState       by viewModel.voiceListeningState.collectAsStateWithLifecycle()
    val isConversationActive      by viewModel.isConversationActive.collectAsStateWithLifecycle()
    val locationLoading           by viewModel.locationLoading.collectAsStateWithLifecycle()
    val locationServicesDisabled  by viewModel.locationServicesDisabled.collectAsStateWithLifecycle()
    val homeAddress               by viewModel.homeAddress.collectAsStateWithLifecycle()
    val workAddress               by viewModel.workAddress.collectAsStateWithLifecycle()

    val conversationPermission = safetyController.getPermission(navState)

    // ── Keep navigation context current in conversation controller ─────────

    LaunchedEffect(navState) {
        viewModel.updateConversationNavState(navState)
    }

    // ── RECORD_AUDIO permission ───────────────────────────────────────────

    val audioPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.d(MainActivity.FLOW_TAG, "RECORD_AUDIO: granted=$granted")
        if (granted) viewModel.toggleConversation()
    }

    fun onMicPress() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.toggleConversation()
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── Maneuver announcements ─────────────────────────────────────────────

    val announcedThresholds = remember { mutableSetOf<Int>() }
    var lastManeuverKey by remember { mutableStateOf("") }
    val maneuverKey = "${navState.maneuverType}_${navState.nextRoadName}"
    val maneuverDist = navState.distanceToNextManeuverMeters.takeIf { it != Int.MAX_VALUE } ?: 0

    LaunchedEffect(navState.maneuverType, navState.nextRoadName) {
        if (maneuverKey != lastManeuverKey) {
            announcedThresholds.clear()
            lastManeuverKey = maneuverKey
        }
    }

    LaunchedEffect(maneuverDist) {
        // ── RUNTIME TRACE: filter logcat by KentasNavTrace to follow this pipeline ──
        val ts = System.currentTimeMillis()
        Log.i(MainActivity.NAV_TRACE_TAG,
            "▶ MANEUVER_DISTANCE_CHANGED ts=$ts maneuverDist=$maneuverDist " +
            "isNavigating=${navState.isNavigating} maneuver=${navState.maneuverType} " +
            "nextRoad='${navState.nextRoadName}' isSpeechBlocked=${viewModel.isSpeechBlocked} " +
            "announced=$announcedThresholds")

        if (!navState.isNavigating) {
            Log.d(MainActivity.NAV_TRACE_TAG, "  STOP ts=$ts reason=NOT_NAVIGATING")
            return@LaunchedEffect
        }
        if (maneuverDist <= 0) {
            Log.d(MainActivity.NAV_TRACE_TAG, "  STOP ts=$ts reason=DIST_ZERO_OR_NEGATIVE dist=$maneuverDist")
            return@LaunchedEffect
        }
        if (viewModel.isSpeechBlocked) {
            Log.d(MainActivity.NAV_TRACE_TAG,
                "  STOP ts=$ts reason=SPEECH_BLOCKED voiceState=${viewModel.voiceListeningState.value}")
            return@LaunchedEffect
        }
        val threshold = listOf(500, 200, 50).firstOrNull { t ->
            maneuverDist <= t && t !in announcedThresholds
        }
        if (threshold == null) {
            Log.d(MainActivity.NAV_TRACE_TAG,
                "  STOP ts=$ts reason=NO_THRESHOLD_MATCHED dist=$maneuverDist announced=$announcedThresholds")
            return@LaunchedEffect
        }
        Log.i(MainActivity.NAV_TRACE_TAG,
            "  PASS ts=$ts threshold=$threshold → calling speakNavInstruction")
        announcedThresholds.add(threshold)
        viewModel.speakNavInstruction(navState, maneuverDist)
    }

    // ── Phase change TTS ──────────────────────────────────────────────────

    var previousPhase by remember { mutableStateOf(NavigationPhase.IDLE) }
    LaunchedEffect(navState.phase) {
        if (navState.phase == NavigationPhase.NAVIGATING &&
            previousPhase != NavigationPhase.NAVIGATING
        ) {
            val dest = navState.resolvedAddress.ifBlank { navState.destinationName }
            viewModel.speakRouteReady(dest)
        }
        previousPhase = navState.phase
    }

    // ── Arrival ───────────────────────────────────────────────────────────
    //
    // LaunchedEffect re-runs on every hasArrived change.
    //
    // hasArrived = false: triggered by stopNavigation() resetting NavigationState()
    //   → clears dialog + session flags so a new trip starts clean.
    //
    // hasArrived = true: triggered once per session by the SDK ArrivalListener
    //   → speak the arrival phrase, show the dialog (unless already shown or declined).
    //
    // "Dar ne" sets arrivalDeclined=true, preventing re-show within the same session
    // (hasArrived stays true for the rest of the session, so the LaunchedEffect won't
    // re-fire — but the flag is an explicit contract for clarity and stale-guard safety).

    LaunchedEffect(navState.hasArrived) {
        if (!navState.hasArrived) {
            // Session reset — clear arrival dialog state so the next trip starts clean.
            showArrivalDialog = false
            arrivalDeclined   = false
            return@LaunchedEffect
        }
        Log.d(MainActivity.FLOW_TAG, "ARRIVAL_DETECTED")
        viewModel.speakArrival()
        when {
            showArrivalDialog -> Log.d(MainActivity.FLOW_TAG, "ARRIVAL_DIALOG_SKIPPED reason=already-shown")
            arrivalDeclined   -> Log.d(MainActivity.FLOW_TAG, "ARRIVAL_DIALOG_SKIPPED reason=declined-in-session")
            else              -> {
                showArrivalDialog = true
                Log.d(MainActivity.FLOW_TAG, "ARRIVAL_DIALOG_SHOWN")
            }
        }
    }

    // ── Screen-on management ──────────────────────────────────────────────
    // Keep the screen awake exactly while navigation is running.
    // DisposableEffect (not LaunchedEffect) ensures the flag is cleared in
    // onDispose, which fires when the composable leaves the composition —
    // covering Activity destruction while navigation is active.
    DisposableEffect(navState.isNavigating) {
        // ── RUNTIME TRACE: screen-on pipeline ────────────────────────────────────
        Log.i(MainActivity.FLOW_TAG,
            "SCREEN_ON_EFFECT_ENTERED isNavigating=${navState.isNavigating} " +
            "activityNull=${activity == null} windowNull=${activity?.window == null}")
        if (navState.isNavigating) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(MainActivity.FLOW_TAG,
                "KEEP_SCREEN_ON_ENABLED addFlags called — " +
                "activityNull=${activity == null} windowNull=${activity?.window == null}")
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(MainActivity.FLOW_TAG, "KEEP_SCREEN_ON_DISABLED reason=navigation-stopped")
        }
        onDispose {
            Log.i(MainActivity.FLOW_TAG,
                "SCREEN_ON_EFFECT_DISPOSED isNavigating=${navState.isNavigating} " +
                "activityNull=${activity == null}")
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(MainActivity.FLOW_TAG, "KEEP_SCREEN_ON_DISABLED reason=activity-disposed")
        }
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
                errorMessage             = displayError,
                engineReady              = engineReady,
                locationLoading          = locationLoading,
                locationServicesDisabled = locationServicesDisabled,
                permissionDenied         = permissionDenied,
                onOpenSettings           = { showSettings = true },
                onStartNavigation        = { destination ->
                    Log.d(MainActivity.FLOW_TAG, "start: destination='$destination'")
                    startScreenError = null
                    if (!engineReady) {
                        startScreenError = engineError ?: "Navigacija neparuošta. Palaukite…"
                        return@StartScreen
                    }
                    isNavigating = true
                    navigationController.disableStandardVoice()
                    viewModel.markerRepository.resetSession()
                    navigationController.startNavigation(
                        context     = context,
                        destination = destination,
                        onError     = { msg ->
                            Log.e(MainActivity.FLOW_TAG, "startNavigation onError: $msg")
                            isNavigating = false; startScreenError = msg
                            viewModel.speechCoordinator.speakNavigation(
                                "Nepavyko rasti arba apskaičiuoti maršruto. Patikrinkite adresą."
                            )
                        },
                    )
                },
            )
        }

        else -> {
            NavigationScreen(
                navigationState        = navState,
                navigationController   = navigationController,
                conversationPermission = conversationPermission,
                voiceListeningState    = voiceListeningState,
                isConversationActive   = isConversationActive,
                onMicPress             = { onMicPress() },
                onStopNavigation       = {
                    Log.d(MainActivity.FLOW_TAG, "onStopNavigation")
                    showArrivalDialog = false
                    arrivalDeclined   = false
                    navigationController.stopNavigation()
                    viewModel.onNavigationStopped()
                    isNavigating = false; startScreenError = null
                },
                onReportMarker         = { type, lat, lng ->
                    scope.launch {
                        viewModel.markerRepository.reportMarker(type, lat, lng)
                    }
                },
                showArrivalDialog  = showArrivalDialog,
                onArrivalConfirmed = {
                    Log.d(MainActivity.FLOW_TAG, "ARRIVAL_CONFIRMED")
                    Log.d(MainActivity.FLOW_TAG, "NAVIGATION_FINISHED reason=arrival")
                    showArrivalDialog = false
                    arrivalDeclined   = false
                    navigationController.stopNavigation()
                    viewModel.onNavigationStopped()
                    isNavigating = false
                    startScreenError = null
                },
                onArrivalDeclined  = {
                    Log.d(MainActivity.FLOW_TAG, "ARRIVAL_DECLINED")
                    showArrivalDialog = false
                    arrivalDeclined   = true
                },
            )
        }
    }
}
