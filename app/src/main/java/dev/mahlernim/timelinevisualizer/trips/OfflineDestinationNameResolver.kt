package dev.mahlernim.timelinevisualizer.trips

import android.content.Context
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Resolves a coarse destination label from bundled GeoNames data without network access. */
class OfflineDestinationNameResolver(context: Context) : DestinationNameResolver {
    private val assets = context.applicationContext.assets
    private val countries by lazy { loadCountries() }
    private val regions by lazy { loadRegions() }
    private val cities by lazy { loadCities() }

    override fun resolve(latitude: Double, longitude: Double): String? {
        val nearest = cities.minByOrNull { distanceKm(latitude, longitude, it.latitude, it.longitude) }
            ?: return null
        val distance = distanceKm(latitude, longitude, nearest.latitude, nearest.longitude)
        val city = cities.asSequence()
            .filter { distanceKm(latitude, longitude, it.latitude, it.longitude) <= MAJOR_CITY_MATCH_KM }
            .maxByOrNull(City::population)
            ?: nearest
        val country = countries[city.countryCode]
        if (distance <= CITY_MATCH_KM) {
            return listOfNotNull(city.name, country).distinct().joinToString(", ").takeIf(String::isNotBlank)
        }
        if (distance <= REGION_MATCH_KM) {
            val region = regions["${nearest.countryCode}.${nearest.admin1Code}"]
            return listOfNotNull(region, country).distinct().joinToString(", ").takeIf(String::isNotBlank)
        }
        return null
    }

    private fun loadCountries(): Map<String, String> = assets.open(COUNTRIES_ASSET).bufferedReader().useLines { lines ->
        lines.filterNot { it.startsWith("#") }.mapNotNull { line ->
            val fields = line.split('\t')
            fields.getOrNull(0)?.takeIf(String::isNotBlank)?.let { it to fields.getOrNull(4).orEmpty() }
        }.toMap()
    }

    private fun loadRegions(): Map<String, String> = assets.open(REGIONS_ASSET).bufferedReader().useLines { lines ->
        lines.mapNotNull { line ->
            val fields = line.split('\t')
            fields.getOrNull(0)?.takeIf(String::isNotBlank)?.let { it to fields.getOrNull(1).orEmpty() }
        }.toMap()
    }

    private fun loadCities(): List<City> = assets.open(CITIES_ASSET).bufferedReader().useLines { lines ->
        lines.mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 11) return@mapNotNull null
            City(
                name = fields[1],
                latitude = fields[4].toDoubleOrNull() ?: return@mapNotNull null,
                longitude = fields[5].toDoubleOrNull() ?: return@mapNotNull null,
                countryCode = fields[8],
                admin1Code = fields[10],
                population = fields.getOrNull(14)?.toLongOrNull() ?: 0,
            )
        }.toList()
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val firstLat = Math.toRadians(lat1)
        val secondLat = Math.toRadians(lat2)
        val latitudeDelta = secondLat - firstLat
        val longitudeDelta = Math.toRadians(lon2 - lon1)
        val h = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(firstLat) * cos(secondLat) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 6_371.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private data class City(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val countryCode: String,
        val admin1Code: String,
        val population: Long,
    )

    companion object {
        private const val CITIES_ASSET = "geonames/cities15000.txt"
        private const val REGIONS_ASSET = "geonames/admin1CodesASCII.txt"
        private const val COUNTRIES_ASSET = "geonames/countryInfo.txt"
        private const val CITY_MATCH_KM = 75.0
        private const val MAJOR_CITY_MATCH_KM = 25.0
        private const val REGION_MATCH_KM = 250.0
    }
}
