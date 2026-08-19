package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LocationOutlierFilterTest {
    @Test
    fun removesSingleImpossibleOutAndBackPoint() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.5665, 126.9780),
            point("2026-01-01T01:00:00Z", 0.0, -50.0),
            point("2026-01-01T02:00:00Z", 37.5700, 126.9800),
        )

        val result = LocationOutlierFilter.filter(points)

        assertEquals(listOf(points.first(), points.last()), result.points)
        assertEquals(1, result.removedCount)
    }

    @Test
    fun removesShortClusteredSpoofingExcursion() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.5665, 126.9780),
            point("2026-01-01T01:00:00Z", 0.0, -50.0),
            point("2026-01-01T01:10:00Z", 0.2, -50.1),
            point("2026-01-01T02:00:00Z", 37.5700, 126.9800),
        )

        val result = LocationOutlierFilter.filter(points)

        assertEquals(listOf(points.first(), points.last()), result.points)
        assertEquals(2, result.removedCount)
    }

    @Test
    fun preservesPlausiblyTimedIntercontinentalTravel() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.5665, 126.9780),
            point("2026-01-02T14:00:00Z", -23.5505, -46.6333),
            point("2026-01-10T12:00:00Z", 37.5700, 126.9800),
        )

        val result = LocationOutlierFilter.filter(points)

        assertEquals(points, result.points)
        assertEquals(0, result.removedCount)
    }

    @Test
    fun preservesOneWayLongDistanceTravelAndEndpoints() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.5665, 126.9780),
            point("2026-01-01T12:00:00Z", 35.6762, 139.6503),
            point("2026-01-02T12:00:00Z", 51.5072, -0.1276),
        )

        val result = LocationOutlierFilter.filter(points)

        assertEquals(points, result.points)
        assertEquals(points.first(), result.points.first())
        assertEquals(points.last(), result.points.last())
    }

    @Test
    fun offModeReturnsEveryPoint() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.5665, 126.9780),
            point("2026-01-01T01:00:00Z", 0.0, -50.0),
            point("2026-01-01T02:00:00Z", 37.5700, 126.9800),
        )

        val result = LocationOutlierFilter.filter(points, LocationFilterMode.OFF)

        assertEquals(points, result.points)
        assertEquals(0, result.removedCount)
    }

    private fun point(instant: String, latitude: Double, longitude: Double) =
        GeoPoint(Instant.parse(instant), latitude, longitude)
}
