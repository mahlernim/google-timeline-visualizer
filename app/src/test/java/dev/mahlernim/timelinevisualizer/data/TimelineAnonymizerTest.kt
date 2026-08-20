package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.privacy.PrivacyArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelineAnonymizerTest {
    @Test
    fun replacesInsidePointsWithAreaCenterWithoutRemovingTimestamps() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.5660, 126.9780),
            point("2026-01-01T01:00:00Z", 37.5670, 126.9790),
            point("2026-01-01T02:00:00Z", 37.6500, 127.1000),
        )
        val home = area("home", 37.5665, 126.9780, 3.0)

        val result = TimelineAnonymizer.anonymize(points, listOf(home))

        assertEquals(listOf(37.5665, 37.5665, 37.6500), result.points.map(GeoPoint::latitude))
        assertEquals(listOf(126.9780, 126.9780, 127.1000), result.points.map(GeoPoint::longitude))
        assertEquals(points.map(GeoPoint::instant), result.points.map(GeoPoint::instant))
        assertEquals(2, result.changedCount)
        assertEquals(0, result.insertedCenterCount)
    }

    @Test
    fun sparseCrossingInsertsCenterInsteadOfLeavingRouteGap() {
        val start = point("2026-01-01T00:00:00Z", 37.5665, 126.90)
        val end = point("2026-01-01T01:00:00Z", 37.5665, 127.05)
        val home = area("home", 37.5665, 126.9780, 2.0)

        val result = TimelineAnonymizer.anonymize(listOf(start, end), listOf(home))

        assertEquals(3, result.points.size)
        assertEquals(home.latitude, result.points[1].latitude, 0.00001)
        assertEquals(home.longitude, result.points[1].longitude, 0.00001)
        assertTrue(result.points[1].instant > start.instant)
        assertTrue(result.points[1].instant < end.instant)
        assertEquals(0, result.changedCount)
        assertEquals(1, result.insertedCenterCount)
    }

    @Test
    fun multipleSparseCrossingsAreInsertedInTravelOrder() {
        val start = point("2026-01-01T00:00:00Z", 0.0, -1.0)
        val end = point("2026-01-01T02:00:00Z", 0.0, 1.0)
        val east = area("east", 0.0, 0.5, 5.0)
        val west = area("west", 0.0, -0.5, 5.0)

        val result = TimelineAnonymizer.anonymize(listOf(start, end), listOf(east, west))

        assertEquals(listOf(-1.0, -0.5, 0.5, 1.0), result.points.map(GeoPoint::longitude))
        assertEquals(2, result.insertedCenterCount)
        assertTrue(result.points.zipWithNext().all { (before, after) -> before.instant <= after.instant })
    }

    @Test
    fun equalTimeCrossingsUseGeometryInsteadOfInputOrderOrIds() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 0.0, -1.0),
            point("2026-01-01T02:00:00Z", 0.0, 1.0),
        )
        val north = area("aaa", 0.02, 0.0, 5.0)
        val south = area("zzz", -0.02, 0.0, 5.0)

        val first = TimelineAnonymizer.anonymize(points, listOf(north, south))
        val second = TimelineAnonymizer.anonymize(points, listOf(south, north))

        assertEquals(listOf(-0.02, 0.02), first.points.subList(1, 3).map(GeoPoint::latitude))
        assertEquals(first, second)
    }

    @Test
    fun nearbySegmentOutsideAreaRemainsUnchanged() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.60, 126.90),
            point("2026-01-01T01:00:00Z", 37.60, 127.05),
        )
        val home = area("home", 37.5665, 126.9780, 1.0)

        val result = TimelineAnonymizer.anonymize(points, listOf(home))

        assertSame(points, result.points)
        assertEquals(0, result.changedCount)
        assertEquals(0, result.insertedCenterCount)
    }

    @Test
    fun flyingPointsAndFlightSegmentsRemainUntouched() {
        val points = listOf(
            point("2026-01-01T00:00:00Z", 37.5665, 126.90, isFlying = true),
            point("2026-01-01T01:00:00Z", 37.5665, 126.9780, isFlying = true),
            point("2026-01-01T02:00:00Z", 37.5665, 127.05, isFlying = true),
        )
        val airport = area("airport", 37.5665, 126.9780, 5.0)

        val result = TimelineAnonymizer.anonymize(points, listOf(airport))

        assertSame(points, result.points)
        assertEquals(0, result.changedCount)
        assertEquals(0, result.insertedCenterCount)
    }

    @Test
    fun areasUseIndependentRadii() {
        val nearNarrowArea = point("2026-01-01T00:00:00Z", 0.0, 0.02)
        val nearWideArea = point("2026-01-01T01:00:00Z", 1.0, 1.02)
        val narrow = area("narrow", 0.0, 0.0, 0.5)
        val wide = area("wide", 1.0, 1.0, 5.0)

        val result = TimelineAnonymizer.anonymize(listOf(nearNarrowArea, nearWideArea), listOf(narrow, wide))

        assertEquals(nearNarrowArea, result.points[0])
        assertEquals(wide.latitude, result.points[1].latitude, 0.00001)
        assertEquals(wide.longitude, result.points[1].longitude, 0.00001)
    }

    @Test
    fun overlappingAreasChooseNearestCenterRegardlessOfInputOrder() {
        val point = point("2026-01-01T00:00:00Z", 0.0, 0.04)
        val west = area("west", 0.0, 0.0, 10.0)
        val east = area("east", 0.0, 0.05, 10.0)

        val first = TimelineAnonymizer.anonymize(listOf(point), listOf(west, east))
        val second = TimelineAnonymizer.anonymize(listOf(point), listOf(east, west))

        assertEquals(east.longitude, first.points.single().longitude, 0.00001)
        assertEquals(first, second)
    }

    @Test
    fun noAreasReturnOriginalList() {
        val points = listOf(point("2026-01-01T00:00:00Z", 37.0, 127.0))

        val result = TimelineAnonymizer.anonymize(points, emptyList())

        assertSame(points, result.points)
        assertEquals(0, result.changedCount)
    }

    private fun point(
        instant: String,
        latitude: Double,
        longitude: Double,
        isFlying: Boolean = false,
    ) = GeoPoint(Instant.parse(instant), latitude, longitude, isFlying)

    private fun area(id: String, latitude: Double, longitude: Double, radiusKm: Double) =
        PrivacyArea(id, id, latitude, longitude, radiusKm)
}
