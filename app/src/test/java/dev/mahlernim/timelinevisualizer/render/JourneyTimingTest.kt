package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyTimingTest {
    @Test
    fun balancedCompressionReducesLongSegmentsShareWithoutChangingGeometryOrDuration() {
        val journey = Journey.from(
            listOf(
                point(0.0),
                point(0.1),
                point(10.1),
                point(10.2),
            ),
            2026,
        )
        val originalPoints = journey.points.toList()
        val linear = JourneyTiming.create(journey, LongTripCompression.OFF)
        val balanced = JourneyTiming.create(journey, LongTripCompression.BALANCED)
        val longStartKm = journey.cumulativeDistanceKm[1]
        val longEndKm = journey.cumulativeDistanceKm[2]
        val linearShare = progressAtDistance(linear, longEndKm) - progressAtDistance(linear, longStartKm)
        val balancedShare = progressAtDistance(balanced, longEndKm) - progressAtDistance(balanced, longStartKm)

        assertTrue("Balanced compression should reduce the unusually long segment's share", balancedShare < linearShare)
        assertEquals(originalPoints, journey.points)
        assertEquals(0.0, balanced.distanceAt(0f), 1e-9)
        assertEquals(journey.totalDistanceKm, balanced.distanceAt(1f), 1e-6)
        assertEquals(30f, TimelineAnimation.totalDurationSeconds(30), 0f)
    }

    @Test
    fun offPreservesLinearDistanceTimingExactly() {
        val journey = Journey.from(listOf(point(0.0), point(0.1), point(10.1)), 2026)
        val timing = JourneyTiming.create(journey, LongTripCompression.OFF)

        listOf(0f, 0.1f, 0.5f, 0.9f, 1f).forEach { progress ->
            assertEquals(journey.totalDistanceKm * progress, timing.distanceAt(progress), 1e-9)
        }
    }

    @Test
    fun smoothedTimingHasNoSpeedJumpAtSegmentBoundary() {
        val journey = Journey.from(listOf(point(0.0), point(0.1), point(10.1), point(10.2)), 2026)
        val timing = JourneyTiming.create(journey, LongTripCompression.BALANCED)
        val boundaryProgress = progressAtDistance(timing, journey.cumulativeDistanceKm[1])
        val step = 0.00001f
        val speedBefore = (timing.distanceAt(boundaryProgress) - timing.distanceAt(boundaryProgress - step)) / step
        val speedAfter = (timing.distanceAt(boundaryProgress + step) - timing.distanceAt(boundaryProgress)) / step

        assertEquals(speedBefore, speedAfter, maxOf(1.0, speedBefore * 0.02))
    }

    @Test
    fun consecutiveTransferPointsDoNotLengthenTheFlightEpisode() {
        val singleHop = Journey.from(
            listOf(point(0.0), point(0.1), point(10.1), point(10.2)),
            2026,
        )
        val splitHop = Journey.from(
            listOf(point(0.0), point(0.1), point(3.4), point(6.8), point(10.1), point(10.2)),
            2026,
        )
        val singleTiming = JourneyTiming.create(singleHop, LongTripCompression.STRONG)
        val splitTiming = JourneyTiming.create(splitHop, LongTripCompression.STRONG)
        val singleShare = progressAtDistance(singleTiming, singleHop.cumulativeDistanceKm[2]) -
            progressAtDistance(singleTiming, singleHop.cumulativeDistanceKm[1])
        val splitShare = progressAtDistance(splitTiming, splitHop.cumulativeDistanceKm[4]) -
            progressAtDistance(splitTiming, splitHop.cumulativeDistanceKm[1])

        assertEquals(singleShare, splitShare, 0.002f)
    }

    @Test
    fun compressionPresetsUseTheAgreedExponents() {
        assertEquals(
            listOf(1.00, 0.85, 0.75, 0.65),
            LongTripCompression.entries.map(LongTripCompression::exponent),
        )
    }

    private fun progressAtDistance(timing: JourneyTiming, distanceKm: Double): Float {
        var low = 0f
        var high = 1f
        repeat(40) {
            val middle = (low + high) / 2f
            if (timing.distanceAt(middle) < distanceKm) low = middle else high = middle
        }
        return (low + high) / 2f
    }

    private fun point(longitude: Double) = GeoPoint(
        Instant.parse("2026-01-01T00:00:00Z"),
        0.0,
        longitude,
    )
}
