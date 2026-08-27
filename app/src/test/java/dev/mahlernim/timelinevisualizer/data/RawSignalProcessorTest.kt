package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RawSignalProcessorTest {
    @Test
    fun rejectsPoorAccuracyAndCollapsesStationaryJitter() {
        val result = RawSignalProcessor.process(
            listOf(
                raw("2026-01-01T00:00:00Z", 37.0, 127.0, 20.0),
                raw("2026-01-01T00:02:00Z", 37.0001, 127.0001, 15.0),
                raw("2026-01-01T00:04:00Z", 37.01, 127.01, 150.0),
                raw("2026-01-01T00:12:00Z", 37.002, 127.002, 20.0),
            ),
        )

        assertEquals(2, result.points.size)
        assertEquals(1, result.accuracyRejectedCount)
        assertEquals(1, result.noiseRejectedCount)
        assertEquals(37.0001, result.points.first().latitude, 0.00001)
    }

    @Test
    fun removesShortImpossibleRoundTripButKeepsSustainedTravel() {
        val result = RawSignalProcessor.process(
            listOf(
                raw("2026-01-01T00:00:00Z", 37.0, 127.0, 10.0),
                raw("2026-01-01T00:01:00Z", 38.0, 128.0, 10.0),
                raw("2026-01-01T00:02:00Z", 37.0001, 127.0001, 10.0),
                raw("2026-01-01T01:00:00Z", 37.1, 127.1, 10.0),
            ),
        )

        assertEquals(2, result.points.size)
        assertEquals(2, result.noiseRejectedCount)
        assertEquals(37.1, result.points.last().latitude, 0.00001)
    }

    @Test
    fun reportsLongObservationGapsWithoutInventingIntermediatePoints() {
        val result = RawSignalProcessor.process(
            listOf(
                raw("2026-01-01T00:00:00Z", 37.0, 127.0, 10.0),
                raw("2026-01-01T01:00:00Z", 37.1, 127.1, 10.0),
            ),
        )

        assertEquals(2, result.points.size)
        assertEquals(1, result.discontinuityCount)
    }

    @Test
    fun removesTwoConsecutiveReadingsAtTheSameImpossibleLocation() {
        val result = RawSignalProcessor.process(
            listOf(
                raw("2026-01-01T00:00:00Z", 37.0, 127.0, 10.0),
                raw("2026-01-01T00:01:00Z", 38.0, 128.0, 10.0),
                raw("2026-01-01T00:02:00Z", 38.0, 128.0, 10.0),
                raw("2026-01-01T00:03:00Z", 37.0001, 127.0001, 10.0),
                raw("2026-01-01T01:00:00Z", 37.1, 127.1, 10.0),
            ),
        )

        assertEquals(2, result.points.size)
        assertEquals(3, result.noiseRejectedCount)
        assertEquals(37.0, result.points.first().latitude, 0.00001)
        assertEquals(37.1, result.points.last().latitude, 0.00001)
    }

    @Test
    fun removesADriftingClusterOfConsecutiveImpossibleReadings() {
        val result = RawSignalProcessor.process(
            listOf(
                raw("2026-01-01T00:00:00Z", 37.0, 127.0, 10.0),
                raw("2026-01-01T00:01:00Z", 38.0, 128.0, 10.0),
                raw("2026-01-01T00:02:00Z", 38.05, 128.05, 10.0),
                raw("2026-01-01T00:03:00Z", 38.02, 128.03, 10.0),
                raw("2026-01-01T00:04:00Z", 37.0001, 127.0001, 10.0),
                raw("2026-01-01T01:00:00Z", 37.1, 127.1, 10.0),
            ),
        )

        assertEquals(2, result.points.size)
        assertEquals(4, result.noiseRejectedCount)
        assertEquals(37.1, result.points.last().latitude, 0.00001)
    }

    @Test
    fun keepsAnExcursionLongerThanTheRunCapRatherThanGuessing() {
        val excursion = (1..6).map { minute ->
            raw("2026-01-01T00:0$minute:00Z", 38.0, 128.0, 10.0)
        }
        val source = listOf(raw("2026-01-01T00:00:00Z", 37.0, 127.0, 10.0)) +
            excursion +
            listOf(
                raw("2026-01-01T00:07:00Z", 37.0001, 127.0001, 10.0),
                raw("2026-01-01T01:00:00Z", 37.1, 127.1, 10.0),
            )

        val result = RawSignalProcessor.process(source)

        assertTrue(result.points.size > 2)
    }

    @Test
    fun keepsSustainedTravelThatNeverRejoinsItsStartingPoint() {
        val result = RawSignalProcessor.process(
            listOf(
                raw("2026-01-01T00:00:00Z", 37.0, 127.0, 10.0),
                raw("2026-01-01T00:20:00Z", 37.3, 127.3, 10.0),
                raw("2026-01-01T00:40:00Z", 37.6, 127.6, 10.0),
                raw("2026-01-01T01:00:00Z", 37.9, 127.9, 10.0),
            ),
        )

        assertEquals(4, result.points.size)
        assertEquals(0, result.noiseRejectedCount)
    }

    private fun raw(
        timestamp: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
    ) = RawSignalPoint(
        point = GeoPoint(Instant.parse(timestamp), latitude, longitude),
        accuracyMeters = accuracyMeters,
    )
}
