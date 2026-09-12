package dev.mahlernim.timelinevisualizer

import android.app.Activity
import android.provider.Settings
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportHelpActivityTest {
    @Test
    fun locationSettingsLeavesGuideOpenAndRecreationPreservesChooseAction() {
        val controller = Robolectric.buildActivity(ExportHelpActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<View>(R.id.exportGuideSettings).performClick()
            assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, shadowOf(activity).nextStartedActivity.action)
            assertFalse(activity.isFinishing)
            controller.pause().stop().start().resume()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportGuideChooseFile).visibility)
            controller.recreate()
            val recreated = controller.get()
            recreated.findViewById<View>(R.id.exportGuideChooseFile).performClick()
            assertEquals(Activity.RESULT_OK, shadowOf(recreated).resultCode)
            assertTrue(recreated.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun backClosesGuideWithoutRequestingImport() {
        val controller = Robolectric.buildActivity(ExportHelpActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<View>(R.id.exportGuideBack).performClick()
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            assertTrue(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
