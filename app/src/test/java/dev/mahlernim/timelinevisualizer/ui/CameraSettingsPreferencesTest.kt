package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoFormatPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            videoFormatPreset = VideoFormatPreset.PORTRAIT_1080,
        )

        preferences.save(expected)

        assertEquals(expected, CameraSettingsPreferences(context).load())
    }

    @Test
    fun savesAndRestoresACustomFormat() {
        val expected = CameraSettings(
            videoFormatPreset = VideoFormatPreset.CUSTOM,
            customFormat = VideoFormat.custom(1280, 720, 30),
        )

        preferences.save(expected)

        val restored = CameraSettingsPreferences(context).load()
        assertEquals(expected, restored)
        assertEquals(1280, restored.videoFormat.width)
        assertEquals(720, restored.videoFormat.height)
        assertEquals(30, restored.videoFormat.frameRate)
    }

    @Test
    fun defaultsMatchTheAdvancedPanelDefaults() {
        assertEquals(CameraSettings.DEFAULT, preferences.load())
        assertEquals(CameraMovement.STEADY, preferences.load().cameraMovement)
        assertEquals(LongTripCompression.BALANCED, preferences.load().longTripCompression)
        assertEquals(VideoFormatPreset.SQUARE_480, preferences.load().videoFormatPreset)
        assertEquals(480, preferences.load().videoFormat.width)
        assertEquals(480, preferences.load().videoFormat.height)
    }

    @Test
    fun readsTheVideoQualityWrittenByEarlierVersions() {
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE).edit()
            .putString("video-quality", "ULTRA")
            .commit()

        val restored = CameraSettingsPreferences(context).load()

        assertEquals(VideoFormatPreset.SQUARE_1080, restored.videoFormatPreset)
        assertEquals(1080, restored.videoFormat.width)
        assertEquals(1080, restored.videoFormat.height)
        assertEquals(24, restored.videoFormat.frameRate)
    }

    @Test
    fun droppingTheLegacyQualityKeyOnSaveLeavesTheNewChoiceInPlace() {
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE).edit()
            .putString("video-quality", "ULTRA")
            .commit()

        preferences.save(CameraSettings(videoFormatPreset = VideoFormatPreset.LANDSCAPE_1080))

        val stored = context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)
        assertNull(stored.getString("video-quality", null))
        assertEquals(VideoFormatPreset.LANDSCAPE_1080, CameraSettingsPreferences(context).load().videoFormatPreset)
    }
}
