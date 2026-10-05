package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Opt-in live map check with synthetic points, the real encoder, and cancellation. */
@RunWith(AndroidJUnit4::class)
class VectorExportDeviceTest {
    @Test fun createsMp4AfterCancellationInSquareAndLandscape() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVectorExport") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(permissions.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertFalse(permissions.contains("android.permission.ACCESS_WIFI_STATE"))
        @Suppress("DEPRECATION")
        val features = context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        assertFalse(features.any { it.name == android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION && it.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED != 0 })
        val folder = File(context.getExternalFilesDir(null), "vector-preview-export").apply { mkdirs() }
        val journey = Journey.from(listOf(37.55 to 126.92,37.57 to 126.98,37.58 to 127.02,37.54 to 127.06).mapIndexed { i, (lat,lon) ->
            GeoPoint(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i*86400L),lat,lon)
        },2026)
        val exporter = Mp4Exporter(context.contentResolver,TileRepository(context),context)
        val settings = CameraSettings.DEFAULT.copy(cameraMovement=CameraMovement.CLOSE_UP,exportFormat=ExportFormatSettings(480,24))
        try {
            exporter.export(Uri.fromFile(File(folder,"cancelled.mp4")),journey,"Vector preview",10,RenderText.ENGLISH,settings,true) {
                if (it.phase == ExportPhase.CREATING_VIDEO && it.completed >= 24) throw CancellationException("Test cancellation")
            }.recycle()
            fail("Export should have been cancelled")
        } catch (_: CancellationException) { }
        for (quality in listOf(VideoQuality.STANDARD,VideoQuality.LANDSCAPE_480)) {
            val selected = settings.copy(videoQuality=quality)
            val file = File(folder,"${quality.name.lowercase()}.mp4")
            val started = System.nanoTime()
            val overview = exporter.export(Uri.fromFile(file),journey,"Vector preview",10,RenderText.ENGLISH,selected,true) { }
            File(folder,"${quality.name.lowercase()}-overview.png").outputStream().use { overview.compress(Bitmap.CompressFormat.PNG,100,it) }
            overview.recycle()
            File(folder,"${quality.name.lowercase()}-time.txt").writeText(((System.nanoTime()-started)/1e9).toString())
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.absolutePath)
                assertEquals(selected.activeVideoFormat.width.toString(),reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                assertEquals(selected.activeVideoFormat.height.toString(),reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 9900..10100)
                val frame = requireNotNull(reader.getFrameAtTime(5_000_000))
                File(folder,"${quality.name.lowercase()}-frame.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
                frame.recycle()
            } finally { reader.release() }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                extractor.selectTrack(0)
                var frames = 0
                while (extractor.sampleTime >= 0) { frames++; extractor.advance() }
                assertEquals(240,frames)
            } finally { extractor.release() }
        }
    }
}
