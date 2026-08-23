package dev.mahlernim.timelinevisualizer.export

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.TimelineFrame
import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Mp4ExporterTileCoverageTest {
    @Test
    fun selectedDurationIncludesTheOutroFrames() {
        val (journeyFrames, outroFrames) = Mp4Exporter.videoFrameCounts(90, 30)

        assertEquals(2_655, journeyFrames)
        assertEquals(45, outroFrames)
        assertEquals(2_700, journeyFrames + outroFrames)
    }

    @Test
    fun preparationIncludesEveryVideoFrameAndTheOverview() {
        val journey = Journey.from(
            listOf(
                point(37.55, 126.95),
                point(35.68, 139.76),
                point(34.69, 135.50),
            ),
            2025,
        )
        val painter = TimelinePainter()
        val journeyFrames = 9
        val outroFrames = 4
        val fps = 3
        val settings = CameraSettings.DEFAULT
        val expected = buildSet {
            for (frame in 0 until journeyFrames + outroFrames) {
                addAll(
                    painter.requiredTiles(
                        painter.viewport(
                            journey,
                            Mp4Exporter.animationFrame(frame, journeyFrames, fps),
                            VIDEO_WIDTH,
                            VIDEO_HEIGHT,
                            settings,
                        ),
                    ).map { it.id },
                )
            }
            addAll(
                painter.requiredTiles(
                    painter.viewport(
                        journey,
                        TimelineFrame(1f, 1f),
                        OVERVIEW_WIDTH,
                        OVERVIEW_HEIGHT,
                        settings,
                    ),
                ).map { it.id },
            )
        }

        val actual = Mp4Exporter.requiredTilesForExport(
            painter,
            journey,
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            journeyFrames,
            outroFrames,
            fps,
            OVERVIEW_WIDTH,
            OVERVIEW_HEIGHT,
            settings,
        )

        assertEquals(expected, actual)
    }

    private fun point(latitude: Double, longitude: Double) = GeoPoint(
        Instant.parse("2025-06-01T00:00:00Z"),
        latitude,
        longitude,
    )

    companion object {
        private const val VIDEO_WIDTH = 480
        private const val VIDEO_HEIGHT = 480
        private const val OVERVIEW_WIDTH = 1080
        private const val OVERVIEW_HEIGHT = 1080
    }
}
