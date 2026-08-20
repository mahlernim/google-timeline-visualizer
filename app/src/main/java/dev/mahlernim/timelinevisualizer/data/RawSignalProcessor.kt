package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.haversineKm
import java.time.Duration
import kotlin.math.max

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
        for (index in 1 until points.lastIndex) {
            val before = kept.last()
            val candidate = points[index]
            val after = points[index + 1]
            if (!isShortImpossibleSpike(before, candidate, after)) kept += candidate
        }
        kept += points.last()
        return kept
    }

    private fun isShortImpossibleSpike(
        before: RawSignalPoint,
        candidate: RawSignalPoint,
        after: RawSignalPoint,
    ): Boolean {
        val window = Duration.between(before.point.instant, after.point.instant)
        if (window.isNegative || window > MAX_SPIKE_WINDOW) return false
        val rejoinToleranceKm = max(
            MIN_REJOIN_TOLERANCE_KM,
            (before.accuracyMeters + after.accuracyMeters) * ACCURACY_REJOIN_MULTIPLIER / 1_000.0,
        )
        if (haversineKm(before.point, after.point) > rejoinToleranceKm) return false
        val ingressKm = haversineKm(before.point, candidate.point)
        val egressKm = haversineKm(candidate.point, after.point)
        val minimumSpikeKm = max(
            MIN_SPIKE_DISTANCE_KM,
            candidate.accuracyMeters * ACCURACY_SPIKE_MULTIPLIER / 1_000.0,
        )
        if (ingressKm < minimumSpikeKm || egressKm < minimumSpikeKm) return false
        return speedKmPerHour(before.point, candidate.point, ingressKm) > MAX_PLAUSIBLE_GROUND_SPEED_KMH &&
            speedKmPerHour(candidate.point, after.point, egressKm) > MAX_PLAUSIBLE_GROUND_SPEED_KMH
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
    private val MAX_SPIKE_WINDOW: Duration = Duration.ofMinutes(20)
    private val MAX_STATIONARY_WINDOW: Duration = Duration.ofMinutes(10)
    private val DISCONTINUITY_GAP: Duration = Duration.ofMinutes(30)
}
