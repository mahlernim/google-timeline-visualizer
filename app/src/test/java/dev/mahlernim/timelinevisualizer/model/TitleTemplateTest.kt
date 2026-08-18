package dev.mahlernim.timelinevisualizer.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleTemplateTest {
    @Test
    fun resolvesSupportedPlaceholders() {
        assertEquals(
            "2026 Mina's Timeline",
            TitleTemplate.resolve("{year} {name}'s Timeline", 2026, "Mina", "My Timeline"),
        )
    }

    @Test
    fun placeholderNamesAreCaseInsensitive() {
        assertEquals(
            "Mina · 2026",
            TitleTemplate.resolve("{NAME} · {YEAR}", 2026, " Mina ", "My Timeline"),
        )
    }

    @Test
    fun blankResultUsesFallback() {
        assertEquals("My Timeline", TitleTemplate.resolve("  ", 2026, "Mina", "My Timeline"))
    }

    @Test
    fun resolvesYearPlaceholderToMultiYearLabel() {
        assertEquals(
            "2025–2026 Mina's Timeline",
            TitleTemplate.resolve("{year} {name}'s Timeline", "2025–2026", "Mina", "My Timeline"),
        )
    }
}
