package lt.sturmanas.bajeristas.navigation

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import lt.sturmanas.bajeristas.BuildConfig
import lt.sturmanas.bajeristas.voice.ai.VoiceDestinationChoice
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin coroutine wrapper around the Google Places SDK (New API).
 *
 * ## Setup (one-time, per developer machine)
 * 1. Enable "Places API (New)" in your Google Cloud Console project.
 * 2. The same `GOOGLE_MAPS_API_KEY` in `local.properties` is used — no extra key required.
 *
 * ## Call flow (typed path — unchanged)
 * ```
 * StartScreen types → getSuggestions() → List<AutocompletePrediction>
 * User taps row   → resolveCoordinates(placeId) → Pair<Double, Double>
 * onStartNavigation("$lat,$lng") → existing GoogleNavigationEngine coordinate path
 * ```
 *
 * ## Call flow (voice path)
 * ```
 * Kentas hears command → handleVoiceNavigation()
 *   category query  → searchNearbyByType()     → List<VoiceDestinationChoice>
 *   chain name      → searchByTextNearby()     → List<VoiceDestinationChoice>
 *   free-text query → getSuggestionsAsVoiceChoices() → List<VoiceDestinationChoice>
 * User selects     → resolveCoordinates(placeId) → Pair<Double,Double> → navigate
 * ```
 *
 * If the key is absent (CI / MockNavigationEngine builds) every call returns an empty
 * list / null, which is safe — the existing Geocoder fallback handles typed text.
 */
object PlacesAutocompleteClient {

    private const val TAG = "PlacesAutoClient"
    private const val MAX_SUGGESTIONS = 5

    @Volatile
    private var cachedClient: com.google.android.libraries.places.api.net.PlacesClient? = null

    /** Rectangular bias half-size in degrees (~55 km lat, ~35 km lng in Lithuania). */
    private const val BIAS_DELTA_DEG = 0.5

    /** Nearby Search radius in metres (10 km). */
    private const val NEARBY_RADIUS_M = 10_000.0

    /** Text Search bias radius in metres (50 km). */
    private const val TEXT_BIAS_RADIUS_M = 50_000.0

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Initialise the Places SDK once per process.
     * Safe to call multiple times — the SDK ignores subsequent calls.
     * Returns false when the API key is absent (no-op; mock / CI builds).
     */
    fun initialize(context: Context): Boolean {
        val key = BuildConfig.GOOGLE_MAPS_API_KEY
        if (key.isBlank()) return false
        
        val appContext = context.applicationContext
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(appContext, key)
            Log.d(TAG, "Places SDK initialised")
        }
        
        if (cachedClient == null) {
            cachedClient = Places.createClient(appContext)
            Log.i(TAG, "PLACES_CLIENT_CREATED")
        }
        return true
    }

    // ── Typed autocomplete (existing — unchanged public signature) ────────────

    /**
     * Returns up to [maxResults] autocomplete predictions for [query].
     * Used by the typed StartScreen flow and as the voice free-text fallback.
     *
     * When [latitude]/[longitude] are provided, results near the user are ranked
     * first via `setOrigin` + `setLocationBias` (rectangular box ±[BIAS_DELTA_DEG]°).
     * Falls back to unbiased countrywide LT search when location is absent.
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

        Log.i(TAG, "PLACES_REQUEST_START type=getSuggestions query='$query'")
        return try {
            val client = cachedClient!!
            val builder = FindAutocompletePredictionsRequest.builder()
                .setQuery(query)
                .setCountries(listOf("LT"))

            if (latitude != null && longitude != null) {
                val origin = LatLng(latitude, longitude)
                builder.setOrigin(origin)
                val sw = LatLng(latitude - BIAS_DELTA_DEG, longitude - BIAS_DELTA_DEG)
                val ne = LatLng(latitude + BIAS_DELTA_DEG, longitude + BIAS_DELTA_DEG)
                builder.setLocationBias(RectangularBounds.newInstance(sw, ne))
                Log.d(TAG, "getSuggestions bias lat=$latitude lng=$longitude")
            }

            val response = client.findAutocompletePredictions(builder.build()).awaitResult()
            Log.i(TAG, "PLACES_REQUEST_SUCCESS type=getSuggestions")
            response.autocompletePredictions.take(maxResults)
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "getSuggestions failed for '$query': ${e.message}")
            }
            emptyList()
        } finally {
            Log.i(TAG, "PLACES_REQUEST_FINISHED type=getSuggestions")
        }
    }

    // ── Voice search — returns VoiceDestinationChoice ────────────────────────

    /**
     * Wraps [getSuggestions] and converts results to [VoiceDestinationChoice].
     * Used by the voice free-text path so AIConversationController does not need to
     * depend on [AutocompletePrediction].
     */
    suspend fun getSuggestionsAsVoiceChoices(
        context: Context,
        query: String,
        latitude: Double? = null,
        longitude: Double? = null,
        maxResults: Int = 3,
    ): List<VoiceDestinationChoice> =
        getSuggestions(context, query, latitude, longitude, maxResults)
            .map { it.toVoiceDestinationChoice() }

    /**
     * Searches for nearby places of [placeType] (Google Place type string, e.g.
     * "pharmacy", "gas_station", "restaurant") around the given coordinates using
     * the Places SDK (New) Nearby Search.  Returns up to [maxResults] results
     * sorted by distance, or an empty list on any error.
     *
     * Requires location — if coordinates are null, returns empty list immediately.
     */
    suspend fun searchNearbyByType(
        context: Context,
        latitude: Double,
        longitude: Double,
        placeType: String,
        maxResults: Int = 3,
    ): List<VoiceDestinationChoice> {
        if (!initialize(context)) return emptyList()

        Log.i(TAG, "PLACES_REQUEST_START type=searchNearbyByType type='$placeType'")
        return try {
            val client = cachedClient!!
            val center = LatLng(latitude, longitude)
            val bounds = CircularBounds.newInstance(center, NEARBY_RADIUS_M)
            val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS)

            val request = SearchNearbyRequest.builder(bounds, fields)
                .setIncludedTypes(listOf(placeType))
                .setMaxResultCount(maxResults)
                .build()

            val response = client.searchNearby(request).awaitResult()
            Log.i(TAG, "PLACES_REQUEST_SUCCESS type=searchNearbyByType count=${response.places.size}")
            response.places.take(maxResults).map { it.toVoiceDestinationChoice() }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "searchNearbyByType failed type='$placeType': ${e.message}")
            }
            emptyList()
        } finally {
            Log.i(TAG, "PLACES_REQUEST_FINISHED type=searchNearbyByType")
        }
    }

    /**
     * Searches for a named chain (e.g. "Maxima", "Lidl") near the user using
     * the Places SDK (New) Text Search with a [CircularBounds] location bias.
     * Falls back to unbiased text search when coordinates are absent.
     *
     * Returns up to [maxResults] results or an empty list on any error.
     */
    suspend fun searchByTextNearby(
        context: Context,
        textQuery: String,
        latitude: Double? = null,
        longitude: Double? = null,
        maxResults: Int = 3,
    ): List<VoiceDestinationChoice> {
        if (!initialize(context)) return emptyList()

        Log.i(TAG, "PLACES_REQUEST_START type=searchByTextNearby query='$textQuery'")
        return try {
            val client = cachedClient!!
            val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS)

            val builder = SearchByTextRequest.builder(textQuery, fields)
                .setMaxResultCount(maxResults)

            if (latitude != null && longitude != null) {
                val center = LatLng(latitude, longitude)
                builder.setLocationBias(CircularBounds.newInstance(center, TEXT_BIAS_RADIUS_M))
                Log.d(TAG, "searchByTextNearby query='$textQuery' lat=$latitude lng=$longitude")
            }

            val response = client.searchByText(builder.build()).awaitResult()
            Log.i(TAG, "PLACES_REQUEST_SUCCESS type=searchByTextNearby count=${response.places.size}")
            response.places.take(maxResults).map { it.toVoiceDestinationChoice() }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "searchByTextNearby failed query='$textQuery': ${e.message}")
            }
            emptyList()
        } finally {
            Log.i(TAG, "PLACES_REQUEST_FINISHED type=searchByTextNearby")
        }
    }

    // ── Coordinates ───────────────────────────────────────────────────────────

    /**
     * Resolves a [placeId] to latitude/longitude using the Places SDK.
     * Returns `null` if the fetch fails.
     */
    suspend fun resolveCoordinates(
        context: Context,
        placeId: String,
    ): Pair<Double, Double>? {
        if (!initialize(context)) return null
        
        Log.i(TAG, "PLACES_REQUEST_START type=resolveCoordinates id='$placeId'")
        return try {
            val client = cachedClient!!
            @Suppress("DEPRECATION")
            val fields = listOf(Place.Field.LAT_LNG)
            val request = FetchPlaceRequest.newInstance(placeId, fields)
            val response = client.fetchPlace(request).awaitResult()
            @Suppress("DEPRECATION")
            val latLng = response.place.latLng ?: return null
            Log.i(TAG, "PLACES_REQUEST_SUCCESS type=resolveCoordinates")
            latLng.latitude to latLng.longitude
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "resolveCoordinates failed for placeId='$placeId': ${e.message}")
            }
            null
        } finally {
            Log.i(TAG, "PLACES_REQUEST_FINISHED type=resolveCoordinates")
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private val COUNTRY_SUFFIX_REGEX = Regex(",?\\s*(Lietuva|Lithuania)\\s*$")

    @Suppress("DEPRECATION")
    private fun AutocompletePrediction.toVoiceDestinationChoice() = VoiceDestinationChoice(
        placeId = this.placeId,
        name = this.getPrimaryText(null).toString(),
        shortAddress = this.getSecondaryText(null).toString()
            .replace(COUNTRY_SUFFIX_REGEX, "")
            .trim(),
    )

    @Suppress("DEPRECATION")
    private fun Place.toVoiceDestinationChoice() = VoiceDestinationChoice(
        placeId = this.id ?: "",
        name = this.name ?: "Nežinoma vieta",
        shortAddress = (this.address ?: "")
            .replace(COUNTRY_SUFFIX_REGEX, "")
            .trim(),
    )

    /**
     * Converts a Google Play Services [Task] to a suspending call.
     */
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exception ->
            if (cont.isActive) cont.resumeWithException(exception)
        }
        cont.invokeOnCancellation {
            Log.i(TAG, "PLACES_REQUEST_CANCELLED")
        }
    }
}
