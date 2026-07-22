package lt.sturmanas.bajeristas.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists TextToSpeech user preferences using [SharedPreferences].
 *
 * Construct with [Context.getApplicationContext] to avoid leaking an Activity.
 * All property reads and writes are synchronous and safe to call from any thread.
 */
class TtsSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * TTS speech rate. 1.0 = normal speed. Clamped to 0.1 – 4.0.
     * Slightly above 1.0 by default — feels more natural for navigation announcements.
     */
    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        set(value) = prefs.edit { putFloat(KEY_SPEECH_RATE, value.coerceIn(0.1f, 4.0f)) }

    /** TTS pitch. 1.0 = normal. Clamped to 0.5 – 2.0. */
    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, DEFAULT_PITCH)
        set(value) = prefs.edit { putFloat(KEY_PITCH, value.coerceIn(0.5f, 2.0f)) }

    /** Whether TTS voice output is enabled at all. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    companion object {
        private const val PREFS_NAME = "kentas_tts_settings"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_PITCH = "pitch"
        private const val KEY_ENABLED = "enabled"

        const val DEFAULT_SPEECH_RATE = 1.05f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_ENABLED = true
    }
}
