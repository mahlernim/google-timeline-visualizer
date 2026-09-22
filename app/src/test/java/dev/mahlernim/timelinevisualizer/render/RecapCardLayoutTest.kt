package dev.mahlernim.timelinevisualizer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecapCardLayoutTest {
    private val sample = RecapSnapshot(161.0, 14, RecapTransportMode.CYCLING, 0.75,
        RecapPersonalityId.MAIN_CHARACTER_AFTER_DARK, 0.5, RecapAnalogyKind.MARATHONS, 4.0)

    @Test
    fun shortPositiveTripsDoNotShowZeroDistanceOrZeroLaps() {
        for ((km, expected) in listOf(0.00001 to "<0.01 km", 0.04 to "0.04 km", 0.4 to "0.4 km", 2.3 to "2.3 km")) {
            val snapshot = sample.copy(totalDistanceKm = km, analogyKind = RecapAnalogyKind.TRACK_LAPS, analogyCount = 0.0)
            val layout = RecapCardLayout.create(1080, 608, snapshot, RenderText.ENGLISH)
            assertEquals(expected, layout.blocks.single { it.role == "distance" }.layout.text.toString())
            assertFalse(layout.blocks.any { it.role == "analogy" })
        }
    }

    @Test
    fun allLocalesAndPersonalityTitlesFitAllVideoShapesWithoutClipping() {
        val gallery = File("build/outputs/recap-preview").apply { mkdirs() }
        for (tag in listOf("en", "de", "es", "fr", "id", "ja", "ko", "pt-BR", "vi", "zh-CN", "zh-TW")) {
            for ((width, height) in listOf(1080 to 608, 608 to 1080, 608 to 608)) {
                for (personality in RecapPersonalityId.entries) {
                    val snapshot = sample.copy(personalityId = personality)
                    val layout = RecapCardLayout.create(width, height, snapshot, RenderText.ENGLISH.copy(localeTag = tag))
                    val description = "$tag ${width}x$height $personality"
                    layout.blocks.forEach { block ->
                        assertTrue("$description ${block.role} top", block.y >= 0f)
                        assertTrue("$description ${block.role} bottom ${block.bottom}", block.bottom < height - 20f)
                        assertTrue("$description ${block.role} right", block.right <= width)
                        for (line in 0 until block.layout.lineCount) {
                            assertTrue("$description ${block.role} width", block.layout.getLineWidth(line) <= block.layout.width + 1f)
                            assertEquals("$description truncated text", 0, block.layout.getEllipsisCount(line))
                        }
                    }
                    val title = layout.blocks.single { it.role == "punchline" }
                    val evidence = layout.blocks.single { it.role == "evidence" }
                    assertTrue("$description hierarchy", title.layout.paint.textSize > evidence.layout.paint.textSize * 1.4f)
                    if (personality == RecapPersonalityId.MAIN_CHARACTER_AFTER_DARK) {
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.rgb(53, 43, 64))
                        layout.draw(canvas)
                        File(gallery, "$tag-${width}x$height.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun unitsAndLanguageReformatCachedNumbersWithoutReanalyzingTheJourney() {
        val journey = Journey.from(listOf(
            GeoPoint(Instant.parse("2026-06-01T01:00:00Z"), 37.5, 127.0),
            GeoPoint(Instant.parse("2026-06-01T02:00:00Z"), 37.6, 127.1),
        ), 2026)
        val painter = TimelinePainter()
        val metric = painter.recapLayout(journey, 1080, 608, RenderText.ENGLISH)
        val repeated = painter.recapLayout(journey, 1080, 608, RenderText.ENGLISH)
        assertTrue(metric === repeated)
        val imperial = painter.recapLayout(journey, 1080, 608, RenderText.ENGLISH.copy(distanceUnit = "mi", distanceScale = 0.621371))
        assertNotEquals(metric.blocks.first { it.role == "distance" }.layout.text.toString(), imperial.blocks.first { it.role == "distance" }.layout.text.toString())
        val korean = painter.recapLayout(journey, 608, 1080, RenderText.ENGLISH.copy(localeTag = "ko"))
        assertNotEquals(metric.blocks.first { it.role == "punchline" }.layout.text, korean.blocks.first { it.role == "punchline" }.layout.text)
        assertEquals(1, painter.recapAnalysisCount)
        painter.recapLayout(journey.copy(), 608, 1080, RenderText.ENGLISH)
        assertEquals(2, painter.recapAnalysisCount)
    }

    @Test
    fun theRecapNeverPrintsDatesAndKeepsBackgroundAttribution() {
        val journey = Journey.from(listOf(
            GeoPoint(Instant.parse("2026-06-01T01:00:00Z"), 37.5, 127.0),
            GeoPoint(Instant.parse("2026-06-01T02:00:00Z"), 37.6, 127.1),
        ), 2026)
        val painter = TimelinePainter()
        val bitmaps = listOf(false, true).map { hidden ->
            Bitmap.createBitmap(1080, 608, Bitmap.Config.ARGB_8888).also {
                painter.draw(Canvas(it), 1080, 608, journey, TimelineFrame(1f, 1f, 1f), 15,
                    "Private June 2026", RenderText.ENGLISH.copy(hideDates = hidden)) { null }
            }
        }
        assertTrue(bitmaps[0].sameAs(bitmaps[1]))
        val withoutAttribution = Bitmap.createBitmap(1080, 608, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(withoutAttribution), 1080, 608, journey, TimelineFrame(1f, 1f, 1f), 15,
            "Private June 2026", RenderText.ENGLISH.copy(attribution = "")) { null }
        assertFalse("Attribution must still be rendered on the ending", bitmaps[0].sameAs(withoutAttribution))
        for (y in 0 until 586) for (x in 0 until 1080) {
            assertEquals("Credits altered the main card", bitmaps[0].getPixel(x, y), withoutAttribution.getPixel(x, y))
        }
        (bitmaps + withoutAttribution).forEach(Bitmap::recycle)
    }
}
