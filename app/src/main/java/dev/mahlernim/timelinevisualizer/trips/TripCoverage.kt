package dev.mahlernim.timelinevisualizer.trips

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class TripCoverage(
    val recordedMovementKm: Double,
    val usablePointCount: Int,
    val activeDayCount: Int,
    val movementSegmentCount: Int,
) {
    val limited: Boolean
        get() = usablePointCount < MIN_USEFUL_POINTS ||
            activeDayCount < MIN_ACTIVE_DAYS ||
            movementSegmentCount < MIN_MOVEMENT_SEGMENTS

    private companion object {
        const val MIN_USEFUL_POINTS = 10
        const val MIN_ACTIVE_DAYS = 2
        const val MIN_MOVEMENT_SEGMENTS = 3
    }
}

object TripCoverageCalculator {
    private const val MIN_MOVEMENT_KM = 0.05
    private const val MIN_ACTIVE_DAY_KM = 1.0
    private const val MAX_LOCAL_SEGMENT_KM = 250.0

    fun calculate(
        timeline: Timeline?,
        startDate: LocalDate,
        endDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TripCoverage = calculateConnected(
        timelines = listOfNotNull(timeline),
        startDate = startDate,
        endDate = endDate,
        zone = zone,
    )

    /** Calculates coverage without joining independently connected route components. */
    fun calculateConnected(
        timelines: List<Timeline>,
        startDate: LocalDate,
        endDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TripCoverage {
        val components = timelines.map { timeline ->
            timeline.points.filter { point ->
                val date = point.instant.atZone(zone).toLocalDate()
                !date.isBefore(startDate) && !date.isAfter(endDate)
            }
        }
        val usablePointCount = components.sumOf(List<GeoPoint>::size)
        if (usablePointCount < 2) return TripCoverage(0.0, usablePointCount, 0, 0)

        val movementByDay = mutableMapOf<LocalDate, Double>()
        var totalMovement = 0.0
        var segmentCount = 0
        components.forEach { points ->
            points.zipWithNext().forEach { (from, to) ->
                val fromDate = from.instant.atZone(zone).toLocalDate()
                val toDate = to.instant.atZone(zone).toLocalDate()
                if (fromDate != toDate) return@forEach
                val distance = distanceKm(from, to)
                if (distance < MIN_MOVEMENT_KM || distance > MAX_LOCAL_SEGMENT_KM) return@forEach
                totalMovement += distance
                segmentCount += 1
                movementByDay[toDate] = movementByDay.getOrDefault(toDate, 0.0) + distance
            }
        }
        return TripCoverage(
            recordedMovementKm = totalMovement,
            usablePointCount = usablePointCount,
            activeDayCount = movementByDay.values.count { it >= MIN_ACTIVE_DAY_KM },
            movementSegmentCount = segmentCount,
        )
    }

    private fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 6371.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
