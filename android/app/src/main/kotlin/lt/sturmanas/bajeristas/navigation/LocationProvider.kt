package lt.sturmanas.bajeristas.navigation

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides the device's current location.
 */
object LocationProvider {

    private const val TAG = "KentasLocation"

    private const val UPDATE_INTERVAL_MS = 30_000L
    private const val UPDATE_MIN_DISTANCE_M = 100f

    private val _locationFlow = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> = _locationFlow.asStateFlow()

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    fun startUpdates(context: Context) {
        val appCtx = context.applicationContext

        val fused = fusedClient
            ?: LocationServices.getFusedLocationProviderClient(appCtx).also { fusedClient = it }

        locationCallback?.let {
            fused.removeLocationUpdates(it)
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS,
        )
            .setMinUpdateDistanceMeters(UPDATE_MIN_DISTANCE_M)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                _locationFlow.value = loc
            }
        }
        locationCallback = callback

        @Suppress("MissingPermission")
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())

        @Suppress("MissingPermission")
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && _locationFlow.value == null) {
                _locationFlow.value = loc
            }
        }
    }

    fun stopUpdates(context: Context) {
        val fused = fusedClient
        locationCallback?.let { cb ->
            fused?.removeLocationUpdates(cb)
        }
        locationCallback = null
        _locationFlow.value = null
    }
}
