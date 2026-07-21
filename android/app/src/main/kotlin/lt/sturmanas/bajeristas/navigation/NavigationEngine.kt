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
     * @param context   Context for Geocoder address resolution.
     * @param destination Human-readable address or "lat,lng" string.
     * @param onError   Called with a Lithuanian error if routing fails.
     */
    fun startNavigation(context: Context, destination: String, onError: (String) -> Unit)

    /** Stop navigation and reset to idle state. */
    fun stopNavigation()

    /**
     * Return the native [View] that renders the navigation map.
     * The composable must call [onViewCreated] immediately after receiving the view.
     *
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
    //
    // NavigationView requires the full Android lifecycle sequence:
    //   onCreate → onStart → onResume → onPause → onStop → onDestroy
    //
    // onCreate is called inside createNavigationView() (once, at view creation).
    // onStart through onDestroy are forwarded by NavigationScreen via a
    // LifecycleEventObserver. onDestroy is also called from onDispose so it fires
    // even when the composable leaves composition while the Activity is still alive.
    // Implementations must be idempotent for onDestroy.

    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()

    /**
     * Tear down the NavigationView and release SDK resources.
     * May be called more than once (from both the lifecycle observer and onDispose);
     * implementations must guard against double-execution.
     */
    fun onDestroy()
}
