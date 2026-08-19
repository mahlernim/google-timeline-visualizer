package dev.mahlernim.timelinevisualizer

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test
    fun videosFirstLaunchAndBackNavigationWorkOnDevice() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.newVideoScreen).visibility)

                activity.findViewById<View>(R.id.navigationCreate).performClick()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.videosScreen).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.newVideoScreen).visibility)

                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)

                activity.findViewById<View>(R.id.navigationSettings).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)

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
}
