package dev.mahlernim.timelinevisualizer.model

import java.time.Instant
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
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
    val years: List<Int> = points.map { it.year }.distinct().sortedDescending()

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
        val selected = points.filter {
            val date = it.instant.atZone(ZoneId.systemDefault())
            val month = YearMonth.of(date.year, date.monthValue)
            month >= period.start && month <= period.endInclusive
        }.sortedBy { it.instant }
        return Journey.from(selected, period)
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
    val fromIndex: Int,
    val toIndex: Int,
    val segmentFraction: Double,
)

data class RouteSample(
    val point: GeoPoint,
    val distanceKm: Double,
)

data class Journey(
    val period: TimelinePeriod,
    val points: List<GeoPoint>,
    val cumulativeDistanceKm: DoubleArray,
) {
    val year: Int get() = period.startYear
    val totalDistanceKm: Double get() = cumulativeDistanceKm.lastOrNull() ?: 0.0
    val renderPath: List<RouteSample> = buildRenderPath()

    fun pointIndexAt(progress: Float): Int {
        return positionAt(progress).toIndex
    }

    fun positionAt(progress: Float): JourneyPosition = positionAtDistance(
        totalDistanceKm * progress.coerceIn(0f, 1f),
    )

    fun positionAtDistance(distanceKm: Double): JourneyPosition {
        if (points.isEmpty()) {
            val epoch = GeoPoint(Instant.EPOCH, 0.0, 0.0)
            return JourneyPosition(epoch, 0.0, 0, 0, 0.0)
        }
        if (points.size == 1 || totalDistanceKm <= 0.0) {
            return JourneyPosition(points.first(), 0.0, 0, 0, 0.0)
        }
        val target = distanceKm.coerceIn(0.0, totalDistanceKm)
        val exact = cumulativeDistanceKm.binarySearch(target)
        if (exact >= 0) return JourneyPosition(points[exact], target, exact, exact, 0.0)

        val to = (-exact - 1).coerceIn(1, points.lastIndex)
        val from = to - 1
        val segmentDistance = cumulativeDistanceKm[to] - cumulativeDistanceKm[from]
        val fraction = if (segmentDistance <= 0.0) 0.0 else
            ((target - cumulativeDistanceKm[from]) / segmentDistance).coerceIn(0.0, 1.0)
        return JourneyPosition(
            interpolate(points[from], points[to], fraction),
            target,
            from,
            to,
            fraction,
        )
    }

    private fun buildRenderPath(): List<RouteSample> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(RouteSample(points.first(), 0.0))
        return buildList {
            add(RouteSample(points.first(), 0.0))
            for (index in 1..points.lastIndex) {
                val startDistance = cumulativeDistanceKm[index - 1]
                val segmentDistance = cumulativeDistanceKm[index] - startDistance
                val steps = ceil(segmentDistance / MAX_RENDER_STEP_KM).toInt().coerceIn(1, MAX_STEPS_PER_SEGMENT)
                for (step in 1..steps) {
                    val fraction = step.toDouble() / steps
                    add(
                        RouteSample(
                            interpolate(points[index - 1], points[index], fraction),
                            startDistance + segmentDistance * fraction,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        fun from(points: List<GeoPoint>, year: Int): Journey = from(points, TimelinePeriod.sameYear(year))

        fun from(points: List<GeoPoint>, period: TimelinePeriod): Journey {
            val distances = DoubleArray(points.size)
            for (index in 1 until points.size) {
                distances[index] = distances[index - 1] + haversineKm(points[index - 1], points[index])
            }
            return Journey(period, points, distances)
        }

        private fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 6371.0088 * 2 * asin(min(1.0, sqrt(h)))
        }

        private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
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

        private const val MAX_RENDER_STEP_KM = 75.0
        private const val MAX_STEPS_PER_SEGMENT = 320
    }
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
