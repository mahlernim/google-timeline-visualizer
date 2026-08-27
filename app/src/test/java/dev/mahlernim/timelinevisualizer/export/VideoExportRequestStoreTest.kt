package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import dev.mahlernim.timelinevisualizer.render.RenderText
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.ExportFormatSettings
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import java.time.Instant
import java.time.YearMonth
import java.io.DataOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.mahlernim.timelinevisualizer.videos.VideoDataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoExportRequestStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = VideoExportRequestStore(context).also(VideoExportRequestStore::clear)

    @After
    fun tearDown() = store.clear()

    @Test
    fun restoresEverythingNeededToRestartVideoCreation() {
        val points = listOf(
            GeoPoint(Instant.parse("2026-01-02T03:04:05Z"), 37.5665, 126.9780),
            GeoPoint(Instant.parse("2026-06-07T08:09:10Z"), 9.6500, 123.8500),
        )
        val request = VideoExportRequest(
            outputUri = "content://documents/timeline.mp4",
            journey = Journey.from(
                points,
                TimelinePeriod(YearMonth.of(2025, 12), YearMonth.of(2026, 6)),
            ),
            title = "2026 Mina's Timeline",
            durationSeconds = 60,
            renderText = RenderText(
                "ja",
                "マイタイムライン",
                "yyyy年M月",
                "mi",
                "attribution",
                distanceScale = 0.621371192237334,
            ),
            cameraSettings = CameraSettings(
                cameraMovement = CameraMovement.FIXED,
                longTripCompression = LongTripCompression.STRONGER,
                videoQuality = VideoQuality.ULTRA,
                tripDetection = TripDetection.SENSITIVE,
                localFraming = LocalFraming.CLOSE,
                keepPastRoutesVisible = true,
            ),
            projectId = "trip-123",
            presetName = "Cinematic",
            dataSource = VideoDataSource.RAW,
        )

        store.save(request)
        val restored = VideoExportRequestStore(context).load()!!

        assertEquals(request.outputUri, restored.outputUri)
        assertEquals(request.title, restored.title)
        assertEquals(request.durationSeconds, restored.durationSeconds)
        assertEquals(request.period, restored.period)
        assertEquals(request.renderText, restored.renderText)
        assertEquals(request.cameraSettings, restored.cameraSettings)
        assertEquals(request.projectId, restored.projectId)
        assertEquals(request.presetName, restored.presetName)
        assertEquals(VideoDataSource.RAW, restored.dataSource)
        assertEquals(request.journey.points, restored.journey.points)
    }

    @Test
    fun clearRemovesPendingRestartData() {
        val request = VideoExportRequest(
            outputUri = "content://documents/timeline.mp4",
            journey = Journey.from(emptyList(), 2026),
            title = "Timeline",
            durationSeconds = 30,
        )
        store.save(request)

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun journalSourceSurvivesPendingExportRestart() {
        val request = VideoExportRequest(
            outputUri = "content://documents/journal.mp4",
            journey = Journey.from(
                listOf(
                    GeoPoint(Instant.parse("2026-01-01T00:00:00Z"), 37.5, 127.0),
                    GeoPoint(Instant.parse("2026-01-01T01:00:00Z"), 37.6, 127.1),
                ),
                2026,
            ),
            title = "Travel Journal",
            durationSeconds = 30,
            dataSource = VideoDataSource.JOURNAL,
        )

        store.save(request)

        assertEquals(VideoDataSource.JOURNAL, store.load()?.dataSource)
    }

    @Test
    fun journalRouteBreaksSurvivePendingExportRestart() {
        val request = VideoExportRequest(
            outputUri = "content://documents/journal-with-gap.mp4",
            journey = Journey.fromSections(
                listOf(
                    listOf(
                        GeoPoint(Instant.parse("2026-01-01T00:00:00Z"), 37.5, 127.0),
                        GeoPoint(Instant.parse("2026-01-01T01:00:00Z"), 37.6, 127.1),
                    ),
                    listOf(
                        GeoPoint(Instant.parse("2026-01-03T00:00:00Z"), 35.6, 139.7),
                        GeoPoint(Instant.parse("2026-01-03T01:00:00Z"), 35.7, 139.8),
                    ),
                ),
                TimelinePeriod.sameYear(2026),
                inferredTransferBeforePointIndices = listOf(1),
            ),
            title = "Travel Journal",
            durationSeconds = 30,
            dataSource = VideoDataSource.JOURNAL,
        )

        store.save(request)
        val restored = store.load()!!

        assertEquals(request.journey.points, restored.journey.points)
        assertEquals(listOf(2), restored.journey.breakBeforePointIndices)
        assertEquals(listOf(1), restored.journey.inferredTransferBeforePointIndices)
        assertEquals(request.journey.totalDistanceKm, restored.journey.totalDistanceKm, 0.001)
        assertEquals(request.journey.knownDistanceKm, restored.journey.knownDistanceKm, 0.001)
    }

    @Test
    fun restoresPortraitAndLandscapePendingExports() {
        listOf(VideoQuality.PORTRAIT, VideoQuality.LANDSCAPE).forEach { format ->
            val request = VideoExportRequest(
                outputUri = "content://documents/${format.name.lowercase()}.mp4",
                journey = Journey.from(emptyList(), 2026),
                title = format.name,
                durationSeconds = 30,
                cameraSettings = CameraSettings(videoQuality = format),
            )

            store.save(request)

            assertEquals(format, store.load()!!.cameraSettings.videoQuality)
        }
    }

    @Test
    fun restoresCustomResolutionAndFrameRate() {
        val format = ExportFormatSettings(2000, 25, customResolution = true, customFrameRate = true)
        val request = VideoExportRequest(
            outputUri = "content://documents/custom.mp4",
            journey = Journey.from(emptyList(), 2026),
            title = "Custom",
            durationSeconds = 30,
            cameraSettings = CameraSettings.DEFAULT.copy(exportFormat = format),
        )

        store.save(request)

        assertEquals(format, store.load()!!.cameraSettings.exportFormat)
    }

    @Test
    fun readsProductionVersionNineExportFormat() {
        writeModernRequest(version = 9) { output ->
            output.writeBoolean(true)
            output.writeInt(1440)
            output.writeInt(60)
            output.writeBoolean(false)
            output.writeBoolean(false)
        }

        val restored = store.load()!!

        assertEquals(1440, restored.cameraSettings.effectiveExportFormat.shortEdge)
        assertEquals(60, restored.cameraSettings.effectiveExportFormat.frameRate)
        assertNull(restored.projectId)
        assertEquals(VideoDataSource.SEMANTIC, restored.dataSource)
    }

    @Test
    fun readsTripsLabFourVersionTenAssociationsWithoutExportFormat() {
        writeModernRequest(version = 10) { output ->
            output.writeBoolean(true)
            output.writeUTF("trip-lab-4")
            output.writeBoolean(true)
            output.writeUTF("Historical preset")
            output.writeUTF(VideoDataSource.RAW.name)
        }

        val restored = store.load()!!

        assertEquals(480, restored.cameraSettings.effectiveExportFormat.shortEdge)
        assertEquals("trip-lab-4", restored.projectId)
        assertEquals("Historical preset", restored.presetName)
        assertEquals(VideoDataSource.RAW, restored.dataSource)
    }

    @Test
    fun readsVersionOneAsASameYearEnglishRequest() {
        val requestFile = File(context.filesDir, "pending-video-export.bin")
        DataOutputStream(requestFile.outputStream().buffered()).use { output ->
            output.writeInt(1)
            output.writeUTF("content://documents/legacy.mp4")
            output.writeUTF("Legacy timeline")
            output.writeInt(30)
            output.writeInt(2025)
            output.writeInt(3)
            output.writeInt(11)
            output.writeInt(1)
            output.writeLong(Instant.parse("2025-06-01T00:00:00Z").toEpochMilli())
            output.writeDouble(35.0)
            output.writeDouble(139.0)
        }

        val restored = store.load()!!

        assertEquals(YearMonth.of(2025, 3), restored.period.start)
        assertEquals(YearMonth.of(2025, 11), restored.period.endInclusive)
        assertEquals(RenderText.ENGLISH, restored.renderText)
        assertEquals(VideoQuality.STANDARD, restored.cameraSettings.videoQuality)
        assertEquals(24, restored.cameraSettings.effectiveExportFormat.frameRate)
        assertEquals(LocalFraming.OFF, restored.cameraSettings.localFraming)
        assertEquals(1, restored.journey.points.size)
    }

    private fun writeModernRequest(version: Int, extra: (DataOutputStream) -> Unit) {
        val requestFile = File(context.filesDir, "pending-video-export.bin")
        DataOutputStream(requestFile.outputStream().buffered()).use { output ->
            output.writeInt(version)
            output.writeUTF("content://documents/compatible.mp4")
            output.writeUTF("Compatible request")
            output.writeInt(30)
            output.writeInt(2026)
            output.writeInt(1)
            output.writeInt(2026)
            output.writeInt(1)
            output.writeUTF("en-US")
            output.writeUTF("My Timeline")
            output.writeUTF("MMMM yyyy")
            output.writeUTF("km")
            output.writeUTF("attribution")
            output.writeDouble(1.0)
            output.writeUTF(CameraMovement.STEADY.name)
            output.writeUTF(LongTripCompression.BALANCED.name)
            output.writeUTF(VideoQuality.STANDARD.name)
            output.writeUTF(TripDetection.BALANCED.name)
            output.writeUTF(LocalFraming.BALANCED.name)
            extra(output)
            output.writeInt(0)
        }
    }

    @Test
    fun readsVersionFourPendingExportsAsKilometers() {
        val requestFile = File(context.filesDir, "pending-video-export.bin")
        DataOutputStream(requestFile.outputStream().buffered()).use { output ->
            output.writeInt(4)
            output.writeUTF("content://documents/version-four.mp4")
            output.writeUTF("Version four")
            output.writeInt(30)
            output.writeInt(2026)
            output.writeInt(1)
            output.writeInt(2026)
            output.writeInt(12)
            output.writeUTF("en-US")
            output.writeUTF("My Timeline")
            output.writeUTF("MMMM yyyy")
            output.writeUTF("km")
            output.writeUTF("attribution")
            output.writeUTF(CameraMovement.STEADY.name)
            output.writeUTF(LongTripCompression.BALANCED.name)
            output.writeUTF(VideoQuality.STANDARD.name)
            output.writeInt(1)
            output.writeLong(Instant.parse("2026-06-01T00:00:00Z").toEpochMilli())
            output.writeDouble(35.0)
            output.writeDouble(139.0)
        }

        val restored = store.load()!!

        assertEquals("km", restored.renderText.distanceUnit)
        assertEquals(1.0, restored.renderText.distanceScale, 0.0)
        assertEquals(CameraMovement.STEADY, restored.cameraSettings.cameraMovement)
        assertEquals(LongTripCompression.BALANCED, restored.cameraSettings.longTripCompression)
        assertEquals(VideoQuality.STANDARD, restored.cameraSettings.videoQuality)
        assertEquals(false, restored.cameraSettings.episodeFramingEnabled)
    }

    @Test
    fun readsVersionFivePendingExportsWithLocalFramingOff() {
        val requestFile = File(context.filesDir, "pending-video-export.bin")
        DataOutputStream(requestFile.outputStream().buffered()).use { output ->
            output.writeInt(5)
            output.writeUTF("content://documents/version-five.mp4")
            output.writeUTF("Version five")
            output.writeInt(30)
            output.writeInt(2026)
            output.writeInt(1)
            output.writeInt(2026)
            output.writeInt(12)
            output.writeUTF("en-US")
            output.writeUTF("My Timeline")
            output.writeUTF("MMMM yyyy")
            output.writeUTF("km")
            output.writeUTF("attribution")
            output.writeDouble(1.0)
            output.writeUTF(CameraMovement.DYNAMIC.name)
            output.writeUTF(LongTripCompression.BALANCED.name)
            output.writeUTF(VideoQuality.STANDARD.name)
            output.writeInt(1)
            output.writeLong(Instant.parse("2026-06-01T00:00:00Z").toEpochMilli())
            output.writeDouble(35.0)
            output.writeDouble(139.0)
        }

        val restored = store.load()!!

        assertEquals(CameraMovement.DYNAMIC, restored.cameraSettings.cameraMovement)
        assertEquals(LocalFraming.OFF, restored.cameraSettings.localFraming)
    }

    @Test
    fun readsVersionSixPendingExportsWithoutChangingTheirCameraFraming() {
        val requestFile = File(context.filesDir, "pending-video-export.bin")
        DataOutputStream(requestFile.outputStream().buffered()).use { output ->
            output.writeInt(6)
            output.writeUTF("content://documents/version-six.mp4")
            output.writeUTF("Version six")
            output.writeInt(30)
            output.writeInt(2026)
            output.writeInt(1)
            output.writeInt(2026)
            output.writeInt(12)
            output.writeUTF("en-US")
            output.writeUTF("My Timeline")
            output.writeUTF("MMMM yyyy")
            output.writeUTF("km")
            output.writeUTF("attribution")
            output.writeDouble(1.0)
            output.writeUTF(CameraMovement.CLOSE_UP.name)
            output.writeUTF(LongTripCompression.BALANCED.name)
            output.writeUTF(VideoQuality.STANDARD.name)
            output.writeDouble(0.75)
            output.writeInt(1)
            output.writeLong(Instant.parse("2026-06-01T00:00:00Z").toEpochMilli())
            output.writeDouble(35.0)
            output.writeDouble(139.0)
        }

        val restored = store.load()!!

        assertEquals(false, restored.cameraSettings.episodeFramingEnabled)
        assertEquals(TripDetection.BALANCED, restored.cameraSettings.tripDetection)
        assertEquals(LocalFraming.OFF, restored.cameraSettings.localFraming)
    }

    @Test
    fun migratesVersionSevenFramingAndDiscardsSlowdown() {
        val requestFile = File(context.filesDir, "pending-video-export.bin")
        DataOutputStream(requestFile.outputStream().buffered()).use { output ->
            output.writeInt(7)
            output.writeUTF("content://documents/version-seven.mp4")
            output.writeUTF("Version seven")
            output.writeInt(30)
            output.writeInt(2026)
            output.writeInt(1)
            output.writeInt(2026)
            output.writeInt(12)
            output.writeUTF("en-US")
            output.writeUTF("My Timeline")
            output.writeUTF("MMMM yyyy")
            output.writeUTF("km")
            output.writeUTF("attribution")
            output.writeDouble(1.0)
            output.writeUTF(CameraMovement.DYNAMIC.name)
            output.writeUTF(LongTripCompression.STRONG.name)
            output.writeUTF(VideoQuality.PORTRAIT.name)
            output.writeDouble(0.90)
            output.writeBoolean(true)
            output.writeUTF(TripDetection.SENSITIVE.name)
            output.writeUTF(LocalFraming.CLOSE.name)
            output.writeInt(0)
        }

        val restored = store.load()!!

        assertEquals(CameraMovement.DYNAMIC, restored.cameraSettings.cameraMovement)
        assertEquals(LongTripCompression.STRONG, restored.cameraSettings.longTripCompression)
        assertEquals(VideoQuality.PORTRAIT, restored.cameraSettings.videoQuality)
        assertEquals(TripDetection.SENSITIVE, restored.cameraSettings.tripDetection)
        assertEquals(LocalFraming.CLOSE, restored.cameraSettings.localFraming)
    }

    @Test
    fun migratesDetailedVersionThreeSettingsToSteadyCameraMovement() {
        val requestFile = File(context.filesDir, "pending-video-export.bin")
        DataOutputStream(requestFile.outputStream().buffered()).use { output ->
            output.writeInt(3)
            output.writeUTF("content://documents/version-three.mp4")
            output.writeUTF("Version three")
            output.writeInt(30)
            output.writeInt(2026)
            output.writeInt(1)
            output.writeInt(2026)
            output.writeInt(12)
            repeat(5) { output.writeUTF("legacy-render-text") }
            output.writeUTF("MAXIMUM")
            output.writeUTF("WIDE")
            output.writeUTF("CINEMATIC")
            output.writeUTF("LESS_SENSITIVE")
            output.writeUTF("BALANCED")
            output.writeUTF("HIGH")
            output.writeInt(1)
            output.writeLong(Instant.parse("2026-06-01T00:00:00Z").toEpochMilli())
            output.writeDouble(35.0)
            output.writeDouble(139.0)
        }

        val restored = store.load()!!

        assertEquals(CameraMovement.STEADY, restored.cameraSettings.cameraMovement)
        assertEquals(LongTripCompression.BALANCED, restored.cameraSettings.longTripCompression)
        assertEquals(VideoQuality.HIGH, restored.cameraSettings.videoQuality)
    }
}
