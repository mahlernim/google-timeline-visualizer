package dev.mahlernim.timelinevisualizer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelinePainterTest {
    @Test
    fun fixedCameraKeepsTheSameZoomSpanAcrossTheJourney() {
        val journey = Journey.from(
            listOf(
                point(37.50, 126.90),
                point(37.55, 126.95),
                point(37.60, 127.00),
            ),
            2025,
        )
        val fixed = CameraSettings(
            cameraMovement = CameraMovement.FIXED,
            longTripCompression = LongTripCompression.OFF,
        )
        val painter = TimelinePainter()
        val spans = listOf(0f, 0.2f, 0.5f, 0.8f, 1f).map { progress ->
            val viewport = painter.viewport(journey, progress, SIZE, SIZE, fixed)
            viewport.maxY - viewport.minY
        }

        spans.forEach { assertEquals(spans.first(), it, 1e-12) }
    }

    @Test
    fun routeStartsEmptyAndAppearsOnlyAsJourneyAdvances() {
        val points = listOf(
            GeoPoint(Instant.parse("2025-01-01T00:00:00Z"), 37.45, 126.75),
            GeoPoint(Instant.parse("2025-04-01T00:00:00Z"), 37.65, 127.20),
            GeoPoint(Instant.parse("2025-08-01T00:00:00Z"), 37.35, 127.55),
            GeoPoint(Instant.parse("2025-12-01T00:00:00Z"), 37.75, 127.90),
        )
        val journey = Journey.from(points, 2025)

        val startRoutePixels = countRouteColoredPixels(render(journey, 0f))
        val halfwayRoutePixels = countRouteColoredPixels(render(journey, 0.5f))
        val endRoutePixels = countRouteColoredPixels(render(journey, 1f))

        // Stroke widths scale with the frame, so these bounds are expressed against the frame size
        // rather than as the absolute counts that suited the old fixed-width strokes.
        val markerBudget = (SIZE.toLong() * SIZE * 25 / 10_000).toInt()
        assertTrue(
            "The initial frame drew $startRoutePixels route-colored pixels, more than a marker",
            startRoutePixels < markerBudget,
        )
        assertTrue(
            "The traveled route should be visibly longer ($startRoutePixels vs $halfwayRoutePixels pixels)",
            halfwayRoutePixels > startRoutePixels * 3 / 2,
        )
        assertTrue(
            "The route should keep growing ($halfwayRoutePixels vs $endRoutePixels pixels)",
            endRoutePixels > halfwayRoutePixels,
        )
    }

    @Test
    fun endingOverviewFitsAWorldwideJourney() {
        val points = listOf(
            GeoPoint(Instant.parse("2025-01-01T00:00:00Z"), 10.0, -150.0),
            GeoPoint(Instant.parse("2025-06-01T00:00:00Z"), 10.0, 0.0),
            GeoPoint(Instant.parse("2025-12-01T00:00:00Z"), 10.0, 150.0),
        )
        val journey = Journey.from(points, 2025)
        val viewport = TimelinePainter().viewport(journey, TimelineFrame(1f, 1f), SIZE, SIZE)

        assertTrue("The ending should fit the complete worldwide route", viewport.maxX - viewport.minX > 0.8)
    }

    @Test
    fun endingOverviewKeepsEveryRouteInsideTheAreaBelowTheHeader() {
        val routes = listOf(
            listOf(point(70.0, 127.0), point(-55.0, 127.0)),
            listOf(point(35.0, -120.0), point(35.0, 140.0)),
            listOf(point(37.50, 126.95), point(37.55, 127.05)),
            listOf(point(10.0, -150.0), point(10.0, 0.0), point(10.0, 150.0)),
            listOf(point(10.0, 179.0), point(10.0, -179.0)),
        )
        for (size in listOf(480, 1080)) {
            routes.forEach { points ->
                val journey = Journey.from(points, 2025)
                val painter = TimelinePainter()
                val viewport = painter.viewport(journey, TimelineFrame(1f, 1f), size, size)
                val safe = painter.overviewSafeArea(size, size)
                val centerX = (viewport.minX + viewport.maxX) / 2.0
                journey.renderPath.forEach { sample ->
                    val projected = dev.mahlernim.timelinevisualizer.model.WebMercator.project(sample.point)
                    val x = unwrapNear(projected.x, centerX)
                    val screenX = ((x - viewport.minX) / (viewport.maxX - viewport.minX) * size).toFloat()
                    val screenY = ((projected.y - viewport.minY) / (viewport.maxY - viewport.minY) * size).toFloat()
                    assertTrue("Route x $screenX was outside $safe at $size", screenX in safe.left..safe.right)
                    assertTrue("Route y $screenY was outside $safe at $size", screenY in safe.top..safe.bottom)
                }
            }
        }
    }

    @Test
    fun theTitleCardStaysInsideEveryExportShape() {
        SHAPES.forEach { (width, height) ->
            val card = TimelinePainter().overlayCard(width, height)

            assertTrue("Card $card left of frame at ${width}x$height", card.left >= 0f)
            assertTrue("Card $card right of frame at ${width}x$height", card.right <= width.toFloat())
            assertTrue("Card $card below frame at ${width}x$height", card.bottom <= height.toFloat())
            assertTrue("Card $card has no width at ${width}x$height", card.width() > 0f)
            assertEquals(
                "Card $card is not centred at ${width}x$height",
                width / 2f,
                card.centerX(),
                0.5f,
            )
        }
    }

    @Test
    fun theTitleCardNeverSwallowsAWideFrame() {
        val landscape = TimelinePainter().overlayCard(1920, 1080)
        val square = TimelinePainter().overlayCard(1080, 1080)

        // The cap binds only when the frame is wider than it is tall, and then both frames share
        // the short edge, so the card keeps the same size instead of stretching across the width.
        assertEquals(square.width(), landscape.width(), 0.5f)
        assertEquals(square.height(), landscape.height(), 0.5f)
        assertTrue("A landscape card should not span the frame", landscape.width() < 1920 * 0.75f)
    }

    @Test
    fun squareOutputKeepsTheLayoutItAlwaysHad() {
        val painter = TimelinePainter()

        listOf(480, 720, 1080).forEach { size ->
            val scale = size / 720f
            val card = painter.overlayCard(size, size)

            assertEquals("Card top at $size", 28f * scale, card.top, 0.01f)
            assertEquals("Card left at $size", 34f * scale, card.left, 0.01f)
            assertEquals("Card right at $size", size - 34f * scale, card.right, 0.01f)
            assertEquals("Card bottom at $size", 132f * scale, card.bottom, 0.01f)
        }
    }

    @Test
    fun theAttributionAndTitleFitInsideEveryExportShape() {
        val journey = Journey.from(
            listOf(point(37.45, 126.75), point(37.65, 127.20), point(37.75, 127.90)),
            2025,
        )
        SHAPES.forEach { (width, height) ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            TimelinePainter().draw(
                Canvas(bitmap),
                width,
                height,
                journey,
                TimelineFrame(0.5f, 0f),
                30,
                "A journey long enough to need shrinking to fit the card",
                RenderText.ENGLISH,
                CameraSettings.DEFAULT,
            ) { null }

            val card = TimelinePainter().overlayCard(width, height)
            val bandHeight = (height / 12).coerceAtLeast(2)
            val bandWidth = (width / 4).coerceAtLeast(2)
            assertTrue(
                "The title and date were missing from the card at ${width}x$height",
                darkPixels(bitmap, card.left.toInt() + 2, card.top.toInt() + 2, card.width().toInt() - 4, card.height().toInt() - 4) > 0,
            )
            assertTrue(
                "The attribution was missing from the bottom-right at ${width}x$height",
                darkPixels(bitmap, width - bandWidth, height - bandHeight, bandWidth, bandHeight) > 0,
            )
            // The attribution is right-aligned, so the matching left corner proves the check above
            // is reading real text rather than the background.
            assertEquals(
                "Something unexpected was drawn in the bottom-left at ${width}x$height",
                0,
                darkPixels(bitmap, 0, height - bandHeight, bandWidth, bandHeight),
            )
            bitmap.recycle()
        }
    }

    @Test
    fun theOverviewSafeAreaStaysInsideEveryExportShape() {
        val painter = TimelinePainter()

        SHAPES.forEach { (width, height) ->
            val safe = painter.overviewSafeArea(width, height)
            val card = painter.overlayCard(width, height)

            assertTrue("Safe area $safe escapes ${width}x$height", safe.left >= 0f && safe.top >= 0f)
            assertTrue(
                "Safe area $safe escapes ${width}x$height",
                safe.right <= width.toFloat() && safe.bottom <= height.toFloat(),
            )
            assertTrue("Safe area $safe is empty at ${width}x$height", safe.width() > 0f && safe.height() > 0f)
            assertTrue("Safe area $safe overlaps the title card at ${width}x$height", safe.top >= card.bottom)
        }
    }

    @Test
    fun everyExportShapeKeepsTheEndingOverviewInsideItsSafeArea() {
        val routes = listOf(
            listOf(point(70.0, 127.0), point(-55.0, 127.0)),
            listOf(point(35.0, -120.0), point(35.0, 140.0)),
            listOf(point(37.50, 126.95), point(37.55, 127.05)),
        )
        SHAPES.forEach { (width, height) ->
            routes.forEach { points ->
                val journey = Journey.from(points, 2025)
                val painter = TimelinePainter()
                val viewport = painter.viewport(journey, TimelineFrame(1f, 1f), width, height)
                val safe = painter.overviewSafeArea(width, height)
                val centerX = (viewport.minX + viewport.maxX) / 2.0
                journey.renderPath.forEach { sample ->
                    val projected = dev.mahlernim.timelinevisualizer.model.WebMercator.project(sample.point)
                    val x = unwrapNear(projected.x, centerX)
                    val screenX = ((x - viewport.minX) / (viewport.maxX - viewport.minX) * width).toFloat()
                    val screenY = ((projected.y - viewport.minY) / (viewport.maxY - viewport.minY) * height).toFloat()
                    assertTrue("Route x $screenX outside $safe at ${width}x$height", screenX in safe.left..safe.right)
                    assertTrue("Route y $screenY outside $safe at ${width}x$height", screenY in safe.top..safe.bottom)
                }
            }
        }
    }

    /** Counts dark pixels in a region. Overlay text is near-black; the map background is not. */
    private fun darkPixels(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): Int {
        val x0 = left.coerceIn(0, bitmap.width - 1)
        val y0 = top.coerceIn(0, bitmap.height - 1)
        val x1 = (left + width).coerceIn(x0 + 1, bitmap.width)
        val y1 = (top + height).coerceIn(y0 + 1, bitmap.height)
        var count = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val color = bitmap.getPixel(x, y)
                val luminance = Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f
                if (luminance < DARK_TEXT_LUMINANCE) count++
            }
        }
        return count
    }

    private fun point(latitude: Double, longitude: Double) = GeoPoint(
        Instant.parse("2025-06-01T00:00:00Z"),
        latitude,
        longitude,
    )

    private fun unwrapNear(value: Double, reference: Double): Double {
        var result = value
        while (result - reference > 0.5) result -= 1.0
        while (result - reference < -0.5) result += 1.0
        return result
    }

    private fun render(journey: Journey, progress: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        TimelinePainter().draw(Canvas(bitmap), SIZE, SIZE, journey, progress, "Timeline") { null }
        return bitmap
    }

    private fun countRouteColoredPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) > 180 && Color.green(color) < 190 && Color.blue(color) < 210) count++
            }
        }
        return count
    }

    companion object {
        /**
         * 720 is the reference edge: [TimelinePainter] scales overlay and stroke sizes from
         * min(width, height) / 720, so at this size strokes take their base widths and pixel counts
         * are directly comparable across changes.
         */
        private const val SIZE = 360
        private const val DARK_TEXT_LUMINANCE = 120f

        /** Square, portrait, and landscape exports, at the sizes the presets offer. */
        private val SHAPES = listOf(
            480 to 480,
            1080 to 1080,
            1080 to 1920,
            1920 to 1080,
            3840 to 2160,
        )
    }
}
