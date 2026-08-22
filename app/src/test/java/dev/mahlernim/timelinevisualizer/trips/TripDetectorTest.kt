package dev.mahlernim.timelinevisualizer.trips

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {
    @Test
    fun findsTightMultiDayEpisodeBracketedByHome() {
        val points = listOf(
            point("2026-01-01", 37.56, 126.97),
            point("2026-01-02", 37.57, 126.98),
            point("2026-01-03", 35.68, 139.76),
            point("2026-01-04", 35.69, 139.75),
            point("2026-01-05", 35.67, 139.77),
            point("2026-01-06", 37.56, 126.97),
            point("2026-01-07", 37.57, 126.98),
        )

        val suggestion = TripDetector.detect(Timeline(points), ZoneOffset.UTC).single()

        assertEquals("2026-01-03", suggestion.startDate.toString())
        assertEquals("2026-01-05", suggestion.endDate.toString())
        assertEquals(SuggestionConfidence.STRONG, suggestion.confidence)
        assertTrue(suggestion.distanceFromHomeKm > 1_000)
    }

    @Test
    fun ignoresSingleDayLongHop() {
        val points = listOf(
            point("2026-01-01", 37.56, 126.97),
            point("2026-01-02", 37.56, 126.97),
            point("2026-01-03", 35.68, 139.76),
            point("2026-01-04", 37.56, 126.97),
            point("2026-01-05", 37.56, 126.97),
        )

        assertTrue(TripDetector.detect(Timeline(points), ZoneOffset.UTC).isEmpty())
    }

    private fun point(date: String, latitude: Double, longitude: Double) = GeoPoint(
        Instant.parse("${date}T12:00:00Z"),
        latitude,
        longitude,
    )
}
