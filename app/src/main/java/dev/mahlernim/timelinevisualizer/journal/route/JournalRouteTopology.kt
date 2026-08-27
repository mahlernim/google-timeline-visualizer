package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Measurements derived from the canonical route topology, never from its flat projection. */
data class JournalRouteSummary(
    val start: Instant?,
    val end: Instant?,
    val usablePointCount: Int,
    val connectedSegmentCount: Int,
    val connectedDistanceKm: Double,
    val gapCount: Int,
    val inferredTransferCount: Int,
)

/**
 * Returns source-supported timelines for analytics. Inferred video transfers start a new component.
 *
 * Consumers that calculate distance or movement must use these components instead of
 * [JournalRoute.timeline], which is only a temporary compatibility projection.
 */
fun JournalRoute.connectedTimelines(): List<Timeline> = spans.connectedTimelines()

fun List<RouteSpan>.connectedTimelines(): List<Timeline> {
    val components = mutableListOf<MutableList<GeoPoint>>()
    var current: MutableList<GeoPoint>? = null
    for (span in this) {
        if (span.source == RouteSource.GAP || span.source == RouteSource.INFERRED_TRANSFER) {
            current = null
            continue
        }
        val component = current ?: mutableListOf<GeoPoint>().also {
            components += it
            current = it
        }
        span.points.sortedBy(GeoPoint::instant).forEach { point ->
            if (component.lastOrNull()?.sameObservation(point) != true) component += point
        }
    }
    return components.filter { it.isNotEmpty() }.map(::Timeline)
}

fun JournalRoute.summary(): JournalRouteSummary {
    val components = connectedTimelines()
    val points = components.flatMap(Timeline::points)
    return JournalRouteSummary(
        start = points.minOfOrNull(GeoPoint::instant),
        end = points.maxOfOrNull(GeoPoint::instant),
        usablePointCount = points.size,
        connectedSegmentCount = components.sumOf { (it.points.size - 1).coerceAtLeast(0) },
        connectedDistanceKm = components.sumOf { component ->
            component.points.zipWithNext(::haversineKm).sum()
        },
        gapCount = spans.count { it.source == RouteSource.GAP },
        inferredTransferCount = spans.count { it.source == RouteSource.INFERRED_TRANSFER },
    )
}

private fun GeoPoint.sameObservation(other: GeoPoint): Boolean =
    instant == other.instant &&
        latitude.toBits() == other.latitude.toBits() &&
        longitude.toBits() == other.longitude.toBits()

private fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 6_371.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
