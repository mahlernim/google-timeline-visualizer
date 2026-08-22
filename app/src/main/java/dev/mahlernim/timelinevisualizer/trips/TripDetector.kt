package dev.mahlernim.timelinevisualizer.trips

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** A deliberately conservative, local-only first pass for the experimental Trips Lab. */
object TripDetector {
    private const val AWAY_DISTANCE_KM = 120.0
    private const val STRONG_DISTANCE_KM = 250.0
    private const val MAX_DESTINATION_RADIUS_KM = 250.0

    fun detect(timeline: Timeline, zone: ZoneId = ZoneId.systemDefault()): List<TripSuggestion> {
        val days = timeline.points
            .groupBy { it.instant.atZone(zone).toLocalDate() }
            .mapValues { (_, points) -> center(points) }
            .toSortedMap()
        if (days.size < 5) return emptyList()

        val homeCell = days.values.groupingBy { point ->
            Cell(round(point.latitude * 2.0) / 2.0, round(point.longitude * 2.0) / 2.0)
        }.eachCount().maxByOrNull { it.value }?.key ?: return emptyList()
        val homePoints = days.values.filter {
            round(it.latitude * 2.0) / 2.0 == homeCell.latitude &&
                round(it.longitude * 2.0) / 2.0 == homeCell.longitude
        }
        val home = center(homePoints)
        val ordered = days.entries.toList()
        val away = ordered.map { distanceKm(home, it.value) >= AWAY_DISTANCE_KM }
        val results = mutableListOf<TripSuggestion>()
        var index = 0
        while (index < ordered.size) {
            if (!away[index]) {
                index += 1
                continue
            }
            val startIndex = index
            while (
                index + 1 < ordered.size && away[index + 1] &&
                ordered[index + 1].key.toEpochDay() - ordered[index].key.toEpochDay() <= 2
            ) index += 1
            val endIndex = index
            val episode = ordered.subList(startIndex, endIndex + 1)
            val spanDays = episode.last().key.toEpochDay() - episode.first().key.toEpochDay() + 1
            val destination = center(episode.map { it.value })
            val radius = episode.maxOf { distanceKm(destination, it.value) }
            val maxDistance = episode.maxOf { distanceKm(home, it.value) }
            val bracketed = startIndex > 0 && endIndex < ordered.lastIndex && !away[startIndex - 1] && !away[endIndex + 1]
            if (spanDays >= 2 && radius <= MAX_DESTINATION_RADIUS_KM) {
                val start = episode.first().key
                val end = episode.last().key
                val confidence = if (bracketed && maxDistance >= STRONG_DISTANCE_KM) {
                    SuggestionConfidence.STRONG
                } else {
                    SuggestionConfidence.POSSIBLE
                }
                results += TripSuggestion(
                    id = stableId(start, end, destination),
                    title = "Suggested trip",
                    startDate = start,
                    endDate = end,
                    confidence = confidence,
                    distanceFromHomeKm = maxDistance,
                )
            }
            index += 1
        }
        return results.sortedWith(compareByDescending<TripSuggestion> { it.confidence == SuggestionConfidence.STRONG }
            .thenByDescending { it.startDate })
    }

    private fun center(points: List<GeoPoint>): GeoPoint {
        val first = points.first()
        return GeoPoint(
            instant = first.instant,
            latitude = points.sumOf(GeoPoint::latitude) / points.size,
            longitude = points.sumOf(GeoPoint::longitude) / points.size,
        )
    }

    private fun stableId(start: LocalDate, end: LocalDate, destination: GeoPoint): String {
        val raw = "$start|$end|${"%.2f".format(java.util.Locale.ROOT, destination.latitude)}|${"%.2f".format(java.util.Locale.ROOT, destination.longitude)}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    private fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 6_371.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private data class Cell(val latitude: Double, val longitude: Double)
}
