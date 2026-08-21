package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraSettingsPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = CameraSettingsPreferences(context)

    @Before
    fun reset() {
        preferences.reset()
    }

    @After
    fun tearDown() {
        preferences.reset()
    }

    @Test
    fun savesAndRestoresAllAdvancedSettings() {
        val expected = CameraSettings(
            cameraMovement = CameraMovement.FIXED,
            longTripCompression = LongTripCompression.STRONG,
            videoQuality = VideoQuality.ULTRA,
            zoomInMovementReduction = 0.85,
        )

        preferences.save(expected)

        assertEquals(expected, CameraSettingsPreferences(context).load())
    }

    @Test
    fun defaultsMatchTheAdvancedPanelDefaults() {
        assertEquals(CameraSettings.DEFAULT, preferences.load())
        assertEquals(CameraMovement.STEADY, preferences.load().cameraMovement)
        assertEquals(LongTripCompression.BALANCED, preferences.load().longTripCompression)
        assertEquals(VideoQuality.STANDARD, preferences.load().videoQuality)
        assertEquals(0.60, preferences.load().zoomInMovementReduction, 0.0001)
    }

    @Test
    fun savesAndRestoresPortraitAndLandscapeFormats() {
        listOf(VideoQuality.PORTRAIT, VideoQuality.LANDSCAPE).forEach { format ->
            preferences.save(CameraSettings(videoQuality = format))

            assertEquals(format, CameraSettingsPreferences(context).load().videoQuality)
        }
    }

    @Test
    fun migratesTheEarlyLabFloatPreference() {
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)
            .edit()
            .putFloat("zoom-in-movement-reduction", 0.75f)
            .apply()

        assertEquals(0.75, CameraSettingsPreferences(context).load().zoomInMovementReduction, 0.0001)
    }
}
