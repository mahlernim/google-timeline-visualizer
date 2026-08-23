package dev.mahlernim.timelinevisualizer.trips

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class RecapPeriodRulesTest {
    @Test
    fun yearlyRangeUsesCompleteCalendarYearsAndMovesInvalidEndForward() {
        assertEquals(
            LocalDate.parse("2024-01-01") to LocalDate.parse("2026-12-31"),
            RecapPeriodRules.yearly(2024, 2026),
        )
        assertEquals(
            LocalDate.parse("2026-01-01") to LocalDate.parse("2026-12-31"),
            RecapPeriodRules.yearly(2026, 2024),
        )
        assertEquals(
            YearMonth.of(2026, 1),
            RecapPeriodRules.yearlyTimelinePeriod(2026).start,
        )
        assertEquals(
            YearMonth.of(2026, 12),
            RecapPeriodRules.yearlyTimelinePeriod(2026).endInclusive,
        )
    }

    @Test
    fun monthlyRangeUsesRealMonthEndsIncludingLeapDay() {
        assertEquals(
            LocalDate.parse("2024-02-01") to LocalDate.parse("2024-02-29"),
            RecapPeriodRules.monthly(YearMonth.of(2024, 2)),
        )
        assertEquals(
            LocalDate.parse("2025-11-01") to LocalDate.parse("2026-02-28"),
            RecapPeriodRules.monthly(YearMonth.of(2025, 11), YearMonth.of(2026, 2)),
        )
    }

    @Test
    fun customDefaultUsesCurrentYearOnlyWhenCovered() {
        assertEquals(
            LocalDate.parse("2026-01-01") to LocalDate.parse("2026-08-23"),
            RecapPeriodRules.customDefault(
                LocalDate.parse("2020-01-01"),
                LocalDate.parse("2026-08-23"),
                LocalDate.parse("2026-08-23"),
            ),
        )
        assertEquals(
            LocalDate.parse("2020-03-04") to LocalDate.parse("2022-05-06"),
            RecapPeriodRules.customDefault(
                LocalDate.parse("2020-03-04"),
                LocalDate.parse("2022-05-06"),
                LocalDate.parse("2026-08-23"),
            ),
        )
    }
}
