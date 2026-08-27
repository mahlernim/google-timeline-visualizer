package dev.mahlernim.timelinevisualizer.journal.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class JournalFreshnessPolicyTest {
    private val zone = ZoneId.of("UTC")
    private val captured = LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun usesCalendarDayFreshnessBoundaries() {
        assertState(13, JournalFreshnessState.CURRENT)
        assertState(14, JournalFreshnessState.GENTLE)
        assertState(20, JournalFreshnessState.GENTLE)
        assertState(21, JournalFreshnessState.UPDATE_DUE)
        assertState(26, JournalFreshnessState.UPDATE_DUE)
        assertState(27, JournalFreshnessState.AT_RISK)
        assertState(30, JournalFreshnessState.AT_RISK)
        assertState(31, JournalFreshnessState.OVERDUE)
    }

    @Test
    fun handlesMissingAndFutureDetailSafely() {
        assertEquals(
            JournalFreshnessState.NO_DETAIL,
            JournalFreshnessPolicy.evaluate(null, captured, zone).state,
        )
        val future = LocalDate.of(2026, 8, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val freshness = JournalFreshnessPolicy.evaluate(future, captured, zone)
        assertEquals(JournalFreshnessState.CURRENT, freshness.state)
        assertEquals(0L, freshness.ageDays)
    }

    @Test
    fun recentImportEligibilityIsBounded() {
        assertTrue(JournalFreshnessPolicy.isRecent(captured, atDay(7), zone))
        assertFalse(JournalFreshnessPolicy.isRecent(captured, atDay(8), zone))
        assertFalse(JournalFreshnessPolicy.isRecent(null, atDay(0), zone))
    }

    @Test
    fun reminderTargetUsesQuietLocalTimeAcrossDst() {
        val newYork = ZoneId.of("America/New_York")
        val beforeDst = LocalDate.of(2026, 3, 1).atStartOfDay(newYork).toInstant().toEpochMilli()
        val target = JournalFreshnessPolicy.reminderTargetEpochMillis(beforeDst, 14, newYork)
        val local = java.time.Instant.ofEpochMilli(target).atZone(newYork)
        assertEquals(LocalDate.of(2026, 3, 15), local.toLocalDate())
        assertEquals(JournalFreshnessPolicy.QUIET_REMINDER_TIME, local.toLocalTime())
    }

    private fun assertState(day: Long, expected: JournalFreshnessState) {
        val result = JournalFreshnessPolicy.evaluate(captured, atDay(day), zone)
        assertEquals(expected, result.state)
        assertEquals(day, result.ageDays)
    }

    private fun atDay(day: Long): Long = LocalDate.of(2026, 8, 1)
        .plusDays(day)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}
