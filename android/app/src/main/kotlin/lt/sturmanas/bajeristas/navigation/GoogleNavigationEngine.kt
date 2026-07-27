package lt.sturmanas.bajeristas.navigation

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Navigator.ArrivalListener
import com.google.android.libraries.navigation.Navigator.RouteChangedListener
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.sturmanas.bajeristas.BuildConfig

/**
 * Production [NavigationEngine] backed by the Google Navigation SDK.
 */
class GoogleNavigationEngine : NavigationEngine {

    private val _state = MutableStateFlow(NavigationState())
    override val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var navigator: Navigator? = null
    fun getNavigator(): Navigator? = navigator
    
    private var navigationView: NavigationView? = null
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var navInfoJob: Job? = null
    private var currentManeuver = ManeuverType.UNKNOWN
    private var currentExitNumber: Int? = null
    private var distanceToManeuverTbt: Int = Int.MAX_VALUE
    private var nextRoadNameTbt = ""

    private var guidanceStarted = false
    private var sessionActive = false
    private var navAttemptId = 0
    private var currentRequestId = 0

    companion object {
        const val TAG = "GoogleNavEngine"
        const val REROUTE_TIMEOUT_MS = 10_000L
    }

    override fun initialize(activity: Activity, onReady: () -> Unit, onError: (String) -> Unit) {
        Log.d(TAG, "initialize: requesting navigator")
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                Log.d(TAG, "onNavigatorReady: navigator obtained")
                navigator = nav
                nav.setAudioGuidance(Navigator.AudioGuidance.SILENT)

                // ── TBT Feed Registration ─────────────────────────────────
                nav.registerServiceForNavUpdates(
                    activity.packageName,
                    NavInfoService::class.java.name,
                    1
                )

                navInfoJob?.cancel()
                navInfoJob = mainScope.launch {
                    NavInfoService.navInfoFlow.filterNotNull().collectLatest { info ->
                        val step = info.currentStep
                        if (step != null) {
                            currentManeuver = ManeuverMapper.fromSdk(step.maneuver)
                            currentExitNumber = step.exitNumber?.toIntOrNull()
                            step.simpleRoadName?.let { name ->
                                if (name.isNotBlank()) nextRoadNameTbt = name
                            }
                        }
                        distanceToManeuverTbt = info.distanceToCurrentStepMeters ?: Int.MAX_VALUE
                        syncState(nav)
                    }
                }

                setupListeners(nav)
                onReady()
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                val msg = "Navigacijos inicializacijos klaida ($errorCode)"
                _state.value = NavigationState(errorMessage = msg)
                onError(msg)
            }
        })
    }

    override fun startNavigation(context: Context, destination: String, onError: (String) -> Unit) {
        val nav = navigator
        if (nav == null) {
            Log.e(TAG, "NAV_START_FAILED: navigator is NULL")
            onError("Navigacija dar neparuošta. Palaukite akimirką.")
            return
        }

        val requestId = ++currentRequestId
        val attemptId = ++navAttemptId
        Log.i(TAG, "NAV_START_REQUESTED attemptId=$attemptId destination='$destination'")

        _state.value = _state.value.copy(
            destinationName = destination,
            phase = NavigationPhase.RESOLVING_ADDRESS,
        )

        ioScope.launch {
            val resolution = DestinationResolver.resolve(destination)
            Log.d(TAG, "DestinationResolver result: $resolution")

            val query = when (resolution) {
                is DestinationResolution.ExactAddress -> resolution.query
                is DestinationResolution.PlaceSearch -> resolution.query
                is DestinationResolution.Failure -> {
                    withContext(Dispatchers.Main) {
                        if (requestId == currentRequestId) {
                            _state.value = _state.value.copy(phase = NavigationPhase.IDLE)
                            onError(resolution.message)
                        }
                    }
                    return@launch
                }
            }

            // ── Resolve address to coordinates ───────────────────────────
            val result = resolveAddress(context, query)
            Log.i(TAG, "ADDRESS_RESOLVE_RESULT: ${if (result != null) "SUCCESS" else "FAIL"}")

            withContext(Dispatchers.Main) {
                if (requestId != currentRequestId) return@withContext

                if (result == null) {
                    _state.value = _state.value.copy(phase = NavigationPhase.IDLE)
                    onError("Nepavyko rasti adreso: $query")
                    return@withContext
                }

                val (lat, lng) = result
                val waypoint = try {
                    Waypoint.builder().setLatLng(lat, lng).build()
                } catch (e: Exception) {
                    Log.e(TAG, "Waypoint build failed", e)
                    _state.value = _state.value.copy(phase = NavigationPhase.IDLE)
                    onError("Klaida nustatant tikslą")
                    return@withContext
                }

                sessionActive = true
                guidanceStarted = false
                _state.value = _state.value.copy(
                    resolvedAddress = query,
                    phase = NavigationPhase.CALCULATING_ROUTE
                )

                Log.i(TAG, "SDK_SET_DESTINATION_START lat=$lat lng=$lng")
                nav.setDestination(waypoint, RoutingOptions()).setOnResultListener { status ->
                    Log.i(TAG, "SDK_ROUTE_RESULT status=$status")
                    if (status != Navigator.RouteStatus.OK) {
                        Log.e(TAG, "Route finding failed: $status")
                    }
                }
            }
        }
    }

    private suspend fun resolveAddress(context: Context, query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        Log.d(TAG, "resolveAddress ENTER: query='$query'")
        // 1. Raw coordinates
        val parts = query.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return@withContext lat to lng
            }
        }

        // 2. Android Geocoder with multiple variants
        val queries = listOf(query, "$query, Lietuva", "$query, Lithuania")
        for (q in queries) {
            try {
                Log.d(TAG, "resolveAddress: attempting '$q'")
                val geocoder = android.location.Geocoder(context, java.util.Locale("lt", "LT"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(q, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    Log.i(TAG, "ADDRESS_RESOLVE_RESULT: SUCCESS lat=${addr.latitude} lng=${addr.longitude}")
                    return@withContext addr.latitude to addr.longitude
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolveAddress: variant '$q' failed: ${e.message}")
            }
        }
        
        Log.e(TAG, "ADDRESS_RESOLVE_RESULT: FAIL for '$query'")
        null
    }

    override fun stopNavigation() {
        sessionActive = false
        navigator?.stopGuidance()
        guidanceStarted = false
        currentManeuver = ManeuverType.UNKNOWN
        nextRoadNameTbt = ""
        _state.value = NavigationState()
    }

    override fun createNavigationView(context: Context): View {
        val view = NavigationView(context)
        navigationView = view
        view.onCreate(null)
        // Explicitly enable built-in UI: header, footer, turn arrow, etc.
        view.setNavigationUiEnabled(true)
        return view
    }

    override fun enableStandardVoice() {
        navigator?.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
    }

    override fun disableStandardVoice() {
        navigator?.setAudioGuidance(Navigator.AudioGuidance.SILENT)
    }

    override fun onStart()  { navigationView?.onStart() }
    override fun onResume() { navigationView?.onResume() }
    override fun onPause()  { navigationView?.onPause() }
    override fun onStop()   { navigationView?.onStop() }

    override fun onViewDestroy() {
        Log.d(TAG, "onViewDestroy: isNull=${navigationView == null}")
        navigationView?.onDestroy()
        navigationView = null
    }

    override fun onDestroy() {
        sessionActive = false
        navInfoJob?.cancel()
        navInfoJob = null
        onViewDestroy()
        navigator?.unregisterServiceForNavUpdates()
        navigator?.cleanup()
        navigator = null
    }

    private fun setupListeners(nav: Navigator) {
        nav.addRemainingTimeOrDistanceChangedListener(1, 1) {
            syncState(nav)
        }

        nav.addRouteChangedListener(RouteChangedListener {
            if (!sessionActive) return@RouteChangedListener
            if (!guidanceStarted) {
                guidanceStarted = true
                nav.startGuidance()
                _state.value = _state.value.copy(isNavigating = true, phase = NavigationPhase.NAVIGATING)
            }
            syncState(nav)
        })

        nav.addArrivalListener(ArrivalListener {
            sessionActive = false
            _state.value = _state.value.copy(
                hasArrived = true,
                isNavigating = false,
                phase = NavigationPhase.ARRIVED
            )
        })
    }

    private fun syncState(nav: Navigator) {
        val td = nav.currentTimeAndDistance
        val sdkDist = td?.meters?.toInt() ?: Int.MAX_VALUE
        val dist = if (distanceToManeuverTbt != Int.MAX_VALUE) distanceToManeuverTbt else sdkDist
        
        Log.i(TAG, "NAV_REAL_UI_MANEUVER: maneuver=$currentManeuver dist=$dist phase=${_state.value.phase}")
        
        _state.value = _state.value.copy(
            maneuverType = currentManeuver,
            exitNumber = currentExitNumber,
            nextRoadName = nextRoadNameTbt,
            distanceToNextManeuverMeters = dist,
            remainingDurationSeconds = td?.seconds?.toInt() ?: 0
        )
    }
}
