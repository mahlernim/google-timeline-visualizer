package dev.mahlernim.timelinevisualizer.export

import android.media.MediaCodecInfo
import dev.mahlernim.timelinevisualizer.render.CustomVideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import dev.mahlernim.timelinevisualizer.render.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class VideoEncoderSupportTest {
    @Test
    fun acceptsSquarePortraitAndLandscapeWhenTheEncoderSupportsThem() {
        val profile = profile()

        VideoQuality.values().forEach { quality ->
            assertTrue(
                quality.name,
                VideoEncoderSupport.select(quality.format, listOf(profile)) is EncoderSupport.Supported,
            )
        }
    }

    @Test
    fun reportsUnsupportedFrameRateWithoutSubstitution() {
        val profile = profile(maxFrameRate = 24.0)

        val result = VideoEncoderSupport.select(VideoQuality.PORTRAIT.format, listOf(profile))

        assertEquals(
            EncoderSupport.Unsupported(EncoderSupport.Reason.FRAME_RATE),
            result,
        )
    }

    @Test
    fun rejectsMissingColorLayoutAndEmptyEncoderLists() {
        val missingColor = VideoEncoderSupport.select(
            VideoQuality.LANDSCAPE.format,
            listOf(profile(colorFormats = emptySet())),
        )

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.COLOR_FORMAT), missingColor)
        assertEquals(
            EncoderSupport.Unsupported(EncoderSupport.Reason.NO_ENCODER),
            VideoEncoderSupport.select(VideoQuality.STANDARD.format, emptyList()),
        )
    }

    @Test
    fun acceptsCustomFormatShapedLikeAPresetWhenTheEncoderSupportsIt() {
        val profile = profile()
        val custom = CustomVideoFormat(VideoAspectRatio.LANDSCAPE, VideoResolution.HIGH.shortEdge, 30).format

        assertTrue(VideoEncoderSupport.select(custom, listOf(profile)) is EncoderSupport.Supported)
    }

    @Test
    fun presetsAreNeverRisky() {
        VideoQuality.values().forEach { quality ->
            assertFalse(quality.name, VideoEncoderSupport.isRisky(quality.format))
        }
    }

    @Test
    fun formatsBeyondTheLargestPresetAreRisky() {
        val higherResolution = CustomVideoFormat(VideoAspectRatio.LANDSCAPE, CustomVideoFormat.MAX_SHORT_EDGE, 30)
        val higherFrameRate = CustomVideoFormat(VideoAspectRatio.SQUARE, VideoResolution.STANDARD.shortEdge, 60)

        assertTrue(VideoEncoderSupport.isRisky(higherResolution.format))
        assertTrue(VideoEncoderSupport.isRisky(higherFrameRate.format))
    }

    private fun profile(
        maxFrameRate: Double = 60.0,
        colorFormats: Set<Int> = setOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar),
    ) = EncoderProfile(
        name = "test.encoder",
        hardwareAccelerated = true,
        widthRange = 240..3840,
        heightRange = 240..3840,
        widthAlignment = 2,
        heightAlignment = 2,
        bitrateRange = 1_000_000..20_000_000,
        maxFrameRateFor = { _, _ -> maxFrameRate },
        colorFormats = colorFormats,
    )
}
