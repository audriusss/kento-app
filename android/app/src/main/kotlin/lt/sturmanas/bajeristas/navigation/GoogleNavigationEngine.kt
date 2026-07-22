package lt.sturmanas.bajeristas.navigation

import android.app.Activity
import android.content.Context
import android.location.Geocoder
import android.util.Log
import android.view.View
import com.google.android.libraries.navigation.ArrivalEvent
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Navigator.ArrivalListener
import com.google.android.libraries.navigation.Navigator.RouteChangedListener
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Production [NavigationEngine] backed by the Google Navigation SDK 7.x.
 *
 * SDK objects ([Navigator], [NavigationView]) are private — the rest of the
 * app sees only [NavigationState]. [ManeuverMapper] converts SDK maneuver
 * enums to the internal type.
 *
 * Lifecycle contract:
 *  - [initialize] must be called first (requires location permission granted).
 *  - [startNavigation] may only be called after [onReady] fires.
 *  - [onResume], [onPause], [onStop], [onDestroy] must be forwarded from the
 *    hosting Activity/composable so [NavigationView] stays in sync.
 *
 * SDK API notes (Navigation SDK 7.8.0):
 *  - Audio guidance type: Navigator.AudioGuidance (nested), not NavigatorAudioGuidance.
 *  - setDestination() returns ListenableResultFuture<Navigator.RouteStatus>, not a Task.
 *    addOnSuccessListener/addOnFailureListener do not exist on this type.
 *    Route readiness is signalled via addRouteChangedListener; that is where
 *    startGuidance() is called (first time only, guarded by [guidanceStarted]).
 *  - Step-level maneuver access (navigator.currentStep) does not exist in the public
 *    SDK 7.8.0 API. ManeuverType is temporarily set to UNKNOWN until the correct API
 *    is confirmed. Distance and duration are read from getCurrentTimeAndDistance().
 *  - Step listener: addOnNavigationStepUpdatedListener does not exist; removed.
 *  - Route listener: addRouteChangedListener (not addOnRouteChangedListener).
 *
 * Android Studio warning "Cannot resolve symbol 'distance'" (around MainActivity line 406):
 *  This is a stale IDE index issue — the symbol referenced is 'distanceMeters' (a function
 *  parameter), which compiles and resolves correctly. Invalidate caches / restart to clear it.
 */
class GoogleNavigationEngine : NavigationEngine {

    private val _state = MutableStateFlow(NavigationState())
    override val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var navigator: Navigator? = null
    private var navigationView: NavigationView? = null
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // Guards against double-calling onDestroy (it is invoked from both the
    // LifecycleEventObserver and onDispose in NavigationScreen).
    private var isDestroyed = false

    /**
     * Prevents [startGuidance] from being called more than once per navigation session.
     * [RouteChangedListener] fires for both the initial route and every re-route;
     * only the first call should start guidance — subsequent calls are reroutes.
     */
    private var guidanceStarted = false

    private companion object {
        const val TAG = "GoogleNavEngine"
    }

    // ── NavigationEngine impl ─────────────────────────────────────────────

    override fun initialize(activity: Activity, onReady: () -> Unit, onError: (String) -> Unit) {
        Log.d(TAG, "initialize: requesting navigator")
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                Log.d(TAG, "onNavigatorReady")
                navigator = nav
                // Kentas speaks; suppress standard navigation voice by default.
                // Navigator.AudioGuidance is the correct nested type in SDK 7.8.0.
                nav.setAudioGuidance(Navigator.AudioGuidance.SILENT)
                setupListeners(nav)
                onReady()
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                val msg = when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED ->
                        "Navigacijos API raktas nepriimtas. Patikrinkite Google Cloud konsolę."
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED ->
                        "Navigacijos naudojimo sąlygos nepriimtos. Paleiskite programą iš naujo."
                    NavigationApi.ErrorCode.NETWORK_ERROR ->
                        "Tinklo klaida. Patikrinkite interneto ryšį."
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING ->
                        "Nepateikti vietos leidimai. Suteikite leidimą nustatymų lange."
                    else -> "Navigacijos inicializacijos klaida (kodas: $errorCode)"
                }
                Log.e(TAG, "Navigator init error $errorCode: $msg")
                _state.value = NavigationState(errorMessage = msg)
                onError(msg)
            }
        })
    }

    override fun startNavigation(context: Context, destination: String, onError: (String) -> Unit) {
        val nav = navigator ?: run {
            val msg = "Navigacija neparuošta. Palaukite ir bandykite dar kartą."
            Log.e(TAG, "startNavigation called but navigator is null")
            onError(msg)
            return
        }

        Log.d(TAG, "startNavigation: destination='$destination'")

        // Phase 1: mark address resolution in progress.
        // isNavigating stays false until guidance actually starts.
        _state.value = _state.value.copy(
            destinationName = destination,
            errorMessage = null,
            phase = NavigationPhase.RESOLVING_ADDRESS,
        )

        resolveDestination(context, destination) { result ->
            if (result == null) {
                Log.e(TAG, "resolveDestination: no result for '$destination'")
                _state.value = _state.value.copy(
                    phase = NavigationPhase.IDLE,
                    errorMessage = "Adresas nerastas: $destination",
                )
                onError("Nepavyko rasti adreso: $destination")
                return@resolveDestination
            }

            val (lat, lng, resolvedName) = result
            Log.d(TAG, "resolveDestination OK: lat=$lat lng=$lng name='$resolvedName'")

            val waypoint = try {
                Waypoint.builder()
                    .setLatLng(lat, lng)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Waypoint build failed", e)
                _state.value = _state.value.copy(
                    phase = NavigationPhase.IDLE,
                    errorMessage = "Klaida nustatant tikslą",
                )
                onError("Klaida nustatant tikslą")
                return@resolveDestination
            }

            // Phase 2: waypoint is valid; hand off to SDK for route calculation.
            guidanceStarted = false
            _state.value = _state.value.copy(
                destinationName = resolvedName.ifBlank { destination },
                resolvedAddress = resolvedName,
                phase = NavigationPhase.CALCULATING_ROUTE,
            )

            Log.d(TAG, "setDestination: lat=$lat lng=$lng")
            // setDestination() returns ListenableResultFuture<RouteStatus>.
            // addOnSuccessListener / addOnFailureListener do not exist on this type.
            // Route readiness is signalled via RouteChangedListener (see setupListeners).
            nav.setDestination(waypoint, RoutingOptions())
        }
    }

    override fun stopNavigation() {
        Log.d(TAG, "stopNavigation")
        navigator?.stopGuidance()
        guidanceStarted = false
        _state.value = NavigationState()
    }

    override fun createNavigationView(context: Context): View {
        // Reset destroy guard so the engine can be reused if navigation
        // is stopped and restarted within the same process.
        isDestroyed = false
        return NavigationView(context).also { view ->
            navigationView = view
            // onCreate must be called here, during composition (inside remember {}
            // in NavigationScreen), so that navigationView is non-null by the time
            // DisposableEffect side-effects run and the lifecycle observer replays
            // ON_START / ON_RESUME. If onCreate were called in the AndroidView
            // factory (layout time, after side-effects), the replay would fire on a
            // null view and the view would be stuck in CREATED state forever.
            view.onCreate(null)
        }
    }

    override fun enableStandardVoice() {
        navigator?.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
    }

    override fun disableStandardVoice() {
        navigator?.setAudioGuidance(Navigator.AudioGuidance.SILENT)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onStart() {
        navigationView?.onStart()
    }

    override fun onResume() {
        navigationView?.onResume()
    }

    override fun onPause() {
        navigationView?.onPause()
    }

    override fun onStop() {
        navigationView?.onStop()
    }

    /**
     * Tears down [NavigationView] and cleans up the [Navigator].
     *
     * Called from two places in [NavigationScreen]:
     *  1. The [LifecycleEventObserver] when the host Activity is destroyed.
     *  2. The [DisposableEffect] onDispose when the composable leaves composition
     *     (e.g. user stops navigation while Activity is still alive).
     *
     * The [isDestroyed] guard ensures only the first call executes; subsequent
     * calls are safe no-ops.
     */
    override fun onDestroy() {
        if (isDestroyed) return
        isDestroyed = true
        Log.d(TAG, "onDestroy")
        navigationView?.onDestroy()
        navigator?.cleanup()
        navigationView = null
        navigator = null
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun setupListeners(nav: Navigator) {
        // Remaining time / distance updates (every 10 m or 5 s change)
        nav.addRemainingTimeOrDistanceChangedListener(5, 10) {
            syncStateFromNavigator(nav)
        }

        // NOTE: addOnNavigationStepUpdatedListener does not exist in Navigation SDK 7.8.0.
        // Step-level maneuver updates are not available from this listener set.
        // ManeuverType remains UNKNOWN until the correct step access API is confirmed.

        // Route changed — fires for both the initial route calculation AND re-routes.
        //
        // Core fix: startGuidance() must be called here, not in startNavigation().
        // setDestination() only requests a route; startGuidance() starts turn-by-turn
        // guidance and triggers maneuver / distance callbacks. The guidanceStarted flag
        // distinguishes the initial route from subsequent re-routes so startGuidance()
        // is called exactly once per navigation session.
        nav.addRouteChangedListener(RouteChangedListener {
            Log.d(TAG, "routeChangedListener: guidanceStarted=$guidanceStarted")
            if (!guidanceStarted) {
                guidanceStarted = true
                Log.d(TAG, "First route ready — calling startGuidance()")
                nav.startGuidance()
                _state.value = _state.value.copy(
                    isNavigating = true,
                    isRerouting = false,
                    phase = NavigationPhase.NAVIGATING,
                )
            } else {
                Log.d(TAG, "Reroute — route updated")
                _state.value = _state.value.copy(isRerouting = true)
            }
            syncStateFromNavigator(nav)
        })

        // Arrival
        nav.addArrivalListener(ArrivalListener { _ ->
            Log.d(TAG, "arrivalListener: arrived at destination")
            _state.value = _state.value.copy(
                hasArrived = true,
                isNavigating = false,
                maneuverType = ManeuverType.ARRIVE,
                distanceToNextManeuverMeters = 0,
                phase = NavigationPhase.ARRIVED,
            )
        })
    }

    private fun syncStateFromNavigator(nav: Navigator) {
        // NOTE: Navigator 7.8.0 does not expose a currentStep property on the Navigator
        // object. There is no public API to read the current maneuver type or road name
        // directly in this SDK version. ManeuverType is set to UNKNOWN and road names
        // are cleared until the correct step-info API surface is confirmed.
        //
        // currentTimeAndDistance gives remaining time/distance to destination (not to
        // the next maneuver). Both distanceToNextManeuverMeters and remainingDistanceMeters
        // are set from this single source until per-step distance becomes available.
        val timeAndDist = nav.currentTimeAndDistance
        val distMeters = timeAndDist?.meters?.toInt() ?: Int.MAX_VALUE
        val durSeconds = timeAndDist?.seconds?.toInt() ?: 0
        Log.d(TAG, "syncState: distMeters=$distMeters durSeconds=$durSeconds")
        _state.value = _state.value.copy(
            maneuverType = ManeuverType.UNKNOWN,
            distanceToNextManeuverMeters = distMeters,
            remainingDistanceMeters = distMeters,
            remainingDurationSeconds = durSeconds,
            isRerouting = false,
        )
    }

    /**
     * Resolve [destination] to (latitude, longitude, resolvedDisplayName).
     *
     * Fast path: accepts "lat,lng" format directly (no network needed).
     * Slow path: uses [Geocoder] for address strings, appending ", Lietuva" when
     * Lithuania is not already mentioned to bias results toward Lithuanian addresses.
     *
     * Result is delivered on the **main thread** via [onResult].
     * Returns `null` when the address cannot be resolved.
     */
    private fun resolveDestination(
        context: Context,
        destination: String,
        onResult: (Triple<Double, Double, String>?) -> Unit,
    ) {
        // Fast path — raw "lat,lng" coordinates, no network required
        val parts = destination.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                Log.d(TAG, "resolveDestination: raw coordinates lat=$lat lng=$lng")
                onResult(Triple(lat, lng, destination))
                return
            }
        }

        // Append Lithuania hint when not already present.
        // The Geocoder uses this to bias results toward Lithuanian addresses,
        // which significantly improves accuracy for partial Lithuanian place names
        // (e.g. "Taikos pr. 61, Klaipėda" → "Taikos pr. 61, Klaipėda, Lietuva").
        val query = if (destination.contains("Lietuva", ignoreCase = true) ||
            destination.contains("Lithuania", ignoreCase = true)
        ) {
            destination
        } else {
            "$destination, Lietuva"
        }
        Log.d(TAG, "resolveDestination: geocoding '$query'")

        ioScope.launch {
            val result: Triple<Double, Double, String>? = try {
                val geocoder = Geocoder(context, Locale("lt", "LT"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 3)
                Log.d(TAG, "geocoder returned ${addresses?.size ?: 0} result(s)")
                addresses?.firstOrNull()?.let { addr ->
                    // Build a human-readable display name from geocoder fields,
                    // falling back to the first address line if structured fields are absent.
                    val displayName = buildString {
                        if (!addr.thoroughfare.isNullOrBlank()) append(addr.thoroughfare)
                        if (!addr.subThoroughfare.isNullOrBlank()) {
                            if (isNotEmpty()) append(" ")
                            append(addr.subThoroughfare)
                        }
                        if (!addr.locality.isNullOrBlank()) {
                            if (isNotEmpty()) append(", ")
                            append(addr.locality)
                        }
                        if (isEmpty()) {
                            append(addr.getAddressLine(0) ?: destination)
                        }
                    }
                    Log.d(TAG, "geocoder resolved: '$displayName' lat=${addr.latitude} lng=${addr.longitude}")
                    Triple(addr.latitude, addr.longitude, displayName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "geocoder exception for '$query'", e)
                null
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }
}
