package dev.mahlernim.timelinevisualizer.trips

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCoverageCalculatorTest {
    @Test
    fun reportsLocalMovementPointsAndActiveDays() {
        val points = buildList {
            repeat(6) { index -> add(point("2026-04-03T0${index}:00:00Z", 35.0, 129.0 + index * 0.01)) }
            repeat(6) { index -> add(point("2026-04-04T0${index}:00:00Z", 35.1, 129.0 + index * 0.01)) }
        }

        val coverage = TripCoverageCalculator.calculate(
            Timeline(points),
            LocalDate.parse("2026-04-03"),
            LocalDate.parse("2026-04-04"),
            ZoneOffset.UTC,
        )

        assertEquals(12, coverage.usablePointCount)
        assertEquals(10, coverage.movementSegmentCount)
        assertEquals(2, coverage.activeDayCount)
        assertTrue(coverage.recordedMovementKm > 5.0)
        assertFalse(coverage.limited)
    }

    @Test
    fun longTransfersDoNotHideSparseDestinationCoverage() {
        val coverage = TripCoverageCalculator.calculate(
            Timeline(
                listOf(
                    point("2026-04-03T00:00:00Z", 37.56, 126.97),
                    point("2026-04-03T12:00:00Z", 35.68, 139.76),
                    point("2026-04-04T12:00:00Z", 35.69, 139.75),
                    point("2026-04-05T12:00:00Z", 37.56, 126.97),
                ),
            ),
            LocalDate.parse("2026-04-03"),
            LocalDate.parse("2026-04-05"),
            ZoneOffset.UTC,
        )

        assertEquals(4, coverage.usablePointCount)
        assertEquals(0, coverage.movementSegmentCount)
        assertEquals(0.0, coverage.recordedMovementKm, 0.0)
        assertTrue(coverage.limited)
    }

    @Test
    fun connectedComponentsDoNotCountMovementAcrossGap() {
        val first = Timeline(
            listOf(
                point("2026-04-03T00:00:00Z", 35.0, 129.00),
                point("2026-04-03T01:00:00Z", 35.0, 129.01),
            ),
        )
        val second = Timeline(
            listOf(
                point("2026-04-03T02:00:00Z", 35.0, 131.00),
                point("2026-04-03T03:00:00Z", 35.0, 131.01),
            ),
        )

        val coverage = TripCoverageCalculator.calculateConnected(
            listOf(first, second),
            LocalDate.parse("2026-04-03"),
            LocalDate.parse("2026-04-03"),
            ZoneOffset.UTC,
        )
        val flattenedCoverage = TripCoverageCalculator.calculate(
            Timeline(first.points + second.points),
            LocalDate.parse("2026-04-03"),
            LocalDate.parse("2026-04-03"),
            ZoneOffset.UTC,
        )

        assertEquals(4, coverage.usablePointCount)
        assertEquals(2, coverage.movementSegmentCount)
        assertTrue(coverage.recordedMovementKm in 1.0..3.0)
        assertEquals(3, flattenedCoverage.movementSegmentCount)
        assertTrue(flattenedCoverage.recordedMovementKm > 150.0)
    }

    private fun point(instant: String, latitude: Double, longitude: Double) = GeoPoint(
        Instant.parse(instant),
        latitude,
        longitude,
    )
}
