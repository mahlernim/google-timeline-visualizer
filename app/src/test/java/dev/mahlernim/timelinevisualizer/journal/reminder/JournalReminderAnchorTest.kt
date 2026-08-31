package dev.mahlernim.timelinevisualizer.journal.reminder

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalEntity
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalReminderAnchorTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val journalId = "captured-anchor"
    private var anchor = 0L

    @Before
    fun setUp() {
        context.deleteDatabase("travel-journal.db")
        context.getSharedPreferences(JournalReminderStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(
            JournalReminderNotifications.CHANNEL_ID,
        )
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
        anchor = System.currentTimeMillis() - Duration.ofDays(25).toMillis()
        val database = JournalDatabase.open(context)
        runBlocking {
            JournalRepository(database).createJournal(
                JournalEntity(
                    id = journalId,
                    name = "Travel Journal",
                    isPrimary = true,
                    createdAtEpochMillis = anchor,
                    detailedCapturedThroughEpochMillis = anchor,
                    detailedUsableThroughEpochMillis = anchor - Duration.ofDays(1).toMillis(),
                    reminderEligible = true,
                    reminderEnabled = true,
                ),
            )
        }
        database.close()
        JournalReminderStateStore(context).resetForAdvance(journalId, anchor)
    }

    @After
    fun tearDown() {
        JournalReminderNotifications.cancel(context)
        JournalReminderStateStore(context).clear(journalId)
        context.deleteDatabase("travel-journal.db")
    }

    @Test
    fun workerValidatesAgainstCapturedDetailRatherThanFilteredRouteDetail() = runBlocking {
        val worker = TestListenableWorkerBuilder<JournalReminderWorker>(context)
            .setInputData(reminderData())
            .build()

        worker.doWork()

        assertTrue(JournalReminderStateStore(context).state(journalId).day24Notified)
    }

    @Test
    fun snoozeReceiverValidatesAgainstCapturedDetailRatherThanFilteredRouteDetail() {
        JournalReminderReceiver().onReceive(
            context,
            Intent(JournalReminderReceiver.ACTION_SNOOZE).apply {
                putExtra(JournalReminderReceiver.EXTRA_JOURNAL_ID, journalId)
                putExtra(JournalReminderReceiver.EXTRA_ANCHOR, anchor)
                putExtra(JournalReminderReceiver.EXTRA_STAGE, JournalReminderStage.DAY_24.name)
            },
        )

        repeat(200) {
            if (JournalReminderStateStore(context).state(journalId).snoozedUntilEpochMillis != null) return
            Thread.sleep(10)
        }
        error("Snooze receiver did not accept the captured-detail anchor")
    }

    private fun reminderData(): Data = Data.Builder()
        .putString(JournalReminderWorker.KEY_JOURNAL_ID, journalId)
        .putLong(JournalReminderWorker.KEY_ANCHOR, anchor)
        .putString(JournalReminderWorker.KEY_STAGE, JournalReminderStage.DAY_24.name)
        .build()
}
