package dev.mahlernim.timelinevisualizer

import android.graphics.Rect
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test
    fun emptyLibraryLaunchAndBackNavigationWorkOnDevice() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.videosScreen).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.newVideoScreen).visibility)

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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.navigationSettings).performClick() }
            val choices = buildList {
                add(R.id.aspectRatioDropdown to 3)
                add(R.id.cameraMovementDropdown to 4)
                add(R.id.longTripDropdown to 4)
                add(R.id.videoQualityDropdown to 3)
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
}
