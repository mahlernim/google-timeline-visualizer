package dev.mahlernim.timelinevisualizer.trips

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
