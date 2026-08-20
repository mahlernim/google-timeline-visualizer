package dev.mahlernim.timelinevisualizer.privacy

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelinePrivacyMaskerTest {
    @Test
    fun multipleAreasUseIndependentRadiiAndBreakHiddenIntervals() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.60, 127.00),
            point("2026-01-01T01:00:00Z", 37.5665, 126.9780),
            point("2026-01-01T02:00:00Z", 37.50, 127.00),
            point("2026-01-01T03:00:00Z", 35.1796, 129.0756),
            point("2026-01-01T04:00:00Z", 35.00, 129.00),
        )
        val areas = listOf(
            area("home", 37.5665, 126.9780, 3.0),
            area("family", 35.1796, 129.0756, 1.0),
        )

        val result = TimelinePrivacyMasker.mask(points, areas)

        assertEquals(2, result.hiddenPointCount)
        assertEquals(2, result.hiddenIntervalCount)
        assertEquals(listOf(points[0], points[2], points[4]), result.points.map {
            it.copy(startsNewRouteSegment = false)
        })
        assertTrue(result.points[1].startsNewRouteSegment)
        assertTrue(result.points[2].startsNewRouteSegment)
    }

    @Test
    fun allTravelTypesFollowTheSameGeographicRule() {
        val inside = point("2026-01-01T00:00:00Z", 37.5665, 126.9780)
        val area = area("airport or home", 37.5665, 126.9780, 5.0)

        val result = TimelinePrivacyMasker.mask(listOf(inside), listOf(area))

        assertEquals(1, result.hiddenPointCount)
        assertTrue(result.points.isEmpty())
    }

    @Test
    fun noAreasReturnTheOriginalList() {
        val points = listOf(point("2026-01-01T00:00:00Z", 37.0, 127.0))

        val result = TimelinePrivacyMasker.mask(points, emptyList())

        assertSame(points, result.points)
        assertEquals(0, result.hiddenPointCount)
    }

    @Test
    fun firstVisiblePointDoesNotStartWithAnOrphanedBreak() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.5665, 126.9780),
            point("2026-01-01T01:00:00Z", 36.0, 127.0),
        )

        val result = TimelinePrivacyMasker.mask(points, listOf(area("home", 37.5665, 126.9780, 5.0)))

        assertEquals(1, result.points.size)
        assertFalse(result.points.single().startsNewRouteSegment)
    }

    @Test
    fun sparseSegmentCrossingAnAreaIsHiddenEvenWithoutAnInsidePoint() {
        val start = point("2026-01-01T00:00:00Z", 37.5665, 126.90)
        val end = point("2026-01-01T01:00:00Z", 37.5665, 127.05)
        val home = area("home", 37.5665, 126.9780, 2.0)

        val result = TimelinePrivacyMasker.mask(listOf(start, end), listOf(home))

        assertEquals(0, result.hiddenPointCount)
        assertEquals(1, result.hiddenIntervalCount)
        assertEquals(2, result.points.size)
        assertTrue(result.points[1].startsNewRouteSegment)
    }

    @Test
    fun nearbySegmentOutsideAnAreaRemainsContinuous() {
        val start = point("2026-01-01T00:00:00Z", 37.60, 126.90)
        val end = point("2026-01-01T01:00:00Z", 37.60, 127.05)
        val home = area("home", 37.5665, 126.9780, 1.0)

        val result = TimelinePrivacyMasker.mask(listOf(start, end), listOf(home))

        assertEquals(0, result.hiddenIntervalCount)
        assertFalse(result.points[1].startsNewRouteSegment)
    }

    private fun point(time: String, latitude: Double, longitude: Double) =
        GeoPoint(Instant.parse(time), latitude, longitude)

    private fun area(id: String, latitude: Double, longitude: Double, radiusKm: Double) =
        PrivacyArea(id, id, latitude, longitude, radiusKm)
}
