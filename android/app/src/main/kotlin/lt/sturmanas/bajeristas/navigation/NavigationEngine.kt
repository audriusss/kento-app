package lt.sturmanas.bajeristas.navigation

import android.app.Activity
import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the navigation data source.
 *
 * [GoogleNavigationEngine] — real Google Navigation SDK implementation.
 * [MockNavigationEngine]   — simulated state for development without an API key.
 *
 * All SDK-specific types must remain private inside the implementing class.
 * [NavigationController] depends only on this interface.
 *
 * ## Lifecycle split — IMPORTANT
 *
 * There are two distinct destroy paths with different scopes:
 *
 * 1. [onViewDestroy] — called by [NavigationScreen]'s `DisposableEffect.onDispose` whenever
 *    the composable leaves composition (user stops navigation, address search fails and returns
 *    to StartScreen, etc.). Tears down the [NavigationView] ONLY. The [Navigator] (the SDK
 *    routing engine) must survive so that [startNavigation] works again on the next attempt
 *    without re-initialising the SDK.
 *
 * 2. [onDestroy] — called by [NavigationController.onDestroy] from [MainActivity.onDestroy]
 *    only. Tears down both the [NavigationView] and the [Navigator]. This is the terminal
 *    cleanup for the whole Activity lifecycle.
 *
 * Failure to observe this split is the cause of the "Navigacija neparuošta" error that
 * appears after any failed address search: [onDestroy] from [DisposableEffect] nulls the
 * Navigator, making all subsequent [startNavigation] calls fail immediately.
 */
interface NavigationEngine {

    /** Live navigation state. Updates arrive from SDK callbacks or the mock timer. */
    val state: StateFlow<NavigationState>

    /**
     * Initialise the engine. Must be called before [startNavigation].
     *
     * For [GoogleNavigationEngine]: calls [NavigationApi.getNavigator] and
     * requires a valid API key in local.properties.
     * For [MockNavigationEngine]: always succeeds synchronously.
     *
     * @param activity  The host activity (required by Navigation SDK).
     * @param onReady   Called when the engine is ready to accept navigation requests.
     * @param onError   Called with a Lithuanian error message if initialisation fails.
     */
    fun initialize(activity: Activity, onReady: () -> Unit, onError: (String) -> Unit)

    /**
     * Resolve [destination] (address string or "lat,lng" pair) and start navigation.
     * Must only be called after [onReady] fires from [initialize].
     *
     * @param context   Context for Geocoder / HTTP address resolution.
     * @param destination Human-readable address or "lat,lng" string.
     * @param onError   Called with a Lithuanian error if routing fails.
     */
    fun startNavigation(context: Context, destination: String, onError: (String) -> Unit)

    /** Stop navigation and reset to idle state. Does not destroy the engine. */
    fun stopNavigation()

    /**
     * Return the native [View] that renders the navigation map.
     * For [GoogleNavigationEngine]: returns a [NavigationView].
     * For [MockNavigationEngine]: returns a simple placeholder [View].
     */
    fun createNavigationView(context: Context): View

    // ── Navigation voice ──────────────────────────────────────────────────

    /** Enable the standard Google navigation voice guidance. */
    fun enableStandardVoice()

    /** Disable the standard Google navigation voice guidance (Kentas speaks instead). */
    fun disableStandardVoice()

    // ── Lifecycle — forward from Activity/Composable ────────────────────

    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()

    /**
     * Tears down the [NavigationView] ONLY — called from [NavigationScreen]'s
     * `DisposableEffect.onDispose`. The [Navigator] remains alive so that
     * subsequent [startNavigation] calls succeed without re-initialisation.
     *
     * Implementations must be idempotent (safe to call multiple times).
     */
    fun onViewDestroy()

    /**
     * Full teardown: tears down both the [NavigationView] and the [Navigator].
     * Called ONLY from [NavigationController.onDestroy] which is called from
     * [MainActivity.onDestroy]. Never call this from a composable.
     *
     * Implementations must be idempotent.
     */
    fun onDestroy()
}
