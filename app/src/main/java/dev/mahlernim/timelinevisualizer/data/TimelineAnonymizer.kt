package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.haversineKm
import dev.mahlernim.timelinevisualizer.privacy.PrivacyArea
import java.time.Duration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

data class TimelineAnonymizationResult(
    val points: List<GeoPoint>,
    val changedCount: Int,
    val insertedCenterCount: Int = 0,
)

object TimelineAnonymizer {
    private const val EARTH_RADIUS_KM = 6371.0088

    fun anonymize(
        points: List<GeoPoint>,
        areas: List<PrivacyArea>,
    ): TimelineAnonymizationResult {
        if (points.isEmpty() || areas.isEmpty()) {
            return TimelineAnonymizationResult(points, changedCount = 0)
        }

        val output = ArrayList<GeoPoint>(points.size)
        var changedCount = 0
        var insertedCenterCount = 0
        points.forEachIndexed { index, point ->
            if (index > 0) {
                val previous = points[index - 1]
                if (!previous.isFlying && !point.isFlying) {
                    crossings(previous, point, areas).forEach { crossing ->
                        output += centerPoint(previous, point, crossing)
                        insertedCenterCount += 1
                    }
                }
            }

            val area = if (point.isFlying) null else containingArea(point, areas)
            val replacement = area?.let {
                point.copy(latitude = it.latitude, longitude = it.longitude)
            } ?: point
            if (replacement != point) changedCount += 1
            output += replacement
        }

        if (changedCount == 0 && insertedCenterCount == 0) {
            return TimelineAnonymizationResult(points, changedCount = 0)
        }
        return TimelineAnonymizationResult(output, changedCount, insertedCenterCount)
    }

    private fun containingArea(point: GeoPoint, areas: List<PrivacyArea>): PrivacyArea? = areas
        .asSequence()
        .map { area -> area to distanceToArea(point, area) }
        .filter { (area, distanceKm) -> distanceKm <= area.radiusKm }
        .minWithOrNull(
            compareBy<Pair<PrivacyArea, Double>> { it.second }
                .thenBy { it.first.latitude }
                .thenBy { it.first.longitude }
                .thenBy { it.first.radiusKm }
                .thenBy { it.first.id },
        )
        ?.first

    private fun crossings(
        start: GeoPoint,
        end: GeoPoint,
        areas: List<PrivacyArea>,
    ): List<AreaCrossing> = areas.asSequence()
        .filter { area -> distanceToArea(start, area) > area.radiusKm && distanceToArea(end, area) > area.radiusKm }
        .mapNotNull { area -> crossingFraction(start, end, area)?.let { AreaCrossing(area, it) } }
        .sortedWith(
            compareBy<AreaCrossing>(AreaCrossing::fraction)
                .thenBy { it.area.latitude }
                .thenBy { it.area.longitude }
                .thenBy { it.area.radiusKm }
                .thenBy { it.area.id },
        )
        .toList()

    private fun crossingFraction(start: GeoPoint, end: GeoPoint, area: PrivacyArea): Double? {
        val segmentLength = haversineKm(start, end) / EARTH_RADIUS_KM
        if (segmentLength <= 0.0 || segmentLength >= PI) return null

        val center = GeoPoint(start.instant, area.latitude, area.longitude)
        val startToCenter = haversineKm(start, center) / EARTH_RADIUS_KM
        val bearingDifference = initialBearing(start, center) - initialBearing(start, end)
        val crossTrack = kotlin.math.asin(
            (sin(startToCenter) * sin(bearingDifference)).coerceIn(-1.0, 1.0),
        )
        val alongTrack = atan2(
            sin(startToCenter) * cos(bearingDifference),
            cos(startToCenter),
        )
        return (alongTrack / segmentLength).takeIf {
            alongTrack in 0.0..segmentLength && abs(crossTrack) * EARTH_RADIUS_KM <= area.radiusKm
        }
    }

    private fun centerPoint(start: GeoPoint, end: GeoPoint, crossing: AreaCrossing): GeoPoint {
        val durationNanos = runCatching { Duration.between(start.instant, end.instant).toNanos() }.getOrDefault(0L)
        val offsetNanos = if (durationNanos > 1L) {
            (durationNanos * crossing.fraction)
                .roundToLong()
                .coerceIn(1L, durationNanos - 1L)
        } else {
            0L
        }
        return GeoPoint(
            instant = start.instant.plusNanos(offsetNanos),
            latitude = crossing.area.latitude,
            longitude = crossing.area.longitude,
        )
    }

    private fun distanceToArea(point: GeoPoint, area: PrivacyArea): Double = haversineKm(
        point,
        GeoPoint(point.instant, area.latitude, area.longitude),
    )

    private fun initialBearing(start: GeoPoint, end: GeoPoint): Double {
        val startLatitude = Math.toRadians(start.latitude)
        val endLatitude = Math.toRadians(end.latitude)
        val longitudeDelta = Math.toRadians(end.longitude - start.longitude)
        return atan2(
            sin(longitudeDelta) * cos(endLatitude),
            cos(startLatitude) * sin(endLatitude) -
                sin(startLatitude) * cos(endLatitude) * cos(longitudeDelta),
        )
    }

    private data class AreaCrossing(val area: PrivacyArea, val fraction: Double)
}
