package dev.mahlernim.timelinevisualizer.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mahlernim.timelinevisualizer.render.CustomVideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoFormatDeviceTest {
    @Test
    fun theDeviceCanEvaluateAndConfigureEveryReportedPreset() {
        val profiles = VideoEncoderSupport.deviceProfiles()
        assertTrue("The device exposed no H.264 encoder", profiles.isNotEmpty())

        VideoQuality.values().forEach { preset ->
            val support = VideoEncoderSupport.select(preset.format, profiles)
            Log.i(TAG, "${preset.name}: $support")
            if (support is EncoderSupport.Supported) configure(preset.format, support)
        }

        assertTrue(
            "The device cannot encode the default square format",
            VideoEncoderSupport.select(VideoQuality.STANDARD.format, profiles) is EncoderSupport.Supported,
        )
    }

    @Test
    fun theDeviceCanEvaluateAndConfigureLargeCustomFormatsWhenSupported() {
        val profiles = VideoEncoderSupport.deviceProfiles()
        assertTrue("The device exposed no H.264 encoder", profiles.isNotEmpty())

        val customFormats = listOf(
            CustomVideoFormat(VideoAspectRatio.LANDSCAPE, CustomVideoFormat.MAX_SHORT_EDGE, CustomVideoFormat.MAX_FRAME_RATE),
            CustomVideoFormat(VideoAspectRatio.SQUARE, CustomVideoFormat.MAX_SHORT_EDGE, CustomVideoFormat.DEFAULT_FRAME_RATE),
        ).map(CustomVideoFormat::format)

        customFormats.forEach { format ->
            val support = VideoEncoderSupport.select(format, profiles)
            Log.i(TAG, "${format.width}x${format.height}@${format.frameRate}: $support")
            if (support is EncoderSupport.Supported) configure(format, support)
        }
    }

    private fun configure(format: VideoFormat, support: EncoderSupport.Supported) {
        val mediaFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            format.width,
            format.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, support.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, format.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(support.name)
        try {
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private companion object {
        const val TAG = "VideoFormatDeviceTest"
    }
}
