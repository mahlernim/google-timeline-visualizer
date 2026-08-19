package dev.mahlernim.timelinevisualizer.export

import android.media.MediaCodecInfo
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoFormatPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure half of the capability check. The JVM test runtime exposes no real codecs, so
 * every case here is built from synthetic [EncoderProfile]s that mirror shapes seen on real devices.
 */
class VideoEncoderSupportTest {
    @Test
    fun acceptsSquarePortraitAndLandscapeOnAFullyCapableEncoder() {
        val profiles = listOf(capableProfile())

        listOf(
            VideoFormatPreset.SQUARE_480,
            VideoFormatPreset.SQUARE_1080,
            VideoFormatPreset.PORTRAIT_1080,
            VideoFormatPreset.LANDSCAPE_1080,
            VideoFormatPreset.LANDSCAPE_2160,
        ).forEach { preset ->
            val support = VideoEncoderSupport.select(preset.format!!, profiles)
            assertTrue("$preset was rejected: $support", support is EncoderSupport.Supported)
        }
    }

    @Test
    fun reportsSizeWhenTheEncoderStopsAt1080p() {
        val profiles = listOf(capableProfile(maxWidth = 1920, maxHeight = 1088))

        val support = VideoEncoderSupport.select(VideoFormatPreset.LANDSCAPE_2160.format!!, profiles)

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.SIZE), support)
    }

    @Test
    fun reportsSizeWhenTheEncoderRejectsTheDimensionsDespiteItsAdvertisedRanges() {
        val profiles = listOf(capableProfile(maxFrameRate = { _, _ -> 0.0 }))

        val support = VideoEncoderSupport.select(VideoFormatPreset.PORTRAIT_1080.format!!, profiles)

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.SIZE), support)
    }

    @Test
    fun reportsAlignmentWhenTheDimensionsAreNotAMultipleOfTheCodecStep() {
        val profiles = listOf(capableProfile(widthAlignment = 16, heightAlignment = 16))

        val support = VideoEncoderSupport.select(VideoFormat.custom(1000, 720, 30), profiles)

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.ALIGNMENT), support)
    }

    @Test
    fun reportsFrameRateWhenTheSizeFitsButTheRateDoesNot() {
        val profiles = listOf(capableProfile(maxFrameRate = { _, _ -> 30.0 }))

        val support = VideoEncoderSupport.select(VideoFormat.custom(1920, 1080, 60), profiles)

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.FRAME_RATE), support)
    }

    @Test
    fun reportsBitrateWhenTheFormatAsksForMoreThanTheEncoderAccepts() {
        val profiles = listOf(capableProfile(maxBitrate = 20_000_000))

        val support = VideoEncoderSupport.select(VideoFormatPreset.LANDSCAPE_2160.format!!, profiles)

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.BITRATE), support)
    }

    @Test
    fun reportsColorFormatWhenNoBufferLayoutIsShared() {
        val profiles = listOf(capableProfile(colorFormats = setOf(SURFACE_COLOR_FORMAT)))

        val support = VideoEncoderSupport.select(VideoFormatPreset.SQUARE_480.format!!, profiles)

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.COLOR_FORMAT), support)
    }

    @Test
    fun reportsNoEncoderWhenTheDeviceListsNone() {
        val support = VideoEncoderSupport.select(VideoFormatPreset.SQUARE_480.format!!, emptyList())

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.NO_ENCODER), support)
    }

    @Test
    fun reportsTheFurthestAlongFailureAcrossEncoders() {
        val profiles = listOf(
            capableProfile(name = "too-small", maxWidth = 640, maxHeight = 640),
            capableProfile(name = "too-slow", maxFrameRate = { _, _ -> 24.0 }),
        )

        val support = VideoEncoderSupport.select(VideoFormat.custom(1920, 1080, 60), profiles)

        assertEquals(EncoderSupport.Unsupported(EncoderSupport.Reason.FRAME_RATE), support)
    }

    @Test
    fun prefersAHardwareEncoderOverASoftwareOne() {
        val profiles = listOf(
            capableProfile(name = "software", hardwareAccelerated = false),
            capableProfile(name = "hardware", hardwareAccelerated = true),
        )

        val support = VideoEncoderSupport.select(VideoFormatPreset.SQUARE_720.format!!, profiles)

        assertEquals(EncoderSupport.Supported("hardware", PLANAR_COLOR_FORMAT), support)
    }

    @Test
    fun fallsBackToTheSemiPlanarLayoutWhenPlanarIsUnavailable() {
        val profiles = listOf(capableProfile(colorFormats = setOf(SEMI_PLANAR_COLOR_FORMAT)))

        val support = VideoEncoderSupport.select(VideoFormatPreset.SQUARE_720.format!!, profiles)

        assertEquals(EncoderSupport.Supported("encoder", SEMI_PLANAR_COLOR_FORMAT), support)
    }

    private fun capableProfile(
        name: String = "encoder",
        hardwareAccelerated: Boolean = true,
        maxWidth: Int = 3840,
        maxHeight: Int = 3840,
        widthAlignment: Int = 2,
        heightAlignment: Int = 2,
        maxBitrate: Int = 100_000_000,
        maxFrameRate: (Int, Int) -> Double = { _, _ -> 60.0 },
        colorFormats: Set<Int> = setOf(PLANAR_COLOR_FORMAT, SEMI_PLANAR_COLOR_FORMAT),
    ) = EncoderProfile(
        name = name,
        hardwareAccelerated = hardwareAccelerated,
        widthRange = 176..maxWidth,
        heightRange = 144..maxHeight,
        widthAlignment = widthAlignment,
        heightAlignment = heightAlignment,
        bitrateRange = 64_000..maxBitrate,
        maxFrameRateFor = maxFrameRate,
        colorFormats = colorFormats,
    )

    private companion object {
        const val PLANAR_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        const val SEMI_PLANAR_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        const val SURFACE_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
    }
}
