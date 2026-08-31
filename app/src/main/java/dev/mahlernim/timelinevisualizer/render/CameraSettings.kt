package dev.mahlernim.timelinevisualizer.render

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

enum class CameraMovement(
    val contextFraction: Double,
    val minimumContextKm: Double,
    val maximumContextKm: Double,
    val padding: Double,
    val minimumViewportSpan: Double,
    val zoomOutAlpha: Double,
    val zoomInAlpha: Double,
    val legAware: Boolean,
    val fixedZoom: Boolean,
) {
    FIXED(0.10, 25.0, 350.0, 2.6, 0.00060, 0.0, 0.0, false, true),
    STEADY(1.00, 650.0, 650.0, 2.8, 0.00060, 0.14, 0.035, false, false),
    DYNAMIC(0.10, 100.0, 350.0, 2.2, 0.00045, 0.24, 0.06, true, false),
    CLOSE_UP(0.035, 6.0, 120.0, 1.7, 0.00030, 0.30, 0.075, true, false),
}

enum class LongTripCompression(val exponent: Double) {
    OFF(1.00),
    BALANCED(0.85),
    STRONG(0.75),
    STRONGER(0.65),
}

enum class TripDetection(val thresholdMultiplier: Double) {
    CONSERVATIVE(1.35),
    BALANCED(1.00),
    SENSITIVE(0.70),
}

enum class LocalFraming(
    val enabled: Boolean,
    val paddingMultiplier: Double,
) {
    OFF(false, 1.00),
    BALANCED(true, 1.00),
    CLOSE(true, 0.78),
}

enum class VideoAspectRatio {
    SQUARE,
    PORTRAIT,
    LANDSCAPE,
}

enum class VideoResolution(val shortEdge: Int) {
    STANDARD(480),
    HIGH(720),
    ULTRA(1080),
}

enum class ExportResolution(val shortEdge: Int) {
    SD(480),
    HD(720),
    FULL_HD(1080),
    QHD(1440),
    UHD(2160),
}

class FrameRate private constructor(
    val numerator: Int,
    val denominator: Int,
) : Comparable<FrameRate> {
    val value: Double get() = numerator.toDouble() / denominator

    val displayValue: String
        get() = DISPLAY_ALIASES[this] ?: BigDecimal(numerator).divide(
            BigDecimal(denominator),
            3,
            RoundingMode.HALF_UP,
        ).stripTrailingZeros().toPlainString()

    fun frameCount(durationSeconds: Int): Int =
        ((durationSeconds.coerceAtLeast(1).toLong() * numerator + denominator / 2) / denominator).toInt()

    fun timestampUs(frame: Int): Long = frame.toLong() * denominator * 1_000_000L / numerator

    override fun compareTo(other: FrameRate): Int =
        (numerator.toLong() * other.denominator).compareTo(other.numerator.toLong() * denominator)

    override fun equals(other: Any?): Boolean =
        other is FrameRate && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator + denominator

    override fun toString(): String = displayValue

    companion object {
        private val INPUT_ALIASES = mapOf(
            "23.976" to of(24_000, 1_001),
            "29.97" to of(30_000, 1_001),
            "59.94" to of(60_000, 1_001),
            "119.88" to of(120_000, 1_001),
        )
        private val DISPLAY_ALIASES = INPUT_ALIASES.entries.associate { (label, rate) -> rate to label }

        fun of(framesPerSecond: Int): FrameRate = of(framesPerSecond, 1)

        fun of(numerator: Int, denominator: Int): FrameRate {
            require(numerator > 0 && denominator > 0)
            val divisor = gcd(numerator, denominator)
            return FrameRate(numerator / divisor, denominator / divisor)
        }

        fun parse(raw: CharSequence?): FrameRate? {
            val value = raw?.toString()?.trim().orEmpty()
            INPUT_ALIASES[value]?.let { return it }
            if (!value.matches(Regex("[0-9]+(?:\\.[0-9]{1,3})?"))) return null
            val scale = value.substringAfter('.', "").length
            val denominator = TEN_POWERS.getOrNull(scale) ?: return null
            val numerator = value.replace(".", "").toIntOrNull() ?: return null
            return of(numerator, denominator)
        }

        private fun gcd(first: Int, second: Int): Int {
            var a = first
            var b = second
            while (b != 0) {
                val remainder = a % b
                a = b
                b = remainder
            }
            return a
        }

        private val TEN_POWERS = intArrayOf(1, 10, 100, 1_000)
    }
}

data class VideoFormat(
    val width: Int,
    val height: Int,
    val frameRate: FrameRate,
    val bitrate: Int,
) {
    constructor(width: Int, height: Int, frameRate: Int, bitrate: Int) :
        this(width, height, FrameRate.of(frameRate), bitrate)

    val aspectRatio: Float get() = width.toFloat() / height
}

data class ExportFormatSettings(
    val shortEdge: Int,
    val frameRate: FrameRate,
    val customResolution: Boolean = false,
    val customFrameRate: Boolean = false,
) {
    constructor(
        shortEdge: Int,
        frameRate: Int,
        customResolution: Boolean = false,
        customFrameRate: Boolean = false,
    ) : this(shortEdge, FrameRate.of(frameRate), customResolution, customFrameRate)

    init {
        require(shortEdge in MIN_SHORT_EDGE..MAX_SHORT_EDGE)
        require(frameRate >= MIN_FRAME_RATE && frameRate <= MAX_FRAME_RATE)
    }

    fun format(aspectRatio: VideoAspectRatio): VideoFormat {
        val longEdge = even(shortEdge * 16 / 9)
        val (width, height) = when (aspectRatio) {
            VideoAspectRatio.SQUARE -> shortEdge to shortEdge
            VideoAspectRatio.PORTRAIT -> shortEdge to longEdge
            VideoAspectRatio.LANDSCAPE -> longEdge to shortEdge
        }
        return VideoFormat(width, height, frameRate, bitrate(width, height, frameRate, aspectRatio))
    }

    companion object {
        const val MIN_SHORT_EDGE = 480
        const val MAX_SHORT_EDGE = 2160
        val MIN_FRAME_RATE = FrameRate.of(15)
        val MAX_FRAME_RATE = FrameRate.of(240)
        const val DEFAULT_SHORT_EDGE = 480
        val DEFAULT_FRAME_RATE = FrameRate.of(30)

        fun parseShortEdge(raw: CharSequence?): Int? = parseBoundedInt(raw, MIN_SHORT_EDGE, MAX_SHORT_EDGE)

        fun parseFrameRate(raw: CharSequence?): FrameRate? =
            FrameRate.parse(raw)?.takeIf { it >= MIN_FRAME_RATE && it <= MAX_FRAME_RATE }

        fun fromLegacy(quality: VideoQuality): ExportFormatSettings = ExportFormatSettings(
            shortEdge = quality.resolution.shortEdge,
            frameRate = quality.frameRate,
        )

        private fun even(value: Int): Int = value / 2 * 2

        private fun parseBoundedInt(raw: CharSequence?, minimum: Int, maximum: Int): Int? {
            val value = raw?.toString()?.trim().orEmpty()
            if (!value.matches(Regex("[0-9]+"))) return null
            return value.toIntOrNull()?.takeIf { it in minimum..maximum }
        }

        private fun bitrate(
            width: Int,
            height: Int,
            frameRate: FrameRate,
            aspectRatio: VideoAspectRatio,
        ): Int {
            val legacyBase = when (width.coerceAtMost(height)) {
                480 -> if (aspectRatio == VideoAspectRatio.SQUARE) 2_500_000 else 3_500_000
                720 -> if (aspectRatio == VideoAspectRatio.SQUARE) 5_000_000 else 7_000_000
                1080 -> if (aspectRatio == VideoAspectRatio.SQUARE) 8_000_000 else 12_000_000
                else -> null
            }
            val legacyRate = if (aspectRatio == VideoAspectRatio.SQUARE) 24 else 30
            val calculated = legacyBase?.let { (it * frameRate.value / legacyRate).roundToLong() }
                ?: (width.toLong() * height * frameRate.value * 19 / 100).roundToLong()
            val maximum = (40_000_000L * maxOf(frameRate.value, 60.0) / 60.0).roundToLong()
            return calculated.coerceIn(1_500_000L, maximum).toInt()
        }
    }
}

enum class VideoQuality(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int,
    val aspectRatioOption: VideoAspectRatio,
    val resolution: VideoResolution,
) {
    STANDARD(480, 480, 24, 2_500_000, VideoAspectRatio.SQUARE, VideoResolution.STANDARD),
    HIGH(720, 720, 24, 5_000_000, VideoAspectRatio.SQUARE, VideoResolution.HIGH),
    ULTRA(1080, 1080, 24, 8_000_000, VideoAspectRatio.SQUARE, VideoResolution.ULTRA),
    PORTRAIT(1080, 1920, 30, 12_000_000, VideoAspectRatio.PORTRAIT, VideoResolution.ULTRA),
    LANDSCAPE(1920, 1080, 30, 12_000_000, VideoAspectRatio.LANDSCAPE, VideoResolution.ULTRA),
    PORTRAIT_480(480, 854, 30, 3_500_000, VideoAspectRatio.PORTRAIT, VideoResolution.STANDARD),
    PORTRAIT_720(720, 1280, 30, 7_000_000, VideoAspectRatio.PORTRAIT, VideoResolution.HIGH),
    LANDSCAPE_480(854, 480, 30, 3_500_000, VideoAspectRatio.LANDSCAPE, VideoResolution.STANDARD),
    LANDSCAPE_720(1280, 720, 30, 7_000_000, VideoAspectRatio.LANDSCAPE, VideoResolution.HIGH);

    val aspectRatio: Float get() = width.toFloat() / height

    val format: VideoFormat get() = VideoFormat(width, height, FrameRate.of(frameRate), bitrate)

    fun withAspectRatio(aspectRatio: VideoAspectRatio): VideoQuality = of(aspectRatio, resolution)

    fun withResolution(resolution: VideoResolution): VideoQuality = of(aspectRatioOption, resolution)

    companion object {
        fun of(aspectRatio: VideoAspectRatio, resolution: VideoResolution): VideoQuality = entries.first {
            it.aspectRatioOption == aspectRatio && it.resolution == resolution
        }
    }
}

data class CameraSettings(
    val cameraMovement: CameraMovement = CameraMovement.STEADY,
    val longTripCompression: LongTripCompression = LongTripCompression.BALANCED,
    val videoQuality: VideoQuality = VideoQuality.STANDARD,
    val exportFormat: ExportFormatSettings? = null,
    val tripDetection: TripDetection = TripDetection.BALANCED,
    val localFraming: LocalFraming = LocalFraming.BALANCED,
    val keepPastRoutesVisible: Boolean = false,
) {
    val episodeFramingEnabled: Boolean get() = localFraming.enabled

    val effectiveExportFormat: ExportFormatSettings
        get() = exportFormat ?: ExportFormatSettings.fromLegacy(videoQuality)

    val activeVideoFormat: VideoFormat
        get() = exportFormat?.format(videoQuality.aspectRatioOption) ?: videoQuality.format

    companion object {
        val DEFAULT = CameraSettings(
            exportFormat = ExportFormatSettings(
                shortEdge = ExportFormatSettings.DEFAULT_SHORT_EDGE,
                frameRate = ExportFormatSettings.DEFAULT_FRAME_RATE,
            ),
        )
    }
}
