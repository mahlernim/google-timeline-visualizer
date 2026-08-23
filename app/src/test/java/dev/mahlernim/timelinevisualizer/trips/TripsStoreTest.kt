package dev.mahlernim.timelinevisualizer.trips

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TripsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.getSharedPreferences("trips_lab", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun keepsConfirmedProjectAndDismissedSuggestionAcrossStoreInstances() {
        val store = TripsStore(context)
        val project = store.create(
            "Tokyo",
            LocalDate.parse("2026-04-02"),
            LocalDate.parse("2026-04-06"),
            TripKind.TRIP,
        )
        store.dismissSuggestion("suggestion-1")

        val restored = TripsStore(context)
        assertEquals(project, restored.list().single())
        assertTrue("suggestion-1" in restored.dismissedSuggestionIds())
    }

    @Test
    fun storesNewKindsAndAutomaticTitleModeAndSupportsScopedRemoval() {
        val store = TripsStore(context)
        val custom = store.create(
            "2026 recap",
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-08-23"),
            TripKind.CUSTOM_RECAP,
            ProjectTitleMode.AUTOMATIC,
        )
        val raw = store.create(
            "Raw data",
            LocalDate.parse("2026-08-01"),
            LocalDate.parse("2026-08-23"),
            TripKind.RAW_DATA,
            ProjectTitleMode.AUTOMATIC,
        )

        val restored = TripsStore(context).list()
        assertTrue(restored.contains(custom))
        assertTrue(restored.contains(raw))

        store.remove(custom.id)
        assertFalse(store.list().any { it.id == custom.id })
        assertTrue(store.list().any { it.id == raw.id })
    }

    @Test
    fun tripsLabThreeRecordsMigrateAsCustomTitles() {
        context.getSharedPreferences("trips_lab", Context.MODE_PRIVATE).edit().putString(
            "projects_v1",
            """[{"id":"legacy","title":"My recap","startDate":"2025-01-01","endDate":"2025-12-31","kind":"YEARLY_RECAP","createdAtMillis":1}]""",
        ).commit()

        val restored = TripsStore(context).list().single()

        assertEquals(ProjectTitleMode.CUSTOM, restored.titleMode)
        assertEquals("My recap", restored.title)
    }
}
