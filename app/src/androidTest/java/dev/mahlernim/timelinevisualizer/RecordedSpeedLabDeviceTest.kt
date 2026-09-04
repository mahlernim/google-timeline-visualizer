package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.export.Mp4Exporter
import dev.mahlernim.timelinevisualizer.export.NoRecordedMovementException
import dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStore
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.ExportFormatSettings
import dev.mahlernim.timelinevisualizer.render.RenderText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordedSpeedLabDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun point(lon: Double, seconds: Long) = GeoPoint(Instant.parse("2026-08-01T00:00:00Z").plusSeconds(seconds), 35.0, lon)

    @Test fun experimentalIdentityNoticeAndMissingMovementGuardAreVisible() {
        assumeTrue(BuildConfig.IS_RECORDED_SPEED_LAB)
        assertEquals("dev.mahlernim.timelinevisualizer.recordedspeedlab", context.packageName)
        assertEquals("Recorded Speed LAB", context.applicationInfo.loadLabel(context.packageManager).toString())
        JournalOnboardingStore(context).complete()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.navigationSettings).performClick()
                val settings = activity.findViewById<View>(R.id.settingsScreen)
                val notice = settings.findViewById<TextView>(R.id.recordedSpeedNotice)
                assertTrue(notice.isShown)
                assertTrue(notice.text.contains("Recorded speed only"))
                val invalid = Journey.from(listOf(point(128.0, 0), point(128.1, 86400)), 2026)
                assertFalse(activity.canCreateVideo(invalid))
                val moving = Journey.from(listOf(point(128.0, 0), point(128.1, 600)), 2026)
                assertTrue(activity.canCreateVideo(moving))
                val icon = context.applicationInfo.loadIcon(context.packageManager)
                val bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, 216, 216)
                icon.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null), "recorded-speed-lab-icon.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    @Test fun actualEncoderExportsRecordedJourneyAndRejectsMissingTiming() = runBlocking {
        assumeTrue(BuildConfig.IS_RECORDED_SPEED_LAB)
        // Offline tiles isolate timing/encoding verification from external map availability.
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(235, 240, 245)) }
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        val tiles = TileRepository(context, connectionFactory = { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        })
        val exporter = Mp4Exporter(context.contentResolver, tiles)
        val file = File(context.cacheDir, "recorded-speed-device-test.mp4")
        val journey = Journey.from(listOf(point(128.0, 0), point(128.01, 600), point(128.04, 1200)), 2026)
        try {
            exporter.export(Uri.fromFile(file), journey, "Recorded speed", 4, RenderText.ENGLISH,
                CameraSettings.DEFAULT.copy(cameraMovement = CameraMovement.CLOSE_UP,
                    exportFormat = ExportFormatSettings(480, 24))) { }
            assertTrue(file.length() > 0)
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(file.absolutePath)
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Actual duration was $duration ms", duration in 3950..4050)
                assertNotNull(reader.getFrameAtTime(1_000_000))
            }
            val invalid = Journey.from(listOf(point(128.0, 0), point(128.1, 86400)), 2026)
            try {
                exporter.export(Uri.fromFile(file), invalid, "Unsupported", 4, RenderText.ENGLISH) { }
                fail("Unsupported movement must not fall back to visual pacing")
            } catch (_: NoRecordedMovementException) { }
        } finally {
            file.delete()
            // Do not leave offline test tiles in the installed app cache.
            File(context.cacheDir, "carto-tiles-v2").deleteRecursively()
        }
    }
}
