package dev.mahlernim.timelinevisualizer.journal.reminder

object JournalReminderDecision {
    fun shouldNotify(
        reminderEligible: Boolean,
        reminderEnabled: Boolean,
        currentAnchorEpochMillis: Long?,
        requestedAnchorEpochMillis: Long,
        stage: JournalReminderStage,
        state: JournalReminderState,
        nowEpochMillis: Long,
    ): Boolean {
        if (!reminderEligible || !reminderEnabled) return false
        if (currentAnchorEpochMillis != requestedAnchorEpochMillis) return false
        if (state.anchorEpochMillis != requestedAnchorEpochMillis) return false
        val snoozeApplies = state.snoozedStage == stage ||
            (state.snoozedStage == JournalReminderStage.DAY_24 && stage == JournalReminderStage.DAY_29)
        val snoozedUntil = state.snoozedUntilEpochMillis?.takeIf { snoozeApplies }
        if (snoozedUntil != null && snoozedUntil > nowEpochMillis) return false
        val alreadyNotified = when (stage) {
            JournalReminderStage.DAY_24 -> state.day24Notified
            JournalReminderStage.DAY_29 -> state.day29Notified
        }
        if (alreadyNotified) return false
        val ageDays = JournalFreshnessPolicy.evaluate(requestedAnchorEpochMillis, nowEpochMillis).ageDays
        if (stage == JournalReminderStage.DAY_24 && ageDays != null && ageDays >= JournalReminderStage.DAY_29.thresholdDay) {
            return false
        }
        if (snoozedUntil != null) return true
        return when (stage) {
            JournalReminderStage.DAY_24 -> ageDays in 24L..28L
            JournalReminderStage.DAY_29 -> ageDays in 29L..FINAL_DELIVERY_GRACE_DAY
        }
    }

    private const val FINAL_DELIVERY_GRACE_DAY = 33L
}
