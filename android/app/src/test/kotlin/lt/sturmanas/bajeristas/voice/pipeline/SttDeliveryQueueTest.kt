package lt.sturmanas.bajeristas.voice.pipeline

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SttDeliveryQueue].
 *
 * All tests run on the JVM — no Android SDK required
 * ([testOptions.unitTests.isReturnDefaultValues = true] stubs [android.util.Log]).
 *
 * Coverage:
 *  - In-order delivery (trivial path)
 *  - Out-of-order delivery: later utterance arrives first, then earlier — must deliver in order
 *  - Stale drop: generation has advanced (mute/stop) before delivery — must drop, not block queue
 *  - HTTP failure (skip): failed utterance advances ordering so successor is not blocked
 *  - Mixed: stale drop interleaved with valid delivery
 *  - reset() clears state for a new session
 */
class SttDeliveryQueueTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun queue(onReady: (String) -> Unit) = SttDeliveryQueue(onReady)

    /** A currentGeneration lambda that always returns [gen]. */
    private fun fixedGen(gen: Int): () -> Int = { gen }

    // ── In-order delivery ──────────────────────────────────────────────────

    @Test fun `single utterance is delivered immediately`() = runBlocking {
        val delivered = mutableListOf<String>()
        val q = queue { delivered += it }

        q.offer(utteranceId = 0, generation = 1, text = "labas", currentGeneration = fixedGen(1))

        assertEquals(listOf("labas"), delivered)
    }

    @Test fun `two utterances arriving in order are both delivered`() = runBlocking {
        val delivered = mutableListOf<String>()
        val q = queue { delivered += it }

        q.offer(utteranceId = 0, generation = 1, text = "pirma", currentGeneration = fixedGen(1))
        q.offer(utteranceId = 1, generation = 1, text = "antra", currentGeneration = fixedGen(1))

        assertEquals(listOf("pirma", "antra"), delivered)
    }

    // ── Out-of-order delivery ──────────────────────────────────────────────

    /**
     * Core scenario from the bug report:
     *   - generation=30 utterance ends → STT_UPLOAD_STARTED (utteranceId=0)
     *   - new utterance starts → STT_UPLOAD_STARTED (utteranceId=1)
     *   - utteranceId=1 STT_UPLOAD_COMPLETED arrives first
     *   - utteranceId=0 STT_UPLOAD_COMPLETED arrives second
     *
     * Expected: delivered in utterance order (0, then 1), not network-arrival order.
     */
    @Test fun `utterance arriving out of order is buffered and delivered after its predecessor`() =
        runBlocking {
            val delivered = mutableListOf<String>()
            val q = queue { delivered += it }
            val gen = fixedGen(5)

            // Utterance 1 (id=1) response arrives first — must be buffered.
            q.offer(utteranceId = 1, generation = 5, text = "antra", currentGeneration = gen)
            assertEquals(
                "utterance 1 must not be delivered before utterance 0 arrives",
                emptyList<String>(),
                delivered,
            )

            // Utterance 0 (id=0) response arrives second — both must drain in order.
            q.offer(utteranceId = 0, generation = 5, text = "pirma", currentGeneration = gen)
            assertEquals(
                "utterances must be delivered in utterance order, not network-arrival order",
                listOf("pirma", "antra"),
                delivered,
            )
        }

    @Test fun `three utterances all arriving in reverse order deliver in correct order`() =
        runBlocking {
            val delivered = mutableListOf<String>()
            val q = queue { delivered += it }
            val gen = fixedGen(3)

            q.offer(utteranceId = 2, generation = 3, text = "trečia", currentGeneration = gen)
            q.offer(utteranceId = 1, generation = 3, text = "antra",  currentGeneration = gen)
            assertTrue("nothing delivered until id=0 arrives", delivered.isEmpty())

            q.offer(utteranceId = 0, generation = 3, text = "pirma", currentGeneration = gen)
            assertEquals(listOf("pirma", "antra", "trečia"), delivered)
        }

    // ── Stale-generation drop ──────────────────────────────────────────────

    /**
     * Pipeline was muted/stopped while the upload was in-flight:
     * generationId advanced from 5 → 6 before the response arrived.
     * The transcript must be silently dropped; the queue must still advance
     * past id=0 so the next utterance (id=1, gen=6) is not blocked.
     */
    @Test fun `stale transcript is dropped and does not block later utterances`() = runBlocking {
        val delivered = mutableListOf<String>()
        var currentGen = 5
        val q = queue { delivered += it }

        // Generation advances (pipeline muted) before the response arrives.
        currentGen = 6

        // Utterance 0 was captured at gen=5 but current is now 6 → stale, drop.
        q.offer(utteranceId = 0, generation = 5, text = "senas", currentGeneration = { currentGen })
        assertTrue("stale transcript must be dropped", delivered.isEmpty())

        // Utterance 1, captured after the mute at gen=6, must still be deliverable.
        q.offer(utteranceId = 1, generation = 6, text = "naujas", currentGeneration = { currentGen })
        assertEquals(
            "valid utterance after stale drop must be delivered",
            listOf("naujas"),
            delivered,
        )
    }

    @Test fun `stale utterance buffered behind out-of-order arrival is dropped on drain`() =
        runBlocking {
            val delivered = mutableListOf<String>()
            var currentGen = 5
            val q = queue { delivered += it }

            // id=1 arrives first (out of order) at gen=5.
            q.offer(utteranceId = 1, generation = 5, text = "antra", currentGeneration = { currentGen })
            assertTrue(delivered.isEmpty())

            // Generation advances before id=0 arrives.
            currentGen = 6

            // id=0 arrives stale (gen=5, current=6) — dropped; id=1 was already buffered
            // with gen=5, also stale when drained.
            q.offer(utteranceId = 0, generation = 5, text = "pirma", currentGeneration = { currentGen })
            assertTrue("both stale utterances must be dropped", delivered.isEmpty())
        }

    // ── HTTP failure (skip) ────────────────────────────────────────────────

    @Test fun `failed upload does not block successor utterance`() = runBlocking {
        val delivered = mutableListOf<String>()
        val q = queue { delivered += it }
        val gen = fixedGen(1)

        // Utterance 0 fails STT.
        q.skip(utteranceId = 0, currentGeneration = gen)
        assertTrue("nothing to deliver yet", delivered.isEmpty())

        // Utterance 1 succeeds — must be delivered even though 0 failed.
        q.offer(utteranceId = 1, generation = 1, text = "labas", currentGeneration = gen)
        assertEquals(listOf("labas"), delivered)
    }

    @Test fun `out-of-order successor unblocks after predecessor skip`() = runBlocking {
        val delivered = mutableListOf<String>()
        val q = queue { delivered += it }
        val gen = fixedGen(2)

        // Utterance 1 response arrives before utterance 0 finishes.
        q.offer(utteranceId = 1, generation = 2, text = "antra", currentGeneration = gen)
        assertTrue(delivered.isEmpty())

        // Utterance 0 fails.
        q.skip(utteranceId = 0, currentGeneration = gen)

        // Utterance 1 should now drain.
        assertEquals(listOf("antra"), delivered)
    }

    // ── reset() ────────────────────────────────────────────────────────────

    @Test fun `reset clears state so next session starts from id zero`() = runBlocking {
        val delivered = mutableListOf<String>()
        val q = queue { delivered += it }
        val gen = fixedGen(1)

        q.offer(utteranceId = 0, generation = 1, text = "pirma", currentGeneration = gen)
        assertEquals(listOf("pirma"), delivered)

        // Simulate pipeline stop/start.
        q.reset()
        delivered.clear()

        // New session — ids start from 0 again.
        q.offer(utteranceId = 0, generation = 1, text = "nauja sesija", currentGeneration = gen)
        assertEquals(listOf("nauja sesija"), delivered)
    }

    // ── Concurrent delivery ────────────────────────────────────────────────

    /**
     * Simulates the exact race from the bug report using real coroutines:
     * two uploads in-flight simultaneously; the second one completes first.
     *
     * Uses a [Mutex] per-upload to control when each "network response" returns,
     * verifying that the delivery queue serialises the callbacks correctly.
     */
    @Test fun `concurrent uploads completing out of order deliver in utterance order`() =
        runBlocking {
            val delivered = mutableListOf<String>()
            val q = queue { delivered += it }

            // Gate locks: each coroutine blocks until its gate is released.
            val gate0 = Mutex(locked = true)  // utterance 0 upload — released second
            val gate1 = Mutex(locked = true)  // utterance 1 upload — released first

            val job0 = launch {
                gate0.withLock { /* wait */ }
                q.offer(utteranceId = 0, generation = 7, text = "pirma", currentGeneration = fixedGen(7))
            }
            val job1 = launch {
                gate1.withLock { /* wait */ }
                q.offer(utteranceId = 1, generation = 7, text = "antra", currentGeneration = fixedGen(7))
            }

            // Utterance 1 completes first.
            gate1.unlock()
            job1.join()
            assertEquals("id=1 must be buffered, not yet delivered", emptyList<String>(), delivered)

            // Utterance 0 completes second.
            gate0.unlock()
            job0.join()
            assertEquals(
                "must deliver in utterance order (0 then 1), not completion order",
                listOf("pirma", "antra"),
                delivered,
            )
        }
}
