package lt.sturmanas.bajeristas.navigation

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An intermediate stop on a multi-stop route.
 *
 * @param displayName   Human-readable label for TTS and UI ("Lidl", "degalinė").
 * @param resolvedQuery Address/query string for [GoogleNavigationEngine.startNavigation] —
 *                      already produced by [DestinationResolver].
 */
data class StopoverEntry(
    val displayName: String,
    val resolvedQuery: String,
)

/**
 * Manages an ordered list of intermediate stops for the current navigation session.
 *
 * ## Route model
 * ```
 * stopovers[0] → stopovers[1] → … → finalDestination
 * ```
 * [nextTarget] returns `stopovers[0]` when any remain, otherwise [finalDestination].
 * Call [advanceToNextStop] each time a stopover is reached; it drops the head and
 * returns the new [nextTarget].
 *
 * Stopovers are exposed as a [StateFlow] so the navigation UI can observe and
 * render a live route card. The final destination is stored separately and
 * accessed via [finalDestination].
 *
 * ## Usage invariants
 * - Call [setFinalDestination] whenever a new primary navigation starts; this also
 *   clears any leftover stopovers from the previous session.
 * - Call [clear] when navigation stops entirely (user taps "Baigti" or says "Sustabdyk").
 * - [addStopover] silently ignores exact-duplicate entries (same [StopoverEntry.resolvedQuery]).
 *
 * Logging tag: `KentasWaypoint`.
 */
class WaypointManager {

    companion object {
        internal const val TAG = "KentasWaypoint"
    }

    private val _stopovers = MutableStateFlow<List<StopoverEntry>>(emptyList())

    /**
     * Live ordered list of intermediate stops. Does **not** include the final destination.
     * Observed by [NavigationScreen] to render the route card.
     */
    val stopovers: StateFlow<List<StopoverEntry>> = _stopovers.asStateFlow()

    private var _finalDestination: StopoverEntry? = null

    /**
     * The overall final destination. Null when no navigation session is active.
     * Set via [setFinalDestination].
     */
    val finalDestination: StopoverEntry? get() = _finalDestination

    // ── Mutations ──────────────────────────────────────────────────────────

    /**
     * Record [entry] as the final destination and clear any stopovers from a prior
     * session. Must be called every time a new primary navigation starts.
     */
    fun setFinalDestination(entry: StopoverEntry) {
        Log.d(TAG, "setFinalDestination: '${entry.displayName}' (query='${entry.resolvedQuery}')")
        _finalDestination = entry
        _stopovers.value = emptyList()
    }

    /**
     * Append [entry] as the **last** intermediate stop before the final destination.
     * Silently rejects entries whose [StopoverEntry.resolvedQuery] exactly matches an
     * existing stopover.
     *
     * @return `true` if the stopover was added; `false` if it was a duplicate.
     */
    fun addStopover(entry: StopoverEntry): Boolean {
        val current = _stopovers.value
        if (current.any { it.resolvedQuery == entry.resolvedQuery }) {
            Log.w(TAG, "addStopover: duplicate '${entry.resolvedQuery}' — ignored")
            return false
        }
        _stopovers.value = current + entry
        Log.d(TAG, "addStopover: '${entry.displayName}' → total stopovers: ${_stopovers.value.size}")
        return true
    }

    /**
     * Remove and return the **last** stopover, or `null` if the list is empty.
     */
    fun removeLastStopover(): StopoverEntry? {
        val current = _stopovers.value
        if (current.isEmpty()) {
            Log.d(TAG, "removeLastStopover: list empty — nothing removed")
            return null
        }
        val removed = current.last()
        _stopovers.value = current.dropLast(1)
        Log.d(TAG, "removeLastStopover: removed '${removed.displayName}' — remaining: ${_stopovers.value.size}")
        return removed
    }

    /**
     * Remove and return the stopover at [index] (0-based), or `null` if out of bounds.
     */
    fun removeStopoverAt(index: Int): StopoverEntry? {
        val current = _stopovers.value
        if (index !in current.indices) {
            Log.w(TAG, "removeStopoverAt($index): index out of bounds (size=${current.size})")
            return null
        }
        val removed = current[index]
        _stopovers.value = current.toMutableList().also { it.removeAt(index) }
        Log.d(TAG, "removeStopoverAt($index): removed '${removed.displayName}'")
        return removed
    }

    /**
     * Remove all intermediate stops. The final destination is preserved.
     */
    fun clearStopovers() {
        val count = _stopovers.value.size
        _stopovers.value = emptyList()
        Log.d(TAG, "clearStopovers: cleared $count stopovers")
    }

    /**
     * Full reset — clears stopovers and nulls the final destination.
     * Call this when navigation is stopped entirely.
     */
    fun clear() {
        _stopovers.value = emptyList()
        _finalDestination = null
        Log.d(TAG, "clear: WaypointManager reset")
    }

    // ── Queries ────────────────────────────────────────────────────────────

    /** `true` if at least one intermediate stop is queued. */
    fun hasStopovers(): Boolean = _stopovers.value.isNotEmpty()

    /**
     * The next navigation target: `stopovers[0]` if any remain, otherwise
     * [finalDestination]. Returns `null` before [setFinalDestination] is called.
     */
    fun nextTarget(): StopoverEntry? = _stopovers.value.firstOrNull() ?: _finalDestination

    /**
     * Drop the first stopover (just arrived) and return the new [nextTarget].
     *
     * If no stopovers remain this is a no-op and [finalDestination] is returned,
     * indicating the overall journey is complete.
     */
    fun advanceToNextStop(): StopoverEntry? {
        val current = _stopovers.value
        if (current.isNotEmpty()) {
            val arrived = current.first()
            _stopovers.value = current.drop(1)
            Log.d(TAG, "advanceToNextStop: completed '${arrived.displayName}' — remaining: ${_stopovers.value.size}")
        }
        return nextTarget()
    }

    /**
     * All stops in route order: `[stopovers..., finalDestination]`.
     * Returns an empty list when no navigation session is active.
     */
    fun allStops(): List<StopoverEntry> {
        val fd = _finalDestination ?: return emptyList()
        return _stopovers.value + fd
    }
}
