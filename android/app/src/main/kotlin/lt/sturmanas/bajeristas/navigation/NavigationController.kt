package lt.sturmanas.bajeristas.navigation

import android.app.Activity
import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

/**
 * Single point of access to navigation for the rest of the application.
 *
 * Delegates all work to [NavigationEngine]. Compose UI, [SafetyController],
 * and personality code must access navigation only through this class — never
 * directly from [GoogleNavigationEngine] or [MockNavigationEngine].
 *
 * Engine selection happens in [MainActivity] based on whether a Google Maps
 * API key is present in [BuildConfig.GOOGLE_MAPS_API_KEY].
 */
class NavigationController(val engine: NavigationEngine) {

    /** Live navigation state. Observe this in Compose via [collectAsStateWithLifecycle]. */
    val state: StateFlow<NavigationState> = engine.state

    // ── Init ──────────────────────────────────────────────────────────────

    /**
     * Initialise the engine. Must be called once before [startNavigation].
     * Requires location permission to be granted before calling.
     */
    fun initialize(activity: Activity, onReady: () -> Unit, onError: (String) -> Unit) {
        engine.initialize(activity, onReady, onError)
    }

    // ── Navigation control ────────────────────────────────────────────────

    /**
     * Resolve [destination] and begin navigation.
     * Must only be called after [onReady] fires from [initialize].
     */
    fun startNavigation(context: Context, destination: String, onError: (String) -> Unit) {
        engine.startNavigation(context, destination, onError)
    }

    /** Stop navigation and return to idle state. */
    fun stopNavigation() {
        engine.stopNavigation()
    }

    // ── Map view ──────────────────────────────────────────────────────────

    /**
     * Return the native Android [View] that renders the navigation map.
     * Embed via [androidx.compose.ui.viewinterop.AndroidView] in Compose.
     * Forward lifecycle events to the engine via [onResume], [onPause], etc.
     */
    fun createNavigationView(context: Context): View = engine.createNavigationView(context)

    // ── Audio ─────────────────────────────────────────────────────────────

    /** Switch navigation audio to the standard Google voice guidance (emergency fallback). */
    fun enableStandardVoice() {
        engine.enableStandardVoice()
    }

    /** Silence navigation voice guidance (Kentas handles speaking). */
    fun disableStandardVoice() {
        engine.disableStandardVoice()
    }

    // ── Lifecycle — forward from Activity/composable ──────────────────────

    fun onResume() = engine.onResume()
    fun onPause() = engine.onPause()
    fun onStop() = engine.onStop()
    fun onDestroy() = engine.onDestroy()
}
