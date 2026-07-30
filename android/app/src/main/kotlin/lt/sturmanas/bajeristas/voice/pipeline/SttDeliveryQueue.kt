package lt.sturmanas.bajeristas.voice.pipeline

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TreeMap

/**
 * Serialises STT transcript delivery so that consecutive utterances are always
 * forwarded to [onTranscriptReady] in utterance order, even when their HTTP
 * responses arrive out of order.
 *
 * ## Problem
 * The pipeline allows up to [PipelineConfig.MAX_CONCURRENT_TRANSCRIPTIONS]
 * uploads in flight simultaneously.  A fast network path for the second
 * utterance can return before the first, meaning [onTranscriptReady] would
 * be invoked for utterance N+1 before utterance N — wrong order from the
 * caller's perspective.
 *
 * ## Solution
 * Each utterance is assigned a monotonically increasing [utteranceId] at the
 * moment its speech ends.  When a transcript arrives it is stored in [pending]
 * keyed by [utteranceId].  The drain loop then forwards the lowest consecutive
 * run (starting from [nextId]) to [onTranscriptReady], buffering any
 * higher-ID results until their predecessor arrives.
 *
 * ## Stale drops
 * A transcript is dropped — and [STT_RESULT_DROPPED] is logged — only when
 * the pipeline's `generationId` has advanced since the utterance was captured
 * (i.e. the pipeline was muted or stopped while the upload was in-flight).
 * The utterance ID is still consumed from [pending] so later utterances are
 * never blocked.
 *
 * ## HTTP failures
 * A failed upload is represented as [Entry.Skipped].  It advances [nextId]
 * without invoking [onTranscriptReady], ensuring the ordering invariant is
 * maintained even when a transcription request errors out.
 *
 * ## Thread safety
 * [offer] and [skip] are `suspend` functions protected by [mutex].
 * [reset] must be called only when no coroutines are active (from
 * [ContinuousMicrophonePipeline.start]).
 */
internal class SttDeliveryQueue(
    private val onTranscriptReady: (String) -> Unit,
) {

    private sealed class Entry {
        /** Successful transcription — delivery is subject to the stale-generation check. */
        data class Ready(val generation: Int, val text: String) : Entry()
        /** HTTP failure — advances ordering without delivering. */
        object Skipped : Entry()
    }

    private val mutex = Mutex()

    /** utteranceId → delivery entry */
    private val pending = TreeMap<Int, Entry>()

    /** Utterance ID that should be delivered next. */
    private var nextId = 0

    companion object {
        private const val TAG = "MicPipeline"
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Reset all sequencing state.  Must be called from
     * [ContinuousMicrophonePipeline.start] before any capture coroutines are
     * launched.
     */
    fun reset() {
        pending.clear()
        nextId = 0
    }

    /**
     * Accept a successfully transcribed utterance and deliver it (or buffer
     * it) in utterance order.
     *
     * If this is the next expected utterance it is delivered immediately;
     * otherwise it is stored until its predecessor(s) arrive.
     *
     * @param utteranceId       Monotonic ID assigned at utterance completion.
     * @param generation        Pipeline generationId captured at that moment.
     * @param text              Transcript returned by the STT backend.
     * @param currentGeneration Returns the pipeline's live [generationId].
     */
    suspend fun offer(
        utteranceId: Int,
        generation: Int,
        text: String,
        currentGeneration: () -> Int,
    ) {
        mutex.withLock {
            pending[utteranceId] = Entry.Ready(generation, text)
            drain(currentGeneration)
        }
    }

    /**
     * Advance the ordering queue past a failed or otherwise undeliverable
     * utterance so that subsequent utterances are not blocked.
     *
     * @param utteranceId       ID of the utterance whose upload failed.
     * @param currentGeneration Returns the pipeline's live [generationId].
     */
    suspend fun skip(
        utteranceId: Int,
        currentGeneration: () -> Int,
    ) {
        mutex.withLock {
            pending[utteranceId] = Entry.Skipped
            drain(currentGeneration)
        }
    }

    // ── Internal drain ─────────────────────────────────────────────────────

    /**
     * Called inside [mutex]; delivers all consecutively available utterances
     * starting from [nextId].
     */
    private fun drain(currentGeneration: () -> Int) {
        while (pending.containsKey(nextId)) {
            val id = nextId
            val entry = pending.remove(id)!!
            nextId++

            when (entry) {
                is Entry.Skipped -> {
                    // HTTP failure was already logged at the call site; just advance.
                }
                is Entry.Ready -> {
                    val cur = currentGeneration()
                    if (cur != entry.generation) {
                        Log.d(
                            TAG,
                            "STT_RESULT_DROPPED reason=stale " +
                            "generation=${entry.generation} currentGeneration=$cur",
                        )
                    } else {
                        Log.i(
                            TAG,
                            "STT_TRANSCRIPT_DELIVERED " +
                            "generation=${entry.generation} utteranceId=$id " +
                            "transcriptLength=${entry.text.length}",
                        )
                        onTranscriptReady(entry.text)
                    }
                }
            }
        }
    }
}
