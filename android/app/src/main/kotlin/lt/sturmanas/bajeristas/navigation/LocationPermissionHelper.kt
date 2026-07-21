package lt.sturmanas.bajeristas.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Utility for checking location permission status.
 *
 * Does not request permissions — that is the Activity's responsibility via
 * [androidx.activity.result.ActivityResultLauncher].
 *
 * Phase 2 requests only [Manifest.permission.ACCESS_FINE_LOCATION].
 * Microphone permission ([Manifest.permission.RECORD_AUDIO]) is deferred to Phase 3.
 */
object LocationPermissionHelper {

    /** Returns true if ACCESS_FINE_LOCATION is granted. */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** The permission string to pass to the ActivityResultLauncher. */
    const val LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION
}
