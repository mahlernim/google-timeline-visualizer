package dev.mahlernim.timelinevisualizer.trips

import java.time.LocalDate
import java.time.YearMonth
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod

object RecapPeriodRules {
    fun yearly(startYear: Int, endYear: Int? = null): Pair<LocalDate, LocalDate> {
        val normalizedEnd = (endYear ?: startYear).coerceAtLeast(startYear)
        return LocalDate.of(startYear, 1, 1) to LocalDate.of(normalizedEnd, 12, 31)
    }

    fun yearlyTimelinePeriod(startYear: Int, endYear: Int? = null): TimelinePeriod {
        val normalizedEnd = (endYear ?: startYear).coerceAtLeast(startYear)
        return TimelinePeriod(
            start = YearMonth.of(startYear, 1),
            endInclusive = YearMonth.of(normalizedEnd, 12),
        )
    }

    fun monthly(start: YearMonth, end: YearMonth? = null): Pair<LocalDate, LocalDate> {
        val normalizedEnd = (end ?: start).coerceAtLeast(start)
        return start.atDay(1) to normalizedEnd.atEndOfMonth()
    }

    fun customDefault(
        availableStart: LocalDate,
        availableEnd: LocalDate,
        today: LocalDate,
    ): Pair<LocalDate, LocalDate> {
        require(availableEnd >= availableStart)
        val currentYearStart = LocalDate.of(today.year, 1, 1)
        val start = if (currentYearStart in availableStart..availableEnd) currentYearStart else availableStart
        return start to availableEnd
    }
}
