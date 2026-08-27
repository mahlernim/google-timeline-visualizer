package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalRouteTopologyTest {
    @Test
    fun connectedTimelinesNeverCrossExplicitGap() {
        val route = route(
            RouteSpan(point(0).instant, point(10).instant, RouteSource.DETAILED, listOf(point(0), point(10))),
            RouteSpan(point(10).instant, point(20).instant, RouteSource.GAP, emptyList()),
            RouteSpan(point(20).instant, point(30).instant, RouteSource.DETAILED, listOf(point(20), point(30))),
        )

        val components = route.connectedTimelines()

        assertEquals(2, components.size)
        assertEquals(listOf(0L, 10L), components[0].points.map { minute(it.instant) })
        assertEquals(listOf(20L, 30L), components[1].points.map { minute(it.instant) })
    }

    @Test
    fun adjacentSourceSpansRemainOneConnectedComponent() {
        val shared = point(10)
        val route = route(
            RouteSpan(point(0).instant, shared.instant, RouteSource.SEMANTIC_PATH, listOf(point(0), shared)),
            RouteSpan(shared.instant, point(20).instant, RouteSource.DETAILED, listOf(shared, point(20))),
        )

        val component = route.connectedTimelines().single()

        assertEquals(listOf(0L, 10L, 20L), component.points.map { minute(it.instant) })
    }

    @Test
    fun inferredTransferRemainsSeparateForAnalyticsAndIsCounted() {
        val before = point(10, 0.1)
        val after = point(20, 20.0)
        val route = route(
            RouteSpan(point(0).instant, before.instant, RouteSource.SEMANTIC_PATH, listOf(point(0), before)),
            RouteSpan(before.instant, after.instant, RouteSource.INFERRED_TRANSFER, listOf(before, after)),
            RouteSpan(after.instant, point(30, 20.1).instant, RouteSource.SEMANTIC_PATH, listOf(after, point(30, 20.1))),
        )

        val summary = route.summary()

        assertEquals(2, route.connectedTimelines().size)
        assertEquals(0, summary.gapCount)
        assertEquals(1, summary.inferredTransferCount)
        assertTrue(summary.connectedDistanceKm < 50.0)
    }

    @Test
    fun summaryExcludesDistanceAcrossGap() {
        val route = route(
            RouteSpan(point(0, 0.0).instant, point(10, 0.1).instant, RouteSource.DETAILED, listOf(point(0, 0.0), point(10, 0.1))),
            RouteSpan(point(10).instant, point(20).instant, RouteSource.GAP, emptyList()),
            RouteSpan(point(20, 40.0).instant, point(30, 40.1).instant, RouteSource.DETAILED, listOf(point(20, 40.0), point(30, 40.1))),
        )

        val summary = route.summary()

        assertEquals(1, summary.gapCount)
        assertEquals(0, summary.inferredTransferCount)
        assertEquals(2, summary.connectedSegmentCount)
        assertTrue(summary.connectedDistanceKm in 20.0..30.0)
    }

    private fun route(vararg spans: RouteSpan): JournalRoute = JournalRoute(
        timeline = Timeline(spans.flatMap(RouteSpan::points)),
        spans = spans.toList(),
        detailedInputCount = 0,
        detailedUsableCount = 0,
        semanticUsableCount = 0,
    )

    private fun point(minutes: Long, longitude: Double = minutes / 100.0) = GeoPoint(
        instant = BASE.plusSeconds(minutes * 60),
        latitude = 0.0,
        longitude = longitude,
    )

    private fun minute(instant: Instant): Long = (instant.epochSecond - BASE.epochSecond) / 60

    private companion object {
        val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
