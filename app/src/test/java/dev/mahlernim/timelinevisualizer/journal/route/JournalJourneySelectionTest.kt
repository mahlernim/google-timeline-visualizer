package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalJourneySelectionTest {
    @Test
    fun sourceChangesRemainConnectedButExplicitGapStartsANewSection() {
        val route = JournalRoute(
            timeline = Timeline(listOf(point(0, 0.0), point(10, 0.1), point(20, 0.2), point(60, 20.0), point(70, 20.1))),
            spans = listOf(
                span(RouteSource.SEMANTIC_PATH, point(0, 0.0), point(10, 0.1)),
                span(RouteSource.DETAILED, point(20, 0.2)),
                RouteSpan(
                    start = instant(20),
                    end = instant(60),
                    source = RouteSource.GAP,
                    points = emptyList(),
                ),
                span(RouteSource.DETAILED, point(60, 20.0), point(70, 20.1)),
            ),
            detailedInputCount = 3,
            detailedUsableCount = 3,
            semanticUsableCount = 2,
        )

        val journey = route.journeyForRange(TimelinePeriod.sameYear(2026), ZoneOffset.UTC)

        assertEquals(listOf(3), journey.breakBeforePointIndices)
        assertTrue(journey.isConnectedToPrevious(2))
        assertFalse(journey.isConnectedToPrevious(3))
    }

    @Test
    fun exactDateSelectionRetainsGapWhenBothSectionsAreSelected() {
        val before = GeoPoint(Instant.parse("2026-01-01T23:00:00Z"), 0.0, 0.0)
        val after = GeoPoint(Instant.parse("2026-01-02T01:00:00Z"), 20.0, 20.0)
        val route = JournalRoute(
            timeline = Timeline(listOf(before, after)),
            spans = listOf(
                span(RouteSource.DETAILED, before),
                RouteSpan(before.instant, after.instant, RouteSource.GAP, emptyList()),
                span(RouteSource.DETAILED, after),
            ),
            detailedInputCount = 2,
            detailedUsableCount = 2,
            semanticUsableCount = 0,
        )

        val journey = route.journeyForDateRange(
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-01-02"),
            ZoneOffset.UTC,
        )

        assertEquals(listOf(1), journey.breakBeforePointIndices)
    }

    @Test
    fun inferredTransferConnectsVideoSectionsInsteadOfCreatingABreak() {
        val before = point(0, 0.0)
        val after = point(8 * 60, 20.0)
        val route = JournalRoute(
            timeline = Timeline(listOf(before, after)),
            spans = listOf(
                span(RouteSource.SEMANTIC_PATH, before),
                span(RouteSource.INFERRED_TRANSFER, before, after),
                span(RouteSource.SEMANTIC_PATH, after),
            ),
            detailedInputCount = 0,
            detailedUsableCount = 0,
            semanticUsableCount = 2,
        )

        val journey = route.journeyForRange(TimelinePeriod.sameYear(2026), ZoneOffset.UTC)

        assertEquals(emptyList<Int>(), journey.breakBeforePointIndices)
        assertEquals(listOf(1), journey.inferredTransferBeforePointIndices)
        assertTrue(journey.isConnectedToPrevious(1))
        assertTrue(journey.isInferredTransferFromPrevious(1))
        assertEquals(listOf(0.0, 20.0), journey.points.map(GeoPoint::latitude))
        assertTrue(journey.totalDistanceKm > 100.0)
        assertEquals(0.0, journey.knownDistanceKm, 0.0)
    }

    private fun span(source: RouteSource, vararg points: GeoPoint) = RouteSpan(
        start = points.first().instant,
        end = points.last().instant,
        source = source,
        points = points.toList(),
    )

    private fun point(minutes: Long, latitude: Double) = GeoPoint(
        instant = instant(minutes),
        latitude = latitude,
        longitude = latitude,
    )

    private fun instant(minutes: Long): Instant = BASE.plusSeconds(minutes * 60)

    private companion object {
        val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
