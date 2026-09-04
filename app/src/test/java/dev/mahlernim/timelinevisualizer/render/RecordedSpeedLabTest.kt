package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.BuildConfig
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.JourneySemanticEpisode
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordedSpeedLabTest {
    private fun p(lon: Double, seconds: Long) = GeoPoint(Instant.EPOCH.plusSeconds(seconds), 35.0, lon)

    @Test fun everyCameraAndAspectProducesExactlyTheSameRecordedDistance() {
        assumeTrue(BuildConfig.IS_RECORDED_SPEED_LAB)
        val raw = Journey.from(listOf(p(128.0, 0), p(128.05, 600), p(130.0, 86400), p(130.01, 87000)), 2026)
        val j = raw.copy(inferredTransferBeforePointIndices = listOf(2), semanticEpisodes = listOf(
            JourneySemanticEpisode(0.0, raw.cumulativeDistanceKm[1], raw.points[0], raw.points[1]),
        ))
        val expected = JourneyTiming.recorded(j)
        val painter = TimelinePainter()
        for (camera in CameraMovement.entries) for ((w, h) in listOf(480 to 480, 480 to 854, 854 to 480)) {
            val settings = CameraSettings.DEFAULT.copy(cameraMovement = camera,
                localFraming = LocalFraming.CLOSE, longTripCompression = LongTripCompression.STRONGER)
            for (step in 0..40) {
                val progress = step / 40f
                assertEquals("$camera $w x $h at $progress", expected.distanceAt(progress),
                    painter.playbackDistanceForTest(j, progress, w, h, settings), 0.0)
            }
        }
    }

    @Test fun viewportSamplesAndArrivalMinimumsCannotAffectLabTiming() {
        assumeTrue(BuildConfig.IS_RECORDED_SPEED_LAB)
        val j = Journey.from(listOf(p(0.0, 0), p(0.01, 600), p(0.02, 1800)), 2026)
        val expected = JourneyTiming.recorded(j)
        val actual = JourneyTiming.createViewportRelative(j, emptyList(), 99.0,
            distancesKm = doubleArrayOf(), minimumShares = listOf(TimingMinimumShare(0.0, j.totalDistanceKm, 0.99)))
        for (step in 0..20) assertEquals(expected.distanceAt(step / 20f), actual.distanceAt(step / 20f), 0.0)
        val fallback = JourneyTiming.create(j, LongTripCompression.OFF)
        assertEquals(expected.distanceAt(0.4f), fallback.distanceAt(0.4f), 0.0)
    }
}
