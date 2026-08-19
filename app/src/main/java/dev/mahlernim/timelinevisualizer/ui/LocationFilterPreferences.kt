package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.core.content.edit
import dev.mahlernim.timelinevisualizer.data.LocationFilterMode

class LocationFilterPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): LocationFilterMode = runCatching {
        enumValueOf<LocationFilterMode>(
            preferences.getString(KEY_MODE, null) ?: return LocationFilterMode.CONSERVATIVE,
        )
    }.getOrDefault(LocationFilterMode.CONSERVATIVE)

    fun save(mode: LocationFilterMode) {
        preferences.edit { putString(KEY_MODE, mode.name) }
    }

    fun reset(): LocationFilterMode {
        preferences.edit { clear() }
        return LocationFilterMode.CONSERVATIVE
    }

    private companion object {
        const val PREFERENCES_NAME = "timeline-filter-settings"
        const val KEY_MODE = "location-filter-mode"
    }
}
