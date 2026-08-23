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

        assertEquals("2026-01-02", suggestion.startDate.toString())
        assertEquals("2026-01-06", suggestion.endDate.toString())
        assertEquals("2026-01-03", suggestion.awayStartDate.toString())
        assertEquals("2026-01-05", suggestion.awayEndDate.toString())
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

    @Test
    fun scopesDetectionAndUsesEditableOfflineDestinationLabel() {
        val points = listOf(
            point("2025-12-30", 37.56, 126.97),
            point("2026-01-01", 37.56, 126.97),
            point("2026-01-02", 35.68, 139.76),
            point("2026-01-03", 35.69, 139.75),
            point("2026-01-04", 37.56, 126.97),
            point("2026-01-05", 37.56, 126.97),
        )

        val suggestion = TripDetector.detect(
            Timeline(points),
            ZoneOffset.UTC,
            TripDetectionRequest(java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-01-05")),
            DestinationNameResolver { _, _ -> "Tokyo, Japan" },
        ).single()

        assertEquals("Tokyo, Japan", suggestion.destinationName)
        assertEquals("2026-01-01", suggestion.startDate.toString())
        assertEquals("2026-01-04", suggestion.endDate.toString())
        assertEquals(4, suggestion.usablePointCount)
    }

    @Test
    fun missingReturnBoundaryFallsBackToLastAwayDate() {
        val points = listOf(
            point("2025-12-29", 37.56, 126.97),
            point("2025-12-30", 37.56, 126.97),
            point("2025-12-31", 37.56, 126.97),
            point("2026-01-01", 37.56, 126.97),
            point("2026-01-02", 37.56, 126.97),
            point("2026-01-03", 35.68, 139.76),
            point("2026-01-04", 35.69, 139.75),
            point("2026-01-05", 35.67, 139.77),
        )

        val suggestion = TripDetector.detect(Timeline(points), ZoneOffset.UTC).single()

        assertEquals("2026-01-05", suggestion.endDate.toString())
        assertEquals(SuggestionConfidence.POSSIBLE, suggestion.confidence)
    }

    @Test
    fun largeOutOfRangeHistoryDoesNotUndersampleRequestedRange() {
        val historical = List(20_000) { index ->
            GeoPoint(
                Instant.parse("2020-01-01T00:00:00Z").plusSeconds(index.toLong()),
                37.56,
                126.97,
            )
        }
        val requested = listOf(
            point("2026-01-01", 37.56, 126.97),
            point("2026-01-02", 37.56, 126.97),
            point("2026-01-03", 35.68, 139.76),
            point("2026-01-04", 35.69, 139.75),
            point("2026-01-05", 35.67, 139.77),
            point("2026-01-06", 37.56, 126.97),
            point("2026-01-07", 37.56, 126.97),
        )

        val suggestions = TripDetector.detect(
            Timeline(historical + requested),
            ZoneOffset.UTC,
            TripDetectionRequest(java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-01-07")),
        )

        assertEquals(1, suggestions.size)
    }

    @Test
    fun ranksStrongMatchesByUsablePointsBeforeRecency() {
        val points = buildList {
            add(point("2026-01-01", 37.56, 126.97))
            add(point("2026-01-02", 37.56, 126.97))
            repeat(4) { add(point("2026-01-03", 35.68 + it * 0.001, 139.76)) }
            repeat(4) { add(point("2026-01-04", 35.68 + it * 0.001, 139.76)) }
            add(point("2026-01-05", 37.56, 126.97))
            add(point("2026-01-06", 37.56, 126.97))
            add(point("2026-01-07", 48.85, 2.35))
            add(point("2026-01-08", 48.86, 2.34))
            add(point("2026-01-09", 37.56, 126.97))
            add(point("2026-01-10", 37.56, 126.97))
        }

        val suggestions = TripDetector.detect(Timeline(points), ZoneOffset.UTC)

        assertEquals(2, suggestions.size)
        assertTrue(suggestions[0].usablePointCount > suggestions[1].usablePointCount)
        assertTrue(suggestions[0].startDate.isBefore(suggestions[1].startDate))
    }

    private fun point(date: String, latitude: Double, longitude: Double) = GeoPoint(
        Instant.parse("${date}T12:00:00Z"),
        latitude,
        longitude,
    )
}
