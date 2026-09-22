package dev.mahlernim.timelinevisualizer.export

import dev.mahlernim.timelinevisualizer.render.VideoQuality
import dev.mahlernim.timelinevisualizer.render.FrameRate
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ExporterTest {
    @Test
    fun recapKeepsExactRequestedFrameCountsAndAReadableHold() {
        for (seconds in listOf(10, 15, 20, 30, 300)) {
            for (fps in listOf(FrameRate.of(15), FrameRate.of(30), FrameRate.of(30_000, 1_001), FrameRate.of(60))) {
                val (journey, outro) = Mp4Exporter.videoFrameCounts(seconds, fps)
                assertEquals(fps.frameCount(seconds), journey + outro)
                val recapFrames = (journey until journey + outro).count {
                    Mp4Exporter.animationFrame(it, journey, fps, outro).recapProgress == 1f
                }
                assertTrue("$seconds seconds at $fps", recapFrames / fps.value >= 3.15)
                val finalFrame = Mp4Exporter.animationFrame(journey + outro - 1, journey, fps, outro)
                assertEquals(1f, finalFrame.recapProgress, 0.001f)
                assertEquals(1f, finalFrame.outroProgress, 0.001f)
            }
        }
    }

    @Test
    fun exportAndPreviewUseTheSameEndingPhases() {
        val fps = FrameRate.of(30)
        val (journey, outro) = Mp4Exporter.videoFrameCounts(15, fps)
        for (offset in 0 until outro) {
            val export = Mp4Exporter.animationFrame(journey + offset, journey, fps, outro)
            val preview = TimelineAnimation.frameAtElapsedSeconds((journey + offset) / 30f, 15)
            assertEquals(preview.outroProgress, export.outroProgress, 0.0001f)
            assertEquals(preview.recapProgress, export.recapProgress, 0.0001f)
        }
    }
    @Test
    fun overviewImageMatchesTheSelectedAspect() {
        assertEquals(1080, Mp4Exporter.overviewWidth(VideoQuality.STANDARD))
        assertEquals(1080, Mp4Exporter.overviewHeight(VideoQuality.STANDARD))

        assertEquals(608, Mp4Exporter.overviewWidth(VideoQuality.PORTRAIT))
        assertEquals(1080, Mp4Exporter.overviewHeight(VideoQuality.PORTRAIT))

        assertEquals(1080, Mp4Exporter.overviewWidth(VideoQuality.LANDSCAPE))
        assertEquals(608, Mp4Exporter.overviewHeight(VideoQuality.LANDSCAPE))
    }
}
