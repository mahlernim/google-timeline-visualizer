package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.AutoCompleteTextView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.videos.VideoRecord
import dev.mahlernim.timelinevisualizer.videos.VideoStore
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.export.VideoExportCoordinator
import dev.mahlernim.timelinevisualizer.export.ExportPhase
import dev.mahlernim.timelinevisualizer.export.ExportProgress
import dev.mahlernim.timelinevisualizer.export.VideoExportSnapshot
import dev.mahlernim.timelinevisualizer.export.VideoExportStateStore
import dev.mahlernim.timelinevisualizer.export.VideoExportStatus
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import dev.mahlernim.timelinevisualizer.render.DistanceUnit
import dev.mahlernim.timelinevisualizer.render.DistanceUnitPreference
import dev.mahlernim.timelinevisualizer.ui.CameraSettingsPreferences
import dev.mahlernim.timelinevisualizer.ui.DistanceUnitPreferences
import dev.mahlernim.timelinevisualizer.ui.TimelineView
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = VideoStore(context)
    private val timelineSourceStore = TimelineSourceStore(context)
    private lateinit var controller: ActivityController<MainActivity>

    @Before
    fun setUp() {
        store.clear()
        VideoExportStateStore(context).clear()
        VideoExportCoordinator.resetForTest()
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("timeline-filter-settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("distance-unit-settings", Context.MODE_PRIVATE).edit().clear().commit()
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
            VideoRecord(
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
        val list = activity.findViewById<LinearLayout>(R.id.videosList)

        assertEquals(1, list.childCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyVideosText).visibility)
        assertEquals(
            "2026 Mina's Timeline",
            list.getChildAt(0).findViewById<android.widget.TextView>(R.id.videoTitle).text.toString(),
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
    fun emptyTimelineExplainsThatTimelineMayNotHaveBeenEnabled() {
        acceptPrivacyDisclosure()
        val empty = File.createTempFile("empty-timeline", ".json", context.cacheDir)
        try {
            val activity = launchActivity(Intent(Intent.ACTION_VIEW, Uri.fromFile(empty)))
            waitUntil { activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE }

            assertEquals(
                activity.getString(R.string.timeline_error_no_locations),
                activity.findViewById<TextView>(R.id.statusText).text.toString(),
            )
        } finally {
            empty.delete()
        }
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
    fun restorationHelpOpensPublicEnglishGuide() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.restoreTimelineHelpLink).performClick()

        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MainActivity.restoreGuideUrl("en"), intent.dataString)
    }

    @Test
    fun restorationGuideHasLocalizedUrlsAndEnglishFallback() {
        assertTrue(MainActivity.restoreGuideUrl("ko").endsWith("restore-google-maps-timeline.ko.md"))
        assertTrue(MainActivity.restoreGuideUrl("ja").endsWith("restore-google-maps-timeline.ja.md"))
        assertTrue(MainActivity.restoreGuideUrl("fr").endsWith("restore-google-maps-timeline.md"))
    }

    @Test
    @Config(sdk = [35], qualifiers = "ja-w360dp-h640dp-xxhdpi")
    fun restorationLinkFitsTheSmallestJapaneseLayout() {
        val activity = launchActivity()
        val root = activity.window.decorView
        val width = activity.resources.displayMetrics.widthPixels
        val height = activity.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val link = activity.findViewById<TextView>(R.id.restoreTimelineHelpLink)

        assertEquals(View.VISIBLE, link.visibility)
        assertTrue(link.measuredWidth <= width)
        assertTrue(link.lineCount <= 2)
    }

    @Test
    fun longCreationListStartsCompactAndCanExpand() {
        repeat(4) { index ->
            store.upsert(
                VideoRecord(
                    uri = "content://example/video/$index",
                    title = "Video $index",
                    fileName = "video-$index.mp4",
                    createdAtMillis = index.toLong(),
                    durationSeconds = 30,
                ),
            )
        }

        val activity = launchActivity()
        val list = activity.findViewById<LinearLayout>(R.id.videosList)
        val showAll = activity.findViewById<View>(R.id.showAllVideosButton)

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
    fun durationMenuOffersPresetsAndCustomChoice() {
        val activity = launchActivity()
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.durationDropdown)
        val values = (0 until dropdown.adapter.count).map { dropdown.adapter.getItem(it).toString() }

        assertEquals(
            listOf(10, 15, 20, 30, 45, 60).map {
                activity.resources.getQuantityString(R.plurals.duration_seconds, it, it)
            } + activity.getString(R.string.custom_duration),
            values,
        )
    }

    @Test
    fun customDurationRejectsInvalidInputWithoutClosingAndAcceptsFiveMinutes() {
        val activity = launchActivity()
        openCustomDurationDialog(activity)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val input = dialog.findViewById<TextView>(R.id.customDurationInput)!!

        input.text = "301"
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals(30, activity.selectedDurationSeconds())

        input.text = "300"
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        assertTrue(!dialog.isShowing)
        assertEquals(300, activity.selectedDurationSeconds())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.durationWarningText).visibility)
    }

    @Test
    fun cancellingCustomDurationRestoresThePreviousValue() {
        val activity = launchActivity()
        openCustomDurationDialog(activity)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog

        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()

        assertEquals(30, activity.selectedDurationSeconds())
        assertEquals(
            activity.resources.getQuantityString(R.plurals.duration_seconds, 30, 30),
            activity.findViewById<AutoCompleteTextView>(R.id.durationDropdown).text.toString(),
        )
    }

    @Test
    fun settingsShowTheRequestedVideoDefaults() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationSettings).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
        assertEquals(
            activity.getString(R.string.camera_steady),
            activity.findViewById<AutoCompleteTextView>(R.id.cameraMovementDropdown).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.compression_balanced),
            activity.findViewById<AutoCompleteTextView>(R.id.longTripDropdown).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.resolution_480),
            activity.findViewById<AutoCompleteTextView>(R.id.videoQualityDropdown).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.aspect_square),
            activity.findViewById<AutoCompleteTextView>(R.id.aspectRatioDropdown).text.toString(),
        )
        val automaticDistance = DistanceUnit.automatic(
            android.content.res.Resources.getSystem().configuration.locales[0],
        )
        val automaticDistanceName = activity.getString(
            if (automaticDistance == DistanceUnit.MILES) {
                R.string.distance_unit_miles
            } else {
                R.string.distance_unit_kilometers
            },
        )
        assertEquals(
            activity.getString(
                R.string.distance_unit_automatic_resolved,
                activity.getString(R.string.distance_unit_automatic),
                automaticDistanceName,
            ),
            activity.findViewById<AutoCompleteTextView>(R.id.distanceUnitDropdown).text.toString(),
        )
        assertTrue(
            activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.locationFilterSwitch,
            ).isChecked,
        )
        assertEquals(
            activity.getString(R.string.language_system_default),
            activity.findViewById<AutoCompleteTextView>(R.id.languageDropdown).text.toString(),
        )
        assertEquals(10, activity.findViewById<AutoCompleteTextView>(R.id.languageDropdown).adapter.count)
        assertEquals(
            activity.getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            activity.findViewById<TextView>(R.id.versionText).text.toString(),
        )
    }

    @Test
    fun milesOverrideUpdatesSettingsSummaryAndPreviewRendering() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationSettings).performClick()
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.distanceUnitDropdown)

        dropdown.onItemClickListener?.onItemClick(null, null, DistanceUnitPreference.MILES.ordinal, 0L)

        assertEquals(activity.getString(R.string.distance_unit_miles), dropdown.text.toString())
        assertEquals(DistanceUnitPreference.MILES, DistanceUnitPreferences(activity).load())
        assertEquals("mi", activity.findViewById<TimelineView>(R.id.timelineView).renderText.distanceUnit)
        assertEquals(
            DistanceUnit.MILES.kilometersMultiplier,
            activity.findViewById<TimelineView>(R.id.timelineView).renderText.distanceScale,
            0.0,
        )
        val journey = Journey.from(
            listOf(
                GeoPoint(Instant.parse("2026-01-01T00:00:00Z"), 37.5, 127.0),
                GeoPoint(Instant.parse("2026-02-01T00:00:00Z"), 35.1, 129.0),
            ),
            2026,
        )
        assertTrue(activity.selectedPeriodSummary(journey).contains(" mi "))

        activity.findViewById<View>(R.id.resetAdvancedSettingsButton).performClick()

        assertEquals(DistanceUnitPreference.MILES, DistanceUnitPreferences(activity).load())
        assertEquals(activity.getString(R.string.distance_unit_miles), dropdown.text.toString())
    }

    @Test
    fun portraitFormatUpdatesTheStoredSettingAndPreviewShape() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationSettings).performClick()
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.aspectRatioDropdown)

        dropdown.onItemClickListener?.onItemClick(null, null, 1, 0L)

        assertEquals(activity.getString(R.string.aspect_portrait), dropdown.text.toString())
        assertEquals(VideoQuality.PORTRAIT_480, CameraSettingsPreferences(activity).load().videoQuality)
        assertEquals(
            VideoQuality.PORTRAIT_480.aspectRatio,
            activity.findViewById<TimelineView>(R.id.timelineView).previewAspectRatio,
            0.001f,
        )
    }

    @Test
    fun changingLocationFilterImmediatelyRestoresIgnoredPoints() {
        acceptPrivacyDisclosure()
        val source = Uri.fromFile(repoRoot().resolve("test-fixtures/outlier-sample.json"))
        val activity = launchActivity(Intent(Intent.ACTION_VIEW, source))
        waitUntil { activity.findViewById<View>(R.id.editorGroup).visibility == View.VISIBLE }

        val summary = activity.findViewById<TextView>(R.id.periodSummaryText)
        assertTrue(
            summary.text.contains(
                activity.resources.getQuantityString(R.plurals.location_outliers_ignored, 1, 1),
            ),
        )

        activity.findViewById<View>(R.id.navigationSettings).performClick()
        activity.findViewById<View>(R.id.locationFilterSwitch).performClick()
        activity.findViewById<View>(R.id.navigationCreate).performClick()

        assertTrue(
            !summary.text.contains(
                activity.resources.getQuantityString(R.plurals.location_outliers_ignored, 1, 1),
            ),
        )
        assertTrue(summary.text.contains("3"))
    }

    @Test
    fun exactDateRangeIsOptionalAndRevealsItsSelector() {
        val activity = launchActivity()
        val button = activity.findViewById<View>(R.id.exactDateRangeButton)

        assertEquals(View.GONE, button.visibility)
        activity.findViewById<View>(R.id.exactDateSwitch).performClick()
        assertEquals(View.VISIBLE, button.visibility)
    }

    @Test
    fun deleteAllVideosRequiresConfirmation() {
        store.upsert(
            VideoRecord(
                uri = "content://example/video",
                title = "Trip",
                fileName = "trip.mp4",
                createdAtMillis = 1L,
                durationSeconds = 30,
            ),
        )
        val activity = launchActivity()
        val deleteAll = activity.findViewById<View>(R.id.deleteAllVideosButton)

        assertEquals(View.VISIBLE, deleteAll.visibility)
        deleteAll.performClick()

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.delete_all_videos_title),
            dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle).text.toString(),
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
            activity.getString(R.string.timeline_file_unavailable),
            activity.findViewById<android.widget.TextView>(R.id.statusText).text.toString(),
        )
    }

    @Test
    fun interruptedRememberedImportStopsAutomaticRetryAndRequestsReselection() {
        val interrupted = Uri.parse("content://example/large-timeline.json")
        assertTrue(timelineSourceStore.replace(interrupted))
        assertTrue(timelineSourceStore.beginImport(interrupted))
        acceptPrivacyDisclosure()

        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationCreate).performClick()

        assertEquals(null, timelineSourceStore.load())
        assertEquals(null, timelineSourceStore.importInProgress())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.loadingGroup).visibility)
        assertEquals(
            activity.getString(R.string.timeline_file_unavailable),
            activity.findViewById<TextView>(R.id.statusText).text.toString(),
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-xxhdpi")
    fun compactEnglishButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    @Config(sdk = [35], qualifiers = "ko-rKR-w360dp-h640dp-xxhdpi")
    fun compactKoreanButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    @Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-xxhdpi")
    fun compactJapaneseButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h640dp-xxhdpi")
    fun compactSimplifiedChineseButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    @Config(sdk = [35], qualifiers = "zh-rTW-w360dp-h640dp-xxhdpi")
    fun compactTraditionalChineseButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    @Config(sdk = [35], qualifiers = "es-w360dp-h640dp-xxhdpi")
    fun compactSpanishButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    @Config(sdk = [35], qualifiers = "fr-w360dp-h640dp-xxhdpi")
    fun compactFrenchButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    @Config(sdk = [35], qualifiers = "de-w360dp-h640dp-xxhdpi")
    fun compactGermanButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    @Config(sdk = [35], qualifiers = "pt-rBR-w360dp-h640dp-xxhdpi")
    fun compactBrazilianPortugueseButtonsRemainSingleLine() = assertCompactButtons()

    @Test
    fun normalLaunchOpensCreateVideoWhenLibraryIsEmpty() {
        val activity = launchActivity()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.videosScreen).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.newVideoScreen).visibility)
        assertEquals(R.id.navigationCreate, activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId)
    }

    @Test
    fun normalLaunchOpensVideosWhenLibraryIsNotEmpty() {
        store.upsert(
            VideoRecord(
                uri = "content://example/video",
                title = "Trip",
                fileName = "trip.mp4",
                createdAtMillis = 1L,
                durationSeconds = 30,
            ),
        )

        val activity = launchActivity()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.newVideoScreen).visibility)
    }

    @Test
    fun runningExportOpensVideosWhenLibraryIsEmpty() {
        VideoExportStateStore(context).save(
            VideoExportSnapshot(status = VideoExportStatus.RUNNING, startedAtMillis = 123L),
        )

        val activity = launchActivity()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportTrayCancelButton).visibility)
    }

    @Test
    fun generationProgressTrayIsIndependentFromPreviewPlaybackControl() {
        val activity = launchActivity()
        val playback = activity.findViewById<SeekBar>(R.id.timelineSeek)
        playback.progress = 375

        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(
                status = VideoExportStatus.RUNNING,
                progress = ExportProgress(0.42f, ExportPhase.CREATING_VIDEO, 42, 100),
                startedAtMillis = System.currentTimeMillis(),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(375, playback.progress)
        assertEquals(420, activity.findViewById<LinearProgressIndicator>(R.id.exportTrayProgress).progress)
    }

    @Test
    fun generationProgressTrayLivesOutsideScrollableScreens() {
        val activity = launchActivity()
        val root = activity.findViewById<ViewGroup>(R.id.exportStatusTray).parent as ViewGroup
        val tray = activity.findViewById<View>(R.id.exportStatusTray)
        val bottomNavigation = activity.findViewById<View>(R.id.bottomNavigation)

        assertTrue(root.indexOfChild(tray) < root.indexOfChild(bottomNavigation))
    }

    @Test
    fun completedExportTrayOffersWatchAndShare() {
        val activity = launchActivity()
        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(
                status = VideoExportStatus.COMPLETE,
                outputUri = "content://example/generated-video",
                title = "Trip",
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportTrayWatchButton).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportTrayShareButton).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportTrayDismissButton).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.exportTrayProgress).visibility)
    }

    @Test
    fun completedExportTrayCanBeDismissedWithoutRemovingSavedVideo() {
        store.upsert(
            VideoRecord(
                uri = "content://example/generated-video",
                title = "Trip",
                fileName = "trip.mp4",
                createdAtMillis = 1L,
                durationSeconds = 30,
            ),
        )
        val activity = launchActivity()
        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(
                status = VideoExportStatus.COMPLETE,
                outputUri = "content://example/generated-video",
                title = "Trip",
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<View>(R.id.exportTrayDismissButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(VideoExportStatus.IDLE, VideoExportStateStore(context).load().status)
        assertEquals(listOf("content://example/generated-video"), store.list().map { it.uri })

        activity.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(listOf("content://example/generated-video"), store.list().map { it.uri })
    }

    @Test
    fun watchingCompletedExportAcknowledgesTrayWithoutRemovingSavedVideo() {
        val uri = "content://example/generated-video"
        store.upsert(
            VideoRecord(
                uri = uri,
                title = "Trip",
                fileName = "trip.mp4",
                createdAtMillis = 1L,
                durationSeconds = 30,
            ),
        )
        val activity = launchActivity()
        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(status = VideoExportStatus.COMPLETE, outputUri = uri, title = "Trip"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<View>(R.id.exportTrayWatchButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.playerScreen).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(VideoExportStatus.IDLE, VideoExportStateStore(context).load().status)
        assertEquals(listOf(uri), store.list().map { it.uri })
    }

    @Test
    fun sharingCompletedExportAcknowledgesTrayWithoutRemovingSavedVideo() {
        val uri = "content://example/generated-video"
        store.upsert(
            VideoRecord(
                uri = uri,
                title = "Trip",
                fileName = "trip.mp4",
                createdAtMillis = 1L,
                durationSeconds = 30,
            ),
        )
        val activity = launchActivity()
        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(status = VideoExportStatus.COMPLETE, outputUri = uri, title = "Trip"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<View>(R.id.exportTrayShareButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity.action)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(VideoExportStatus.IDLE, VideoExportStateStore(context).load().status)
        assertEquals(listOf(uri), store.list().map { it.uri })
    }

    @Test
    fun userNavigationToVideosAcknowledgesCompletionButSettingsDoesNot() {
        val activity = launchActivity()
        val completed = VideoExportSnapshot(
            status = VideoExportStatus.COMPLETE,
            outputUri = "content://example/generated-video",
            title = "Trip",
        )
        VideoExportCoordinator.publish(context, completed)
        shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<View>(R.id.navigationSettings).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VideoExportStatus.COMPLETE, VideoExportStateStore(context).load().status)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportStatusTray).visibility)

        activity.findViewById<View>(R.id.navigationVideos).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VideoExportStatus.IDLE, VideoExportStateStore(context).load().status)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.exportStatusTray).visibility)

        VideoExportCoordinator.publish(context, completed)
        shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<View>(R.id.navigationVideos).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VideoExportStatus.IDLE, VideoExportStateStore(context).load().status)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
    }

    @Test
    fun automaticVideosLaunchDoesNotAcknowledgeRestoredCompletion() {
        val uri = "content://example/generated-video"
        store.upsert(
            VideoRecord(
                uri = uri,
                title = "Trip",
                fileName = "trip.mp4",
                createdAtMillis = 1L,
                durationSeconds = 30,
            ),
        )
        VideoExportStateStore(context).save(
            VideoExportSnapshot(status = VideoExportStatus.COMPLETE, outputUri = uri, title = "Trip"),
        )

        val activity = launchActivity()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(VideoExportStatus.COMPLETE, VideoExportStateStore(context).load().status)
    }

    @Test
    @Config(sdk = [35], qualifiers = "de-w360dp-h640dp-xxhdpi")
    fun completedExportTrayActionsFitCompactWidth() {
        val activity = launchActivity()
        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(
                status = VideoExportStatus.COMPLETE,
                outputUri = "content://example/generated-video",
                title = "Trip",
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        measureActivity(activity)

        val actions = activity.findViewById<ViewGroup>(R.id.exportTrayActions)
        assertSingleLineButtons(actions)
        repeat(actions.childCount) { index ->
            val child = actions.getChildAt(index)
            if (child.visibility == View.VISIBLE) {
                assertTrue("Tray action exceeds compact width", child.right <= actions.width)
            }
        }
    }

    @Test
    fun failedExportTrayOffersRetry() {
        val activity = launchActivity()
        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(status = VideoExportStatus.FAILED, errorMessage = "Could not create video"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportTrayRetryButton).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.exportTrayProgress).visibility)
    }

    @Test
    fun recreatedActivityRestoresVideosInsteadOfReapplyingEmptyLibraryRule() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationVideos).performClick()

        controller.recreate()
        val recreated = controller.get()

        assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.videosScreen).visibility)
        assertEquals(View.GONE, recreated.findViewById<View>(R.id.newVideoScreen).visibility)
    }

    @Test
    fun emptyVideosRemainsReachableAndBackFromAutomaticCreateDoesNotLoop() {
        val activity = launchActivity()

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)

        activity.findViewById<View>(R.id.navigationCreate).performClick()
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
    }

    @Test
    fun backFromNewVideoReturnsToVideosWithoutCancellingExport() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationCreate).performClick()
        VideoExportCoordinator.publish(
            context,
            VideoExportSnapshot(status = VideoExportStatus.RUNNING, startedAtMillis = 123L),
        )
        shadowOf(Looper.getMainLooper()).idle()

        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.exportStatusTray).visibility)
        assertEquals(VideoExportStatus.RUNNING, VideoExportStateStore(context).load().status)
    }

    @Test
    fun switchingTabsPreservesTheUnfinishedCreateDraft() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationCreate).performClick()
        activity.findViewById<TextView>(R.id.ownerInput).text = "Mina"
        activity.findViewById<TextView>(R.id.titleInput).text = "{name}'s weekend"

        activity.findViewById<View>(R.id.navigationSettings).performClick()
        activity.findViewById<View>(R.id.navigationCreate).performClick()

        assertEquals("Mina", activity.findViewById<TextView>(R.id.ownerInput).text.toString())
        assertEquals("{name}'s weekend", activity.findViewById<TextView>(R.id.titleInput).text.toString())
    }

    @Test
    fun switchingTabsPreservesCustomDuration() {
        val activity = launchActivity()
        openCustomDurationDialog(activity)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<TextView>(R.id.customDurationInput)!!.text = "125"
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()

        activity.findViewById<View>(R.id.navigationSettings).performClick()
        activity.findViewById<View>(R.id.navigationCreate).performClick()

        assertEquals(125, activity.selectedDurationSeconds())
    }

    @Test
    @Config(sdk = [28])
    fun customDurationPropagatesToTheExportRequest() {
        acceptPrivacyDisclosure()
        val source = Uri.fromFile(repoRoot().resolve("test-fixtures/seoul-bohol-sample.json"))
        val activity = launchActivity(Intent(Intent.ACTION_VIEW, source))
        waitUntil { activity.findViewById<View>(R.id.editorGroup).visibility == View.VISIBLE }
        openCustomDurationDialog(activity)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<TextView>(R.id.customDurationInput)!!.text = "125"
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()

        activity.findViewById<View>(R.id.exportButton).performClick()

        assertEquals(125, activity.pendingExportDurationSeconds())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-xxhdpi")
    fun bottomNavigationFitsGestureInsetOnACompactPortraitDisplay() {
        val activity = launchActivity()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val density = activity.resources.displayMetrics.density
        val bottomInset = (24 * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, bottomInset))
            .build()

        ViewCompat.dispatchApplyWindowInsets(root, insets)
        measureActivity(activity)

        val navigation = activity.findViewById<ViewGroup>(R.id.bottomNavigation)
        assertEquals(0, root.paddingBottom)
        assertEquals(bottomInset, navigation.paddingBottom)
        assertNavigationItemsInsideBounds(navigation)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-xxhdpi")
    fun bottomNavigationFitsLargerLabelsOnACompactDisplay() {
        val activity = launchActivity()
        val navigation = activity.findViewById<ViewGroup>(R.id.bottomNavigation)
        for (itemId in listOf(R.id.navigationVideos, R.id.navigationCreate, R.id.navigationSettings)) {
            val item = navigation.findViewById<ViewGroup>(itemId)
            item.findViewById<TextView>(com.google.android.material.R.id.navigation_bar_item_large_label_view)
                .setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            item.findViewById<TextView>(com.google.android.material.R.id.navigation_bar_item_small_label_view)
                .setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }

        measureActivity(activity)

        assertNavigationItemsInsideBounds(navigation)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun bottomNavigationConsumesAThreeButtonInsetOnlyOnce() {
        val activity = launchActivity()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0)
        val density = activity.resources.displayMetrics.density
        val bottomInset = (48 * density).toInt()
        val topInset = (24 * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, topInset, 0, bottomInset))
            .build()

        ViewCompat.dispatchApplyWindowInsets(root, insets)
        measureActivity(activity)

        val navigation = activity.findViewById<ViewGroup>(R.id.bottomNavigation)
        assertEquals(topInset, root.paddingTop)
        assertEquals(0, root.paddingBottom)
        assertEquals(bottomInset, navigation.paddingBottom)
        assertTrue(navigation.measuredHeight >= (80 * density).toInt() + bottomInset)
        assertNavigationItemsInsideBounds(navigation)
    }

    @Test
    @Config(sdk = [35], qualifiers = "notnight")
    fun systemLightModeUsesLightThemeAndSystemBars() {
        val activity = launchActivity()

        assertEquals(activity.getColor(R.color.surface), resolvedColor(activity, com.google.android.material.R.attr.colorSurface))
        assertEquals(activity.getColor(R.color.surface_container), resolvedColor(activity, com.google.android.material.R.attr.colorSurfaceContainer))
        assertEquals(true, resolvedBoolean(activity, android.R.attr.windowLightStatusBar))
        assertEquals(true, WindowInsetsControllerCompat(activity.window, activity.window.decorView).isAppearanceLightNavigationBars)
        assertEquals(activity.getColor(R.color.surface), resolvedColor(activity, android.R.attr.statusBarColor))
        assertEquals(activity.getColor(R.color.surface_container), resolvedColor(activity, android.R.attr.navigationBarColor))
    }

    @Test
    @Config(sdk = [35], qualifiers = "night")
    fun systemDarkModeUsesDarkThemeAndSystemBars() {
        val activity = launchActivity()

        assertEquals(activity.getColor(R.color.surface), resolvedColor(activity, com.google.android.material.R.attr.colorSurface))
        assertEquals(activity.getColor(R.color.surface_container), resolvedColor(activity, com.google.android.material.R.attr.colorSurfaceContainer))
        assertEquals(false, resolvedBoolean(activity, android.R.attr.windowLightStatusBar))
        assertEquals(false, WindowInsetsControllerCompat(activity.window, activity.window.decorView).isAppearanceLightNavigationBars)
        assertEquals(activity.getColor(R.color.surface), resolvedColor(activity, android.R.attr.statusBarColor))
        assertEquals(activity.getColor(R.color.surface_container), resolvedColor(activity, android.R.attr.navigationBarColor))
    }

    @Test
    fun selectedPeriodStatesUseTheSameJourneyAvailabilityRule() {
        val activity = launchActivity()
        val period = TimelinePeriod.sameYear(2026)
        val first = GeoPoint(Instant.parse("2026-01-01T00:00:00Z"), 37.5, 127.0)
        val samePlace = GeoPoint(Instant.parse("2026-02-01T00:00:00Z"), 37.5, 127.0)
        val moved = GeoPoint(Instant.parse("2026-03-01T00:00:00Z"), 35.1, 129.0)
        val empty = Journey.from(emptyList(), period)
        val one = Journey.from(listOf(first), period)
        val still = Journey.from(listOf(first, samePlace), period)
        val moving = Journey.from(listOf(first, moved), period)

        assertEquals(activity.getString(R.string.selected_period_empty), activity.selectedPeriodSummary(empty))
        assertEquals(activity.getString(R.string.selected_period_one_point), activity.selectedPeriodSummary(one))
        assertTrue(activity.selectedPeriodSummary(still).contains(activity.getString(R.string.selected_period_no_movement, "2")))
        assertTrue(!activity.canCreateVideo(empty))
        assertTrue(!activity.canCreateVideo(one))
        assertTrue(!activity.canCreateVideo(still))
        assertTrue(activity.canCreateVideo(moving))
    }

    @Test
    fun explicitlyOpenedTimelineTakesPrecedenceOverRememberedDocument() {
        val remembered = Uri.fromFile(File(context.cacheDir, "missing-remembered.json"))
        assertTrue(timelineSourceStore.replace(remembered))
        acceptPrivacyDisclosure()
        val explicit = Uri.fromFile(repoRoot().resolve("test-fixtures/seoul-bohol-sample.json"))

        val activity = launchActivity(Intent(Intent.ACTION_VIEW, explicit))
        waitUntil { activity.findViewById<View>(R.id.editorGroup).visibility == View.VISIBLE }
        waitUntil {
            drawActivity(activity)
            timelineSourceStore.importInProgress() == null
        }

        assertEquals(explicit, timelineSourceStore.load())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.loadingGroup).visibility)
    }

    @Test
    fun rawOnlyTimelineRequiresWarningBeforeUsingEstimatedRoute() {
        acceptPrivacyDisclosure()
        val source = File.createTempFile("raw-only-timeline", ".json", context.cacheDir)
        source.writeText(
            """
            {
              "rawSignals": [
                {"position":{"LatLng":"geo:37.5000,127.0000","timestamp":"2026-08-01T00:00:00Z","accuracyMeters":10}},
                {"position":{"LatLng":"geo:37.5100,127.0100","timestamp":"2026-08-01T00:10:00Z","accuracyMeters":10}}
              ]
            }
            """.trimIndent(),
        )

        try {
            val activity = launchActivity(Intent(Intent.ACTION_VIEW, Uri.fromFile(source)))
            waitUntil {
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.isShowing == true
            }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(activity.getString(R.string.raw_only_message), dialog.findViewById<TextView>(android.R.id.message)?.text)
            assertEquals(
                activity.getString(R.string.continue_with_raw_data),
                dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).text,
            )

            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            waitUntil { activity.findViewById<View>(R.id.editorGroup).visibility == View.VISIBLE }

            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.rawSignalsDescription).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.rawAccuracyLayout).visibility)
            assertTrue(activity.findViewById<TextView>(R.id.periodSummaryText).text.contains("2"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun playbackIntentOpensTheInternalPlayerRoute() {
        val uri = Uri.parse("content://example/video/internal")
        val intent = MainActivity.playbackIntent(context, uri)

        assertEquals(MainActivity.ACTION_WATCH_VIDEO, intent.action)
        assertEquals(uri, intent.data)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun playbackIntentDisplaysTheFullScreenInternalPlayer() {
        val uri = Uri.parse("content://example/video/internal")
        val activity = launchActivity(MainActivity.playbackIntent(context, uri))

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.playerScreen).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNavigation).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.videosScreen).visibility)
    }

    private fun acceptPrivacyDisclosure() {
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit()
            .putBoolean("map_privacy_accepted_v1", true)
            .commit()
    }

    private fun assertCompactButtons() {
        val activity = launchActivity()
        measureActivity(activity)
        assertSingleLineButtons(activity.window.decorView)
        activity.findViewById<View>(R.id.navigationCreate).performClick()
        measureActivity(activity)
        assertSingleLineButtons(activity.findViewById(R.id.newVideoScreen))
        activity.findViewById<View>(R.id.navigationSettings).performClick()
        measureActivity(activity)
        assertSingleLineButtons(activity.findViewById(R.id.settingsScreen))
        controller.newIntent(MainActivity.playbackIntent(context, Uri.parse("content://example/video/layout")))
        measureActivity(activity)
        assertSingleLineButtons(activity.findViewById(R.id.playerScreen))
    }

    private fun measureActivity(activity: MainActivity) {
        val root = activity.window.decorView
        val width = activity.resources.displayMetrics.widthPixels
        val height = activity.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    private fun drawActivity(activity: MainActivity) {
        measureActivity(activity)
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.recycle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertSingleLineButtons(view: View) {
        if (view is MaterialButton && view.visibility == View.VISIBLE) {
            assertTrue("Button wrapped: ${view.text}", view.lineCount <= 1)
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) assertSingleLineButtons(view.getChildAt(index))
        }
    }

    private fun openCustomDurationDialog(activity: MainActivity) {
        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.durationDropdown)
        val position = dropdown.adapter.count - 1
        dropdown.onItemClickListener?.onItemClick(null, null, position, position.toLong())
    }

    private fun assertNavigationItemsInsideBounds(navigation: ViewGroup) {
        for (itemId in listOf(R.id.navigationVideos, R.id.navigationCreate, R.id.navigationSettings)) {
            val item = navigation.findViewById<ViewGroup>(itemId)
            val icon = item.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view)
            val label = item.findViewById<TextView>(com.google.android.material.R.id.navigation_bar_item_large_label_view)
            assertTrue(icon.top >= 0 && icon.bottom <= item.height)
            assertTrue(label.height > 0 && label.top >= 0 && label.bottom <= item.height)
            assertTrue(item.top >= 0 && item.bottom <= navigation.height - navigation.paddingBottom)
        }
    }

    private fun resolvedBoolean(activity: MainActivity, attribute: Int): Boolean {
        val value = TypedValue()
        assertTrue(activity.theme.resolveAttribute(attribute, value, true))
        return value.data != 0
    }

    private fun resolvedColor(activity: MainActivity, attribute: Int): Int {
        val value = TypedValue()
        assertTrue(activity.theme.resolveAttribute(attribute, value, true))
        return value.data
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
