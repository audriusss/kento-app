package lt.sturmanas.bajeristas.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Manages Android native [TextToSpeech] for Kentas.
 *
 * ## Lifecycle
 * - Construct once and store in [MainViewModel] so it survives screen rotation.
 * - [initialize] is called from the ViewModel constructor automatically.
 * - Call [speak] whenever Kentas should say something; any ongoing speech is stopped first.
 * - Call [release] from [MainViewModel.onCleared] — **never** from an Activity lifecycle method,
 *   because the Activity is recreated on rotation but the ViewModel persists.
 *
 * ## Locale
 * - Prefers `lt_LT` (Lithuanian).
 * - Falls back to `en_US` if Lithuanian data is not installed on the device.
 *
 * ## Thread safety
 * - [speak] and [stop] are safe to call from the main thread or from a
 *   `rememberCoroutineScope`-launched coroutine (which runs on the main dispatcher).
 * - [isReady] is marked `@Volatile` so the init callback's write is visible
 *   to calls coming from any thread.
 */
class TtsManager(context: Context) {

    /** Exposes configurable options (rate, pitch, enabled). Persisted via SharedPreferences. */
    val settings: TtsSettings = TtsSettings(context.applicationContext)

    private val appContext: Context = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false

    // ── Public API ────────────────────────────────────────────────────────

    /** True while the engine is synthesising speech. */
    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    /**
     * Initialise the TTS engine asynchronously. Safe to call multiple times —
     * subsequent calls are no-ops. Calls to [speak] before init completes are dropped.
     */
    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyLocale()
                applySettings()
                isReady = true
                Log.d(TAG, "TTS ready — locale: ${tts?.language}")
            } else {
                Log.e(TAG, "TTS init failed (status=$status)")
            }
        }
    }

    /**
     * Speak [text], immediately interrupting any speech currently in progress.
     *
     * No-op when:
     * - [TtsSettings.isEnabled] is false
     * - TTS engine is not yet initialised
     * - [text] is blank after trimming
     *
     * Uses [TextToSpeech.QUEUE_FLUSH] so previous utterances are discarded —
     * responses never queue up behind each other.
     */
    fun speak(text: String) {
        if (!isReady || !settings.isEnabled) return
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Stop any ongoing or queued speech immediately. */
    fun stop() {
        if (isReady) tts?.stop()
    }

    /**
     * Push current [TtsSettings.speechRate] and [TtsSettings.pitch] into the engine.
     * Call after the user updates settings in the UI so they take effect on the next [speak].
     */
    fun applySettings() {
        tts?.setSpeechRate(settings.speechRate)
        tts?.setPitch(settings.pitch)
    }

    /**
     * Shut down the engine and release all native resources.
     * Called exactly once from [MainViewModel.onCleared].
     */
    fun release() {
        isReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        Log.d(TAG, "TTS released")
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun applyLocale() {
        val engine = tts ?: return
        val lithuanian = Locale("lt", "LT")
        val result = engine.setLanguage(lithuanian)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Lithuanian TTS not available — falling back to en_US")
            engine.setLanguage(Locale.US)
        }
    }

    companion object {
        private const val TAG = "TtsManager"
        // A stable, non-null utterance ID is required to avoid the deprecated 3-arg speak() overload.
        private const val UTTERANCE_ID = "kentas"
    }
}
