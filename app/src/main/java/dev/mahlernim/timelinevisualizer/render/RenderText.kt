package dev.mahlernim.timelinevisualizer.render

import java.util.Locale

data class RenderText(
    val localeTag: String,
    val fallbackTitle: String,
    val datePattern: String,
    val distanceUnit: String,
    val attribution: String,
) {
    val locale: Locale get() = Locale.forLanguageTag(localeTag).takeUnless { it.language.isBlank() } ?: Locale.ENGLISH

    companion object {
        val ENGLISH = RenderText(
            localeTag = "en",
            fallbackTitle = "My Timeline",
            datePattern = "MMMM yyyy",
            distanceUnit = "km",
            attribution = "© OpenStreetMap  © CARTO",
        )
    }
}
