package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.google.gson.JsonParser

class VideoQualityTest {
    @Test
    fun zeroAndInvalidFrameRatesReturnNull() {
        listOf("0", "00", "0.0", "0.00", "0.000", "", "-1", ".", "99999999999999999999").forEach {
            assertEquals(it, null, FrameRate.parse(it))
            assertEquals(it, null, ExportFormatSettings.parseFrameRate(it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameRateInvariantStillRejectsZero() {
        FrameRate.of(0)
    }

    @Test
    fun dimensionsMatchSharedPlatformFixture() {
        val root = listOf(File("test-fixtures"), File("../test-fixtures")).first(File::isDirectory)
        val formats = JsonParser.parseString(File(root, "platform-parity-expected.json").readText())
            .asJsonObject.getAsJsonObject("videoDimensions")
        formats.entrySet().forEach { (edge, aspects) ->
            aspects.asJsonObject.entrySet().forEach { (aspect, dimensions) ->
                val format = ExportFormatSettings(edge.toInt(), 30).format(VideoAspectRatio.valueOf(aspect.uppercase()))
                assertEquals(dimensions.asJsonArray[0].asInt, format.width)
                assertEquals(dimensions.asJsonArray[1].asInt, format.height)
            }
        }
    }

    @Test
    fun squareDefaultsRemainCompatible() {
        assertEquals(listOf(480, 720, 1080), VideoQuality.values().take(3).map(VideoQuality::width))
        assertTrue(VideoQuality.values().take(3).all { it.width == it.height && it.frameRate == 24 })
        assertEquals(VideoQuality.STANDARD, CameraSettings.DEFAULT.videoQuality)
        assertEquals(FrameRate.of(30), CameraSettings.DEFAULT.activeVideoFormat.frameRate)
    }

    @Test
    fun exportResolutionsProduceEvenDimensionsForEveryAspect() {
        ExportResolution.entries.forEach { resolution ->
            VideoAspectRatio.entries.forEach { aspect ->
                val format = ExportFormatSettings(resolution.shortEdge, 30).format(aspect)
                assertEquals(0, format.width % 2)
                assertEquals(0, format.height % 2)
                assertEquals(resolution.shortEdge, minOf(format.width, format.height))
            }
        }
        assertEquals(3840, ExportFormatSettings(2160, 60).format(VideoAspectRatio.LANDSCAPE).width)
        assertEquals(40_000_000, ExportFormatSettings(2160, 60).format(VideoAspectRatio.LANDSCAPE).bitrate)
        assertEquals(60_000_000, ExportFormatSettings(2160, 90).format(VideoAspectRatio.LANDSCAPE).bitrate)
        assertEquals(80_000_000, ExportFormatSettings(2160, 120).format(VideoAspectRatio.LANDSCAPE).bitrate)
        assertEquals(160_000_000, ExportFormatSettings(2160, 240).format(VideoAspectRatio.LANDSCAPE).bitrate)
    }

    @Test
    fun customValuesAreBounded() {
        assertEquals(480, ExportFormatSettings.parseShortEdge("480"))
        assertEquals(2160, ExportFormatSettings.parseShortEdge("2160"))
        assertEquals(null, ExportFormatSettings.parseShortEdge("479"))
        assertEquals(FrameRate.of(15), ExportFormatSettings.parseFrameRate("15"))
        assertEquals(FrameRate.of(30_000, 1_001), ExportFormatSettings.parseFrameRate("29.97"))
        assertEquals("29.97", ExportFormatSettings.parseFrameRate("29.97")?.displayValue)
        assertEquals(FrameRate.of(240), ExportFormatSettings.parseFrameRate("240"))
        assertEquals(null, ExportFormatSettings.parseFrameRate("240.001"))
        assertEquals(null, ExportFormatSettings.parseFrameRate("241"))
        assertEquals(null, ExportFormatSettings.parseFrameRate("29.9700"))
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
