package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.haversineKm
import java.time.Duration
import kotlin.math.min

enum class LocationFilterMode {
    CONSERVATIVE,
    OFF,
}

data class LocationFilterResult(
    val points: List<GeoPoint>,
    val removedCount: Int,
)

object LocationOutlierFilter {
    fun filter(
        points: List<GeoPoint>,
        mode: LocationFilterMode = LocationFilterMode.CONSERVATIVE,
    ): LocationFilterResult {
        if (mode == LocationFilterMode.OFF || points.size < 3) {
            return LocationFilterResult(points, 0)
        }

        val kept = mutableListOf(points.first())
        var removedCount = 0
        var index = 1
        while (index < points.lastIndex) {
            val runEnd = suspiciousRunEnd(points, kept.last(), index)
            if (runEnd == null) {
                kept += points[index]
                index += 1
            } else {
                removedCount += runEnd - index + 1
                index = runEnd + 1
            }
        }
        kept += points.last()
        return LocationFilterResult(kept, removedCount)
    }

    private fun suspiciousRunEnd(points: List<GeoPoint>, before: GeoPoint, start: Int): Int? {
        val latestEnd = min(start + MAX_OUTLIER_RUN_POINTS - 1, points.lastIndex - 1)
        for (end in latestEnd downTo start) {
            if (isSuspiciousExcursion(before, points.subList(start, end + 1), points[end + 1])) {
                return end
            }
        }
        return null
    }

    private fun isSuspiciousExcursion(
        before: GeoPoint,
        candidates: List<GeoPoint>,
        after: GeoPoint,
    ): Boolean {
        val window = Duration.between(before.instant, after.instant)
        if (window.isNegative || window > MAX_EXCURSION_DURATION) return false
        if (haversineKm(before, after) > MAX_REJOIN_DISTANCE_KM) return false

        val first = candidates.first()
        val last = candidates.last()
        val ingressKm = haversineKm(before, first)
        val egressKm = haversineKm(last, after)
        if (ingressKm < MIN_OUTLIER_HOP_KM || egressKm < MIN_OUTLIER_HOP_KM) return false
        if (speedKmPerHour(before, first, ingressKm) <= MAX_PLAUSIBLE_SPEED_KMH) return false
        if (speedKmPerHour(last, after, egressKm) <= MAX_PLAUSIBLE_SPEED_KMH) return false

        val clusterAnchor = candidates.first()
        if (candidates.any { haversineKm(clusterAnchor, it) > MAX_OUTLIER_CLUSTER_SPAN_KM }) return false
        if (candidates.any {
                haversineKm(before, it) < MIN_OUTLIER_HOP_KM ||
                    haversineKm(it, after) < MIN_OUTLIER_HOP_KM
            }
        ) return false
        return true
    }

    private fun speedKmPerHour(from: GeoPoint, to: GeoPoint, distanceKm: Double): Double {
        val millis = Duration.between(from.instant, to.instant).toMillis()
        if (millis <= 0L) return Double.POSITIVE_INFINITY
        return distanceKm / (millis / 3_600_000.0)
    }

    private const val MAX_OUTLIER_RUN_POINTS = 3
    private const val MIN_OUTLIER_HOP_KM = 500.0
    private const val MAX_REJOIN_DISTANCE_KM = 200.0
    private const val MAX_OUTLIER_CLUSTER_SPAN_KM = 200.0
    private const val MAX_PLAUSIBLE_SPEED_KMH = 1_300.0
    private val MAX_EXCURSION_DURATION: Duration = Duration.ofHours(12)
}
