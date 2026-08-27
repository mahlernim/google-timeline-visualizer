package dev.mahlernim.timelinevisualizer.journal.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration

class JournalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val journalId = intent.getStringExtra(EXTRA_JOURNAL_ID) ?: return
        val anchor = intent.getLongExtra(EXTRA_ANCHOR, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE } ?: return
        val stage = intent.getStringExtra(EXTRA_STAGE)
            ?.let { runCatching { JournalReminderStage.valueOf(it) }.getOrNull() }
            ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = JournalDatabase.open(context)
                val journal = try {
                    JournalRepository(database).journal(journalId)
                } finally {
                    database.close()
                } ?: return@launch
                when (intent.action) {
                    ACTION_SNOOZE -> {
                        if (!journal.reminderEnabled || journal.detailedUsableThroughEpochMillis != anchor) {
                            return@launch
                        }
                        val until = System.currentTimeMillis() + Duration.ofDays(SNOOZE_DAYS).toMillis()
                        JournalReminderStateStore(context).snooze(journalId, anchor, stage, until)
                        JournalReminderNotifications.cancel(context)
                        JournalReminderCoordinator(context).schedule(journalId, anchor, replace = true)
                    }
                    ACTION_DISABLE -> {
                        val database = JournalDatabase.open(context)
                        try {
                            JournalRepository(database).setReminderEnabled(journalId, enabled = false)
                        } finally {
                            database.close()
                        }
                        JournalReminderStateStore(context).clear(journalId)
                        JournalReminderCoordinator(context).cancel(journalId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "dev.mahlernim.timelinevisualizer.action.SNOOZE_JOURNAL_REMINDER"
        const val ACTION_DISABLE = "dev.mahlernim.timelinevisualizer.action.DISABLE_JOURNAL_REMINDERS"
        const val EXTRA_JOURNAL_ID = "journal_id"
        const val EXTRA_ANCHOR = "anchor_epoch_millis"
        const val EXTRA_STAGE = "reminder_stage"
        private const val SNOOZE_DAYS = 3L
    }
}
