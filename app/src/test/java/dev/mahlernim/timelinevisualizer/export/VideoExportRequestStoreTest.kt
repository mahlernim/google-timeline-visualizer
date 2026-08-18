package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import dev.mahlernim.timelinevisualizer.render.RenderText
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
            renderText = RenderText("ja", "マイタイムライン", "yyyy年M月", "km", "attribution"),
        )

        store.save(request)
        val restored = VideoExportRequestStore(context).load()!!

        assertEquals(request.outputUri, restored.outputUri)
        assertEquals(request.title, restored.title)
        assertEquals(request.durationSeconds, restored.durationSeconds)
        assertEquals(request.period, restored.period)
        assertEquals(request.renderText, restored.renderText)
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
        assertEquals(1, restored.journey.points.size)
    }
}
