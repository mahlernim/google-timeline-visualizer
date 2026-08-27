package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStore
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalEntity
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import dev.mahlernim.timelinevisualizer.presets.PresetRepository
import dev.mahlernim.timelinevisualizer.trips.SuggestionConfidence
import dev.mahlernim.timelinevisualizer.trips.ProjectTitleMode
import dev.mahlernim.timelinevisualizer.trips.TripKind
import dev.mahlernim.timelinevisualizer.trips.TripProject
import dev.mahlernim.timelinevisualizer.trips.TripSuggestion
import dev.mahlernim.timelinevisualizer.videos.VideoDataSource
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalLabUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun resetJournal() {
        TimelineSourceStore(context).clear()
        context.deleteDatabase("travel-journal.db")
        context.getSharedPreferences(JournalOnboardingStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("video-presets", Context.MODE_PRIVATE).edit().clear().commit()
        JournalOnboardingStore(context).complete()
    }

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    @Test
    fun creationUsesOneAutomaticJournalSourceWithoutRawChoice() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.rawDataChoice).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.automaticJournalSourceText).visibility)
        val sourceMessage = activity.getString(R.string.journal_automatic_source_summary)
        assertTrue(sourceMessage.contains("automatically use your most detailed saved routes"))
        assertTrue(sourceMessage.contains("best available Timeline history"))
        assertFalse(sourceMessage.contains("raw data", ignoreCase = true))
    }

    @Test
    fun settingsUseConcreteTimelineImportLanguage() {
        assertEquals("Travel Journal", context.getString(R.string.timeline_data))
        assertEquals("Import updated Timeline", context.getString(R.string.import_updated_timeline))
        assertEquals("Get updated Timeline file", context.getString(R.string.get_updated_timeline_file))
        assertTrue(context.getString(R.string.timeline_not_imported).contains("ready to grow"))
        assertEquals("Title shown in video", context.getString(R.string.video_title_template))
        assertEquals(
            "Edit the title that appears in this video.",
            context.getString(R.string.title_template_help),
        )
        assertFalse(context.getString(R.string.title_template_help).contains("{year}"))
    }

    @Test
    fun emptyJournalUsesFirstImportLanguage() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationSettings).performClick()
        waitUntil {
            activity.findViewById<TextView>(R.id.settingsImportTimelineButton).text ==
                activity.getString(R.string.import_timeline_to_journal)
        }

        assertEquals(
            activity.getString(R.string.import_timeline_to_journal),
            activity.findViewById<TextView>(R.id.settingsImportTimelineButton).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.get_timeline_file),
            activity.findViewById<TextView>(R.id.settingsTimelineHelpButton).text.toString(),
        )
    }

    @Test
    fun videoDefaultsCanBeSavedAsAPresetBesidePresetManagement() {
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationSettings).performClick()

        val saveButton = activity.findViewById<TextView>(R.id.saveDefaultsAsPresetButton)
        val manageButton = activity.findViewById<TextView>(R.id.managePresetsButton)
        assertEquals(activity.getString(R.string.save_as_preset), saveButton.text.toString())
        assertEquals(activity.getString(R.string.manage_presets), manageButton.text.toString())

        saveButton.performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        requireNotNull(dialog.findViewById<android.widget.EditText>(R.id.presetNameInput))
            .setText("My defaults")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        assertTrue(PresetRepository(context).presets().any { it.name == "My defaults" })
    }

    @Test
    fun firstImportShowsThatTheTravelJournalIsBeingCreated() {
        val source = rawTimeline("progress", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val activity = launchActivity()
        activity.findViewById<View>(R.id.navigationSettings).performClick()

        activity.importTimeline(Uri.fromFile(source))

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsTimelineProgressGroup).visibility)
        val firstImportText = activity.findViewById<TextView>(R.id.settingsImportTimelineButton).text.toString()
        assertTrue(
            firstImportText == activity.getString(R.string.journal_import_preparing) ||
                firstImportText == activity.getString(R.string.journal_import_creating),
        )
        assertFalse(firstImportText == activity.getString(R.string.journal_import_in_progress))
        waitUntil {
            activity.findViewById<View>(R.id.settingsTimelineProgressGroup).visibility == View.GONE
        }
        assertEquals(
            activity.getString(R.string.import_updated_timeline),
            activity.findViewById<TextView>(R.id.settingsImportTimelineButton).text.toString(),
        )
        val result = ShadowDialog.getLatestDialog()
        assertEquals(
            activity.getString(R.string.journal_created_title),
            result.findViewById<TextView>(R.id.journalGrowthHeadline)?.text,
        )
    }

    @Test
    fun firstHybridImportReportsBothJournalLayers() {
        val activity = launchActivity()
        val source = Uri.fromFile(repoRoot().resolve("test-fixtures/semantic-and-raw-ranges.json"))

        activity.importTimeline(source)
        waitUntil {
            ShadowDialog.getLatestDialog()?.findViewById<TextView>(R.id.journalGrowthHeadline)?.text ==
                activity.getString(R.string.journal_created_title)
        }

        val result = requireNotNull(ShadowDialog.getLatestDialog())
        val detail = requireNotNull(result.findViewById<TextView>(R.id.journalGrowthDetail)).text.toString()
        assertTrue(detail.contains("Detailed routes"))
        assertTrue(detail.contains("Journey history"))
        assertTrue(detail.contains("points"))
        assertTrue(detail.contains("entries"))
    }

    @Test
    fun recentDetailedImportOffersOptionalRemindersAndShowsStructuredStatus() {
        val source = rawTimeline("recent", 37.5, 127.0, java.time.Instant.now().minusSeconds(60).toString())
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)
        ShadowDialog.getLatestDialog()?.dismiss()
        activity.findViewById<View>(R.id.navigationSettings).performClick()
        waitUntil { activity.findViewById<View>(R.id.journalReminderSwitch).visibility == View.VISIBLE }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalFreshnessStatus).visibility)
        val status = activity.findViewById<TextView>(R.id.timelineDataStatus).text
        assertTrue(status.contains("Detailed routes"))
        assertTrue(status.contains("Journey history"))
        assertTrue("Unexpected Journal status: $status", Regex("\\d+ days?").containsMatchIn(status))
        assertTrue(status.contains("points"))
        assertTrue(status.contains("entries"))
        assertFalse(
            activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                R.id.journalReminderSwitch,
            ).isChecked,
        )
    }

    @Test
    @Config(sdk = [32])
    fun remindersDefaultOnWhenSystemPermissionIsNotRequired() {
        val source = rawTimeline("reminder-default", 37.5, 127.0, java.time.Instant.now().toString())
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitUntil(activity::journalMetadataReady)

        val database = JournalDatabase.open(context)
        val reminderEnabled = runBlocking { JournalRepository(database).primaryJournal()?.reminderEnabled }
        database.close()
        assertEquals(true, reminderEnabled)
    }

    @Test
    fun reminderActionOpensTheJournalCardWithoutStartingAFilePicker() {
        val activity = launchActivity()
        requireNotNull(controller).newIntent(
            Intent(activity, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_JOURNAL),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
        assertTrue(activity.findViewById<View>(R.id.settingsImportTimelineButton).hasFocus())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.loadingGroup).visibility)
    }

    @Test
    fun secondLaunchLoadsOnlyFastJournalMetadata() {
        val database = JournalDatabase.open(context)
        runBlocking {
            JournalRepository(database).createJournal(
                JournalEntity(
                    id = "existing-fast-start",
                    name = "Travel Journal",
                    isPrimary = true,
                    createdAtEpochMillis = 1_000L,
                ),
            )
        }
        database.close()

        val activity = launchActivity()
        waitUntil(activity::journalMetadataReady)

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
        assertFalse(activity.journalRouteReady())
    }

    @Test
    fun openingCreateDoesNotStartRoutePreparation() {
        val database = JournalDatabase.open(context)
        runBlocking {
            JournalRepository(database).createJournal(
                JournalEntity(
                    id = "progress-start",
                    name = "Travel Journal",
                    isPrimary = true,
                    createdAtEpochMillis = 1_000L,
                ),
            )
        }
        database.close()

        val activity = launchActivity()
        waitUntil(activity::journalMetadataReady)
        activity.findViewById<View>(R.id.navigationCreate).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.createTypeStepGroup).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalRoutePreparingGroup).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalRouteProgressBar).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalRouteProgressStageText).visibility)
        assertFalse(activity.journalRouteReady())
        assertTrue(activity.findViewById<View>(R.id.tripVideoChoice).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.recapVideoChoice).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.customRecapChoice).isEnabled)
    }

    @Test
    fun firstImportRecapHidesPreparationIndicatorWhenTheRouteIsReady() {
        val activity = launchActivity()
        activity.importTimeline(Uri.fromFile(twoYearTimeline()))
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        ShadowDialog.getLatestDialog()?.dismiss()
        activity.findViewById<View>(R.id.navigationCreate).performClick()
        activity.findViewById<View>(R.id.recapVideoChoice).performClick()
        val recapDialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        recapDialog.listView.performItemClick(null, 0, 0L)

        val continueButton = activity.findViewById<View>(R.id.wizardContinueButton)
        waitUntil(continueButton::isEnabled)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalRoutePreparingGroup).visibility)

        continueButton.performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.styleStepGroup).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalRoutePreparingGroup).visibility)
    }

    @Test
    fun findTripsIsAvailableFromJournalMetadataBeforeGeometryLoads() {
        val database = JournalDatabase.open(context)
        runBlocking {
            JournalRepository(database).createJournal(
                JournalEntity(
                    id = "trip-metadata",
                    name = "Travel Journal",
                    isPrimary = true,
                    createdAtEpochMillis = 1_000L,
                    semanticStartEpochMillis = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(),
                    semanticEndEpochMillis = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
                ),
            )
        }
        database.close()

        val activity = launchActivity()
        waitUntil(activity::journalMetadataReady)
        activity.findViewById<View>(R.id.navigationCreate).performClick()
        activity.findViewById<View>(R.id.tripVideoChoice).performClick()

        assertFalse(activity.journalRouteReady())
        assertTrue(activity.findViewById<View>(R.id.findTripsButton).isEnabled)

        activity.findViewById<View>(R.id.findTripsButton).performClick()
        val findButton = activity.findViewById<MaterialButton>(R.id.runTripDetectionButton)
        findButton.performClick()
        assertFalse(findButton.isEnabled)
        waitUntil(findButton::isEnabled)
        assertEquals(activity.getString(R.string.recommend_trips), findButton.text.toString())
    }

    @Test
    fun leavingTripDiscoveryRestoresTheFindingTripsControls() {
        val activity = launchActivity()
        val source = Uri.fromFile(repoRoot().resolve("test-fixtures/semantic-and-raw-ranges.json"))
        activity.importTimeline(source)
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        ShadowDialog.getLatestDialog()?.dismiss()
        activity.findViewById<View>(R.id.navigationCreate).performClick()
        activity.findViewById<View>(R.id.tripVideoChoice).performClick()
        activity.findViewById<View>(R.id.findTripsButton).performClick()

        val findButton = activity.findViewById<MaterialButton>(R.id.runTripDetectionButton)
        findButton.performClick()
        assertFalse(findButton.isEnabled)
        assertEquals(activity.getString(R.string.detecting_trips), findButton.text.toString())

        activity.findViewById<View>(R.id.wizardBackButton).performClick()

        assertTrue(findButton.isEnabled)
        assertEquals(activity.getString(R.string.recommend_trips), findButton.text.toString())
    }

    @Test
    fun cachedConfirmedSuggestionEnablesContinueImmediately() {
        val start = LocalDate.of(2026, 1, 1)
        val end = LocalDate.of(2026, 1, 7)
        val activity = launchActivity()
        activity.importTimeline(Uri.fromFile(rawTimelineRange("cached-suggestion", start, end)))
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        activity.prepareJournalRangeForTest(start, end)
        waitUntil { activity.journalRouteCoversForTest(start, end) }

        activity.confirmSuggestionForTest(
            TripSuggestion(
                id = "cached-trip",
                destinationName = "Busan",
                startDate = start.plusDays(1),
                endDate = end.minusDays(1),
                confidence = SuggestionConfidence.STRONG,
                distanceFromHomeKm = 320.0,
            ),
        )

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.projectStepGroup).visibility)
        assertTrue(activity.findViewById<View>(R.id.wizardContinueButton).isEnabled)
    }

    @Test
    fun cachedManualAndSavedTripsEnableContinueImmediately() {
        val start = LocalDate.of(2026, 2, 1)
        val end = LocalDate.of(2026, 2, 7)
        val activity = launchActivity()
        activity.importTimeline(Uri.fromFile(rawTimelineRange("cached-projects", start, end)))
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        activity.prepareJournalRangeForTest(start, end)
        waitUntil { activity.journalRouteCoversForTest(start, end) }

        activity.startManualProjectForTest(TripKind.TRIP)
        assertTrue(activity.findViewById<View>(R.id.wizardContinueButton).isEnabled)

        activity.openProjectForTest(
            TripProject(
                id = "saved-cached-trip",
                title = "Saved trip",
                startDate = start.plusDays(1),
                endDate = end.minusDays(1),
                kind = TripKind.TRIP,
                createdAtMillis = 1L,
                titleMode = ProjectTitleMode.CUSTOM,
            ),
        )
        assertTrue(activity.findViewById<View>(R.id.wizardContinueButton).isEnabled)
    }

    @Test
    fun uncoveredProjectRangeDisablesContinueBeforeLoadingAndCannotAdvance() {
        val start = LocalDate.of(2026, 3, 1)
        val end = LocalDate.of(2026, 3, 7)
        val activity = launchActivity()
        activity.importTimeline(Uri.fromFile(rawTimelineRange("coverage-guard", start, end)))
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        activity.prepareJournalRangeForTest(start, end)
        waitUntil { activity.journalRouteCoversForTest(start, end) }
        activity.startManualProjectForTest(TripKind.TRIP)
        assertTrue(activity.findViewById<View>(R.id.wizardContinueButton).isEnabled)

        activity.selectProjectDatesForTest(start.plusMonths(1), end.plusMonths(1))

        assertFalse(activity.findViewById<View>(R.id.wizardContinueButton).isEnabled)
        activity.continueCreateForTest()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.projectStepGroup).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.styleStepGroup).visibility)
    }

    @Test
    fun returningToCachedRangeCancelsTheSupersededRouteRequest() {
        val cachedStart = LocalDate.of(2026, 4, 1)
        val cachedEnd = LocalDate.of(2026, 4, 7)
        val otherStart = cachedStart.plusMonths(1)
        val otherEnd = cachedEnd.plusMonths(1)
        val activity = launchActivity()
        activity.importTimeline(Uri.fromFile(rawTimelineRange("range-replacement", cachedStart, cachedEnd)))
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        activity.prepareJournalRangeForTest(cachedStart, cachedEnd)
        waitUntil { activity.journalRouteCoversForTest(cachedStart, cachedEnd) }
        activity.startManualProjectForTest(TripKind.TRIP)

        activity.selectProjectDatesForTest(otherStart, otherEnd)
        activity.selectProjectDatesForTest(cachedStart, cachedEnd)
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertTrue(activity.journalRouteCoversForTest(cachedStart, cachedEnd))
        assertFalse(activity.journalRouteCoversForTest(otherStart, otherEnd))
        assertTrue(activity.findViewById<View>(R.id.wizardContinueButton).isEnabled)
    }

    @Test
    fun sparseBoundaryYearDoesNotBlockAValidCachedProject() {
        val recordedStart = LocalDate.of(2027, 1, 1)
        val recordedEnd = LocalDate.of(2027, 1, 2)
        val projectStart = LocalDate.of(2026, 12, 31)
        val activity = launchActivity()
        activity.importTimeline(Uri.fromFile(rawTimelineRange("sparse-boundary", recordedStart, recordedEnd)))
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        activity.prepareJournalRangeForTest(projectStart, recordedEnd)
        waitUntil { activity.journalRouteCoversForTest(projectStart, recordedEnd) }

        activity.confirmSuggestionForTest(
            TripSuggestion(
                id = "cross-year-trip",
                destinationName = "New Year",
                startDate = projectStart,
                endDate = recordedEnd,
                confidence = SuggestionConfidence.POSSIBLE,
                distanceFromHomeKm = 1.0,
            ),
        )

        assertTrue(activity.findViewById<View>(R.id.wizardContinueButton).isEnabled)
    }

    @Test
    fun changingDetectionYearClearsSuggestionsFromThePreviousRange() {
        val activity = launchActivity()
        activity.importTimeline(Uri.fromFile(twoYearTimeline()))
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        activity.findViewById<View>(R.id.navigationCreate).performClick()
        activity.findViewById<View>(R.id.tripVideoChoice).performClick()
        activity.findViewById<View>(R.id.findTripsButton).performClick()
        activity.installTripSuggestionsForTest(
            listOf(
                TripSuggestion(
                    id = "old-range",
                    destinationName = "Old range",
                    startDate = LocalDate.of(2026, 1, 1),
                    endDate = LocalDate.of(2026, 1, 2),
                    confidence = SuggestionConfidence.POSSIBLE,
                    distanceFromHomeKm = 200.0,
                ),
            ),
        )
        assertEquals(1, activity.tripSuggestionCountForTest())

        val dropdown = activity.findViewById<AutoCompleteTextView>(R.id.detectionRangeDropdown)
        dropdown.onItemClickListener?.onItemClick(null, null, 1, 1L)

        assertEquals(0, activity.tripSuggestionCountForTest())
        assertEquals(0, (activity.findViewById<View>(R.id.suggestionsList) as android.view.ViewGroup).childCount)
    }

    @Test
    fun dueJournalAppearsAsAnInAppLibraryCard() {
        val source = rawTimeline("due", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)
        ShadowDialog.getLatestDialog()?.dismiss()
        activity.findViewById<View>(R.id.navigationVideos).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalFreshnessCard).visibility)
        activity.findViewById<View>(R.id.journalFreshnessCard).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
    }

    @Test
    fun recreatedCreateLoadsGeometryOnlyAfterAChoice() {
        val activity = launchActivity()
        val source = Uri.fromFile(repoRoot().resolve("test-fixtures/semantic-and-raw-ranges.json"))

        activity.importTimeline(source)
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        assertEquals(null, TimelineSourceStore(context).load())
        assertEquals(VideoDataSource.JOURNAL, activity.currentVideoDataSource())

        controller = requireNotNull(controller).recreate()
        val recreated = requireNotNull(controller).get()
        waitUntil(recreated::journalMetadataReady)
        recreated.findViewById<View>(R.id.navigationCreate).performClick()

        assertFalse(recreated.journalRouteReady())
        recreated.findViewById<View>(R.id.customRecapChoice).performClick()
        waitUntil(recreated::journalRouteReady)
        assertTrue(recreated.currentJourneyPoints().isNotEmpty())
    }

    @Test
    fun rawOnlyImportIsAcceptedDirectlyAndEnablesAutomaticCreationChoices() {
        val source = rawTimeline("raw-only", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)
        activity.findViewById<View>(R.id.wizardBackButton).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.createTypeStepGroup).visibility)
        assertTrue(activity.findViewById<View>(R.id.tripVideoChoice).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.recapVideoChoice).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.customRecapChoice).isEnabled)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.rawSignalsDescription).visibility)
    }

    @Test
    fun nonOverlappingUpdateIsBlockedAndLeavesExistingRouteUnchanged() {
        val first = rawTimeline("first", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val different = rawTimeline("different", 48.8, 2.3, "2026-08-10T00:00:00Z")
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(first))
        waitForImportedRoute(activity)
        val before = activity.currentJourneyPoints()
        activity.importTimeline(Uri.fromFile(different))
        waitUntil {
            ShadowDialog.getLatestDialog()?.findViewById<TextView>(android.R.id.message)
                ?.text == activity.getString(R.string.journal_import_mismatch_message)
        }

        assertEquals(before, activity.currentJourneyPoints())
    }

    @Test
    fun failedUpdateKeepsPreviouslyLoadedJournalRoute() {
        val valid = rawTimeline("valid", 37.5, 127.0, "2026-08-01T00:00:00Z")
        val malformed = File.createTempFile("malformed", ".json", context.cacheDir).apply {
            writeText("{not-json")
        }
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(valid))
        waitForImportedRoute(activity)
        val before = activity.currentJourneyPoints()
        activity.importTimeline(Uri.fromFile(malformed))
        waitUntil { activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE }

        assertEquals(before, activity.currentJourneyPoints())
    }

    @Test
    fun detailedObservationGapBecomesAConnectedInferredTransfer() {
        val source = File.createTempFile("gapped", ".json", context.cacheDir).apply {
            writeText(
                """
                {"rawSignals":[
                  {"position":{"LatLng":"geo:37.50,127.00","timestamp":"2026-08-01T00:00:00Z","accuracyMeters":10}},
                  {"position":{"LatLng":"geo:37.51,127.01","timestamp":"2026-08-01T00:10:00Z","accuracyMeters":10}},
                  {"position":{"LatLng":"geo:38.50,128.00","timestamp":"2026-08-01T01:00:00Z","accuracyMeters":10}},
                  {"position":{"LatLng":"geo:38.51,128.01","timestamp":"2026-08-01T01:10:00Z","accuracyMeters":10}}
                ]}
                """.trimIndent(),
            )
        }
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)

        assertEquals(emptyList<Int>(), activity.currentJourneyBreakIndices())
        assertTrue(activity.currentJourneyDistanceKm() < 5.0)
    }

    @Test
    fun independentSemanticRecordsRemainConnectedForVideo() {
        val source = File.createTempFile("semantic-gap", ".json", context.cacheDir).apply {
            writeText(
                """
                {"semanticSegments":[
                  {"startTime":"2026-08-01T00:00:00Z","endTime":"2026-08-01T00:10:00Z","activity":{"start":{"latLng":"37.50,127.00"},"end":{"latLng":"37.51,127.01"}}},
                  {"startTime":"2026-08-01T01:00:00Z","endTime":"2026-08-01T01:10:00Z","activity":{"start":{"latLng":"38.50,128.00"},"end":{"latLng":"38.51,128.01"}}}
                ]}
                """.trimIndent(),
            )
        }
        val activity = launchActivity()

        activity.importTimeline(Uri.fromFile(source))
        waitForImportedRoute(activity)

        assertEquals(emptyList<Int>(), activity.currentJourneyBreakIndices())
        assertTrue(activity.currentJourneyDistanceKm() < 5.0)
    }

    private fun rawTimeline(name: String, latitude: Double, longitude: Double, start: String): File {
        val second = java.time.Instant.parse(start).plusSeconds(600)
        return File.createTempFile(name, ".json", context.cacheDir).apply {
            writeText(
                """
                {"rawSignals":[
                  {"position":{"LatLng":"geo:$latitude,$longitude","timestamp":"$start","accuracyMeters":10}},
                  {"position":{"LatLng":"geo:${latitude + 0.01},${longitude + 0.01}","timestamp":"$second","accuracyMeters":10}}
                ]}
                """.trimIndent(),
            )
        }
    }

    private fun rawTimelineRange(name: String, start: LocalDate, end: LocalDate): File {
        val observations = buildList {
            var date = start
            var index = 0
            while (!date.isAfter(end)) {
                val latitude = 37.5 + index * 0.01
                val longitude = 127.0 + index * 0.01
                val first = date.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC)
                val second = first.plusSeconds(600)
                add("{\"position\":{\"LatLng\":\"geo:$latitude,$longitude\",\"timestamp\":\"$first\",\"accuracyMeters\":10}}")
                add("{\"position\":{\"LatLng\":\"geo:${latitude + 0.001},${longitude + 0.001}\",\"timestamp\":\"$second\",\"accuracyMeters\":10}}")
                date = date.plusDays(1)
                index += 1
            }
        }
        return File.createTempFile(name, ".json", context.cacheDir).apply {
            writeText("{\"rawSignals\":[${observations.joinToString(",")}]}" )
        }
    }

    private fun twoYearTimeline(): File = File.createTempFile("two-year", ".json", context.cacheDir).apply {
        writeText(
            """
            {"rawSignals":[
              {"position":{"LatLng":"geo:37.50,127.00","timestamp":"2025-01-01T12:00:00Z","accuracyMeters":10}},
              {"position":{"LatLng":"geo:37.51,127.01","timestamp":"2025-01-01T12:10:00Z","accuracyMeters":10}},
              {"position":{"LatLng":"geo:37.52,127.02","timestamp":"2026-01-01T12:00:00Z","accuracyMeters":10}},
              {"position":{"LatLng":"geo:37.53,127.03","timestamp":"2026-01-01T12:10:00Z","accuracyMeters":10}}
            ]}
            """.trimIndent(),
        )
    }

    private fun launchActivity(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return requireNotNull(controller).get()
    }

    private fun waitForImportedRoute(activity: MainActivity) {
        waitUntil {
            activity.journalMetadataReady() &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
        activity.findViewById<View>(R.id.navigationCreate).performClick()
        activity.findViewById<View>(R.id.customRecapChoice).performClick()
        waitUntil {
            activity.currentJourneyPoints().size >= 2 &&
                activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("The asynchronous Journal operation did not finish")
    }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: error("Working directory unavailable")).absoluteFile
        while (!File(current, "settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Repository root unavailable")
        }
        return current
    }
}
