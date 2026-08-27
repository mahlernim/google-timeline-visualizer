package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Builds a selected Journey without reconnecting route sections separated by a Journal gap. */
fun JournalRoute.journeyForRange(
    period: TimelinePeriod,
    zone: ZoneId = ZoneId.systemDefault(),
): Journey = journeyFromSpans(period) { point ->
    val date = point.instant.atZone(zone)
    val month = YearMonth.of(date.year, date.monthValue)
    month >= period.start && month <= period.endInclusive
}

/** Builds an exact-date Journey without reconnecting route sections separated by a Journal gap. */
fun JournalRoute.journeyForDateRange(
    start: LocalDate,
    endInclusive: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Journey {
    require(endInclusive >= start)
    return journeyFromSpans(
        period = TimelinePeriod(YearMonth.from(start), YearMonth.from(endInclusive)),
    ) { point ->
        val date = point.instant.atZone(zone).toLocalDate()
        date >= start && date <= endInclusive
    }
}

private fun JournalRoute.journeyFromSpans(
    period: TimelinePeriod,
    include: (GeoPoint) -> Boolean,
): Journey {
    val sections = mutableListOf<MutableList<GeoPoint>>()
    val inferredTransferBeforePointIndices = mutableListOf<Int>()
    var pointCount = 0
    var current = mutableListOf<GeoPoint>()
    spans.forEach { span ->
        if (span.source == RouteSource.GAP) {
            if (current.isNotEmpty()) sections.add(current)
            current = mutableListOf()
        } else {
            span.points.asSequence().filter(include).forEach { point ->
                val previous = current.lastOrNull()
                if (previous == null || pointKey(previous) != pointKey(point)) {
                    if (previous != null && span.source == RouteSource.INFERRED_TRANSFER) {
                        inferredTransferBeforePointIndices += pointCount
                    }
                    current += point
                    pointCount += 1
                }
            }
        }
    }
    if (current.isNotEmpty()) sections.add(current)
    return Journey.fromSections(sections, period, inferredTransferBeforePointIndices)
}

private fun pointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
    point.instant.toEpochMilli(),
    point.latitude.toBits(),
    point.longitude.toBits(),
)
