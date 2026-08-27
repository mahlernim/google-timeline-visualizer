package dev.mahlernim.timelinevisualizer.journal.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mahlernim.timelinevisualizer.MainActivity
import dev.mahlernim.timelinevisualizer.R

object JournalReminderNotifications {
    const val CHANNEL_ID = "journal_reminders"
    const val NOTIFICATION_ID = 4208

    fun show(
        context: Context,
        journalId: String,
        anchorEpochMillis: Long,
        stage: JournalReminderStage,
    ): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) return false
        createChannel(context)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return false
        val title = when (stage) {
            JournalReminderStage.DAY_24 -> context.getString(R.string.journal_reminder_day24_title)
            JournalReminderStage.DAY_29 -> context.getString(R.string.journal_reminder_day29_title)
        }
        val detail = when (stage) {
            JournalReminderStage.DAY_24 -> context.getString(R.string.journal_reminder_day24_detail)
            JournalReminderStage.DAY_29 -> context.getString(R.string.journal_reminder_day29_detail)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openJournalPendingIntent(context))
            .addAction(
                0,
                context.getString(R.string.update_journal),
                openJournalPendingIntent(context),
            )
            .addAction(
                0,
                context.getString(R.string.remind_in_three_days),
                receiverPendingIntent(context, JournalReminderReceiver.ACTION_SNOOZE, journalId, anchorEpochMillis, stage, 4301),
            )
            .addAction(
                0,
                context.getString(R.string.turn_off),
                receiverPendingIntent(context, JournalReminderReceiver.ACTION_DISABLE, journalId, anchorEpochMillis, stage, 4302),
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
        return true
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.journal_reminder_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.journal_reminder_channel_summary)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun openJournalPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        4300,
        Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_JOURNAL
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun receiverPendingIntent(
        context: Context,
        action: String,
        journalId: String,
        anchorEpochMillis: Long,
        stage: JournalReminderStage,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, JournalReminderReceiver::class.java).apply {
            this.action = action
            putExtra(JournalReminderReceiver.EXTRA_JOURNAL_ID, journalId)
            putExtra(JournalReminderReceiver.EXTRA_ANCHOR, anchorEpochMillis)
            putExtra(JournalReminderReceiver.EXTRA_STAGE, stage.name)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
