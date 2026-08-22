package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityTest {
    @Test
    fun squareDefaultsRemainCompatible() {
        assertEquals(listOf(480, 720, 1080), VideoQuality.values().take(3).map(VideoQuality::width))
        assertTrue(VideoQuality.values().take(3).all { it.width == it.height && it.frameRate == 24 })
        assertEquals(VideoQuality.STANDARD, CameraSettings.DEFAULT.videoQuality)
    }

    @Test
    fun socialPresetsHaveReciprocalAspectsAndEvenDimensions() {
        val portrait = VideoQuality.PORTRAIT
        val landscape = VideoQuality.LANDSCAPE

        assertEquals(portrait.aspectRatio, 1f / landscape.aspectRatio, 0.0001f)
        listOf(portrait, landscape).forEach { format ->
            assertEquals(0, format.width % 2)
            assertEquals(0, format.height % 2)
            assertEquals(30, format.frameRate)
        }
    }

    @Test
    fun everyAspectSupportsEveryResolutionWithoutChangingTheOtherChoice() {
        VideoAspectRatio.entries.forEach { aspect ->
            VideoResolution.entries.forEach { resolution ->
                val format = VideoQuality.of(aspect, resolution)
                assertEquals(aspect, format.aspectRatioOption)
                assertEquals(resolution, format.resolution)
                assertEquals(resolution, format.withAspectRatio(aspect).resolution)
                assertEquals(aspect, format.withResolution(resolution).aspectRatioOption)
            }
        }
    }
}
