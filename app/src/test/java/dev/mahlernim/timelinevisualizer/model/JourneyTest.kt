package dev.mahlernim.timelinevisualizer.model

import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JourneyTest {
    private val seoul = GeoPoint(Instant.parse("2025-06-01T00:00:00Z"), 37.5665, 126.9780)
    private val bohol = GeoPoint(Instant.parse("2025-06-01T04:00:00Z"), 9.8500, 124.1435)

    @Test
    fun continuouslyInterpolatesALongFlight() {
        val journey = Journey.from(listOf(seoul, bohol), 2025)
        val quarter = journey.positionAt(0.25f)
        val halfway = journey.positionAt(0.5f)
        val threeQuarters = journey.positionAt(0.75f)

        assertTrue(quarter.point.latitude < seoul.latitude)
        assertTrue(quarter.point.latitude > halfway.point.latitude)
        assertTrue(halfway.point.latitude > threeQuarters.point.latitude)
        assertTrue(threeQuarters.point.latitude > bohol.latitude)
        assertEquals(journey.totalDistanceKm / 2.0, halfway.distanceKm, 0.1)
        assertEquals(0.5, halfway.segmentFraction, 0.0001)
    }

    @Test
    fun densifiesLongLegsForSmoothRendering() {
        val journey = Journey.from(listOf(seoul, bohol), 2025)

        assertTrue(journey.renderPath.size > 20)
        val largestStep = journey.renderPath.zipWithNext { a, b -> b.distanceKm - a.distanceKm }.max()
        assertTrue("Largest rendered step was $largestStep km", largestStep <= 75.1)
    }

    @Test
    fun movingHeadStaysInsideItsCameraViewport() {
        val journey = Journey.from(listOf(seoul, bohol), 2025)
        val painter = TimelinePainter()

        for (sample in 0..100) {
            val progress = sample / 100f
            val position = journey.positionAt(progress)
            val projected = WebMercator.project(position.point)
            val viewport = painter.viewport(journey, progress, 480, 480)
            val x = unwrapNear(projected.x, (viewport.minX + viewport.maxX) / 2.0)
            val screenX = (x - viewport.minX) / (viewport.maxX - viewport.minX)
            val screenY = (projected.y - viewport.minY) / (viewport.maxY - viewport.minY)

            assertTrue("Marker x was $screenX at $progress", screenX in 0.25..0.75)
            assertTrue("Marker y was $screenY at $progress", screenY in 0.25..0.75)
        }
    }

    @Test
    fun commuteReversalsMoveTheCameraLessThanTheMarker() {
        val home = seoul.copy(latitude = 37.50, longitude = 126.95)
        val office = seoul.copy(latitude = 37.50, longitude = 127.05)
        val points = List(365) { index ->
            (if (index % 2 == 0) home else office).copy(
                instant = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(index * 43_200L),
            )
        }
        val journey = Journey.from(points, 2025)
        val painter = TimelinePainter()
        val markerCenters = mutableListOf<Double>()
        val cameraCenters = mutableListOf<Double>()

        for (sample in 0..240) {
            val progress = sample / 240f
            markerCenters += WebMercator.project(journey.positionAt(progress).point).x
            val viewport = painter.viewport(journey, progress, 480, 480)
            cameraCenters += (viewport.minX + viewport.maxX) / 2.0
        }

        val markerTravel = markerCenters.zipWithNext { a, b -> kotlin.math.abs(b - a) }.sum()
        val cameraTravel = cameraCenters.zipWithNext { a, b -> kotlin.math.abs(b - a) }.sum()
        assertTrue(
            "Camera traveled $cameraTravel world units while marker traveled $markerTravel",
            cameraTravel < markerTravel * 0.45,
        )
        assertEquals(1, (0..240).map { sample ->
            painter.viewport(journey, sample / 240f, 480, 480).zoom
        }.distinct().size)
    }

    @Test
    fun cameraTrackIsDeterministicWhenSeeking() {
        val journey = Journey.from(listOf(seoul, bohol), 2025)
        val painter = TimelinePainter()
        val first = painter.viewport(journey, 0.63f, 480, 480)

        painter.viewport(journey, 0.05f, 480, 480)
        painter.viewport(journey, 0.95f, 480, 480)
        val afterSeeking = painter.viewport(journey, 0.63f, 480, 480)

        assertEquals(first, afterSeeking)
    }

    @Test
    fun interpolationTakesTheShortWayAcrossTheDateLine() {
        val west = seoul.copy(latitude = 10.0, longitude = 179.0)
        val east = bohol.copy(latitude = 10.0, longitude = -179.0)
        val halfway = Journey.from(listOf(west, east), 2025).positionAt(0.5f).point

        assertTrue("Halfway longitude was ${halfway.longitude}", kotlin.math.abs(halfway.longitude) > 179.5)
    }

    @Test
    fun monthRangeDefaultsCanBeNarrowed() {
        val timeline = Timeline(
            listOf(
                seoul.copy(instant = Instant.parse("2025-01-15T00:00:00Z")),
                seoul.copy(instant = Instant.parse("2025-06-15T00:00:00Z")),
                bohol.copy(instant = Instant.parse("2025-07-15T00:00:00Z")),
                bohol.copy(instant = Instant.parse("2025-12-15T00:00:00Z")),
            ),
        )

        assertEquals(4, timeline.forYear(2025).points.size)
        assertEquals(2, timeline.forRange(2025, 6, 7).points.size)
    }

    @Test
    fun rangeCanSpanMultipleYearsInclusively() {
        val timeline = Timeline(
            listOf(
                seoul.copy(instant = Instant.parse("2025-11-15T00:00:00Z")),
                seoul.copy(instant = Instant.parse("2025-12-15T00:00:00Z")),
                bohol.copy(instant = Instant.parse("2026-01-15T00:00:00Z")),
                bohol.copy(instant = Instant.parse("2026-02-15T00:00:00Z")),
            ),
        )
        val period = TimelinePeriod(YearMonth.of(2025, 12), YearMonth.of(2026, 1))

        val journey = timeline.forRange(period)

        assertEquals(period, journey.period)
        assertEquals(2, journey.points.size)
        assertEquals("2025–2026", journey.period.yearLabel)
    }

    private fun unwrapNear(value: Double, reference: Double): Double {
        var result = value
        while (result - reference > 0.5) result -= 1.0
        while (result - reference < -0.5) result += 1.0
        return result
    }
}
