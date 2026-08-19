package dev.mahlernim.timelinevisualizer.export

import android.media.MediaCodecInfo
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoFormatPreset
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame conversion runs concurrently over row bands, so the interesting property is that it
 * produces exactly what a single pass over the whole frame would have.
 */
class Mp4ExporterTest {
    @Test
    fun bandedConversionMatchesASinglePassForBothBufferLayouts() {
        listOf(PLANAR, SEMI_PLANAR).forEach { colorFormat ->
            listOf(64 to 64, 480 to 480, 240 to 426, 1080 to 608).forEach { (width, height) ->
                val pixels = randomPixels(width, height)
                val reference = ByteArray(width * height * 3 / 2)
                Mp4Exporter.argbToYuv420Rows(pixels, reference, width, height, 0 until height, colorFormat)

                val banded = ByteArray(width * height * 3 / 2)
                bands(height).forEach { rows ->
                    Mp4Exporter.argbToYuv420Rows(pixels, banded, width, height, rows, colorFormat)
                }

                assertArrayEquals("${width}x$height format $colorFormat", reference, banded)
            }
        }
    }

    @Test
    fun theRealBandLayoutAlsoMatchesASinglePass() {
        val width = 320
        val height = 576
        val pixels = randomPixels(width, height)
        val reference = ByteArray(width * height * 3 / 2)
        Mp4Exporter.argbToYuv420Rows(pixels, reference, width, height, 0 until height, PLANAR)

        val banded = ByteArray(width * height * 3 / 2)
        Mp4Exporter.rowBands(height).forEach { rows ->
            Mp4Exporter.argbToYuv420Rows(pixels, banded, width, height, rows, PLANAR)
        }

        assertArrayEquals(reference, banded)
    }

    @Test
    fun everyBandCoversTheFrameExactlyOnceAndStartsOnAnEvenRow() {
        listOf(64, 480, 608, 1080, 1088, 1920, 2160).forEach { height ->
            val bands = Mp4Exporter.rowBands(height)

            assertEquals("height $height", 0, bands.first().first)
            assertEquals("height $height", height - 1, bands.last().last)
            bands.forEach { assertEquals("height $height band $it", 0, it.first % 2) }
            bands.zipWithNext().forEach { (before, after) ->
                assertEquals("height $height", before.last + 1, after.first)
            }
            assertEquals("height $height", height, bands.sumOf { it.count() })
        }
    }

    @Test
    fun theOverviewImageKeepsTheExportAspectWithA1080LongEdge() {
        val square = VideoFormatPreset.SQUARE_480.format!!
        assertEquals(1080, Mp4Exporter.overviewWidth(square))
        assertEquals(1080, Mp4Exporter.overviewHeight(square))

        val portrait = VideoFormatPreset.PORTRAIT_1080.format!!
        assertEquals(1080, Mp4Exporter.overviewHeight(portrait))
        assertEquals(607, Mp4Exporter.overviewWidth(portrait))

        val landscape = VideoFormatPreset.LANDSCAPE_1080.format!!
        assertEquals(1080, Mp4Exporter.overviewWidth(landscape))
        assertEquals(607, Mp4Exporter.overviewHeight(landscape))

        val ultra = VideoFormatPreset.LANDSCAPE_2160.format!!
        assertEquals(1080, Mp4Exporter.overviewWidth(ultra))
        assertEquals(607, Mp4Exporter.overviewHeight(ultra))
    }

    @Test
    fun theOverviewNeverCollapsesToNothingForExtremeCustomShapes() {
        val sliver = VideoFormat.custom(VideoFormat.MAX_DIMENSION, VideoFormat.MIN_DIMENSION, 30)

        assertEquals(1080, Mp4Exporter.overviewWidth(sliver))
        assertTrue(Mp4Exporter.overviewHeight(sliver) >= 1)
    }

    private fun randomPixels(width: Int, height: Int): IntArray {
        val random = Random(width * 31 + height)
        return IntArray(width * height) { random.nextInt() }
    }

    /** Deliberately uneven bands, to prove the offsets do not depend on equal-sized chunks. */
    private fun bands(height: Int): List<IntRange> {
        val first = (height / 3).let { if (it % 2 == 0) it else it + 1 }.coerceAtMost(height)
        if (first >= height) return listOf(0 until height)
        return listOf(0 until first, first until height)
    }

    private companion object {
        const val PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        const val SEMI_PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }
}
