package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.data.LocationFilterMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationFilterPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = LocationFilterPreferences(context)

    @Before
    fun reset() {
        preferences.reset()
    }

    @After
    fun tearDown() {
        preferences.reset()
    }

    @Test
    fun defaultsToConservativeAndPersistsOffMode() {
        assertEquals(LocationFilterMode.CONSERVATIVE, preferences.load())

        preferences.save(LocationFilterMode.OFF)

        assertEquals(LocationFilterMode.OFF, LocationFilterPreferences(context).load())
    }
}
