package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalRoutePatchTest {
    @Test
    fun clippingSelectsPointsAndTrimsGapWithoutInventingBoundaryPoints() {
        val route = JournalRoute(
            timeline = Timeline(listOf(point(0, 1.0), point(10, 1.1), point(20, 1.2), point(30, 1.3))),
            spans = listOf(
                RouteSpan(instant(0), instant(10), RouteSource.DETAILED, listOf(point(0, 1.0), point(10, 1.1))),
                RouteSpan(instant(10), instant(20), RouteSource.GAP, emptyList()),
                RouteSpan(instant(20), instant(30), RouteSource.SEMANTIC_PATH, listOf(point(20, 1.2), point(30, 1.3))),
            ),
            detailedInputCount = 2,
            detailedUsableCount = 2,
            semanticUsableCount = 2,
        )

        val clipped = route.clippedTo(instant(5), instant(25))

        assertEquals(listOf(10L, 20L), clipped.timeline.points.map { it.instant.epochSecond })
        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.GAP, RouteSource.SEMANTIC_PATH),
            clipped.spans.map(RouteSpan::source),
        )
        assertEquals(5L, clipped.spans[0].start.epochSecond)
        assertEquals(24L, clipped.spans.last().end.epochSecond)
    }

    @Test
    fun `replaces only the requested window`() {
        val existing = route(
            span(RouteSource.SEMANTIC_PATH, point(1), point(2), point(3), point(4), point(5)),
        )
        val replacement = route(
            span(RouteSource.DETAILED, point(2, 20.0), point(3, 30.0), point(4, 40.0)),
        )

        val patched = existing.replacingWindow(
            start = instant(2),
            endExclusive = instant(5),
            replacement = replacement,
        )

        assertEquals(listOf(1.0, 20.0, 30.0, 40.0, 5.0), patched.timeline.points.map { it.latitude })
        assertEquals(
            listOf(RouteSource.SEMANTIC_PATH, RouteSource.DETAILED, RouteSource.SEMANTIC_PATH),
            patched.spans.map { it.source },
        )
    }

    @Test
    fun `keeps explicit gaps outside the replacement window`() {
        val existing = route(
            span(RouteSource.DETAILED, point(1)),
            RouteSpan(instant(2), instant(8), RouteSource.GAP, emptyList(), "unsupported"),
            span(RouteSource.SEMANTIC_PATH, point(9)),
        )
        val replacement = route(span(RouteSource.DETAILED, point(4), point(5)))

        val patched = existing.replacingWindow(instant(3), instant(7), replacement)

        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.GAP, RouteSource.DETAILED, RouteSource.GAP, RouteSource.SEMANTIC_PATH),
            patched.spans.map { it.source },
        )
        assertEquals(instant(3), patched.spans[1].end)
        assertEquals(instant(7), patched.spans[3].start)
    }

    @Test
    fun `refresh window expands across the complete sparse semantic component`() {
        val existing = route(span(RouteSource.SEMANTIC_PATH, point(0), point(60 * 60)))

        val expanded = existing.expandedRefreshWindow(
            start = instant(10 * 60),
            endExclusive = instant(50 * 60),
        )

        assertEquals(instant(0), expanded.first)
        assertEquals(instant(60 * 60).plusMillis(1), expanded.second)
    }

    @Test
    fun `refreshing an explicit gap includes both neighboring components`() {
        val existing = route(
            span(RouteSource.SEMANTIC_PATH, point(0), point(10)),
            RouteSpan(instant(10), instant(20), RouteSource.GAP, emptyList(), "unsupported"),
            span(RouteSource.SEMANTIC_PATH, point(20), point(30)),
        )

        val expanded = existing.expandedRefreshWindow(instant(12), instant(18))

        assertEquals(instant(0), expanded.first)
        assertEquals(instant(30).plusMillis(1), expanded.second)
    }

    @Test
    fun `distant new tail includes the previous component so fusion preserves its gap`() {
        val existing = route(span(RouteSource.DETAILED, point(0), point(10)))
        val newTailStart = instant(60 * 60)
        val expanded = existing.expandedRefreshWindow(newTailStart, newTailStart.plusSeconds(11))
        val fullSpans = JournalRouteFusion.fuse(
            semanticPoints = emptyList(),
            detailedPoints = listOf(point(0), point(10), point(60 * 60), point(60 * 60 + 10)),
        )
        val fullReplacement = route(*fullSpans.toTypedArray())

        val patched = existing.replacingWindow(expanded.first, expanded.second, fullReplacement)

        assertEquals(instant(0), expanded.first)
        assertEquals(fullReplacement.spans.map { it.source }, patched.spans.map { it.source })
        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.INFERRED_TRANSFER, RouteSource.DETAILED),
            patched.spans.map { it.source },
        )
    }

    @Test
    fun `single point component at the boundary is replaced rather than duplicated`() {
        val existing = route(span(RouteSource.DETAILED, point(10)))
        val replacement = route(span(RouteSource.SEMANTIC_PATH, point(10, latitude = 99.0)))

        val patched = existing.replacingWindow(instant(10), instant(11), replacement)

        assertEquals(listOf(99.0), patched.timeline.points.map { it.latitude })
        assertEquals(listOf(RouteSource.SEMANTIC_PATH), patched.spans.map { it.source })
    }

    private fun route(vararg spans: RouteSpan): JournalRoute {
        val points = spans.flatMap(RouteSpan::points).sortedBy(GeoPoint::instant)
        return JournalRoute(Timeline(points), spans.toList(), points.size, points.size, 0)
    }

    private fun span(source: RouteSource, vararg points: GeoPoint): RouteSpan =
        RouteSpan(points.first().instant, points.last().instant, source, points.toList())

    private fun point(second: Long, latitude: Double = second.toDouble()): GeoPoint =
        GeoPoint(instant(second), latitude, latitude)

    private fun instant(second: Long): Instant = Instant.ofEpochSecond(second)
}
