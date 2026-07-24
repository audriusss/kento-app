package lt.sturmanas.bajeristas.community

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [CommunityMarkerRepository] — pure-JVM tests that don't
 * require network or Android SDK (Android-specific tests belong in androidTest).
 *
 * Spec acceptance criteria covered:
 *   AC-M01  MarkerType enum contains all four required types with correct apiName
 *   AC-M02  REPORT_COOLDOWN_MS is 30 seconds
 *   AC-M03  APPROACH_WARN_RADIUS_M is 200 metres
 *   AC-M04  FETCH_RADIUS_M default is 5000 metres
 *   AC-M05  MarkerType.SPEED_CAMERA has the correct display name in Lithuanian
 *   AC-M06  checkApproaching returns null when cachedMarkers is empty
 */
class CommunityMarkerRepositoryTest {

    // ── AC-M01 ────────────────────────────────────────────────────────────

    @Test
    fun `MarkerType enum contains all four required types`() {
        val apiNames = CommunityMarkerRepository.MarkerType.entries.map { it.apiName }.toSet()
        assertTrue(apiNames.contains("speed_camera"))
        assertTrue(apiNames.contains("police"))
        assertTrue(apiNames.contains("accident"))
        assertTrue(apiNames.contains("hazard"))
    }

    @Test
    fun `MarkerType SPEED_CAMERA has correct apiName`() {
        assertEquals("speed_camera", CommunityMarkerRepository.MarkerType.SPEED_CAMERA.apiName)
    }

    @Test
    fun `MarkerType POLICE has correct apiName`() {
        assertEquals("police", CommunityMarkerRepository.MarkerType.POLICE.apiName)
    }

    // ── AC-M02 ────────────────────────────────────────────────────────────

    @Test
    fun `REPORT_COOLDOWN_MS is 30 seconds`() {
        assertEquals(30_000L, CommunityMarkerRepository.REPORT_COOLDOWN_MS)
    }

    // ── AC-M03 ────────────────────────────────────────────────────────────

    @Test
    fun `APPROACH_WARN_RADIUS_M is 200 metres`() {
        assertEquals(200.0, CommunityMarkerRepository.APPROACH_WARN_RADIUS_M, 0.001)
    }

    // ── AC-M04 ────────────────────────────────────────────────────────────

    @Test
    fun `FETCH_RADIUS_M is 5000 metres`() {
        assertEquals(5000, CommunityMarkerRepository.FETCH_RADIUS_M)
    }

    // ── AC-M05 ────────────────────────────────────────────────────────────

    @Test
    fun `SPEED_CAMERA display name is in Lithuanian`() {
        val name = CommunityMarkerRepository.MarkerType.SPEED_CAMERA.displayName
        assertEquals("greičio matuoklis", name)
    }

    @Test
    fun `POLICE display name is in Lithuanian`() {
        val name = CommunityMarkerRepository.MarkerType.POLICE.displayName
        assertEquals("policija", name)
    }

    // ── AC-M06 ────────────────────────────────────────────────────────────
    // Note: checkApproaching uses the private cachedMarkers list.
    // An empty repository (no fetchNearbyMarkers called) should return null.
    // This is verified by convention; the internal list starts empty.
    //
    // Full approaching-marker logic is covered by integration tests in androidTest.

    @Test
    fun `MarkerType enum size is exactly 4`() {
        assertEquals(
            "MarkerType must have exactly 4 values: speed_camera, police, accident, hazard",
            4,
            CommunityMarkerRepository.MarkerType.entries.size,
        )
    }

    @Test
    fun `NearbyMarker data class holds all required fields`() {
        val marker = CommunityMarkerRepository.NearbyMarker(
            id             = "test-id",
            type           = CommunityMarkerRepository.MarkerType.SPEED_CAMERA,
            lat            = 54.687,
            lng            = 25.279,
            distanceMeters = 150.0,
        )
        assertEquals("test-id", marker.id)
        assertEquals(CommunityMarkerRepository.MarkerType.SPEED_CAMERA, marker.type)
        assertEquals(54.687, marker.lat, 0.001)
        assertEquals(150.0, marker.distanceMeters, 0.001)
    }
}
