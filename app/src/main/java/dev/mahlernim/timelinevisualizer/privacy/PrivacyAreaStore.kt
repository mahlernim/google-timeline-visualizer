package dev.mahlernim.timelinevisualizer.privacy

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PrivacyAreaStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): List<PrivacyArea> {
        val json = preferences.getString(KEY_AREAS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PrivacyArea>>() {}.type
            gson.fromJson<List<PrivacyArea>>(json, type)
                .orEmpty()
                .filter(::isValid)
                .distinctBy(PrivacyArea::id)
        }.getOrDefault(emptyList())
    }

    fun save(areas: List<PrivacyArea>) {
        preferences.edit().putString(KEY_AREAS, gson.toJson(areas)).apply()
    }

    private fun isValid(area: PrivacyArea): Boolean = runCatching {
        PrivacyArea(area.id, area.name, area.latitude, area.longitude, area.radiusKm)
    }.isSuccess

    companion object {
        private const val PREFERENCES = "privacy_prototype"
        private const val KEY_AREAS = "areas_v1"
    }
}
