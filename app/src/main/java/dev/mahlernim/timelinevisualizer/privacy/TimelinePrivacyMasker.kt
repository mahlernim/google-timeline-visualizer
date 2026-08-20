package dev.mahlernim.timelinevisualizer.privacy

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.haversineKm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class PrivacyMaskResult(
    val points: List<GeoPoint>,
    val hiddenPointCount: Int,
    val hiddenIntervalCount: Int,
)

object TimelinePrivacyMasker {
    private const val EARTH_RADIUS_KM = 6371.0088

    fun mask(points: List<GeoPoint>, areas: List<PrivacyArea>): PrivacyMaskResult {
        if (points.isEmpty() || areas.isEmpty()) return PrivacyMaskResult(points, 0, 0)

        val visible = ArrayList<GeoPoint>(points.size)
        var hiddenPointCount = 0
        var hiddenIntervalCount = 0
        var insideHiddenInterval = false

        points.forEachIndexed { index, point ->
            val hidden = isHidden(point, areas)
            if (hidden) {
                hiddenPointCount += 1
                if (!insideHiddenInterval) hiddenIntervalCount += 1
                insideHiddenInterval = true
            } else {
                val beginsAfterHiddenInterval = insideHiddenInterval && visible.isNotEmpty()
                val crossesHiddenArea = index > 0 &&
                    !isHidden(points[index - 1], areas) &&
                    areas.any { segmentIntersectsArea(points[index - 1], point, it) }
                if (crossesHiddenArea) hiddenIntervalCount += 1
                val beginsNewSegment = beginsAfterHiddenInterval || crossesHiddenArea
                visible += if (beginsNewSegment && !point.startsNewRouteSegment && visible.isNotEmpty()) {
                    point.copy(startsNewRouteSegment = true)
                } else {
                    point
                }
                insideHiddenInterval = false
            }
        }

        return PrivacyMaskResult(visible, hiddenPointCount, hiddenIntervalCount)
    }

    fun isHidden(point: GeoPoint, areas: List<PrivacyArea>): Boolean = areas.any { area ->
        haversineKm(
            point,
            GeoPoint(point.instant, area.latitude, area.longitude),
        ) <= area.radiusKm
    }

    /**
     * Treats the route between two samples as the shorter great-circle arc. A crossing is hidden
     * even when sparse Timeline data has no recorded sample inside the selected circle.
     */
    fun segmentIntersectsArea(start: GeoPoint, end: GeoPoint, area: PrivacyArea): Boolean {
        if (isHidden(start, listOf(area)) || isHidden(end, listOf(area))) return true

        val segmentLength = haversineKm(start, end) / EARTH_RADIUS_KM
        if (segmentLength <= 0.0 || segmentLength >= PI) return false

        val center = GeoPoint(start.instant, area.latitude, area.longitude)
        val startToCenter = haversineKm(start, center) / EARTH_RADIUS_KM
        val segmentBearing = initialBearing(start, end)
        val centerBearing = initialBearing(start, center)
        val bearingDifference = centerBearing - segmentBearing
        val crossTrack = kotlin.math.asin(
            (sin(startToCenter) * sin(bearingDifference)).coerceIn(-1.0, 1.0),
        )
        val alongTrack = atan2(
            sin(startToCenter) * cos(bearingDifference),
            cos(startToCenter),
        )

        return alongTrack in 0.0..segmentLength &&
            abs(crossTrack) * EARTH_RADIUS_KM <= area.radiusKm
    }

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
}
