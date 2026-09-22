package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapLocalizerTest {
    private val sample = RecapSnapshot(
        totalDistanceKm = 161.0,
        movementDays = 14,
        dominantMode = RecapTransportMode.CYCLING,
        dominantModeShare = 0.75,
        personalityId = RecapPersonalityId.MAIN_CHARACTER_AFTER_DARK,
        personalityValue = 0.5,
        analogyKind = RecapAnalogyKind.MARATHONS,
        analogyCount = 4.0,
    )

    @Test
    fun approvedMockupCopyNamesTheActualTimeWindow() {
        val text = RecapLocalizer(RenderText.ENGLISH)
        assertEquals("Main Character After Dark", text.personalityTitle(sample.personalityId))
        assertEquals("50% of recorded distance was between 9 pm and 5 am.", text.personalityEvidence(sample))
        assertEquals("About 4 marathons", text.analogy(sample.analogyKind, sample.analogyCount))
        assertEquals("75%", text.formatPercent(sample.dominantModeShare!!))
        assertEquals("Cycling", text.transportName(sample.dominantMode))
    }

    @Test
    fun everySupportedLocaleHasLocalizedTitlesEvidenceAndLabels() {
        val english = RecapLocalizer(RenderText.ENGLISH)
        for (tag in SUPPORTED_LOCALES) {
            val text = RecapLocalizer(RenderText.ENGLISH.copy(localeTag = tag))
            assertTrue("$tag distance label", text.totalDistanceLabel.isNotBlank())
            assertTrue("$tag movement label", text.movementDaysLabel.isNotBlank())
            for (mode in RecapTransportMode.entries) {
                assertTrue("$tag $mode", text.transportName(mode).isNotBlank())
            }
            for (kind in RecapAnalogyKind.entries) {
                assertTrue("$tag $kind", text.analogy(kind, 1.5).isNotBlank())
            }
            for (id in RecapPersonalityId.entries) {
                val title = text.personalityTitle(id)
                val evidence = text.personalityEvidence(sample.copy(personalityId = id))
                assertTrue("$tag $id title", title.isNotBlank())
                assertTrue("$tag $id evidence", evidence.isNotBlank())
                if (tag != "en") {
                    assertNotEquals("$tag $id title must not fall back to English", english.personalityTitle(id), title)
                    assertNotEquals("$tag $id evidence must not fall back to English", english.personalityEvidence(sample.copy(personalityId = id)), evidence)
                }
            }
        }
    }

    @Test
    fun distanceEvidenceUsesTheCurrentUnitsRatherThanCachedKilometers() {
        val miles = RecapLocalizer(RenderText.ENGLISH.copy(
            distanceUnit = "mi",
            distanceScale = DistanceUnit.MILES.kilometersMultiplier,
        ))
        val longHaul = sample.copy(personalityId = RecapPersonalityId.LONG_HAUL_ENERGY, personalityValue = 100.0)
        val streak = sample.copy(personalityId = RecapPersonalityId.STREAK_MODE_UNLOCKED, personalityValue = 14.0)
        assertEquals("An average of 62 mi per day with recorded movement.", miles.personalityEvidence(longHaul))
        assertEquals("14 consecutive days with at least 3 mi recorded each.", miles.personalityEvidence(streak))
        for (tag in SUPPORTED_LOCALES) {
            val text = RecapLocalizer(RenderText.ENGLISH.copy(
                localeTag = tag,
                distanceUnit = "mi",
                distanceScale = DistanceUnit.MILES.kilometersMultiplier,
            ))
            for (snapshot in listOf(longHaul, streak)) {
                val evidence = text.personalityEvidence(snapshot)
                assertTrue("$tag uses selected unit", evidence.contains("mi"))
                assertFalse("$tag does not hard-code kilometers", evidence.contains("km"))
            }
        }
    }

    @Test
    fun localeChangesReformatNumbersAndChineseScriptsResolveConsistently() {
        val german = RecapLocalizer(RenderText.ENGLISH.copy(localeTag = "de"))
        assertEquals("Etwa 1,5 Marathons", german.analogy(RecapAnalogyKind.MARATHONS, 1.5))
        val taiwan = RecapLocalizer(RenderText.ENGLISH.copy(localeTag = "zh-TW"))
        val traditional = RecapLocalizer(RenderText.ENGLISH.copy(localeTag = "zh-Hant"))
        val simplified = RecapLocalizer(RenderText.ENGLISH.copy(localeTag = "zh-CN"))
        assertEquals(taiwan.totalDistanceLabel, traditional.totalDistanceLabel)
        assertEquals(taiwan.personalityEvidence(sample), traditional.personalityEvidence(sample))
        assertNotEquals(taiwan.totalDistanceLabel, simplified.totalDistanceLabel)
        assertEquals("Main Character After Dark", RecapLocalizer(RenderText.ENGLISH.copy(localeTag = "nl"))
            .personalityTitle(sample.personalityId))
    }

    @Test
    fun earlyBirdAndFallbackDescribeRecordedEvidenceWithoutImplyingInactivity() {
        val text = RecapLocalizer(RenderText.ENGLISH)
        assertEquals("50% of recorded distance was between 5 am and 9 am.", text.personalityEvidence(
            sample.copy(personalityId = RecapPersonalityId.EARLY_BIRD_DLC),
        ))
        assertEquals("Movement recorded on 1 day.", text.personalityEvidence(
            sample.copy(personalityId = RecapPersonalityId.MAIN_CHARACTER_ON_THE_MOVE, personalityValue = 1.0),
        ))
    }

    private companion object {
        val SUPPORTED_LOCALES = listOf("en", "ko", "ja", "de", "es", "fr", "pt-BR", "zh-CN", "zh-TW", "id", "vi")
    }
}
