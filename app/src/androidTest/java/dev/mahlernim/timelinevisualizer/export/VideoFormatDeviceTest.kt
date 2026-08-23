package dev.mahlernim.timelinevisualizer.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.ExportFormatSettings
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoFormatDeviceTest {
    @Test
    fun theDeviceCanEvaluateAndConfigureEveryReportedPreset() {
        val profiles = VideoEncoderSupport.deviceProfiles()
        assertTrue("The device exposed no H.264 encoder", profiles.isNotEmpty())

        val formats = VideoQuality.values().map(VideoQuality::format) + listOf(
            CameraSettings.DEFAULT.activeVideoFormat,
            ExportFormatSettings(1440, 30).format(VideoAspectRatio.LANDSCAPE),
            ExportFormatSettings(2160, 60).format(VideoAspectRatio.LANDSCAPE),
        )
        formats.forEach { format ->
            val support = VideoEncoderSupport.select(format, profiles)
            Log.i(TAG, "${format.width}x${format.height}@${format.frameRate}: $support")
            if (support is EncoderSupport.Supported) configure(format, support)
        }

        assertTrue(
            "The device cannot encode the default square format",
            VideoEncoderSupport.select(VideoQuality.STANDARD, profiles) is EncoderSupport.Supported,
        )
    }

    private fun configure(preset: VideoFormat, support: EncoderSupport.Supported) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            preset.width,
            preset.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, support.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, preset.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, preset.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(support.name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
