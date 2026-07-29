package lt.sturmanas.bajeristas

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import lt.sturmanas.bajeristas.navigation.GoogleNavigationEngine
import lt.sturmanas.bajeristas.navigation.LocationPermissionHelper
import lt.sturmanas.bajeristas.navigation.MockNavigationEngine
import lt.sturmanas.bajeristas.navigation.NavigationController
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.ui.NavigationScreen
import lt.sturmanas.bajeristas.ui.StartScreen
import lt.sturmanas.bajeristas.ui.theme.SturmanasTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val FLOW_TAG = "KentasFlow"
    }

    private val engine by lazy {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()) {
            Log.d(FLOW_TAG, "engine: GoogleNavigationEngine selected")
            GoogleNavigationEngine()
        } else {
            Log.d(FLOW_TAG, "engine: MockNavigationEngine selected")
            MockNavigationEngine()
        }
    }

    private val viewModel: MainViewModel by viewModels()
    private val navigationController by lazy { NavigationController(engine) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        
        if (locationGranted) {
            Log.i(FLOW_TAG, "LOCATION_PERMISSION_GRANTED")
            viewModel.retryLocationUpdates()
            viewModel.initAI(this)
            initializeNavigation()
        } else {
            Log.w(FLOW_TAG, "location permission denied")
            viewModel.setEngineError("Vietos leidimas atmestas.")
        }

        if (!audioGranted) {
            Log.w(FLOW_TAG, "audio permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(FLOW_TAG, "onCreate")

        val permissionsNeeded = mutableListOf<String>()
        if (!LocationPermissionHelper.hasLocationPermission(this)) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(android.Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsNeeded.isEmpty()) {
            Log.i(FLOW_TAG, "PERMISSIONS_ALREADY_GRANTED: invoking initAI")
            viewModel.initAI(this)
            initializeNavigation()
        } else {
            Log.d(FLOW_TAG, "requesting permissions: $permissionsNeeded")
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        }

        setContent {
            SturmanasTheme {
                SturmanasApp(
                    navigationController = navigationController,
                    viewModel            = viewModel,
                )
            }
        }

        // Keep the screen on while actively navigating; clear the flag as soon
        // as navigation stops, arrives, or is cancelled.
        // Uses repeatOnLifecycle so the flag is managed correctly across
        // onStop/onStart cycles (e.g. app backgrounded mid-route).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var screenOnActive = false
                navigationController.state.collect { state ->
                    val shouldKeepOn = state.phase == NavigationPhase.NAVIGATING
                    if (shouldKeepOn == screenOnActive) return@collect   // no change
                    if (shouldKeepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        Log.i(FLOW_TAG, "SCREEN_KEEP_ON enabled reason=NAVIGATING")
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val reason = when (state.phase) {
                            NavigationPhase.ARRIVED  -> "ARRIVED"
                            NavigationPhase.IDLE     -> "IDLE"
                            else                     -> "CANCELLED"
                        }
                        Log.i(FLOW_TAG, "SCREEN_KEEP_ON disabled reason=$reason")
                    }
                    screenOnActive = shouldKeepOn
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        navigationController.onDestroy()
    }

    private fun initializeNavigation() {
        Log.d(FLOW_TAG, "initializeNavigation")
        navigationController.initialize(
            activity = this,
            onReady  = {
                Log.d(FLOW_TAG, "engine ready")
                viewModel.setEngineReady(true)
                viewModel.startObserving(navigationController)
            },
            onError  = { msg ->
                Log.e(FLOW_TAG, "engine init error: $msg")
                viewModel.setEngineError(msg)
                viewModel.setEngineReady(false)
            },
        )
    }
}

@Composable
private fun SturmanasApp(
    navigationController: NavigationController,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val navState    by navigationController.state.collectAsStateWithLifecycle()
    val engineReady by viewModel.engineReady.collectAsStateWithLifecycle()
    val engineError by viewModel.engineError.collectAsStateWithLifecycle()
    val aiStatus    by viewModel.aiStatus.collectAsStateWithLifecycle()

    var isNavigating by remember { mutableStateOf(false) }
    var startScreenError by remember { mutableStateOf<String?>(null) }

    // This LaunchedEffect is the single source of truth for the isNavigating flag:
    // • Sets true  when the engine enters any active phase (covers voice-nav path
    //   that bypasses the StartScreen button).
    // • Sets false when the engine returns to IDLE — this handles voice route
    //   cancellation ("nutrauk maršrutą") where onStopNavigation() is called from
    //   AIConversationController and the engine transitions back to IDLE.
    // • ARRIVED keeps the NavigationScreen visible until the user dismisses.
    LaunchedEffect(navState.phase) {
        when (navState.phase) {
            NavigationPhase.RESOLVING_ADDRESS,
            NavigationPhase.CALCULATING_ROUTE,
            NavigationPhase.NAVIGATING -> isNavigating = true
            NavigationPhase.IDLE       -> {
                Log.i(FLOW_TAG, "NAV_UI_RETURN_TO_START")
                isNavigating = false
            }
            else -> { /* ARRIVED — keep NavigationScreen visible */ }
        }
    }

    if (!isNavigating) {
        StartScreen(
            errorMessage      = startScreenError ?: engineError,
            engineReady       = engineReady,
            onStartNavigation = { destination ->
                isNavigating = true
                navigationController.startNavigation(
                    context     = context,
                    destination = destination,
                    onError     = { msg ->
                        isNavigating = false
                        startScreenError = msg
                    },
                )
            },
        )
    } else {
        NavigationScreen(
            navigationState      = navState,
            navigationController = navigationController,
            onStopNavigation     = {
                navigationController.stopNavigation()
                viewModel.stopNavigationVoice()
                isNavigating = false
            },
            aiStatus = aiStatus
        )
    }

}
