package lt.sturmanas.bajeristas.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Centralized TTS owner that enforces navigation-first audio priority.
 *
 * ## Priority rule
 * Navigation instructions always interrupt conversation TTS.
 * [speakNavigation] calls [TtsManager.speak] with QUEUE_FLUSH, discarding any
 * in-progress conversation reply. The pending [onConversationDone] callback is
 * cleared so the conversation controller does not re-listen after being pre-empted.
 *
 * ## TTS watchdog
 * Armed in [TtsManager.onStart]; cancelled in [TtsManager.onDone].
 * If [onDone] has not fired within [WATCHDOG_MS], [TtsManager.forceComplete] is
 * called so the conversation loop is never permanently blocked by a frozen engine.
 *
 * Owns [TtsManager] — call [release] from [MainViewModel.onCleared].
 */
class KentasSpeechCoordinator(
    private val ttsManager: TtsManager,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "KentasTtsCoord"
        /** Maximum time from onStart to onDone before the watchdog fires. */
        const val WATCHDOG_MS = 12_000L
    }

    /** True while TTS is currently synthesising any speech. */
    val isSpeaking: Boolean get() = ttsManager.isSpeaking

    /** Expose settings (rate, pitch, enabled) for the SettingsScreen. */
    val settings get() = ttsManager.settings

    /** Callback invoked after a conversation utterance finishes. Cleared by [speakNavigation]. */
    private var onConversationDone: (() -> Unit)? = null

    /**
     * Callback invoked after a navigation utterance finishes.
     * Set by [speakNavigation]; takes priority over [onConversationDone].
     * Cleared to null before invoking, so a second nav utterance does not
     * re-trigger the previous callback.
     */
    private var onNavigationDone: (() -> Unit)? = null

    private var watchdogJob: Job? = null

    init {
        ttsManager.onStart = {
            Log.d(TAG, "TTS onStart — arming watchdog")
            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                delay(WATCHDOG_MS)
                if (ttsManager.isSpeaking) {
                    Log.w(TAG, "watchdog fired — forcing completion")
                    ttsManager.forceComplete()
                }
            }
        }

        ttsManager.onDone = {
            Log.d(TAG, "TTS onDone — cancelling watchdog")
            watchdogJob?.cancel()
            watchdogJob = null
            // Navigation callback takes priority. Exactly one callback fires per utterance.
            val navCb  = onNavigationDone
            val convCb = onConversationDone
            onNavigationDone  = null
            onConversationDone = null
            navCb?.invoke() ?: convCb?.invoke()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Speak a navigation instruction.
     *
     * Interrupts any in-progress conversation TTS immediately (QUEUE_FLUSH).
     * Clears [onConversationDone] so the conversation controller does NOT re-listen
     * from the conversation path; [onDone] is invoked instead so the controller
     * can resume one listening session after the nav utterance finishes.
     *
     * @param onDone Optional callback invoked when this utterance finishes.
     *               Typically wired to [KentasConversationController.resumeAfterNavInterrupt].
     */
    fun speakNavigation(text: String, onDone: (() -> Unit)? = null) {
        Log.d(TAG, "speakNavigation (interrupting conv): '${text.take(60)}'")
        onConversationDone = null   // pre-empt any pending conversation callback
        onNavigationDone   = onDone
        ttsManager.speak(text)
    }

    /**
     * Speak a conversation response.
     *
     * After the utterance finishes (or errors), [onDone] is invoked on the
     * TtsManager's UtteranceProgressListener thread.  The conversation controller
     * uses this to re-enter the listening cycle.
     *
     * Will be interrupted by a subsequent [speakNavigation] call.
     */
    fun speakConversation(text: String, onDone: (() -> Unit)? = null) {
        Log.d(TAG, "speakConversation: '${text.take(60)}'")
        onConversationDone = onDone
        ttsManager.speak(text)
    }

    /** Stop any playing speech immediately. Does NOT invoke any pending callbacks. */
    fun stop() {
        onConversationDone = null
        onNavigationDone   = null
        watchdogJob?.cancel()
        watchdogJob = null
        ttsManager.stop()
    }

    /** Apply updated speech rate / pitch from settings. */
    fun applySettings() = ttsManager.applySettings()

    /** Full teardown — call from ViewModel.onCleared only. */
    fun release() {
        stop()
        ttsManager.release()
    }
}
