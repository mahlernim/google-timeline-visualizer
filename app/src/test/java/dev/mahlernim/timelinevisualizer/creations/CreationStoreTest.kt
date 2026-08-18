package dev.mahlernim.timelinevisualizer.creations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.edit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = CreationStore(context).also(CreationStore::clear)

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
            ),
        )

        val restored = CreationStore(context).list()
        assertEquals(listOf("New", "Old"), restored.map(CreationRecord::title))
        assertEquals(2025, restored.first().startYear)
        assertEquals(12, restored.first().startMonth)
        assertEquals(2026, restored.first().endYear)
        assertEquals(1, restored.first().endMonth)
    }

    @Test
    fun upsertReplacesMatchingUriAndRemoveForgetsOnlyThatRecord() {
        store.upsert(record("content://same", "First", 100L))
        store.upsert(record("content://other", "Other", 150L))
        store.upsert(record("content://same", "Updated", 200L))

        assertEquals(listOf("Updated", "Other"), store.list().map(CreationRecord::title))
        store.remove("content://same")
        assertEquals(listOf("Other"), store.list().map(CreationRecord::title))
    }

    @Test
    fun readsLegacyYearAndMonthMetadataWithoutDroppingHistory() {
        context.getSharedPreferences("creations", Context.MODE_PRIVATE).edit {
            putString(
                "records_v1",
                """[{"uri":"content://legacy","title":"Legacy","fileName":"legacy.mp4","createdAtMillis":100,"durationSeconds":30,"year":2024,"startMonth":2,"endMonth":10}]""",
            )
        }

        val restored = CreationStore(context).list().single()

        assertEquals(2024, restored.startYear)
        assertEquals(2, restored.startMonth)
        assertEquals(2024, restored.endYear)
        assertEquals(10, restored.endMonth)
    }

    private fun record(uri: String, title: String, createdAt: Long) = CreationRecord(
        uri = uri,
        title = title,
        fileName = "$title.mp4",
        createdAtMillis = createdAt,
        durationSeconds = 30,
    )
}
