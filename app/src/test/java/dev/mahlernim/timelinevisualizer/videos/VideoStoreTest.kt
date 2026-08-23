package dev.mahlernim.timelinevisualizer.videos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.edit
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import dev.mahlernim.timelinevisualizer.render.VideoResolution
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = VideoStore(context).also(VideoStore::clear)

    @After
    fun tearDown() = store.clear()

    @Test
    fun savesNewestFirstAndRestoresOptionalTimelineDetails() {
        store.upsert(record("content://old", "Old", 100L))
        store.upsert(
            record("content://new", "New", 200L).copy(
                startYear = 2025,
                startMonth = 12,
                endYear = 2026,
                endMonth = 1,
                projectId = "trip-123",
                presetName = "Cinematic",
            ),
        )

        val restored = VideoStore(context).list()
        assertEquals(listOf("New", "Old"), restored.map(VideoRecord::title))
        assertEquals(2025, restored.first().startYear)
        assertEquals(12, restored.first().startMonth)
        assertEquals(2026, restored.first().endYear)
        assertEquals(1, restored.first().endMonth)
        assertEquals("trip-123", restored.first().projectId)
        assertEquals("Cinematic", restored.first().presetName)
    }

    @Test
    fun upsertReplacesMatchingUriAndRemoveForgetsOnlyThatRecord() {
        store.upsert(record("content://same", "First", 100L))
        store.upsert(record("content://other", "Other", 150L))
        store.upsert(record("content://same", "Updated", 200L))

        assertEquals(listOf("Updated", "Other"), store.list().map(VideoRecord::title))
        store.remove("content://same")
        assertEquals(listOf("Other"), store.list().map(VideoRecord::title))
    }

    @Test
    fun removeAllForgetsOnlySuccessfullyDeletedRecords() {
        store.upsert(record("content://one", "One", 100L))
        store.upsert(record("content://two", "Two", 200L))
        store.upsert(record("content://three", "Three", 300L))

        store.removeAll(setOf("content://one", "content://three"))

        assertEquals(listOf("Two"), store.list().map(VideoRecord::title))
    }

    @Test
    fun readsLegacyYearAndMonthMetadataWithoutDroppingHistory() {
        context.getSharedPreferences("creations", Context.MODE_PRIVATE).edit {
            putString(
                "records_v1",
                """[{"uri":"content://legacy","title":"Legacy","fileName":"legacy.mp4","createdAtMillis":100,"durationSeconds":30,"year":2024,"startMonth":2,"endMonth":10}]""",
            )
        }

        val restored = VideoStore(context).list().single()

        assertEquals(2024, restored.startYear)
        assertEquals(2, restored.startMonth)
        assertEquals(2024, restored.endYear)
        assertEquals(10, restored.endMonth)
        assertEquals(null, restored.settingsSnapshot)
    }

    @Test
    fun savesOptionalEffectiveSettingsSnapshot() {
        val snapshot = VideoSettingsSnapshot(
            presetName = "Trip Close-up",
            requestedDurationSeconds = 20,
            aspectRatio = VideoAspectRatio.SQUARE,
            cameraMovement = CameraMovement.CLOSE_UP,
            tripDetection = TripDetection.SENSITIVE,
            localFraming = LocalFraming.CLOSE,
            longTripCompression = LongTripCompression.STRONGER,
            resolution = VideoResolution.HIGH,
            exportShortEdge = 1440,
            exportFrameRate = 60,
        )
        store.upsert(record("content://trip", "Tokyo", 300L).copy(settingsSnapshot = snapshot))

        assertEquals(snapshot, VideoStore(context).list().single().settingsSnapshot)
    }

    private fun record(uri: String, title: String, createdAt: Long) = VideoRecord(
        uri = uri,
        title = title,
        fileName = "$title.mp4",
        createdAtMillis = createdAt,
        durationSeconds = 30,
    )
}
