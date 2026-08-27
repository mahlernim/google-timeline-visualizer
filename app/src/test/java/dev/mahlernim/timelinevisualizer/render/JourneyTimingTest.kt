package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyTimingTest {
    @Test
    fun closeFramingReceivesMoreTimeForTheSameGroundDistance() {
        val journey = Journey.from(listOf(point(0.0), point(1.0), point(2.0)), 2026)
        val viewports = List(101) { index ->
            viewport(spanY = if (index <= 50) 0.01 else 0.10)
        }

        val timing = JourneyTiming.createViewportRelative(journey, viewports, aspect = 1.0)
        val halfwayProgress = progressAtDistance(timing, journey.totalDistanceKm / 2.0)

        assertTrue("Close framing should make the first half calmer", halfwayProgress > 0.75f)
        assertEquals(0.0, timing.distanceAt(0f), 1e-9)
        assertEquals(journey.totalDistanceKm, timing.distanceAt(1f), 1e-6)
    }

    @Test
    fun equalElapsedIntervalsHaveSimilarViewportRelativeGroundMovement() {
        val journey = Journey.from(listOf(point(0.0), point(1.0), point(2.0)), 2026)
        val closeSpan = 0.01
        val wideSpan = 0.10
        val timing = JourneyTiming.createViewportRelative(
            journey,
            List(201) { index -> viewport(if (index <= 100) closeSpan else wideSpan) },
            aspect = 1.0,
        )
        val halfwayProgress = progressAtDistance(timing, journey.totalDistanceKm / 2.0)
        val closeVisualSpeed = (journey.totalDistanceKm / 2.0 / closeSpan) / halfwayProgress
        val wideVisualSpeed = (journey.totalDistanceKm / 2.0 / wideSpan) / (1.0 - halfwayProgress)

        assertEquals(closeVisualSpeed, wideVisualSpeed, closeVisualSpeed * 0.08)
        assertTrue(halfwayProgress > 0.85f)
    }

    @Test
    fun legacyCompressionAndDetectionFieldsDoNotChangeAutomaticTiming() {
        val journey = Journey.from(listOf(point(0.0), point(0.1), point(10.1)), 2026)
        val natural = JourneyTiming.create(journey, LongTripCompression.OFF, TripDetection.SENSITIVE)
        val strongest = JourneyTiming.create(journey, LongTripCompression.STRONGER, TripDetection.CONSERVATIVE)

        (0..20).forEach { step ->
            val progress = step / 20f
            assertEquals(natural.distanceAt(progress), strongest.distanceAt(progress), 1e-9)
        }
    }

    @Test
    fun sparseAndDenseGeometryProduceStableTiming() {
        val sparse = Journey.from(listOf(point(0.0), point(12.0)), 2026)
        val dense = Journey.from((0..120).map { point(it / 10.0) }, 2026)
        val viewports = List(121) { index -> viewport(0.02 + index / 120.0 * 0.18) }
        val sparseTiming = JourneyTiming.createViewportRelative(sparse, viewports, 1.0)
        val denseTiming = JourneyTiming.createViewportRelative(dense, viewports, 1.0)

        (0..20).forEach { step ->
            val progress = step / 20f
            val sparseFraction = sparseTiming.distanceAt(progress) / sparse.totalDistanceKm
            val denseFraction = denseTiming.distanceAt(progress) / dense.totalDistanceKm
            assertEquals(sparseFraction, denseFraction, 0.002)
        }
    }

    @Test
    fun distanceIsFiniteMonotonicAndCompletesExactly() {
        val journey = Journey.from(
            listOf(point(179.8), point(-179.8), point(-179.0), point(-170.0)),
            2026,
        )
        val viewports = List(81) { index -> viewport(if (index == 40) 1e-12 else 0.04) }
        val timing = JourneyTiming.createViewportRelative(journey, viewports, 1.5)
        var previous = -1.0

        (0..1_000).forEach { step ->
            val distance = timing.distanceAt(step / 1_000f)
            assertTrue(distance.isFinite())
            assertTrue("Distance must never move backwards", distance + 1e-8 >= previous)
            previous = distance
        }
        assertEquals(0.0, timing.distanceAt(-1f), 0.0)
        assertEquals(journey.totalDistanceKm, timing.distanceAt(2f), 1e-6)
    }

    @Test
    fun fixedDistanceSamplingRejectsAOneSampleViewportSpike() {
        val journey = Journey.from(listOf(point(0.0), point(20.0)), 2026)
        val steady = List(101) { viewport(0.05) }
        val spike = steady.toMutableList().also { it[50] = viewport(0.000001) }
        val steadyTiming = JourneyTiming.createViewportRelative(journey, steady, 1.0)
        val spikeTiming = JourneyTiming.createViewportRelative(journey, spike, 1.0)

        val steadyMiddle = progressAtDistance(steadyTiming, journey.totalDistanceKm / 2.0)
        val spikeMiddle = progressAtDistance(spikeTiming, journey.totalDistanceKm / 2.0)
        assertEquals(steadyMiddle, spikeMiddle, 0.03f)
    }

    @Test
    fun minimumEpisodeShareReallocatesTimeWithoutHoldingDistance() {
        val journey = Journey.from(listOf(point(0.0), point(1.0), point(2.0)), 2026)
        val total = journey.totalDistanceKm
        val episodeStart = total * 0.50
        val episodeEnd = total * 0.501
        val distances = doubleArrayOf(0.0, episodeStart, episodeEnd, total)
        val timing = JourneyTiming.createViewportRelative(
            journey = journey,
            viewports = List(distances.size) { viewport(0.08) },
            aspect = 1.0,
            distancesKm = distances,
            minimumShares = listOf(TimingMinimumShare(episodeStart, episodeEnd, 0.03)),
        )

        val startProgress = timing.progressAtDistance(episodeStart)
        val endProgress = timing.progressAtDistance(episodeEnd)
        val middleDistance = timing.distanceAt((startProgress + endProgress) / 2f)

        assertTrue(endProgress - startProgress >= 0.029f)
        assertTrue("Episode timing must keep moving", middleDistance > episodeStart)
        assertTrue("Episode timing must not finish early", middleDistance < episodeEnd)
        assertEquals(0.0, timing.distanceAt(0f), 0.0)
        assertEquals(total, timing.distanceAt(1f), 1e-6)
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

    private fun viewport(spanY: Double) = Viewport(
        minX = -spanY / 2,
        maxX = spanY / 2,
        minY = 0.5 - spanY / 2,
        maxY = 0.5 + spanY / 2,
        zoom = 8,
    )

    private fun point(longitude: Double) = GeoPoint(
        Instant.parse("2026-01-01T00:00:00Z"),
        0.0,
        longitude,
    )
}
