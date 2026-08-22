package dev.mahlernim.timelinevisualizer.trips

import java.time.LocalDate

enum class TripKind { TRIP, MONTHLY_RECAP, YEARLY_RECAP }

enum class SuggestionConfidence { STRONG, POSSIBLE }

data class TripProject(
    val id: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val kind: TripKind,
    val createdAtMillis: Long,
)
data class TripSuggestion(
    val id: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val confidence: SuggestionConfidence,
    val distanceFromHomeKm: Double,
)
