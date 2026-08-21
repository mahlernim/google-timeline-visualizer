package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.core.content.edit
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.VideoQuality

class CameraSettingsPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CameraSettings = CameraSettings(
        cameraMovement = enumValue(KEY_CAMERA_MOVEMENT, CameraMovement.STEADY),
        longTripCompression = enumValue(KEY_LONG_TRIP, LongTripCompression.BALANCED),
        videoQuality = enumValue(KEY_VIDEO_QUALITY, VideoQuality.STANDARD),
        zoomInMovementReduction = zoomInMovementReduction(),
    )

    fun save(settings: CameraSettings) {
        preferences.edit {
            putString(KEY_CAMERA_MOVEMENT, settings.cameraMovement.name)
            putString(KEY_LONG_TRIP, settings.longTripCompression.name)
            putString(KEY_VIDEO_QUALITY, settings.videoQuality.name)
            putInt(
                KEY_ZOOM_IN_MOVEMENT_REDUCTION,
                (settings.zoomInMovementReduction.coerceIn(0.0, 1.0) * 100).toInt(),
            )
            remove(KEY_ROUTE_CONTEXT)
            remove(KEY_LOCAL_FRAMING)
            remove(KEY_ZOOM_IN)
            remove(KEY_LONG_HOP)
        }
    }

    fun reset(): CameraSettings {
        preferences.edit { clear() }
        return CameraSettings.DEFAULT
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, null) ?: return fallback) }.getOrDefault(fallback)

    private fun zoomInMovementReduction(): Double = when (
        val stored = preferences.all[KEY_ZOOM_IN_MOVEMENT_REDUCTION]
    ) {
        is Int -> stored / 100.0
        is Float -> stored.toDouble()
        else -> CameraSettings.DEFAULT_ZOOM_IN_MOVEMENT_REDUCTION
    }.coerceIn(0.0, 1.0)

    private companion object {
        const val PREFERENCES_NAME = "camera-settings"
        const val KEY_CAMERA_MOVEMENT = "camera-movement"
        const val KEY_ROUTE_CONTEXT = "route-context"
        const val KEY_LOCAL_FRAMING = "local-framing"
        const val KEY_ZOOM_IN = "zoom-in"
        const val KEY_LONG_HOP = "long-hop"
        const val KEY_LONG_TRIP = "long-trip"
        const val KEY_VIDEO_QUALITY = "video-quality"
        const val KEY_ZOOM_IN_MOVEMENT_REDUCTION = "zoom-in-movement-reduction"
    }
}
