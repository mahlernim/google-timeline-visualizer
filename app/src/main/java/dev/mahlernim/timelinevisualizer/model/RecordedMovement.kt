package dev.mahlernim.timelinevisualizer.model

import java.time.Duration

data class RecordedMotionInterval(val startKm: Double, val endKm: Double, val seconds: Double)

/** Recorded moving time only. No camera, visual-distance or invented-speed fallback. */
class RecordedMovement private constructor(val intervals: List<RecordedMotionInterval>) {
    val totalSeconds: Double = intervals.sumOf { it.seconds }
    val hasMovement: Boolean get() = totalSeconds > 0.0

    companion object {
        fun from(journey: Journey): RecordedMovement {
            if (journey.points.size < 2) return RecordedMovement(emptyList())
            val route = journey.cumulativeDistanceKm
            val excluded = (journey.breakBeforePointIndices + journey.inferredTransferBeforePointIndices).toSet()
            val episodes = journey.semanticEpisodes.sortedBy { it.startKm }
            val knots = (route.asSequence() + episodes.asSequence().flatMap { sequenceOf(it.startKm, it.endKm) })
                .filter { it.isFinite() && it in 0.0..journey.totalDistanceKm }.distinct().sorted().toList()
            val result = mutableListOf<RecordedMotionInterval>()
            var pointIndex = 1
            var episodeIndex = 0
            for (index in 1 until knots.size) {
                val start = knots[index - 1]
                val end = knots[index]
                val middle = (start + end) / 2.0
                while (pointIndex < route.lastIndex && route[pointIndex] <= middle) pointIndex++
                while (episodeIndex < episodes.size && episodes[episodeIndex].endKm <= middle) episodeIndex++
                if (pointIndex in excluded) continue
                val episode = episodes.getOrNull(episodeIndex)?.takeIf { middle >= it.startKm && middle < it.endKm }
                val length: Double
                val seconds: Double
                if (episode != null) {
                    length = episode.lengthKm
                    seconds = duration(episode.origin, episode.destination)
                } else {
                    length = route[pointIndex] - route[pointIndex - 1]
                    seconds = duration(journey.points[pointIndex - 1], journey.points[pointIndex])
                    if (seconds > MAX_OBSERVATION_GAP_SECONDS) continue
                }
                if (length <= 0 || seconds <= 0 || !seconds.isFinite()) continue
                val speedKmh = length * 3600.0 / seconds
                if (speedKmh !in MIN_MOVING_SPEED_KMH..MAX_MOVING_SPEED_KMH) continue
                result += RecordedMotionInterval(start, end, seconds * (end - start) / length)
            }
            return RecordedMovement(result)
        }

        private fun duration(start: GeoPoint, end: GeoPoint): Double =
            Duration.between(start.instant, end.instant).toMillis() / 1000.0

        private const val MAX_OBSERVATION_GAP_SECONDS = 30 * 60.0
        private const val MIN_MOVING_SPEED_KMH = 0.5
        private const val MAX_MOVING_SPEED_KMH = 1300.0
    }
}
