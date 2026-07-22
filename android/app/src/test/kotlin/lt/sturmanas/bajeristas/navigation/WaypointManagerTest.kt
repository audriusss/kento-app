package lt.sturmanas.bajeristas.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [WaypointManager].
 *
 * All tests are pure-Kotlin — no Android framework, no Robolectric.
 * StateFlow values are read synchronously via `.value`.
 */
class WaypointManagerTest {

    private lateinit var wm: WaypointManager

    // Helper factories
    private fun entry(name: String, query: String = name) = StopoverEntry(name, query)
    private fun dest(name: String) = entry(name)

    @Before
    fun setUp() {
        wm = WaypointManager()
    }

    // ── Initial state ──────────────────────────────────────────────────────

    @Test
    fun `initial stopovers list is empty`() {
        assertTrue(wm.stopovers.value.isEmpty())
    }

    @Test
    fun `initial finalDestination is null`() {
        assertNull(wm.finalDestination)
    }

    @Test
    fun `initial nextTarget returns null`() {
        assertNull(wm.nextTarget())
    }

    @Test
    fun `initial allStops returns empty list`() {
        assertTrue(wm.allStops().isEmpty())
    }

    @Test
    fun `initial hasStopovers returns false`() {
        assertFalse(wm.hasStopovers())
    }

    // ── setFinalDestination ────────────────────────────────────────────────

    @Test
    fun `setFinalDestination stores the entry`() {
        wm.setFinalDestination(dest("Akropolis"))
        assertEquals("Akropolis", wm.finalDestination?.displayName)
    }

    @Test
    fun `setFinalDestination stores the resolvedQuery correctly`() {
        wm.setFinalDestination(entry("Akropolis", "Taikos pr. 61, Klaipėda"))
        assertEquals("Taikos pr. 61, Klaipėda", wm.finalDestination?.resolvedQuery)
    }

    @Test
    fun `setFinalDestination clears existing stopovers`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.addStopover(entry("Maxima"))
        assertEquals(2, wm.stopovers.value.size)

        wm.setFinalDestination(dest("Vilnius"))
        assertTrue(wm.stopovers.value.isEmpty())
        assertEquals("Vilnius", wm.finalDestination?.displayName)
    }

    @Test
    fun `setFinalDestination replaces previous final destination`() {
        wm.setFinalDestination(dest("Pirmas tikslas"))
        wm.setFinalDestination(dest("Antras tikslas"))
        assertEquals("Antras tikslas", wm.finalDestination?.displayName)
    }

    @Test
    fun `nextTarget returns finalDestination when no stopovers`() {
        wm.setFinalDestination(dest("Akropolis"))
        assertEquals("Akropolis", wm.nextTarget()?.displayName)
    }

    // ── addStopover ────────────────────────────────────────────────────────

    @Test
    fun `addStopover returns true for new entry`() {
        wm.setFinalDestination(dest("Akropolis"))
        assertTrue(wm.addStopover(entry("Lidl")))
    }

    @Test
    fun `addStopover appends to list`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.addStopover(entry("Maxima"))
        val stops = wm.stopovers.value
        assertEquals(2, stops.size)
        assertEquals("Lidl", stops[0].displayName)
        assertEquals("Maxima", stops[1].displayName)
    }

    @Test
    fun `addStopover returns false for duplicate resolvedQuery`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl", "Minijos 131, Klaipėda"))
        val added = wm.addStopover(entry("Lidl 2", "Minijos 131, Klaipėda"))
        assertFalse(added)
        assertEquals(1, wm.stopovers.value.size)
    }

    @Test
    fun `addStopover allows same displayName with different resolvedQuery`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl", "Minijos 131, Klaipėda"))
        val added = wm.addStopover(entry("Lidl", "Šilutės pl. 19, Klaipėda"))
        assertTrue(added)
        assertEquals(2, wm.stopovers.value.size)
    }

    @Test
    fun `addStopover does not affect finalDestination`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        assertEquals("Akropolis", wm.finalDestination?.displayName)
    }

    @Test
    fun `addStopover updates stopovers StateFlow`() {
        wm.setFinalDestination(dest("Akropolis"))
        assertEquals(0, wm.stopovers.value.size)
        wm.addStopover(entry("Lidl"))
        assertEquals(1, wm.stopovers.value.size)
    }

    @Test
    fun `nextTarget returns first stopover when stopovers present`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.addStopover(entry("Maxima"))
        assertEquals("Lidl", wm.nextTarget()?.displayName)
    }

    @Test
    fun `hasStopovers returns true after addStopover`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        assertTrue(wm.hasStopovers())
    }

    // ── removeLastStopover ─────────────────────────────────────────────────

    @Test
    fun `removeLastStopover returns null on empty list`() {
        assertNull(wm.removeLastStopover())
    }

    @Test
    fun `removeLastStopover returns the removed entry`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.addStopover(entry("Maxima"))
        val removed = wm.removeLastStopover()
        assertEquals("Maxima", removed?.displayName)
    }

    @Test
    fun `removeLastStopover shrinks list by one`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.addStopover(entry("Maxima"))
        wm.removeLastStopover()
        assertEquals(1, wm.stopovers.value.size)
        assertEquals("Lidl", wm.stopovers.value[0].displayName)
    }

    @Test
    fun `removeLastStopover from single-element list leaves empty`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.removeLastStopover()
        assertTrue(wm.stopovers.value.isEmpty())
        assertFalse(wm.hasStopovers())
    }

    @Test
    fun `removeLastStopover does not affect finalDestination`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.removeLastStopover()
        assertEquals("Akropolis", wm.finalDestination?.displayName)
    }

    // ── removeStopoverAt ───────────────────────────────────────────────────

    @Test
    fun `removeStopoverAt valid index removes correct entry`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Stop A"))
        wm.addStopover(entry("Stop B"))
        wm.addStopover(entry("Stop C"))
        val removed = wm.removeStopoverAt(1)
        assertEquals("Stop B", removed?.displayName)
        assertEquals(listOf("Stop A", "Stop C"), wm.stopovers.value.map { it.displayName })
    }

    @Test
    fun `removeStopoverAt index zero removes first element`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Stop A"))
        wm.addStopover(entry("Stop B"))
        val removed = wm.removeStopoverAt(0)
        assertEquals("Stop A", removed?.displayName)
        assertEquals(listOf("Stop B"), wm.stopovers.value.map { it.displayName })
    }

    @Test
    fun `removeStopoverAt negative index returns null`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        assertNull(wm.removeStopoverAt(-1))
        assertEquals(1, wm.stopovers.value.size)
    }

    @Test
    fun `removeStopoverAt out-of-bounds index returns null`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        assertNull(wm.removeStopoverAt(5))
        assertEquals(1, wm.stopovers.value.size)
    }

    @Test
    fun `removeStopoverAt single element list leaves empty`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.removeStopoverAt(0)
        assertTrue(wm.stopovers.value.isEmpty())
    }

    // ── clearStopovers ─────────────────────────────────────────────────────

    @Test
    fun `clearStopovers empties the list`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.addStopover(entry("Maxima"))
        wm.clearStopovers()
        assertTrue(wm.stopovers.value.isEmpty())
    }

    @Test
    fun `clearStopovers does not affect finalDestination`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.clearStopovers()
        assertEquals("Akropolis", wm.finalDestination?.displayName)
    }

    @Test
    fun `clearStopovers on empty list is safe`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.clearStopovers() // should not throw
        assertTrue(wm.stopovers.value.isEmpty())
    }

    @Test
    fun `nextTarget returns finalDestination after clearStopovers`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.clearStopovers()
        assertEquals("Akropolis", wm.nextTarget()?.displayName)
    }

    // ── clear ──────────────────────────────────────────────────────────────

    @Test
    fun `clear resets stopovers to empty`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.clear()
        assertTrue(wm.stopovers.value.isEmpty())
    }

    @Test
    fun `clear nulls finalDestination`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.clear()
        assertNull(wm.finalDestination)
    }

    @Test
    fun `nextTarget returns null after clear`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.clear()
        assertNull(wm.nextTarget())
    }

    @Test
    fun `allStops returns empty after clear`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.clear()
        assertTrue(wm.allStops().isEmpty())
    }

    // ── advanceToNextStop ──────────────────────────────────────────────────

    @Test
    fun `advanceToNextStop with one stopover drops it and returns finalDestination`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        val next = wm.advanceToNextStop()
        assertEquals("Akropolis", next?.displayName)
        assertTrue(wm.stopovers.value.isEmpty())
    }

    @Test
    fun `advanceToNextStop with multiple stopovers drops first and returns second`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Stop A"))
        wm.addStopover(entry("Stop B"))
        wm.addStopover(entry("Stop C"))
        val next = wm.advanceToNextStop()
        assertEquals("Stop B", next?.displayName)
        assertEquals(listOf("Stop B", "Stop C"), wm.stopovers.value.map { it.displayName })
    }

    @Test
    fun `advanceToNextStop with no stopovers returns finalDestination unchanged`() {
        wm.setFinalDestination(dest("Akropolis"))
        val next = wm.advanceToNextStop()
        assertEquals("Akropolis", next?.displayName)
        assertTrue(wm.stopovers.value.isEmpty())
    }

    @Test
    fun `advanceToNextStop with no final destination and no stopovers returns null`() {
        val next = wm.advanceToNextStop()
        assertNull(next)
    }

    @Test
    fun `advanceToNextStop chains correctly through all stops`() {
        wm.setFinalDestination(dest("Final"))
        wm.addStopover(entry("Stop 1"))
        wm.addStopover(entry("Stop 2"))

        assertEquals("Stop 1", wm.nextTarget()?.displayName)
        val after1 = wm.advanceToNextStop()
        assertEquals("Stop 2", after1?.displayName)
        val after2 = wm.advanceToNextStop()
        assertEquals("Final", after2?.displayName)
        assertTrue(wm.stopovers.value.isEmpty())
    }

    // ── allStops ───────────────────────────────────────────────────────────

    @Test
    fun `allStops returns empty when no finalDestination set`() {
        assertTrue(wm.allStops().isEmpty())
    }

    @Test
    fun `allStops with only finalDestination returns single-element list`() {
        wm.setFinalDestination(dest("Akropolis"))
        val stops = wm.allStops()
        assertEquals(1, stops.size)
        assertEquals("Akropolis", stops[0].displayName)
    }

    @Test
    fun `allStops with stopovers returns correct order`() {
        wm.setFinalDestination(dest("Final"))
        wm.addStopover(entry("Stop A"))
        wm.addStopover(entry("Stop B"))
        val stops = wm.allStops()
        assertEquals(3, stops.size)
        assertEquals("Stop A", stops[0].displayName)
        assertEquals("Stop B", stops[1].displayName)
        assertEquals("Final", stops[2].displayName)
    }

    @Test
    fun `allStops does not modify internal state`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.allStops()
        assertEquals(1, wm.stopovers.value.size)
        assertNotNull(wm.finalDestination)
    }

    // ── StateFlow reactivity ───────────────────────────────────────────────

    @Test
    fun `stopovers StateFlow reflects addStopover`() {
        wm.setFinalDestination(dest("Akropolis"))
        assertEquals(0, wm.stopovers.value.size)
        wm.addStopover(entry("Lidl"))
        assertEquals(1, wm.stopovers.value.size)
        wm.addStopover(entry("Maxima"))
        assertEquals(2, wm.stopovers.value.size)
    }

    @Test
    fun `stopovers StateFlow reflects removeLastStopover`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.addStopover(entry("Maxima"))
        wm.removeLastStopover()
        assertEquals(1, wm.stopovers.value.size)
    }

    @Test
    fun `stopovers StateFlow reflects clearStopovers`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.clearStopovers()
        assertEquals(0, wm.stopovers.value.size)
    }

    @Test
    fun `stopovers StateFlow reflects clear`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.clear()
        assertEquals(0, wm.stopovers.value.size)
    }

    // ── Duplicate detection edge cases ─────────────────────────────────────

    @Test
    fun `duplicate detection is by resolvedQuery not displayName`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl Šilutė", "Šilutės pl. 19, Klaipėda"))
        // Same displayName, different query — should be allowed
        val added1 = wm.addStopover(entry("Lidl Šilutė", "Minijos 131, Klaipėda"))
        assertTrue(added1)
        // Same query — should be rejected
        val added2 = wm.addStopover(entry("Lidl 2", "Šilutės pl. 19, Klaipėda"))
        assertFalse(added2)
        assertEquals(2, wm.stopovers.value.size)
    }

    @Test
    fun `can add stopovers after clear and setFinalDestination again`() {
        wm.setFinalDestination(dest("Akropolis"))
        wm.addStopover(entry("Lidl"))
        wm.clear()

        wm.setFinalDestination(dest("Mega"))
        val added = wm.addStopover(entry("Rimi"))
        assertTrue(added)
        assertEquals(1, wm.stopovers.value.size)
        assertEquals("Mega", wm.finalDestination?.displayName)
    }
}
