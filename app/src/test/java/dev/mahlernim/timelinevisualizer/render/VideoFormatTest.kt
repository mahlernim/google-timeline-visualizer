package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFormatTest {
    @Test
    fun theDefaultPresetIsTheSquareOutputTheAppHasAlwaysProduced() {
        val format = VideoFormatPreset.DEFAULT.format!!

        assertEquals(480, format.width)
        assertEquals(480, format.height)
        assertEquals(24, format.frameRate)
        assertEquals(2_500_000, format.bitrate)
    }

    @Test
    fun presetAspectsMatchTheirNames() {
        assertEquals(1f, VideoFormatPreset.SQUARE_1080.format!!.aspect, 1e-6f)
        assertTrue(VideoFormatPreset.PORTRAIT_1080.format!!.aspect < 1f)
        assertTrue(VideoFormatPreset.LANDSCAPE_1080.format!!.aspect > 1f)
        assertEquals(
            VideoFormatPreset.PORTRAIT_1080.format!!.aspect,
            1f / VideoFormatPreset.LANDSCAPE_1080.format!!.aspect,
            1e-6f,
        )
    }

    @Test
    fun everyPresetExceptCustomCarriesAFormat() {
        VideoFormatPreset.values().forEach { preset ->
            assertEquals(preset.name, preset == VideoFormatPreset.CUSTOM, preset.isCustom)
        }
    }

    @Test
    fun mapsTheVideoQualityNamesWrittenByEarlierVersions() {
        assertEquals(VideoFormatPreset.SQUARE_480, VideoFormatPreset.fromStoredName("STANDARD"))
        assertEquals(VideoFormatPreset.SQUARE_720, VideoFormatPreset.fromStoredName("HIGH"))
        assertEquals(VideoFormatPreset.SQUARE_1080, VideoFormatPreset.fromStoredName("ULTRA"))
    }

    @Test
    fun readsItsOwnNamesBack() {
        VideoFormatPreset.values().forEach { preset ->
            assertEquals(preset, VideoFormatPreset.fromStoredName(preset.name))
        }
    }

    @Test
    fun refusesToGuessAtAnUnknownName() {
        assertNull(VideoFormatPreset.fromStoredName(null))
        assertNull(VideoFormatPreset.fromStoredName(""))
        assertNull(VideoFormatPreset.fromStoredName("PANORAMA"))
    }

    @Test
    fun boundsRejectSizesAndRatesTheAppDoesNotOffer() {
        assertTrue(VideoFormat.isWithinBounds(1080, 1920, 30))
        assertFalse(VideoFormat.isWithinBounds(VideoFormat.MIN_DIMENSION - 1, 1080, 30))
        assertFalse(VideoFormat.isWithinBounds(1080, VideoFormat.MAX_DIMENSION + 1, 30))
        assertFalse(VideoFormat.isWithinBounds(1080, 1920, 25))
        assertFalse(VideoFormat.isWithinBounds(0, 0, 0))
    }

    @Test
    fun customBitratesStayInsideTheEncodableRange() {
        val smallest = VideoFormat.custom(VideoFormat.MIN_DIMENSION, VideoFormat.MIN_DIMENSION, 24)
        val largest = VideoFormat.custom(VideoFormat.MAX_DIMENSION, VideoFormat.MAX_DIMENSION, 60)

        assertTrue(smallest.bitrate >= VideoFormat.MIN_BITRATE)
        assertTrue(largest.bitrate <= VideoFormat.MAX_BITRATE)
    }

    @Test
    fun aCustomSquareLandsCloseToTheMatchingPreset() {
        val preset = VideoFormatPreset.SQUARE_720.format!!
        val custom = VideoFormat.custom(720, 720, 24)

        val ratio = custom.bitrate.toDouble() / preset.bitrate
        assertTrue("custom ${custom.bitrate} vs preset ${preset.bitrate}", ratio in 0.8..1.25)
    }

    @Test
    fun higherFrameRatesAskForProportionallyMoreBits() {
        assertEquals(
            2.0,
            VideoFormat.bitrateFor(1280, 720, 60).toDouble() / VideoFormat.bitrateFor(1280, 720, 30),
            0.01,
        )
    }

    @Test
    fun edgeHelpersDescribeTheFrameShape() {
        val portrait = VideoFormatPreset.PORTRAIT_1080.format!!

        assertEquals(1080, portrait.shortEdge)
        assertEquals(1920, portrait.longEdge)
    }
}
