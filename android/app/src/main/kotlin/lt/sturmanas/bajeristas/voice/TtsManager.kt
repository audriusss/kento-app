package lt.sturmanas.bajeristas.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
 *
 * ## Completion callback
 * - [onDone] is invoked when each utterance finishes (or errors out).
 *   The continuous voice session loop uses this to restart listening after
 *   Kentas finishes speaking. Set it once from [MainViewModel.init].
 */
class TtsManager(context: Context) {

    /** Exposes configurable options (rate, pitch, enabled). Persisted via SharedPreferences. */
    val settings: TtsSettings = TtsSettings(context.applicationContext)

    private val appContext: Context = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false

    /**
     * True while the engine is synthesising speech.
     * Backed by [UtteranceProgressListener] so it accurately reflects the
     * engine's speaking state rather than relying on the deprecated polling API.
     */
    @Volatile var isSpeaking: Boolean = false
        private set

    /**
     * Called when the current utterance finishes (success, error, or interrupted).
     * Set once from [MainViewModel.setupRecognitionCallbacks] to restart the
     * continuous listening loop after Kentas completes a TTS response.
     */
    var onDone: (() -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────

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
                registerProgressListener()
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
        if (isReady) {
            tts?.stop()
            isSpeaking = false
        }
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
        isSpeaking = false
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

    /**
     * Register an [UtteranceProgressListener] that keeps [isSpeaking] accurate
     * and fires [onDone] when each utterance completes or errors out.
     *
     * Must be called after the TTS engine reports [TextToSpeech.SUCCESS] in its
     * init callback, because [TextToSpeech.setOnUtteranceProgressListener] is
     * undefined before the engine is ready.
     */
    private fun registerProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                Log.d(TAG, "utterance started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                Log.d(TAG, "utterance done: $utteranceId")
                onDone?.invoke()
            }

            @Deprecated("Required override — delegate to onError(String)")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                Log.w(TAG, "utterance error (deprecated callback): $utteranceId")
                onDone?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                isSpeaking = false
                Log.w(TAG, "utterance error: utteranceId=$utteranceId errorCode=$errorCode")
                onDone?.invoke()
            }
        })
    }

    companion object {
        private const val TAG = "TtsManager"
        // A stable, non-null utterance ID is required to avoid the deprecated 3-arg speak() overload.
        private const val UTTERANCE_ID = "kentas"
    }
}
