package lt.sturmanas.bajeristas.voice

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import lt.sturmanas.bajeristas.navigation.SavedPlacesMap

/**
 * Persists user-configured saved-place aliases (home and work addresses) using
 * [SharedPreferences], following the same pattern as [TtsSettings].
 *
 * Construct with [Context.getApplicationContext] to avoid leaking an Activity.
 * All reads and writes are synchronous and safe to call from any thread.
 *
 * The saved-place map is keyed by Lithuanian alias strings understood by
 * [lt.sturmanas.bajeristas.navigation.DestinationResolver]:
 *   - `"namai"` → home address
 *   - `"darbas"` → work address
 */
class SavedPlacesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Home address ──────────────────────────────────────────────────────

    /** Returns the stored home address, or `null` if none is configured. */
    fun getHomeAddress(): String? =
        prefs.getString(KEY_HOME, null).takeIf { !it.isNullOrBlank() }

    /**
     * Persist a home address. Trims whitespace before storing.
     * Passing a blank string has no effect — call [clearHomeAddress] to remove.
     */
    fun setHomeAddress(addr: String) {
        val trimmed = addr.trim()
        if (trimmed.isBlank()) return
        Log.d(TAG, "setHomeAddress: '$trimmed'")
        prefs.edit { putString(KEY_HOME, trimmed) }
    }

    /** Remove the stored home address. */
    fun clearHomeAddress() {
        Log.d(TAG, "clearHomeAddress")
        prefs.edit { remove(KEY_HOME) }
    }

    // ── Work address ──────────────────────────────────────────────────────

    /** Returns the stored work address, or `null` if none is configured. */
    fun getWorkAddress(): String? =
        prefs.getString(KEY_WORK, null).takeIf { !it.isNullOrBlank() }

    /**
     * Persist a work address. Trims whitespace before storing.
     * Passing a blank string has no effect — call [clearWorkAddress] to remove.
     */
    fun setWorkAddress(addr: String) {
        val trimmed = addr.trim()
        if (trimmed.isBlank()) return
        Log.d(TAG, "setWorkAddress: '$trimmed'")
        prefs.edit { putString(KEY_WORK, trimmed) }
    }

    /** Remove the stored work address. */
    fun clearWorkAddress() {
        Log.d(TAG, "clearWorkAddress")
        prefs.edit { remove(KEY_WORK) }
    }

    // ── Combined access ───────────────────────────────────────────────────

    /**
     * Return all configured saved places as a [SavedPlacesMap].
     * Only entries that have a non-blank address are included.
     */
    fun getAll(): SavedPlacesMap = buildMap {
        getHomeAddress()?.let { put("namai", it) }
        getWorkAddress()?.let { put("darbas", it) }
    }

    companion object {
        private const val TAG = "KentasVoice"
        private const val PREFS_NAME = "kentas_saved_places"
        private const val KEY_HOME   = "home_address"
        private const val KEY_WORK   = "work_address"
    }
}
