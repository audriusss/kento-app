package lt.sturmanas.bajeristas.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wraps [SpeechRecognizer] for Lithuanian speech-to-text.
 *
 * ## Lifecycle contract
 *
 * Owned by [MainViewModel] — lives for the lifetime of the Activity task stack,
 * survives screen rotation.
 *
 * - [initialize] — call once from ViewModel init. Checks recognizer availability.
 * - [startListening] — must be called on the Main thread (SpeechRecognizer requirement).
 *   Re-creates the recognizer on every call for reliability on Xiaomi/MIUI devices
 *   where a reused recognizer often enters bad states after the first session.
 * - [cancel] — stop listening and release the current recognizer.
 * - [release] — full teardown; call from ViewModel.onCleared().
 *
 * ## TTS coordination
 *
 * The caller ([MainViewModel.onMicPressed]) must stop TTS speech BEFORE calling
 * [startListening] to prevent the recognizer from hearing Kentas's own voice.
 *
 * ## Duplicate callback guard
 *
 * [SpeechRecognizer] sometimes fires [RecognitionListener.onResults] twice on
 * certain ROMs. The manager tracks [lastResultTimestampMs] and silently discards
 * a second result that arrives within [DUPLICATE_WINDOW_MS].
 *
 * ## Thread safety
 *
 * All SpeechRecognizer calls must happen on the thread that created the recognizer
 * (the Main thread). The callbacks fire on the same thread.
 *
 * ## Typed error callbacks
 *
 * In addition to the generic [onError] kept for backward compatibility, the manager
 * exposes two typed callbacks used by the continuous session loop:
 * - [onRecoverableError] — fired for NO_MATCH, SPEECH_TIMEOUT, RECOGNIZER_BUSY.
 *   The session loop retries up to [MainViewModel.MAX_SESSION_RETRIES] times.
 * - [onFatalError] — fired for INSUFFICIENT_PERMISSIONS and other hard failures.
 *   The session loop stops immediately.
 *
 * ## Usage example
 * ```
 * manager.onResult = { text -> viewModel.onRecognitionResult(text) }
 * manager.onError = { msg -> viewModel.onRecognitionError(msg) }
 * manager.startListening()
 * ```
 */
class SpeechRecognitionManager(private val appContext: Context) {

    companion object {
        const val TAG = "KentasVoice"
        private const val DUPLICATE_WINDOW_MS = 500L
    }

    // ── Callbacks (set by MainViewModel) ──────────────────────────────────

    var onListeningStarted: (() -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onResult: ((String) -> Unit)? = null
    /** Generic error callback — kept for backward compatibility. */
    var onError: ((String) -> Unit)? = null
    var onListeningStopped: (() -> Unit)? = null

    /**
     * Fired for transient, recoverable errors: [SpeechRecognizer.ERROR_NO_MATCH],
     * [SpeechRecognizer.ERROR_SPEECH_TIMEOUT], [SpeechRecognizer.ERROR_RECOGNIZER_BUSY].
     * Receives the raw error code so the session loop can apply error-specific delays.
     * When set, [onError] is NOT additionally called for these error codes.
     */
    var onRecoverableError: ((Int) -> Unit)? = null

    /**
     * Fired for hard, non-retryable failures: [SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS]
     * and unknown error codes.
     * Receives a user-facing Lithuanian message.
     * When set, [onError] is NOT additionally called for these error codes.
     */
    var onFatalError: ((String) -> Unit)? = null

    // ── Internal state ────────────────────────────────────────────────────

    private var recognizer: SpeechRecognizer? = null
    private var isAvailable = false
    private var lastResultTimestampMs = 0L

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Check device support. Must be called before [startListening].
     * Safe to call on any thread.
     */
    fun initialize() {
        isAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        Log.d(TAG, "initialize: SpeechRecognizer available=$isAvailable")
    }

    /**
     * Start Lithuanian speech recognition.
     *
     * Must be called on the Main thread.
     * Destroys any currently active recognizer before creating a new one
     * (re-create-each-call pattern — more reliable on Xiaomi/MIUI).
     *
     * Calls [onError] with a Lithuanian message if:
     * - the device has no speech recognition capability, or
     * - the recognizer cannot be created.
     */
    fun startListening() {
        Log.d(TAG, "startListening: isAvailable=$isAvailable")

        if (!isAvailable) {
            Log.w(TAG, "startListening: recognition not available on this device")
            val msg = "Balso atpažinimas neprieinamas šiame įrenginyje."
            if (onFatalError != null) onFatalError?.invoke(msg) else onError?.invoke(msg)
            return
        }

        // Destroy any existing recognizer before creating a new one.
        // Re-creation on each call prevents stale state on Xiaomi/MIUI.
        destroyCurrentRecognizer()

        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
            if (sr == null) {
                Log.e(TAG, "startListening: SpeechRecognizer.create returned null")
                val msg = "Nepavyko sukurti balso atpažintuvio."
                if (onFatalError != null) onFatalError?.invoke(msg) else onError?.invoke(msg)
                return
            }
            sr.setRecognitionListener(listener)
            recognizer = sr

            val intent = buildRecognitionIntent()
            sr.startListening(intent)
            Log.d(TAG, "startListening: listening started")
        } catch (e: Exception) {
            Log.e(TAG, "startListening: exception", e)
            val msg = "Nepavyko paleisti balso atpažinimo: ${e.message?.take(40)}"
            if (onFatalError != null) onFatalError?.invoke(msg) else onError?.invoke(msg)
        }
    }

    /**
     * Stop listening and destroy the current recognizer.
     * Calls [onListeningStopped].
     */
    fun cancel() {
        Log.d(TAG, "cancel: stopping recognition")
        destroyCurrentRecognizer()
        onListeningStopped?.invoke()
    }

    /**
     * Full teardown. Call from ViewModel.onCleared().
     */
    fun release() {
        Log.d(TAG, "release: full teardown")
        destroyCurrentRecognizer()
        onListeningStarted = null
        onPartialResult = null
        onResult = null
        onError = null
        onListeningStopped = null
        onRecoverableError = null
        onFatalError = null
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun destroyCurrentRecognizer() {
        recognizer?.run {
            try {
                cancel()
                destroy()
            } catch (_: Exception) { /* ignore — may already be destroyed */ }
        }
        recognizer = null
    }

    private fun buildRecognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "lt-LT")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "lt-LT")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "lt-LT")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

    // ── RecognitionListener ───────────────────────────────────────────────

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            onListeningStarted?.invoke()
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            /* Audio level updates — not logged (too noisy) */
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            /* Raw audio buffer — unused */
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            onListeningStopped?.invoke()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: return
            Log.d(TAG, "onPartialResults: '$partial'")
            onPartialResult?.invoke(partial)
        }

        override fun onResults(results: Bundle?) {
            // Duplicate callback guard — some ROMs fire onResults twice.
            val now = System.currentTimeMillis()
            if (now - lastResultTimestampMs < DUPLICATE_WINDOW_MS) {
                Log.d(TAG, "onResults: duplicate callback within ${DUPLICATE_WINDOW_MS}ms — ignored")
                return
            }
            lastResultTimestampMs = now

            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }

            Log.d(TAG, "onResults: text='$text'")

            if (text.isNullOrBlank()) {
                val msg = "Nieko aiškiai neišgirdau. Pabandykite dar kartą."
                // Treat an empty result as recoverable (user just didn't speak).
                if (onRecoverableError != null) {
                    onRecoverableError?.invoke(SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    onError?.invoke(msg)
                }
            } else {
                onResult?.invoke(text)
            }
        }

        override fun onError(errorCode: Int) {
            when (errorCode) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    val msg = if (errorCode == SpeechRecognizer.ERROR_NO_MATCH)
                        "Nieko aiškiai neišgirdau. Pabandykite dar kartą."
                    else
                        "Nieko neišgirdau."
                    Log.w(TAG, "onError: recoverable code=$errorCode")
                    if (onRecoverableError != null) {
                        onRecoverableError?.invoke(errorCode)
                    } else {
                        onError?.invoke(msg)
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Destroy and allow a fresh startListening() call — do not propagate as error.
                    Log.w(TAG, "onError: RECOGNIZER_BUSY ($errorCode) — cancelling for retry")
                    destroyCurrentRecognizer()
                    if (onRecoverableError != null) {
                        onRecoverableError?.invoke(errorCode)
                    } else {
                        onListeningStopped?.invoke()
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    val msg = "Nėra mikrofono leidimo."
                    Log.e(TAG, "onError: FATAL code=$errorCode msg='$msg'")
                    if (onFatalError != null) onFatalError?.invoke(msg) else onError?.invoke(msg)
                }
                else -> {
                    val msg = when (errorCode) {
                        SpeechRecognizer.ERROR_NETWORK         -> "Balso atpažinimui nepavyko prisijungti prie interneto."
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tinklo užklausa užtruko per ilgai."
                        SpeechRecognizer.ERROR_AUDIO           -> "Nepavyko naudoti mikrofono."
                        SpeechRecognizer.ERROR_SERVER          -> "Balso atpažinimo serverio klaida."
                        SpeechRecognizer.ERROR_CLIENT          -> "Kliento klaida."
                        else                                   -> "Balso atpažinimo klaida (kodas: $errorCode)"
                    }
                    Log.e(TAG, "onError: code=$errorCode msg='$msg'")
                    // Network/audio errors may be transient — route to recoverable if handler set.
                    if (onRecoverableError != null && errorCode in listOf(
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            SpeechRecognizer.ERROR_AUDIO,
                        )
                    ) {
                        onRecoverableError?.invoke(errorCode)
                    } else if (onFatalError != null) {
                        onFatalError?.invoke(msg)
                    } else {
                        onError?.invoke(msg)
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            /* SDK extension point — unused */
        }
    }
}
