package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import dev.mahlernim.timelinevisualizer.R
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoQuality

internal data class EncoderProfile(
    val name: String,
    val hardwareAccelerated: Boolean,
    val widthRange: IntRange,
    val heightRange: IntRange,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val bitrateRange: IntRange,
    val maxFrameRateFor: (Int, Int) -> Double,
    val colorFormats: Set<Int>,
)

internal sealed interface EncoderSupport {
    data class Supported(val name: String, val colorFormat: Int) : EncoderSupport

    data class Unsupported(val reason: Reason) : EncoderSupport

    enum class Reason { NO_ENCODER, SIZE, ALIGNMENT, FRAME_RATE, BITRATE, COLOR_FORMAT }
}

internal fun EncoderSupport.Reason.describe(context: Context, format: VideoFormat): String =
    context.getString(R.string.format_not_supported, format.width, format.height)

internal class UnsupportedVideoFormatException(
    val reason: EncoderSupport.Reason,
    val format: VideoFormat,
) : RuntimeException(
    "No H.264 encoder accepts ${format.width}x${format.height}@${format.frameRate} ($reason)",
)

internal object VideoEncoderSupport {
    // Flexible YUV does not identify the planar or semi-planar byte layout required by the frame converter.
    @Suppress("DEPRECATION")
    private val preferredColorFormats = listOf(
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
    )

    fun evaluate(format: VideoFormat): EncoderSupport = select(format, deviceProfiles())

    // A custom format outside the envelope of the largest shipped preset hasn't been exercised
    // by VideoFormatDeviceTest on real hardware, so treat it as a "may be slow or crash" risk
    // even when the device claims to support it.
    fun isRisky(format: VideoFormat): Boolean {
        val largestPreset = VideoQuality.LANDSCAPE
        return format.width.coerceAtLeast(format.height) > largestPreset.width.coerceAtLeast(largestPreset.height) ||
            format.frameRate > largestPreset.frameRate
    }

    fun select(format: VideoFormat, profiles: List<EncoderProfile>): EncoderSupport {
        if (profiles.isEmpty()) return EncoderSupport.Unsupported(EncoderSupport.Reason.NO_ENCODER)
        var closest = EncoderSupport.Reason.NO_ENCODER
        profiles.sortedByDescending { it.hardwareAccelerated }.forEach { profile ->
            when (val result = evaluate(format, profile)) {
                is EncoderSupport.Supported -> return result
                is EncoderSupport.Unsupported -> if (result.reason.ordinal > closest.ordinal) {
                    closest = result.reason
                }
            }
        }
        return EncoderSupport.Unsupported(closest)
    }

    fun deviceProfiles(): List<EncoderProfile> =
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList())
            .mapNotNull(::toProfile)

    private fun evaluate(format: VideoFormat, profile: EncoderProfile): EncoderSupport {
        if (format.width !in profile.widthRange || format.height !in profile.heightRange) {
            return EncoderSupport.Unsupported(EncoderSupport.Reason.SIZE)
        }
        if (
            format.width % profile.widthAlignment.coerceAtLeast(1) != 0 ||
            format.height % profile.heightAlignment.coerceAtLeast(1) != 0
        ) {
            return EncoderSupport.Unsupported(EncoderSupport.Reason.ALIGNMENT)
        }
        val maximumFrameRate = runCatching { profile.maxFrameRateFor(format.width, format.height) }
            .getOrDefault(0.0)
        if (maximumFrameRate <= 0.0) return EncoderSupport.Unsupported(EncoderSupport.Reason.SIZE)
        if (format.frameRate > maximumFrameRate) {
            return EncoderSupport.Unsupported(EncoderSupport.Reason.FRAME_RATE)
        }
        if (format.bitrate !in profile.bitrateRange) {
            return EncoderSupport.Unsupported(EncoderSupport.Reason.BITRATE)
        }
        val colorFormat = preferredColorFormats.firstOrNull(profile.colorFormats::contains)
            ?: return EncoderSupport.Unsupported(EncoderSupport.Reason.COLOR_FORMAT)
        return EncoderSupport.Supported(profile.name, colorFormat)
    }

    private fun toProfile(info: MediaCodecInfo): EncoderProfile? {
        if (!info.isEncoder) return null
        if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) return null
        val capabilities = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }
            .getOrNull() ?: return null
        val video = capabilities.videoCapabilities ?: return null
        return EncoderProfile(
            name = info.name,
            hardwareAccelerated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated,
            widthRange = video.supportedWidths.lower..video.supportedWidths.upper,
            heightRange = video.supportedHeights.lower..video.supportedHeights.upper,
            widthAlignment = video.widthAlignment,
            heightAlignment = video.heightAlignment,
            bitrateRange = video.bitrateRange.lower..video.bitrateRange.upper,
            maxFrameRateFor = { width, height ->
                runCatching { video.getSupportedFrameRatesFor(width, height).upper }.getOrDefault(0.0)
            },
            colorFormats = capabilities.colorFormats.toSet(),
        )
    }
}
