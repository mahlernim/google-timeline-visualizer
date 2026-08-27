package dev.mahlernim.timelinevisualizer.journal.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mahlernim.timelinevisualizer.BuildConfig
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase

class JournalReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.IS_JOURNAL_LAB) return Result.success()
        val journalId = inputData.getString(KEY_JOURNAL_ID) ?: return Result.failure()
        val anchor = inputData.getLong(KEY_ANCHOR, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }
            ?: return Result.failure()
        val stage = inputData.getString(KEY_STAGE)
            ?.let { runCatching { JournalReminderStage.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val database = JournalDatabase.open(applicationContext)
        val journal = try {
            database.journalDao().journal(journalId)
        } finally {
            database.close()
        } ?: return Result.success()
        val now = System.currentTimeMillis()
        val stateStore = JournalReminderStateStore(applicationContext)
        if (!JournalReminderDecision.shouldNotify(
                reminderEligible = journal.reminderEligible,
                reminderEnabled = journal.reminderEnabled,
                currentAnchorEpochMillis = journal.detailedUsableThroughEpochMillis,
                requestedAnchorEpochMillis = anchor,
                stage = stage,
                state = stateStore.state(journalId),
                nowEpochMillis = now,
            )
        ) return Result.success()
        if (JournalReminderNotifications.show(applicationContext, journalId, anchor, stage)) {
            stateStore.markNotified(journalId, anchor, stage)
        }
        return Result.success()
    }

    companion object {
        const val KEY_JOURNAL_ID = "journal_id"
        const val KEY_ANCHOR = "anchor_epoch_millis"
        const val KEY_STAGE = "reminder_stage"
    }
}
