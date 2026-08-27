package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.haversineKm
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

data class RawSignalProcessingResult(
    val points: List<GeoPoint>,
    val inputCount: Int,
    val accuracyRejectedCount: Int,
    val noiseRejectedCount: Int,
    val discontinuityCount: Int,
) {
    val rejectedCount: Int get() = accuracyRejectedCount + noiseRejectedCount
}

object RawSignalProcessor {
    const val DEFAULT_MAXIMUM_ACCURACY_METERS = 100.0

    fun process(
        source: List<RawSignalPoint>,
        maximumAccuracyMeters: Double? = DEFAULT_MAXIMUM_ACCURACY_METERS,
    ): RawSignalProcessingResult {
        val accuracyFiltered = source.filter { point ->
            maximumAccuracyMeters == null || point.accuracyMeters <= maximumAccuracyMeters
        }
        val withoutSpikes = removeShortImpossibleSpikes(accuracyFiltered)
        val stabilized = collapseUncertainMovement(withoutSpikes)
        val discontinuities = stabilized.zipWithNext().count { (before, after) ->
            Duration.between(before.point.instant, after.point.instant) > DISCONTINUITY_GAP
        }
        return RawSignalProcessingResult(
            points = stabilized.map(RawSignalPoint::point),
            inputCount = source.size,
            accuracyRejectedCount = source.size - accuracyFiltered.size,
            noiseRejectedCount = accuracyFiltered.size - stabilized.size,
            discontinuityCount = discontinuities,
        )
    }

    private fun removeShortImpossibleSpikes(points: List<RawSignalPoint>): List<RawSignalPoint> {
        if (points.size < 3) return points
        val kept = ArrayList<RawSignalPoint>(points.size)
        kept += points.first()
        var index = 1
        while (index < points.lastIndex) {
            val runEnd = impossibleRunEnd(points, kept.last(), index)
            if (runEnd == null) {
                kept += points[index]
                index += 1
            } else {
                index = runEnd + 1
            }
        }
        kept += points.last()
        return kept
    }

    /**
     * Returns the last index of the impossible excursion starting at [start], or null when the
     * point at [start] rejoins the track plausibly. Longer runs are tried first so a cluster of
     * consecutive readings at the same wrong location is removed as a whole; checking only the
     * single-point window leaves every member of such a cluster in place, because neither the
     * first nor the last one rejoins near [before].
     */
    private fun impossibleRunEnd(
        points: List<RawSignalPoint>,
        before: RawSignalPoint,
        start: Int,
    ): Int? {
        val latestEnd = min(start + MAX_SPIKE_RUN_POINTS - 1, points.lastIndex - 1)
        for (end in latestEnd downTo start) {
            if (isImpossibleExcursion(before, points, start, end, points[end + 1])) return end
        }
        return null
    }

    private fun isImpossibleExcursion(
        before: RawSignalPoint,
        points: List<RawSignalPoint>,
        start: Int,
        end: Int,
        after: RawSignalPoint,
    ): Boolean {
        val window = Duration.between(before.point.instant, after.point.instant)
        if (window.isNegative || window > MAX_SPIKE_WINDOW) return false
        val rejoinToleranceKm = max(
            MIN_REJOIN_TOLERANCE_KM,
            (before.accuracyMeters + after.accuracyMeters) * ACCURACY_REJOIN_MULTIPLIER / 1_000.0,
        )
        if (haversineKm(before.point, after.point) > rejoinToleranceKm) return false

        val first = points[start]
        val last = points[end]
        val ingressKm = haversineKm(before.point, first.point)
        val egressKm = haversineKm(last.point, after.point)
        if (speedKmPerHour(before.point, first.point, ingressKm) <= MAX_PLAUSIBLE_GROUND_SPEED_KMH) return false
        if (speedKmPerHour(last.point, after.point, egressKm) <= MAX_PLAUSIBLE_GROUND_SPEED_KMH) return false

        val anchor = first.point
        for (index in start..end) {
            val candidate = points[index]
            val minimumSpikeKm = max(
                MIN_SPIKE_DISTANCE_KM,
                candidate.accuracyMeters * ACCURACY_SPIKE_MULTIPLIER / 1_000.0,
            )
            if (haversineKm(before.point, candidate.point) < minimumSpikeKm) return false
            if (haversineKm(candidate.point, after.point) < minimumSpikeKm) return false
            if (haversineKm(anchor, candidate.point) > MAX_SPIKE_CLUSTER_SPAN_KM) return false
        }
        return true
    }

    private fun collapseUncertainMovement(points: List<RawSignalPoint>): List<RawSignalPoint> {
        if (points.size < 2) return points
        val kept = ArrayList<RawSignalPoint>(points.size)
        kept += points.first()
        for (candidate in points.drop(1)) {
            val previous = kept.last()
            val elapsed = Duration.between(previous.point.instant, candidate.point.instant)
            val uncertaintyKm = max(
                MIN_STATIONARY_RADIUS_KM,
                (previous.accuracyMeters + candidate.accuracyMeters) / 1_000.0,
            )
            val overlapsWithinUncertainty = !elapsed.isNegative &&
                elapsed <= MAX_STATIONARY_WINDOW &&
                haversineKm(previous.point, candidate.point) <= uncertaintyKm
            if (overlapsWithinUncertainty) {
                if (candidate.accuracyMeters < previous.accuracyMeters) {
                    kept[kept.lastIndex] = candidate
                }
            } else {
                kept += candidate
            }
        }
        return kept
    }

    private fun speedKmPerHour(from: GeoPoint, to: GeoPoint, distanceKm: Double): Double {
        val millis = Duration.between(from.instant, to.instant).toMillis()
        if (millis <= 0L) return Double.POSITIVE_INFINITY
        return distanceKm / (millis / 3_600_000.0)
    }

    private const val MIN_REJOIN_TOLERANCE_KM = 0.2
    private const val MIN_SPIKE_DISTANCE_KM = 0.5
    private const val MIN_STATIONARY_RADIUS_KM = 0.025
    private const val ACCURACY_REJOIN_MULTIPLIER = 2.0
    private const val ACCURACY_SPIKE_MULTIPLIER = 5.0
    private const val MAX_PLAUSIBLE_GROUND_SPEED_KMH = 250.0
    private const val MAX_SPIKE_RUN_POINTS = 5
    private const val MAX_SPIKE_CLUSTER_SPAN_KM = 50.0
    private val MAX_SPIKE_WINDOW: Duration = Duration.ofMinutes(20)
    private val MAX_STATIONARY_WINDOW: Duration = Duration.ofMinutes(10)
    private val DISCONTINUITY_GAP: Duration = Duration.ofMinutes(30)
}
