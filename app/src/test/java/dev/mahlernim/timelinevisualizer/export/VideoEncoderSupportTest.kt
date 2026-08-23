package dev.mahlernim.timelinevisualizer.export

import android.media.MediaCodecInfo
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class VideoEncoderSupportTest {
    @Test
    fun acceptsSquarePortraitAndLandscapeWhenTheEncoderSupportsThem() {
        val profile = profile()

        VideoQuality.values().forEach { format ->
            assertTrue(format.name, VideoEncoderSupport.select(format, listOf(profile)) is EncoderSupport.Supported)
        }
    }

    @Test
    fun reportsUnsupportedFrameRateWithoutSubstitution() {
        val profile = profile(maxFrameRate = 24.0)

        val result = VideoEncoderSupport.select(VideoQuality.PORTRAIT, listOf(profile))

        assertEquals(
            EncoderSupport.Unsupported(EncoderSupport.Reason.FRAME_RATE),
            result,
        )
    }

    @Test
    fun rejectsMissingColorLayoutAndEmptyEncoderLists() {
        val missingColor = VideoEncoderSupport.select(
            VideoQuality.LANDSCAPE,
            listOf(profile(colorFormats = emptySet())),
        )

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.COLOR_FORMAT), missingColor)
        assertEquals(
            EncoderSupport.Unsupported(EncoderSupport.Reason.NO_ENCODER),
            VideoEncoderSupport.select(VideoQuality.STANDARD, emptyList()),
        )
    }

    @Test
    fun retriesTheNextCompatibleEncoderWhenThePreferredOneFails() {
        val candidates = listOf(
            EncoderSupport.Supported("hardware.encoder", MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar),
            EncoderSupport.Supported("software.encoder", MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar),
        )

        val selected = VideoEncoderSupport.firstUsable(candidates) { candidate ->
            if (candidate.name == "hardware.encoder") error("configuration failed")
            "configured"
        }

        assertEquals("configured", selected?.first)
        assertEquals("software.encoder", selected?.second?.name)
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
