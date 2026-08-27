package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalRouteFusionTest {
    @Test
    fun detailedGeometryReplacesCompleteSemanticOverlap() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = listOf(point(0, 0.0), point(10, 10.0), point(20, 20.0)),
            detailedPoints = listOf(point(0, 1.0), point(5, 2.0), point(20, 3.0)),
        )

        assertEquals(listOf(RouteSource.DETAILED), result.map(RouteSpan::source))
        assertEquals(listOf(1.0, 2.0, 3.0), result.single().points.map(GeoPoint::latitude))
    }

    @Test
    fun semanticGeometryFillsBeforeAndAfterDetailedIslandOnce() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = listOf(point(0, 0.0), point(10, 10.0), point(20, 20.0), point(30, 30.0)),
            detailedPoints = listOf(point(10, 11.0), point(15, 12.0), point(20, 13.0)),
        )

        assertEquals(
            listOf(RouteSource.SEMANTIC_PATH, RouteSource.DETAILED, RouteSource.SEMANTIC_PATH),
            result.map(RouteSpan::source),
        )
        assertEquals(listOf(0.0, 11.0, 12.0, 13.0, 30.0), result.flatMap(RouteSpan::points).map(GeoPoint::latitude))
    }

    @Test
    fun semanticTimelineIsTheFallbackWhenNoDetailedObservationsExist() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = listOf(point(0, 0.0), point(60, 1.0)),
            detailedPoints = emptyList(),
        )

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), result.map(RouteSpan::source))
    }

    @Test
    fun detailedOnlyDiscontinuityBecomesAnInferredTransfer() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = emptyList(),
            detailedPoints = listOf(point(0, 0.0), point(10, 1.0), point(50, 2.0), point(55, 3.0)),
        )

        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.INFERRED_TRANSFER, RouteSource.DETAILED),
            result.map(RouteSpan::source),
        )
        assertEquals(listOf(1.0, 2.0), result[1].points.map(GeoPoint::latitude))
        assertEquals("Inferred between detailed observation islands", result[1].transitionReason)
    }

    @Test
    fun significantSemanticGapStillProducesAConnectedVideoTransition() {
        val result = JournalRouteFusion.fuseSemanticPaths(
            semanticPaths = listOf(
                SemanticRoutePath("before", instant(0), instant(10), listOf(point(0, 0.0), point(10, 0.1))),
                SemanticRoutePath("after", instant(8 * 60), instant(8 * 60 + 10), listOf(point(8 * 60, 20.0), point(8 * 60 + 10, 20.1))),
            ),
            detailedPoints = emptyList(),
        )

        assertEquals(
            listOf(RouteSource.SEMANTIC_PATH, RouteSource.INFERRED_TRANSFER, RouteSource.SEMANTIC_PATH),
            result.map(RouteSpan::source),
        )
        assertEquals(2, result[1].points.size)
        assertEquals("Inferred between available Timeline records", result[1].transitionReason)
    }

    @Test
    fun adjacentSemanticRecordsWithMismatchedEndpointsRemainConnected() {
        val boundary = instant(10)
        val result = JournalRouteFusion.fuseSemanticPaths(
            semanticPaths = listOf(
                SemanticRoutePath("visit", instant(0), boundary, listOf(point(0, 0.0), point(10, 0.1))),
                SemanticRoutePath("activity", boundary, instant(20), listOf(point(10, 15.0), point(20, 15.1))),
            ),
            detailedPoints = emptyList(),
        )

        assertEquals(RouteSource.INFERRED_TRANSFER, result[1].source)
        assertEquals(boundary, result[1].start)
        assertEquals(boundary, result[1].end)
    }

    @Test
    fun ambiguousDetailedTimestampIsRejectedInsteadOfCreatingBacktracking() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = listOf(point(10, 8.0)),
            detailedPoints = listOf(point(10, 1.0), point(10, 99.0)),
        )

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), result.map(RouteSpan::source))
        assertEquals(8.0, result.single().points.single().latitude, 0.0)
    }

    @Test
    fun multipleDetailedIslandsSplitOneSemanticPathWithoutLosingFragments() {
        val result = JournalRouteFusion.fuse(
            semanticPoints = (0L..60L step 10L).map { minute -> point(minute, minute.toDouble()) },
            detailedPoints = listOf(
                point(10, 101.0),
                point(50, 105.0),
            ),
            discontinuity = java.time.Duration.ofMinutes(30),
        )

        assertEquals(
            listOf(
                RouteSource.SEMANTIC_PATH,
                RouteSource.DETAILED,
                RouteSource.SEMANTIC_PATH,
                RouteSource.DETAILED,
                RouteSource.SEMANTIC_PATH,
            ),
            result.map(RouteSpan::source),
        )
        assertEquals(
            listOf(0.0, 101.0, 20.0, 30.0, 40.0, 105.0, 60.0),
            result.flatMap(RouteSpan::points).map(GeoPoint::latitude),
        )
    }

    @Test
    fun semanticPathAfterManyDetailedIslandsKeepsTheSameTopology() {
        val detailed = (0L until 600L step 60L).mapIndexed { index, minute ->
            point(minute, 100.0 + index)
        }
        val semantic = SemanticRoutePath(
            "late-semantic",
            instant(610),
            instant(620),
            listOf(point(610, 10.0), point(620, 11.0)),
        )

        val result = JournalRouteFusion.fuseSemanticPaths(
            semanticPaths = listOf(semantic),
            detailedPoints = detailed,
        )

        assertEquals(RouteSource.SEMANTIC_PATH, result.last().source)
        assertEquals(listOf(10.0, 11.0), result.last().points.map(GeoPoint::latitude))
        assertEquals(detailed.size, result.count { it.source == RouteSource.DETAILED })
    }

    private fun point(minutes: Long, latitude: Double): GeoPoint = GeoPoint(
        instant = instant(minutes),
        latitude = latitude,
        longitude = latitude,
    )

    private fun instant(minutes: Long): Instant =
        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minutes * 60)
}
