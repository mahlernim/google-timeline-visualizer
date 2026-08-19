package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.core.content.edit
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoFormatPreset

class CameraSettingsPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CameraSettings = CameraSettings(
        cameraMovement = enumValue(KEY_CAMERA_MOVEMENT, CameraMovement.STEADY),
        longTripCompression = enumValue(KEY_LONG_TRIP, LongTripCompression.BALANCED),
        videoFormatPreset = loadFormatPreset(),
        customFormat = loadCustomFormat(),
    )

    fun save(settings: CameraSettings) {
        preferences.edit {
            putString(KEY_CAMERA_MOVEMENT, settings.cameraMovement.name)
            putString(KEY_LONG_TRIP, settings.longTripCompression.name)
            putString(KEY_VIDEO_FORMAT, settings.videoFormatPreset.name)
            settings.customFormat?.let { custom ->
                putInt(KEY_CUSTOM_WIDTH, custom.width)
                putInt(KEY_CUSTOM_HEIGHT, custom.height)
                putInt(KEY_CUSTOM_FRAME_RATE, custom.frameRate)
                putInt(KEY_CUSTOM_BITRATE, custom.bitrate)
            } ?: run {
                remove(KEY_CUSTOM_WIDTH)
                remove(KEY_CUSTOM_HEIGHT)
                remove(KEY_CUSTOM_FRAME_RATE)
                remove(KEY_CUSTOM_BITRATE)
            }
            remove(KEY_ROUTE_CONTEXT)
            remove(KEY_LOCAL_FRAMING)
            remove(KEY_ZOOM_IN)
            remove(KEY_LONG_HOP)
            remove(KEY_VIDEO_QUALITY)
        }
    }

    fun reset(): CameraSettings {
        preferences.edit { clear() }
        return CameraSettings.DEFAULT
    }

    /**
     * Reads the format preset, falling back to the `video-quality` value written by versions up to
     * 2.1.0 so an upgrade keeps the square size the user already chose.
     */
    private fun loadFormatPreset(): VideoFormatPreset =
        VideoFormatPreset.fromStoredName(preferences.getString(KEY_VIDEO_FORMAT, null))
            ?: VideoFormatPreset.fromStoredName(preferences.getString(KEY_VIDEO_QUALITY, null))
            ?: VideoFormatPreset.DEFAULT

    private fun loadCustomFormat(): VideoFormat? {
        val width = preferences.getInt(KEY_CUSTOM_WIDTH, 0)
        val height = preferences.getInt(KEY_CUSTOM_HEIGHT, 0)
        val frameRate = preferences.getInt(KEY_CUSTOM_FRAME_RATE, 0)
        if (!VideoFormat.isWithinBounds(width, height, frameRate)) return null
        val bitrate = preferences.getInt(KEY_CUSTOM_BITRATE, 0)
            .takeIf { it in VideoFormat.MIN_BITRATE..VideoFormat.MAX_BITRATE }
            ?: VideoFormat.bitrateFor(width, height, frameRate)
        return VideoFormat(width, height, frameRate, bitrate)
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, null) ?: return fallback) }.getOrDefault(fallback)

    private companion object {
        const val PREFERENCES_NAME = "camera-settings"
        const val KEY_CAMERA_MOVEMENT = "camera-movement"
        const val KEY_ROUTE_CONTEXT = "route-context"
        const val KEY_LOCAL_FRAMING = "local-framing"
        const val KEY_ZOOM_IN = "zoom-in"
        const val KEY_LONG_HOP = "long-hop"
        const val KEY_LONG_TRIP = "long-trip"
        const val KEY_VIDEO_QUALITY = "video-quality"
        const val KEY_VIDEO_FORMAT = "video-format-preset"
        const val KEY_CUSTOM_WIDTH = "video-format-width"
        const val KEY_CUSTOM_HEIGHT = "video-format-height"
        const val KEY_CUSTOM_FRAME_RATE = "video-format-frame-rate"
        const val KEY_CUSTOM_BITRATE = "video-format-bitrate"
    }
}
