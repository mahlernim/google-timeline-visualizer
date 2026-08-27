package dev.mahlernim.timelinevisualizer.journal.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class JournalReminderCoordinator(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val applicationContext = context.applicationContext

    fun schedule(journalId: String, anchorEpochMillis: Long, replace: Boolean = false) {
        val state = JournalReminderStateStore(applicationContext).state(journalId)
        val currentTime = now()
        val ageDays = JournalFreshnessPolicy.evaluate(anchorEpochMillis, currentTime, zoneId).ageDays
        JournalReminderStage.entries.forEach { stage ->
            val alreadyNotified = when (stage) {
                JournalReminderStage.DAY_24 -> state.day24Notified
                JournalReminderStage.DAY_29 -> state.day29Notified
            }
            if (state.anchorEpochMillis == anchorEpochMillis && alreadyNotified) return@forEach
            if (stage == JournalReminderStage.DAY_24 && ageDays != null && ageDays >= JournalReminderStage.DAY_29.thresholdDay) {
                workManager.cancelUniqueWork(workName(journalId, stage))
                return@forEach
            }
            if (stage == JournalReminderStage.DAY_29 && ageDays != null && ageDays > FINAL_SCHEDULE_DAY && state.snoozedUntilEpochMillis == null) {
                workManager.cancelUniqueWork(workName(journalId, stage))
                return@forEach
            }
            val threshold = JournalFreshnessPolicy.reminderTargetEpochMillis(
                anchorEpochMillis,
                stage.thresholdDay,
                zoneId,
            )
            val snoozeApplies = state.snoozedStage == stage ||
                (state.snoozedStage == JournalReminderStage.DAY_24 && stage == JournalReminderStage.DAY_29)
            val target = maxOf(threshold, state.snoozedUntilEpochMillis?.takeIf { snoozeApplies } ?: Long.MIN_VALUE)
            val input = Data.Builder()
                .putString(JournalReminderWorker.KEY_JOURNAL_ID, journalId)
                .putLong(JournalReminderWorker.KEY_ANCHOR, anchorEpochMillis)
                .putString(JournalReminderWorker.KEY_STAGE, stage.name)
                .build()
            val request = OneTimeWorkRequestBuilder<JournalReminderWorker>()
                .setInputData(input)
                .setInitialDelay((target - currentTime).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(
                workName(journalId, stage),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    fun cancel(journalId: String) {
        JournalReminderStage.entries.forEach { stage -> workManager.cancelUniqueWork(workName(journalId, stage)) }
        JournalReminderNotifications.cancel(applicationContext)
    }

    companion object {
        private const val TAG = "journal-reminder"
        private const val FINAL_SCHEDULE_DAY = 30L
        fun workName(journalId: String, stage: JournalReminderStage): String =
            "journal-reminder:$journalId:${stage.thresholdDay}"
    }
}
