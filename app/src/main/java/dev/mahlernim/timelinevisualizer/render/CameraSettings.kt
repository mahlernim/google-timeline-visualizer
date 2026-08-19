package dev.mahlernim.timelinevisualizer.render

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
}

enum class LongTripCompression(val exponent: Double) {
    OFF(1.00),
    GENTLE(0.92),
    BALANCED(0.85),
    STRONG(0.75),
}

data class CameraSettings(
    val cameraMovement: CameraMovement = CameraMovement.STEADY,
    val longTripCompression: LongTripCompression = LongTripCompression.BALANCED,
    val videoFormatPreset: VideoFormatPreset = VideoFormatPreset.DEFAULT,
    val customFormat: VideoFormat? = null,
) {
    /** The format the exporter and the preview should use for the current selection. */
    val videoFormat: VideoFormat
        get() = videoFormatPreset.format
            ?: customFormat
            ?: VideoFormatPreset.DEFAULT.format!!

    companion object {
        val DEFAULT = CameraSettings()
    }
}
