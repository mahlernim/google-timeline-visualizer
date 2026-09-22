package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.JourneySemanticEpisode
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.ExportFormatSettings
import dev.mahlernim.timelinevisualizer.render.RenderText
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Produces inspectable encoded previews through the production exporter and real map tiles. */
@RunWith(AndroidJUnit4::class)
class RecapPreviewDeviceTest {
    @Test
    fun previewExportsKeepTenFifteenAndTwentySecondsAcrossAllThreeShapes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "recap-preview").apply { mkdirs() }
        val exporter = Mp4Exporter(context.contentResolver, TileRepository(context))
        val journey = sampleJourney()
        val variants = listOf(10 to VideoQuality.LANDSCAPE_480, 15 to VideoQuality.PORTRAIT_480, 20 to VideoQuality.STANDARD)
        for ((duration, quality) in variants) {
            val settings = CameraSettings.DEFAULT.copy(videoQuality = quality, exportFormat = ExportFormatSettings(480, 15))
            val format = settings.activeVideoFormat
            val name = "recap-${duration}s-${format.width}x${format.height}"
            val output = File(directory, "$name.mp4")
            val overview = exporter.export(Uri.fromFile(output), journey, "Recap preview", duration,
                RenderText.ENGLISH.copy(hideDates = true), settings) { }
            overview.recycle()
            assertTrue("Missing $name", output.length() > 10_000L)
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(output.absolutePath)
                val actualMs = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("$name duration was $actualMs", abs(actualMs - duration * 1000L) <= 70L)
                assertEquals(format.width.toString(), metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                assertEquals(format.height.toString(), metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                val ending = metadata.getFrameAtTime((duration - 1L) * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("No decoded recap frame", ending)
                File(directory, "$name.png").outputStream().use { ending!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ending!!.recycle()
            } finally {
                metadata.release()
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                extractor.selectTrack(0)
                var count = 0
                while (extractor.sampleTime >= 0) {
                    count++
                    if (!extractor.advance()) break
                }
                assertEquals("$name encoded frame count", format.frameRate.frameCount(duration), count)
            } finally {
                extractor.release()
            }
        }
    }

    private fun sampleJourney(): Journey {
        val sections = (0..3).map { day ->
            val start = LocalDate.of(2026, 6, 1).plusDays(day.toLong()).atTime(21, 0).atZone(ZoneId.systemDefault()).toInstant()
            (0..24).map { step ->
                val angle = step / 24.0 * Math.PI * 2
                GeoPoint(start.plusSeconds(step * 240L), 37.54 + sin(angle) * 0.016, 127.02 + cos(angle) * 0.024)
            }
        }
        val journey = Journey.fromSections(sections, TimelinePeriod.sameYear(2026))
        val episodes = sections.mapIndexed { index, points ->
            val first = index * 25
            JourneySemanticEpisode(journey.cumulativeDistanceKm[first], journey.cumulativeDistanceKm[first + 24],
                points.first(), points.last(), if (index < 3) "CYCLING" else "IN_BUS")
        }
        return journey.copy(semanticEpisodes = episodes)
    }
}
