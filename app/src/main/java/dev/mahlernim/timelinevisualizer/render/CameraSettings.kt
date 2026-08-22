package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.BuildConfig

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
    CLOSE_UP(0.035, 15.0, 120.0, 1.7, 0.00030, 0.30, 0.075, true, false),
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

enum class VideoQuality(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int,
) {
    STANDARD(480, 480, 24, 2_500_000),
    HIGH(720, 720, 24, 5_000_000),
    ULTRA(1080, 1080, 24, 8_000_000),
    PORTRAIT(1080, 1920, 30, 12_000_000),
    LANDSCAPE(1920, 1080, 30, 12_000_000);

    val aspectRatio: Float get() = width.toFloat() / height
}

data class CameraSettings(
    val cameraMovement: CameraMovement = CameraMovement.STEADY,
    val longTripCompression: LongTripCompression = LongTripCompression.BALANCED,
    val videoQuality: VideoQuality = VideoQuality.STANDARD,
    val tripDetection: TripDetection = TripDetection.BALANCED,
    val localFraming: LocalFraming = DEFAULT_LOCAL_FRAMING,
) {
    val episodeFramingEnabled: Boolean get() = localFraming.enabled

    companion object {
        val DEFAULT_LOCAL_FRAMING = if (BuildConfig.DEFAULT_EPISODE_FRAMING) {
            LocalFraming.BALANCED
        } else {
            LocalFraming.OFF
        }
        val DEFAULT = CameraSettings()
    }
}
