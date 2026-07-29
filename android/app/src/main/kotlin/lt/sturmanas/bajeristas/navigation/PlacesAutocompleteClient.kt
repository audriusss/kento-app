package lt.sturmanas.bajeristas.navigation

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import lt.sturmanas.bajeristas.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin coroutine wrapper around the Google Places SDK (New API).
 *
 * ## Setup (one-time, per developer machine)
 * 1. Enable "Places API (New)" in your Google Cloud Console project.
 * 2. The same `GOOGLE_MAPS_API_KEY` in `local.properties` is used — no extra key required.
 *
 * ## Call flow
 * ```
 * StartScreen types → getSuggestions() → List<AutocompletePrediction>
 * User taps row   → resolveCoordinates(placeId) → Pair<Double, Double>
 * onStartNavigation("$lat,$lng") → existing GoogleNavigationEngine coordinate path
 * ```
 *
 * If the key is absent (CI / MockNavigationEngine builds) every call returns an empty
 * list / null, which is safe — the existing Geocoder fallback in
 * [GoogleNavigationEngine.startNavigation] handles typed text as before.
 */
object PlacesAutocompleteClient {

    private const val TAG = "PlacesAutoClient"
    private const val MAX_SUGGESTIONS = 5

    /** Radius (metres) used for location-bias rectangular half-width/height. ~55 km. */
    private const val BIAS_DELTA_DEG = 0.5

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Initialise the Places SDK once per process.
     * Safe to call multiple times — the SDK ignores subsequent calls.
     * Returns false when the API key is absent (no-op; mock / CI builds).
     */
    fun initialize(context: Context): Boolean {
        val key = BuildConfig.GOOGLE_MAPS_API_KEY
        if (key.isBlank()) return false
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(context.applicationContext, key)
            Log.d(TAG, "Places SDK initialised")
        }
        return true
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns up to [maxResults] autocomplete predictions for [query].
     *
     * Supports:
     * - Partial addresses ("Taikos pr")
     * - Business names ("Maxima Vilniuje")
     * - POIs ("Gedimino pilies bokštas")
     * - Categories ("vaistinė", "degalinė", "restoranas")
     *
     * Results are restricted to Lithuania (`LT`).
     * When [latitude]/[longitude] are provided, results near the user are ranked
     * first via `setOrigin` + `setLocationBias` (rectangular box of ±[BIAS_DELTA_DEG]°
     * around the user — roughly ±55 km latitude, ±35 km longitude in Lithuania).
     * Falls back to unbiased countrywide search when location is absent.
     *
     * Returns an empty list on any error — the caller always degrades gracefully.
     */
    suspend fun getSuggestions(
        context: Context,
        query: String,
        latitude: Double? = null,
        longitude: Double? = null,
        maxResults: Int = MAX_SUGGESTIONS,
    ): List<AutocompletePrediction> {
        if (query.isBlank()) return emptyList()
        if (!initialize(context)) return emptyList()

        return try {
            val client = Places.createClient(context)
            val builder = FindAutocompletePredictionsRequest.builder()
                .setQuery(query)
                .setCountries(listOf("LT"))

            if (latitude != null && longitude != null) {
                val origin = LatLng(latitude, longitude)
                // setOrigin lets the SDK compute distances and rank nearby results first.
                builder.setOrigin(origin)
                // setLocationBias nudges the ranking — does NOT restrict to the box,
                // so Lithuania-wide results are still returned when nothing is nearby.
                val sw = LatLng(latitude - BIAS_DELTA_DEG, longitude - BIAS_DELTA_DEG)
                val ne = LatLng(latitude + BIAS_DELTA_DEG, longitude + BIAS_DELTA_DEG)
                builder.setLocationBias(RectangularBounds.newInstance(sw, ne))
                Log.d(TAG, "getSuggestions bias lat=$latitude lng=$longitude")
            }

            val response = client.findAutocompletePredictions(builder.build()).awaitResult()
            response.autocompletePredictions.take(maxResults)
        } catch (e: Exception) {
            Log.w(TAG, "getSuggestions failed for '$query': ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolves a [placeId] to latitude/longitude using the Places SDK.
     *
     * Returns `null` if the fetch fails, allowing the caller to fall back to
     * the existing typed-text geocoder path in [GoogleNavigationEngine].
     */
    suspend fun resolveCoordinates(
        context: Context,
        placeId: String,
    ): Pair<Double, Double>? {
        if (!initialize(context)) return null
        return try {
            val client = Places.createClient(context)
            @Suppress("DEPRECATION")   // LAT_LNG is the v3 field name; LOCATION is v4+
            val fields = listOf(Place.Field.LAT_LNG)
            val request = FetchPlaceRequest.newInstance(placeId, fields)
            val response = client.fetchPlace(request).awaitResult()
            @Suppress("DEPRECATION")
            val latLng = response.place.latLng ?: return null
            latLng.latitude to latLng.longitude
        } catch (e: Exception) {
            Log.w(TAG, "resolveCoordinates failed for placeId='$placeId': ${e.message}")
            null
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Converts a Google Play Services [Task] to a suspending call.
     * Cancellation is propagated: if the coroutine is cancelled the task is
     * not cancelled (the SDK owns it), but the continuation is simply not resumed.
     */
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exception ->
            if (cont.isActive) cont.resumeWithException(exception)
        }
    }
}
