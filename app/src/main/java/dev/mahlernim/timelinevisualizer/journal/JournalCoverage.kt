package dev.mahlernim.timelinevisualizer.journal

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Counts the local calendar dates touched by an inclusive stored time range. */
internal fun inclusiveCalendarDayCount(
    startEpochMillis: Long?,
    endEpochMillis: Long?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Int {
    if (startEpochMillis == null || endEpochMillis == null || endEpochMillis < startEpochMillis) return 0
    val startDate = Instant.ofEpochMilli(startEpochMillis).atZone(zoneId).toLocalDate()
    val endDate = Instant.ofEpochMilli(endEpochMillis).atZone(zoneId).toLocalDate()
    return (ChronoUnit.DAYS.between(startDate, endDate) + 1)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}
