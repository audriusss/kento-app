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
 *   Re-creates the recognizer on every call for reliability on Xiaomi/MIUI devices.
 *   Returns the [generation] ID of the new session so callers can correlate callbacks.
 * - [cancel] — stop listening and release the current recognizer.
 * - [release] — full teardown; call from ViewModel.onCleared().
 *
 * ## Generation IDs — stale callback elimination
 *
 * Every [startListening] call increments [generation] and creates a new
 * [RecognitionListener] that captures its own generation value. Each callback
 * checks whether its generation is still current before doing anything. This
 * makes callbacks from old, destroyed recognizer instances completely harmless —
 * the main source of race conditions on Xiaomi/MIUI and after rapid toggles.
 *
 * ## Single active session guarantee
 *
 * [isSessionActive] tracks whether a recognizer is currently between
 * startListening→onReadyForSpeech and onResults/onError. Callers must check this
 * before scheduling another start to avoid overlapping sessions.
 *
 * ## TTS coordination
 *
 * The caller ([MainViewModel]) must stop TTS speech BEFORE calling [startListening]
 * to prevent the recognizer from hearing Kentas's own voice.
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
 */
class SpeechRecognitionManager(private val appContext: Context) {

    companion object {
        private const val TAG            = "KentasVoice"
        private const val LIFECYCLE_TAG  = "KentasSpeechLifecycle"
        private const val DUPLICATE_WINDOW_MS = 500L
    }

    // ── Callbacks (set by MainViewModel) ──────────────────────────────────

    /**
     * Fired immediately when [startListening] is called — before the recognizer is ready.
     * Use to transition UI to [VoiceSessionState.Starting].
     */
    var onStartRequested: (() -> Unit)? = null

    /**
     * Fired when [android.speech.RecognitionListener.onReadyForSpeech] is received —
     * the recognizer is genuinely active. UI may only show "Kentas klauso…" from here.
     */
    var onReadyForSpeech: (() -> Unit)? = null

    /** Fired when the user begins speaking (onBeginningOfSpeech). */
    var onBeginningOfSpeech: (() -> Unit)? = null

    var onPartialResult: ((String) -> Unit)? = null
    var onResult: ((String) -> Unit)? = null

    /** Generic error callback — kept for backward compatibility with single-press mode. */
    var onError: ((String) -> Unit)? = null

    var onListeningStopped: (() -> Unit)? = null

    /**
     * Fired for transient recoverable errors.
     * Receives the raw error code so the session loop can apply [RecoveryPolicy] delays.
     * When set, [onError] is NOT additionally called for these codes.
     */
    var onRecoverableError: ((Int) -> Unit)? = null

    /**
     * Fired for hard, non-retryable failures (e.g. [RecoveryPolicy.E_INSUFFICIENT_PERMISSIONS]).
     * Receives a user-facing Lithuanian message.
     * When set, [onError] is NOT additionally called for these codes.
     */
    var onFatalError: ((String) -> Unit)? = null

    // ── Internal state ────────────────────────────────────────────────────

    private var recognizer: SpeechRecognizer? = null
    private var isAvailable = false
    private var lastResultTimestampMs = 0L

    /**
     * Monotonically increasing counter. Incremented on every [startListening] call.
     * Each [RecognitionListener] instance captures its own generation and ignores
     * callbacks that arrive after the generation has advanced (i.e. after a new session
     * has started or cancel was called).
     */
    @Volatile var generation: Long = 0L
        private set

    /**
     * True while a recognition session is in progress — between [startListening]
     * and [onResults]/[onError]/[cancel].
     *
     * Callers should check this before scheduling a new start to prevent overlap.
     */
    @Volatile var isSessionActive: Boolean = false
        private set

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Check device support. Must be called before [startListening].
     * Safe to call on any thread.
     */
    fun initialize() {
        isAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        Log.d(LIFECYCLE_TAG, "initialize: SpeechRecognizer available=$isAvailable")
    }

    /**
     * Start Lithuanian speech recognition.
     *
     * Must be called on the Main thread.
     * Destroys any currently active recognizer before creating a new one
     * (re-create-each-call pattern — reliable on Xiaomi/MIUI).
     *
     * @return the [generation] ID of the new session, or -1 if startup failed.
     */
    fun startListening(): Long {
        Log.d(LIFECYCLE_TAG,
            "startListening: gen=${generation+1} isAvailable=$isAvailable isSessionActive=$isSessionActive")

        if (!isAvailable) {
            val msg = "Balso atpažinimas neprieinamas šiame įrenginyje."
            Log.w(LIFECYCLE_TAG, "startListening: recognition not available")
            if (onFatalError != null) onFatalError?.invoke(msg) else onError?.invoke(msg)
            return -1L
        }

        // Advance generation BEFORE destroying — any in-flight callbacks from the old
        // listener will see their generation != currentGeneration and abort.
        generation++
        val myGeneration = generation

        destroyCurrentRecognizer()

        // Notify caller that a session is being requested (before onReadyForSpeech).
        isSessionActive = true
        onStartRequested?.invoke()
        Log.d(LIFECYCLE_TAG,
            "startListening: gen=$myGeneration — START_REQUESTED")

        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
            if (sr == null) {
                val msg = "Nepavyko sukurti balso atpažintuvio."
                Log.e(LIFECYCLE_TAG, "startListening: SpeechRecognizer.create returned null gen=$myGeneration")
                isSessionActive = false
                if (onFatalError != null) onFatalError?.invoke(msg) else onError?.invoke(msg)
                return -1L
            }
            sr.setRecognitionListener(makeListener(myGeneration))
            recognizer = sr
            sr.startListening(buildRecognitionIntent())
            Log.d(LIFECYCLE_TAG,
                "startListening: gen=$myGeneration — LISTENING_REQUESTED")
        } catch (e: Exception) {
            Log.e(LIFECYCLE_TAG, "startListening: exception gen=$myGeneration", e)
            isSessionActive = false
            val msg = "Nepavyko paleisti balso atpažinimo: ${e.message?.take(40)}"
            if (onFatalError != null) onFatalError?.invoke(msg) else onError?.invoke(msg)
            return -1L
        }

        return myGeneration
    }

    /**
     * Stop listening and destroy the current recognizer.
     * Increments [generation] so any pending callbacks are discarded.
     */
    fun cancel() {
        Log.d(LIFECYCLE_TAG, "cancel: gen=$generation")
        generation++          // invalidate any in-flight callbacks
        isSessionActive = false
        destroyCurrentRecognizer()
        onListeningStopped?.invoke()
    }

    /**
     * Full teardown. Call from ViewModel.onCleared().
     */
    fun release() {
        Log.d(LIFECYCLE_TAG, "release: full teardown gen=$generation")
        generation++
        isSessionActive = false
        destroyCurrentRecognizer()
        onStartRequested  = null
        onReadyForSpeech  = null
        onBeginningOfSpeech = null
        onPartialResult   = null
        onResult          = null
        onError           = null
        onListeningStopped = null
        onRecoverableError = null
        onFatalError      = null
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

            // ── Silence thresholds ────────────────────────────────────────────
            // Lithuanian speech often has a natural pause between a street name
            // and the house number ("Taikos … 61") and between casual sentence
            // fragments. Without these extras the platform default (~500 ms) cuts
            // the utterance before the number is spoken.
            //
            // Values are intentionally generous so navigation commands still feel
            // responsive while addresses and longer sentences have room to finish.
            //
            // Not all devices honour every extra — missing support is silent.
            // RecognitionListener.onResults still arrives when the device decides
            // the utterance is complete; these extras only extend the upper bound.
            //
            // String-key form used because some MIUI and Samsung ROMs require it
            // instead of the EXTRA_ constants.
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 1500)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 1400)
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2000)
        }

    /**
     * Create a [RecognitionListener] that captures [sessionGeneration].
     * Every callback checks `sessionGeneration == generation` before proceeding.
     * If the session has advanced (new start or cancel), the callback is a no-op.
     */
    private fun makeListener(sessionGeneration: Long) = object : RecognitionListener {

        /** Returns true and logs if this callback is stale. */
        private fun isStale(event: String): Boolean {
            if (sessionGeneration != generation) {
                Log.d(LIFECYCLE_TAG,
                    "$event: STALE gen=$sessionGeneration currentGen=$generation — ignored")
                return true
            }
            return false
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (isStale("onReadyForSpeech")) return
            Log.d(LIFECYCLE_TAG, "onReadyForSpeech gen=$sessionGeneration — LISTENING_READY")
            this@SpeechRecognitionManager.onReadyForSpeech?.invoke()
        }

        override fun onBeginningOfSpeech() {
            if (isStale("onBeginningOfSpeech")) return
            Log.d(LIFECYCLE_TAG, "onBeginningOfSpeech gen=$sessionGeneration — USER_SPEAKING")
            this@SpeechRecognitionManager.onBeginningOfSpeech?.invoke()
        }

        override fun onRmsChanged(rmsdB: Float) {
            /* Audio level updates — not logged (too noisy) */
        }

        override fun onBufferReceived(buffer: ByteArray?) { /* unused */ }

        override fun onEndOfSpeech() {
            if (isStale("onEndOfSpeech")) return
            Log.d(LIFECYCLE_TAG, "onEndOfSpeech gen=$sessionGeneration")
            onListeningStopped?.invoke()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isStale("onPartialResults")) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: return
            Log.d(LIFECYCLE_TAG, "onPartialResults gen=$sessionGeneration: '$partial'")
            onPartialResult?.invoke(partial)
        }

        override fun onResults(results: Bundle?) {
            if (isStale("onResults")) return

            // Duplicate callback guard — some ROMs fire onResults twice.
            val now = System.currentTimeMillis()
            if (now - lastResultTimestampMs < DUPLICATE_WINDOW_MS) {
                Log.d(LIFECYCLE_TAG,
                    "onResults: DUPLICATE within ${DUPLICATE_WINDOW_MS}ms gen=$sessionGeneration — ignored")
                return
            }
            lastResultTimestampMs = now
            isSessionActive = false

            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }

            Log.d(LIFECYCLE_TAG, "onResults gen=$sessionGeneration: text='$text'")

            if (text.isNullOrBlank()) {
                Log.w(LIFECYCLE_TAG,
                    "onResults: empty result gen=$sessionGeneration → routing to NO_MATCH")
                if (onRecoverableError != null) {
                    onRecoverableError?.invoke(RecoveryPolicy.E_NO_MATCH)
                } else {
                    onError?.invoke("Nieko aiškiai neišgirdau. Pabandykite dar kartą.")
                }
            } else {
                onResult?.invoke(text)
            }
        }

        override fun onError(errorCode: Int) {
            if (isStale("onError")) return
            isSessionActive = false

            val name  = RecoveryPolicy.errorName(errorCode)
            val fatal = RecoveryPolicy.isFatal(errorCode)
            val recreate = RecoveryPolicy.shouldRecreateRecognizer(errorCode)

            Log.w(LIFECYCLE_TAG,
                "onError gen=$sessionGeneration code=$errorCode ($name) " +
                "fatal=$fatal recreate=$recreate")

            // Destroy the recognizer when the error indicates a broken session state.
            // A fresh instance will be created by the next startListening() call.
            if (recreate) {
                destroyCurrentRecognizer()
            }

            when {
                fatal -> {
                    val msg = RecoveryPolicy.fatalTtsMessage(errorCode)
                    Log.e(LIFECYCLE_TAG, "onError: FATAL code=$errorCode ($name)")
                    if (onFatalError != null) onFatalError?.invoke(msg) else onError?.invoke(msg)
                }
                onRecoverableError != null -> {
                    onRecoverableError?.invoke(errorCode)
                }
                else -> {
                    // Fallback: use generic error callback with Lithuanian message.
                    val msg = RecoveryPolicy.statusText(errorCode)
                    onError?.invoke(msg)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* SDK extension — unused */ }
    }
}
