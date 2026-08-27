package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.JourneySemanticEpisode
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
    val journey = Journey.fromSections(sections, period, inferredTransferBeforePointIndices)
    if (journey.points.size < 2 || cameraEpisodes.isEmpty()) return journey

    val projectedEpisodes = cameraEpisodes.mapNotNull { episode ->
        val startIndex = journey.points.lowerBound(episode.start)
        val endIndex = journey.points.upperBound(episode.end) - 1
        if (startIndex !in journey.points.indices || endIndex <= startIndex) return@mapNotNull null
        if (journey.breakBeforePointIndices.any { it in (startIndex + 1)..endIndex }) return@mapNotNull null
        val startKm = journey.cumulativeDistanceKm[startIndex]
        val endKm = journey.cumulativeDistanceKm[endIndex]
        if (endKm <= startKm) return@mapNotNull null
        JourneySemanticEpisode(
            startKm = startKm,
            endKm = endKm,
            origin = episode.origin,
            destination = episode.destination,
        )
    }.sortedBy(JourneySemanticEpisode::startKm)
    return journey.copy(semanticEpisodes = projectedEpisodes)
}

private fun List<GeoPoint>.lowerBound(instant: java.time.Instant): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].instant < instant) low = middle + 1 else high = middle
    }
    return low
}

private fun List<GeoPoint>.upperBound(instant: java.time.Instant): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].instant <= instant) low = middle + 1 else high = middle
    }
    return low
}

private fun pointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
    point.instant.toEpochMilli(),
    point.latitude.toBits(),
    point.longitude.toBits(),
)
