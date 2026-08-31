package dev.mahlernim.timelinevisualizer.update

import android.content.Context
import androidx.core.content.edit

class UpdatePromptStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val lastSuccessfulCheckMillis: Long
        get() = preferences.getLong(KEY_LAST_SUCCESSFUL_CHECK, 0L)

    val lastAttemptMillis: Long
        get() = preferences.getLong(KEY_LAST_ATTEMPT, 0L)

    val dismissedVersionCode: Int
        get() = preferences.getInt(KEY_DISMISSED_VERSION, 0)

    val dismissedAtMillis: Long
        get() = preferences.getLong(KEY_DISMISSED_AT, 0L)

    fun recordAttempt(nowMillis: Long) {
        preferences.edit { putLong(KEY_LAST_ATTEMPT, nowMillis) }
    }

    fun recordSuccess(nowMillis: Long) {
        preferences.edit { putLong(KEY_LAST_SUCCESSFUL_CHECK, nowMillis) }
    }

    fun dismiss(versionCode: Int, nowMillis: Long) {
        preferences.edit {
            putInt(KEY_DISMISSED_VERSION, versionCode)
            putLong(KEY_DISMISSED_AT, nowMillis)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "app_update_prompt"
        const val KEY_LAST_SUCCESSFUL_CHECK = "last_successful_check_millis"
        const val KEY_LAST_ATTEMPT = "last_attempt_millis"
        const val KEY_DISMISSED_VERSION = "dismissed_version_code"
        const val KEY_DISMISSED_AT = "dismissed_at_millis"
    }
}
