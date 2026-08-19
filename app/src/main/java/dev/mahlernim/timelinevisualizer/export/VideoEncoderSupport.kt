package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import dev.mahlernim.timelinevisualizer.R
import dev.mahlernim.timelinevisualizer.render.VideoFormat

/**
 * What a single H.264 encoder on this device can accept, flattened into plain values.
 *
 * Keeping the decision separate from [MediaCodecInfo] lets [VideoEncoderSupport.select] be a pure
 * function, which matters because the JVM test runtime exposes no real codecs.
 */
data class EncoderProfile(
    val name: String,
    val hardwareAccelerated: Boolean,
    val widthRange: IntRange,
    val heightRange: IntRange,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val bitrateRange: IntRange,
    /** Highest frame rate for the given size, or 0.0 when the codec rejects the size outright. */
    val maxFrameRateFor: (Int, Int) -> Double,
    val colorFormats: Set<Int>,
)

sealed interface EncoderSupport {
    data class Supported(val name: String, val colorFormat: Int) : EncoderSupport

    data class Unsupported(val reason: Reason) : EncoderSupport

    /**
     * Why no encoder accepted the format. Ordered least to most specific: when several encoders
     * fail for different reasons, the furthest-along failure is reported, because that is the one
     * the user can act on.
     */
    enum class Reason { NO_ENCODER, SIZE, ALIGNMENT, FRAME_RATE, BITRATE, COLOR_FORMAT }
}

/** The message to show when [this] kept [format] from being encoded. */
fun EncoderSupport.Reason.describe(context: Context, format: VideoFormat): String = when (this) {
    EncoderSupport.Reason.FRAME_RATE -> context.getString(
        R.string.format_not_supported_frame_rate,
        format.width,
        format.height,
        format.frameRate,
    )
    EncoderSupport.Reason.BITRATE -> context.getString(
        R.string.format_not_supported_bitrate,
        format.width,
        format.height,
    )
    else -> context.getString(R.string.format_not_supported, format.width, format.height)
}

class UnsupportedVideoFormatException(
    val reason: EncoderSupport.Reason,
    val format: VideoFormat,
) : RuntimeException("No H.264 encoder accepts ${format.width}x${format.height}@${format.frameRate} ($reason)")

object VideoEncoderSupport {
    private val PREFERRED_COLOR_FORMATS = listOf(
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
    )

    fun evaluate(format: VideoFormat): EncoderSupport = select(format, deviceProfiles())

    fun isSupported(format: VideoFormat): Boolean = evaluate(format) is EncoderSupport.Supported

    /** Pure selection: prefers hardware encoders, otherwise keeps the platform's own ordering. */
    fun select(format: VideoFormat, profiles: List<EncoderProfile>): EncoderSupport {
        if (profiles.isEmpty()) return EncoderSupport.Unsupported(EncoderSupport.Reason.NO_ENCODER)
        var closest = EncoderSupport.Reason.NO_ENCODER
        for (profile in profiles.sortedByDescending { it.hardwareAccelerated }) {
            when (val outcome = evaluate(format, profile)) {
                is EncoderSupport.Supported -> return outcome
                is EncoderSupport.Unsupported ->
                    if (outcome.reason.ordinal > closest.ordinal) closest = outcome.reason
            }
        }
        return EncoderSupport.Unsupported(closest)
    }

    private fun evaluate(format: VideoFormat, profile: EncoderProfile): EncoderSupport {
        if (format.width !in profile.widthRange || format.height !in profile.heightRange) {
            return EncoderSupport.Unsupported(EncoderSupport.Reason.SIZE)
        }
        if (
            profile.widthAlignment > 0 && format.width % profile.widthAlignment != 0 ||
            profile.heightAlignment > 0 && format.height % profile.heightAlignment != 0
        ) {
            return EncoderSupport.Unsupported(EncoderSupport.Reason.ALIGNMENT)
        }
        val maxFrameRate = runCatching { profile.maxFrameRateFor(format.width, format.height) }
            .getOrDefault(0.0)
        if (maxFrameRate <= 0.0) return EncoderSupport.Unsupported(EncoderSupport.Reason.SIZE)
        if (format.frameRate > maxFrameRate) {
            return EncoderSupport.Unsupported(EncoderSupport.Reason.FRAME_RATE)
        }
        if (format.bitrate !in profile.bitrateRange) {
            return EncoderSupport.Unsupported(EncoderSupport.Reason.BITRATE)
        }
        val colorFormat = PREFERRED_COLOR_FORMATS.firstOrNull(profile.colorFormats::contains)
            ?: return EncoderSupport.Unsupported(EncoderSupport.Reason.COLOR_FORMAT)
        return EncoderSupport.Supported(profile.name, colorFormat)
    }

    /** Every H.264 encoder this device advertises, described as [EncoderProfile]s. */
    fun deviceProfiles(): List<EncoderProfile> =
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList())
            .mapNotNull(::toProfile)

    private fun toProfile(info: MediaCodecInfo): EncoderProfile? {
        if (!info.isEncoder) return null
        if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) return null
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
