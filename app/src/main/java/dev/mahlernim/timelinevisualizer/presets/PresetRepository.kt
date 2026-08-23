package dev.mahlernim.timelinevisualizer.presets

import android.content.Context
import androidx.core.content.edit
import dev.mahlernim.timelinevisualizer.R
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import dev.mahlernim.timelinevisualizer.model.VideoDuration
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class PresetRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun presets(): List<VideoPreset> = localizedBuiltIns() + userPresets()

    private fun localizedBuiltIns(): List<VideoPreset> = BUILT_IN_PRESETS.map { preset ->
        preset.copy(
            name = context.getString(
                if (preset.id == TRIP_CLOSE_UP_ID) R.string.trip_defaults else R.string.recap_defaults,
            ),
        )
    }

    private fun userPresets(): List<VideoPreset> {
        val raw = preferences.getString(KEY_PRESETS, null) ?: return emptyList()
        if (raw.length > MAX_STORED_CHARS) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_PRESETS)) {
                parse(array.optJSONObject(index))?.let(::add)
            }
        }
    }

    fun defaultPresetId(): String? = preferences.getString(KEY_DEFAULT_ID, null)
        ?.takeIf { id -> presets().any { it.id == id } }

    fun setDefaultPresetId(id: String?) {
        preferences.edit { if (id == null) remove(KEY_DEFAULT_ID) else putString(KEY_DEFAULT_ID, id) }
    }

    fun validateName(input: String, excludingId: String? = null): PresetNameResult {
        val name = input.replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty()) return PresetNameResult.Empty
        if (name.codePointCount(0, name.length) > MAX_NAME_CODE_POINTS) return PresetNameResult.TooLong
        val key = name.lowercase(Locale.ROOT)
        if (presets().any { it.id != excludingId && it.name.lowercase(Locale.ROOT) == key }) {
            return PresetNameResult.Duplicate
        }
        return PresetNameResult.Valid(name)
    }

    fun add(name: String, values: PresetValues): VideoPreset {
        val valid = validateName(name) as? PresetNameResult.Valid
            ?: throw IllegalArgumentException("Invalid preset name")
        val existing = userPresets()
        check(existing.size < MAX_PRESETS)
        return VideoPreset(UUID.randomUUID().toString(), valid.name, values).also {
            write(existing + it)
        }
    }

    fun rename(id: String, name: String): VideoPreset? {
        if (isBuiltIn(id)) return null
        val valid = validateName(name, id) as? PresetNameResult.Valid ?: return null
        var renamed: VideoPreset? = null
        val updated = userPresets().map {
            if (it.id == id) it.copy(name = valid.name).also { value -> renamed = value } else it
        }
        if (renamed != null) write(updated)
        return renamed
    }

    fun replace(id: String, values: PresetValues): VideoPreset? {
        if (isBuiltIn(id)) return null
        var replaced: VideoPreset? = null
        val updated = userPresets().map { preset ->
            if (preset.id == id) preset.copy(values = values).also { replaced = it } else preset
        }
        if (replaced != null) write(updated)
        return replaced
    }

    fun delete(id: String): Boolean {
        if (isBuiltIn(id)) return false
        val current = userPresets()
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        write(updated)
        if (preferences.getString(KEY_DEFAULT_ID, null) == id) setDefaultPresetId(null)
        return true
    }

    fun exactMatch(values: PresetValues): VideoPreset? = presets().firstOrNull { it.values == values }

    fun isBuiltIn(id: String): Boolean = id == TRIP_CLOSE_UP_ID || id == RECAP_PORTRAIT_ID

    private fun write(presets: List<VideoPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("aspect", preset.values.aspectRatio.name)
                put("camera", preset.values.cameraMovement.name)
                put("trip", preset.values.tripDetection.name)
                put("framing", preset.values.localFraming.name)
                put("pacing", preset.values.longTripCompression.name)
                put("durationSeconds", preset.values.durationSeconds)
            })
        }
        preferences.edit { putString(KEY_PRESETS, array.toString()) }
    }

    private fun parse(value: JSONObject?): VideoPreset? = runCatching {
        value ?: return null
        val id = value.getString("id").takeIf { it.length in 1..64 } ?: return null
        val name = value.getString("name")
        if (validateStoredName(name) == null) return null
        VideoPreset(
            id,
            name,
            PresetValues(
                VideoAspectRatio.valueOf(value.getString("aspect")),
                CameraMovement.valueOf(value.getString("camera")),
                TripDetection.valueOf(value.getString("trip")),
                LocalFraming.valueOf(value.getString("framing")),
                LongTripCompression.valueOf(value.getString("pacing")),
                value.optInt("durationSeconds", 30).coerceIn(
                    VideoDuration.MIN_SECONDS,
                    VideoDuration.MAX_SECONDS,
                ),
            ),
        )
    }.getOrNull()

    private fun validateStoredName(name: String): String? = name.takeIf {
        it.isNotBlank() && it.codePointCount(0, it.length) <= MAX_NAME_CODE_POINTS
    }

    companion object {
        const val TRIP_CLOSE_UP_ID = "builtin-trip-close-up"
        const val RECAP_PORTRAIT_ID = "builtin-recap-portrait"
        const val MAX_NAME_CODE_POINTS = 40
        const val MAX_PRESETS = 50
        private const val MAX_STORED_CHARS = 64 * 1024
        private const val PREFERENCES_NAME = "video-presets"
        private const val KEY_PRESETS = "presets-v1"
        private const val KEY_DEFAULT_ID = "default-preset-id"

        val BUILT_IN_PRESETS = listOf(
            VideoPreset(
                id = TRIP_CLOSE_UP_ID,
                name = "Trip defaults",
                values = PresetValues(
                    aspectRatio = VideoAspectRatio.PORTRAIT,
                    cameraMovement = CameraMovement.CLOSE_UP,
                    tripDetection = TripDetection.SENSITIVE,
                    localFraming = LocalFraming.CLOSE,
                    longTripCompression = LongTripCompression.STRONGER,
                    durationSeconds = 20,
                ),
                builtIn = true,
            ),
            VideoPreset(
                id = RECAP_PORTRAIT_ID,
                name = "Recap defaults",
                values = PresetValues(
                    aspectRatio = VideoAspectRatio.PORTRAIT,
                    cameraMovement = CameraMovement.STEADY,
                    tripDetection = TripDetection.BALANCED,
                    localFraming = LocalFraming.BALANCED,
                    longTripCompression = LongTripCompression.BALANCED,
                    durationSeconds = 30,
                ),
                builtIn = true,
            ),
        )
    }
}
