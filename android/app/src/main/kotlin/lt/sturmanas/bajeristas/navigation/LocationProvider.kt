package lt.sturmanas.bajeristas.navigation

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Provides the device's current location and reverse-geocoded locality name.
 *
 * Uses Android's [LocationManager] — no extra dependencies beyond the Android framework.
 * Tries GPS first (best accuracy), then NETWORK (faster cold-start fix).
 *
 * ## Continuous updates
 *
 * Call [startUpdates] from the ViewModel's [init] block to begin receiving location fixes
 * as soon as the app launches. This populates [cachedLocation] so that the first voice
 * command benefits from a real position rather than a stale last-known fix or null.
 * Call [stopUpdates] from [ViewModel.onCleared] to unsubscribe.
 *
 * ## One-shot lookup
 *
 * [getCurrentLocation] still works as a standalone fallback but is now secondary —
 * callers should prefer reading [cachedLocation] directly when updates are running.
 *
 * Locality is reverse-geocoded via [Geocoder] and used by [DestinationResolver]
 * to bias place searches (e.g. "artimiausia degalinė" → "degalinė, Klaipėda").
 *
 * All blocking work runs on [Dispatchers.IO]; the public functions are safe to call
 * from any coroutine context.
 *
 * **Requires [android.Manifest.permission.ACCESS_FINE_LOCATION] or
 * [android.Manifest.permission.ACCESS_COARSE_LOCATION] to be granted.**
 * Returns null components gracefully if the permission is absent or no fix is
 * available yet (e.g. the device just booted and GPS hasn't acquired a lock).
 */
object LocationProvider {

    private const val TAG = "KentasLocation"

    /** Minimum time between location update callbacks (30 seconds). */
    private const val UPDATE_INTERVAL_MS = 30_000L

    /** Minimum distance before a location update callback is triggered (100 m). */
    private const val UPDATE_MIN_DISTANCE_M = 100f

    /**
     * The most recent location fix delivered by [startUpdates].
     * Null until the first update arrives or when [stopUpdates] clears it.
     */
    @Volatile
    var cachedLocation: Location? = null
        private set

    private var locationListener: LocationListener? = null

    // ── Continuous-update API ──────────────────────────────────────────────

    /**
     * Starts requesting periodic location updates from the best available provider.
     *
     * Updates arrive at most every [UPDATE_INTERVAL_MS] ms and/or [UPDATE_MIN_DISTANCE_M] m.
     * Each fix is stored in [cachedLocation] so [resolveAndNavigate] can read it without
     * an additional blocking call.
     *
     * Safe to call multiple times — existing listener is removed before registering a new one.
     * Must be called on the Main thread (LocationManager requirement on some API levels).
     *
     * @param context Application context.
     * @param onUpdate Optional callback invoked on every new fix (on the calling thread/looper).
     */
    fun startUpdates(context: Context, onUpdate: ((Location) -> Unit)? = null) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Remove any previous listener first.
        locationListener?.let { lm.removeUpdates(it) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d(TAG, "Location update: lat=${location.latitude} lng=${location.longitude} provider=${location.provider}")
                cachedLocation = location
                onUpdate?.invoke(location)
            }

            // Deprecated in API 29 but required for compatibility with older devices.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        locationListener = listener

        val providers = buildList {
            // Prefer FUSED on API 31+ for best accuracy with battery efficiency.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }

        var registered = false
        for (provider in providers) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    @Suppress("MissingPermission")
                    lm.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MS,
                        UPDATE_MIN_DISTANCE_M,
                        listener,
                        Looper.getMainLooper(),
                    )
                    Log.d(TAG, "Registered location updates on provider '$provider'")
                    registered = true
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates($provider) failed: ${e.message}")
            }
        }

        if (!registered) {
            Log.w(TAG, "No location provider available for continuous updates")
        }

        // Seed cachedLocation immediately from last-known so the ViewModel has
        // something useful before the first update callback fires.
        if (cachedLocation == null) {
            cachedLocation = getBestLastKnownLocation(lm)?.also {
                Log.d(TAG, "Seeded cachedLocation from last-known: lat=${it.latitude} lng=${it.longitude}")
            }
        }
    }

    /**
     * Stops location updates and clears [cachedLocation].
     * Call from [ViewModel.onCleared] to release the system resource.
     */
    fun stopUpdates(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener?.let {
            lm.removeUpdates(it)
            Log.d(TAG, "Location updates stopped")
        }
        locationListener = null
        cachedLocation = null
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
     * Failure is always silent — a null result means [DestinationResolver] falls
     * back to unbiased searches rather than throwing or blocking the user.
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

            val lat = location.latitude
            val lng = location.longitude
            val ageMs = System.currentTimeMillis() - location.time
            Log.d(TAG, "Got location: lat=$lat lng=$lng provider=${location.provider} age=${ageMs}ms")

            val locality = reverseGeocodeLocality(context, lat, lng)
            Log.d(TAG, "Reverse-geocoded locality: '$locality'")

            Triple(lat, lng, locality)
        }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Tries GPS, then NETWORK provider in order and returns the first non-null
     * last-known location. Returns null if neither provider has a cached fix.
     * All SecurityException / IllegalArgumentException errors are swallowed so
     * a missing permission does not crash — the caller receives null instead.
     */
    private fun getBestLastKnownLocation(lm: LocationManager): android.location.Location? {
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            // FUSED_PROVIDER is available on API 31+; include it as an additional candidate.
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
     * Reverse-geocodes [lat]/[lng] to the most specific available locality name.
     *
     * Priority: locality → subAdminArea → adminArea (broadest fallback).
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
