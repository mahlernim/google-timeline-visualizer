package dev.mahlernim.timelinevisualizer.privacy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PrivacyAreaStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<PrivacyArea> {
        val json = preferences.getString(KEY_AREAS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                readArea(array.optJSONObject(index) ?: return@mapNotNull null)
            }
                .distinctBy(PrivacyArea::id)
        }.getOrDefault(emptyList())
    }

    fun save(areas: List<PrivacyArea>) {
        val array = JSONArray()
        areas.forEach { area ->
            array.put(
                JSONObject()
                    .put(FIELD_ID, area.id)
                    .put(FIELD_NAME, area.name)
                    .put(FIELD_LATITUDE, area.latitude)
                    .put(FIELD_LONGITUDE, area.longitude)
                    .put(FIELD_RADIUS_KM, area.radiusKm),
            )
        }
        preferences.edit().putString(KEY_AREAS, array.toString()).apply()
    }

    private fun readArea(value: JSONObject): PrivacyArea? = runCatching {
        PrivacyArea(
            id = value.getString(FIELD_ID),
            name = value.getString(FIELD_NAME),
            latitude = value.getDouble(FIELD_LATITUDE),
            longitude = value.getDouble(FIELD_LONGITUDE),
            radiusKm = value.getDouble(FIELD_RADIUS_KM),
        )
    }.getOrNull()

    companion object {
        private const val PREFERENCES = "safe_sharing_areas"
        private const val KEY_AREAS = "areas_v1"
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_LATITUDE = "latitude"
        private const val FIELD_LONGITUDE = "longitude"
        private const val FIELD_RADIUS_KM = "radiusKm"
    }
}
