package dev.mahlernim.timelinevisualizer.journal.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class JournalReminderDecisionTest {
    private val anchor = 1_700_000_000_000L

    @Test
    fun sendsEachStageOnlyInsideItsObservedWindow() {
        assertTrue(decide(JournalReminderStage.DAY_24, day = 24))
        assertTrue(decide(JournalReminderStage.DAY_24, day = 28))
        assertFalse(decide(JournalReminderStage.DAY_24, day = 29))
        assertTrue(decide(JournalReminderStage.DAY_29, day = 29))
        assertTrue(decide(JournalReminderStage.DAY_29, day = 30))
        assertTrue(decide(JournalReminderStage.DAY_29, day = 31))
        assertFalse(decide(JournalReminderStage.DAY_29, day = 34))
    }

    @Test
    fun rejectsDisabledStaleSnoozedAndAlreadySentWork() {
        val now = atDay(24)
        assertFalse(decide(JournalReminderStage.DAY_24, 24, enabled = false))
        assertFalse(decide(JournalReminderStage.DAY_24, 24, currentAnchor = anchor + 1))
        assertFalse(decide(JournalReminderStage.DAY_24, 24, stateAnchor = anchor + 1))
        assertFalse(decide(JournalReminderStage.DAY_24, 24, snoozedUntil = now + 1))
        assertFalse(decide(JournalReminderStage.DAY_24, 24, day24Sent = true))
        assertTrue(decide(JournalReminderStage.DAY_29, 32, snoozedUntil = atDay(32)))
        assertFalse(decide(JournalReminderStage.DAY_24, 31, snoozedUntil = atDay(31)))
        assertTrue(
            decide(
                JournalReminderStage.DAY_29,
                31,
                snoozedUntil = atDay(31),
                snoozedStage = JournalReminderStage.DAY_24,
            ),
        )
    }

    private fun decide(
        stage: JournalReminderStage,
        day: Long,
        enabled: Boolean = true,
        currentAnchor: Long? = anchor,
        stateAnchor: Long? = anchor,
        snoozedUntil: Long? = null,
        day24Sent: Boolean = false,
        snoozedStage: JournalReminderStage? = snoozedUntil?.let { stage },
    ): Boolean = JournalReminderDecision.shouldNotify(
        reminderEligible = true,
        reminderEnabled = enabled,
        currentAnchorEpochMillis = currentAnchor,
        requestedAnchorEpochMillis = anchor,
        stage = stage,
        state = JournalReminderState(
            anchorEpochMillis = stateAnchor,
            day24Notified = day24Sent,
            day29Notified = false,
            snoozedUntilEpochMillis = snoozedUntil,
            snoozedStage = snoozedStage,
        ),
        nowEpochMillis = atDay(day),
    )

    private fun atDay(day: Long): Long = anchor + Duration.ofDays(day).toMillis()
}
