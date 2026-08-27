package dev.mahlernim.timelinevisualizer.journal.reminder

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class JournalFreshnessState {
    NO_DETAIL,
    CURRENT,
    GENTLE,
    UPDATE_DUE,
    AT_RISK,
    OVERDUE,
}

data class JournalFreshness(
    val state: JournalFreshnessState,
    val ageDays: Long?,
)

object JournalFreshnessPolicy {
    const val GENTLE_DAY = 14L
    const val FIRST_REMINDER_DAY = 24L
    const val FINAL_REMINDER_DAY = 29L
    const val OBSERVED_WINDOW_LAST_DAY = 30L
    const val RECENT_IMPORT_DAYS = 7L
    val QUIET_REMINDER_TIME: LocalTime = LocalTime.of(10, 0)

    fun evaluate(
        detailedCapturedThroughEpochMillis: Long?,
        nowEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): JournalFreshness {
        val captured = detailedCapturedThroughEpochMillis
            ?: return JournalFreshness(JournalFreshnessState.NO_DETAIL, null)
        val capturedDate = Instant.ofEpochMilli(captured).atZone(zoneId).toLocalDate()
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        val ageDays = ChronoUnit.DAYS.between(capturedDate, today).coerceAtLeast(0L)
        val state = when {
            ageDays < GENTLE_DAY -> JournalFreshnessState.CURRENT
            ageDays < 21L -> JournalFreshnessState.GENTLE
            ageDays < 27L -> JournalFreshnessState.UPDATE_DUE
            ageDays <= OBSERVED_WINDOW_LAST_DAY -> JournalFreshnessState.AT_RISK
            else -> JournalFreshnessState.OVERDUE
        }
        return JournalFreshness(state, ageDays)
    }

    fun isRecent(
        detailedCapturedThroughEpochMillis: Long?,
        nowEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val freshness = evaluate(detailedCapturedThroughEpochMillis, nowEpochMillis, zoneId)
        return freshness.ageDays != null && freshness.ageDays <= RECENT_IMPORT_DAYS
    }

    fun reminderTargetEpochMillis(
        detailedCapturedThroughEpochMillis: Long,
        reminderDay: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long = Instant.ofEpochMilli(detailedCapturedThroughEpochMillis)
        .atZone(zoneId)
        .toLocalDate()
        .plusDays(reminderDay)
        .atTime(QUIET_REMINDER_TIME)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
}
