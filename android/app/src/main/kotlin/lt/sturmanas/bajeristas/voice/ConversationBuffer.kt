package lt.sturmanas.bajeristas.voice

/**
 * In-memory conversation history for one drive.
 *
 * Stores the last [maxTurns] complete exchanges as OpenAI-compatible (role, content) pairs.
 * One turn = one driver message ("user") + one Kentas reply ("assistant") = two entries.
 *
 * Lifecycle contract (enforced by the caller):
 *   - Created once per [SturmanasApp] composition.
 *   - [clear] must be called at the start of every new drive and when navigation ends.
 *   - No persistence between drives — history resets by design.
 *
 * Thread-safety: all methods must be called from the Compose main-thread dispatcher only.
 * The caller snapshots [messages] on the main thread before launching a coroutine so the
 * buffer is never accessed concurrently.
 */
class ConversationBuffer(val maxTurns: Int = 5) {

    // Chronological list of (role, content) pairs. "user" = driver, "assistant" = Kentas.
    private val entries = ArrayDeque<Pair<String, String>>()

    /**
     * All stored messages in chronological order, ready to splice into an OpenAI
     * messages array. Returns a snapshot — safe to capture before a coroutine launch.
     */
    val messages: List<Pair<String, String>> get() = entries.toList()

    /**
     * Record one complete exchange. Evicts the oldest turn (as a pair) when full
     * so that role/content pairing is never broken by a partial eviction.
     *
     * Only call this when [kentasReply] is genuine content — never pass error strings,
     * otherwise the model will treat previous errors as valid conversational context.
     */
    fun addTurn(driverText: String, kentasReply: String) {
        while (entries.size >= maxTurns * 2) {
            entries.removeFirst() // evict driver message …
            entries.removeFirst() // … and its paired Kentas reply together
        }
        entries.addLast("user" to driverText)
        entries.addLast("assistant" to kentasReply)
    }

    /** Discard all history. Call at the start of a new drive and when navigation ends. */
    fun clear() = entries.clear()
}
