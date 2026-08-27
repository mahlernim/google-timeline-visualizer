package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.core.content.edit

class TimelineDisplayPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun simplifyRouteDetail(): Boolean = preferences.getBoolean(KEY_SIMPLIFY_ROUTE_DETAIL, false)

    fun setSimplifyRouteDetail(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_SIMPLIFY_ROUTE_DETAIL, enabled) }
    }

    fun keepPastRoutesVisible(): Boolean = preferences.getBoolean(KEY_KEEP_PAST_ROUTES_VISIBLE, false)

    fun setKeepPastRoutesVisible(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_KEEP_PAST_ROUTES_VISIBLE, enabled) }
    }

    fun clear() = preferences.edit { clear() }

    private companion object {
        const val PREFERENCES_NAME = "timeline-display-settings"
        const val KEY_SIMPLIFY_ROUTE_DETAIL = "simplify-route-detail"
        const val KEY_KEEP_PAST_ROUTES_VISIBLE = "keep-past-routes-visible"
    }
}
