package lt.sturmanas.bajeristas.navigation

import android.app.Activity
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.sturmanas.bajeristas.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Production [NavigationEngine] backed by the Google Navigation SDK 7.x.
 *
 * ## Lifecycle split — read before modifying
 *
 * [onViewDestroy] = tears down [NavigationView] only. Called from NavigationScreen's
 * DisposableEffect.onDispose when the composable leaves composition. The [Navigator]
 * survives, so [startNavigation] works again immediately on the next attempt.
 *
 * [onDestroy] = full teardown (Navigator + NavigationView). Called ONLY from
 * [MainActivity.onDestroy] via [NavigationController.onDestroy]. Never call from a composable.
 *
 * This split is the fix for: "Navigacija neparuošta" appearing after every failed address
 * search. The old onDestroy() nulled the Navigator from DisposableEffect, permanently
 * breaking the engine until the Activity was restarted.
 *
 * ## Address resolution
 *
 * Multi-attempt strategy (in order):
 *  1. Raw coordinates "lat,lng" — fast path, no network.
 *  2. Android Geocoder — raw input (API 33+: callback; older: synchronous on IO).
 *  3. Android Geocoder — input + ", Lietuva" appended.
 *  4. Android Geocoder — Lithuanian abbreviations normalised (pr.→prospektas, etc.).
 *  5. Google Geocoding API HTTP — fallback for devices where Geocoder returns nothing
 *     (common on Xiaomi/MIUI which ships without full Google Services Geocoder support).
 *
 * SDK API notes (Navigation SDK 7.8.0):
 *  - Audio guidance: Navigator.AudioGuidance (nested enum), not NavigatorAudioGuidance.
 *  - setDestination() returns ListenableResultFuture<Navigator.RouteStatus>.
 *    addOnSuccessListener/addOnFailureListener do NOT exist on this type.
 *    Route readiness → RouteChangedListener; arrival → ArrivalListener.
 *  - startGuidance() must be called explicitly after the first RouteChangedListener fires.
 *    It is NOT called automatically by setDestination().
 *  - currentStep / per-maneuver data: not available in public SDK 7.8.0 API.
 *    currentTimeAndDistance gives remaining distance to destination.
 */
class GoogleNavigationEngine : NavigationEngine {

    private val _state = MutableStateFlow(NavigationState())
    override val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var navigator: Navigator? = null
    private var navigationView: NavigationView? = null
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * Guards NavigationView re-creation.
     * Reset in [createNavigationView]; set in [onViewDestroy] and [onDestroy].
     * Only blocks NavigationView teardown, not Navigator access.
     */
    private var isViewDestroyed = false

    /**
     * Prevents [startGuidance] from being called more than once per session.
     * [RouteChangedListener] fires for both the initial route and every re-route;
     * only the first call should start guidance.
     */
    private var guidanceStarted = false

    /**
     * Request ID incremented on every [startNavigation] call.
     * Compared inside the geocoder callback to discard stale results when the user
     * submits a new destination before the previous geocoder call completes.
     */
    private var currentRequestId = 0

    companion object {
        const val TAG = "GoogleNavEngine"

        /**
         * Returns the query stripped of its locality suffix (everything before the
         * first ", ") if one is present, or `null` if there is nothing to strip.
         *
         * Used by the locality-stripped retry in [resolveAddress] to convert a
         * PlaceSearch query like "degalinė, Klaipėda" into just "degalinė" when
         * all full-query geocoding attempts have returned zero results.
         *
         * Examples:
         *   "degalinė, Klaipėda" → "degalinė"
         *   "degalinė near 55.7,21.1" → null  (no ", " separator)
         *   "degalinė"           → null
         */
        internal fun stripLocalitySuffix(query: String): String? {
            val idx = query.indexOf(", ")
            if (idx < 0) return null
            val stripped = query.substring(0, idx).trim()
            return stripped.ifBlank { null }
        }
    }

    // ── NavigationEngine impl ─────────────────────────────────────────────

    override fun initialize(activity: Activity, onReady: () -> Unit, onError: (String) -> Unit) {
        Log.d(TAG, "initialize: requesting navigator (navigator currently ${if (navigator != null) "alive" else "null"})")
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                Log.d(TAG, "onNavigatorReady: navigator obtained")
                navigator = nav
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
                Log.e(TAG, "navigator init error $errorCode: $msg")
                _state.value = NavigationState(errorMessage = msg)
                onError(msg)
            }
        })
    }

    override fun startNavigation(context: Context, destination: String, onError: (String) -> Unit) {
        val nav = navigator
        Log.d(TAG, "startNavigation: destination='$destination' navigator=${if (nav != null) "ready" else "NULL"}")

        if (nav == null) {
            val msg = "Navigacija neparuošta. Palaukite ir bandykite dar kartą."
            Log.e(TAG, "startNavigation: navigator is null — engine may not have finished initialising")
            onError(msg)
            return
        }

        val requestId = ++currentRequestId
        Log.d(TAG, "startNavigation: requestId=$requestId")

        _state.value = _state.value.copy(
            destinationName = destination,
            errorMessage = null,
            phase = NavigationPhase.RESOLVING_ADDRESS,
        )

        ioScope.launch {
            Log.d(TAG, "resolveAddress [$requestId]: starting for '$destination'")
            val result = resolveAddress(context, destination)

            withContext(Dispatchers.Main) {
                // Discard result if a newer request has replaced this one.
                if (requestId != currentRequestId) {
                    Log.d(TAG, "resolveAddress [$requestId]: stale — discarding (current=$currentRequestId)")
                    return@withContext
                }

                if (result == null) {
                    Log.e(TAG, "resolveAddress [$requestId]: all attempts failed for '$destination'")
                    _state.value = _state.value.copy(
                        phase = NavigationPhase.IDLE,
                        errorMessage = "Adresas nerastas: $destination",
                    )
                    onError("Nepavyko rasti „$destination". Pabandykite kitaip.")
                    return@withContext
                }

                val (lat, lng, resolvedName) = result
                Log.d(TAG, "resolveAddress [$requestId]: resolved lat=$lat lng=$lng name='$resolvedName'")

                val waypoint = try {
                    Waypoint.builder().setLatLng(lat, lng).build()
                } catch (e: Exception) {
                    Log.e(TAG, "Waypoint build failed", e)
                    _state.value = _state.value.copy(
                        phase = NavigationPhase.IDLE,
                        errorMessage = "Klaida nustatant tikslą",
                    )
                    onError("Klaida nustatant tikslą")
                    return@withContext
                }

                guidanceStarted = false
                _state.value = _state.value.copy(
                    destinationName = resolvedName.ifBlank { destination },
                    resolvedAddress = resolvedName,
                    phase = NavigationPhase.CALCULATING_ROUTE,
                )

                Log.d(TAG, "setDestination: lat=$lat lng=$lng")
                // setDestination returns ListenableResultFuture<RouteStatus>.
                // addOnSuccessListener / addOnFailureListener do NOT exist on this type.
                // Route readiness is signalled via RouteChangedListener (see setupListeners).
                nav.setDestination(waypoint, RoutingOptions())
            }
        }
    }

    override fun stopNavigation() {
        Log.d(TAG, "stopNavigation")
        navigator?.stopGuidance()
        guidanceStarted = false
        _state.value = NavigationState()
    }

    override fun createNavigationView(context: Context): View {
        Log.d(TAG, "createNavigationView: resetting isViewDestroyed flag")
        isViewDestroyed = false
        return NavigationView(context).also { view ->
            navigationView = view
            // onCreate must be called here, during composition (inside remember {} in
            // NavigationScreen), so that the view is non-null when DisposableEffect
            // side-effects run and the lifecycle observer replays ON_START / ON_RESUME.
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

    override fun onStart()  { navigationView?.onStart() }
    override fun onResume() { navigationView?.onResume() }
    override fun onPause()  { navigationView?.onPause() }
    override fun onStop()   { navigationView?.onStop() }

    /**
     * Tears down the [NavigationView] ONLY.
     *
     * Called from [NavigationScreen]'s `DisposableEffect.onDispose` when the composable
     * leaves composition — e.g. the user goes back to [StartScreen] after a failed address
     * search, or presses "Baigti".
     *
     * The [Navigator] is intentionally left alive. This is the critical fix for the bug
     * where address search failures permanently break the engine:
     *
     *   old flow: onError → isNavigating=false → NavigationScreen unmounts →
     *             DisposableEffect.onDispose → onDestroy() → navigator=null →
     *             next startNavigation fails immediately with "Navigacija neparuošta"
     *
     *   new flow: onError → isNavigating=false → NavigationScreen unmounts →
     *             DisposableEffect.onDispose → onViewDestroy() → NavigationView cleaned up,
     *             Navigator alive → next startNavigation works correctly
     */
    override fun onViewDestroy() {
        if (isViewDestroyed) return
        isViewDestroyed = true
        Log.d(TAG, "onViewDestroy: tearing down NavigationView only (Navigator stays alive)")
        navigationView?.onDestroy()
        navigationView = null
    }

    /**
     * Full teardown — NavigationView + Navigator. Called ONLY from
     * [MainActivity.onDestroy] via [NavigationController.onDestroy].
     * Never call this from a composable or DisposableEffect.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy: full teardown (Activity destroyed)")
        onViewDestroy()                // tears down NavigationView (idempotent)
        navigator?.cleanup()
        navigator = null
        guidanceStarted = false
    }

    // ── Private: listeners ────────────────────────────────────────────────

    private fun setupListeners(nav: Navigator) {
        nav.addRemainingTimeOrDistanceChangedListener(5, 10) {
            syncStateFromNavigator(nav)
        }

        // Route changed — fires for both initial route calculation AND re-routes.
        //
        // startGuidance() is the missing call in the original code. setDestination()
        // only requests a route; startGuidance() begins turn-by-turn guidance and
        // activates maneuver / distance callbacks. The guidanceStarted flag ensures it
        // is called exactly once per navigation session, not on every re-route.
        nav.addRouteChangedListener(RouteChangedListener {
            Log.d(TAG, "routeChangedListener: guidanceStarted=$guidanceStarted")
            if (!guidanceStarted) {
                guidanceStarted = true
                Log.d(TAG, "first route ready — calling startGuidance()")
                nav.startGuidance()
                _state.value = _state.value.copy(
                    isNavigating = true,
                    isRerouting = false,
                    phase = NavigationPhase.NAVIGATING,
                )
            } else {
                Log.d(TAG, "re-route — route updated")
                _state.value = _state.value.copy(isRerouting = true)
            }
            syncStateFromNavigator(nav)
        })

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
        // currentTimeAndDistance = remaining time/distance to destination (not next maneuver).
        // Both distanceToNextManeuverMeters and remainingDistanceMeters use this until
        // per-step distance becomes available in the public API.
        val td = nav.currentTimeAndDistance
        val distMeters = td?.meters?.toInt() ?: Int.MAX_VALUE
        val durSeconds = td?.seconds?.toInt() ?: 0
        Log.d(TAG, "syncState: dist=$distMeters m dur=$durSeconds s")
        _state.value = _state.value.copy(
            maneuverType = ManeuverType.UNKNOWN,
            distanceToNextManeuverMeters = distMeters,
            remainingDistanceMeters = distMeters,
            remainingDurationSeconds = durSeconds,
            isRerouting = false,
        )
    }

    // ── Private: address resolution ───────────────────────────────────────

    /**
     * Multi-attempt address resolution. All attempts run on Dispatchers.IO (caller's scope).
     *
     * Strategy (in order):
     *  1. Raw "lat,lng" — fast path, no network.
     *  2. Android Geocoder with raw input.
     *  3. Android Geocoder with input + ", Lietuva" bias.
     *  4. Android Geocoder with normalised Lithuanian abbreviations.
     *  5. Android Geocoder with normalised + ", Lietuva".
     *  6. Google Geocoding API HTTP request — reliable fallback for devices (e.g. Xiaomi/MIUI)
     *     where the Android Geocoder returns empty results despite valid internet connectivity.
     *
     * Returns `Triple(lat, lng, displayName)` or `null` if all attempts fail.
     */
    private suspend fun resolveAddress(
        context: Context,
        destination: String,
    ): Triple<Double, Double, String>? {
        // ── 1. Raw coordinates ────────────────────────────────────────────
        val parts = destination.split(",")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                Log.d(TAG, "resolveAddress: raw coordinates lat=$lat lng=$lng")
                return Triple(lat, lng, destination)
            }
        }

        // ── 2–5. Android Geocoder (multi-attempt) ────────────────────────
        val hasLietuva = destination.contains("Lietuva", ignoreCase = true) ||
            destination.contains("Lithuania", ignoreCase = true)
        val normalized = normalizeAddress(destination)
        val normalizedDiffers = normalized != destination

        val geocoderQueries = buildList {
            add(destination)                                         // 2. raw
            if (!hasLietuva) add("$destination, Lietuva")           // 3. raw + country
            if (normalizedDiffers) add(normalized)                   // 4. normalized
            if (normalizedDiffers && !hasLietuva)
                add("$normalized, Lietuva")                          // 5. normalized + country
        }

        for (query in geocoderQueries) {
            Log.d(TAG, "geocoder attempt: '$query'")
            val addresses = geocodeWithAndroid(context, query)
            Log.d(TAG, "geocoder returned ${addresses.size} result(s) for '$query'")
            if (addresses.isNotEmpty()) {
                val addr = addresses.first()
                val name = buildDisplayName(addr, destination)
                Log.d(TAG, "geocoder selected: lat=${addr.latitude} lng=${addr.longitude} name='$name'")
                return Triple(addr.latitude, addr.longitude, name)
            }
        }

        // ── 6. Google Geocoding API HTTP fallback ────────────────────────
        // Xiaomi / MIUI ships without full Google GMS geocoder support, causing the
        // Android Geocoder to return empty results even for valid Lithuanian addresses.
        // The Google Geocoding API HTTP endpoint is always accurate and bypasses this.
        val apiKey = BuildConfig.GOOGLE_MAPS_API_KEY
        if (apiKey.isNotBlank()) {
            val googleQuery = if (hasLietuva) destination else "$destination, Lithuania"
            Log.d(TAG, "Google Geocoding API fallback: '$googleQuery'")
            val result = geocodeWithGoogleApi(googleQuery, apiKey)
            if (result != null) {
                Log.d(TAG, "Google API resolved: lat=${result.first} lng=${result.second} name='${result.third}'")
                return result
            }
            Log.w(TAG, "Google Geocoding API: no result for '$googleQuery'")
        } else {
            Log.w(TAG, "Google Geocoding API fallback skipped: GOOGLE_MAPS_API_KEY is blank")
        }

        // ── 7. Locality-stripped retry ────────────────────────────────────
        // If the query is a PlaceSearch result with a locality appended
        // (e.g. "degalinė, Klaipėda"), strip everything after the first ", "
        // and try again.  This recovers the common case where a category
        // search like "degalinė, Klaipėda" has no Geocoder entry but the
        // bare keyword "degalinė" does.
        val simplified = stripLocalitySuffix(destination)
        if (simplified != null) {
            Log.d(TAG, "resolveAddress: locality-stripped retry with '$simplified'")
            for (query in listOf(simplified, "$simplified, Lietuva")) {
                Log.d(TAG, "geocoder attempt (stripped): '$query'")
                val addresses = geocodeWithAndroid(context, query)
                Log.d(TAG, "geocoder returned ${addresses.size} result(s) for '$query' (stripped)")
                if (addresses.isNotEmpty()) {
                    val addr = addresses.first()
                    val name = buildDisplayName(addr, destination)
                    Log.d(TAG, "locality-stripped geocoder: lat=${addr.latitude} lng=${addr.longitude} name='$name'")
                    return Triple(addr.latitude, addr.longitude, name)
                }
            }
            if (apiKey.isNotBlank()) {
                val strippedQuery = "$simplified, Lithuania"
                Log.d(TAG, "Google Geocoding API fallback (stripped): '$strippedQuery'")
                val result = geocodeWithGoogleApi(strippedQuery, apiKey)
                if (result != null) {
                    Log.d(TAG, "locality-stripped HTTP: lat=${result.first} lng=${result.second} name='${result.third}'")
                    return result
                }
                Log.w(TAG, "Google Geocoding API: no result for '$strippedQuery' (stripped)")
            }
        }

        return null
    }

    /**
     * Calls Android [Geocoder] with [query]. Uses the API 33+ callback API on Tiramisu
     * and the deprecated synchronous API on older versions (both run on IO thread).
     */
    private suspend fun geocodeWithAndroid(context: Context, query: String): List<Address> {
        return try {
            val geocoder = Geocoder(context, Locale("lt", "LT"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val deferred = CompletableDeferred<List<Address>>()
                geocoder.getFromLocationName(query, 3, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        deferred.complete(addresses)
                    }
                    override fun onError(errorMessage: String?) {
                        Log.w(TAG, "Geocoder.GeocodeListener.onError: $errorMessage")
                        deferred.complete(emptyList())
                    }
                })
                deferred.await()
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 3) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "geocodeWithAndroid exception for '$query'", e)
            emptyList()
        }
    }

    /**
     * Calls the Google Geocoding API via HTTP.
     * Uses [BuildConfig.GOOGLE_MAPS_API_KEY] — never hardcoded.
     * Language and region are set to Lithuanian for best local results.
     */
    private suspend fun geocodeWithGoogleApi(
        query: String,
        apiKey: String,
    ): Triple<Double, Double, String>? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://maps.googleapis.com/maps/api/geocode/json" +
                "?address=$encoded&key=$apiKey&language=lt&region=lt"
            Log.d(TAG, "Google Geocoding API: GET $url")

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val responseCode = conn.responseCode
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            conn.disconnect()

            val json = JSONObject(body)
            val status = json.getString("status")
            Log.d(TAG, "Google Geocoding API: status=$status (HTTP $responseCode)")

            if (status == "OK") {
                val first = json.getJSONArray("results").getJSONObject(0)
                val loc = first.getJSONObject("geometry").getJSONObject("location")
                val lat = loc.getDouble("lat")
                val lng = loc.getDouble("lng")
                val formatted = first.getString("formatted_address")
                Triple(lat, lng, formatted)
            } else {
                Log.w(TAG, "Google Geocoding API returned status=$status for '$query'")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "geocodeWithGoogleApi exception", e)
            null
        }
    }

    /**
     * Expands common Lithuanian address abbreviations.
     * Applied before Geocoder attempts to improve match rate.
     *
     * Examples: "Taikos pr." → "Taikos prospektas"
     *           "Gedimino g." → "Gedimino gatvė"
     */
    private fun normalizeAddress(address: String): String {
        // Replacements with trailing space/comma ensure we match standalone abbreviations
        // and not substrings inside longer words.
        val replacements = listOf(
            Pair(" pr. ",  " prospektas "),
            Pair(" pr., ", " prospektas, "),
            Pair(" pr.",   " prospektas"),   // end-of-string
            Pair(" g. ",   " gatvė "),
            Pair(" g., ",  " gatvė, "),
            Pair(" g.",    " gatvė"),
            Pair(" al. ",  " alėja "),
            Pair(" al., ", " alėja, "),
            Pair(" al.",   " alėja"),
            Pair(" pl. ",  " plentas "),
            Pair(" pl., ", " plentas, "),
            Pair(" pl.",   " plentas"),
            Pair(" sk. ",  " skersgatvis "),
            Pair(" sk., ", " skersgatvis, "),
            Pair(" a. ",   " aikštė "),
            Pair(" a., ",  " aikštė, "),
        )
        var result = " $address " // pad so leading/trailing abbreviations match
        for ((from, to) in replacements) {
            result = result.replace(from, to)
        }
        return result.trim()
    }

    /**
     * Builds a human-readable display name from a [Geocoder] [Address] result.
     * Falls back to the first address line, or the original input if nothing is available.
     */
    private fun buildDisplayName(addr: Address, fallback: String): String = buildString {
        if (!addr.thoroughfare.isNullOrBlank()) append(addr.thoroughfare)
        if (!addr.subThoroughfare.isNullOrBlank()) {
            if (isNotEmpty()) append(" ")
            append(addr.subThoroughfare)
        }
        if (!addr.locality.isNullOrBlank()) {
            if (isNotEmpty()) append(", ")
            append(addr.locality)
        }
        if (isEmpty()) append(addr.getAddressLine(0) ?: fallback)
    }
}
