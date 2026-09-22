package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.JourneySemanticEpisode
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapAnalyzerTest {
    private val utc = ZoneId.of("UTC")

    @Test fun partialLabelsDoNotBecomeOneHundredPercent() {
        val journey = segments(listOf(segment(1, 12, 13, 100.0)))
        val sparse = RecapAnalyzer.analyze(labeled(journey, Triple(0.0, 25.0, "WALKING")), utc)
        assertEquals(RecapTransportMode.UNKNOWN, sparse.dominantMode)
        assertNull(sparse.dominantModeShare)
        val covered = RecapAnalyzer.analyze(labeled(journey, Triple(0.0, 80.0, "WALKING")), utc)
        assertEquals(RecapTransportMode.ON_FOOT, covered.dominantMode)
        assertEquals(0.8, covered.dominantModeShare!!, 1e-9)
    }

    @Test fun duplicateLabelsCountOnceAndConflictingLabelsRemainUnknown() {
        val journey = segments(listOf(segment(1, 12, 13, 100.0)))
        val duplicate = RecapAnalyzer.analyze(labeled(journey,
            Triple(0.0, 100.0, "CYCLING"), Triple(0.0, 100.0, "CYCLING")), utc)
        assertEquals(1.0, duplicate.dominantModeShare!!, 1e-9)
        val conflict = RecapAnalyzer.analyze(labeled(journey,
            Triple(0.0, 100.0, "CYCLING"), Triple(50.0, 100.0, "WALKING")), utc)
        assertEquals(RecapTransportMode.UNKNOWN, conflict.dominantMode)
    }

    @Test fun inferredTransferIsExcludedFromTotalAndTransport() {
        val journey = segments(listOf(segment(1, 12, 13, 90.0), segment(2, 12, 13, 10.0)))
            .copy(inferredTransferBeforePointIndices = listOf(1))
        val recap = RecapAnalyzer.analyze(labeled(journey,
            Triple(0.0, 90.0, "FLYING"), Triple(90.0, 100.0, "WALKING")), utc)
        assertEquals(10.0, recap.totalDistanceKm, 1e-9)
        assertEquals(1, recap.movementDays)
        assertEquals(RecapTransportMode.ON_FOOT, recap.dominantMode)
        assertEquals(1.0, recap.dominantModeShare!!, 1e-9)
    }

    @Test fun unknownTransportIsNotGuessedFromSpeedAndShortTripsStillCount() {
        val recap = RecapAnalyzer.analyze(segments(listOf(segment(1, 12, 13, 0.2))), utc)
        assertEquals(RecapTransportMode.UNKNOWN, recap.dominantMode)
        assertEquals(1, recap.movementDays)
        assertEquals(0.2, recap.totalDistanceKm, 1e-9)
        assertEquals(RecapPersonalityId.MAIN_CHARACTER_ON_THE_MOVE, recap.personalityId)
        assertEquals(1.0, recap.personalityValue, 0.0)
    }

    @Test fun midnightSplitsDaysAndNightWindowSplitsAtNinePm() {
        val midnight = segments(listOf(Triple("2026-01-01T23:00:00Z", "2026-01-02T01:00:00Z", 10.0)))
        assertEquals(2, RecapAnalyzer.analyze(midnight, utc).movementDays)
        val night = RecapAnalyzer.analyze(segments((1..4).map { segment(it, 20, 22, 10.0) }), utc)
        assertEquals(RecapPersonalityId.MAIN_CHARACTER_AFTER_DARK, night.personalityId)
        assertEquals(0.5, night.personalityValue, 1e-9)
    }

    @Test fun nightEndsAtFiveAmAndMorningEndsAtNineAm() {
        val dawn = RecapAnalyzer.analyze(segments((1..4).map { segment(it, 4, 6, 10.0) }), utc)
        assertEquals(RecapPersonalityId.MAIN_CHARACTER_AFTER_DARK, dawn.personalityId)
        assertEquals(0.5, dawn.personalityValue, 1e-9)
        val morning = RecapAnalyzer.analyze(segments((1..4).map { segment(it, 8, 10, 10.0) }), utc)
        assertEquals(RecapPersonalityId.EARLY_BIRD_DLC, morning.personalityId)
        assertEquals(0.5, morning.personalityValue, 1e-9)
    }

    @Test fun longRecordingGapDoesNotCreateIntermediateMovementDaysOrAStory() {
        val recap = RecapAnalyzer.analyze(segments(listOf(
            Triple("2026-01-01T23:00:00Z", "2026-01-31T01:00:00Z", 1000.0),
        )), utc)
        assertEquals(2, recap.movementDays)
        assertEquals(RecapPersonalityId.MAIN_CHARACTER_ON_THE_MOVE, recap.personalityId)
    }

    @Test fun contrastingEvidenceSelectsDifferentRepeatablePunchlines() {
        val weekend = RecapAnalyzer.analyze(segments(listOf(3, 4, 17, 18).map { segment(it, 12, 13, 10.0) }), utc)
        val plot = RecapAnalyzer.analyze(segments(listOf(segment(1, 12, 13, 80.0), segment(2, 12, 13, 10.0), segment(3, 12, 13, 10.0))), utc)
        val longJourney = segments((1..4).map { segment(it, 12, 13, 600.0) })
        val long = RecapAnalyzer.analyze(longJourney, utc)
        val streak = RecapAnalyzer.analyze(segments((1..31).map { segment(it, 12, 13, 10.0) }), utc)
        assertEquals(RecapPersonalityId.WEEKEND_MAIN_CHARACTER, weekend.personalityId)
        assertEquals(RecapPersonalityId.ONE_DAY_PLOT_TWIST, plot.personalityId)
        assertEquals(RecapPersonalityId.LONG_HAUL_ENERGY, long.personalityId)
        assertEquals(RecapPersonalityId.STREAK_MODE_UNLOCKED, streak.personalityId)
        assertEquals(long, RecapAnalyzer.analyze(longJourney, utc))
    }

    @Test fun oneDominantDayUsesPlotTwistWhileSeveralLargeDaysCanUseChaos() {
        val singlePeak = segments((1..30).map { day -> segment(day, 12, 13, if (day == 1) 600.0 else 10.0) })
        val severalPeaks = segments((1..30).map { day -> segment(day, 12, 13, if (day <= 4) 180.0 else 1.0) })
        val single = RecapAnalyzer.analyze(singlePeak, utc)
        val several = RecapAnalyzer.analyze(severalPeaks, utc)
        assertEquals(RecapPersonalityId.ONE_DAY_PLOT_TWIST, single.personalityId)
        assertEquals(600.0 / 890.0, single.personalityValue, 1e-9)
        assertEquals(RecapPersonalityId.CHAOS_COORDINATOR, several.personalityId)
        assertEquals(180.0 / 746.0, several.personalityValue, 1e-9)
    }

    @Test fun emptyAndDisconnectedRoutesRemainFinite() {
        val recap = RecapAnalyzer.analyze(Journey.from(emptyList(), 2026), utc)
        assertEquals(0.0, recap.totalDistanceKm, 0.0)
        assertEquals(0, recap.movementDays)
        assertTrue(recap.analogyCount.isFinite())
    }

    private fun segment(day: Int, startHour: Int, endHour: Int, km: Double) = Triple(
        "2026-01-${day.toString().padStart(2, '0')}T${startHour.toString().padStart(2, '0')}:00:00Z",
        "2026-01-${day.toString().padStart(2, '0')}T${endHour.toString().padStart(2, '0')}:00:00Z", km,
    )

    private fun segments(parts: List<Triple<String, String, Double>>): Journey {
        val points = mutableListOf<GeoPoint>()
        val cumulative = mutableListOf<Double>()
        var km = 0.0
        parts.forEach { (from, to, length) ->
            points += GeoPoint(Instant.parse(from), 0.0, 0.0)
            cumulative += km
            km += length
            points += GeoPoint(Instant.parse(to), 0.1, 0.1)
            cumulative += km
        }
        return Journey(TimelinePeriod.sameYear(2026), points, cumulative.toDoubleArray(), (1 until parts.size).map { it * 2 })
    }

    private fun labeled(journey: Journey, vararg labels: Triple<Double, Double, String>) = journey.copy(
        semanticEpisodes = labels.map { (start, end, label) ->
            JourneySemanticEpisode(start, end, journey.points.first(), journey.points.last(), label)
        },
    )
}
