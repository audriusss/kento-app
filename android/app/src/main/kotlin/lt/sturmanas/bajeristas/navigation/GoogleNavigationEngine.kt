package lt.sturmanas.bajeristas.navigation

import android.app.Activity
import android.content.Context
import android.location.Geocoder
import android.view.View
import com.google.android.libraries.navigation.ArrivalEvent
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Navigator.ArrivalListener
import com.google.android.libraries.navigation.Navigator.RouteChangedListener
import com.google.android.libraries.navigation.NavigatorAudioGuidance
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
 */
class GoogleNavigationEngine : NavigationEngine {

    private val _state = MutableStateFlow(NavigationState())
    override val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var navigator: Navigator? = null
    private var navigationView: NavigationView? = null
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // ── NavigationEngine impl ─────────────────────────────────────────────

    override fun initialize(activity: Activity, onReady: () -> Unit, onError: (String) -> Unit) {
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                navigator = nav
                // Kentas speaks; suppress standard navigation voice by default.
                nav.setAudioGuidance(NavigatorAudioGuidance.SILENT)
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
                _state.value = NavigationState(errorMessage = msg)
                onError(msg)
            }
        })
    }

    override fun startNavigation(context: Context, destination: String, onError: (String) -> Unit) {
        val nav = navigator ?: run {
            onError("Navigacija neparuošta. Palaukite ir bandykite dar kartą.")
            return
        }

        _state.value = _state.value.copy(
            isNavigating = true,
            destinationName = destination,
            errorMessage = null,
        )

        resolveDestination(context, destination) { latLng ->
            if (latLng == null) {
                _state.value = _state.value.copy(
                    isNavigating = false,
                    errorMessage = "Adresas nerastas: $destination",
                )
                onError("Nepavyko rasti adreso: $destination")
                return@resolveDestination
            }

            val waypoint = try {
                Waypoint.builder()
                    .setLatLng(latLng.first, latLng.second)
                    .build()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isNavigating = false,
                    errorMessage = "Klaida nustatant tikslą",
                )
                onError("Klaida nustatant tikslą")
                return@resolveDestination
            }

            nav.setDestination(waypoint, RoutingOptions())
                .addOnSuccessListener {
                    // State updates arrive via listeners; no extra action needed.
                }
                .addOnFailureListener { e ->
                    _state.value = _state.value.copy(
                        isNavigating = false,
                        errorMessage = "Maršrutas nerastas",
                    )
                    onError("Maršrutas nerastas")
                }
        }
    }

    override fun stopNavigation() {
        navigator?.stopGuidance()
        _state.value = NavigationState()
    }

    override fun createNavigationView(context: Context): View {
        return NavigationView(context).also { view ->
            navigationView = view
            view.onCreate(null)
        }
    }

    override fun enableStandardVoice() {
        navigator?.setAudioGuidance(NavigatorAudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
    }

    override fun disableStandardVoice() {
        navigator?.setAudioGuidance(NavigatorAudioGuidance.SILENT)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onResume() {
        navigationView?.onResume()
    }

    override fun onPause() {
        navigationView?.onPause()
    }

    override fun onStop() {
        navigationView?.onStop()
    }

    override fun onDestroy() {
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

        // Step changes (new maneuver approaching)
        nav.addOnNavigationStepUpdatedListener {
            syncStateFromNavigator(nav)
        }

        // Rerouting
        nav.addOnRouteChangedListener(RouteChangedListener {
            _state.value = _state.value.copy(isRerouting = true)
            syncStateFromNavigator(nav)
        })

        // Arrival
        nav.addArrivalListener(ArrivalListener { _ ->
            _state.value = _state.value.copy(
                hasArrived = true,
                isNavigating = false,
                maneuverType = ManeuverType.ARRIVE,
                distanceToNextManeuverMeters = 0,
            )
        })
    }

    private fun syncStateFromNavigator(nav: Navigator) {
        val step = nav.currentStep
        val timeAndDist = nav.currentTimeAndDistance
        _state.value = _state.value.copy(
            maneuverType = ManeuverMapper.fromSdk(step?.maneuver),
            distanceToNextManeuverMeters = timeAndDist?.meters?.toInt() ?: Int.MAX_VALUE,
            remainingDurationSeconds = timeAndDist?.seconds?.toInt() ?: 0,
            currentRoadName = step?.fullRoadName ?: "",
            nextRoadName = step?.nextStepRoadName ?: "",
            isRerouting = false,
        )
    }

    /**
     * Resolve [destination] to (latitude, longitude).
     * Accepts "lat,lng" format directly, or uses [Geocoder] for address strings.
     * Result is delivered on the main thread via [onResult].
     */
    private fun resolveDestination(
        context: Context,
        destination: String,
        onResult: (Pair<Double, Double>?) -> Unit,
    ) {
        // Try parsing as raw coordinates first
        val parts = destination.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                onResult(Pair(lat, lng))
                return
            }
        }

        // Use Geocoder for address strings
        ioScope.launch {
            val result = try {
                val geocoder = Geocoder(context, Locale("lt", "LT"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(destination, 1)
                addresses?.firstOrNull()?.let { Pair(it.latitude, it.longitude) }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }
}
