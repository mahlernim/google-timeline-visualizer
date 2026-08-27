package dev.mahlernim.timelinevisualizer.model

import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JourneyTest {
    @Test
    fun sectionsPreserveRouteBreaksWithoutAddingMissingDistance() {
        val firstSection = listOf(
            GeoPoint(Instant.parse("2025-01-01T00:00:00Z"), 0.0, 0.0),
            GeoPoint(Instant.parse("2025-01-01T01:00:00Z"), 0.0, 0.1),
        )
        val secondSection = listOf(
            GeoPoint(Instant.parse("2025-01-02T00:00:00Z"), 40.0, 120.0),
            GeoPoint(Instant.parse("2025-01-02T01:00:00Z"), 40.0, 120.1),
        )

        val journey = Journey.fromSections(listOf(firstSection, emptyList(), secondSection), TimelinePeriod.sameYear(2025))
        val separatelyMeasuredDistance = Journey.from(firstSection, 2025).totalDistanceKm +
            Journey.from(secondSection, 2025).totalDistanceKm

        assertEquals(listOf(2), journey.breakBeforePointIndices)
        assertTrue(journey.isConnectedToPrevious(1))
        assertEquals(false, journey.isConnectedToPrevious(2))
        assertTrue(journey.isConnectedToPrevious(3))
        assertEquals(separatelyMeasuredDistance, journey.totalDistanceKm, 0.001)
        assertEquals(secondSection.first(), journey.positionAtDistance(journey.cumulativeDistanceKm[2]).point)
    }

    @Test
    fun ordinaryJourneyRemainsFullyConnected() {
        val journey = Journey.from(listOf(seoul, bohol), 2025)

        assertEquals(emptyList<Int>(), journey.breakBeforePointIndices)
        assertTrue(journey.isConnectedToPrevious(1))
    }

    @Test
    fun exactDateRangeIncludesOnlyPointsOnSelectedDates() {
        val timeline = Timeline(
            listOf(
                GeoPoint(Instant.parse("2026-04-01T12:00:00Z"), 37.0, 127.0),
                GeoPoint(Instant.parse("2026-04-02T12:00:00Z"), 37.1, 127.1),
                GeoPoint(Instant.parse("2026-04-03T12:00:00Z"), 37.2, 127.2),
                GeoPoint(Instant.parse("2026-04-04T12:00:00Z"), 37.3, 127.3),
            ),
        )

        val journey = timeline.forDateRange(LocalDate.parse("2026-04-02"), LocalDate.parse("2026-04-03"))

        assertEquals(2, journey.points.size)
        assertEquals(2, timeline.countForDateRange(LocalDate.parse("2026-04-02"), LocalDate.parse("2026-04-03")))
        assertEquals(Instant.parse("2026-04-02T12:00:00Z"), journey.points.first().instant)
        assertEquals(Instant.parse("2026-04-03T12:00:00Z"), journey.points.last().instant)
    }

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
    fun virtualRenderPathMatchesThePreviousGeometryExactly() {
        val points = listOf(
            seoul,
            bohol,
            bohol.copy(
                instant = Instant.parse("2025-06-01T08:00:00Z"),
                latitude = 35.6762,
                longitude = 139.6503,
            ),
        )
        val journey = Journey.from(points, 2025)
        val expected = legacyRenderPath(journey)

        assertEquals(expected.size, journey.renderPath.size)
        expected.indices.forEach { index ->
            assertEquals(expected[index].distanceKm, journey.renderPath[index].distanceKm, 0.0)
            assertEquals(expected[index].point, journey.renderPath[index].point)
        }
        assertSame(points.last(), journey.renderPath.last().point)
    }

    @Test
    fun virtualProjectionMatchesThePreviousPreparedRoute() {
        val points = listOf(
            seoul.copy(latitude = 10.0, longitude = 179.0),
            bohol.copy(latitude = 20.0, longitude = -179.0),
            bohol.copy(
                instant = Instant.parse("2025-06-01T08:00:00Z"),
                latitude = 35.6762,
                longitude = 139.6503,
            ),
        )
        val journey = Journey.from(points, 2025)
        val expected = unwrapLegacyRoute(legacyRenderPath(journey))
        val painter = TimelinePainter()

        expected.indices.forEach { index ->
            val actual = painter.routeWorldPoint(journey, index)
            assertEquals(expected[index].x, actual.x, 1e-12)
            assertEquals(expected[index].y, actual.y, 1e-12)
        }
    }

    @Test(timeout = 15_000)
    fun millionsOfRenderSamplesStayVirtual() {
        val start = Instant.parse("2025-01-01T00:00:00Z")
        val points = List(30_000) { index ->
            GeoPoint(
                instant = start.plusSeconds(index.toLong()),
                latitude = 0.0,
                longitude = if (index % 2 == 0) 0.0 else 179.0,
            )
        }

        val journey = Journey.from(points, 2025)

        assertTrue("Expected millions of samples, got ${journey.renderPath.size}", journey.renderPath.size > 7_000_000)
        assertEquals(points.first(), journey.renderPath.first().point)
        assertSame(points.last(), journey.renderPath.last().point)
    }

    @Test
    fun adaptsTransferThresholdToDenselySampledLocalTravel() {
        val local = equatorialPoints(doubleArrayOf(0.0, 0.01, 0.02, 0.03, 0.04))
        val shortFlight = local + equatorialPoint(0.76, 5)
        val journey = Journey.from(shortFlight, 2025)

        assertTrue("Adaptive threshold was ${journey.transferThresholdKm} km", journey.transferThresholdKm < 80.0)
        assertEquals(listOf(false, true), journey.legs.map { it.isTransfer })
    }

    @Test
    fun consistentlySparseTravelDoesNotCreateFalseTransferLegs() {
        val journey = Journey.from(equatorialPoints(doubleArrayOf(0.0, 0.81, 1.62, 2.43)), 2025)

        assertEquals(120.0, journey.transferThresholdKm, 0.1)
        assertEquals(listOf(false), journey.legs.map { it.isTransfer })
    }

    @Test
    fun absoluteGuardrailStillRecognizesAnUntrackedLongHop() {
        val local = equatorialPoints(doubleArrayOf(0.0, 0.01, 0.02, 0.03))
        val journey = Journey.from(local + equatorialPoint(2.0, 4), 2025)

        assertEquals(listOf(false, true), journey.legs.map { it.isTransfer })
    }

    @Test
    fun cameraReturnsToLocalScaleAfterAnAdaptiveTransfer() {
        val departure = compactCityPoints(centerLongitude = 0.0, count = 31)
        val arrival = compactCityPoints(centerLongitude = 1.1, count = 31, startHour = departure.size)
        val journey = Journey.from(departure + arrival, 2025)
        val painter = TimelinePainter()
        val settings = CameraSettings(
            cameraMovement = CameraMovement.DYNAMIC,
            longTripCompression = LongTripCompression.OFF,
        )
        val arrivalStartKm = journey.legs.last().startKm
        val localWidthsKm = (0..100).map { sample ->
            val distanceKm = arrivalStartKm + journey.legs.last().lengthKm * sample / 100.0
            val viewport = painter.viewport(
                journey,
                (distanceKm / journey.totalDistanceKm).toFloat(),
                480,
                480,
                settings,
            )
            (viewport.maxX - viewport.minX) * EQUATOR_KM
        }

        assertEquals(listOf(false, true, false), journey.legs.map { it.isTransfer })
        assertTrue("Tightest arrival frame was ${localWidthsKm.min()} km", localWidthsKm.min() < 40.0)
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
        assertEquals(2, timeline.countForRange(TimelinePeriod.sameYear(2025, 6, 7)))
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
        assertEquals(journey.points.size, timeline.countForRange(period))
        assertEquals("2025–2026", journey.period.yearLabel)
    }

    private fun unwrapNear(value: Double, reference: Double): Double {
        var result = value
        while (result - reference > 0.5) result -= 1.0
        while (result - reference < -0.5) result += 1.0
        return result
    }

    private fun equatorialPoints(longitudes: DoubleArray): List<GeoPoint> =
        longitudes.mapIndexed { index, longitude -> equatorialPoint(longitude, index) }

    private fun equatorialPoint(longitude: Double, hour: Int): GeoPoint = GeoPoint(
        instant = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(hour * 3_600L),
        latitude = 0.0,
        longitude = longitude,
    )

    private fun compactCityPoints(
        centerLongitude: Double,
        count: Int,
        startHour: Int = 0,
    ): List<GeoPoint> = List(count) { index ->
        equatorialPoint(
            longitude = centerLongitude + if (index % 2 == 0) -0.01 else 0.01,
            hour = startHour + index,
        )
    }

    private fun legacyRenderPath(journey: Journey): List<RouteSample> = buildList {
        if (journey.points.isEmpty()) return@buildList
        add(RouteSample(journey.points.first(), 0.0))
        for (index in 1..journey.points.lastIndex) {
            val startDistance = journey.cumulativeDistanceKm[index - 1]
            val segmentDistance = journey.cumulativeDistanceKm[index] - startDistance
            val steps = kotlin.math.ceil(segmentDistance / 75.0).toInt().coerceIn(1, 320)
            for (step in 1..steps) {
                val fraction = step.toDouble() / steps
                add(
                    RouteSample(
                        Journey.interpolate(journey.points[index - 1], journey.points[index], fraction),
                        startDistance + segmentDistance * fraction,
                    ),
                )
            }
        }
    }

    private fun unwrapLegacyRoute(path: List<RouteSample>): List<WorldPoint> = buildList {
        path.forEach { sample ->
            val projected = WebMercator.project(sample.point)
            val x = if (isEmpty()) projected.x else unwrapNear(projected.x, last().x)
            add(WorldPoint(x, projected.y))
        }
    }

    private companion object {
        const val EQUATOR_KM = 40_075.0
    }
}
