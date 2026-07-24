package lt.sturmanas.bajeristas.navigation

import android.app.Activity
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import android.view.View
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
     * Scope used for Main-thread map operations (enabling blue dot, camera move).
     * Cancelled in [onDestroy] to prevent leaks if the engine outlives the view.
     */
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Reference to the underlying [GoogleMap] obtained via [NavigationView.getMapAsync].
     * Null until the map is ready and cleared on [onViewDestroy].
     */
    private var googleMap: GoogleMap? = null

    /**
     * One-shot job that waits for the first location fix and animates the camera.
     * Cancelled on [onViewDestroy] so a pending camera move from a previous navigation
     * session does not fire on a freshly-created view.
     */
    private var mapCameraJob: Job? = null

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
     * True while a navigation session is active (between [startNavigation] and
     * [stopNavigation] / [onDestroy]).
     *
     * Guards [RouteChangedListener] and [ArrivalListener] against stale SDK callbacks
     * that fire after [stopNavigation] — e.g. the SDK delivers one final route event
     * after [stopGuidance] on some devices. Without this flag the listener would see
     * [guidanceStarted]==false, call [startGuidance] again on a dead session, and
     * corrupt the second-trip flow.
     */
    private var sessionActive = false

    /**
     * Monotonically-increasing attempt counter. Incremented on every [startNavigation]
     * call. Included in all [NAV_TAG] log lines so individual trips are
     * distinguishable in logcat when multiple trips occur in the same session.
     */
    private var navAttemptId = 0

    /**
     * Request ID incremented on every [startNavigation] call.
     * Compared inside the geocoder callback to discard stale results when the user
     * submits a new destination before the previous geocoder call completes.
     */
    private var currentRequestId = 0

    /**
     * Monotonically-increasing counter incremented every time a new reroute begins OR
     * rerouting state is cleared.  The timeout coroutine captures its generation at
     * launch and only acts if the counter has not moved — preventing a stale timeout
     * from clearing the state of a fresh reroute that began while the old timer was
     * still running.
     */
    private var reroutingGeneration = 0

    /**
     * Safety-timeout job for [NavigationState.isRerouting].
     *
     * Scheduled in [mainScope] when a reroute starts; cancelled by
     * [clearReroutingState] when the reroute finishes normally.
     * If it fires, it clears ONLY the visual flag — it does NOT cancel navigation.
     */
    private var reroutingTimeoutJob: Job? = null

    companion object {
        const val TAG     = "GoogleNavEngine"
        const val NAV_TAG = "KentasNavigation"
        const val MAP_TAG = "KentasMap"

        /**
         * Initial map zoom level used when the camera is centered on the user's
         * current position.  16.0f gives a roughly 200 m field of view — practical
         * for urban driving without being too close to see the next junction.
         */
        const val DRIVING_ZOOM_LEVEL = 16.0f

        /** Minimum confidence score for a geocoder result to be accepted. */
        const val SCORE_THRESHOLD = 3

        /**
         * Maximum time the rerouting spinner may remain visible after a route-changed
         * event.  Cleared by the next [addRemainingTimeOrDistanceChangedListener] callback
         * under normal conditions; this timeout is a safety net for devices where that
         * callback is delayed or never fires.
         *
         * The timeout clears ONLY the visual [NavigationState.isRerouting] flag — it does
         * NOT cancel navigation or modify any other SDK state.
         */
        const val REROUTE_TIMEOUT_MS = 10_000L

        /**
         * Detects geocoding queries produced by [DestinationResolver] step C
         * (street + house number WITH a city component).
         * Capture groups: [1] street stem · [2] house number · [3] city name.
         * Number group supports "61", "61A", "17-2", "12B", "5/7".
         */
        internal val STREET_QUERY_WITH_CITY = Regex(
            """^([A-ZĄČĘĖĮŠŲŪŽa-ząčęėįšųūž][^,\d]*?)\s+(\d+(?:[a-zA-ZĄČĘĖĮŠŲŪŽąčęėįšųūž]|[-\/]\d+)?),\s*(.+?),\s*Lithuania\s*$"""
        )

        /**
         * Same pattern but WITHOUT a city component: e.g. "Taikos 61, Lithuania".
         * Capture groups: [1] street stem · [2] house number.
         */
        internal val STREET_QUERY_NO_CITY = Regex(
            """^([A-ZĄČĘĖĮŠŲŪŽa-ząčęėįšųūž][^,\d]*?)\s+(\d+(?:[a-zA-ZĄČĘĖĮŠŲŪŽąčęėįšųūž]|[-\/]\d+)?),\s*Lithuania\s*$"""
        )

        /**
         * Confidence score for a geocoder result against a street + house-number query.
         *
         * Scoring table:
         *   +3  city matches   (locality or subAdminArea ⊇ expectedLocality, or vice versa)
         *   +3  street matches (thoroughfare or featureName, after stripping type suffixes,
         *                       contains the expected street stem)
         *   +3  number matches (subThoroughfare, featureName, or formattedAddress ⊇ expectedNumber)
         *   +1  Lithuania confirmed (formattedAddress mentions "Lietuva"/"Lithuania")
         *   −5  no thoroughfare → city-only / administrative area result
         *   −5  expected number absent from all address fields
         *   −5  locality is present and does not match expected city
         *
         * Accepts when score ≥ [SCORE_THRESHOLD].
         * All arguments are plain Strings — no Android dependency — for full JVM testability.
         */
        internal fun scoreStreetResult(
            thoroughfare: String?,
            subThoroughfare: String?,
            locality: String?,
            subAdminArea: String?,
            featureName: String?,
            formattedAddress: String?,
            expectedStreet: String?,
            expectedNumber: String?,
            expectedLocality: String?,
        ): Int {
            var score = 0
            val tag = "KentasDestination"

            // ── Thoroughfare / street match ────────────────────────────────
            if (thoroughfare.isNullOrBlank()) {
                score -= 5
                Log.d(tag, "score: −5 no thoroughfare (city-centre fallback?)")
            } else if (!expectedStreet.isNullOrBlank()) {
                val normExp     = normalizeStreetText(expectedStreet)
                val normStreet  = normalizeStreetText(thoroughfare)
                val normFeature = normalizeStreetText(featureName ?: "")
                val streetMatch = normStreet.contains(normExp) || normExp.contains(normStreet) ||
                    (normFeature.isNotBlank() &&
                        (normFeature.contains(normExp) || normExp.contains(normFeature)))
                if (streetMatch) {
                    score += 3
                    Log.d(tag, "score: +3 street match thoroughfare='$thoroughfare'")
                } else {
                    Log.d(tag, "score: ±0 street mismatch '$thoroughfare' vs expected '$expectedStreet'")
                }
            }

            // ── House number ───────────────────────────────────────────────
            if (!expectedNumber.isNullOrBlank()) {
                val numFound = listOf(subThoroughfare, featureName, formattedAddress)
                    .any { it?.contains(expectedNumber, ignoreCase = true) == true }
                if (numFound) {
                    score += 3
                    Log.d(tag, "score: +3 house number '$expectedNumber' found")
                } else {
                    score -= 5
                    Log.d(tag, "score: −5 house number '$expectedNumber' not found in any field")
                }
            }

            // ── City ───────────────────────────────────────────────────────
            if (!expectedLocality.isNullOrBlank()) {
                val normExp   = expectedLocality.trim().lowercase()
                val cityMatch = listOf(locality, subAdminArea).any { loc ->
                    if (loc.isNullOrBlank()) false
                    else {
                        val nl = loc.trim().lowercase()
                        nl.contains(normExp) || normExp.contains(nl)
                    }
                }
                if (cityMatch) {
                    score += 3
                    Log.d(tag, "score: +3 city match '${locality ?: subAdminArea}'")
                } else if (!locality.isNullOrBlank()) {
                    score -= 5
                    Log.d(tag, "score: −5 wrong city locality='$locality' expected='$expectedLocality'")
                }
            }

            // ── Country ────────────────────────────────────────────────────
            if (formattedAddress?.contains("Lietuva", ignoreCase = true) == true ||
                formattedAddress?.contains("Lithuania", ignoreCase = true) == true) {
                score += 1
                Log.d(tag, "score: +1 Lithuania confirmed")
            }

            Log.d(tag, "score: total=$score (threshold=$SCORE_THRESHOLD)")
            return score
        }

        /**
         * Thin boolean wrapper around [scoreStreetResult] for call sites that only
         * have the basic geocoder fields.
         */
        internal fun isValidStreetResult(
            thoroughfare: String?,
            subThoroughfare: String?,
            locality: String?,
            expectedNumber: String?,
            expectedLocality: String?,
        ): Boolean = scoreStreetResult(
            thoroughfare     = thoroughfare,
            subThoroughfare  = subThoroughfare,
            locality         = locality,
            subAdminArea     = null,
            featureName      = null,
            formattedAddress = null,
            expectedStreet   = null,
            expectedNumber   = expectedNumber,
            expectedLocality = expectedLocality,
        ) >= SCORE_THRESHOLD

        /**
         * Strips Lithuanian street-type suffixes and normalises a street name for
         * fuzzy comparison (lowercase, remove type words, collapse whitespace).
         */
        private fun normalizeStreetText(text: String): String =
            text.lowercase().trim()
                .replace(
                    Regex("""\b(g\.|gatvė|gatvę|gatve|pr\.|prospektas|prospektą|al\.|alėja|alėją|aleja|pl\.|plentas|plentą)\b"""),
                    "",
                )
                .replace(Regex("""\s+"""), " ")
                .trim()

        /**
         * Returns the query stripped of its locality suffix (everything before the
         * first ", ") if one is present, or `null` if there is nothing to strip.
         *
         * Used by the locality-stripped retry in [resolveAddress] to convert a
         * PlaceSearch query like "degalinė, Klaipėda" into just "degalinė" when
         * all full-query geocoding attempts have returned zero results.
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

        val requestId    = ++currentRequestId
        val attemptId    = ++navAttemptId
        // Detect street+number queries produced by DestinationResolver step C so we
        // can give a more actionable error message when all candidates fail.
        val streetWithCityDetected = STREET_QUERY_WITH_CITY.containsMatchIn(destination)
        val isStreetQuery          = streetWithCityDetected || STREET_QUERY_NO_CITY.containsMatchIn(destination)
        val isStreetWithKnownCity  = streetWithCityDetected
        Log.d(TAG,     "startNavigation: requestId=$requestId attemptId=$attemptId isStreetQuery=$isStreetQuery isStreetWithKnownCity=$isStreetWithKnownCity")
        Log.d(NAV_TAG, "startNavigation attemptId=$attemptId destination='$destination'")

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
                    // Street queries get a directed prompt based on whether the city was known.
                    val errorMsg = when {
                        isStreetQuery && isStreetWithKnownCity ->
                            "Adreso tiksliai rasti nepavyko. Pasakyk gatvę, numerį ir miestą."
                        isStreetQuery ->
                            "Kuriame mieste yra šis adresas?"
                        else ->
                            "Nepavyko rasti \"$destination\". Pabandykite kitaip."
                    }
                    onError(errorMsg)
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

                sessionActive  = true
                guidanceStarted = false
                _state.value = _state.value.copy(
                    destinationName = resolvedName.ifBlank { destination },
                    resolvedAddress = resolvedName,
                    phase = NavigationPhase.CALCULATING_ROUTE,
                )

                Log.d(TAG,     "setDestination: lat=$lat lng=$lng requestId=$requestId attemptId=$attemptId")
                Log.d(NAV_TAG, "setDestination attemptId=$attemptId lat=$lat lng=$lng name='$resolvedName'")
                // setDestination returns ListenableResultFuture<RouteStatus>.
                // addOnSuccessListener / addOnFailureListener do NOT exist on this type.
                // Route readiness is signalled via RouteChangedListener (see setupListeners).
                nav.setDestination(waypoint, RoutingOptions())
            }
        }
    }

    override fun stopNavigation() {
        Log.d(TAG,     "stopNavigation: attemptId=$navAttemptId sessionActive=$sessionActive")
        Log.d(NAV_TAG, "stopNavigation attemptId=$navAttemptId")
        clearReroutingState("navigation-stopped")
        sessionActive  = false
        navigator?.stopGuidance()
        // Clear the active destination so the SDK does not hold onto stale route
        // state between trips. The next startNavigation call will set a new one.
        @Suppress("TryCatchInsteadOfSafe")
        try { navigator?.clearDestinations() } catch (_: Exception) { /* SDK may not expose this on all versions */ }
        guidanceStarted = false
        _state.value = NavigationState()
    }

    override fun createNavigationView(context: Context): View {
        Log.d(TAG, "createNavigationView: resetting isViewDestroyed flag")
        isViewDestroyed = false
        val view = NavigationView(context)
        navigationView = view
        // onCreate must be called here, during composition (inside remember {} in
        // NavigationScreen), so that the view is non-null when DisposableEffect
        // side-effects run and the lifecycle observer replays ON_START / ON_RESUME.
        view.onCreate(null)

        // ── Map readiness: blue dot + initial camera ───────────────────
        //
        // getMapAsync delivers the underlying GoogleMap on the Main thread once it is
        // ready.  We do two things here:
        //
        //  1. Enable isMyLocationEnabled — shows the blue dot and the "my location" button.
        //     Permission is guaranteed at this point: NavigationScreen is only shown after
        //     navigation starts, which requires the engine to have initialised, which
        //     requires ACCESS_FINE_LOCATION to be granted.
        //
        //  2. Animate the camera to the user's current position exactly ONCE.  If a fix
        //     is already cached we move immediately; otherwise we wait for the first fix
        //     from locationFlow (which is a StateFlow — it replays the latest value).
        //     The job is stored in mapCameraJob so onViewDestroy can cancel it if the
        //     NavigationScreen unmounts before a fix arrives.
        //
        //  The Navigation SDK takes over camera control once guidance starts, so this
        //  initial move only matters for the brief "Calculating route…" window.
        view.getMapAsync { map ->
            googleMap = map

            try {
                @Suppress("MissingPermission")
                map.isMyLocationEnabled = true
                Log.i(MAP_TAG, "USER_LOCATION_LAYER_ENABLED")
            } catch (e: SecurityException) {
                Log.w(MAP_TAG, "isMyLocationEnabled: permission not granted — ${e.message}")
            }

            // Cancel any stale camera job from a previous navigation attempt.
            mapCameraJob?.cancel()

            val cachedFix = LocationProvider.cachedLocation
            if (cachedFix != null) {
                // Fix already available — move camera synchronously (we're on Main).
                val latLng = LatLng(cachedFix.latitude, cachedFix.longitude)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DRIVING_ZOOM_LEVEL))
                Log.i(MAP_TAG,
                    "MAP_CAMERA_MOVED_TO_CURRENT_LOCATION" +
                    " lat=${cachedFix.latitude} lng=${cachedFix.longitude} (immediate)")
            } else {
                // No fix yet — wait for the first non-null emission from locationFlow.
                // filterNotNull().first() collects exactly one value then completes.
                mapCameraJob = mainScope.launch {
                    val loc = LocationProvider.locationFlow
                        .filterNotNull()
                        .first()
                    val latLng = LatLng(loc.latitude, loc.longitude)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, DRIVING_ZOOM_LEVEL))
                    Log.i(MAP_TAG,
                        "MAP_CAMERA_MOVED_TO_CURRENT_LOCATION" +
                        " lat=${loc.latitude} lng=${loc.longitude} (after fix)")
                }
            }
        }

        return view
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
        Log.d(TAG,     "onViewDestroy: tearing down NavigationView only (Navigator stays alive) attemptId=$navAttemptId")
        Log.d(MAP_TAG, "NavigationView onDestroy (view-only teardown, navigator alive)")
        // Cancel the safety-timeout job: the view is gone, showing the banner is meaningless.
        reroutingTimeoutJob?.cancel()
        reroutingTimeoutJob = null
        // Cancel the pending camera-move job so it doesn't fire on a new view.
        mapCameraJob?.cancel()
        mapCameraJob = null
        googleMap = null
        navigationView?.onDestroy()
        navigationView = null
    }

    /**
     * Full teardown — NavigationView + Navigator. Called ONLY from
     * [MainActivity.onDestroy] via [NavigationController.onDestroy].
     * Never call this from a composable or DisposableEffect.
     */
    override fun onDestroy() {
        Log.d(TAG,     "onDestroy: full teardown (Activity destroyed) attemptId=$navAttemptId")
        Log.d(NAV_TAG, "onDestroy attemptId=$navAttemptId")
        Log.d(MAP_TAG, "NavigationView onDestroy (full teardown path)")
        reroutingTimeoutJob?.cancel()  // cancel before mainScope dies to avoid log noise
        reroutingTimeoutJob = null
        sessionActive  = false
        guidanceStarted = false
        mainScope.coroutineContext[Job]?.cancel()  // cancel all mainScope children (incl. any race survivors)
        onViewDestroy()                // tears down NavigationView (idempotent)
        navigator?.cleanup()
        navigator = null
    }

    // ── Private: rerouting state ──────────────────────────────────────────

    /**
     * Canonical exit path for rerouting state.
     *
     * Cancels any pending safety-timeout job, invalidates its generation so a
     * late-firing coroutine is a no-op, and clears [NavigationState.isRerouting]
     * if it was true.  All call sites pass a [reason] tag that appears in
     * `REROUTE_STATE_CLEARED` log lines.
     *
     * Must be called on the Main thread (all Navigator callbacks and coroutines
     * that write [_state] run on Main).
     */
    private fun clearReroutingState(reason: String) {
        reroutingTimeoutJob?.cancel()
        reroutingTimeoutJob = null
        ++reroutingGeneration          // invalidate any in-flight timeout coroutine
        if (_state.value.isRerouting) {
            Log.d(NAV_TAG, "REROUTE_STATE_CLEARED reason=$reason")
            Log.d(NAV_TAG, "REROUTE_UI_VISIBLE=false")
            _state.value = _state.value.copy(isRerouting = false)
        }
    }

    // ── Private: listeners ────────────────────────────────────────────────

    private fun setupListeners(nav: Navigator) {
        nav.addRemainingTimeOrDistanceChangedListener(5, 10) {
            // After a re-route the first distance/time-change callback signals the new
            // route is settled.  clearReroutingState cancels the safety-timeout job and
            // clears the flag atomically; syncStateFromNavigator then fills in the fresh
            // distance/duration values.
            if (_state.value.isRerouting) {
                Log.d(NAV_TAG, "REROUTE_COMPLETED")
                clearReroutingState("route-received")
            }
            syncStateFromNavigator(nav)
        }

        // Route changed — fires for both initial route calculation AND re-routes.
        //
        // startGuidance() is the missing call in the original code. setDestination()
        // only requests a route; startGuidance() begins turn-by-turn guidance and
        // activates maneuver / distance callbacks. The guidanceStarted flag ensures it
        // is called exactly once per navigation session, not on every re-route.
        //
        // sessionActive guard: the SDK may fire one final RouteChangedListener callback
        // after stopGuidance() on some devices. Without the guard the listener would see
        // guidanceStarted==false (already reset by stopNavigation) and call startGuidance
        // on a dead session, corrupting second-trip state.
        nav.addRouteChangedListener(RouteChangedListener {
            val attempt = navAttemptId
            Log.d(TAG, "routeChangedListener: attemptId=$attempt sessionActive=$sessionActive guidanceStarted=$guidanceStarted")
            if (!sessionActive) {
                Log.w(NAV_TAG, "routeChangedListener: sessionActive=false (attemptId=$attempt) — stale callback, ignoring")
                return@RouteChangedListener
            }
            if (!guidanceStarted) {
                guidanceStarted = true
                Log.d(TAG,     "first route ready — calling startGuidance() attemptId=$attempt")
                Log.d(NAV_TAG, "startGuidance attemptId=$attempt")
                nav.startGuidance()
                _state.value = _state.value.copy(
                    isNavigating = true,
                    isRerouting  = false,
                    phase        = NavigationPhase.NAVIGATING,
                )
            } else {
                Log.d(TAG, "re-route — route updated attemptId=$attempt")
                if (_state.value.isRerouting) {
                    // RouteChangedListener can fire multiple times for the same physical
                    // reroute event (e.g. two SDK callbacks in quick succession for traffic
                    // recalculation).  Ignore duplicates: the existing spinner and its
                    // safety-timeout continue counting down unchanged.
                    Log.d(NAV_TAG, "routeChangedListener: already rerouting — ignoring duplicate (attemptId=$attempt)")
                } else {
                    val gen = ++reroutingGeneration
                    Log.d(NAV_TAG, "REROUTE_STARTED reason=route-changed-sdk attemptId=$attempt")
                    _state.value = _state.value.copy(isRerouting = true)
                    Log.d(NAV_TAG, "REROUTE_UI_VISIBLE=true attemptId=$attempt")
                    // Schedule a safety timeout.  The distance/time-change listener clears
                    // the flag normally once the new route settles; this job fires only if
                    // that callback is unexpectedly delayed (e.g. stationary device, SDK bug).
                    // It clears ONLY the visual flag — navigation continues unaffected.
                    reroutingTimeoutJob?.cancel()
                    reroutingTimeoutJob = mainScope.launch {
                        delay(REROUTE_TIMEOUT_MS)
                        if (reroutingGeneration == gen && _state.value.isRerouting) {
                            Log.w(NAV_TAG, "REROUTE_STATE_CLEARED reason=timeout gen=$gen attemptId=$attempt")
                            Log.d(NAV_TAG, "REROUTE_UI_VISIBLE=false")
                            _state.value = _state.value.copy(isRerouting = false)
                            reroutingTimeoutJob = null
                        }
                    }
                }
                return@RouteChangedListener
            }
            syncStateFromNavigator(nav)
        })

        nav.addArrivalListener(ArrivalListener { _ ->
            val attempt = navAttemptId
            Log.d(TAG, "arrivalListener: attemptId=$attempt sessionActive=$sessionActive")
            if (!sessionActive) {
                Log.w(NAV_TAG, "arrivalListener: sessionActive=false (attemptId=$attempt) — stale callback, ignoring")
                return@ArrivalListener
            }
            Log.d(NAV_TAG, "arrived attemptId=$attempt")
            sessionActive = false   // arrival ends the session; no more callbacks expected
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
        // per-step distance becomes available in the public SDK API.
        //
        // isRerouting is intentionally NOT modified here — it is set to true by the
        // RouteChangedListener re-route branch and cleared by the first subsequent
        // RemainingTimeOrDistanceChangedListener callback so the overlay stays visible
        // until real route data arrives.
        val td = nav.currentTimeAndDistance
        val distMeters = td?.meters?.toInt() ?: Int.MAX_VALUE
        val durSeconds = td?.seconds?.toInt() ?: 0
        Log.d(TAG, "syncState: dist=$distMeters m dur=$durSeconds s")
        _state.value = _state.value.copy(
            maneuverType = ManeuverType.UNKNOWN,
            distanceToNextManeuverMeters = distMeters,
            remainingDistanceMeters = distMeters,
            remainingDurationSeconds = durSeconds,
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

        // ── 1b. Street + number path ─────────────────────────────────────
        // Queries from DestinationResolver step C always end with ", Lithuania".
        // Route them to a dedicated path that tries multiple canonical variants and
        // validates each Geocoder result (rejects city-only / no house number / wrong city).
        val streetWithCity = STREET_QUERY_WITH_CITY.find(destination)
        val streetNoCity   = STREET_QUERY_NO_CITY.find(destination)
        val streetMatch    = streetWithCity ?: streetNoCity
        if (streetMatch != null) {
            val streetPart = streetMatch.groupValues[1].trim()
            val numberPart = streetMatch.groupValues[2].trim()
            val cityPart   = if (streetWithCity != null) streetMatch.groupValues[3].trim() else null
            Log.d("KentasDestination",
                "resolveAddress: routing to street+number path street='$streetPart' " +
                "number='$numberPart' city='$cityPart'")
            return resolveStreetAddress(context, streetPart, numberPart, cityPart)
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
     * Resolves a street + house-number address by trying an ordered list of geocoding
     * candidates produced by [DestinationResolver.buildStreetCandidateQueries].
     *
     * Each Android Geocoder result is validated with [isValidStreetResult]; results
     * lacking a thoroughfare (city-centre fallback), missing a house number, or placed
     * in the wrong city are rejected with a detailed log entry. Google Geocoding API is
     * tried as a per-candidate fallback for devices where the system Geocoder is unreliable.
     *
     * Logs every attempt and rejection under tag "KentasDestination".
     *
     * @return `Triple(lat, lng, displayName)` for the first accepted result, or `null`
     *   when all candidates fail (caller will speak "Pasakyk visą adresą su miestu.").
     */
    private suspend fun resolveStreetAddress(
        context: Context,
        streetPart: String,
        numberPart: String,
        cityPart: String?,
    ): Triple<Double, Double, String>? {
        val apiKey     = BuildConfig.GOOGLE_MAPS_API_KEY
        val candidates = DestinationResolver.buildStreetCandidateQueries(streetPart, numberPart, cityPart)
        Log.d("KentasDestination",
            "resolveStreetAddress: ${candidates.size} candidate(s) for " +
            "street='$streetPart' number='$numberPart' city='$cityPart': $candidates")

        for (candidate in candidates) {
            Log.d("KentasDestination", "resolveStreetAddress: trying '$candidate'")

            // ── Android Geocoder ──────────────────────────────────────────
            val addresses = geocodeWithAndroid(context, candidate)
            Log.d("KentasDestination",
                "resolveStreetAddress: ${addresses.size} Geocoder result(s) for '$candidate'")
            for (addr in addresses) {
                val formattedAddr = runCatching {
                    (0..addr.maxAddressLineIndex).joinToString(", ") { addr.getAddressLine(it) }
                }.getOrElse { "" }
                Log.d("KentasDestination",
                    "resolveStreetAddress: result " +
                    "thoroughfare='${addr.thoroughfare}' " +
                    "subThoroughfare='${addr.subThoroughfare}' " +
                    "locality='${addr.locality}' " +
                    "subAdminArea='${addr.subAdminArea}' " +
                    "featureName='${addr.featureName}' " +
                    "formattedAddress='$formattedAddr' " +
                    "lat=${addr.latitude} lng=${addr.longitude}")
                val score = scoreStreetResult(
                    thoroughfare     = addr.thoroughfare,
                    subThoroughfare  = addr.subThoroughfare,
                    locality         = addr.locality,
                    subAdminArea     = addr.subAdminArea,
                    featureName      = addr.featureName,
                    formattedAddress = formattedAddr,
                    expectedStreet   = streetPart,
                    expectedNumber   = numberPart,
                    expectedLocality = cityPart,
                )
                if (score >= SCORE_THRESHOLD) {
                    val displayName = buildDisplayName(addr, "$streetPart $numberPart")
                    Log.d("KentasDestination",
                        "resolveStreetAddress: ACCEPTED score=$score via Geocoder '$candidate' → name='$displayName'")
                    return Triple(addr.latitude, addr.longitude, displayName)
                } else {
                    Log.d("KentasDestination",
                        "resolveStreetAddress: REJECTED score=$score for '$candidate'")
                }
            }

            // ── Google Geocoding API fallback (per candidate) ─────────────
            if (apiKey.isNotBlank()) {
                Log.d("KentasDestination", "resolveStreetAddress: Google API fallback for '$candidate'")
                val result = geocodeWithGoogleApi(candidate, apiKey)
                if (result != null) {
                    Log.d("KentasDestination",
                        "resolveStreetAddress: ACCEPTED via Google API '$candidate' " +
                        "→ lat=${result.first} lng=${result.second} name='${result.third}'")
                    return result
                }
                Log.d("KentasDestination",
                    "resolveStreetAddress: Google API — no result for '$candidate'")
            }
        }

        Log.w("KentasDestination",
            "resolveStreetAddress: all ${candidates.size} candidate(s) failed/rejected " +
            "for street='$streetPart' number='$numberPart' city='$cityPart'")
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
