package lt.sturmanas.bajeristas.navigation

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Provides the device's current location and reverse-geocoded locality name.
 *
 * Uses Google Play Services [FusedLocationProviderClient] for the best available fix
 * that blends GPS, Wi-Fi, and cell-tower data.  Unlike the raw [LocationManager] GPS
 * provider, fused location delivers an initial fix within seconds using network/Wi-Fi
 * triangulation without waiting for a cold GPS satellite lock.
 *
 * ## Continuous updates
 *
 * Call [startUpdates] from the ViewModel's `init` block.  This seeds [cachedLocation]
 * from the last-known fused fix immediately, then begins receiving fresh callbacks.
 * Call [stopUpdates] from `ViewModel.onCleared`.
 *
 * ## Location services check
 *
 * [locationServicesEnabled] reflects whether the device's location switch is on.
 * When false, [startUpdates] logs a warning and does NOT register callbacks (there is
 * nothing to receive). The UI should prompt the user to enable Location Services.
 *
 * ## One-shot lookup
 *
 * [getCurrentLocation] works as a standalone fallback but callers should prefer
 * reading [cachedLocation] directly when [startUpdates] is running.
 *
 * All blocking work runs on [Dispatchers.IO].
 *
 * **Requires [android.Manifest.permission.ACCESS_FINE_LOCATION] or
 * [android.Manifest.permission.ACCESS_COARSE_LOCATION].**
 */
object LocationProvider {

    private const val TAG = "KentasLocation"

    /** Interval between fused location callbacks (30 s). */
    private const val UPDATE_INTERVAL_MS = 30_000L

    /** Minimum distance before a new callback is delivered (100 m). */
    private const val UPDATE_MIN_DISTANCE_M = 100f

    /**
     * Maximum age of a GPS/network fix that is still trusted for city (locality) derivation.
     * Fixes older than this are coordinates-only — the locality is returned as null so the
     * caller can ask the user for the city rather than silently routing to the wrong one.
     * Exposed as [internal] so [MainViewModel] can include the threshold in diagnostic logs.
     */
    internal const val LOCATION_MAX_AGE_MS = 5 * 60 * 1_000L

    /**
     * How long a cached reverse-geocode locality result is considered fresh.
     * 15 minutes: long enough to avoid repeated geocoder calls during a drive,
     * short enough to catch a city change if the user travels between towns.
     */
    private const val LOCALITY_CACHE_TTL_MS = 15 * 60 * 1_000L

    /** Distance threshold beyond which the locality cache is invalidated (~500 m). */
    private const val LOCALITY_CACHE_MAX_DISTANCE_M = 500.0

    // ── Public state ───────────────────────────────────────────────────────

    /**
     * The most recent location fix delivered by [startUpdates].
     * Null until the first update arrives or when [stopUpdates] clears it.
     */
    @Volatile
    var cachedLocation: Location? = null
        private set

    /**
     * StateFlow of the most recent known location.
     * Emits the last-known fused seed immediately on [startUpdates] (if one exists),
     * then emits each fresh callback. Emits null when [stopUpdates] is called.
     *
     * This value is NEVER set to non-null by a timeout — a non-null value always
     * means a real location fix is available.
     */
    private val _locationFlow = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> = _locationFlow.asStateFlow()

    /**
     * True when the device's location switch is on and at least one provider is enabled.
     * Updated each time [startUpdates] is called.
     */
    private val _locationServicesEnabled = MutableStateFlow(true)
    val locationServicesEnabled: StateFlow<Boolean> = _locationServicesEnabled.asStateFlow()

    // ── Private state ──────────────────────────────────────────────────────

    /** True after the first fresh callback (not the last-known seed). */
    @Volatile private var freshFixLogged = false

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    // ── Locality cache ─────────────────────────────────────────────────────

    private data class LocalityCache(
        val lat: Double,
        val lng: Double,
        val locality: String?,
        val timestampMs: Long,
    )

    @Volatile
    private var localityCache: LocalityCache? = null

    // ── Continuous-update API ──────────────────────────────────────────────

    /**
     * Start (or re-start) fused location updates.
     *
     * Uses [Priority.PRIORITY_BALANCED_POWER_ACCURACY] so the OS can return a fast
     * Wi-Fi / cell fix without waiting for a cold GPS satellite lock.
     * [setWaitForAccurateLocation] is false so the first available fix is delivered
     * immediately even if accuracy is coarse.
     *
     * Safe to call multiple times — the previous callback is removed before registering
     * a new one.  Must be called on the Main thread (FusedLocationProviderClient
     * requires a [Looper]).
     *
     * @param context    Application context.
     * @param onUpdate   Optional callback invoked on every new fix (on the Main looper).
     */
    fun startUpdates(context: Context, onUpdate: ((Location) -> Unit)? = null) {
        val appCtx = context.applicationContext

        // ── Location services check ────────────────────────────────────────
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val servicesEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        _locationServicesEnabled.value = servicesEnabled
        Log.i(TAG, "LOCATION_SERVICES_ENABLED=$servicesEnabled")

        if (!servicesEnabled) {
            Log.w(TAG, "startUpdates: location services disabled — skipping callback registration")
            return
        }

        val fused = fusedClient
            ?: LocationServices.getFusedLocationProviderClient(appCtx).also { fusedClient = it }

        // Remove any previous callback before registering a new one.
        locationCallback?.let {
            fused.removeLocationUpdates(it)
            Log.d(TAG, "startUpdates: removed previous location callback")
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS,
        )
            .setMinUpdateDistanceMeters(UPDATE_MIN_DISTANCE_M)
            // Deliver the first available fix immediately even if accuracy is coarse.
            // This gives a fast Wi-Fi/cell fix before GPS locks.
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                cachedLocation = loc
                _locationFlow.value = loc
                onUpdate?.invoke(loc)
                val ageMs = System.currentTimeMillis() - loc.time
                Log.i(TAG,
                    "FUSED_LOCATION_RECEIVED" +
                    " lat=${loc.latitude} lng=${loc.longitude}" +
                    " ageMs=$ageMs accuracy=${loc.accuracy}m provider=${loc.provider}")
                if (!freshFixLogged) {
                    freshFixLogged = true
                    Log.i(TAG,
                        "FIRST FRESH LOCATION FIX:" +
                        " lat=${loc.latitude} lng=${loc.longitude}" +
                        " provider=${loc.provider} accuracy=${loc.accuracy}m")
                }
            }
        }
        locationCallback = callback

        @Suppress("MissingPermission")
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        Log.d(TAG, "startUpdates: fused location updates registered")

        // Seed cachedLocation immediately from last-known fused fix so the ViewModel has
        // something useful before the first update callback fires.
        if (cachedLocation == null) {
            @Suppress("MissingPermission")
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && cachedLocation == null) {
                    cachedLocation = loc
                    _locationFlow.value = loc
                    val ageMs = System.currentTimeMillis() - loc.time
                    Log.i(TAG,
                        "FUSED_LOCATION_RECEIVED (last-known seed)" +
                        " lat=${loc.latitude} lng=${loc.longitude}" +
                        " ageMs=$ageMs accuracy=${loc.accuracy}m")
                }
            }
        }
    }

    /**
     * Stops fused location updates and clears [cachedLocation] and the locality cache.
     * Call from `ViewModel.onCleared`.
     */
    fun stopUpdates(context: Context) {
        val fused = fusedClient
        locationCallback?.let { cb ->
            fused?.removeLocationUpdates(cb)
            Log.d(TAG, "stopUpdates: fused location updates removed")
        }
        locationCallback = null
        cachedLocation = null
        localityCache = null
        _locationFlow.value = null
        freshFixLogged = false
    }

    // ── One-shot API ───────────────────────────────────────────────────────

    /**
     * Returns the device's best last-known location and reverse-geocoded locality.
     *
     * All three components can be null:
     *  - [Triple.first]  = latitude, null if no location fix is available
     *  - [Triple.second] = longitude, null if no location fix is available
     *  - [Triple.third]  = locality name (e.g. "Klaipėda"), null if geocoding fails
     *
     * Prefer reading [cachedLocation] directly when [startUpdates] is running.
     */
    suspend fun getCurrentLocation(context: Context): Triple<Double?, Double?, String?> =
        withContext(Dispatchers.IO) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val location = cachedLocation ?: getBestLastKnownLocation(lm)
            if (location == null) {
                Log.w(TAG, "No location available (GPS not yet fixed or permission missing)")
                return@withContext Triple(null, null, null)
            }

            val lat   = location.latitude
            val lng   = location.longitude
            val ageMs = System.currentTimeMillis() - location.time

            Log.d(TAG, "Got location: lat=$lat lng=$lng provider=${location.provider} age=${ageMs}ms")
            Log.d("KentasLocationContext",
                "location lat=$lat lng=$lng provider=${location.provider} " +
                "ageMs=$ageMs cachedLocationPresent=${cachedLocation != null}")

            val locality: String?
            if (ageMs > LOCATION_MAX_AGE_MS) {
                Log.w("KentasLocationContext",
                    "location fix is stale (${ageMs}ms > ${LOCATION_MAX_AGE_MS}ms) " +
                    "— locality withheld to prevent wrong-city routing")
                locality = null
            } else {
                locality = getCachedOrFetchLocality(context, lat, lng)
                Log.d("KentasLocationContext",
                    "locality='$locality' ageMs=$ageMs cachePresent=${localityCache != null}")
            }

            Triple(lat, lng, locality)
        }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Fallback for [getCurrentLocation]: tries GPS, NETWORK, then FUSED provider
     * via raw [LocationManager.getLastKnownLocation]. Used when [cachedLocation] is null
     * (i.e. [startUpdates] hasn't received a callback yet).
     */
    private fun getBestLastKnownLocation(lm: LocationManager): Location? {
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }
        return providers.firstNotNullOfOrNull { provider ->
            try {
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(provider)
            } catch (e: Exception) {
                Log.d(TAG, "getLastKnownLocation($provider) failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Returns a cached locality if still fresh and device hasn't moved significantly;
     * otherwise fetches a fresh value via [reverseGeocodeLocality] and caches it.
     */
    private suspend fun getCachedOrFetchLocality(
        context: Context,
        lat: Double,
        lng: Double,
    ): String? {
        val cache = localityCache
        val nowMs = System.currentTimeMillis()

        if (cache != null) {
            val ageMs = nowMs - cache.timestampMs
            val distanceM = haversineDistanceM(cache.lat, cache.lng, lat, lng)
            if (ageMs < LOCALITY_CACHE_TTL_MS && distanceM < LOCALITY_CACHE_MAX_DISTANCE_M) {
                Log.d(TAG, "Locality cache hit: '${cache.locality}' age=${ageMs}ms dist=${distanceM.toInt()}m")
                return cache.locality
            }
            Log.d(TAG, "Locality cache miss: age=${ageMs}ms dist=${distanceM.toInt()}m — fetching fresh")
        }

        val locality = reverseGeocodeLocality(context, lat, lng)
        localityCache = LocalityCache(lat = lat, lng = lng, locality = locality, timestampMs = nowMs)
        return locality
    }

    /**
     * Approximate straight-line distance in metres between two WGS-84 coordinates
     * using the Haversine formula.
     */
    private fun haversineDistanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return earthRadiusM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Reverse-geocodes [lat]/[lng] to the most specific available locality name.
     * Priority: locality → subAdminArea → adminArea.
     * Returns null if [Geocoder] returns no results or throws.
     */
    private suspend fun reverseGeocodeLocality(
        context: Context,
        lat: Double,
        lng: Double,
    ): String? {
        return try {
            val geocoder = Geocoder(context, Locale("lt", "LT"))
            val addresses: List<Address> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val deferred = CompletableDeferred<List<Address>>()
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(results: MutableList<Address>) {
                            deferred.complete(results)
                        }
                        override fun onError(errorMessage: String?) {
                            Log.w(TAG, "Geocoder.GeocodeListener.onError: $errorMessage")
                            deferred.complete(emptyList())
                        }
                    })
                    deferred.await()
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1) ?: emptyList()
                }

            val addr = addresses.firstOrNull()
            addr?.locality
                ?: addr?.subAdminArea
                ?: addr?.adminArea
        } catch (e: Exception) {
            Log.w(TAG, "reverseGeocodeLocality exception: ${e.message}")
            null
        }
    }
}
