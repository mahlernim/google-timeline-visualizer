package dev.mahlernim.timelinevisualizer.journal.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalReminderStateStoreTest {
    private lateinit var context: Context
    private lateinit var store: JournalReminderStateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(JournalReminderStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = JournalReminderStateStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(JournalReminderStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun advanceResetsSentAndSnoozeState() {
        store.resetForAdvance(JOURNAL_ID, 100L)
        store.markNotified(JOURNAL_ID, 100L, JournalReminderStage.DAY_24)
        store.snooze(JOURNAL_ID, 100L, JournalReminderStage.DAY_24, 500L)
        store.resetForAdvance(JOURNAL_ID, 200L)

        val state = store.state(JOURNAL_ID)
        assertEquals(200L, state.anchorEpochMillis)
        assertFalse(state.day24Notified)
        assertFalse(state.day29Notified)
        assertNull(state.snoozedUntilEpochMillis)
        assertNull(state.snoozedStage)
    }

    @Test
    fun snoozeRecordsItsStageAndDoesNotBlockEarlierStageChecksGlobally() {
        store.resetForAdvance(JOURNAL_ID, 100L)
        store.snooze(JOURNAL_ID, 100L, JournalReminderStage.DAY_29, 500L)

        val state = store.state(JOURNAL_ID)
        assertEquals(JournalReminderStage.DAY_29, state.snoozedStage)
        assertTrue(store.canNotify(JOURNAL_ID, 100L, JournalReminderStage.DAY_24, 200L))
        assertFalse(store.canNotify(JOURNAL_ID, 100L, JournalReminderStage.DAY_29, 200L))
    }

    @Test
    fun notifiedStageIsIdempotentAndClearRemovesState() {
        store.resetForAdvance(JOURNAL_ID, 100L)
        assertTrue(store.canNotify(JOURNAL_ID, 100L, JournalReminderStage.DAY_29, 200L))
        store.markNotified(JOURNAL_ID, 100L, JournalReminderStage.DAY_29)
        assertFalse(store.canNotify(JOURNAL_ID, 100L, JournalReminderStage.DAY_29, 200L))
        store.clear(JOURNAL_ID)
        assertNull(store.state(JOURNAL_ID).anchorEpochMillis)
    }

    companion object {
        private const val JOURNAL_ID = "journal-test"
    }
}
