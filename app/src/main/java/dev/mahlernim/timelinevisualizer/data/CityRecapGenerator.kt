package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class VisitedCity(
    val primaryArea: String,
    val subAreas: List<String>,
    val date: String,
    val startMillis: Long,
)

class CityRecapGenerator(private val context: Context) {
    // Configurable parameters for stop detection
    private val minDwellDurationMs: Long = 30 * 60 * 1000L // 30 minutes
    private val clusterRadiusKm: Double = 1.0 // 1 km radius to consider as same stop

    suspend fun generateRecap(journey: Journey): List<VisitedCity> = withContext(Dispatchers.IO) {
        val points = journey.points
        if (points.isEmpty()) return@withContext emptyList()

        val candidateStops = detectStops(points)
        val representativePoints = mergeNearbyStops(candidateStops)

        val geocoder = Geocoder(context)
        val results = mutableListOf<VisitedCity>()
        val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.systemDefault())

        for (stop in representativePoints) {
            val dateStr = dateFormatter.format(stop.instant)
            val address = try {
                geocoder.getFromLocation(stop.latitude, stop.longitude, 1)?.firstOrNull()
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }

            if (address != null) {
                val primaryArea = address.locality
                    ?: address.subAdminArea
                    ?: address.adminArea
                    ?: address.countryName

                if (primaryArea == null) continue

                val subAreas = mutableListOf<String>()
                address.subLocality?.let { if (it != primaryArea) subAreas.add(it) }
                address.featureName?.let { 
                    if (it != primaryArea && it != address.subLocality && !subAreas.contains(it) && !it.matches(Regex("^[0-9+]+$"))) {
                        subAreas.add(it) 
                    }
                }

                results.add(
                    VisitedCity(
                        primaryArea = primaryArea,
                        subAreas = subAreas.distinct(),
                        date = dateStr,
                        startMillis = stop.instant.toEpochMilli()
                    )
                )
            }
        }

        // Deduplicate grouping by Date and Primary Area (regardless of sequence on that day)
        val deduplicated = mutableListOf<VisitedCity>()
        for (result in results) {
            val existingIndex = deduplicated.indexOfFirst { it.date == result.date && it.primaryArea == result.primaryArea }
            if (existingIndex != -1) {
                val existing = deduplicated[existingIndex]
                val mergedSubAreas = (existing.subAreas + result.subAreas).distinct()
                deduplicated[existingIndex] = existing.copy(subAreas = mergedSubAreas)
            } else {
                deduplicated.add(result)
            }
        }

        return@withContext deduplicated
    }

    private fun detectStops(points: List<GeoPoint>): List<GeoPoint> {
        val stops = mutableListOf<GeoPoint>()
        if (points.isEmpty()) return stops

        var clusterStart = points[0]
        var clusterLast = points[0]

        for (i in 1 until points.size) {
            val pt = points[i]
            val dist = haversineKm(clusterStart, pt)
            if (dist <= clusterRadiusKm) {
                clusterLast = pt
            } else {
                // If the user stayed in this cluster for >30 mins, 
                // OR if there is a massive gap (>30 mins) before the next known location 
                // (which happens when Timeline compresses a visit into a single point)
                val clusterSpan = clusterLast.instant.toEpochMilli() - clusterStart.instant.toEpochMilli()
                val gapToNext = pt.instant.toEpochMilli() - clusterLast.instant.toEpochMilli()
                
                if (clusterSpan >= minDwellDurationMs || gapToNext >= minDwellDurationMs) {
                    stops.add(clusterStart) // Use start point as representative
                }
                clusterStart = pt
                clusterLast = pt
            }
        }

        // For the very last point/cluster, we safely assume it's a stop 
        // since the selected journey ends there.
        stops.add(clusterStart)

        return stops
    }

    private fun mergeNearbyStops(stops: List<GeoPoint>): List<GeoPoint> {
        val merged = mutableListOf<GeoPoint>()
        if (stops.isEmpty()) return merged

        var current = stops[0]
        merged.add(current)

        for (i in 1 until stops.size) {
            val pt = stops[i]
            if (haversineKm(current, pt) > clusterRadiusKm) {
                merged.add(pt)
                current = pt
            }
        }

        return merged
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
}
