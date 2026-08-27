package dev.mahlernim.timelinevisualizer.model

import java.time.Instant
import java.time.Month
import java.time.YearMonth
import java.time.LocalDate
import java.time.ZoneId
import java.util.AbstractList
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class GeoPoint(
    val instant: Instant,
    val latitude: Double,
    val longitude: Double,
) {
    val year: Int get() = instant.atZone(ZoneId.systemDefault()).year
}

data class Timeline(
    val points: List<GeoPoint>,
) {
    private val chronological = run {
        var ordered = true
        var index = 1
        while (index < points.size && ordered) {
            ordered = points[index - 1].instant <= points[index].instant
            index += 1
        }
        ordered
    }
    val years: List<Int> = points.asSequence().map { it.year }.distinct().sortedDescending().toList()

    fun forYear(year: Int): Journey {
        return forRange(year, Month.JANUARY.value, Month.DECEMBER.value)
    }

    fun forRange(year: Int, startMonth: Int, endMonth: Int): Journey {
        require(startMonth in 1..12 && endMonth in startMonth..12)
        return forRange(
            TimelinePeriod(
                start = YearMonth.of(year, startMonth),
                endInclusive = YearMonth.of(year, endMonth),
            ),
        )
    }

    fun forRange(period: TimelinePeriod): Journey {
        val zone = ZoneId.systemDefault()
        val selected = if (chronological) {
            chronologicalRange(
                lowerMatches = { point -> yearMonth(point, zone) >= period.start },
                upperMatches = { point -> yearMonth(point, zone) > period.endInclusive },
            )
        } else {
            points.filter {
                val month = yearMonth(it, zone)
                month >= period.start && month <= period.endInclusive
            }
        }
        return Journey.from(selected, period)
    }

    fun countForRange(period: TimelinePeriod): Int {
        val zone = ZoneId.systemDefault()
        return points.count {
            val date = it.instant.atZone(zone)
            val month = YearMonth.of(date.year, date.monthValue)
            month >= period.start && month <= period.endInclusive
        }
    }

    fun forDateRange(start: LocalDate, endInclusive: LocalDate): Journey {
        require(endInclusive >= start)
        val zone = ZoneId.systemDefault()
        val selected = if (chronological) {
            chronologicalRange(
                lowerMatches = { point -> point.instant.atZone(zone).toLocalDate() >= start },
                upperMatches = { point -> point.instant.atZone(zone).toLocalDate() > endInclusive },
            )
        } else {
            points.filter {
                val date = it.instant.atZone(zone).toLocalDate()
                date >= start && date <= endInclusive
            }
        }
        return Journey.from(
            selected,
            TimelinePeriod(YearMonth.from(start), YearMonth.from(endInclusive)),
        )
    }

    fun countForDateRange(start: LocalDate, endInclusive: LocalDate): Int {
        require(endInclusive >= start)
        val zone = ZoneId.systemDefault()
        return points.count {
            val date = it.instant.atZone(zone).toLocalDate()
            date >= start && date <= endInclusive
        }
    }

    private fun chronologicalRange(
        lowerMatches: (GeoPoint) -> Boolean,
        upperMatches: (GeoPoint) -> Boolean,
    ): List<GeoPoint> {
        fun lowerBound(predicate: (GeoPoint) -> Boolean): Int {
            var low = 0
            var high = points.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (predicate(points[middle])) high = middle else low = middle + 1
            }
            return low
        }
        val fromIndex = lowerBound(lowerMatches)
        val toIndex = lowerBound(upperMatches).coerceAtLeast(fromIndex)
        return points.subList(fromIndex, toIndex)
    }

    private fun yearMonth(point: GeoPoint, zone: ZoneId): YearMonth {
        val date = point.instant.atZone(zone)
        return YearMonth.of(date.year, date.monthValue)
    }
}

data class TimelinePeriod(
    val start: YearMonth,
    val endInclusive: YearMonth,
) {
    init {
        require(endInclusive >= start) { "The end month must not be before the start month" }
    }

    val startYear: Int get() = start.year
    val startMonth: Int get() = start.monthValue
    val endYear: Int get() = endInclusive.year
    val endMonth: Int get() = endInclusive.monthValue
    val yearLabel: String get() = if (startYear == endYear) startYear.toString() else "$startYear\u2013$endYear"

    companion object {
        fun sameYear(year: Int, startMonth: Int = 1, endMonth: Int = 12): TimelinePeriod = TimelinePeriod(
            start = YearMonth.of(year, startMonth),
            endInclusive = YearMonth.of(year, endMonth),
        )
    }
}

data class JourneyPosition(
    val point: GeoPoint,
    val distanceKm: Double,
    val knownDistanceKm: Double,
    val fromIndex: Int,
    val toIndex: Int,
    val segmentFraction: Double,
)

data class RouteSample(
    val point: GeoPoint,
    val distanceKm: Double,
)

internal data class RenderSampleLocation(
    val toPointIndex: Int,
    val step: Int,
    val steps: Int,
) {
    val fraction: Double get() = step.toDouble() / steps
}

internal class MutableRenderSampleLocation(
    var toPointIndex: Int = 0,
    var step: Int = 0,
    var steps: Int = 0,
) {
    val fraction: Double get() = step.toDouble() / steps
}

private class JourneyRenderPath(
    private val points: List<GeoPoint>,
    private val cumulativeDistanceKm: DoubleArray,
    private val breakBeforePointIndices: Set<Int>,
) : AbstractList<RouteSample>() {
    private val segmentEnds = IntArray((points.size - 1).coerceAtLeast(0))

    override val size: Int

    init {
        var sampleCount = if (points.isEmpty()) 0L else 1L
        for (toIndex in 1..points.lastIndex) {
            val segmentDistance = cumulativeDistanceKm[toIndex] - cumulativeDistanceKm[toIndex - 1]
            val steps = if (toIndex in breakBeforePointIndices) 1 else renderSteps(segmentDistance)
            sampleCount += steps
            require(sampleCount <= Int.MAX_VALUE) { "Timeline contains too many render samples" }
            segmentEnds[toIndex - 1] = sampleCount.toInt()
        }
        size = sampleCount.toInt()
    }

    override fun get(index: Int): RouteSample {
        if (index !in indices) throw IndexOutOfBoundsException("Index $index, size $size")
        if (index == 0) return RouteSample(points.first(), 0.0)
        val location = locationAt(index)
        val fromIndex = location.toPointIndex - 1
        val fraction = location.fraction
        val startDistance = cumulativeDistanceKm[fromIndex]
        val segmentDistance = cumulativeDistanceKm[location.toPointIndex] - startDistance
        return RouteSample(
            interpolate(points[fromIndex], points[location.toPointIndex], fraction),
            startDistance + segmentDistance * fraction,
        )
    }

    fun locationAt(index: Int): RenderSampleLocation {
        val location = MutableRenderSampleLocation()
        fillLocationAt(index, location)
        return RenderSampleLocation(location.toPointIndex, location.step, location.steps)
    }

    fun fillLocationAt(index: Int, location: MutableRenderSampleLocation) {
        require(index in 1 until size)
        var low = 0
        var high = segmentEnds.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (segmentEnds[middle] <= index) low = middle + 1 else high = middle
        }
        val previousEnd = if (low == 0) 1 else segmentEnds[low - 1]
        val steps = segmentEnds[low] - previousEnd
        location.toPointIndex = low + 1
        location.step = index - previousEnd + 1
        location.steps = steps
    }
}

data class JourneyLeg(
    val startKm: Double,
    val endKm: Double,
    val isTransfer: Boolean,
) {
    val lengthKm: Double get() = endKm - startKm
}

/**
 * A semantic activity projected onto the detailed journey distance axis.
 *
 * The activity endpoints provide global trip context while the points between [startKm] and
 * [endKm] remain the authoritative local geometry followed by the marker and route trail.
 */
data class JourneySemanticEpisode(
    val startKm: Double,
    val endKm: Double,
    val origin: GeoPoint,
    val destination: GeoPoint,
) {
    val lengthKm: Double get() = endKm - startKm
    val displacementKm: Double get() = haversineKm(origin, destination)
}

data class Journey(
    val period: TimelinePeriod,
    val points: List<GeoPoint>,
    val cumulativeDistanceKm: DoubleArray,
    val breakBeforePointIndices: List<Int> = emptyList(),
    val inferredTransferBeforePointIndices: List<Int> = emptyList(),
    val semanticEpisodes: List<JourneySemanticEpisode> = emptyList(),
) {
    private val breakIndexSet = breakBeforePointIndices.toSet()
    private val inferredTransferIndexSet = inferredTransferBeforePointIndices.toSet()
    private val knownCumulativeDistanceKm = DoubleArray(points.size).also { distances ->
        for (index in 1 until points.size) {
            distances[index] = distances[index - 1] + if (
                index in breakIndexSet || index in inferredTransferIndexSet
            ) {
                0.0
            } else {
                haversineKm(points[index - 1], points[index])
            }
        }
    }
    private val renderPathData = JourneyRenderPath(points, cumulativeDistanceKm, breakIndexSet)
    val year: Int get() = period.startYear
    val totalDistanceKm: Double get() = cumulativeDistanceKm.lastOrNull() ?: 0.0
    /** Distance supported by detailed or semantic geometry, excluding inferred transfers. */
    val knownDistanceKm: Double get() = knownCumulativeDistanceKm.lastOrNull() ?: 0.0
    val renderPath: List<RouteSample> = renderPathData
    /**
     * A bounded, journey-specific cutoff for unusually large untracked hops. Dense local routes
     * can recognize shorter transfers, while consistently sparse routes keep the conservative cap.
     */
    val transferThresholdKm: Double = calculateTransferThresholdKm()
    val legs: List<JourneyLeg> = buildLegs(transferThresholdKm)

    init {
        require(cumulativeDistanceKm.size == points.size)
        require(breakBeforePointIndices == breakBeforePointIndices.distinct().sorted())
        require(breakBeforePointIndices.all { it in 1..points.lastIndex })
        require(inferredTransferBeforePointIndices == inferredTransferBeforePointIndices.distinct().sorted())
        require(inferredTransferBeforePointIndices.all { it in 1..points.lastIndex })
        require(inferredTransferBeforePointIndices.none(breakIndexSet::contains))
        require(semanticEpisodes.all { episode ->
            episode.startKm >= 0.0 && episode.endKm > episode.startKm && episode.endKm <= totalDistanceKm
        })
        require(semanticEpisodes.zipWithNext().all { (before, after) -> before.startKm <= after.startKm })
    }

    fun isConnectedToPrevious(pointIndex: Int): Boolean =
        pointIndex in 1..points.lastIndex && pointIndex !in breakIndexSet

    fun isInferredTransferFromPrevious(pointIndex: Int): Boolean =
        pointIndex in inferredTransferIndexSet

    internal fun isRenderConnectionFromPrevious(renderIndex: Int): Boolean {
        if (renderIndex !in 1 until renderPath.size) return false
        return renderPathData.locationAt(renderIndex).let { location ->
            location.step > 1 || isConnectedToPrevious(location.toPointIndex)
        }
    }

    fun pointIndexAt(progress: Float): Int {
        return positionAt(progress).toIndex
    }

    fun legsForThreshold(thresholdKm: Double): List<JourneyLeg> =
        if (kotlin.math.abs(thresholdKm - transferThresholdKm) < 1e-9) legs else buildLegs(thresholdKm)

    fun legAt(distanceKm: Double, candidates: List<JourneyLeg> = legs): JourneyLeg {
        if (candidates.isEmpty()) return JourneyLeg(0.0, totalDistanceKm, false)
        val target = distanceKm.coerceIn(0.0, totalDistanceKm)
        var low = 0
        var high = candidates.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (candidates[middle].startKm <= target) low = middle + 1 else high = middle
        }
        return candidates[(low - 1).coerceIn(0, candidates.lastIndex)]
    }

    fun positionAt(progress: Float): JourneyPosition = positionAtDistance(
        totalDistanceKm * progress.coerceIn(0f, 1f),
    )

    fun positionAtDistance(distanceKm: Double): JourneyPosition {
        if (points.isEmpty()) {
            val epoch = GeoPoint(Instant.EPOCH, 0.0, 0.0)
            return JourneyPosition(epoch, 0.0, 0.0, 0, 0, 0.0)
        }
        if (points.size == 1 || totalDistanceKm <= 0.0) {
            return JourneyPosition(points.first(), 0.0, 0.0, 0, 0, 0.0)
        }
        val target = distanceKm.coerceIn(0.0, totalDistanceKm)
        var low = 0
        var high = cumulativeDistanceKm.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cumulativeDistanceKm[middle] <= target) low = middle + 1 else high = middle
        }
        val fromOrExact = (low - 1).coerceAtLeast(0)
        if (cumulativeDistanceKm[fromOrExact] == target) {
            return JourneyPosition(
                points[fromOrExact],
                target,
                knownCumulativeDistanceKm[fromOrExact],
                fromOrExact,
                fromOrExact,
                0.0,
            )
        }

        val to = low.coerceIn(1, points.lastIndex)
        val from = to - 1
        val segmentDistance = cumulativeDistanceKm[to] - cumulativeDistanceKm[from]
        val fraction = if (segmentDistance <= 0.0) 0.0 else
            ((target - cumulativeDistanceKm[from]) / segmentDistance).coerceIn(0.0, 1.0)
        return JourneyPosition(
            interpolate(points[from], points[to], fraction),
            target,
            knownCumulativeDistanceKm[from] +
                (knownCumulativeDistanceKm[to] - knownCumulativeDistanceKm[from]) * fraction,
            from,
            to,
            fraction,
        )
    }

    internal fun renderSampleLocation(index: Int): RenderSampleLocation? =
        if (index == 0 || renderPath.isEmpty()) null else renderPathData.locationAt(index)

    internal fun fillRenderSampleLocation(index: Int, location: MutableRenderSampleLocation): Boolean {
        if (index == 0 || renderPath.isEmpty()) return false
        renderPathData.fillLocationAt(index, location)
        return true
    }

    private fun calculateTransferThresholdKm(): Double {
        val candidates = DoubleArray((cumulativeDistanceKm.size - 1).coerceAtLeast(0))
        var count = 0
        for (index in 1 until cumulativeDistanceKm.size) {
            if (index in inferredTransferIndexSet) continue
            val distance = cumulativeDistanceKm[index] - cumulativeDistanceKm[index - 1]
            if (distance > 0.0 && distance < MAX_TRANSFER_THRESHOLD_KM) candidates[count++] = distance
        }
        if (count == 0) return MAX_TRANSFER_THRESHOLD_KM

        candidates.sort(0, count)
        val typicalHopKm = median(candidates, count)
        for (index in 0 until count) candidates[index] = kotlin.math.abs(candidates[index] - typicalHopKm)
        candidates.sort(0, count)
        val medianDeviationKm = median(candidates, count)
        return max(
            MIN_TRANSFER_THRESHOLD_KM,
            max(typicalHopKm * TRANSFER_TO_TYPICAL_RATIO, typicalHopKm + medianDeviationKm * DEVIATION_MULTIPLIER),
        ).coerceAtMost(MAX_TRANSFER_THRESHOLD_KM)
    }

    private fun buildLegs(thresholdKm: Double): List<JourneyLeg> {
        if (points.size < 2 || totalDistanceKm <= 0.0) return emptyList()
        val cutoff = thresholdKm.coerceAtLeast(1.0)
        val transferRanges = ArrayList<TransferRange>()
        for (index in 1..points.lastIndex) {
            val startKm = cumulativeDistanceKm[index - 1]
            val endKm = cumulativeDistanceKm[index]
            if (index in inferredTransferIndexSet || endKm - startKm >= cutoff) {
                transferRanges += TransferRange(startKm, endKm)
            }
        }
        semanticEpisodes.forEach { episode ->
            if (episode.displacementKm >= cutoff) {
                transferRanges += TransferRange(episode.startKm, episode.endKm)
            }
        }
        if (transferRanges.isEmpty()) return listOf(JourneyLeg(0.0, totalDistanceKm, false))

        transferRanges.sortWith(compareBy<TransferRange> { it.startKm }.thenBy { it.endKm })
        val merged = ArrayList<TransferRange>(transferRanges.size)
        transferRanges.forEach { next ->
            val previous = merged.lastOrNull()
            if (previous == null || next.startKm > previous.endKm) {
                merged += next
            } else if (next.endKm > previous.endKm) {
                merged[merged.lastIndex] = previous.copy(endKm = next.endKm)
            }
        }

        val result = ArrayList<JourneyLeg>(merged.size * 2 + 1)
        var localStartKm = 0.0
        merged.forEach { transfer ->
            if (transfer.startKm > localStartKm) result += JourneyLeg(localStartKm, transfer.startKm, false)
            result += JourneyLeg(transfer.startKm, transfer.endKm, true)
            localStartKm = transfer.endKm
        }
        if (totalDistanceKm > localStartKm) result += JourneyLeg(localStartKm, totalDistanceKm, false)
        return result
    }

    companion object {
        fun from(points: List<GeoPoint>, year: Int): Journey = from(points, TimelinePeriod.sameYear(year))

        fun from(points: List<GeoPoint>, period: TimelinePeriod): Journey {
            return fromFlattened(points, period, emptyList())
        }

        /** Builds one journey while preserving discontinuities between ordered route sections. */
        fun fromSections(
            sections: List<List<GeoPoint>>,
            period: TimelinePeriod,
            inferredTransferBeforePointIndices: List<Int> = emptyList(),
        ): Journey {
            val nonEmptySections = sections.filter(List<GeoPoint>::isNotEmpty)
            val points = ArrayList<GeoPoint>(nonEmptySections.sumOf(List<GeoPoint>::size))
            val breaks = ArrayList<Int>((nonEmptySections.size - 1).coerceAtLeast(0))
            nonEmptySections.forEachIndexed { index, section ->
                if (index > 0) breaks += points.size
                points += section
            }
            return fromFlattened(points, period, breaks, inferredTransferBeforePointIndices)
        }

        /** Restores a persisted journey topology after validating its break indices. */
        fun fromBreakIndices(
            points: List<GeoPoint>,
            period: TimelinePeriod,
            breakBeforePointIndices: List<Int>,
            inferredTransferBeforePointIndices: List<Int> = emptyList(),
            semanticEpisodes: List<JourneySemanticEpisode> = emptyList(),
        ): Journey = fromFlattened(
            points,
            period,
            breakBeforePointIndices,
            inferredTransferBeforePointIndices,
            semanticEpisodes,
        )

        private fun fromFlattened(
            points: List<GeoPoint>,
            period: TimelinePeriod,
            breakBeforePointIndices: List<Int>,
            inferredTransferBeforePointIndices: List<Int> = emptyList(),
            semanticEpisodes: List<JourneySemanticEpisode> = emptyList(),
        ): Journey {
            val breaks = breakBeforePointIndices.distinct().sorted()
            require(breaks.all { it in 1..points.lastIndex })
            val inferredTransfers = inferredTransferBeforePointIndices.distinct().sorted()
            require(inferredTransfers.all { it in 1..points.lastIndex })
            require(inferredTransfers.none(breaks::contains))
            val breakSet = breaks.toSet()
            val distances = DoubleArray(points.size)
            for (index in 1 until points.size) {
                distances[index] = distances[index - 1] + if (index in breakSet) {
                    0.0
                } else {
                    haversineKm(points[index - 1], points[index])
                }
            }
            return Journey(period, points, distances, breaks, inferredTransfers, semanticEpisodes)
        }

        internal fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
            if (fraction <= 0.0) return a
            if (fraction >= 1.0) return b
            val lat1 = Math.toRadians(a.latitude)
            val lon1 = Math.toRadians(a.longitude)
            val lat2 = Math.toRadians(b.latitude)
            val lon2 = Math.toRadians(b.longitude)
            val ax = cos(lat1) * cos(lon1)
            val ay = cos(lat1) * sin(lon1)
            val az = sin(lat1)
            val bx = cos(lat2) * cos(lon2)
            val by = cos(lat2) * sin(lon2)
            val bz = sin(lat2)
            val dot = (ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0)
            val omega = kotlin.math.acos(dot)
            val (left, right) = if (sin(omega) < 1e-8) {
                (1.0 - fraction) to fraction
            } else {
                (sin((1.0 - fraction) * omega) / sin(omega)) to (sin(fraction * omega) / sin(omega))
            }
            val x = left * ax + right * bx
            val y = left * ay + right * by
            val z = left * az + right * bz
            val latitude = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
            val longitude = Math.toDegrees(atan2(y, x))
            val startMillis = a.instant.toEpochMilli()
            val instant = Instant.ofEpochMilli(startMillis + ((b.instant.toEpochMilli() - startMillis) * fraction).toLong())
            return GeoPoint(instant, latitude, longitude)
        }

        private const val MIN_TRANSFER_THRESHOLD_KM = 60.0
        private const val MAX_TRANSFER_THRESHOLD_KM = 120.0
        // A hop must also stand well clear of the journey's ordinary sampling pattern.
        private const val TRANSFER_TO_TYPICAL_RATIO = 3.0
        private const val DEVIATION_MULTIPLIER = 6.0

        private fun median(sorted: DoubleArray, size: Int): Double {
            if (size == 0) return 0.0
            val middle = size / 2
            return if (size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            } else {
                sorted[middle]
            }
        }

        private data class TransferRange(val startKm: Double, val endKm: Double)
    }
}

private const val MAX_RENDER_STEP_KM = 75.0
private const val MAX_STEPS_PER_SEGMENT = 320

private fun renderSteps(segmentDistance: Double): Int =
    ceil(segmentDistance / MAX_RENDER_STEP_KM).toInt().coerceIn(1, MAX_STEPS_PER_SEGMENT)

private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint =
    Journey.interpolate(a, b, fraction)

internal fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 6371.0088 * 2 * asin(min(1.0, sqrt(h)))
}

data class WorldPoint(val x: Double, val y: Double)

object WebMercator {
    private const val MAX_LATITUDE = 85.05112878

    fun project(point: GeoPoint): WorldPoint = project(point.latitude, point.longitude)

    fun project(latitude: Double, longitude: Double): WorldPoint {
        val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = (longitude + 180.0) / 360.0
        val sinLat = sin(Math.toRadians(lat))
        val y = 0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)
        return WorldPoint(x, y.coerceIn(0.0, 1.0))
    }

    fun shortestWrappedX(values: List<Double>): List<Double> {
        if (values.size < 2) return values
        val directSpan = (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)
        val shifted = values.map { if (it < 0.5) it + 1.0 else it }
        val shiftedSpan = (shifted.maxOrNull() ?: 0.0) - (shifted.minOrNull() ?: 0.0)
        return if (shiftedSpan < directSpan) shifted else values
    }
}
