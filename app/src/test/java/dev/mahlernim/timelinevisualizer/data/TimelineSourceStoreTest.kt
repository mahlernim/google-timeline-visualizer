package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineSourceStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = TimelineSourceStore(context).also(TimelineSourceStore::clearForTest)

    @After
    fun tearDown() = store.clearForTest()

    @Test
    fun replacingSourceKeepsOnlyTheLatestDocument() {
        val first = Uri.parse("content://example/first.json")
        val second = Uri.parse("content://example/second.json")

        assertTrue(store.replace(first))
        assertTrue(store.replace(second))
        assertEquals(second, store.load())
        assertEquals(second, store.clear())
        assertNull(store.load())
    }

    @Test
    fun interruptedRememberedImportIsClearedForManualReselection() {
        val source = Uri.parse("content://example/large.json")
        assertTrue(store.replace(source))
        assertTrue(store.beginImport(source))

        assertEquals(source, store.recoverInterruptedImport())
        assertNull(store.load())
        assertNull(store.importInProgress())
    }

    @Test
    fun successfulRenderedImportClearsOnlyItsMarker() {
        val source = Uri.parse("content://example/large.json")
        assertTrue(store.replace(source))
        assertTrue(store.beginImport(source))

        store.completeImport(source)

        assertEquals(source, store.load())
        assertNull(store.importInProgress())
    }

    @Test
    fun storesLocalSourceSummaryAndClearsItWithTheMasterSource() {
        val metadata = TimelineSourceMetadata(
            fileName = "Timeline.json",
            importedAtMillis = 1234L,
            semanticStart = LocalDate.parse("2020-01-02"),
            semanticEnd = LocalDate.parse("2026-08-23"),
            rawStart = LocalDate.parse("2026-08-01"),
            rawEnd = LocalDate.parse("2026-08-23"),
            fileSizeBytes = 58L * 1024L * 1024L,
        )
        store.replace(Uri.parse("content://example/timeline.json"))
        store.updateMetadata(metadata)

        assertEquals(metadata, TimelineSourceStore(context).metadata())

        store.clear()
        assertNull(store.metadata())
    }

    @Test
    fun olderMetadataWithoutFileSizeRemainsReadable() {
        val metadata = TimelineSourceMetadata(
            fileName = "Timeline.json",
            importedAtMillis = 1234L,
            semanticStart = null,
            semanticEnd = null,
            rawStart = null,
            rawEnd = null,
        )

        store.updateMetadata(metadata)

        assertEquals(metadata, store.metadata())
        assertNull(store.metadata()?.fileSizeBytes)
    }
}
