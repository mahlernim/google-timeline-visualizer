package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.AutoCompleteTextView
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.creations.CreationRecord
import dev.mahlernim.timelinevisualizer.creations.CreationStore
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.export.VideoExportCoordinator
import dev.mahlernim.timelinevisualizer.export.VideoExportSnapshot
import dev.mahlernim.timelinevisualizer.export.VideoExportStateStore
import dev.mahlernim.timelinevisualizer.export.VideoExportStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = CreationStore(context)
    private val timelineSourceStore = TimelineSourceStore(context)
    private lateinit var controller: ActivityController<MainActivity>

    @Before
    fun setUp() {
        store.clear()
        VideoExportStateStore(context).clear()
        VideoExportCoordinator.resetForTest()
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().clear().commit()
        timelineSourceStore.clearForTest()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
        store.clear()
        VideoExportStateStore(context).clear()
        VideoExportCoordinator.resetForTest()
        timelineSourceStore.clearForTest()
    }

    @Test
    fun savedCreationAppearsAfterActivityRestart() {
        store.upsert(
            CreationRecord(
                uri = "content://example/video",
                title = "2026 Mina's Timeline",
                fileName = "timeline.mp4",
                createdAtMillis = 1_786_900_000_000L,
                durationSeconds = 30,
                startYear = 2026,
                startMonth = 1,
                endYear = 2026,
                endMonth = 12,
            ),
        )

        val activity = launchActivity()
        val list = activity.findViewById<LinearLayout>(R.id.creationsList)

        assertEquals(1, list.childCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyCreationsText).visibility)
        assertEquals(
            "2026 Mina's Timeline",
            list.getChildAt(0).findViewById<android.widget.TextView>(R.id.creationTitle).text.toString(),
        )
    }

    @Test
    fun checkForUpdatesOpensConfiguredDistributionPage() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.checkUpdatesButton).performClick()

        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            BuildConfig.UPDATE_URL,
            intent.dataString,
        )
    }

    @Test
    fun firstTimelineLoadShowsMapPrivacyDisclosure() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.importButton).performClick()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertEquals(true, dialog.isShowing)
        assertEquals(
            activity.getString(R.string.map_privacy_message),
            dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString(),
        )
    }

    @Test
    fun privacyPolicyOpensPublicEnglishPolicy() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.privacyPolicyButton).performClick()

        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.md",
            intent.dataString,
        )
    }

    @Test
    fun longCreationListStartsCompactAndCanExpand() {
        repeat(4) { index ->
            store.upsert(
                CreationRecord(
                    uri = "content://example/video/$index",
                    title = "Video $index",
                    fileName = "video-$index.mp4",
                    createdAtMillis = index.toLong(),
                    durationSeconds = 30,
                ),
            )
        }

        val activity = launchActivity()
        val list = activity.findViewById<LinearLayout>(R.id.creationsList)
        val showAll = activity.findViewById<View>(R.id.showAllCreationsButton)

        assertEquals(3, list.childCount)
        assertEquals(View.VISIBLE, showAll.visibility)
        showAll.performClick()
        assertEquals(4, list.childCount)
    }

    @Test
    fun leavingActivityDoesNotCancelRunningVideoCreation() {
        launchActivity()
        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(status = VideoExportStatus.RUNNING, startedAtMillis = 123L),
        )

        controller.pause().stop().destroy()

        assertEquals(VideoExportStatus.RUNNING, VideoExportStateStore(context).load().status)
    }

    @Test
    fun durationMenuIncludesFortyFiveAndSeventyFiveSeconds() {
        val activity = launchActivity()
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.durationDropdown)
        val values = (0 until dropdown.adapter.count).map { dropdown.adapter.getItem(it).toString() }

        assertEquals(
            listOf(15, 30, 45, 60, 75, 90).map {
                activity.resources.getQuantityString(R.plurals.duration_seconds, it, it)
            },
            values,
        )
    }

    @Test
    fun missingRememberedDocumentIsClearedAndLoadingStateAlwaysEnds() {
        val missing = Uri.fromFile(File(context.cacheDir, "missing-timeline.json"))
        assertTrue(timelineSourceStore.replace(missing))
        acceptPrivacyDisclosure()

        val activity = launchActivity()
        waitUntil { timelineSourceStore.load() == null }

        assertEquals(View.GONE, activity.findViewById<View>(R.id.loadingGroup).visibility)
        assertEquals(true, activity.findViewById<View>(R.id.importButton).isEnabled)
        assertEquals(
            activity.getString(R.string.remembered_timeline_unavailable),
            activity.findViewById<android.widget.TextView>(R.id.statusText).text.toString(),
        )
    }

    @Test
    fun explicitlyOpenedTimelineTakesPrecedenceOverRememberedDocument() {
        val remembered = Uri.fromFile(File(context.cacheDir, "missing-remembered.json"))
        assertTrue(timelineSourceStore.replace(remembered))
        acceptPrivacyDisclosure()
        val explicit = Uri.fromFile(repoRoot().resolve("test-fixtures/seoul-bohol-sample.json"))

        val activity = launchActivity(Intent(Intent.ACTION_VIEW, explicit))
        waitUntil { activity.findViewById<View>(R.id.editorGroup).visibility == View.VISIBLE }

        assertEquals(explicit, timelineSourceStore.load())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.loadingGroup).visibility)
    }

    private fun acceptPrivacyDisclosure() {
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit()
            .putBoolean("map_privacy_accepted_v1", true)
            .commit()
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("The asynchronous activity operation did not finish")
    }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: error("Working directory unavailable")).absoluteFile
        while (!File(current, "settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Repository root unavailable")
        }
        return current
    }

    private fun launchActivity(intent: Intent? = null): MainActivity {
        controller = if (intent == null) {
            Robolectric.buildActivity(MainActivity::class.java)
        } else {
            Robolectric.buildActivity(MainActivity::class.java, intent)
        }.setup()
        return controller.get()
    }
}
