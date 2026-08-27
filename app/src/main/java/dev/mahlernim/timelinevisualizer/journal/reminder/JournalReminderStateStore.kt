package dev.mahlernim.timelinevisualizer.journal.reminder

import android.content.Context
import androidx.core.content.edit

enum class JournalReminderStage(val thresholdDay: Long) {
    DAY_24(JournalFreshnessPolicy.FIRST_REMINDER_DAY),
    DAY_29(JournalFreshnessPolicy.FINAL_REMINDER_DAY),
}

data class JournalReminderState(
    val anchorEpochMillis: Long?,
    val day24Notified: Boolean,
    val day29Notified: Boolean,
    val snoozedUntilEpochMillis: Long?,
    val snoozedStage: JournalReminderStage?,
)

class JournalReminderStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun state(journalId: String): JournalReminderState = JournalReminderState(
        anchorEpochMillis = preferences.longOrNull(key(journalId, ANCHOR)),
        day24Notified = preferences.getBoolean(key(journalId, DAY_24_NOTIFIED), false),
        day29Notified = preferences.getBoolean(key(journalId, DAY_29_NOTIFIED), false),
        snoozedUntilEpochMillis = preferences.longOrNull(key(journalId, SNOOZED_UNTIL)),
        snoozedStage = preferences.getString(key(journalId, SNOOZED_STAGE), null)
            ?.let { runCatching { JournalReminderStage.valueOf(it) }.getOrNull() },
    )

    fun resetForAdvance(journalId: String, anchorEpochMillis: Long) {
        preferences.edit(commit = true) {
            putLong(key(journalId, ANCHOR), anchorEpochMillis)
            remove(key(journalId, DAY_24_NOTIFIED))
            remove(key(journalId, DAY_29_NOTIFIED))
            remove(key(journalId, SNOOZED_UNTIL))
            remove(key(journalId, SNOOZED_STAGE))
        }
    }

    fun markNotified(journalId: String, anchorEpochMillis: Long, stage: JournalReminderStage) {
        preferences.edit(commit = true) {
            putLong(key(journalId, ANCHOR), anchorEpochMillis)
            putBoolean(key(journalId, stage.notifiedSuffix), true)
            remove(key(journalId, SNOOZED_UNTIL))
            remove(key(journalId, SNOOZED_STAGE))
        }
    }

    fun snooze(
        journalId: String,
        anchorEpochMillis: Long,
        stage: JournalReminderStage,
        untilEpochMillis: Long,
    ) {
        preferences.edit(commit = true) {
            putLong(key(journalId, ANCHOR), anchorEpochMillis)
            putBoolean(key(journalId, stage.notifiedSuffix), false)
            putLong(key(journalId, SNOOZED_UNTIL), untilEpochMillis)
            putString(key(journalId, SNOOZED_STAGE), stage.name)
        }
    }

    fun clear(journalId: String) {
        preferences.edit(commit = true) {
            remove(key(journalId, ANCHOR))
            remove(key(journalId, DAY_24_NOTIFIED))
            remove(key(journalId, DAY_29_NOTIFIED))
            remove(key(journalId, SNOOZED_UNTIL))
            remove(key(journalId, SNOOZED_STAGE))
        }
    }

    fun canNotify(
        journalId: String,
        anchorEpochMillis: Long,
        stage: JournalReminderStage,
        nowEpochMillis: Long,
    ): Boolean {
        val state = state(journalId)
        if (state.anchorEpochMillis != anchorEpochMillis) return false
        if (state.snoozedUntilEpochMillis?.let { it > nowEpochMillis } == true && state.appliesSnoozeTo(stage)) return false
        return when (stage) {
            JournalReminderStage.DAY_24 -> !state.day24Notified
            JournalReminderStage.DAY_29 -> !state.day29Notified
        }
    }

    private val JournalReminderStage.notifiedSuffix: String
        get() = when (this) {
            JournalReminderStage.DAY_24 -> DAY_24_NOTIFIED
            JournalReminderStage.DAY_29 -> DAY_29_NOTIFIED
        }

    private fun JournalReminderState.appliesSnoozeTo(stage: JournalReminderStage): Boolean =
        snoozedStage == stage || (snoozedStage == JournalReminderStage.DAY_24 && stage == JournalReminderStage.DAY_29)

    private fun key(journalId: String, suffix: String): String = "$journalId.$suffix"

    private fun android.content.SharedPreferences.longOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    companion object {
        const val PREFERENCES_NAME = "journal_reminders"
        private const val ANCHOR = "anchor"
        private const val DAY_24_NOTIFIED = "day24_notified"
        private const val DAY_29_NOTIFIED = "day29_notified"
        private const val SNOOZED_UNTIL = "snoozed_until"
        private const val SNOOZED_STAGE = "snoozed_stage"
    }
}
