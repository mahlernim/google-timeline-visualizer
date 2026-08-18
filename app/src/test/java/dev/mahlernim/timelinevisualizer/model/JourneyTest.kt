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
    private val bogota = GeoPoint(Instant.parse("2026-06-05T00:00:00Z"), 4.71, -74.07)

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
    fun splitsLongHopsIntoTransferLegs() {
        val journey = Journey.from(cityFlightCityPoints(), 2026)

        assertEquals(listOf(false, true, false), journey.legs.map { it.isTransfer })
        assertTrue("The flight leg was ${journey.legs[1].lengthKm} km", journey.legs[1].lengthKm > 300)
        assertTrue(journey.legAt(journey.legs[1].startKm + 1.0).isTransfer)
        assertTrue(!journey.legAt(0.0).isTransfer)
        assertTrue(!journey.legAt(journey.totalDistanceKm).isTransfer)
    }

    @Test
    fun localTravelWithoutLongHopsStaysOneLeg() {
        val journey = Journey.from(cityPoints(7.90, -72.50, 40), 2026)

        assertEquals(1, journey.legs.size)
        assertEquals(false, journey.legs.single().isTransfer)
        assertEquals(journey.totalDistanceKm, journey.legs.single().endKm, 1e-9)
    }

    @Test
    fun aJourneyEndingOnATransferHasNoEmptyTrailingLeg() {
        val points = cityPoints(7.90, -72.50, 20) + bogota

        val legs = Journey.from(points, 2026).legs

        assertEquals(listOf(false, true), legs.map { it.isTransfer })
        assertTrue(legs.none { it.lengthKm <= 0.0 })
    }

    @Test
    fun cityTravelZoomsInBetweenFlights() {
        val journey = Journey.from(cityFlightCityPoints(), 2026)
        val painter = TimelinePainter()
        val localWidths = mutableListOf<Double>()
        val transferWidths = mutableListOf<Double>()

        for (sample in 0..400) {
            val progress = sample / 400f
            val viewport = painter.viewport(journey, progress, 480, 480)
            val widthKm = (viewport.maxX - viewport.minX) * EQUATOR_KM * kotlin.math.cos(Math.toRadians(7.9))
            val leg = journey.legAt(journey.positionAt(progress).distanceKm)
            if (leg.isTransfer) transferWidths += widthKm else localWidths += widthKm
        }

        val tightestLocal = localWidths.min()
        assertTrue(
            "The camera never reached city scale, tightest frame was $tightestLocal km",
            tightestLocal < 25.0,
        )
        assertTrue(
            "Half of local travel should be framed tightly, median was ${localWidths.sorted()[localWidths.size / 2]} km",
            localWidths.sorted()[localWidths.size / 2] < 60.0,
        )
        assertTrue(
            "The flight should pull the camera out, widest frame was ${transferWidths.max()} km",
            transferWidths.max() > 500.0,
        )
    }

    /** Local travel around Cúcuta, a flight to Medellín, then local travel there. */
    private fun cityFlightCityPoints(): List<GeoPoint> =
        cityPoints(7.90, -72.50, 40) + cityPoints(6.24, -75.57, 40, startHour = 100)

    private fun cityPoints(
        latitude: Double,
        longitude: Double,
        count: Int,
        startHour: Long = 0,
    ): List<GeoPoint> = List(count) { index ->
        val offset = index * 0.004
        GeoPoint(
            Instant.parse("2026-06-01T00:00:00Z").plusSeconds((startHour + index) * 3_600L),
            latitude + if (index % 2 == 0) offset else -offset,
            longitude + offset,
        )
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

    private companion object {
        const val EQUATOR_KM = 40_075.0
    }
}
