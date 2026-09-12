package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.render.DistanceUnit
import java.util.Locale
import dev.mahlernim.timelinevisualizer.render.DistanceUnitPreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DistanceUnitPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = DistanceUnitPreferences(context)

    @Before
    fun reset() = preferences.clear()

    @After
    fun tearDown() = preferences.clear()

    @Test
    fun kilometersAreTheDefaultEvenInTheUs() {
        assertEquals(DistanceUnitPreference.KILOMETERS, preferences.load())
        assertEquals(DistanceUnit.KILOMETERS, preferences.load().resolve(Locale.US))
    }

    @Test
    fun preservesSavedAutomaticPreference() {
        preferences.save(DistanceUnitPreference.AUTOMATIC)

        assertEquals(DistanceUnitPreference.AUTOMATIC, DistanceUnitPreferences(context).load())
    }

    @Test
    fun savesAndRestoresAnExplicitOverride() {
        preferences.save(DistanceUnitPreference.MILES)

        assertEquals(DistanceUnitPreference.MILES, DistanceUnitPreferences(context).load())
    }

    @Test
    fun invalidStoredValuesFallBackToKilometers() {
        context.getSharedPreferences("distance-unit-settings", Context.MODE_PRIVATE)
            .edit().putString("distance-unit", "NAUTICAL_MILES").commit()

        assertEquals(DistanceUnitPreference.KILOMETERS, preferences.load())
    }
}
