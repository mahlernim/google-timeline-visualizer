package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.data.LocationFilterMode
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.DistanceUnitPreference
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelTest {
    private lateinit var cameraPreferences: CameraSettingsPreferences
    private lateinit var distancePreferences: DistanceUnitPreferences
    private lateinit var filterPreferences: LocationFilterPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        cameraPreferences = CameraSettingsPreferences(context)
        distancePreferences = DistanceUnitPreferences(context)
        filterPreferences = LocationFilterPreferences(context)
        clearPreferences()
    }

    @After
    fun tearDown() = clearPreferences()

    @Test
    fun updatesArePersistedAndExposedAsOneState() {
        val viewModel = viewModel()
        val camera = CameraSettings(
            cameraMovement = CameraMovement.DYNAMIC,
            longTripCompression = LongTripCompression.STRONG,
            videoQuality = VideoQuality.PORTRAIT,
        )

        viewModel.updateCamera(camera)
        viewModel.updateDistanceUnit(DistanceUnitPreference.MILES)
        viewModel.updateLocationFilter(LocationFilterMode.OFF)

        assertEquals(SettingsState(camera, DistanceUnitPreference.MILES, LocationFilterMode.OFF), viewModel.state.value)
        assertEquals(camera, cameraPreferences.load())
        assertEquals(DistanceUnitPreference.MILES, distancePreferences.load())
        assertEquals(LocationFilterMode.OFF, filterPreferences.load())
    }

    @Test
    fun resetPreservesRegionalAndTimelinePreferences() {
        val viewModel = viewModel()
        viewModel.updateCamera(CameraSettings.DEFAULT.copy(videoQuality = VideoQuality.ULTRA))
        viewModel.updateDistanceUnit(DistanceUnitPreference.MILES)
        viewModel.updateLocationFilter(LocationFilterMode.OFF)

        viewModel.resetVideoDefaults()

        assertEquals(CameraSettings.DEFAULT, viewModel.state.value.camera)
        assertEquals(DistanceUnitPreference.MILES, viewModel.state.value.distanceUnit)
        assertEquals(LocationFilterMode.OFF, viewModel.state.value.locationFilter)
    }

    private fun viewModel() = SettingsViewModel(cameraPreferences, distancePreferences, filterPreferences)

    private fun clearPreferences() {
        cameraPreferences.reset()
        distancePreferences.clear()
        filterPreferences.reset()
    }
}
