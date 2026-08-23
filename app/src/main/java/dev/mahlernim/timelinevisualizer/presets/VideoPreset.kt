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
) {
    fun applyTo(draft: CameraSettings): CameraSettings = draft.copy(
        videoQuality = draft.videoQuality.withAspectRatio(aspectRatio),
        customVideoFormat = draft.customVideoFormat?.copy(aspectRatioOption = aspectRatio),
        cameraMovement = cameraMovement,
        tripDetection = tripDetection,
        localFraming = localFraming,
        longTripCompression = longTripCompression,
    )

    companion object {
        fun from(settings: CameraSettings): PresetValues = PresetValues(
            aspectRatio = settings.videoQuality.aspectRatioOption,
            cameraMovement = settings.cameraMovement,
            tripDetection = settings.tripDetection,
            localFraming = settings.localFraming,
            longTripCompression = settings.longTripCompression,
        )
    }
}

data class VideoPreset(
    val id: String,
    val name: String,
    val values: PresetValues,
)

sealed interface PresetNameResult {
    data class Valid(val name: String) : PresetNameResult
    data object Empty : PresetNameResult
    data object TooLong : PresetNameResult
    data object Duplicate : PresetNameResult
}
