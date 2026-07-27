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
import lt.sturmanas.bajeristas.ui.NavigationScreen
import lt.sturmanas.bajeristas.ui.StartScreen
import lt.sturmanas.bajeristas.ui.debug.PocDebugOverlay
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

    // DEBUG-only VAD PoC overlay.
    // PocDebugOverlay is a no-op in release builds (guarded by BuildConfig.DEBUG internally).
    // Never touches production voice state, ConversationController, or NavigationController.
    PocDebugOverlay()
}
