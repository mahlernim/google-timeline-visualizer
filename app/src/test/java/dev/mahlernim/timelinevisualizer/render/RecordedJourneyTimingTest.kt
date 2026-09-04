package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.JourneySemanticEpisode
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RecordedJourneyTimingTest {
    private fun p(lon: Double, seconds: Long) = GeoPoint(Instant.EPOCH.plusSeconds(seconds), 0.0, lon)

    @Test fun timeSharesFollowDurationRatherThanDistance() {
        val j = Journey.from(listOf(p(0.0, 0), p(0.01, 600), p(0.11, 1200)), 2026)
        val t = JourneyTiming.recorded(j)
        assertEquals(0.5f, t.progressAtDistance(j.cumulativeDistanceKm[1]), 1e-6f)
    }

    @Test fun stationaryHoursDoNotConsumePlaybackTime() {
        val j = Journey.from(listOf(p(0.0, 0), p(0.01, 600), p(0.01, 86400), p(0.02, 87000)), 2026)
        assertEquals(1200.0, j.recordedMovement.totalSeconds, 1e-8)
        assertEquals(0.5f, JourneyTiming.recorded(j).progressAtDistance(j.cumulativeDistanceKm[1]), 1e-6f)
    }

    @Test fun unsupportedConnectorIsACutAndNeverGetsInventedTravelTime() {
        val raw = Journey.from(listOf(p(0.0, 0), p(0.01, 600), p(1.0, 86400), p(1.01, 87000)), 2026)
        val j = raw.copy(inferredTransferBeforePointIndices = listOf(2))
        val t = JourneyTiming.recorded(j)
        assertEquals(1200.0, j.recordedMovement.totalSeconds, 1e-8)
        assertTrue(t.distanceAt(0.4999f) <= j.cumulativeDistanceKm[1])
        assertEquals(j.cumulativeDistanceKm[2], t.distanceAt(0.5f), 1e-8)
        assertTrue(t.distanceAt(0.5001f) >= j.cumulativeDistanceKm[2])
    }

    @Test fun noRecordedMovementNeverFallsBackToDistanceTiming() {
        for (endSeconds in listOf(0L, 86400L, 1L)) {
            val j = Journey.from(listOf(p(0.0, 0), p(1.0, endSeconds)), 2026)
            assertFalse(j.recordedMovement.hasMovement)
            for (i in 0..10) assertEquals(0.0, JourneyTiming.recorded(j).distanceAt(i / 10f), 0.0)
        }
    }

    @Test fun supportedSemanticActivityCanSpanSparseObservationGaps() {
        val raw = Journey.from(listOf(p(0.0, 0), p(1.0, 7200), p(2.0, 14400)), 2026)
        val j = raw.copy(semanticEpisodes = listOf(JourneySemanticEpisode(0.0, raw.totalDistanceKm, p(0.0, 0), p(2.0, 14400))))
        assertEquals(14400.0, j.recordedMovement.totalSeconds, 1e-8)
        assertEquals(0.5f, JourneyTiming.recorded(j).progressAtDistance(j.totalDistanceKm / 2), 1e-6f)
    }

    @Test fun skippingLeadingAndTrailingUnknownIntervalsKeepsSupportedEndpoints() {
        val j = Journey.from(listOf(p(0.0, 0), p(1.0, 86400), p(1.01, 87000), p(2.0, 172800)), 2026)
        val t = JourneyTiming.recorded(j)
        assertEquals(j.cumulativeDistanceKm[1], t.distanceAt(0f), 1e-8)
        assertEquals(j.cumulativeDistanceKm[2], t.distanceAt(1f), 1e-8)
    }

    @Test fun smoothingIsMonotoneAndPreservesSourceArrivalTimes() {
        val j = Journey.from(listOf(p(0.0, 0), p(0.01, 600), p(0.02, 1800), p(0.05, 2400)), 2026)
        val t = JourneyTiming.recorded(j)
        assertEquals(j.cumulativeDistanceKm[1], t.distanceAt(0.25f), 1e-8)
        assertEquals(j.cumulativeDistanceKm[2], t.distanceAt(0.75f), 1e-8)
        var previous = -1.0
        for (i in 0..1000) {
            val current = t.distanceAt(i / 1000f)
            assertTrue(current.isFinite() && current >= previous)
            previous = current
        }
        assertEquals(j.totalDistanceKm, t.distanceAt(1f), 1e-8)
    }

    @Test fun equalSpeedResamplingDoesNotChangeTiming() {
        val sparse = Journey.from(listOf(p(0.0, 0), p(0.1, 1200)), 2026)
        val dense = Journey.from((0..10).map { p(it * 0.01, it * 120L) }, 2026)
        val a = JourneyTiming.recorded(sparse)
        val b = JourneyTiming.recorded(dense)
        for (i in 0..100) assertEquals(a.distanceAt(i / 100f), b.distanceAt(i / 100f), 1e-6)
    }
}
