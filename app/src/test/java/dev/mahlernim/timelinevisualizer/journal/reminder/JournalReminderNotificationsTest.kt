package dev.mahlernim.timelinevisualizer.journal.reminder

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalReminderNotificationsTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.deleteNotificationChannel(JournalReminderNotifications.CHANNEL_ID)
        JournalReminderNotifications.cancel(context)
    }

    @After
    fun tearDown() {
        JournalReminderNotifications.cancel(context)
    }

    @Test
    fun createsSeparateQuietPrivateChannel() {
        JournalReminderNotifications.createChannel(context)

        val channel = requireNotNull(manager.getNotificationChannel(JournalReminderNotifications.CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertEquals(context.getString(R.string.journal_reminder_channel), channel.name)
    }

    @Test
    fun notificationIsPrivateAndContainsNoDestination() {
        assertTrue(
            JournalReminderNotifications.show(
                context,
                journalId = "primary",
                anchorEpochMillis = 1_700_000_000_000L,
                stage = JournalReminderStage.DAY_29,
            ),
        )
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(NotificationCompatCategory.REMINDER, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(text.contains("Seoul", ignoreCase = true))
        assertFalse(text.contains("trip to", ignoreCase = true))
        assertTrue(text.contains("29"))
    }

    @Test
    fun blockedChannelDoesNotReportNotificationAsDelivered() {
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                JournalReminderNotifications.CHANNEL_ID,
                "Blocked",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        assertFalse(
            JournalReminderNotifications.show(
                context,
                journalId = "primary",
                anchorEpochMillis = 1_700_000_000_000L,
                stage = JournalReminderStage.DAY_29,
            ),
        )
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    private object NotificationCompatCategory {
        const val REMINDER = "reminder"
    }
}
