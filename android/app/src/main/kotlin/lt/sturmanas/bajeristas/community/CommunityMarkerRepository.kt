package lt.sturmanas.bajeristas.community

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Reports and retrieves community speed-camera / police markers.
 *
 * ## Anonymous device identity
 * A random [UUID] is generated on first launch and persisted in SharedPreferences.
 * It is never linked to any personal data — it only prevents one device from
 * flooding the server.
 *
 * ## Rate limiting (client side)
 * Maximum one [reportMarker] call per [REPORT_COOLDOWN_MS] (30 s) per [MarkerType].
 * Attempts within the cooldown are silently dropped and return false.
 *
 * ## Offline safety
 * Network failures are caught and logged. The UI always receives a clear boolean
 * result; it is never left in an unknown state.
 *
 * ## Approaching-marker warning
 * Call [checkApproaching] on every navigation state update. It returns a
 * [NearbyMarker] if the device is within [APPROACH_WARN_RADIUS_M] metres of a
 * confirmed marker that has not yet been announced this session.
 */
class CommunityMarkerRepository(context: Context) {

    // ── Public types ───────────────────────────────────────────────────────

    enum class MarkerType(val apiName: String, val displayName: String) {
        SPEED_CAMERA("speed_camera", "greičio matuoklis"),
        POLICE("police", "policija"),
        ACCIDENT("accident", "avarija"),
        HAZARD("hazard", "pavojus kelyje"),
    }

    data class NearbyMarker(
        val id: String,
        val type: MarkerType,
        val lat: Double,
        val lng: Double,
        val distanceMeters: Double,
    )

    // ── Constants ──────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "KentasMarkers"
        private const val PREFS_NAME = "kentas_community"
        private const val KEY_DEVICE_ID = "device_id"

        /** Client-side cooldown between marker reports per type. */
        const val REPORT_COOLDOWN_MS = 30_000L

        /** Radius within which the driver is warned of a nearby marker. */
        const val APPROACH_WARN_RADIUS_M = 200.0

        /** Bounding-box radius for marker fetch requests. */
        const val FETCH_RADIUS_M = 5000

        /** Base URL of the API server. */
        private const val BASE_URL = "https://api.example.com/api"  // override in BuildConfig or env
    }

    // ── State ──────────────────────────────────────────────────────────────

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY_DEVICE_ID, id) }
        }
    }

    /** Last report timestamp per marker type for client-side rate limiting. */
    private val lastReportMs = mutableMapOf<MarkerType, Long>()

    /** Marker IDs already announced this session (to avoid repeating the warning). */
    private val announcedIds = mutableSetOf<String>()

    /** In-memory cache of nearby markers (refreshed by [fetchNearbyMarkers]). */
    @Volatile
    private var cachedMarkers: List<NearbyMarker> = emptyList()

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Report a new marker at [lat]/[lng].
     *
     * Returns true if the report was accepted by the server, false on rate-limit
     * or network failure.  Never throws.
     */
    suspend fun reportMarker(type: MarkerType, lat: Double, lng: Double): Boolean =
        withContext(Dispatchers.IO) {
            // Client-side rate limit
            val now = System.currentTimeMillis()
            val lastMs = lastReportMs[type] ?: 0L
            if (now - lastMs < REPORT_COOLDOWN_MS) {
                Log.d(TAG, "reportMarker: rate limited (${type.apiName})")
                return@withContext false
            }

            try {
                val body = JSONObject().apply {
                    put("type", type.apiName)
                    put("lat", lat)
                    put("lng", lng)
                    put("deviceId", deviceId)
                }.toString()

                val conn = (URL("$BASE_URL/markers").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    connectTimeout = 8_000
                    readTimeout = 10_000
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                conn.disconnect()

                if (code == HttpURLConnection.HTTP_CREATED || code == HttpURLConnection.HTTP_OK) {
                    lastReportMs[type] = now
                    Log.d(TAG, "reportMarker: accepted type=${type.apiName} lat=$lat lng=$lng")
                    true
                } else {
                    Log.w(TAG, "reportMarker: server rejected code=$code")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "reportMarker: network error: ${e.message}")
                false
            }
        }

    /**
     * Fetch markers near [lat]/[lng] within [FETCH_RADIUS_M] metres.
     * Updates [cachedMarkers] for [checkApproaching].
     * Never throws.
     */
    suspend fun fetchNearbyMarkers(lat: Double, lng: Double): List<NearbyMarker> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/markers?lat=$lat&lng=$lng&radius=$FETCH_RADIUS_M"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    Log.w(TAG, "fetchNearbyMarkers: server error code=$code")
                    return@withContext emptyList()
                }

                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                conn.disconnect()

                val arr = JSONArray(body)
                val markers = (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val obj = arr.getJSONObject(i)
                        val typeName = obj.getString("type")
                        val markerType = MarkerType.entries.firstOrNull { it.apiName == typeName }
                            ?: return@mapNotNull null
                        val mLat = obj.getDouble("lat")
                        val mLng = obj.getDouble("lng")
                        NearbyMarker(
                            id             = obj.getString("id"),
                            type           = markerType,
                            lat            = mLat,
                            lng            = mLng,
                            distanceMeters = haversineM(lat, lng, mLat, mLng),
                        )
                    }.getOrNull()
                }

                cachedMarkers = markers
                Log.d(TAG, "fetchNearbyMarkers: ${markers.size} markers loaded")
                markers
            } catch (e: Exception) {
                Log.w(TAG, "fetchNearbyMarkers: ${e.message}")
                emptyList()
            }
        }

    /**
     * Returns the closest unannounced marker within [APPROACH_WARN_RADIUS_M] metres,
     * or null if none exists. Marks the returned marker as announced so the driver
     * hears each warning at most once per session.
     *
     * Call on every navigation position update.
     */
    fun checkApproaching(lat: Double, lng: Double): NearbyMarker? {
        val candidate = cachedMarkers
            .filter { it.id !in announcedIds }
            .map { it.copy(distanceMeters = haversineM(lat, lng, it.lat, it.lng)) }
            .filter { it.distanceMeters <= APPROACH_WARN_RADIUS_M }
            .minByOrNull { it.distanceMeters }

        if (candidate != null) {
            announcedIds.add(candidate.id)
            Log.d(TAG, "approaching: ${candidate.type.apiName} at ${candidate.distanceMeters.toInt()} m")
        }
        return candidate
    }

    /** Clear announced IDs — call at the start of each navigation session. */
    fun resetSession() {
        announcedIds.clear()
    }

    // ── Math ───────────────────────────────────────────────────────────────

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
