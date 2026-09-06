package dev.mahlernim.timelinevisualizer.render

import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale

enum class DistanceUnit(
    val symbol: String,
    val kilometersMultiplier: Double,
) {
    KILOMETERS("km", 1.0),
    MILES("mi", 0.621371192237334),
    ;

    fun fromKilometers(kilometers: Double): Double = kilometers * kilometersMultiplier

    companion object {
        fun automatic(locale: Locale): DistanceUnit =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) automaticFromIcu(locale) else automaticFallback(locale)

        @RequiresApi(Build.VERSION_CODES.P)
        private fun automaticFromIcu(locale: Locale): DistanceUnit = when (
            LocaleData.getMeasurementSystem(ULocale.forLocale(locale))
        ) {
            LocaleData.MeasurementSystem.US, LocaleData.MeasurementSystem.UK -> MILES
            else -> KILOMETERS
        }

        private fun automaticFallback(locale: Locale): DistanceUnit =
            if (locale.country.uppercase(Locale.ROOT) in MILE_REGIONS) MILES else KILOMETERS

        private val MILE_REGIONS = setOf("GB", "LR", "MM", "US")
    }
}

enum class DistanceUnitPreference {
    AUTOMATIC,
    KILOMETERS,
    MILES,
    ;

    fun resolve(systemLocale: Locale): DistanceUnit = when (this) {
        AUTOMATIC -> DistanceUnit.automatic(systemLocale)
        KILOMETERS -> DistanceUnit.KILOMETERS
        MILES -> DistanceUnit.MILES
    }

    companion object {
        val selectable = listOf(KILOMETERS, MILES)

        fun fromSelection(position: Int): DistanceUnitPreference = selectable[position]

        fun selectionIndex(preference: DistanceUnitPreference, systemLocale: Locale): Int =
            preference.resolve(systemLocale).ordinal
    }
}
