package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.WebMercator
import kotlin.math.abs
import kotlin.math.hypot

/** Maps elapsed video progress to original route distance without changing route geometry. */
class JourneyTiming private constructor(
    private val elapsedFractions: DoubleArray,
    private val distancesKm: DoubleArray,
    private val slopes: DoubleArray,
    private val linearDistanceKm: Double?,
) {
    fun distanceAt(progress: Float): Double {
        val elapsed = progress.coerceIn(0f, 1f).toDouble()
        linearDistanceKm?.let { return it * elapsed }
        if (elapsedFractions.size < 2) return distancesKm.lastOrNull() ?: 0.0
        val exact = elapsedFractions.binarySearch(elapsed)
        if (exact >= 0) return distancesKm[exact]
        val to = (-exact - 1).coerceIn(1, elapsedFractions.lastIndex)
        val from = to - 1
        val width = elapsedFractions[to] - elapsedFractions[from]
        if (width <= 0.0) return distancesKm[from]
        val t = ((elapsed - elapsedFractions[from]) / width).coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * distancesKm[from] +
            (t3 - 2 * t2 + t) * width * slopes[from] +
            (-2 * t3 + 3 * t2) * distancesKm[to] +
            (t3 - t2) * width * slopes[to]
    }

    internal fun progressAtDistance(distanceKm: Double): Float {
        val target = distanceKm.coerceIn(0.0, distancesKm.lastOrNull() ?: linearDistanceKm ?: 0.0)
        linearDistanceKm?.let { total ->
            return if (total <= 0.0) 0f else (target / total).toFloat().coerceIn(0f, 1f)
        }
        var low = 0f
        var high = 1f
        repeat(32) {
            val middle = (low + high) / 2f
            if (distanceAt(middle) < target) low = middle else high = middle
        }
        return (low + high) / 2f
    }

    companion object {
        fun create(
            journey: Journey,
            @Suppress("UNUSED_PARAMETER")
            compression: LongTripCompression,
            @Suppress("UNUSED_PARAMETER")
            tripDetection: TripDetection = TripDetection.BALANCED,
        ): JourneyTiming {
            return linear(journey)
        }

        internal fun createViewportRelative(
            journey: Journey,
            viewports: List<Viewport>,
            aspect: Double,
            distancesKm: DoubleArray = DoubleArray(viewports.size) { index ->
                if (viewports.size < 2) 0.0 else journey.totalDistanceKm * index / (viewports.size - 1).toDouble()
            },
            minimumShares: List<TimingMinimumShare> = emptyList(),
        ): JourneyTiming {
            if (
                journey.points.size < 2 ||
                journey.totalDistanceKm <= 0.0 ||
                viewports.size < 2 ||
                distancesKm.size != viewports.size
            ) {
                return linear(journey)
            }
            val sampleCount = viewports.size
            val distances = distancesKm.copyOf()
            if (
                distances.first() != 0.0 ||
                kotlin.math.abs(distances.last() - journey.totalDistanceKm) > 1e-6 ||
                distances.indices.drop(1).any { distances[it] <= distances[it - 1] }
            ) {
                return linear(journey)
            }
            val work = DoubleArray(sampleCount - 1)
            var previous = WebMercator.project(journey.positionAtDistance(distances[0]).point)
            for (index in 1 until sampleCount) {
                val current = WebMercator.project(journey.positionAtDistance(distances[index]).point)
                val viewport = viewports[index - 1]
                val nextViewport = viewports[index]
                val spanY = geometricMean(
                    viewport.maxY - viewport.minY,
                    nextViewport.maxY - nextViewport.minY,
                ).coerceAtLeast(MIN_VIEWPORT_SPAN)
                val spanX = (spanY * aspect).coerceAtLeast(MIN_VIEWPORT_SPAN)
                val deltaX = wrappedDelta(current.x - previous.x)
                val deltaY = current.y - previous.y
                work[index - 1] = hypot(deltaX / spanX, deltaY / spanY)
                previous = current
            }

            val positive = work.filter { it.isFinite() && it > MIN_VISUAL_WORK }.sorted()
            if (positive.isEmpty()) return linear(journey)
            val median = positive[positive.size / 2]
            val floor = median * MIN_WORK_RATIO
            val ceiling = median * MAX_WORK_RATIO
            val guardedWork = DoubleArray(work.size) { index ->
                work[index].let { raw -> if (raw.isFinite()) raw.coerceIn(floor, ceiling) else median }
            }
            val total = guardedWork.sum()
            if (!total.isFinite() || total <= MIN_VISUAL_WORK) return linear(journey)
            val shares = DoubleArray(guardedWork.size) { guardedWork[it] / total }
            applyMinimumShares(distances, shares, minimumShares)
            val elapsed = DoubleArray(sampleCount)
            shares.forEachIndexed { index, share -> elapsed[index + 1] = elapsed[index] + share }
            elapsed[elapsed.lastIndex] = 1.0
            return JourneyTiming(elapsed, distances, monotoneSlopes(elapsed, distances), null)
        }

        private fun applyMinimumShares(
            distances: DoubleArray,
            shares: DoubleArray,
            minimumShares: List<TimingMinimumShare>,
        ) {
            if (minimumShares.isEmpty() || shares.isEmpty()) return
            data class Recipient(val indices: List<Int>, val extra: Double)
            val recipients = minimumShares.mapNotNull { minimum ->
                val indices = shares.indices.filter { index ->
                    val midpoint = (distances[index] + distances[index + 1]) / 2.0
                    midpoint >= minimum.startKm && midpoint < minimum.endKm
                }
                if (indices.isEmpty()) return@mapNotNull null
                val current = indices.sumOf { shares[it] }
                Recipient(indices, (minimum.minimumFraction - current).coerceAtLeast(0.0))
            }.filter { it.extra > 0.0 }
            if (recipients.isEmpty()) return

            val recipientIndices = recipients.flatMapTo(mutableSetOf()) { it.indices }
            val donorIndices = shares.indices.filterNot(recipientIndices::contains)
            val donorShare = donorIndices.sumOf { shares[it] }
            val requestedExtra = recipients.sumOf { it.extra }
            val grantedScale = if (requestedExtra <= 0.0) 0.0 else {
                minOf(1.0, (donorShare * MAX_REALLOCATED_SHARE_OF_DONORS) / requestedExtra)
            }
            val grantedExtra = requestedExtra * grantedScale
            if (grantedExtra <= 0.0 || donorShare <= 0.0) return

            val donorScale = (donorShare - grantedExtra) / donorShare
            donorIndices.forEach { shares[it] *= donorScale }
            recipients.forEach { recipient ->
                val extra = recipient.extra * grantedScale
                val current = recipient.indices.sumOf { shares[it] }
                if (current > 0.0) {
                    recipient.indices.forEach { index ->
                        shares[index] += extra * shares[index] / current
                    }
                } else {
                    val perInterval = extra / recipient.indices.size
                    recipient.indices.forEach { shares[it] += perInterval }
                }
            }
        }

        private fun linear(journey: Journey) =
            JourneyTiming(doubleArrayOf(), doubleArrayOf(), doubleArrayOf(), journey.totalDistanceKm)

        private fun geometricMean(first: Double, second: Double): Double =
            kotlin.math.sqrt(abs(first * second))

        private fun wrappedDelta(delta: Double): Double = when {
            delta > 0.5 -> delta - 1.0
            delta < -0.5 -> delta + 1.0
            else -> delta
        }

        private fun monotoneSlopes(x: DoubleArray, y: DoubleArray): DoubleArray {
            val segmentCount = x.size - 1
            val delta = DoubleArray(segmentCount) { index ->
                (y[index + 1] - y[index]) / (x[index + 1] - x[index])
            }
            if (segmentCount == 1) return doubleArrayOf(delta[0], delta[0])
            val slopes = DoubleArray(x.size)
            slopes[0] = endpointSlope(x[1] - x[0], x[2] - x[1], delta[0], delta[1])
            for (index in 1 until x.lastIndex) {
                if (delta[index - 1] <= 0.0 || delta[index] <= 0.0) {
                    slopes[index] = 0.0
                } else {
                    val beforeWidth = x[index] - x[index - 1]
                    val afterWidth = x[index + 1] - x[index]
                    val weightBefore = 2 * afterWidth + beforeWidth
                    val weightAfter = afterWidth + 2 * beforeWidth
                    slopes[index] = (weightBefore + weightAfter) /
                        (weightBefore / delta[index - 1] + weightAfter / delta[index])
                }
            }
            slopes[slopes.lastIndex] = endpointSlope(
                x[x.lastIndex] - x[x.lastIndex - 1],
                x[x.lastIndex - 1] - x[x.lastIndex - 2],
                delta[delta.lastIndex],
                delta[delta.lastIndex - 1],
            )
            return slopes
        }

        private fun endpointSlope(firstWidth: Double, secondWidth: Double, first: Double, second: Double): Double {
            val slope = ((2 * firstWidth + secondWidth) * first - firstWidth * second) /
                (firstWidth + secondWidth)
            return when {
                slope <= 0.0 -> 0.0
                slope > 3 * first -> 3 * first
                else -> slope
            }
        }

        private const val MIN_VIEWPORT_SPAN = 1e-9
        private const val MIN_VISUAL_WORK = 1e-12
        private const val MIN_WORK_RATIO = 0.05
        private const val MAX_WORK_RATIO = 20.0
        private const val MAX_REALLOCATED_SHARE_OF_DONORS = 0.20
    }
}

internal data class TimingMinimumShare(
    val startKm: Double,
    val endKm: Double,
    val minimumFraction: Double,
)
