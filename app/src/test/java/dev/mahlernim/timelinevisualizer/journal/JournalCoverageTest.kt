package dev.mahlernim.timelinevisualizer.journal

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalCoverageTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun missingOrReversedRangeHasNoCoveredDays() {
        assertEquals(0, inclusiveCalendarDayCount(null, null, seoul))
        assertEquals(0, inclusiveCalendarDayCount(2, 1, seoul))
    }

    @Test
    fun pointsOnOneLocalDateCoverOneDay() {
        assertEquals(
            1,
            inclusiveCalendarDayCount(
                Instant.parse("2026-01-01T15:30:00Z").toEpochMilli(),
                Instant.parse("2026-01-02T14:30:00Z").toEpochMilli(),
                seoul,
            ),
        )
    }

    @Test
    fun rangeCountsInclusiveLocalCalendarDates() {
        assertEquals(
            3,
            inclusiveCalendarDayCount(
                Instant.parse("2026-01-01T14:59:00Z").toEpochMilli(),
                Instant.parse("2026-01-02T15:01:00Z").toEpochMilli(),
                seoul,
            ),
        )
    }
}
