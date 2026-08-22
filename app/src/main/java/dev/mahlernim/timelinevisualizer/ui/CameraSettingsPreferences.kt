package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.core.content.edit
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoQuality

class CameraSettingsPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CameraSettings {
        val existingProductionSettings = preferences.contains(KEY_CAMERA_MOVEMENT) ||
            preferences.contains(KEY_LONG_TRIP) || preferences.contains(KEY_VIDEO_QUALITY)
        val settings = CameraSettings(
            cameraMovement = enumValue(KEY_CAMERA_MOVEMENT, CameraMovement.STEADY),
            longTripCompression = enumValue(KEY_LONG_TRIP, LongTripCompression.BALANCED),
            videoQuality = enumValue(KEY_VIDEO_QUALITY, VideoQuality.STANDARD),
            tripDetection = enumValue(KEY_TRIP_DETECTION, TripDetection.BALANCED),
            localFraming = localFraming(existingProductionSettings),
        )
        save(settings)
        return settings
    }

    fun save(settings: CameraSettings) {
        preferences.edit {
            putString(KEY_CAMERA_MOVEMENT, settings.cameraMovement.name)
            putString(KEY_LONG_TRIP, settings.longTripCompression.name)
            putString(KEY_VIDEO_QUALITY, settings.videoQuality.name)
            putString(KEY_TRIP_DETECTION, settings.tripDetection.name)
            putString(KEY_EPISODE_LOCAL_FRAMING, settings.localFraming.name)
            remove(KEY_ZOOM_IN_TRAVEL_SLOWDOWN)
            remove(KEY_ZOOM_IN_MOVEMENT_REDUCTION)
            remove(KEY_EPISODE_FRAMING_ENABLED)
            remove(KEY_ROUTE_CONTEXT)
            remove(KEY_LEGACY_LOCAL_FRAMING)
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

    private fun localFraming(existingProductionSettings: Boolean): LocalFraming {
        val stored = preferences.getString(KEY_EPISODE_LOCAL_FRAMING, null)
        if (preferences.contains(KEY_EPISODE_FRAMING_ENABLED)) {
            if (!preferences.getBoolean(KEY_EPISODE_FRAMING_ENABLED, false)) return LocalFraming.OFF
            return when (stored) {
                LocalFraming.CLOSE.name -> LocalFraming.CLOSE
                else -> LocalFraming.BALANCED
            }
        }
        return when (stored) {
            LocalFraming.CLOSE.name -> LocalFraming.CLOSE
            LocalFraming.OFF.name -> LocalFraming.OFF
            LocalFraming.BALANCED.name -> LocalFraming.BALANCED
            else -> if (existingProductionSettings) LocalFraming.OFF else LocalFraming.BALANCED
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "camera-settings"
        const val KEY_CAMERA_MOVEMENT = "camera-movement"
        const val KEY_ROUTE_CONTEXT = "route-context"
        const val KEY_LEGACY_LOCAL_FRAMING = "local-framing"
        const val KEY_ZOOM_IN = "zoom-in"
        const val KEY_LONG_HOP = "long-hop"
        const val KEY_LONG_TRIP = "long-trip"
        const val KEY_VIDEO_QUALITY = "video-quality"
        const val KEY_ZOOM_IN_TRAVEL_SLOWDOWN = "zoom-in-travel-slowdown"
        const val KEY_ZOOM_IN_MOVEMENT_REDUCTION = "zoom-in-movement-reduction"
        const val KEY_EPISODE_FRAMING_ENABLED = "episode-framing-enabled"
        const val KEY_TRIP_DETECTION = "episode-trip-detection"
        const val KEY_EPISODE_LOCAL_FRAMING = "episode-local-framing"
    }
}
