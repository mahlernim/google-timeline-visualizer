package dev.mahlernim.timelinevisualizer.presets

import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio

data class PresetValues(
    val aspectRatio: VideoAspectRatio,
    val cameraMovement: CameraMovement,
    val tripDetection: TripDetection,
    val localFraming: LocalFraming,
    val longTripCompression: LongTripCompression,
    val durationSeconds: Int = 30,
) {
    fun applyTo(draft: CameraSettings): CameraSettings = draft.copy(
        videoQuality = draft.videoQuality.withAspectRatio(aspectRatio),
        cameraMovement = cameraMovement,
        tripDetection = tripDetection,
        localFraming = localFraming,
        longTripCompression = longTripCompression,
    )

    companion object {
        fun from(settings: CameraSettings, durationSeconds: Int = 30): PresetValues = PresetValues(
            aspectRatio = settings.videoQuality.aspectRatioOption,
            cameraMovement = settings.cameraMovement,
            tripDetection = settings.tripDetection,
            localFraming = settings.localFraming,
            longTripCompression = settings.longTripCompression,
            durationSeconds = durationSeconds,
        )
    }
}

data class VideoPreset(
    val id: String,
    val name: String,
    val values: PresetValues,
    val builtIn: Boolean = false,
)

sealed interface PresetNameResult {
    data class Valid(val name: String) : PresetNameResult
    data object Empty : PresetNameResult
    data object TooLong : PresetNameResult
    data object Duplicate : PresetNameResult
}
