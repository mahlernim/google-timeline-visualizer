package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.core.content.edit
import dev.mahlernim.timelinevisualizer.render.DistanceUnitPreference

class DistanceUnitPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): DistanceUnitPreference = runCatching {
        enumValueOf<DistanceUnitPreference>(
            preferences.getString(KEY_DISTANCE_UNIT, null) ?: return DistanceUnitPreference.KILOMETERS,
        )
    }.getOrDefault(DistanceUnitPreference.KILOMETERS)

    fun save(preference: DistanceUnitPreference) {
        preferences.edit { putString(KEY_DISTANCE_UNIT, preference.name) }
    }

    internal fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val PREFERENCES_NAME = "distance-unit-settings"
        const val KEY_DISTANCE_UNIT = "distance-unit"
    }
}
