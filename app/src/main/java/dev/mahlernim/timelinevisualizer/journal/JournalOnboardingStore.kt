package dev.mahlernim.timelinevisualizer.journal

import android.content.Context
import androidx.core.content.edit

class JournalOnboardingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isCompleted(): Boolean = preferences.getInt(COMPLETED_VERSION, 0) >= CURRENT_VERSION

    fun complete() {
        preferences.edit(commit = true) { putInt(COMPLETED_VERSION, CURRENT_VERSION) }
    }

    companion object {
        const val PREFERENCES_NAME = "journal_onboarding"
        const val CURRENT_VERSION = 1
        private const val COMPLETED_VERSION = "completed_version"
    }
}
