package dev.mahlernim.timelinevisualizer.render

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The pixel dimensions, frame rate, and bitrate of an exported video.
 *
 * Dimensions are stored exactly as chosen. Nothing in the export pipeline rounds or substitutes
 * them: if a device cannot encode a format, the export is refused rather than quietly resized.
 */
data class VideoFormat(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int,
) {
    val aspect: Float get() = width.toFloat() / height.coerceAtLeast(1)

    val shortEdge: Int get() = minOf(width, height)

    val longEdge: Int get() = maxOf(width, height)

    companion object {
        const val MIN_DIMENSION = 240
        const val MAX_DIMENSION = 3840
        const val MIN_BITRATE = 1_000_000
        const val MAX_BITRATE = 60_000_000

        val FRAME_RATES = listOf(24, 30, 60)

        /**
         * Bitrate for a custom format.
         *
         * Encoders need proportionally fewer bits per pixel as frames grow, so the pixel count is
         * damped by an exponent rather than scaled linearly. The constants are fitted to the three
         * square presets, which keeps a custom 720x720 close to [VideoFormatPreset.SQUARE_720].
         */
        fun bitrateFor(width: Int, height: Int, frameRate: Int): Int {
            val pixels = (width.toDouble() * height).coerceAtLeast(1.0)
            val raw = BITRATE_CONSTANT * pixels.pow(BITRATE_EXPONENT) * (frameRate / 24.0)
            return raw.roundToInt().coerceIn(MIN_BITRATE, MAX_BITRATE)
        }

        /** True when width, height, and frame rate are all inside the bounds this app offers. */
        fun isWithinBounds(width: Int, height: Int, frameRate: Int): Boolean =
            width in MIN_DIMENSION..MAX_DIMENSION &&
                height in MIN_DIMENSION..MAX_DIMENSION &&
                frameRate in FRAME_RATES

        fun custom(width: Int, height: Int, frameRate: Int): VideoFormat =
            VideoFormat(width, height, frameRate, bitrateFor(width, height, frameRate))

        private const val BITRATE_CONSTANT = 237.0
        private const val BITRATE_EXPONENT = 0.75
    }
}

/**
 * The export formats offered in Settings.
 *
 * The three square entries reproduce the original `VideoQuality` values byte for byte so existing
 * installs keep the output they already had. Their bitrates are the historical constants, not
 * [VideoFormat.bitrateFor] results.
 */
enum class VideoFormatPreset(val format: VideoFormat?) {
    SQUARE_480(VideoFormat(480, 480, 24, 2_500_000)),
    SQUARE_720(VideoFormat(720, 720, 24, 5_000_000)),
    SQUARE_1080(VideoFormat(1080, 1080, 24, 8_000_000)),
    PORTRAIT_1080(VideoFormat(1080, 1920, 30, 12_000_000)),
    LANDSCAPE_1080(VideoFormat(1920, 1080, 30, 12_000_000)),
    LANDSCAPE_2160(VideoFormat(3840, 2160, 30, 40_000_000)),
    CUSTOM(null);

    val isCustom: Boolean get() = format == null

    companion object {
        val DEFAULT = SQUARE_480

        /**
         * Resolves a preset name read from storage, accepting the `VideoQuality` names written by
         * app versions up to 2.1.0. Returns null when the name is unknown, so callers can fall back
         * deliberately instead of mistaking a corrupt value for a real choice.
         */
        fun fromStoredName(name: String?): VideoFormatPreset? {
            if (name.isNullOrBlank()) return null
            values().firstOrNull { it.name == name }?.let { return it }
            return when (name) {
                "STANDARD" -> SQUARE_480
                "HIGH" -> SQUARE_720
                "ULTRA" -> SQUARE_1080
                else -> null
            }
        }
    }
}
