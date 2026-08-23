package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun customFormatSizingMatchesEachAspectRatio() {
        val shortEdge = 1200

        val square = CustomVideoFormat(VideoAspectRatio.SQUARE, shortEdge, 30).format
        assertEquals(shortEdge, square.width)
        assertEquals(shortEdge, square.height)

        val portrait = CustomVideoFormat(VideoAspectRatio.PORTRAIT, shortEdge, 30).format
        assertEquals(shortEdge, portrait.width)
        assertTrue(portrait.height > portrait.width)
        assertEquals(0, portrait.height % 2)

        val landscape = CustomVideoFormat(VideoAspectRatio.LANDSCAPE, shortEdge, 30).format
        assertEquals(shortEdge, landscape.height)
        assertTrue(landscape.width > landscape.height)
        assertEquals(0, landscape.width % 2)

        assertEquals(portrait.width, landscape.height)
        assertEquals(portrait.height, landscape.width)
    }

    @Test
    fun customFormatBitrateStaysWithinBounds() {
        val smallest = CustomVideoFormat(
            VideoAspectRatio.SQUARE,
            CustomVideoFormat.MIN_SHORT_EDGE,
            CustomVideoFormat.MIN_FRAME_RATE,
        ).format
        val largest = CustomVideoFormat(
            VideoAspectRatio.LANDSCAPE,
            CustomVideoFormat.MAX_SHORT_EDGE,
            CustomVideoFormat.MAX_FRAME_RATE,
        ).format

        assertTrue(smallest.bitrate > 0)
        assertTrue(largest.bitrate >= smallest.bitrate)
    }

    @Test
    fun parseShortEdgeAndFrameRateRejectOutOfRangeAndNonNumericInput() {
        assertEquals(CustomVideoFormat.MIN_SHORT_EDGE, CustomVideoFormat.parseShortEdge("${CustomVideoFormat.MIN_SHORT_EDGE}"))
        assertEquals(CustomVideoFormat.MAX_SHORT_EDGE, CustomVideoFormat.parseShortEdge("${CustomVideoFormat.MAX_SHORT_EDGE}"))
        assertNull(CustomVideoFormat.parseShortEdge("${CustomVideoFormat.MIN_SHORT_EDGE - 1}"))
        assertNull(CustomVideoFormat.parseShortEdge("${CustomVideoFormat.MAX_SHORT_EDGE + 1}"))
        assertNull(CustomVideoFormat.parseShortEdge("abc"))
        assertNull(CustomVideoFormat.parseShortEdge(""))
        assertNull(CustomVideoFormat.parseShortEdge(null))

        assertEquals(CustomVideoFormat.MIN_FRAME_RATE, CustomVideoFormat.parseFrameRate("${CustomVideoFormat.MIN_FRAME_RATE}"))
        assertEquals(CustomVideoFormat.MAX_FRAME_RATE, CustomVideoFormat.parseFrameRate("${CustomVideoFormat.MAX_FRAME_RATE}"))
        assertNull(CustomVideoFormat.parseFrameRate("${CustomVideoFormat.MAX_FRAME_RATE + 1}"))
    }
}
