package dev.mahlernim.timelinevisualizer

import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.AutoCompleteTextView
import android.widget.Filterable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.export.VideoExportCoordinator
import dev.mahlernim.timelinevisualizer.export.VideoExportSnapshot
import dev.mahlernim.timelinevisualizer.export.VideoExportStatus
import dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStore
import dev.mahlernim.timelinevisualizer.videos.VideoRecord
import dev.mahlernim.timelinevisualizer.videos.VideoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test
    fun exportTrayClearsSystemNavigationDuringPlayback() {
        completeJournalOnboarding()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val videoFile = File(context.cacheDir, "tray-existing.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("tray-playback.mp4").use { input ->
            videoFile.outputStream().use(input::copyTo)
        }
        val uri = Uri.fromFile(videoFile)
        VideoStore(context).upsert(
            VideoRecord(uri.toString(), "Existing video", videoFile.name, System.currentTimeMillis(), 1),
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                VideoExportCoordinator.publish(
                    context,
                    VideoExportSnapshot(
                        status = VideoExportStatus.RUNNING,
                        startedAtMillis = System.currentTimeMillis(),
                    ),
                    persist = false,
                )
                waitForVisible(scenario, R.id.exportStatusTray)
                waitForEnabled(scenario, R.id.videoWatchButton)
                scenario.onActivity { activity ->
                    val tray = activity.findViewById<View>(R.id.exportStatusTray)
                    val navigation = activity.findViewById<View>(R.id.bottomNavigation)
                    val trayBounds = Rect()
                    val navigationBounds = Rect()
                    assertTrue(tray.getGlobalVisibleRect(trayBounds))
                    assertTrue(navigation.getGlobalVisibleRect(navigationBounds))
                    assertTrue(trayBounds.bottom <= navigationBounds.top - dp(activity, 8))
                    assertTrue(activity.findViewById<View>(R.id.videoWatchButton).isEnabled)
                    activity.findViewById<View>(R.id.videoWatchButton).performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val tray = activity.findViewById<View>(R.id.exportStatusTray)
                    val navigation = activity.findViewById<View>(R.id.bottomNavigation)
                    val decor = activity.window.decorView
                    val trayBounds = Rect()
                    val decorBounds = Rect()
                    val bottomInset = ViewCompat.getRootWindowInsets(decor)
                        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom
                        ?: throw AssertionError("Missing system-bar insets")
                    assertTrue(tray.getGlobalVisibleRect(trayBounds))
                    assertTrue(decor.getGlobalVisibleRect(decorBounds))
                    assertEquals(View.GONE, navigation.visibility)
                    assertTrue(trayBounds.bottom <= decorBounds.bottom - bottomInset - dp(activity, 8))
                    activity.findViewById<View>(R.id.playerBackButton).performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val tray = activity.findViewById<View>(R.id.exportStatusTray)
                    val navigation = activity.findViewById<View>(R.id.bottomNavigation)
                    val trayBounds = Rect()
                    val navigationBounds = Rect()
                    assertTrue(tray.getGlobalVisibleRect(trayBounds))
                    assertTrue(navigation.getGlobalVisibleRect(navigationBounds))
                    assertEquals(View.VISIBLE, navigation.visibility)
                    assertTrue(trayBounds.bottom <= navigationBounds.top - dp(activity, 8))
                }
            }
        } finally {
            VideoExportCoordinator.publish(context, VideoExportSnapshot(status = VideoExportStatus.IDLE), persist = false)
            VideoStore(context).remove(uri.toString())
            videoFile.delete()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test
    fun emptyLibraryLaunchAndBackNavigationWorkOnDevice() {
        completeJournalOnboarding()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForVisible(scenario, R.id.videosScreen)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.newVideoScreen).visibility)
                activity.findViewById<View>(R.id.navigationCreate).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalSetupIntro).visibility)

                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)

                activity.findViewById<View>(R.id.navigationSettings).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
                val tripDetection = activity.findViewById<AutoCompleteTextView>(R.id.tripDetectionDropdown)
                val localFraming = activity.findViewById<AutoCompleteTextView>(R.id.localFramingDropdown)
                assertEquals(activity.getString(R.string.trip_detection_balanced), tripDetection.text.toString())
                assertEquals(activity.getString(R.string.local_framing_balanced), localFraming.text.toString())
                localFraming.onItemClickListener?.onItemClick(null, null, 0, 0L)
                assertEquals(activity.getString(R.string.local_framing_off), localFraming.text.toString())
                assertTrue(tripDetection.isEnabled)
                val settingsBounds = Rect()
                val defaultsBounds = Rect()
                activity.findViewById<View>(R.id.settingsScreen).getGlobalVisibleRect(settingsBounds)
                activity.findViewById<View>(R.id.resetAdvancedSettingsButton).getGlobalVisibleRect(defaultsBounds)
                assertTrue(defaultsBounds.bottom <= settingsBounds.bottom)

                val navigation = activity.findViewById<ViewGroup>(R.id.bottomNavigation)
                for (itemId in listOf(R.id.navigationVideos, R.id.navigationCreate, R.id.navigationSettings)) {
                    val item = navigation.findViewById<ViewGroup>(itemId)
                    val icon = item.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view)
                    val label = item.findViewById<TextView>(com.google.android.material.R.id.navigation_bar_item_large_label_view)
                    assertEquals(true, icon.top >= 0 && icon.bottom <= item.height)
                    assertEquals(true, label.height > 0 && label.top >= 0 && label.bottom <= item.height)
                }
            }
        }
    }

    @Test
    fun settingsDropdownsKeepEveryChoiceAfterSelectionAndNavigation() {
        completeJournalOnboarding()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.navigationSettings).performClick() }
            val choices = buildList {
                add(R.id.aspectRatioDropdown to 3)
                add(R.id.cameraMovementDropdown to 4)
                add(R.id.longTripDropdown to 4)
                add(R.id.videoQualityDropdown to 6)
                add(R.id.frameRateDropdown to 4)
                add(R.id.languageDropdown to 10)
                add(R.id.tripDetectionDropdown to 3)
                add(R.id.localFramingDropdown to 3)
            }
            choices.forEach { (id, count) -> assertChoicesRemainAvailable(scenario, id, count) }

            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.navigationVideos).performClick()
                activity.findViewById<View>(R.id.navigationCreate).performClick()
                activity.findViewById<View>(R.id.navigationSettings).performClick()
            }
            choices.forEach { (id, count) -> assertChoicesRemainAvailable(scenario, id, count) }
        }
    }

    @Test
    fun appLanguageSelectionSurvivesRecreationAndReturnsToSystemDefault() {
        completeJournalOnboarding()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.navigationSettings).performClick()
                activity.findViewById<AutoCompleteTextView>(R.id.languageDropdown)
                    .onItemClickListener?.onItemClick(null, null, 2, 0L)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("ko", activity.resources.configuration.locales[0].language)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
                assertEquals(
                    activity.getString(R.string.language_name_ko),
                    activity.findViewById<AutoCompleteTextView>(R.id.languageDropdown).text.toString(),
                )
                assertEquals(
                    activity.getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    activity.findViewById<TextView>(R.id.versionText).text.toString(),
                )
            }

            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
            }
        }
    }

    private fun assertChoicesRemainAvailable(
        scenario: ActivityScenario<MainActivity>,
        dropdownId: Int,
        expectedCount: Int,
    ) {
        val completed = CountDownLatch(1)
        scenario.onActivity { activity ->
            val dropdown = activity.findViewById<AutoCompleteTextView>(dropdownId)
            dropdown.setText(dropdown.adapter.getItem(expectedCount - 1).toString(), false)
            (dropdown.adapter as Filterable).filter.filter(dropdown.text) { completed.countDown() }
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        scenario.onActivity { activity ->
            assertEquals(expectedCount, activity.findViewById<AutoCompleteTextView>(dropdownId).adapter.count)
        }
    }

    private fun completeJournalOnboarding() {
        JournalOnboardingStore(ApplicationProvider.getApplicationContext()).complete()
    }

    private fun dp(activity: MainActivity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun waitForVisible(scenario: ActivityScenario<MainActivity>, viewId: Int) {
        val deadline = System.currentTimeMillis() + 10_000L
        var visible = false
        while (System.currentTimeMillis() < deadline && !visible) {
            scenario.onActivity { activity ->
                visible = activity.findViewById<View>(viewId).visibility == View.VISIBLE
            }
            if (!visible) Thread.sleep(50)
        }
        assertTrue(visible)
    }

    private fun waitForEnabled(scenario: ActivityScenario<MainActivity>, viewId: Int) {
        val deadline = System.currentTimeMillis() + 10_000L
        var enabled = false
        while (System.currentTimeMillis() < deadline && !enabled) {
            scenario.onActivity { enabled = it.findViewById<View>(viewId).isEnabled }
            if (!enabled) Thread.sleep(50)
        }
        assertTrue(enabled)
    }
}
