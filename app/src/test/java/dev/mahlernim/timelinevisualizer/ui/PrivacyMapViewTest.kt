package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.privacy.PrivacyArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacyMapViewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun fitsTheWholeTimelineAndCanFocusAnExistingArea() {
        val view = PrivacyMapView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 720)
        view.setTimeline(
            listOf(
                GeoPoint(Instant.EPOCH, 37.5665, 126.9780),
                GeoPoint(Instant.EPOCH, 35.1796, 129.0756),
            ),
        )
        val work = PrivacyArea("work", "Work", 35.1796, 129.0756, 1.5)

        view.focusOn(work)
        val selected = view.selectedPoint()

        assertEquals(work.latitude, selected.latitude, 0.0001)
        assertEquals(work.longitude, selected.longitude, 0.0001)
        assertEquals(work.radiusKm, view.candidateRadiusKm, 0.0001)
        assertTrue(view.performClick())
    }
}
