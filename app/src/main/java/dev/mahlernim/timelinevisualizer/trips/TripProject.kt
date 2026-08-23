package dev.mahlernim.timelinevisualizer.trips

import java.time.LocalDate

enum class TripKind { TRIP, MONTHLY_RECAP, YEARLY_RECAP, CUSTOM_RECAP, RAW_DATA }

enum class ProjectTitleMode { AUTOMATIC, CUSTOM }

enum class SuggestionConfidence { STRONG, POSSIBLE }

data class TripProject(
    val id: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val kind: TripKind,
    val createdAtMillis: Long,
    val titleMode: ProjectTitleMode = ProjectTitleMode.CUSTOM,
)
data class TripSuggestion(
    val id: String,
    val destinationName: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val awayStartDate: LocalDate = startDate,
    val awayEndDate: LocalDate = endDate,
    val destinationLatitude: Double = 0.0,
    val destinationLongitude: Double = 0.0,
    val confidence: SuggestionConfidence,
    val distanceFromHomeKm: Double,
    val usablePointCount: Int = 0,
)

data class TripDetectionRequest(
    val startDate: LocalDate,
    val endDate: LocalDate,
)

fun interface DestinationNameResolver {
    fun resolve(latitude: Double, longitude: Double): String?
}
