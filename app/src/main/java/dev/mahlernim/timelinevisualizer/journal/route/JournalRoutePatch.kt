package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Instant

/** Selects one logical cache range without inventing points at its boundaries. */
fun JournalRoute.clippedTo(start: Instant, endExclusive: Instant): JournalRoute {
    require(endExclusive > start) { "The clipped range must not be empty" }
    val endInclusive = Instant.ofEpochMilli(
        if (endExclusive.toEpochMilli() == Long.MIN_VALUE) Long.MIN_VALUE else endExclusive.toEpochMilli() - 1,
    )
    val clippedSpans = spans.mapNotNull { span ->
        if (span.end < start || span.start >= endExclusive) return@mapNotNull null
        if (span.source == RouteSource.GAP) {
            val clippedStart = maxOf(span.start, start)
            val clippedEnd = minOf(span.end, endInclusive)
            return@mapNotNull if (clippedEnd >= clippedStart) {
                span.copy(start = clippedStart, end = clippedEnd)
            } else {
                null
            }
        }
        val points = span.points.filter { it.instant >= start && it.instant < endExclusive }
        if (points.isEmpty()) return@mapNotNull null
        if (span.source == RouteSource.INFERRED_TRANSFER && points.size < 2) return@mapNotNull null
        span.copy(
            start = maxOf(span.start, start),
            end = minOf(span.end, endInclusive),
            points = points,
        )
    }
    val flattened = clippedSpans.asSequence()
        .filter { it.source != RouteSource.GAP }
        .flatMap { it.points.asSequence() }
        .distinctBy(::routePointKey)
        .sortedBy(GeoPoint::instant)
        .toList()
    return copy(timeline = Timeline(flattened), spans = clippedSpans)
}

/** Replaces one prepared time window without reconstructing the lifetime Journal route. */
fun JournalRoute.replacingWindow(
    start: Instant,
    endExclusive: Instant,
    replacement: JournalRoute,
): JournalRoute {
    require(endExclusive > start) { "The replacement window must not be empty" }
    val before = mutableListOf<RouteSpan>()
    val after = mutableListOf<RouteSpan>()
    spans.forEach { span ->
        if (span.end < start || (span.source == RouteSource.GAP && span.end == start)) {
            before += span
        } else if (span.start >= endExclusive) {
            after += span
        } else if (span.source == RouteSource.GAP) {
            if (span.start < start) before += span.copy(end = start)
            if (span.end >= endExclusive) after += span.copy(start = endExclusive)
        } else {
            span.points.filter { it.instant < start }.toSpanOrNull(span)?.let(before::add)
            span.points.filter { it.instant >= endExclusive }.toSpanOrNull(span)?.let(after::add)
        }
    }
    val mergedSpans = (before + replacement.spans + after)
        .sortedWith(compareBy<RouteSpan> { it.start }.thenBy { it.end })
    val flattened = mergedSpans.asSequence()
        .filter { it.source != RouteSource.GAP }
        .flatMap { it.points.asSequence() }
        .distinctBy(::routePointKey)
        .sortedBy(GeoPoint::instant)
        .toList()
    return copy(
        timeline = Timeline(flattened),
        spans = mergedSpans,
        // These counters are diagnostic only. A bounded replacement cannot derive lifetime totals
        // without repeating the expensive lifetime query that this path intentionally avoids.
        detailedInputCount = detailedInputCount,
        detailedUsableCount = detailedUsableCount,
        semanticUsableCount = semanticUsableCount,
    )
}

/** Expands a changed interval to complete existing components so fusion owns both cut boundaries. */
fun JournalRoute.expandedRefreshWindow(
    start: Instant,
    endExclusive: Instant,
): Pair<Instant, Instant> {
    require(endExclusive > start) { "The refresh window must not be empty" }
    if (spans.isEmpty()) return start to endExclusive
    val ordered = spans.sortedWith(compareBy<RouteSpan> { it.start }.thenBy { it.end })
    var first = ordered.indexOfFirst { it.end > start && it.start < endExclusive }
    var last = ordered.indexOfLast { it.end > start && it.start < endExclusive }
    if (first < 0) {
        val nearestBefore = ordered.indexOfLast { !it.isRefreshBoundary() && it.end <= start }
        val nearestAfter = ordered.indexOfFirst { !it.isRefreshBoundary() && it.start >= endExclusive }
        first = when {
            nearestBefore >= 0 -> nearestBefore
            nearestAfter >= 0 -> nearestAfter
            else -> return start to endExclusive
        }
        last = when {
            nearestBefore >= 0 && nearestAfter >= 0 -> nearestAfter
            else -> first
        }
    }

    // A gap affected by new observations needs both neighboring components present so the
    // replacement fusion can decide whether the gap remains. Otherwise stop at existing gaps.
    while (first > 0 && !ordered[first - 1].isRefreshBoundary()) first -= 1
    while (last < ordered.lastIndex && !ordered[last + 1].isRefreshBoundary()) last += 1

    val expandedStart = minOf(start, ordered[first].start)
    val lastEndMillis = ordered[last].end.toEpochMilli()
    val expandedEnd = Instant.ofEpochMilli(
        if (lastEndMillis == Long.MAX_VALUE) lastEndMillis else lastEndMillis + 1,
    )
    return expandedStart to maxOf(endExclusive, expandedEnd)
}

private fun List<GeoPoint>.toSpanOrNull(source: RouteSpan): RouteSpan? =
    takeIf { it.isNotEmpty() }?.let { points ->
        source.copy(
            start = points.first().instant,
            end = points.last().instant,
            points = points,
        )
    }

private fun RouteSpan.isRefreshBoundary(): Boolean =
    source == RouteSource.GAP || source == RouteSource.INFERRED_TRANSFER

private fun routePointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
    point.instant.toEpochMilli(),
    point.latitude.toBits(),
    point.longitude.toBits(),
)
