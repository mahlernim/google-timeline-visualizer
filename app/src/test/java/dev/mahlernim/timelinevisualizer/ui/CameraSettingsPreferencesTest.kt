package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.ExportFormatSettings
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.TripDetection
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
            longTripCompression = LongTripCompression.STRONGER,
            videoQuality = VideoQuality.ULTRA,
            tripDetection = TripDetection.SENSITIVE,
            localFraming = LocalFraming.CLOSE,
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
        assertEquals(TripDetection.BALANCED, preferences.load().tripDetection)
        assertEquals(LocalFraming.BALANCED, preferences.load().localFraming)
    }

    @Test
    fun savesAndRestoresPortraitAndLandscapeFormats() {
        listOf(VideoQuality.PORTRAIT, VideoQuality.LANDSCAPE).forEach { format ->
            preferences.save(CameraSettings(videoQuality = format))

            assertEquals(format, CameraSettingsPreferences(context).load().videoQuality)
        }
    }

    @Test
    fun removesObsoleteSlowdownPreferencesWhenSaving() {
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)
            .edit()
            .putFloat("zoom-in-movement-reduction", 0.75f)
            .putInt("zoom-in-travel-slowdown", 90)
            .apply()

        preferences.save(CameraSettings.DEFAULT)

        val stored = context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE).all
        assertEquals(false, stored.containsKey("zoom-in-movement-reduction"))
        assertEquals(false, stored.containsKey("zoom-in-travel-slowdown"))
    }

    @Test
    fun migratesDisabledCameraLabFourFramingToOff() {
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("episode-framing-enabled", false)
            .putString("episode-local-framing", "CLOSE")
            .apply()

        assertEquals(LocalFraming.OFF, CameraSettingsPreferences(context).load().localFraming)
    }

    @Test
    fun migratesEnabledCameraLabFourFramingAndDropsWide() {
        val raw = context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)
        raw.edit()
            .putBoolean("episode-framing-enabled", true)
            .putString("episode-local-framing", "CLOSE")
            .apply()

        assertEquals(LocalFraming.CLOSE, CameraSettingsPreferences(context).load().localFraming)

        raw.edit()
            .putBoolean("episode-framing-enabled", true)
            .putString("episode-local-framing", "WIDE")
            .apply()

        assertEquals(LocalFraming.BALANCED, CameraSettingsPreferences(context).load().localFraming)
    }

    @Test
    fun gentleCompressionMigratesToBalanced() {
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("long-trip", "GENTLE")
            .apply()

        assertEquals(LongTripCompression.BALANCED, CameraSettingsPreferences(context).load().longTripCompression)
    }

    @Test
    fun existingProductionSettingsKeepLocalFramingOffDuringUpgrade() {
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("camera-movement", "STEADY")
            .putString("long-trip", "BALANCED")
            .putString("video-quality", "STANDARD")
            .apply()

        assertEquals(LocalFraming.OFF, CameraSettingsPreferences(context).load().localFraming)
        assertEquals(24, CameraSettingsPreferences(context).load().effectiveExportFormat.frameRate)
    }

    @Test
    fun existingPortraitSettingsKeepThirtyFramesPerSecondDuringUpgrade() {
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("video-quality", VideoQuality.PORTRAIT.name)
            .apply()

        assertEquals(30, CameraSettingsPreferences(context).load().effectiveExportFormat.frameRate)
    }

    @Test
    fun savesCustomExportFormatAndResetUsesThirtyFramesPerSecond() {
        val custom = ExportFormatSettings(2000, 25, customResolution = true, customFrameRate = true)
        preferences.save(CameraSettings.DEFAULT.copy(exportFormat = custom))

        assertEquals(custom, CameraSettingsPreferences(context).load().exportFormat)

        preferences.reset()
        assertEquals(30, CameraSettingsPreferences(context).load().effectiveExportFormat.frameRate)
    }
}
