package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], qualifiers = "w640dp-h360dp-land")
class MainActivityInsetsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun resetJournal() {
        TimelineSourceStore(context).clear()
        context.deleteDatabase("travel-journal.db")
        context.getSharedPreferences(JournalOnboardingStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        JournalOnboardingStore(context).complete()
    }

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    @Test
    fun sideSystemBarsProtectEveryRootChild() {
        val activity = launchActivity()
        val root = contentRoot(activity)

        dispatchInsets(root, bars = Insets.of(18, 24, 48, 0))

        assertRootPadding(root, 18, 24, 48)
    }

    @Test
    fun displayCutoutsAndSystemBarsUseTheMaximumPerEdge() {
        val activity = launchActivity()
        val root = contentRoot(activity)
        activity.findViewById<View>(R.id.bottomNavigation).visibility = View.VISIBLE

        dispatchInsets(root, Insets.of(10, 24, 30, 48), Insets.of(72, 60, 0, 12))

        assertRootPadding(root, 72, 60, 30)
        assertEquals(48, activity.findViewById<View>(R.id.bottomNavigation).paddingBottom)
        assertEquals(0, activity.findViewById<View>(R.id.videosScreen).paddingBottom)
    }

    @Test
    fun repeatedInsetDispatchAndRotationDoNotAccumulatePadding() {
        val activity = launchActivity()
        val root = contentRoot(activity)

        repeat(2) {
            dispatchInsets(root, Insets.of(0, 24, 48, 0), Insets.of(72, 0, 0, 0))
            assertRootPadding(root, 72, 24, 48)
        }
        dispatchInsets(root, Insets.of(48, 24, 0, 0), Insets.of(0, 0, 72, 0))
        assertRootPadding(root, 48, 24, 72)
        dispatchInsets(root, Insets.of(0, 24, 0, 24))
        assertRootPadding(root, 0, 24, 0)
    }

    @Test
    fun bottomSafeAreaRetainsNavigationContentAndTrayOwnership() {
        val activity = launchActivity()
        val root = contentRoot(activity)
        val navigation = activity.findViewById<View>(R.id.bottomNavigation)
        val tray = activity.findViewById<View>(R.id.exportStatusTray)
        val baseMargin = (tray.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val screens = listOf(
            R.id.videosScreen, R.id.newVideoScreen, R.id.settingsScreen,
            R.id.playerScreen, R.id.journalOnboardingScreen,
        ).map { activity.findViewById<View>(it) }
        navigation.visibility = View.GONE
        tray.visibility = View.VISIBLE

        dispatchInsets(root, Insets.of(0, 24, 0, 24), Insets.of(0, 0, 0, 44))

        assertEquals(0, navigation.paddingBottom)
        screens.forEach { assertEquals(44, it.paddingBottom) }
        assertEquals(baseMargin + 44, (tray.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)

        navigation.visibility = View.VISIBLE
        dispatchInsets(root, Insets.of(0, 24, 0, 24), Insets.of(0, 0, 0, 44))

        assertEquals(44, navigation.paddingBottom)
        screens.forEach { assertEquals(0, it.paddingBottom) }
        assertEquals(baseMargin, (tray.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)

        navigation.visibility = View.GONE
        tray.visibility = View.GONE
        dispatchInsets(root, Insets.of(0, 24, 0, 24), Insets.of(0, 0, 0, 44))
        assertEquals(baseMargin, (tray.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
    }

    private fun launchActivity(): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = created
        return created.get()
    }

    private fun contentRoot(activity: MainActivity): View =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun dispatchInsets(root: View, bars: Insets, cutout: Insets = Insets.NONE) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
            .setDisplayCutout(
                DisplayCutoutCompat(Rect(cutout.left, cutout.top, cutout.right, cutout.bottom), emptyList()),
            )
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    private fun assertRootPadding(root: View, left: Int, top: Int, right: Int) {
        assertEquals(left, root.paddingLeft)
        assertEquals(top, root.paddingTop)
        assertEquals(right, root.paddingRight)
        assertEquals(0, root.paddingBottom)
    }
}
