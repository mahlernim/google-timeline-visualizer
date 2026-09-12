package dev.mahlernim.timelinevisualizer.render

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderTextTest {
    private val august2025: ZonedDateTime = ZonedDateTime.of(2025, 8, 19, 12, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun hiddenDatesLeaveOnlyLocalizedDistanceWithoutASeparator() {
        assertEquals("August 2025  ·  123 km", RenderText.ENGLISH.formatSubtitle(august2025, 123.0))
        val hidden = RenderText.ENGLISH.copy(hideDates = true)
        assertEquals("123 km", hidden.formatSubtitle(august2025, 123.0))
        assertEquals("0 km", hidden.formatSubtitle(august2025, 0.0))
        assertEquals("62 mi", hidden.copy(distanceUnit = "mi", distanceScale = DistanceUnit.MILES.kilometersMultiplier)
            .formatSubtitle(august2025, 100.0))
        assertEquals("123 km", hidden.copy(localeTag = "ja", datePattern = "yyyy年M月")
            .formatSubtitle(august2025, 123.0))
    }

    @Test
    fun validPatternFormatsWithTheRequestedLocale() {
        val text = RenderText.ENGLISH.copy(localeTag = "es", datePattern = "MMMM yyyy")
        assertEquals("agosto 2025", text.dateFormatter.format(august2025))
    }

    @Test
    fun translatedYearLettersFallBackInsteadOfThrowing() {
        // "aaaa" is what translating "yyyy" produced in the Spanish, French, and Portuguese
        // resources; "a" is the reserved am/pm symbol, so ofPattern rejects the pattern.
        val text = RenderText.ENGLISH.copy(localeTag = "pt-BR", datePattern = "MMMM aaaa")
        assertEquals("agosto 2025", text.dateFormatter.format(august2025))
    }

    @Test
    fun unknownPatternLettersFallBackInsteadOfThrowing() {
        // "jjjj" came from translating "yyyy" in the German resources; "j" is not a valid symbol.
        val text = RenderText.ENGLISH.copy(localeTag = "de", datePattern = "MMMM jjjj")
        assertEquals("August 2025", text.dateFormatter.format(august2025))
    }

    @Test
    fun localizedLiteralTextIsPreserved() {
        val text = RenderText.ENGLISH.copy(localeTag = "ja", datePattern = "yyyy年M月")
        assertEquals("2025年8月", text.dateFormatter.format(august2025))
    }

    @Test
    fun distanceScaleChangesTheValueAndUnitTogether() {
        val text = RenderText.ENGLISH.copy(
            distanceUnit = "mi",
            distanceScale = DistanceUnit.MILES.kilometersMultiplier,
        )

        assertEquals("62 mi", text.formatDistance(100.0))
    }
}
