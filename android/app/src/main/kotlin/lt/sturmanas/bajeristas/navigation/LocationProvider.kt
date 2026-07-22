package lt.sturmanas.bajeristas.navigation

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Provides the device's current location and reverse-geocoded locality name.
 *
 * Uses Android's [LocationManager.getLastKnownLocation] — no extra dependencies
 * beyond the Android framework. Tries GPS first (best accuracy), then NETWORK
 * (faster cold-start fix).
 *
 * Locality is reverse-geocoded via [Geocoder] and used by [DestinationResolver]
 * to bias place searches (e.g. "artimiausia degalinė" → "degalinė, Klaipėda").
 *
 * All blocking work runs on [Dispatchers.IO]; the public function is safe to call
 * from any coroutine context.
 *
 * **Requires [android.Manifest.permission.ACCESS_FINE_LOCATION] or
 * [android.Manifest.permission.ACCESS_COARSE_LOCATION] to be granted.**
 * Returns null components gracefully if the permission is absent or no fix is
 * available yet (e.g. the device just booted and GPS hasn't acquired a lock).
 */
object LocationProvider {

    private const val TAG = "KentasLocation"

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
     */
    suspend fun getCurrentLocation(context: Context): Triple<Double?, Double?, String?> =
        withContext(Dispatchers.IO) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val location = getBestLastKnownLocation(lm)
            if (location == null) {
                Log.w(TAG, "No last-known location available (GPS not yet fixed or permission missing)")
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
