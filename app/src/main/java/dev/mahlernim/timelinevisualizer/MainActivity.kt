package dev.mahlernim.timelinevisualizer

import android.animation.ValueAnimator
import android.Manifest
import android.app.LocaleManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.OpenableColumns
import android.text.InputType
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.Insets
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.viewpager2.widget.ViewPager2
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.mahlernim.timelinevisualizer.data.CachedTimelineLoader
import dev.mahlernim.timelinevisualizer.data.LocationFilterMode
import dev.mahlernim.timelinevisualizer.data.LocationOutlierFilter
import dev.mahlernim.timelinevisualizer.data.RawSignalPoint
import dev.mahlernim.timelinevisualizer.data.RawSignalProcessingResult
import dev.mahlernim.timelinevisualizer.data.RawSignalProcessor
import dev.mahlernim.timelinevisualizer.data.TimelineParseException
import dev.mahlernim.timelinevisualizer.data.TimelineParseReason
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.data.TimelineSourceMetadata
import dev.mahlernim.timelinevisualizer.databinding.ActivityMainBinding
import dev.mahlernim.timelinevisualizer.databinding.DialogJournalGrowthBinding
import dev.mahlernim.timelinevisualizer.databinding.ItemVideoBinding
import dev.mahlernim.timelinevisualizer.databinding.ItemTripBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenJournalOnboardingBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenNewVideoBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenPlayerBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenSettingsBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenVideosBinding
import dev.mahlernim.timelinevisualizer.databinding.SheetAdvancedVideoSettingsBinding
import dev.mahlernim.timelinevisualizer.export.ExportProgress
import dev.mahlernim.timelinevisualizer.export.ExportEtaEstimator
import dev.mahlernim.timelinevisualizer.export.ExportPhase
import dev.mahlernim.timelinevisualizer.export.VideoExportRequest
import dev.mahlernim.timelinevisualizer.export.VideoExportRequestStore
import dev.mahlernim.timelinevisualizer.export.VideoExportService
import dev.mahlernim.timelinevisualizer.export.VideoExportSnapshot
import dev.mahlernim.timelinevisualizer.export.VideoExportStatus
import dev.mahlernim.timelinevisualizer.export.VideoExportViewModel
import dev.mahlernim.timelinevisualizer.export.EncoderSupport
import dev.mahlernim.timelinevisualizer.export.VideoEncoderSupport
import dev.mahlernim.timelinevisualizer.export.describe
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalEntity
import dev.mahlernim.timelinevisualizer.journal.JournalImportResult
import dev.mahlernim.timelinevisualizer.journal.JournalMatchClassification
import dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStore
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import dev.mahlernim.timelinevisualizer.journal.JournalEntryDestination
import dev.mahlernim.timelinevisualizer.journal.JournalSetupNavigation
import dev.mahlernim.timelinevisualizer.journal.JournalStatusSnapshot
import dev.mahlernim.timelinevisualizer.journal.inclusiveCalendarDayCount
import dev.mahlernim.timelinevisualizer.journal.importer.TimelineJournalImportAdapter
import dev.mahlernim.timelinevisualizer.journal.onboarding.JournalOnboardingAdapter
import dev.mahlernim.timelinevisualizer.journal.onboarding.JournalOnboardingIllustration
import dev.mahlernim.timelinevisualizer.journal.onboarding.JournalOnboardingPages
import dev.mahlernim.timelinevisualizer.journal.reminder.JournalFreshnessPolicy
import dev.mahlernim.timelinevisualizer.journal.reminder.JournalFreshnessState
import dev.mahlernim.timelinevisualizer.journal.reminder.JournalReminderCoordinator
import dev.mahlernim.timelinevisualizer.journal.reminder.JournalReminderNotifications
import dev.mahlernim.timelinevisualizer.journal.reminder.JournalReminderStateStore
import dev.mahlernim.timelinevisualizer.journal.route.JournalRoute
import dev.mahlernim.timelinevisualizer.journal.route.JournalRouteService
import dev.mahlernim.timelinevisualizer.journal.route.JournalRoutePreparationStage
import dev.mahlernim.timelinevisualizer.journal.route.RouteSource
import dev.mahlernim.timelinevisualizer.journal.route.RouteDetail
import dev.mahlernim.timelinevisualizer.journal.route.connectedTimelines
import dev.mahlernim.timelinevisualizer.journal.route.journeyForDateRange
import dev.mahlernim.timelinevisualizer.journal.route.journeyForRange
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.Timeline
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import dev.mahlernim.timelinevisualizer.model.TitleTemplate
import dev.mahlernim.timelinevisualizer.model.VideoDuration
import dev.mahlernim.timelinevisualizer.presets.PresetNameResult
import dev.mahlernim.timelinevisualizer.presets.PresetRepository
import dev.mahlernim.timelinevisualizer.presets.PresetValues
import dev.mahlernim.timelinevisualizer.presets.VideoPreset
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import dev.mahlernim.timelinevisualizer.render.RenderText
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.DistanceUnit
import dev.mahlernim.timelinevisualizer.render.DistanceUnitPreference
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.ExportFormatSettings
import dev.mahlernim.timelinevisualizer.render.ExportResolution
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import dev.mahlernim.timelinevisualizer.render.VideoResolution
import dev.mahlernim.timelinevisualizer.ui.CameraSettingsPreferences
import dev.mahlernim.timelinevisualizer.ui.DistanceUnitPreferences
import dev.mahlernim.timelinevisualizer.ui.AppLanguage
import dev.mahlernim.timelinevisualizer.ui.LocationFilterPreferences
import dev.mahlernim.timelinevisualizer.ui.SelectionArrayAdapter
import dev.mahlernim.timelinevisualizer.ui.SettingsViewModel
import dev.mahlernim.timelinevisualizer.ui.TimelineDisplayPreferences
import dev.mahlernim.timelinevisualizer.videos.GeneratedMediaRepository
import dev.mahlernim.timelinevisualizer.videos.VideoMedia
import dev.mahlernim.timelinevisualizer.videos.VideoRecord
import dev.mahlernim.timelinevisualizer.videos.VideoSettingsSnapshot
import dev.mahlernim.timelinevisualizer.videos.VideoLibraryViewModel
import dev.mahlernim.timelinevisualizer.videos.VideoStore
import dev.mahlernim.timelinevisualizer.videos.VideoDataSource
import dev.mahlernim.timelinevisualizer.trips.SuggestionConfidence
import dev.mahlernim.timelinevisualizer.trips.TripDetector
import dev.mahlernim.timelinevisualizer.trips.TripDetectionRequest
import dev.mahlernim.timelinevisualizer.trips.TripKind
import dev.mahlernim.timelinevisualizer.trips.TripProject
import dev.mahlernim.timelinevisualizer.trips.TripSuggestion
import dev.mahlernim.timelinevisualizer.trips.TripsStore
import dev.mahlernim.timelinevisualizer.trips.OfflineDestinationNameResolver
import dev.mahlernim.timelinevisualizer.trips.TripCoverage
import dev.mahlernim.timelinevisualizer.trips.TripCoverageCalculator
import dev.mahlernim.timelinevisualizer.trips.ProjectTitleMode
import dev.mahlernim.timelinevisualizer.trips.RecapPeriodRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormatSymbols
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.UUID
import java.util.Locale
import java.time.YearMonth
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import androidx.core.util.Pair as AndroidPair
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var home: ScreenVideosBinding
    private lateinit var editor: ScreenNewVideoBinding
    private lateinit var onboarding: ScreenJournalOnboardingBinding
    private lateinit var settingsScreen: ScreenSettingsBinding
    private lateinit var playerScreen: ScreenPlayerBinding
    private var timeline: Timeline? = null
    private var renderTimeline: Timeline? = null
    private var rawSignalPoints: List<RawSignalPoint> = emptyList()
    private var rawSignalProcessing: RawSignalProcessingResult? = null
    private var renderRawSignalsTimeline: Timeline? = null
    private var rawSignalsEnabled = false
    private var rawOnlyImport = false
    private var journey: Journey? = null
    private var selectedIgnoredCount = 0
    private var animation: ValueAnimator? = null
    private var pendingExport: VideoExportRequest? = null
    private var lastVideoUri: Uri? = null
    private var lastVideoTitle: String? = null
    private var pendingOverviewVideoUri: Uri? = null
    private var pendingVideoCopyUri: Uri? = null
    private var importJob: Job? = null
    private var selectedStartYear: Int? = null
    private var selectedEndYear: Int? = null
    private var selectedStartMonth = 1
    private var selectedEndMonth = 12
    private var exactDateRangeEnabled = false
    private var selectedStartDate: LocalDate? = null
    private var selectedEndDate: LocalDate? = null
    private val titleHandler = Handler(Looper.getMainLooper())
    private val monthNames by lazy { DateFormatSymbols.getInstance().months.take(12) }
    private val shortMonthNames by lazy { DateFormatSymbols.getInstance().shortMonths.take(12) }
    private val preferences by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private val videoMedia by lazy { VideoMedia(applicationContext) }
    private val generatedMedia by lazy { GeneratedMediaRepository(applicationContext) }
    private val timelineLoader by lazy { CachedTimelineLoader(applicationContext) }
    private val timelineSourceStore by lazy { TimelineSourceStore(applicationContext) }
    private val journalDatabaseDelegate = lazy { JournalDatabase.open(applicationContext) }
    private val journalDatabase by journalDatabaseDelegate
    private val journalRepository by lazy { JournalRepository(journalDatabase) }
    private val journalRouteService by lazy { JournalRouteService(journalRepository) }
    private val journalImportAdapter by lazy { TimelineJournalImportAdapter() }
    private val journalOnboardingStore by lazy { JournalOnboardingStore(applicationContext) }
    private val presetRepository by lazy { PresetRepository(applicationContext) }
    private val tripsStore by lazy { TripsStore(applicationContext) }
    private val settingsViewModel by viewModels<SettingsViewModel> {
        viewModelFactory {
            initializer {
                SettingsViewModel(
                    CameraSettingsPreferences(applicationContext),
                    DistanceUnitPreferences(applicationContext),
                    LocationFilterPreferences(applicationContext),
                    TimelineDisplayPreferences(applicationContext),
                )
            }
        }
    }
    private val videoExportViewModel by viewModels<VideoExportViewModel> {
        viewModelFactory { initializer { VideoExportViewModel(applicationContext) } }
    }
    private val videoLibraryViewModel by viewModels<VideoLibraryViewModel> {
        viewModelFactory { initializer { VideoLibraryViewModel(VideoStore(applicationContext)) } }
    }
    private val videoEncoderProfiles by lazy { VideoEncoderSupport.deviceProfiles() }
    private var cameraSettings = CameraSettings.DEFAULT
    private var distanceUnitPreference = DistanceUnitPreference.AUTOMATIC
    private var videoFormatSupported = true
    private var locationFilterMode = LocationFilterMode.CONSERVATIVE
    private var simplifyRouteDetail = false
    private var routeDurationSeconds = VideoDuration.DEFAULT_SECONDS
    private val applyTitleChanges = Runnable { commitTitlePreferences() }
    private var videoRenderJob: Job? = null
    private val videoCardJobs = mutableListOf<Job>()
    private var videosExpanded = false
    private var currentScreen = Screen.VIDEOS
    private var rememberedTimelineLoaded = false
    private var interruptedTimelineRecovered = false
    private var lastRenderedExportStatus = VideoExportStatus.IDLE
    private var lastAnnouncedExportPhase: ExportPhase? = null
    private val exportEtaEstimator = ExportEtaEstimator()
    private var videoPlayer: ExoPlayer? = null
    private var playerUri: Uri? = null
    private var playerPositionMs = 0L
    private var playerPlayWhenReady = true
    private var syncingBottomNavigation = false
    private var exportingVideo = false
    private var pendingImportCompletionUri: Uri? = null
    private var activePresetId: String? = null
    private var presetOriginId: String? = null
    private var modifiedBuiltInId: String? = null
    private var presetsConfigured = false
    private var tripSuggestions: List<TripSuggestion> = emptyList()
    private var suggestionsExpanded = false
    private var activeProjectId: String? = null
    private var activeProjectKind = TripKind.TRIP
    private var activeStartDate: LocalDate? = null
    private var activeEndDate: LocalDate? = null
    private var activeSuggestionId: String? = null
    private var currentCreateStep = CreateStep.TYPE
    private var videoTitleUserEdited = false
    private var updatingVideoTitle = false
    private var activeProjectTitleMode = ProjectTitleMode.CUSTOM
    private var updatingProjectTitle = false
    private var endPeriodEnabled = false
    private var editingProjectOnly = false
    private var detectionStartDate: LocalDate? = null
    private var detectionEndDate: LocalDate? = null
    private var selectedDetectionYear: Int? = null
    private var detectionYears: List<Int> = emptyList()
    private var tripDiscoveryRequested = false
    private var tripDetectionRunning = false
    private var tripDetectionRequestId = 0L
    private var tripDetectionJob: Job? = null
    private var settingsReturnToCreate = false
    private var journalSetupMode = false
    private var journalSetupReturnToCreate = false
    private var customizationOriginalCamera: CameraSettings? = null
    private var customizationOriginalPresetId: String? = null
    private var customizationOriginalModifiedBuiltInId: String? = null
    private var rawProjectRangeConflict = false
    private var activeJournal: JournalEntity? = null
    private var activeJournalStatus: JournalStatusSnapshot? = null
    private var activeJournalRoute: JournalRoute? = null
    private var activeJournalRouteStart: Instant? = null
    private var activeJournalRouteEndExclusive: Instant? = null
    private var journalLoaded = false
    private var journalLoadJob: Job? = null
    private var journalRouteLoadJob: Job? = null
    private var journalRouteRequestId = 0L
    private var journalRouteProgressDelayJob: Job? = null
    private var journalRouteProgressExpanded = false
    private var journalRoutePreparationStage = JournalRoutePreparationStage.PREPARING_DETAILED_ROUTES
    private var activeJournalRouteRequest: JournalRouteRequest? = null
    private var journalImportIsInitial: Boolean? = null
    private var updatingJournalReminderSwitch = false
    private var pendingJournalReminderId: String? = null
    private var onboardingPage = 0
    private var onboardingReplay = false

    private val openTimeline = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTimeline(uri)
    }

    private val createVideo = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) startVideoExport(uri, request)
    }

    private val createOverviewImage = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val videoUri = pendingOverviewVideoUri
        pendingOverviewVideoUri = null
        if (uri == null) {
            Snackbar.make(binding.root, R.string.overview_save_cancelled, Snackbar.LENGTH_SHORT).show()
        } else if (videoUri != null) {
            copyOverviewImage(videoUri, uri)
        }
    }

    private val copyCompletedVideo = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val source = pendingVideoCopyUri
        pendingVideoCopyUri = null
        if (uri != null && source != null) copyCompletedVideo(source, uri)
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { openExportDestination() }

    private val requestJournalNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val journalId = pendingJournalReminderId
        pendingJournalReminderId = null
        if (granted && journalId != null) {
            enableJournalReminders(journalId)
        } else if (!granted) {
            Snackbar.make(binding.root, R.string.journal_reminders_permission_denied, Snackbar.LENGTH_LONG).show()
            updateJournalReminderSwitch(enabled = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        home = ScreenVideosBinding.bind(findViewById(R.id.videosScreen))
        editor = ScreenNewVideoBinding.bind(findViewById(R.id.newVideoScreen))
        onboarding = ScreenJournalOnboardingBinding.bind(findViewById(R.id.journalOnboardingScreen))
        settingsScreen = ScreenSettingsBinding.bind(findViewById(R.id.settingsScreen))
        playerScreen = ScreenPlayerBinding.bind(findViewById(R.id.playerScreen))
        val lightSystemBars = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        if (!BuildConfig.IS_JOURNAL_LAB) {
            timelineSourceStore.recoverInterruptedImport()?.let { uri ->
                releaseUriAccess(uri)
                interruptedTimelineRecovered = true
                rememberedTimelineLoaded = true
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, 0)
            binding.bottomNavigation.setPadding(
                binding.bottomNavigation.paddingLeft,
                binding.bottomNavigation.paddingTop,
                binding.bottomNavigation.paddingRight,
                bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (syncingBottomNavigation) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.navigationVideos -> showVideos(acknowledgeCompletion = true)
                R.id.navigationCreate -> openCreateTab()
                R.id.navigationSettings -> showSettings(fromCreate = false)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.navigationVideos) acknowledgeCompletedExport()
        }
        binding.exportTrayCancelButton.setOnClickListener { VideoExportService.cancel(applicationContext) }
        binding.exportTrayRetryButton.setOnClickListener { retryVideoExport() }
        binding.exportTrayWatchButton.setOnClickListener { lastVideoUri?.let(::watchVideo) }
        binding.exportTrayShareButton.setOnClickListener { lastVideoUri?.let(::shareVideo) }
        binding.exportTrayDismissButton.setOnClickListener {
            videoExportViewModel.clear()
        }
        editor.doneButton.setOnClickListener {
            videoExportViewModel.clear()
            editor.videoReadyGroup.visibility = View.GONE
            resetCreateEntry()
            showVideos()
        }
        editor.viewInTripButton.setOnClickListener {
            videoExportViewModel.clear()
            editor.videoReadyGroup.visibility = View.GONE
            showVideos()
        }
        editor.saveAsButton.setOnClickListener { lastVideoUri?.let(::chooseVideoCopyDestination) }
        editor.importButton.setOnClickListener {
            if (BuildConfig.IS_JOURNAL_LAB) showJournalSetup(returnToCreate = true) else requestTimelineImport()
        }
        editor.exportHelpButton.setOnClickListener { showExportHelp() }
        editor.restoreTimelineHelpLink.setOnClickListener { openRestoreGuide() }
        editor.playButton.setOnClickListener { togglePreview() }
        editor.timelineView.setOnClickListener { togglePreview() }
        editor.exportButton.setOnClickListener { chooseExportDestination() }
        editor.shareButton.setOnClickListener { lastVideoUri?.let(::shareVideo) }
        editor.saveOverviewButton.setOnClickListener { lastVideoUri?.let(::chooseOverviewDestination) }
        editor.shareOverviewButton.setOnClickListener { lastVideoUri?.let(::shareOverviewImage) }
        editor.watchVideoButton.setOnClickListener { lastVideoUri?.let(::watchVideo) }
        editor.createAnotherButton.setOnClickListener { prepareAnotherVideo() }
        editor.saveTripButton.setOnClickListener {
            if (saveActiveProject(asNew = false) != null && editingProjectOnly) {
                editingProjectOnly = false
                Snackbar.make(binding.root, R.string.project_changes_saved, Snackbar.LENGTH_SHORT).show()
                showVideos()
            }
        }
        editor.saveAsNewTripButton.setOnClickListener { saveActiveProject(asNew = true) }
        editor.wizardBackButton.setOnClickListener { moveCreateStep(-1) }
        editor.wizardContinueButton.setOnClickListener { moveCreateStep(1) }
        editor.createStepDetails.setOnClickListener { navigateToCreateStage(0) }
        editor.createStepStyle.setOnClickListener { navigateToCreateStage(1) }
        editor.createStepCreate.setOnClickListener { navigateToCreateStage(2) }
        editor.customizeSettingsButton.setOnClickListener { showAdvancedVideoSettingsSheet() }
        editor.useAvailableRawRangeButton.setOnClickListener { useAvailableRawRange() }
        editor.tripVideoChoice.setOnClickListener {
            currentCreateStep = CreateStep.TRIP_SOURCE
            renderCreateStep()
        }
        editor.recapVideoChoice.setOnClickListener { chooseRecapKind() }
        editor.customRecapChoice.setOnClickListener { startManualProject(TripKind.CUSTOM_RECAP) }
        editor.rawDataChoice.setOnClickListener {
            if (renderRawSignalsTimeline != null) startManualProject(TripKind.RAW_DATA)
        }
        editor.addEndPeriodButton.setOnClickListener {
            endPeriodEnabled = !endPeriodEnabled
            if (!endPeriodEnabled) {
                selectedEndYear = selectedStartYear
                selectedEndMonth = selectedStartMonth
            }
            updateProjectPeriodControls()
            onProjectPeriodChanged()
        }
        editor.resetSuggestedTitleButton.setOnClickListener {
            activeProjectTitleMode = ProjectTitleMode.AUTOMATIC
            updateSuggestedProjectTitle(force = true)
        }
        editor.findTripsButton.setOnClickListener { showTripDiscovery() }
        editor.createTripButton.setOnClickListener { startManualProject(TripKind.TRIP) }
        editor.runTripDetectionButton.setOnClickListener { runTripDetection() }
        editor.detectionCustomRangeButton.setOnClickListener { chooseDetectionRange() }
        editor.showAllSuggestionsButton.setOnClickListener {
            suggestionsExpanded = !suggestionsExpanded
            renderTrips()
        }
        home.showAllVideosButton.setOnClickListener {
            videosExpanded = !videosExpanded
            renderVideos()
        }
        home.deleteAllVideosButton.setOnClickListener { confirmDeleteAllLibraryContent() }
        home.journalFreshnessCard.setOnClickListener { openJournalFromReminder() }
        home.journalFreshnessCardAction.setOnClickListener { openJournalFromReminder() }
        home.journalSetupCard.setOnClickListener { showJournalSetup(returnToCreate = false) }
        home.journalSetupCardAction.setOnClickListener { showJournalSetup(returnToCreate = false) }
        home.journalSetupIllustration.illustration = JournalOnboardingIllustration.IMPORT
        settingsScreen.privacyPolicyButton.setOnClickListener { openPrivacyPolicy() }
        settingsScreen.githubProjectButton.setOnClickListener { openWebPage(PROJECT_URL, R.string.web_page_unavailable) }
        settingsScreen.checkUpdatesButton.setOnClickListener { openUpdates() }
        settingsScreen.saveDefaultsAsPresetButton.setOnClickListener { saveVideoDefaultsAsPreset() }
        settingsScreen.managePresetsButton.setOnClickListener { showPresetManager() }
        settingsScreen.cancelCustomizeButton.setOnClickListener { finishVideoCustomization(apply = false) }
        settingsScreen.applyCustomizeButton.setOnClickListener { finishVideoCustomization(apply = true) }
        settingsScreen.settingsImportTimelineButton.setOnClickListener { requestTimelineImport() }
        onboarding.onboardingFileDisclosureButton.setOnClickListener {
            toggleDisclosure(
                onboarding.onboardingFileDisclosureButton,
                onboarding.onboardingFileDisclosureDetail,
            )
        }
        settingsScreen.settingsWhyImportButton.setOnClickListener {
            toggleDisclosure(
                settingsScreen.settingsWhyImportButton,
                settingsScreen.settingsWhyImportDetail,
            )
        }
        settingsScreen.journalReminderSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingJournalReminderSwitch) return@setOnCheckedChangeListener
            val journal = activeJournal ?: return@setOnCheckedChangeListener
            if (checked) requestEnableJournalReminders(journal.id) else disableJournalReminders(journal.id)
        }
        settingsScreen.settingsTimelineHelpButton.setOnClickListener { showExportHelp() }
        settingsScreen.settingsTimelineRestoreButton.setOnClickListener { openRestoreGuide() }
        settingsScreen.settingsJournalHowItWorksButton.setOnClickListener {
            showJournalOnboarding(page = 0, replay = true)
        }
        settingsScreen.versionText.text = installedVersionLabel()
        playerScreen.playerBackButton.setOnClickListener { showVideos(acknowledgeCompletion = true) }
        playerScreen.playerShareButton.setOnClickListener { playerUri?.let(::shareVideo) }
        playerScreen.playerMoreButton.setOnClickListener {
            playerUri?.let { uri ->
                videoLibraryViewModel.records.value.firstOrNull { it.uri == uri.toString() }?.let(::showVideoActions)
            }
        }
        playerScreen.playerExternalButton.setOnClickListener { playerUri?.let(::openExternalVideoPlayer) }
        configureJournalOnboarding()
        onBackPressedDispatcher.addCallback(this) {
            if (currentScreen == Screen.ONBOARDING) {
                when {
                    onboardingPage > 0 -> onboarding.onboardingPager.currentItem = onboardingPage - 1
                    onboardingReplay -> showSettings(fromCreate = false)
                    else -> finish()
                }
            } else if (currentScreen == Screen.NEW_VIDEO) {
                if (currentCreateStep == CreateStep.TYPE) showVideos(acknowledgeCompletion = true) else moveCreateStep(-1)
            } else if (currentScreen == Screen.SETTINGS && settingsReturnToCreate) {
                finishVideoCustomization(apply = false)
            } else if (currentScreen == Screen.SETTINGS && journalSetupMode) {
                journalSetupMode = false
                journalSetupReturnToCreate = false
                showVideos(acknowledgeCompletion = true)
            } else if (currentScreen == Screen.VIDEOS) {
                finish()
            } else {
                showVideos(acknowledgeCompletion = true)
            }
        }
        editor.timelineSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    animation?.cancel()
                    showProgress(progress / 1000f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        editor.ownerInput.setText(preferences.getString("owner_name", null).orEmpty())
        editor.titleInput.setText(
            preferences.getString("title_template", null) ?: getString(R.string.default_title_template),
        )
        editor.ownerInput.doAfterTextChanged {
            if (!videoTitleUserEdited) {
                setAutomaticVideoTitle(editor.projectTitleInput.text?.toString().orEmpty())
            }
            scheduleTitleUpdate()
        }
        editor.titleInput.doAfterTextChanged {
            if (!updatingVideoTitle) videoTitleUserEdited = true
            scheduleTitleUpdate()
        }
        editor.projectTitleInput.doAfterTextChanged { value ->
            if (!updatingProjectTitle) {
                activeProjectTitleMode = ProjectTitleMode.CUSTOM
                editor.resetSuggestedTitleButton.visibility = if (activeProjectKind == TripKind.TRIP) View.GONE else View.VISIBLE
            }
            if (!videoTitleUserEdited) {
                setAutomaticVideoTitle(value?.toString().orEmpty())
            }
        }
        editor.ownerInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTitlePreferences() }
        editor.titleInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTitlePreferences() }

        val durations = VideoDuration.presets.map {
            resources.getQuantityString(R.plurals.duration_seconds, it, it)
        } + getString(R.string.custom_duration)
        editor.durationDropdown.setAdapter(SelectionArrayAdapter(this, durations))
        applyDuration(VideoDuration.DEFAULT_SECONDS)
        configureDistanceUnitSelection()
        editor.timelineView.renderText = currentRenderText()
        editor.durationDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == VideoDuration.presets.size) {
                showCustomDurationDialog()
            } else {
                applyDuration(VideoDuration.presets[position])
            }
        }
        makeDropdownOpenReliably(editor.durationDropdown)
        configureAdvancedSettings()
        configurePresets()
        restoreDraftSettings(savedInstanceState)
        configureLocationFiltering()
        configureTimelineDisplay()
        configureLanguageSelection()
        configureCameraPreparation()
        configureMonthDropdowns()
        configureExactDates()
        configureTripDiscovery()
        activeProjectId = savedInstanceState?.getString(STATE_ACTIVE_PROJECT_ID)
        activeSuggestionId = savedInstanceState?.getString(STATE_ACTIVE_SUGGESTION_ID)
        activeProjectKind = savedInstanceState?.getString(STATE_ACTIVE_PROJECT_KIND)
            ?.let { runCatching { TripKind.valueOf(it) }.getOrNull() }
            ?: TripKind.TRIP
        activeProjectTitleMode = savedInstanceState?.getString(STATE_ACTIVE_TITLE_MODE)
            ?.let { runCatching { ProjectTitleMode.valueOf(it) }.getOrNull() }
            ?: activeProjectId?.let { id -> tripsStore.list().firstOrNull { it.id == id }?.titleMode }
            ?: ProjectTitleMode.CUSTOM
        endPeriodEnabled = savedInstanceState?.getBoolean(STATE_END_PERIOD_ENABLED) ?: false
        editingProjectOnly = savedInstanceState?.getBoolean(STATE_EDITING_PROJECT_ONLY) ?: false
        activeStartDate = savedInstanceState?.getString(STATE_ACTIVE_START_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        activeEndDate = savedInstanceState?.getString(STATE_ACTIVE_END_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        modifiedBuiltInId = savedInstanceState?.getString(STATE_MODIFIED_BUILT_IN_ID)
        presetOriginId = savedInstanceState?.getString(STATE_PRESET_ORIGIN_ID)
        currentCreateStep = savedInstanceState?.getString(STATE_CREATE_STEP)
            ?.let { runCatching { CreateStep.valueOf(it) }.getOrNull() }
            ?: CreateStep.TYPE
        settingsReturnToCreate = savedInstanceState?.getBoolean(STATE_SETTINGS_RETURN_TO_CREATE) ?: false
        journalSetupMode = savedInstanceState?.getBoolean(STATE_JOURNAL_SETUP_MODE) ?: false
        journalSetupReturnToCreate = savedInstanceState?.getBoolean(STATE_JOURNAL_SETUP_RETURN_TO_CREATE) ?: false
        pendingJournalReminderId = savedInstanceState?.getString(STATE_PENDING_JOURNAL_REMINDER_ID)
        onboardingPage = savedInstanceState?.getInt(STATE_JOURNAL_ONBOARDING_PAGE, 0) ?: 0
        onboardingReplay = savedInstanceState?.getBoolean(STATE_JOURNAL_ONBOARDING_REPLAY) ?: false
        customizationOriginalCamera = restoreCustomizationCamera(savedInstanceState)
        customizationOriginalPresetId = savedInstanceState?.getString(STATE_CUSTOMIZATION_PRESET_ID)
        customizationOriginalModifiedBuiltInId = savedInstanceState?.getString(STATE_CUSTOMIZATION_MODIFIED_ID)
        videoTitleUserEdited = savedInstanceState?.getBoolean(STATE_VIDEO_TITLE_EDITED) ?: false
        savedInstanceState?.getInt(STATE_DRAFT_DURATION, VideoDuration.DEFAULT_SECONDS)?.let(::applyDuration)
        renderCreateStep()
        renderVideos()
        lifecycleScope.launch(Dispatchers.IO) { videoMedia.pruneOverviewCache() }
        observeVideoExport()
        VideoExportService.resumeIfNeeded(applicationContext)

        playerUri = savedInstanceState?.getString(STATE_PLAYER_URI)?.toUri()
        playerPositionMs = savedInstanceState?.getLong(STATE_PLAYER_POSITION) ?: 0L
        playerPlayWhenReady = savedInstanceState?.getBoolean(STATE_PLAYER_PLAYING) ?: true
        val incoming = intent?.data
        if (intent?.action == ACTION_OPEN_JOURNAL && BuildConfig.IS_JOURNAL_LAB) {
            openJournalFromReminder()
            intent?.action = null
        } else if (intent?.action == ACTION_WATCH_VIDEO && incoming != null) {
            if (savedInstanceState == null) watchVideo(incoming) else showVideoPlayer(incoming, resetPosition = false)
            VideoExportService.clearNotification(applicationContext)
        } else if (intent?.action == ACTION_SHARE_VIDEO && incoming != null) {
            showVideos()
            if (savedInstanceState == null) shareVideo(incoming)
            VideoExportService.clearNotification(applicationContext)
        } else if (incoming != null) {
            showNewVideo(loadRemembered = false)
            requestTimelineImport(incoming)
            intent?.data = null
            intent?.action = null
        } else when (savedInstanceState?.getString(STATE_SCREEN)) {
            Screen.NEW_VIDEO.name -> showNewVideo(loadRemembered = true)
            Screen.VIDEOS.name -> showVideos()
            Screen.SETTINGS.name -> when {
                journalSetupMode -> showJournalSetup(journalSetupReturnToCreate)
                else -> showSettings(fromCreate = settingsReturnToCreate)
            }
            Screen.PLAYER.name -> playerUri?.let { showVideoPlayer(it, resetPosition = false) } ?: showVideos()
            Screen.ONBOARDING.name -> showJournalOnboarding(onboardingPage, onboardingReplay)
            else -> showDefaultLaunchScreen()
        }
    }

    private fun showDefaultLaunchScreen() {
        if (!BuildConfig.IS_JOURNAL_LAB) {
            showVideos()
            return
        }
        showJournalStartupPlaceholder()
        lifecycleScope.launch {
            val journal = withContext(Dispatchers.IO) { journalRepository.primaryJournal() }
            if (currentScreen != Screen.VIDEOS) return@launch
            when (JournalSetupNavigation.defaultDestination(
                isJournalLab = true,
                hasJournal = journal != null,
                onboardingCompleted = journalOnboardingStore.isCompleted(),
            )) {
                JournalEntryDestination.JOURNAL_ONBOARDING -> showJournalOnboarding(page = 0, replay = false)
                else -> {
                    journal?.let { loadJournalMetadata(it.id) }
                    showVideos()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCREEN, currentScreen.name)
        videoPlayer?.let {
            playerPositionMs = it.currentPosition
            playerPlayWhenReady = it.playWhenReady
        }
        outState.putString(STATE_PLAYER_URI, playerUri?.toString())
        outState.putLong(STATE_PLAYER_POSITION, playerPositionMs)
        outState.putBoolean(STATE_PLAYER_PLAYING, playerPlayWhenReady)
        outState.putString(STATE_DRAFT_CAMERA, cameraSettings.cameraMovement.name)
        outState.putString(STATE_DRAFT_PACING, cameraSettings.longTripCompression.name)
        outState.putString(STATE_DRAFT_QUALITY, cameraSettings.videoQuality.name)
        val exportFormat = cameraSettings.effectiveExportFormat
        outState.putInt(STATE_DRAFT_EXPORT_SHORT_EDGE, exportFormat.shortEdge)
        outState.putInt(STATE_DRAFT_EXPORT_FRAME_RATE, exportFormat.frameRate)
        outState.putBoolean(STATE_DRAFT_CUSTOM_RESOLUTION, exportFormat.customResolution)
        outState.putBoolean(STATE_DRAFT_CUSTOM_FRAME_RATE, exportFormat.customFrameRate)
        outState.putString(STATE_DRAFT_TRIP_DETECTION, cameraSettings.tripDetection.name)
        outState.putString(STATE_DRAFT_LOCAL_FRAMING, cameraSettings.localFraming.name)
        outState.putString(STATE_ACTIVE_PRESET_ID, activePresetId)
        outState.putString(STATE_MODIFIED_BUILT_IN_ID, modifiedBuiltInId)
        outState.putString(STATE_PRESET_ORIGIN_ID, presetOriginId)
        outState.putString(STATE_CREATE_STEP, currentCreateStep.name)
        outState.putBoolean(STATE_VIDEO_TITLE_EDITED, videoTitleUserEdited)
        outState.putInt(STATE_DRAFT_DURATION, routeDurationSeconds)
        outState.putBoolean(STATE_SETTINGS_RETURN_TO_CREATE, settingsReturnToCreate)
        outState.putBoolean(STATE_JOURNAL_SETUP_MODE, journalSetupMode)
        outState.putBoolean(STATE_JOURNAL_SETUP_RETURN_TO_CREATE, journalSetupReturnToCreate)
        outState.putString(STATE_PENDING_JOURNAL_REMINDER_ID, pendingJournalReminderId)
        outState.putInt(STATE_JOURNAL_ONBOARDING_PAGE, onboardingPage)
        outState.putBoolean(STATE_JOURNAL_ONBOARDING_REPLAY, onboardingReplay)
        customizationOriginalCamera?.let { original ->
            outState.putString(STATE_CUSTOMIZATION_CAMERA, original.cameraMovement.name)
            outState.putString(STATE_CUSTOMIZATION_PACING, original.longTripCompression.name)
            outState.putString(STATE_CUSTOMIZATION_QUALITY, original.videoQuality.name)
            outState.putString(STATE_CUSTOMIZATION_TRIP_DETECTION, original.tripDetection.name)
            outState.putString(STATE_CUSTOMIZATION_LOCAL_FRAMING, original.localFraming.name)
        }
        outState.putString(STATE_CUSTOMIZATION_PRESET_ID, customizationOriginalPresetId)
        outState.putString(STATE_CUSTOMIZATION_MODIFIED_ID, customizationOriginalModifiedBuiltInId)
        outState.putString(STATE_ACTIVE_PROJECT_ID, activeProjectId)
        outState.putString(STATE_ACTIVE_SUGGESTION_ID, activeSuggestionId)
        outState.putString(STATE_ACTIVE_PROJECT_KIND, activeProjectKind.name)
        outState.putString(STATE_ACTIVE_TITLE_MODE, activeProjectTitleMode.name)
        outState.putBoolean(STATE_END_PERIOD_ENABLED, endPeriodEnabled)
        outState.putBoolean(STATE_EDITING_PROJECT_ONLY, editingProjectOnly)
        outState.putString(STATE_ACTIVE_START_DATE, activeStartDate?.toString())
        outState.putString(STATE_ACTIVE_END_DATE, activeEndDate?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_JOURNAL && BuildConfig.IS_JOURNAL_LAB) {
            openJournalFromReminder()
            intent.action = null
            return
        }
        intent.data?.let { uri ->
            when (intent.action) {
                ACTION_WATCH_VIDEO -> {
                    watchVideo(uri)
                    VideoExportService.clearNotification(applicationContext)
                }
                ACTION_SHARE_VIDEO -> {
                    showVideos()
                    shareVideo(uri)
                    VideoExportService.clearNotification(applicationContext)
                }
                else -> {
                    showNewVideo(loadRemembered = false)
                    requestTimelineImport(uri)
                    intent.data = null
                    intent.action = null
                }
            }
        }
    }

    private fun openJournalFromReminder() {
        JournalReminderNotifications.cancel(applicationContext)
        showSettings(fromCreate = false)
        loadJournalIfNeeded()
        settingsScreen.settingsImportTimelineButton.post {
            settingsScreen.settingsImportTimelineButton.requestFocus()
            settingsScreen.settingsImportTimelineButton.announceForAccessibility(
                getString(R.string.update_journal),
            )
        }
    }

    private fun showVideos(acknowledgeCompletion: Boolean = false) {
        if (currentCreateStep == CreateStep.DISCOVERY) cancelTripDetection()
        if (acknowledgeCompletion) acknowledgeCompletedExport()
        releaseVideoPlayer()
        currentScreen = Screen.VIDEOS
        home.root.visibility = View.VISIBLE
        editor.root.visibility = View.GONE
        settingsScreen.root.visibility = View.GONE
        playerScreen.root.visibility = View.GONE
        onboarding.root.visibility = View.GONE
        binding.bottomNavigation.visibility = View.VISIBLE
        if (binding.bottomNavigation.selectedItemId != R.id.navigationVideos) {
            syncingBottomNavigation = true
            binding.bottomNavigation.selectedItemId = R.id.navigationVideos
            syncingBottomNavigation = false
        }
        updateHomeJournalCard()
        renderVideos()
        renderTrips()
        if (BuildConfig.IS_JOURNAL_LAB) {
            loadJournalIfNeeded()
            return
        }
        if (!rememberedTimelineLoaded && timeline == null && preferences.getBoolean(MAP_PRIVACY_ACCEPTED, false)) {
            rememberedTimelineLoaded = true
            timelineSourceStore.load()?.let { importTimeline(it, remembered = true) }
        }
    }

    private fun openCreateTab() {
        if (currentScreen == Screen.VIDEOS || currentScreen == Screen.PLAYER) resetCreateEntry()
        val journalAvailable = activeJournal != null || (BuildConfig.IS_JOURNAL_LAB && !journalLoaded)
        when (JournalSetupNavigation.createDestination(BuildConfig.IS_JOURNAL_LAB, journalAvailable || timeline != null)) {
            JournalEntryDestination.JOURNAL_SETUP -> showJournalSetup(returnToCreate = true)
            else -> showNewVideo(loadRemembered = true)
        }
    }

    private fun resetCreateEntry() {
        currentCreateStep = CreateStep.TYPE
        activeProjectId = null
        activeSuggestionId = null
        activeProjectKind = TripKind.TRIP
        activeProjectTitleMode = ProjectTitleMode.CUSTOM
        activeStartDate = null
        activeEndDate = null
        selectedStartDate = null
        selectedEndDate = null
        endPeriodEnabled = false
        editingProjectOnly = false
        videoTitleUserEdited = false
        updatingVideoTitle = true
        editor.titleInput.setText(preferences.getString("title_template", null) ?: getString(R.string.default_title_template))
        updatingVideoTitle = false
        activePresetId = null
        presetOriginId = null
        modifiedBuiltInId = null
        rawProjectRangeConflict = false
        applyDuration(VideoDuration.DEFAULT_SECONDS)
        renderPresetSelection()
    }

    private fun showNewVideo(loadRemembered: Boolean) {
        releaseVideoPlayer()
        currentScreen = Screen.NEW_VIDEO
        home.root.visibility = View.GONE
        editor.root.visibility = View.VISIBLE
        settingsScreen.root.visibility = View.GONE
        playerScreen.root.visibility = View.GONE
        onboarding.root.visibility = View.GONE
        binding.bottomNavigation.visibility = View.VISIBLE
        if (binding.bottomNavigation.selectedItemId != R.id.navigationCreate) {
            syncingBottomNavigation = true
            binding.bottomNavigation.selectedItemId = R.id.navigationCreate
            syncingBottomNavigation = false
        }
        editor.saveTripButton.isEnabled = activeProjectId != null
        renderCreateStep()
        if (BuildConfig.IS_JOURNAL_LAB) {
            loadJournalIfNeeded()
            return
        }
        if (loadRemembered && interruptedTimelineRecovered) {
            interruptedTimelineRecovered = false
            editor.statusText.setText(R.string.timeline_file_unavailable)
            Snackbar.make(binding.root, R.string.choose_timeline_again, Snackbar.LENGTH_LONG).show()
        }
        if (loadRemembered && !rememberedTimelineLoaded && timeline == null &&
            preferences.getBoolean(MAP_PRIVACY_ACCEPTED, false)
        ) {
            rememberedTimelineLoaded = true
            timelineSourceStore.load()?.let { importTimeline(it, remembered = true) }
        }
    }

    private fun showSettings(fromCreate: Boolean = false) {
        if (currentCreateStep == CreateStep.DISCOVERY) cancelTripDetection()
        releaseVideoPlayer()
        journalSetupMode = false
        journalSetupReturnToCreate = false
        settingsReturnToCreate = fromCreate
        currentScreen = Screen.SETTINGS
        home.root.visibility = View.GONE
        editor.root.visibility = View.GONE
        settingsScreen.root.visibility = View.VISIBLE
        playerScreen.root.visibility = View.GONE
        onboarding.root.visibility = View.GONE
        binding.bottomNavigation.visibility = if (fromCreate) View.GONE else View.VISIBLE
        settingsScreen.customizeSettingsActions.visibility = if (fromCreate) View.VISIBLE else View.GONE
        settingsScreen.timelineDataCard.visibility = if (fromCreate) View.GONE else View.VISIBLE
        settingsScreen.settingsJournalHowItWorksButton.visibility =
            if (BuildConfig.IS_JOURNAL_LAB && !fromCreate) View.VISIBLE else View.GONE
        settingsScreen.journalSetupIntro.visibility = View.GONE
        settingsScreen.timelineDataTitle.setText(R.string.timeline_data)
        settingsScreen.settingsTitle.setText(if (fromCreate) R.string.customize_video else R.string.settings)
        settingsScreen.settingsSummary.setText(if (fromCreate) R.string.customize_video_summary else R.string.settings_summary)
        if (!fromCreate && binding.bottomNavigation.selectedItemId != R.id.navigationSettings) {
            syncingBottomNavigation = true
            binding.bottomNavigation.selectedItemId = R.id.navigationSettings
            syncingBottomNavigation = false
        }
        if (!fromCreate) updateTimelineSettingsCard()
    }

    private fun showJournalSetup(returnToCreate: Boolean) {
        releaseVideoPlayer()
        settingsReturnToCreate = false
        journalSetupMode = true
        journalSetupReturnToCreate = returnToCreate
        currentScreen = Screen.SETTINGS
        home.root.visibility = View.GONE
        editor.root.visibility = View.GONE
        settingsScreen.root.visibility = View.VISIBLE
        playerScreen.root.visibility = View.GONE
        onboarding.root.visibility = View.GONE
        binding.bottomNavigation.visibility = View.VISIBLE
        settingsScreen.customizeSettingsActions.visibility = View.GONE
        settingsScreen.timelineDataCard.visibility = View.VISIBLE
        settingsScreen.settingsJournalHowItWorksButton.visibility =
            if (BuildConfig.IS_JOURNAL_LAB) View.VISIBLE else View.GONE
        settingsScreen.journalSetupIntro.visibility = View.VISIBLE
        settingsScreen.timelineDataTitle.setText(R.string.travel_journal)
        settingsScreen.settingsTitle.setText(R.string.journal_setup_title)
        settingsScreen.settingsSummary.setText(R.string.journal_setup_summary)
        if (binding.bottomNavigation.selectedItemId != R.id.navigationSettings) {
            syncingBottomNavigation = true
            binding.bottomNavigation.selectedItemId = R.id.navigationSettings
            syncingBottomNavigation = false
        }
        updateTimelineSettingsCard()
        loadJournalIfNeeded()
    }

    private fun showJournalStartupPlaceholder() {
        currentScreen = Screen.VIDEOS
        home.root.visibility = View.GONE
        editor.root.visibility = View.GONE
        settingsScreen.root.visibility = View.GONE
        playerScreen.root.visibility = View.GONE
        onboarding.root.visibility = View.GONE
        binding.bottomNavigation.visibility = View.GONE
        binding.exportStatusTray.visibility = View.GONE
    }

    private fun configureJournalOnboarding() {
        val pages = JournalOnboardingPages.all
        setDisclosureExpanded(
            onboarding.onboardingFileDisclosureButton,
            onboarding.onboardingFileDisclosureDetail,
            expanded = false,
        )
        setDisclosureExpanded(
            settingsScreen.settingsWhyImportButton,
            settingsScreen.settingsWhyImportDetail,
            expanded = false,
        )
        onboarding.onboardingPager.adapter = JournalOnboardingAdapter(pages)
        onboarding.onboardingPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onboardingPage = position
                renderJournalOnboardingPage(announce = currentScreen == Screen.ONBOARDING)
            }
        })
        onboarding.onboardingNextButton.setOnClickListener {
            onboarding.onboardingPager.currentItem = (onboardingPage + 1).coerceAtMost(pages.lastIndex)
        }
        onboarding.onboardingBackButton.setOnClickListener {
            if (onboardingPage > 0) {
                onboarding.onboardingPager.currentItem = onboardingPage - 1
            } else if (onboardingReplay) {
                showSettings(fromCreate = false)
            }
        }
        onboarding.onboardingSkipButton.setOnClickListener {
            onboarding.onboardingPager.currentItem = pages.lastIndex
        }
        onboarding.onboardingLanguageButton.setOnClickListener { showOnboardingLanguagePicker() }
        onboarding.onboardingChooseFileButton.setOnClickListener {
            journalOnboardingStore.complete()
            showJournalSetup(returnToCreate = false)
            requestTimelineImport()
        }
        onboarding.onboardingSetupMapsButton.setOnClickListener {
            journalOnboardingStore.complete()
            showJournalSetup(returnToCreate = false)
            showExportHelp()
        }
        onboarding.onboardingNotNowButton.setOnClickListener {
            journalOnboardingStore.complete()
            if (onboardingReplay) showSettings(fromCreate = false) else showVideos()
        }
    }

    private fun showJournalOnboarding(page: Int, replay: Boolean) {
        releaseVideoPlayer()
        onboardingReplay = replay
        onboardingPage = page.coerceIn(JournalOnboardingPages.all.indices)
        currentScreen = Screen.ONBOARDING
        home.root.visibility = View.GONE
        editor.root.visibility = View.GONE
        settingsScreen.root.visibility = View.GONE
        playerScreen.root.visibility = View.GONE
        onboarding.root.visibility = View.VISIBLE
        setDisclosureExpanded(
            onboarding.onboardingFileDisclosureButton,
            onboarding.onboardingFileDisclosureDetail,
            expanded = false,
        )
        binding.bottomNavigation.visibility = View.GONE
        binding.exportStatusTray.visibility = View.GONE
        onboarding.onboardingPager.setCurrentItem(onboardingPage, false)
        renderJournalOnboardingPage(announce = false)
    }

    private fun renderJournalOnboardingPage(announce: Boolean) {
        val pages = JournalOnboardingPages.all
        val pageNumber = onboardingPage + 1
        val isFinal = onboardingPage == pages.lastIndex
        listOf(
            onboarding.onboardingDotOne,
            onboarding.onboardingDotTwo,
            onboarding.onboardingDotThree,
        ).forEachIndexed { index, dot ->
            dot.alpha = if (index == onboardingPage) 1f else 0.24f
        }
        onboarding.onboardingBackButton.visibility =
            if (onboardingPage > 0 || onboardingReplay) View.VISIBLE else View.INVISIBLE
        onboarding.onboardingSkipButton.visibility = if (isFinal) View.INVISIBLE else View.VISIBLE
        onboarding.onboardingLanguageButton.visibility = if (onboardingPage == 0) View.VISIBLE else View.GONE
        updateOnboardingLanguageLabel()
        onboarding.onboardingNavigationActions.visibility = if (isFinal) View.GONE else View.VISIBLE
        onboarding.onboardingFinalActions.visibility = if (isFinal) View.VISIBLE else View.GONE
        val announcement = getString(
            R.string.onboarding_page_announcement,
            pageNumber,
            pages.size,
            getString(pages[onboardingPage].titleRes),
        )
        onboarding.onboardingPager.contentDescription = announcement
        if (announce) onboarding.onboardingPager.announceForAccessibility(announcement)
    }

    private fun toggleDisclosure(button: View, detail: View) {
        setDisclosureExpanded(button, detail, detail.visibility != View.VISIBLE)
    }

    private fun setDisclosureExpanded(button: View, detail: View, expanded: Boolean) {
        detail.visibility = if (expanded) View.VISIBLE else View.GONE
        ViewCompat.setStateDescription(
            button,
            getString(if (expanded) R.string.disclosure_expanded else R.string.disclosure_collapsed),
        )
    }

    private fun showVideoCustomization() {
        customizationOriginalCamera = cameraSettings
        customizationOriginalPresetId = activePresetId
        customizationOriginalModifiedBuiltInId = modifiedBuiltInId
        showSettings(fromCreate = true)
    }

    private fun showAdvancedVideoSettingsSheet() {
        val sheet = SheetAdvancedVideoSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        var working = cameraSettings

        val aspectLabels = listOf(R.string.aspect_square, R.string.aspect_portrait, R.string.aspect_landscape).map(::getString)
        val cameraLabels = mapViewLabelResources.map(::getString)
        val detectionLabels = listOf(R.string.trip_detection_conservative, R.string.trip_detection_balanced, R.string.trip_detection_sensitive).map(::getString)
        val framingLabels = listOf(R.string.local_framing_off, R.string.local_framing_balanced, R.string.local_framing_close).map(::getString)
        val pacingLabels = listOf(R.string.compression_off, R.string.compression_balanced, R.string.compression_strong, R.string.compression_stronger).map(::getString)
        val resolutionLabels = listOf(
            R.string.resolution_480,
            R.string.resolution_720,
            R.string.resolution_1080,
            R.string.resolution_1440,
            R.string.resolution_2160,
        ).map(::getString).toMutableList()
        val currentResolutionIndex = ExportResolution.entries.indexOfFirst {
            it.shortEdge == working.effectiveExportFormat.shortEdge && !working.effectiveExportFormat.customResolution
        }
        if (currentResolutionIndex < 0) {
            val format = working.activeVideoFormat
            resolutionLabels += getString(R.string.custom_resolution_selected, format.width, format.height)
        }
        val presetFrameRates = listOf(24, 30, 60)
        val frameRateLabels = presetFrameRates.map { getString(R.string.frame_rate_value, it) }.toMutableList()
        val currentFrameRateIndex = presetFrameRates.indexOf(working.effectiveExportFormat.frameRate)
        if (currentFrameRateIndex < 0 || working.effectiveExportFormat.customFrameRate) {
            frameRateLabels += getString(
                R.string.custom_frame_rate_selected,
                working.effectiveExportFormat.frameRate,
            )
        }

        listOf(
            sheet.aspectRatioDropdown to aspectLabels,
            sheet.cameraMovementDropdown to cameraLabels,
            sheet.tripDetectionDropdown to detectionLabels,
            sheet.localFramingDropdown to framingLabels,
            sheet.longTripDropdown to pacingLabels,
            sheet.videoQualityDropdown to resolutionLabels,
            sheet.frameRateDropdown to frameRateLabels,
        ).forEach { (dropdown, labels) ->
            dropdown.setAdapter(SelectionArrayAdapter(this, labels))
            makeDropdownOpenReliably(dropdown)
        }
        sheet.aspectRatioDropdown.setText(aspectLabels[working.videoQuality.aspectRatioOption.ordinal], false)
        sheet.cameraMovementDropdown.setText(cameraLabels[working.cameraMovement.ordinal], false)
        sheet.tripDetectionDropdown.setText(detectionLabels[working.tripDetection.ordinal], false)
        sheet.localFramingDropdown.setText(framingLabels[working.localFraming.ordinal], false)
        sheet.longTripDropdown.setText(pacingLabels[working.longTripCompression.ordinal], false)
        sheet.videoQualityDropdown.setText(
            resolutionLabels[currentResolutionIndex.takeIf { it >= 0 } ?: resolutionLabels.lastIndex],
            false,
        )
        sheet.frameRateDropdown.setText(
            frameRateLabels[currentFrameRateIndex.takeIf {
                it >= 0 && !working.effectiveExportFormat.customFrameRate
            } ?: frameRateLabels.lastIndex],
            false,
        )

        sheet.aspectRatioDropdown.setOnItemClickListener { _, _, position, _ ->
            working = working.copy(videoQuality = working.videoQuality.withAspectRatio(VideoAspectRatio.entries[position]))
        }
        sheet.cameraMovementDropdown.setOnItemClickListener { _, _, position, _ ->
            working = working.withAutomaticMapView(CameraMovement.entries[position])
        }
        sheet.tripDetectionDropdown.setOnItemClickListener { _, _, position, _ ->
            working = working.copy(tripDetection = TripDetection.entries[position])
        }
        sheet.localFramingDropdown.setOnItemClickListener { _, _, position, _ ->
            working = working.copy(localFraming = LocalFraming.entries[position])
        }
        sheet.longTripDropdown.setOnItemClickListener { _, _, position, _ ->
            working = working.copy(longTripCompression = LongTripCompression.entries[position])
        }
        sheet.videoQualityDropdown.setOnItemClickListener { _, _, position, _ ->
            ExportResolution.entries.getOrNull(position)?.let { resolution ->
                working = working.copy(
                    exportFormat = working.effectiveExportFormat.copy(
                        shortEdge = resolution.shortEdge,
                        customResolution = false,
                    ),
                )
            }
        }
        sheet.frameRateDropdown.setOnItemClickListener { _, _, position, _ ->
            presetFrameRates.getOrNull(position)?.let { frameRate ->
                working = working.copy(
                    exportFormat = working.effectiveExportFormat.copy(
                        frameRate = frameRate,
                        customFrameRate = false,
                    ),
                )
            }
        }
        sheet.cancelButton.setOnClickListener { dialog.dismiss() }
        sheet.applyButton.setOnClickListener {
            applyAdvancedSettings(working)
            syncPresetMatch()
            renderCreateStep()
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    private fun finishVideoCustomization(apply: Boolean) {
        if (!settingsReturnToCreate) return
        if (!apply) {
            customizationOriginalCamera?.let { original ->
                applyAdvancedSettings(original)
            }
            activePresetId = customizationOriginalPresetId
            modifiedBuiltInId = customizationOriginalModifiedBuiltInId
            renderPresetSelection()
        }
        customizationOriginalCamera = null
        customizationOriginalPresetId = null
        customizationOriginalModifiedBuiltInId = null
        settingsReturnToCreate = false
        currentCreateStep = CreateStep.STYLE
        showNewVideo(loadRemembered = true)
    }

    private fun renderCreateStep() {
        if (!::editor.isInitialized) return
        val isType = currentCreateStep == CreateStep.TYPE
        val isTripSource = currentCreateStep == CreateStep.TRIP_SOURCE
        val isDiscovery = currentCreateStep == CreateStep.DISCOVERY
        val isProject = currentCreateStep == CreateStep.PROJECT
        val isStyle = currentCreateStep == CreateStep.STYLE
        val isPreview = currentCreateStep == CreateStep.PREVIEW
        val hasTimeline = timeline != null
        val hasJournalMetadata = BuildConfig.IS_JOURNAL_LAB && activeJournalStatus != null
        editor.createTypeStepGroup.visibility = if (isType && (hasTimeline || hasJournalMetadata)) View.VISIBLE else View.GONE
        editor.journalRoutePreparingGroup.visibility = if (
            hasJournalMetadata &&
            isProject &&
            journalRouteLoadJob?.isActive == true &&
            !selectedJournalRangeReady()
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val showExpandedRouteProgress = editor.journalRoutePreparingGroup.visibility == View.VISIBLE &&
            journalRouteProgressExpanded
        editor.journalRouteCompactProgressGroup.visibility = if (showExpandedRouteProgress) View.GONE else View.VISIBLE
        editor.journalRouteProgressStageText.visibility = if (showExpandedRouteProgress) View.VISIBLE else View.GONE
        editor.journalRouteProgressBar.visibility = if (showExpandedRouteProgress) View.VISIBLE else View.GONE
        if (showExpandedRouteProgress) {
            editor.journalRouteProgressStageText.setText(journalRoutePreparationStage.labelResource())
            editor.journalRoutePreparingGroup.contentDescription = editor.journalRouteProgressStageText.text
        } else {
            editor.journalRoutePreparingGroup.contentDescription = getString(R.string.preparing_your_journeys)
        }
        editor.tripSourceStepGroup.visibility = if (isTripSource) View.VISIBLE else View.GONE
        editor.tripDiscoveryStepGroup.visibility = if (isDiscovery) View.VISIBLE else View.GONE
        editor.projectStepGroup.visibility = if (isProject) View.VISIBLE else View.GONE
        editor.timelineSourceGroup.visibility = if (!BuildConfig.IS_JOURNAL_LAB && isType && !hasTimeline) {
            View.VISIBLE
        } else {
            View.GONE
        }
        editor.periodStepGroup.visibility = if (isProject) View.VISIBLE else View.GONE
        editor.styleStepGroup.visibility = if (isStyle) View.VISIBLE else View.GONE
        editor.videoDetailsGroup.visibility = if (isStyle) View.VISIBLE else View.GONE
        editor.previewStepGroup.visibility = if (isPreview) View.VISIBLE else View.GONE
        editor.createStepText.setText(
            when (currentCreateStep) {
                CreateStep.TYPE -> R.string.create_step_type
                CreateStep.TRIP_SOURCE -> R.string.create_step_trip_source
                CreateStep.DISCOVERY -> R.string.create_step_discovery
                CreateStep.PROJECT -> R.string.create_step_project
                CreateStep.STYLE -> R.string.create_step_style
                CreateStep.PREVIEW -> R.string.create_step_preview
            },
        )
        renderCreateStepper()
        editor.wizardNavigationGroup.visibility = if (isProject || isStyle) View.VISIBLE else View.GONE
        editor.wizardBackButton.visibility = if (isType) View.GONE else View.VISIBLE
        editor.wizardBackButton.isEnabled = true
        editor.wizardContinueButton.visibility = if (isProject || isStyle) View.VISIBLE else View.GONE
        editor.wizardContinueButton.setText(
            if (isProject && editingProjectOnly) R.string.save_trip else R.string.continue_label,
        )
        updateWizardContinueAvailability()
        if (isType && (hasTimeline || hasJournalMetadata)) updateCreateTypeAvailability()
        // The wizard's primary action saves the project. Keeping the older inline save
        // actions here creates two competing paths through the same step.
        editor.saveTripButton.visibility = View.GONE
        editor.saveAsNewTripButton.visibility = View.GONE
        if (isTripSource) renderCreateTripSources()
        if (isDiscovery) {
            editor.runTripDetectionButton.isEnabled = !tripDetectionRunning
            editor.runTripDetectionButton.setText(
                if (tripDetectionRunning) R.string.detecting_trips else R.string.recommend_trips,
            )
            editor.detectionRangeDropdown.isEnabled = !tripDetectionRunning
            editor.detectionCustomRangeButton.isEnabled = !tripDetectionRunning
            renderTripSuggestions()
        }
        if (isProject) updateProjectDateLabel()
        updateRawDataAvailability()
        if (isPreview) editor.previewSettingsSummary.text = currentVideoSettingsSummary()
    }

    private fun updateWizardContinueAvailability() {
        if (!::editor.isInitialized) return
        val isProject = currentCreateStep == CreateStep.PROJECT
        val selectedJournalRangeReady = !BuildConfig.IS_JOURNAL_LAB || !isProject || selectedJournalRangeReady()
        editor.wizardContinueButton.isEnabled = !(isProject && rawProjectRangeConflict) && selectedJournalRangeReady
    }

    private fun selectedJournalRangeReady(): Boolean = currentProjectDates()?.let { (start, end) ->
        val zone = ZoneId.systemDefault()
        journalRouteCovers(
            start.atStartOfDay(zone).toInstant(),
            end.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    } == true

    private fun renderCreateStepper() {
        val stage = currentCreateStage()
        val canAdvanceOneStage = currentCreateStep == CreateStep.PROJECT || currentCreateStep == CreateStep.STYLE
        val labels = listOf(
            getString(R.string.create_step_details_label),
            getString(R.string.create_step_style_label),
            getString(R.string.create_step_create_label),
        )
        val views = listOf(editor.createStepDetails, editor.createStepStyle, editor.createStepCreate)
        views.forEachIndexed { index, view ->
            val status = when {
                index < stage -> R.string.step_status_completed
                index == stage -> R.string.step_status_current
                else -> R.string.step_status_upcoming
            }
            view.text = if (index < stage) "✓ ${labels[index]}" else "${index + 1} ${labels[index]}"
            view.alpha = if (index > stage) 0.55f else 1f
            view.setTypeface(null, if (index == stage) Typeface.BOLD else Typeface.NORMAL)
            view.setTextColor(ContextCompat.getColor(this, if (index <= stage) R.color.interactive else R.color.on_surface_variant))
            view.contentDescription = getString(R.string.step_accessibility, index + 1, views.size, labels[index], getString(status))
            view.isEnabled = index <= stage || (canAdvanceOneStage && index == stage + 1)
        }
    }

    private fun currentCreateStage(): Int = when (currentCreateStep) {
        CreateStep.TYPE, CreateStep.TRIP_SOURCE, CreateStep.DISCOVERY, CreateStep.PROJECT -> 0
        CreateStep.STYLE -> 1
        CreateStep.PREVIEW -> 2
    }

    private fun navigateToCreateStage(targetStage: Int) {
        val currentStage = currentCreateStage()
        when {
            targetStage == currentStage -> Unit
            targetStage == 0 && currentStage > 0 -> {
                currentCreateStep = CreateStep.PROJECT
                renderCreateStep()
            }
            targetStage == 1 && currentStage == 2 -> {
                currentCreateStep = CreateStep.STYLE
                renderCreateStep()
            }
            targetStage == currentStage + 1 &&
                (currentCreateStep == CreateStep.PROJECT || currentCreateStep == CreateStep.STYLE) -> {
                moveCreateStep(1)
            }
        }
    }

    private fun updateCreateTypeAvailability() {
        if (BuildConfig.IS_JOURNAL_LAB && activeJournalStatus != null && timeline == null) {
            listOf(editor.tripVideoChoice, editor.recapVideoChoice, editor.customRecapChoice).forEach { card ->
                card.isEnabled = true
                card.isClickable = true
                card.alpha = 1f
            }
            editor.rawDataChoice.isEnabled = false
            editor.rawDataChoice.isClickable = false
            editor.rawDataChoice.alpha = 0.55f
            editor.semanticUnavailableText.visibility = View.GONE
            return
        }
        val semanticAvailable = semanticDateBounds() != null
        val rawAvailable = rawDateBounds() != null
        listOf(editor.tripVideoChoice, editor.recapVideoChoice, editor.customRecapChoice).forEach { card ->
            card.isEnabled = semanticAvailable
            card.isClickable = semanticAvailable
            card.alpha = if (semanticAvailable) 1f else 0.55f
        }
        editor.rawDataChoice.isEnabled = rawAvailable
        editor.rawDataChoice.isClickable = rawAvailable
        editor.rawDataChoice.alpha = if (rawAvailable) 1f else 0.55f
        editor.rawDataChoiceSummary.setText(
            if (rawAvailable) R.string.raw_data_video_summary else R.string.raw_data_unavailable,
        )
        editor.semanticUnavailableText.visibility = if (semanticAvailable) View.GONE else View.VISIBLE
    }

    private fun moveCreateStep(delta: Int) {
        if (delta < 0) {
            if (currentCreateStep == CreateStep.PROJECT && editingProjectOnly) {
                editingProjectOnly = false
                showVideos()
                return
            }
            if (currentCreateStep == CreateStep.DISCOVERY) cancelTripDetection()
            currentCreateStep = when (currentCreateStep) {
                CreateStep.TYPE -> CreateStep.TYPE
                CreateStep.TRIP_SOURCE -> CreateStep.TYPE
                CreateStep.DISCOVERY -> CreateStep.TRIP_SOURCE
                CreateStep.PROJECT -> if (activeProjectKind == TripKind.TRIP) CreateStep.TRIP_SOURCE else CreateStep.TYPE
                CreateStep.STYLE -> CreateStep.PROJECT
                CreateStep.PREVIEW -> CreateStep.STYLE
            }
            renderCreateStep()
            return
        }
        currentCreateStep = when (currentCreateStep) {
            CreateStep.PROJECT -> {
                if (BuildConfig.IS_JOURNAL_LAB) {
                    val dates = currentProjectDates()
                    val zone = ZoneId.systemDefault()
                    val routeReady = dates?.let { (start, end) ->
                        journalRouteCovers(
                            start.atStartOfDay(zone).toInstant(),
                            end.plusDays(1).atStartOfDay(zone).toInstant(),
                        )
                    } == true
                    if (!routeReady) {
                        dates?.let { (start, end) -> loadJournalRouteForRange(start, end) }
                        Snackbar.make(binding.root, R.string.preparing_your_journeys, Snackbar.LENGTH_SHORT).show()
                        return
                    }
                }
                if (timeline == null) {
                    Snackbar.make(binding.root, R.string.choose_timeline_again, Snackbar.LENGTH_LONG).show()
                    return
                }
                if (saveActiveProject(asNew = activeProjectId == null) == null) return
                if (editingProjectOnly) {
                    editingProjectOnly = false
                    Snackbar.make(binding.root, R.string.project_changes_saved, Snackbar.LENGTH_SHORT).show()
                    showVideos()
                    return
                }
                applyRecommendedPresetIfNeeded()
                CreateStep.STYLE
            }
            CreateStep.STYLE -> CreateStep.PREVIEW
            else -> currentCreateStep
        }
        renderCreateStep()
    }

    private fun renderCreateTripSources() {
        val savedTrips = tripsStore.list().filter { it.kind == TripKind.TRIP }
        editor.findTripsButton.isEnabled = semanticDateBounds() != null && !rawOnlyImport
        editor.emptySavedTripsText.visibility = if (savedTrips.isEmpty()) View.VISIBLE else View.GONE
        editor.savedTripsList.removeAllViews()
        savedTrips.forEach { project ->
            val card = ItemTripBinding.inflate(layoutInflater, editor.savedTripsList, false)
            card.tripBadge.setText(R.string.trip_badge)
            card.tripTitle.text = project.title
            card.tripDetails.text = projectDisplayRange(project)
            showTripCoverage(card, coverageFor(project.startDate, project.endDate))
            card.tripPrimaryButton.setText(R.string.use_trip)
            card.tripPrimaryButton.setOnClickListener { openProject(project) }
            card.root.setOnClickListener { openProject(project) }
            editor.savedTripsList.addView(card.root)
        }
    }

    private fun setAutomaticVideoTitle(value: String) {
        val projectTitle = value.trim()
        val owner = editor.ownerInput.text?.toString()?.trim().orEmpty()
        val automaticTitle = if (activeProjectKind != TripKind.TRIP && owner.isNotEmpty() && projectTitle.isNotEmpty()) {
            getString(R.string.named_video_title, projectTitle).replace("{name}", owner, ignoreCase = true)
        } else {
            projectTitle
        }
        updatingVideoTitle = true
        editor.titleInput.setText(automaticTitle)
        updatingVideoTitle = false
    }

    private fun applyRecommendedPresetIfNeeded() {
        val current = selectedPreset()
        if (current != null) return
        val recommendedId = if (activeProjectKind == TripKind.TRIP) {
            PresetRepository.TRIP_CLOSE_UP_ID
        } else {
            PresetRepository.RECAP_PORTRAIT_ID
        }
        presetRepository.presets().firstOrNull { it.id == recommendedId }?.let(::applyPreset)
    }

    private fun showVideoPlayer(uri: Uri, resetPosition: Boolean = true) {
        currentScreen = Screen.PLAYER
        playerUri = uri
        if (resetPosition) {
            playerPositionMs = 0L
            playerPlayWhenReady = true
        }
        home.root.visibility = View.GONE
        editor.root.visibility = View.GONE
        settingsScreen.root.visibility = View.GONE
        playerScreen.root.visibility = View.VISIBLE
        onboarding.root.visibility = View.GONE
        binding.bottomNavigation.visibility = View.GONE
        playerScreen.playerTitle.text =
            videoLibraryViewModel.records.value.firstOrNull { it.uri == uri.toString() }?.title
            ?: getString(R.string.timeline_video)
        playerScreen.playerErrorGroup.visibility = View.GONE
        initializeVideoPlayer()
    }

    override fun onStart() {
        super.onStart()
        if (currentScreen == Screen.PLAYER) initializeVideoPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsScreen.isInitialized) updateLanguageSelectionLabel()
        if (::settingsScreen.isInitialized && distanceUnitPreference == DistanceUnitPreference.AUTOMATIC) {
            applyDistanceUnitPreference(distanceUnitPreference, save = false)
        }
        if (
            BuildConfig.IS_JOURNAL_LAB &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activeJournal?.takeIf { it.reminderEnabled }?.let { disableJournalReminders(it.id) }
        }
    }

    override fun onStop() {
        releaseVideoPlayer()
        super.onStop()
    }

    private fun initializeVideoPlayer() {
        val uri = playerUri ?: return
        if (videoPlayer != null) return
        playerScreen.playerErrorGroup.visibility = View.GONE
        videoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerScreen.playerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playerScreen.playerErrorGroup.visibility = View.VISIBLE
                }
            })
            player.setMediaItem(MediaItem.fromUri(uri))
            player.seekTo(playerPositionMs)
            player.playWhenReady = playerPlayWhenReady
            player.prepare()
        }
    }

    private fun releaseVideoPlayer() {
        videoPlayer?.let { player ->
            playerPositionMs = player.currentPosition
            playerPlayWhenReady = player.playWhenReady
            playerScreen.playerView.player = null
            player.release()
        }
        videoPlayer = null
    }

    override fun onDestroy() {
        releaseVideoPlayer()
        titleHandler.removeCallbacks(applyTitleChanges)
        importJob?.cancel()
        journalLoadJob?.cancel()
        journalRouteLoadJob?.cancel()
        setTimelineLoading(false)
        animation?.cancel()
        if (journalDatabaseDelegate.isInitialized()) journalDatabase.close()
        super.onDestroy()
    }

    private fun scheduleTitleUpdate() {
        titleHandler.removeCallbacks(applyTitleChanges)
        titleHandler.postDelayed(applyTitleChanges, TITLE_UPDATE_DELAY_MS)
    }

    private fun commitTitlePreferences() {
        titleHandler.removeCallbacks(applyTitleChanges)
        preferences.edit {
            putString("owner_name", editor.ownerInput.text?.toString().orEmpty())
            if (activeProjectKind != TripKind.TRIP || activeStartDate == null) {
                putString("title_template", editor.titleInput.text?.toString().orEmpty())
            }
        }
        updateResolvedTitle()
    }

    private fun updateResolvedTitle() {
        val period = if (rawSignalsEnabled) rawSignalsPeriod() else currentPeriod()
        if (period == null) return
        editor.timelineView.videoTitle = resolvedTitle(period)
    }

    internal fun resolvedTitle(period: TimelinePeriod): String {
        return TitleTemplate.resolve(
            template = editor.titleInput.text?.toString().orEmpty(),
            yearLabel = period.yearLabel,
            name = editor.ownerInput.text?.toString().orEmpty(),
            fallback = getString(R.string.default_title),
        )
    }

    internal fun importTimeline(uri: Uri, remembered: Boolean = false) {
        if (BuildConfig.IS_JOURNAL_LAB) {
            importJournalTimeline(uri)
            return
        }
        if (importJob?.isActive == true) return
        if (!remembered && currentScreen == Screen.VIDEOS) showNewVideo(loadRemembered = false)
        if (!remembered) interruptedTimelineRecovered = false
        timelineSourceStore.beginImport(uri)
        pendingImportCompletionUri = null
        animation?.cancel()
        editor.editorGroup.visibility = View.GONE
        setTimelineLoading(true, R.string.opening_timeline)
        importJob = lifecycleScope.launch {
            try {
                editor.loadingStageText.setText(R.string.reading_timeline)
                val parsed = withContext(Dispatchers.IO) {
                    timelineLoader.load(uri, useCache = remembered)
                }
                if (parsed.timeline == null) {
                    showRawOnlyImportChoice(uri, parsed.rawSignals, remembered)
                    return@launch
                }
                val loaded = parsed.timeline
                editor.loadingStageText.setText(R.string.preparing_trips)
                val prepared = withContext(Dispatchers.Default) {
                    prepareTimeline(loaded)
                }
                timeline = prepared.source
                renderTimeline = prepared.render
                rawSignalPoints = parsed.rawSignals
                rawOnlyImport = false
                rebuildRawSignalsTimeline()
                pendingImportCompletionUri = uri
                configureYears(loaded, prepared.initialJourney, prepared.ignoredCount)
                applyActiveProjectDates()
                tripSuggestions = refreshRequestedTripSuggestions(prepared.source)
                editor.editorGroup.visibility = View.VISIBLE
                updateCameraPreparationUi()
                if (!remembered) rememberTimelineSource(uri)
                updateTimelineSourceMetadata(uri, refreshed = !remembered)
                renderTrips()
                renderCreateStep()
                updateTimelineSettingsCard()
                timelineSourceStore.completeImport(uri)
                pendingImportCompletionUri = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TimelineParseException) {
                Log.e(TAG, "Timeline import failed", error)
                timelineSourceStore.completeImport(uri)
                pendingImportCompletionUri = null
                timeline = null
                renderTimeline = null
                clearRawSignalState()
                if (remembered) {
                    timelineSourceStore.clear()
                    releaseUriAccess(uri)
                    editor.statusText.setText(R.string.timeline_file_unavailable)
                    Snackbar.make(binding.root, R.string.choose_timeline_again, Snackbar.LENGTH_LONG).show()
                } else {
                    editor.statusText.setText(timelineParseMessage(error.reason))
                    Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
                }
                renderCreateStep()
                updateTimelineSettingsCard()
            } catch (error: Throwable) {
                Log.e(TAG, "Timeline import failed", error)
                timelineSourceStore.completeImport(uri)
                pendingImportCompletionUri = null
                timeline = null
                renderTimeline = null
                clearRawSignalState()
                if (remembered) {
                    timelineSourceStore.clear()
                    releaseUriAccess(uri)
                    editor.statusText.setText(R.string.timeline_file_unavailable)
                    Snackbar.make(binding.root, R.string.choose_timeline_again, Snackbar.LENGTH_LONG).show()
                } else {
                    editor.statusText.setText(R.string.import_failed_detail)
                    Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
                }
                renderCreateStep()
                updateTimelineSettingsCard()
            } finally {
                setTimelineLoading(false)
                importJob = null
            }
        }
    }

    private fun importJournalTimeline(uri: Uri) {
        if (importJob?.isActive == true) return
        if (currentScreen == Screen.VIDEOS) showNewVideo(loadRemembered = false)
        animation?.cancel()
        journalImportIsInitial = null
        setTimelineLoading(true, R.string.opening_timeline)
        importJob = lifecycleScope.launch {
            try {
                val sourceSize = timelineFileSize(uri)
                showJournalImportStage(R.string.reading_timeline, bytesRead = 0L, totalBytes = sourceSize)
                val existing = withContext(Dispatchers.IO) { journalRepository.primaryJournal() }
                journalImportIsInitial = existing == null
                updateSettingsImportButton(loading = true)
                val adapted = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                        journalImportAdapter.adapt(
                            input = input,
                            sourceName = timelineDisplayName(uri),
                            importedAtEpochMillis = System.currentTimeMillis(),
                            matchClassification = if (existing == null) {
                                JournalMatchClassification.NEW_JOURNAL
                            } else {
                                JournalMatchClassification.UNCERTAIN
                            },
                            onBytesRead = { bytesRead ->
                                runOnUiThread {
                                    showJournalImportStage(
                                        R.string.reading_timeline,
                                        bytesRead = bytesRead,
                                        totalBytes = sourceSize,
                                    )
                                }
                            },
                        )
                    } ?: error("Timeline document is unavailable")
                }
                val journal = existing ?: JournalEntity(
                    id = UUID.randomUUID().toString(),
                    name = getString(R.string.timeline_data),
                    isPrimary = true,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    reminderEnabled = canPostJournalReminders(),
                )
                val classified = if (existing == null) {
                    adapted
                } else {
                    showJournalImportStage(R.string.journal_import_checking)
                    val committed = withContext(Dispatchers.IO) {
                        journalRepository.committedImport(journal.id, adapted.sourceHash)
                    }
                    if (committed != null) {
                        showJournalDuplicateResult()
                        return@launch
                    } else {
                        val likelySame = withContext(Dispatchers.IO) {
                            journalRepository.hasLikelySameDetailedIdentity(journal.id, adapted.detailedObservations)
                        }
                        if (!likelySame) {
                            showJournalMismatch()
                            return@launch
                        }
                        adapted.copy(matchClassification = JournalMatchClassification.LIKELY_SAME)
                    }
                }
                showJournalImportStage(R.string.journal_import_saving)
                val result = withContext(Dispatchers.IO) {
                    if (existing == null) {
                        journalRepository.createJournalAndImport(journal, classified) { processed, total ->
                            runOnUiThread { showJournalSaveProgress(processed, total) }
                        }
                    } else {
                        journalRepository.import(journal.id, classified) { processed, total ->
                            runOnUiThread { showJournalSaveProgress(processed, total) }
                        }
                    }
                }
                showJournalImportStage(R.string.journal_import_updating)
                refreshJournalAfterImport(journal.id, result)
                showJournalImportResult(result, previousJournal = existing)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Travel Journal import failed", error)
                Snackbar.make(binding.root, R.string.journal_import_failed_preserved, Snackbar.LENGTH_LONG).show()
            } finally {
                setTimelineLoading(false)
                journalImportIsInitial = null
                importJob = null
            }
        }
    }

    private fun showJournalMismatch() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.journal_import_mismatch_title)
            .setMessage(R.string.journal_import_mismatch_message)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun loadJournalIfNeeded() {
        if (journalLoaded || journalLoadJob?.isActive == true) return
        journalLoadJob = lifecycleScope.launch {
            try {
                val journal = withContext(Dispatchers.IO) { journalRepository.primaryJournal() }
                if (journal != null) {
                    if (journalSetupMode && !journalSetupReturnToCreate) {
                        journalSetupMode = false
                        journalSetupReturnToCreate = false
                        showVideos()
                    }
                    loadJournalMetadata(journal.id)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Travel Journal reload failed", error)
            } finally {
                journalLoaded = true
                journalLoadJob = null
                updateTimelineSettingsCard()
                renderCreateStep()
                if (journalSetupMode && timeline != null) {
                    val returnToCreate = journalSetupReturnToCreate
                    journalSetupMode = false
                    journalSetupReturnToCreate = false
                    if (returnToCreate) showNewVideo(loadRemembered = true) else showVideos()
                }
            }
        }
    }

    private suspend fun loadJournalMetadata(journalId: String) {
        val status = withContext(Dispatchers.IO) { journalRepository.status(journalId) } ?: return
        activeJournal = status.journal
        activeJournalStatus = status
        journalLoaded = true
        updateTimelineSettingsCard()
        updateHomeJournalCard()
        if (currentScreen == Screen.NEW_VIDEO && currentCreateStep in setOf(
                CreateStep.PROJECT,
                CreateStep.STYLE,
                CreateStep.PREVIEW,
            )
        ) {
            prepareActiveProjectRoute()
        }
    }

    private fun loadJournalRouteForRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        onComplete: ((JournalRouteLoadOutcome) -> Unit)? = null,
    ) {
        if (!BuildConfig.IS_JOURNAL_LAB) {
            onComplete?.invoke(JournalRouteLoadOutcome.READY)
            return
        }
        val zone = ZoneId.systemDefault()
        val requestedStart = startDate.atStartOfDay(zone).toInstant()
        val requestedEndExclusive = endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        if (journalRouteCovers(requestedStart, requestedEndExclusive)) {
            val inFlight = activeJournalRouteRequest
            if (
                journalRouteLoadJob?.isActive == true &&
                (inFlight?.start != requestedStart || inFlight.endExclusive != requestedEndExclusive)
            ) {
                invalidateJournalRouteRequest()
            }
            updateWizardContinueAvailability()
            onComplete?.let { callback ->
                runCatching { callback(JournalRouteLoadOutcome.READY) }
                    .onFailure { error -> Log.e(TAG, "Journal route completion callback failed", error) }
            }
            return
        }
        val inFlight = activeJournalRouteRequest
        if (
            journalRouteLoadJob?.isActive == true &&
            inFlight?.start == requestedStart &&
            inFlight.endExclusive == requestedEndExclusive
        ) {
            onComplete?.let(inFlight.callbacks::add)
            return
        }
        completeJournalRouteRequest(JournalRouteLoadOutcome.CANCELLED)
        journalRouteLoadJob?.cancel()
        val requestId = ++journalRouteRequestId
        activeJournalRouteRequest = JournalRouteRequest(
            id = requestId,
            start = requestedStart,
            endExclusive = requestedEndExclusive,
            callbacks = onComplete?.let { mutableListOf(it) } ?: mutableListOf(),
        )
        updateWizardContinueAvailability()
        journalRouteProgressExpanded = false
        journalRoutePreparationStage = JournalRoutePreparationStage.PREPARING_DETAILED_ROUTES
        journalRouteProgressDelayJob?.cancel()
        journalRouteProgressDelayJob = lifecycleScope.launch {
            delay(JOURNAL_ROUTE_PROGRESS_DELAY_MILLIS)
            if (journalRouteLoadJob?.isActive == true) {
                journalRouteProgressExpanded = true
                renderCreateStep()
            }
        }
        journalRouteLoadJob = lifecycleScope.launch {
            renderCreateStep()
            try {
                val journal = activeJournal ?: withContext(Dispatchers.IO) { journalRepository.primaryJournal() }
                if (journal == null) {
                    completeJournalRouteRequest(JournalRouteLoadOutcome.FAILED, requestId)
                    showJournalSetup(returnToCreate = true)
                    return@launch
                }
                if (activeJournal == null) loadJournalMetadata(journal.id)
                val loaded = loadJournalRange(
                    journalId = journal.id,
                    start = requestedStart,
                    endExclusive = requestedEndExclusive,
                    requestId = requestId,
                )
                completeJournalRouteRequest(
                    if (loaded) JournalRouteLoadOutcome.READY else JournalRouteLoadOutcome.FAILED,
                    requestId,
                )
                if (!loaded) {
                    Snackbar.make(binding.root, R.string.journal_route_load_failed, Snackbar.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) {
                    completeJournalRouteRequest(JournalRouteLoadOutcome.CANCELLED, requestId)
                    throw error
                }
                completeJournalRouteRequest(JournalRouteLoadOutcome.FAILED, requestId)
                Log.e(TAG, "Travel Journal route load failed", error)
                Snackbar.make(binding.root, R.string.journal_route_load_failed, Snackbar.LENGTH_LONG).show()
            } finally {
                if (requestId != journalRouteRequestId) return@launch
                journalRouteProgressDelayJob?.cancel()
                journalRouteProgressDelayJob = null
                journalRouteProgressExpanded = false
                journalRouteLoadJob = null
                renderCreateStep()
            }
        }
    }

    private fun completeJournalRouteRequest(
        outcome: JournalRouteLoadOutcome,
        requestId: Long? = null,
    ) {
        val request = activeJournalRouteRequest ?: return
        if (requestId != null && request.id != requestId) return
        activeJournalRouteRequest = null
        request.callbacks.toList().forEach { callback ->
            runCatching { callback(outcome) }
                .onFailure { error -> Log.e(TAG, "Journal route completion callback failed", error) }
        }
    }

    private fun journalRouteCovers(start: Instant, endExclusive: Instant): Boolean =
        activeJournalRoute != null &&
            activeJournalRouteStart?.let { !it.isAfter(start) } == true &&
            activeJournalRouteEndExclusive?.let { !it.isBefore(endExclusive) } == true

    private suspend fun loadJournalRange(
        journalId: String,
        start: Instant,
        endExclusive: Instant,
        requestId: Long,
    ): Boolean {
        val loadedJournal = withContext(Dispatchers.IO) { journalRepository.journal(journalId) } ?: return false
        val route = withContext(Dispatchers.IO) {
            journalRouteService.route(
                journalId = journalId,
                start = start,
                endExclusive = endExclusive,
                routeDetail = if (simplifyRouteDetail) RouteDetail.SIMPLIFIED else RouteDetail.DETAILED,
                onPreparationStage = { stage ->
                    withContext(Dispatchers.Main.immediate) {
                        if (requestId == journalRouteRequestId && activeJournalRouteRequest?.id == requestId) {
                            journalRoutePreparationStage = stage
                            if (journalRouteProgressExpanded) renderCreateStep()
                        }
                    }
                },
            )
        }
        if (requestId != journalRouteRequestId) return false
        applyJournalRoute(
            journal = loadedJournal,
            route = route,
            refreshTrips = false,
            routeStart = start,
            routeEndExclusive = endExclusive,
            updateDetailedFreshness = false,
        )
        return true
    }

    private fun JournalRoutePreparationStage.labelResource(): Int = when (this) {
        JournalRoutePreparationStage.PREPARING_DETAILED_ROUTES -> R.string.preparing_detailed_routes
        JournalRoutePreparationStage.COMBINING_JOURNEY_HISTORY -> R.string.combining_journey_history
        JournalRoutePreparationStage.SAVING_FOR_FASTER_STARTS -> R.string.saving_for_faster_starts
    }

    private suspend fun refreshJournalAfterImport(journalId: String, result: JournalImportResult) {
        val committed = result as? JournalImportResult.Committed ?: return
        if (!committed.needsRouteRefresh) {
            val loadedJournal = withContext(Dispatchers.IO) { journalRepository.journal(journalId) } ?: return
            activeJournal = loadedJournal
            activeJournalStatus = withContext(Dispatchers.IO) { journalRepository.status(journalId) }
            journalLoaded = true
            updateTimelineSettingsCard()
            return
        }
        invalidateJournalRouteRequest()
        if (committed.changeKind == JournalImportResult.ChangeKind.INITIAL) {
            activeJournalRoute = null
            activeJournalRouteStart = null
            activeJournalRouteEndExclusive = null
            timeline = null
            renderTimeline = null
            loadJournalMetadata(journalId)
            return
        }
        activeJournalRoute = null
        activeJournalRouteStart = null
        activeJournalRouteEndExclusive = null
        timeline = null
        renderTimeline = null
        loadJournalMetadata(journalId)
        if (currentScreen == Screen.NEW_VIDEO && currentCreateStep != CreateStep.TYPE) {
            currentProjectDates()?.let { (start, end) -> loadJournalRouteForRange(start, end) }
        }
    }

    private fun invalidateJournalRouteRequest() {
        journalRouteRequestId += 1
        completeJournalRouteRequest(JournalRouteLoadOutcome.CANCELLED)
        journalRouteLoadJob?.cancel()
        journalRouteLoadJob = null
        journalRouteProgressDelayJob?.cancel()
        journalRouteProgressDelayJob = null
        journalRouteProgressExpanded = false
    }

    private suspend fun applyJournalRoute(
        journal: JournalEntity,
        route: JournalRoute,
        refreshTrips: Boolean,
        routeStart: Instant? = null,
        routeEndExclusive: Instant? = null,
        updateDetailedFreshness: Boolean = true,
    ) {
        var reminderAdjustedJournal = journal
        if (
            journal.reminderEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            withContext(Dispatchers.IO) { journalRepository.setReminderEnabled(journal.id, enabled = false) }
            JournalReminderCoordinator(applicationContext).cancel(journal.id)
            reminderAdjustedJournal = journal.copy(reminderEnabled = false)
        }
        val usableThrough = if (updateDetailedFreshness) {
            route.spans.asSequence()
                .filter { it.source == RouteSource.DETAILED }
                .flatMap { it.points.asSequence() }
                .maxOfOrNull { it.instant.toEpochMilli() }
        } else {
            reminderAdjustedJournal.detailedUsableThroughEpochMillis
        }
        if (updateDetailedFreshness && reminderAdjustedJournal.detailedUsableThroughEpochMillis != usableThrough) {
            withContext(Dispatchers.IO) {
                journalRepository.setDetailedUsableThrough(reminderAdjustedJournal.id, usableThrough)
            }
        }
        val loadedJournal = reminderAdjustedJournal.copy(detailedUsableThroughEpochMillis = usableThrough)
        activeJournal = loadedJournal
        activeJournalStatus = withContext(Dispatchers.IO) { journalRepository.status(journal.id) }
            ?.copy(journal = loadedJournal)
        activeJournalRoute = route
        activeJournalRouteStart = routeStart
        activeJournalRouteEndExclusive = routeEndExclusive
        journalLoaded = true
        loadedJournal.detailedUsableThroughEpochMillis?.takeIf { loadedJournal.reminderEnabled }?.let { anchor ->
            val stateStore = JournalReminderStateStore(applicationContext)
            val anchorChanged = stateStore.state(loadedJournal.id).anchorEpochMillis != anchor
            if (anchorChanged) {
                JournalReminderNotifications.cancel(applicationContext)
                stateStore.resetForAdvance(loadedJournal.id, anchor)
            }
            JournalReminderCoordinator(applicationContext).schedule(loadedJournal.id, anchor, replace = anchorChanged)
        }
        rawSignalPoints = emptyList()
        rawSignalProcessing = null
        renderRawSignalsTimeline = null
        rawSignalsEnabled = false
        rawOnlyImport = false
        timeline = route.timeline.takeIf { it.points.isNotEmpty() }
        renderTimeline = timeline
        val loaded = timeline
        if (loaded != null) {
            val period = TimelinePeriod.sameYear(loaded.years.first())
            configureYears(
                loaded = loaded,
                initialJourney = route.journeyForRange(period),
                ignoredCount = 0,
                availableYears = semanticDateBounds()?.let { (start, end) ->
                    (start.year..end.year).toList().sortedDescending()
                }.orEmpty(),
            )
            applyActiveProjectDates()
            if (refreshTrips) {
                if (importJob?.isActive == true) showJournalImportStage(R.string.finding_suggested_trips)
                tripSuggestions = refreshRequestedTripSuggestions(loaded)
            }
            editor.editorGroup.visibility = View.VISIBLE
            updateCameraPreparationUi()
        }
        renderTrips()
        renderCreateStep()
        updateTimelineSettingsCard()
    }

    private fun showRawOnlyImportChoice(uri: Uri, points: List<RawSignalPoint>, remembered: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.raw_only_title)
            .setMessage(R.string.raw_only_message)
            .setPositiveButton(R.string.open_google_maps) { _, _ ->
                cancelPendingImport(uri)
                openGoogleMaps()
            }
            .setNegativeButton(R.string.continue_with_raw_data) { _, _ ->
                continueRawOnlyImport(uri, points, remembered)
            }
            .setOnCancelListener { cancelPendingImport(uri) }
            .show()
    }

    private fun continueRawOnlyImport(uri: Uri, points: List<RawSignalPoint>, remembered: Boolean) {
        if (importJob?.isActive == true) return
        setTimelineLoading(true, R.string.preparing_trips)
        importJob = lifecycleScope.launch {
            try {
                rawSignalPoints = points
                rawOnlyImport = true
                rawSignalsEnabled = true
                val result = withContext(Dispatchers.Default) {
                    RawSignalProcessor.process(points)
                }
                rawSignalProcessing = result
                renderRawSignalsTimeline = result.points.takeIf { it.isNotEmpty() }?.let(::Timeline)
                val loaded = result.points.takeIf(List<dev.mahlernim.timelinevisualizer.model.GeoPoint>::isNotEmpty)
                    ?.let(::Timeline)
                    ?: throw TimelineParseException(
                        TimelineParseReason.NO_USABLE_LOCATIONS,
                        "No raw locations passed the default quality filter",
                    )
                timeline = loaded
                renderTimeline = loaded
                tripSuggestions = refreshRequestedTripSuggestions(loaded)
                val period = rawSignalsPeriod() ?: TimelinePeriod.sameYear(loaded.years.first())
                pendingImportCompletionUri = uri
                configureYears(
                    loaded,
                    loaded.forRange(period),
                    result.rejectedCount,
                    startInRawMode = true,
                )
                editor.editorGroup.visibility = View.VISIBLE
                updateCameraPreparationUi()
                if (!remembered) rememberTimelineSource(uri)
                updateTimelineSourceMetadata(uri, refreshed = !remembered)
                renderCreateStep()
                updateTimelineSettingsCard()
                timelineSourceStore.completeImport(uri)
                pendingImportCompletionUri = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Raw Timeline import failed", error)
                cancelPendingImport(uri)
                editor.statusText.setText(R.string.timeline_error_no_locations)
                Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
            } finally {
                setTimelineLoading(false)
                importJob = null
            }
        }
    }

    private fun cancelPendingImport(uri: Uri) {
        timelineSourceStore.completeImport(uri)
        pendingImportCompletionUri = null
        timeline = null
        renderTimeline = null
        clearRawSignalState()
        editor.statusText.setText(R.string.no_timeline)
    }

    private fun clearRawSignalState() {
        rawSignalPoints = emptyList()
        rawSignalProcessing = null
        renderRawSignalsTimeline = null
        rawSignalsEnabled = false
        rawOnlyImport = false
    }

    private fun openGoogleMaps() {
        val launch = packageManager.getLaunchIntentForPackage(GOOGLE_MAPS_PACKAGE)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, GOOGLE_TIMELINE_HELP_URL.toUri())
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Intent.ACTION_VIEW, GOOGLE_TIMELINE_HELP_URL.toUri())) }
    }

    private fun timelineParseMessage(reason: TimelineParseReason): Int = when (reason) {
        TimelineParseReason.MALFORMED_JSON -> R.string.timeline_error_malformed
        TimelineParseReason.EMPTY_EXPORT -> R.string.timeline_error_no_locations
        TimelineParseReason.LEGACY_FORMAT -> R.string.timeline_error_legacy
        TimelineParseReason.RAW_SIGNALS_ONLY -> R.string.timeline_error_raw_only
        TimelineParseReason.NO_USABLE_LOCATIONS -> R.string.timeline_error_no_locations
        TimelineParseReason.UNSUPPORTED_FORMAT -> R.string.import_failed_detail
    }

    private fun setTimelineLoading(loading: Boolean, stage: Int = R.string.opening_timeline) {
        editor.loadingGroup.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) editor.loadingStageText.setText(stage)
        editor.importButton.isEnabled = !loading
        editor.exportHelpButton.isEnabled = !loading
        if (::settingsScreen.isInitialized) {
            settingsScreen.settingsTimelineProgressGroup.visibility = if (loading) View.VISIBLE else View.GONE
            settingsScreen.settingsImportTimelineButton.isEnabled = !loading
            settingsScreen.settingsTimelineHelpButton.isEnabled = !loading
            updateSettingsImportButton(loading)
            if (loading) {
                showJournalImportStage(stage)
            } else {
                settingsScreen.settingsTimelineProgress.isIndeterminate = true
                settingsScreen.settingsTimelineProgress.progress = 0
                settingsScreen.settingsTimelineProgressDetail.setText(R.string.journal_import_working_detail)
            }
        }
    }

    private fun updateSettingsImportButton(loading: Boolean) {
        if (!::settingsScreen.isInitialized) return
        settingsScreen.settingsImportTimelineButton.setText(
            if (!loading) {
                if (BuildConfig.IS_JOURNAL_LAB) {
                    if (activeJournal == null) R.string.import_timeline_to_journal else R.string.import_updated_timeline
                } else {
                    R.string.import_or_update
                }
            } else if (!BuildConfig.IS_JOURNAL_LAB) {
                R.string.journal_import_in_progress
            } else {
                when (journalImportIsInitial) {
                    true -> R.string.journal_import_creating
                    false -> R.string.journal_import_in_progress
                    null -> R.string.journal_import_preparing
                }
            },
        )
    }

    private fun showJournalImportStage(
        stage: Int,
        bytesRead: Long? = null,
        totalBytes: Long? = null,
    ) {
        editor.loadingStageText.setText(stage)
        if (!::settingsScreen.isInitialized) return
        settingsScreen.settingsTimelineProgressStage.setText(stage)
        val determinate = bytesRead != null && totalBytes != null && totalBytes > 0L
        setJournalProgressIndeterminate(!determinate)
        if (determinate) {
            val boundedBytes = bytesRead.coerceIn(0L, totalBytes)
            settingsScreen.settingsTimelineProgress.progress =
                ((boundedBytes * JOURNAL_PROGRESS_MAX) / totalBytes).toInt()
            settingsScreen.settingsTimelineProgressDetail.text = getString(
                R.string.journal_import_read_progress,
                Formatter.formatFileSize(this, boundedBytes),
                Formatter.formatFileSize(this, totalBytes),
            )
        } else {
            settingsScreen.settingsTimelineProgress.progress = 0
            settingsScreen.settingsTimelineProgressDetail.setText(R.string.journal_import_working_detail)
        }
    }

    private fun showJournalSaveProgress(processed: Int, total: Int) {
        if (!::settingsScreen.isInitialized) return
        settingsScreen.settingsTimelineProgressStage.setText(R.string.journal_import_saving)
        if (total > 0) {
            setJournalProgressIndeterminate(false)
            settingsScreen.settingsTimelineProgress.progress =
                ((processed.toLong().coerceIn(0L, total.toLong()) * JOURNAL_PROGRESS_MAX) / total).toInt()
            settingsScreen.settingsTimelineProgressDetail.text = getString(
                R.string.journal_import_save_progress,
                processed.coerceIn(0, total),
                total,
            )
        } else {
            setJournalProgressIndeterminate(true)
            settingsScreen.settingsTimelineProgressDetail.setText(R.string.journal_import_working_detail)
        }
    }

    private fun setJournalProgressIndeterminate(indeterminate: Boolean) {
        val indicator = settingsScreen.settingsTimelineProgress
        if (indicator.isIndeterminate == indeterminate) return
        val wasVisible = indicator.visibility == View.VISIBLE
        if (wasVisible) indicator.visibility = View.INVISIBLE
        indicator.isIndeterminate = indeterminate
        if (wasVisible) indicator.visibility = View.VISIBLE
    }

    private fun rememberTimelineSource(uri: Uri) {
        if (!persistUriAccess(uri, includeWrite = false)) return
        val previous = timelineSourceStore.load()
        if (timelineSourceStore.replace(uri)) {
            if (previous != null && previous != uri) releaseUriAccess(previous)
        } else if (previous != uri) {
            releaseUriAccess(uri)
        }
    }

    private fun updateTimelineSourceMetadata(uri: Uri, refreshed: Boolean) {
        val existing = timelineSourceStore.metadata()
        val semantic = semanticDateBounds()
        val raw = rawDateBounds()
        timelineSourceStore.updateMetadata(
            TimelineSourceMetadata(
                fileName = timelineDisplayName(uri),
                importedAtMillis = if (refreshed || existing == null) System.currentTimeMillis() else existing.importedAtMillis,
                semanticStart = semantic?.first,
                semanticEnd = semantic?.second,
                rawStart = raw?.first,
                rawEnd = raw?.second,
                fileSizeBytes = timelineFileSize(uri),
            ),
        )
    }

    private fun timelineDisplayName(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Timeline.json"
    }

    private fun timelineFileSize(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
            } else null
        }
    }.getOrNull()?.takeIf { it >= 0L }

    private fun showJournalDuplicateResult() {
        showJournalGrowthDialog(
            title = getString(R.string.journal_up_to_date_title),
            detail = getString(R.string.journal_up_to_date_detail),
            initial = false,
            offerReminders = false,
        )
    }

    private suspend fun showJournalImportResult(
        result: JournalImportResult,
        previousJournal: JournalEntity?,
    ) {
        val committed = result as? JournalImportResult.Committed
        if (committed == null) {
            showJournalDuplicateResult()
            return
        }
        var journal = activeJournal
        val initial = committed.changeKind == JournalImportResult.ChangeKind.INITIAL
        val previousDetailedThrough = previousJournal?.detailedCapturedThroughEpochMillis
        val advancedAnchor = journal?.detailedCapturedThroughEpochMillis?.takeIf { anchor ->
            committed.insertedObservationCount > 0 &&
                (previousDetailedThrough == null || anchor > previousDetailedThrough)
        }
        val preservedObservations = resources.getQuantityString(
            R.plurals.journal_preserved_observations_count,
            committed.insertedObservationCount,
            committed.insertedObservationCount,
        )
        val statusDetail = activeJournalStatus?.let(::journalStatusDetail)
            ?: getString(R.string.journal_timeline_growth_detail)
        val (title, detail) = when {
            initial -> getString(R.string.journal_created_title) to statusDetail
            advancedAnchor != null -> {
                getString(R.string.journal_growth_title) to getString(
                    R.string.journal_update_added_detail,
                    preservedObservations,
                    statusDetail,
                )
            }
            committed.insertedObservationCount > 0 -> {
                getString(R.string.journal_backfill_title) to getString(
                    R.string.journal_update_added_detail,
                    preservedObservations,
                    statusDetail,
                )
            }
            committed.semanticSegmentCount > 0 -> {
                getString(R.string.journal_timeline_growth_title) to statusDetail
            }
            else -> getString(R.string.journal_up_to_date_title) to getString(R.string.journal_up_to_date_detail)
        }
        val meaningfulRecentAdvance = JournalFreshnessPolicy.isRecent(advancedAnchor, System.currentTimeMillis())
        if (advancedAnchor != null) {
            JournalReminderNotifications.cancel(applicationContext)
            JournalReminderStateStore(applicationContext).resetForAdvance(journal.id, advancedAnchor)
            if (journal.reminderEnabled) {
                JournalReminderCoordinator(applicationContext).schedule(journal.id, advancedAnchor, replace = true)
            }
        }
        if (meaningfulRecentAdvance && journal != null && !journal.reminderEligible) {
            withContext(Dispatchers.IO) { journalRepository.setReminderEligible(journal.id, eligible = true) }
            journal = journal.copy(reminderEligible = true)
            activeJournal = journal
            activeJournalStatus = activeJournalStatus?.copy(journal = journal)
        }
        showJournalGrowthDialog(
            title = title,
            detail = detail,
            initial = initial,
            offerReminders = meaningfulRecentAdvance && journal?.reminderEnabled != true,
        )
        if (initial) {
            journalSetupMode = false
            journalSetupReturnToCreate = false
            showNewVideo(loadRemembered = true)
        }
    }

    private fun showJournalGrowthDialog(
        title: String,
        detail: String,
        initial: Boolean,
        offerReminders: Boolean,
    ) {
        val content = DialogJournalGrowthBinding.inflate(layoutInflater)
        content.journalGrowthHeadline.text = title
        content.journalGrowthDetail.text = detail
        content.journalGrowthProgress.progress = 0
        val builder = MaterialAlertDialogBuilder(this)
            .setView(content.root)
            .setPositiveButton(
                if (initial) R.string.journal_import_result_create else R.string.journal_import_result_done,
            ) { _, _ -> Unit }
        if (offerReminders) {
            builder.setNeutralButton(R.string.turn_on_reminders) { _, _ ->
                activeJournal?.let { requestEnableJournalReminders(it.id) }
            }
        }
        val dialog = builder.show()
        content.journalGrowthProgress.setProgressCompat(1000, ValueAnimator.areAnimatorsEnabled())
        content.journalGrowthDetail.post {
            content.journalGrowthDetail.announceForAccessibility("$title. $detail")
        }
        dialog.setOnDismissListener { updateTimelineSettingsCard() }
    }

    private fun requestEnableJournalReminders(journalId: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingJournalReminderId = journalId
            requestJournalNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enableJournalReminders(journalId)
        }
    }

    private fun canPostJournalReminders(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun enableJournalReminders(journalId: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { journalRepository.setReminderEnabled(journalId, enabled = true) }
            activeJournal = activeJournal?.takeIf { it.id == journalId }?.copy(reminderEnabled = true) ?: activeJournal
            activeJournalStatus = activeJournalStatus?.let { status ->
                if (status.journal.id == journalId) {
                    status.copy(journal = status.journal.copy(reminderEnabled = true))
                } else {
                    status
                }
            }
            val anchor = activeJournal?.takeIf { it.id == journalId }?.detailedUsableThroughEpochMillis
            if (anchor != null) {
                val store = JournalReminderStateStore(applicationContext)
                if (store.state(journalId).anchorEpochMillis != anchor) store.resetForAdvance(journalId, anchor)
                JournalReminderCoordinator(applicationContext).schedule(journalId, anchor, replace = true)
                JournalReminderNotifications.createChannel(applicationContext)
            }
            updateTimelineSettingsCard()
            Snackbar.make(binding.root, R.string.journal_reminders_enabled, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun disableJournalReminders(journalId: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { journalRepository.setReminderEnabled(journalId, enabled = false) }
            activeJournal = activeJournal?.takeIf { it.id == journalId }?.copy(reminderEnabled = false) ?: activeJournal
            activeJournalStatus = activeJournalStatus?.let { status ->
                if (status.journal.id == journalId) {
                    status.copy(journal = status.journal.copy(reminderEnabled = false))
                } else {
                    status
                }
            }
            JournalReminderStateStore(applicationContext).clear(journalId)
            JournalReminderCoordinator(applicationContext).cancel(journalId)
            updateTimelineSettingsCard()
        }
    }

    private fun updateJournalReminderSwitch(enabled: Boolean) {
        if (!::settingsScreen.isInitialized) return
        updatingJournalReminderSwitch = true
        settingsScreen.journalReminderSwitch.isChecked = enabled
        updatingJournalReminderSwitch = false
    }

    private fun formatJournalDate(epochMillis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

    private fun formatJournalRange(startEpochMillis: Long?, endEpochMillis: Long?): String =
        if (startEpochMillis == null || endEpochMillis == null) {
            getString(R.string.timeline_range_unavailable)
        } else {
            getString(
                R.string.timeline_raw_points_available,
                formatJournalDate(startEpochMillis),
                formatJournalDate(endEpochMillis),
            )
        }

    private fun formatJournalCoverageDays(startEpochMillis: Long?, endEpochMillis: Long?): String {
        val dayCount = inclusiveCalendarDayCount(startEpochMillis, endEpochMillis)
        return resources.getQuantityString(R.plurals.journal_coverage_days, dayCount, dayCount)
    }

    private fun journalStatusDetail(status: JournalStatusSnapshot): String = getString(
        R.string.journal_status_detail,
        formatJournalRange(status.detailedStartEpochMillis, status.detailedEndEpochMillis),
        formatJournalCoverageDays(status.detailedStartEpochMillis, status.detailedEndEpochMillis),
        NumberFormat.getIntegerInstance().format(status.preservedObservationCount),
        formatJournalRange(status.journal.semanticStartEpochMillis, status.journal.semanticEndEpochMillis),
        formatJournalCoverageDays(status.journal.semanticStartEpochMillis, status.journal.semanticEndEpochMillis),
        NumberFormat.getIntegerInstance().format(status.semanticEntryCount),
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(
            Date(status.lastSuccessfulImportAtEpochMillis ?: status.journal.createdAtEpochMillis),
        ),
    )

    private fun updateTimelineSettingsCard() {
        if (!::settingsScreen.isInitialized) return
        if (BuildConfig.IS_JOURNAL_LAB) {
            val journal = activeJournal
            if (journal == null) {
                settingsScreen.timelineDataStatus.text = getString(R.string.timeline_not_imported)
                settingsScreen.settingsTimelineHelpButton.setText(R.string.get_timeline_file)
                updateSettingsImportButton(loading = false)
                settingsScreen.journalFreshnessStatus.visibility = View.GONE
                settingsScreen.journalReminderSwitch.visibility = View.GONE
                settingsScreen.journalReminderSummary.visibility = View.GONE
                settingsScreen.settingsWhyImportButton.visibility = View.GONE
                settingsScreen.settingsWhyImportDetail.visibility = View.GONE
                updateHomeJournalCard()
                return
            }
            val status = activeJournalStatus
            settingsScreen.settingsTimelineHelpButton.setText(R.string.get_updated_timeline_file)
            settingsScreen.settingsWhyImportButton.visibility = View.VISIBLE
            updateSettingsImportButton(loading = false)
            settingsScreen.timelineDataStatus.text = status?.let(::journalStatusDetail)
                ?: getString(R.string.timeline_range_unavailable)
            val freshness = JournalFreshnessPolicy.evaluate(
                journal.detailedUsableThroughEpochMillis,
                System.currentTimeMillis(),
            )
            settingsScreen.journalFreshnessStatus.text = when (freshness.state) {
                JournalFreshnessState.NO_DETAIL -> getString(R.string.journal_status_no_detail)
                JournalFreshnessState.CURRENT -> getString(R.string.journal_status_current, freshness.ageDays)
                JournalFreshnessState.GENTLE -> getString(R.string.journal_status_gentle, freshness.ageDays)
                JournalFreshnessState.UPDATE_DUE -> getString(R.string.journal_status_due)
                JournalFreshnessState.AT_RISK -> getString(R.string.journal_status_at_risk)
                JournalFreshnessState.OVERDUE -> getString(R.string.journal_status_overdue)
            }
            settingsScreen.journalFreshnessStatus.visibility = View.VISIBLE
            val remindersAvailable = journal.reminderEligible && journal.detailedUsableThroughEpochMillis != null
            settingsScreen.journalReminderSwitch.visibility = if (remindersAvailable) View.VISIBLE else View.GONE
            settingsScreen.journalReminderSummary.visibility = if (remindersAvailable) View.VISIBLE else View.GONE
            updateJournalReminderSwitch(journal.reminderEnabled)
            updateHomeJournalCard()
            return
        }
        var metadata = timelineSourceStore.metadata()
        if (metadata?.fileSizeBytes == null) {
            timelineSourceStore.load()?.let(::timelineFileSize)?.let { size ->
                metadata = metadata?.copy(fileSizeBytes = size)?.also(timelineSourceStore::updateMetadata)
            }
        }
        settingsScreen.timelineDataStatus.text = if (metadata == null) {
            getString(R.string.timeline_not_imported)
        } else {
            val imported = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(
                Date(metadata.importedAtMillis),
            )
            val fileLabel = metadata.fileSizeBytes?.let { size ->
                getString(
                    R.string.timeline_file_name_size,
                    metadata.fileName.ifBlank { "Timeline.json" },
                    Formatter.formatShortFileSize(this, size),
                )
            } ?: metadata.fileName.ifBlank { "Timeline.json" }
            getString(
                R.string.timeline_file_status,
                fileLabel,
                imported,
                formatTimelineRange(metadata.semanticStart, metadata.semanticEnd),
                formatTimelineRange(metadata.rawStart, metadata.rawEnd),
            )
        }
    }

    private fun updateHomeJournalCard() {
        if (!::home.isInitialized) return
        val journal = activeJournal
        if (!BuildConfig.IS_JOURNAL_LAB) {
            home.journalFreshnessCard.visibility = View.GONE
            home.journalSetupCard.visibility = View.GONE
            return
        }
        if (journal == null) {
            home.journalFreshnessCard.visibility = View.GONE
            home.journalSetupCard.visibility =
                if (journalOnboardingStore.isCompleted()) View.VISIBLE else View.GONE
            return
        }
        home.journalSetupCard.visibility = View.GONE
        val freshness = JournalFreshnessPolicy.evaluate(
            journal.detailedUsableThroughEpochMillis,
            System.currentTimeMillis(),
        )
        val ageDays = freshness.ageDays
        if (
            ageDays == null ||
            freshness.state !in setOf(
                JournalFreshnessState.UPDATE_DUE,
                JournalFreshnessState.AT_RISK,
                JournalFreshnessState.OVERDUE,
            )
        ) {
            home.journalFreshnessCard.visibility = View.GONE
            return
        }
        home.journalFreshnessCardDetail.text = getString(R.string.journal_freshness_inline, ageDays)
        home.journalFreshnessCard.contentDescription = home.journalFreshnessCardDetail.text
        home.journalFreshnessCard.visibility = View.VISIBLE
    }

    private fun formatTimelineRange(start: LocalDate?, end: LocalDate?): String =
        if (start == null || end == null) {
            getString(R.string.timeline_range_unavailable)
        } else {
            getString(R.string.timeline_raw_points_available, formatExactDate(start), formatExactDate(end))
        }

    private fun formatPointRange(points: List<GeoPoint>): String {
        if (points.isEmpty()) return getString(R.string.timeline_range_unavailable)
        val zone = ZoneId.systemDefault()
        return formatTimelineRange(
            points.minOf { it.instant }.atZone(zone).toLocalDate(),
            points.maxOf { it.instant }.atZone(zone).toLocalDate(),
        )
    }

    private fun prepareTimeline(loaded: Timeline): PreparedTimeline {
        val filtered = LocationOutlierFilter.filter(loaded.points, locationFilterMode)
        val rendered = if (filtered.points === loaded.points) loaded else Timeline(filtered.points)
        val initialPeriod = TimelinePeriod.sameYear(loaded.years.first())
        val initialJourney = rendered.forRange(initialPeriod)
        return PreparedTimeline(
            source = loaded,
            render = rendered,
            initialJourney = initialJourney,
            ignoredCount = (loaded.countForRange(initialPeriod) - initialJourney.points.size).coerceAtLeast(0),
        )
    }

    private fun configureYears(
        loaded: Timeline,
        initialJourney: Journey,
        ignoredCount: Int,
        startInRawMode: Boolean = false,
        availableYears: List<Int> = loaded.years,
    ) {
        val years = availableYears.ifEmpty { loaded.years }
        val labels = years.map { NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }.format(it) }
        editor.startYearDropdown.setAdapter(SelectionArrayAdapter(this, labels))
        editor.endYearDropdown.setAdapter(SelectionArrayAdapter(this, labels))
        makeDropdownOpenReliably(editor.startYearDropdown)
        makeDropdownOpenReliably(editor.endYearDropdown)
        editor.startYearDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedStartYear = years[position]
            normalizeRange(changedStart = true)
        }
        editor.endYearDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedEndYear = years[position]
            normalizeRange(changedStart = false)
        }
        selectedStartYear = years.first()
        selectedEndYear = years.first()
        exactDateRangeEnabled = false
        rawSignalsEnabled = startInRawMode
        rawOnlyImport = startInRawMode
        selectedStartDate = null
        selectedEndDate = null
        editor.exactDateSwitch.isChecked = false
        editor.rawSignalsSwitch.isChecked = startInRawMode
        editor.rawSignalsSwitch.visibility = if (rawSignalPoints.isNotEmpty() && !rawOnlyImport) View.VISIBLE else View.GONE
        updateExactDateControls()
        updateRawSignalsControls()
        updateYearDropdowns()
        applySelectedJourney(initialJourney, ignoredCount)
        updateResolvedTitle()
    }

    private fun selectRange() {
        if (BuildConfig.IS_JOURNAL_LAB) {
            val requestedDates = currentProjectDates()
            if (requestedDates != null) {
                val zone = ZoneId.systemDefault()
                val requestedStart = requestedDates.first.atStartOfDay(zone).toInstant()
                val requestedEnd = requestedDates.second.plusDays(1).atStartOfDay(zone).toInstant()
                if (!journalRouteCovers(requestedStart, requestedEnd)) {
                    loadJournalRouteForRange(requestedDates.first, requestedDates.second) { outcome ->
                        if (outcome == JournalRouteLoadOutcome.READY) selectRange()
                    }
                    return
                }
                // Even a cached selection must supersede an incompatible request that is
                // still preparing a range the user has already left.
                loadJournalRouteForRange(requestedDates.first, requestedDates.second)
            }
            val route = activeJournalRoute ?: return
            val period = currentPeriod() ?: return
            val selected = if (exactDateRangeEnabled) {
                route.journeyForDateRange(
                    start = selectedStartDate ?: return,
                    endInclusive = selectedEndDate ?: return,
                )
            } else {
                route.journeyForRange(period)
            }
            applySelectedJourney(selected, ignoredCount = 0)
            return
        }
        if (rawSignalsEnabled) {
            val period = rawSignalsPeriod() ?: return
            val selected = if (exactDateRangeEnabled && selectedStartDate != null && selectedEndDate != null) {
                renderRawSignalsTimeline?.forDateRange(selectedStartDate!!, selectedEndDate!!)
            } else {
                renderRawSignalsTimeline?.forRange(period)
            } ?: Journey.from(emptyList(), period)
            applySelectedJourney(selected, rawSignalProcessing?.rejectedCount ?: 0)
            return
        }
        val period = currentPeriod() ?: return
        val selected = if (exactDateRangeEnabled) {
            val start = selectedStartDate ?: return
            val end = selectedEndDate ?: return
            renderTimeline?.forDateRange(start, end)
        } else {
            renderTimeline?.forRange(period)
        } ?: return
        val unfilteredCount = if (exactDateRangeEnabled) {
            timeline?.countForDateRange(selectedStartDate ?: return, selectedEndDate ?: return)
        } else {
            timeline?.countForRange(period)
        }
        val ignoredCount = ((unfilteredCount ?: selected.points.size) - selected.points.size).coerceAtLeast(0)
        applySelectedJourney(selected, ignoredCount)
    }

    private fun applySelectedJourney(selected: Journey, ignoredCount: Int) {
        animation?.cancel()
        journey = selected
        selectedIgnoredCount = ignoredCount
        editor.timelineView.journey = selected
        editor.timelineSeek.progress = 0
        showProgress(0f)
        editor.videoReadyGroup.visibility = View.GONE
        editor.periodSummaryText.text = selectedPeriodSummary(selected, ignoredCount)
        updateCameraPreparationUi()
    }

    internal fun selectedPeriodSummary(selected: Journey, ignoredCount: Int = 0): String {
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
        val unit = resolvedDistanceUnit()
        if (selected.points.isEmpty()) return withOutlierSummary(getString(R.string.selected_period_empty), ignoredCount)
        if (selected.points.size == 1) return withOutlierSummary(getString(R.string.selected_period_one_point), ignoredCount)
        if (rawSignalsEnabled) {
            return getString(
                R.string.raw_location_summary,
                number.format(selected.points.size),
                number.format(unit.fromKilometers(selected.knownDistanceKm)),
                unit.symbol,
                number.format(rawSignalProcessing?.rejectedCount ?: ignoredCount),
            )
        }
        if (selected.knownDistanceKm <= 0) {
            return withOutlierSummary(
                getString(R.string.selected_period_no_movement, number.format(selected.points.size)),
                ignoredCount,
            )
        }
        val range = selected.period
        val period = if (exactDateRangeEnabled && selectedStartDate != null && selectedEndDate != null) {
            getString(
                R.string.exact_date_range,
                formatExactDate(selectedStartDate!!),
                formatExactDate(selectedEndDate!!),
            )
        } else if (
            range.startYear == range.endYear &&
            range.startMonth == 1 &&
            range.endMonth == 12
        ) {
            NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }.format(range.startYear)
        } else if (range.startYear == range.endYear) {
            getString(
                R.string.period_same_year,
                monthNames[range.startMonth - 1],
                monthNames[range.endMonth - 1],
                range.startYear,
            )
        } else {
            getString(
                R.string.period_cross_year,
                monthNames[range.startMonth - 1],
                range.startYear,
                monthNames[range.endMonth - 1],
                range.endYear,
            )
        }
        return withOutlierSummary(
            getString(
                R.string.selected_period_summary,
                number.format(selected.points.size),
                number.format(unit.fromKilometers(selected.knownDistanceKm)),
                unit.symbol,
                period,
            ),
            ignoredCount,
        )
    }

    private fun withOutlierSummary(summary: String, ignoredCount: Int): String {
        if (ignoredCount <= 0) return summary
        return summary + "\n" + resources.getQuantityString(
            R.plurals.location_outliers_ignored,
            ignoredCount,
            ignoredCount,
        )
    }

    internal fun canCreateVideo(selected: Journey): Boolean =
        selected.points.size >= 2 && selected.totalDistanceKm > 0

    private fun configureMonthDropdowns() {
        editor.startMonthDropdown.setAdapter(SelectionArrayAdapter(this, monthNames))
        editor.endMonthDropdown.setAdapter(SelectionArrayAdapter(this, monthNames))
        editor.startMonthDropdown.setText(monthNames.first(), false)
        editor.endMonthDropdown.setText(monthNames.last(), false)
        makeDropdownOpenReliably(editor.startMonthDropdown)
        makeDropdownOpenReliably(editor.endMonthDropdown)
        editor.startMonthDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedStartMonth = position + 1
            normalizeRange(changedStart = true)
        }
        editor.endMonthDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedEndMonth = position + 1
            normalizeRange(changedStart = false)
        }
    }

    private fun configureExactDates() {
        editor.exactDateSwitch.setOnCheckedChangeListener { _, checked ->
            exactDateRangeEnabled = checked
            if (checked && (selectedStartDate == null || selectedEndDate == null)) {
                resetExactDatesToSelectedMonths()
            }
            updateExactDateControls()
            updateResolvedTitle()
            selectRange()
            if (currentCreateStep == CreateStep.PROJECT) updateProjectDateLabel()
        }
        editor.exactDateRangeButton.setOnClickListener { showExactDatePicker() }
        editor.rawSignalsSwitch.setOnCheckedChangeListener { _, checked ->
            rawSignalsEnabled = (checked || rawOnlyImport) && rawSignalPoints.isNotEmpty()
            rebuildRawSignalsTimeline()
            updateRawSignalsControls()
            selectRange()
            updateResolvedTitle()
        }
        updateExactDateControls()
        updateRawSignalsControls()
    }

    private fun updateRawSignalsControls() {
        editor.periodDateControlsGroup.visibility = if (rawSignalsEnabled) View.GONE else View.VISIBLE
        editor.exactDateSwitch.visibility = if (rawSignalsEnabled) View.GONE else View.VISIBLE
        editor.exactDateRangeButton.visibility = if (!rawSignalsEnabled && exactDateRangeEnabled) View.VISIBLE else View.GONE
        editor.rawSignalsDescription.visibility = if (rawSignalsEnabled) View.VISIBLE else View.GONE
    }

    private fun rebuildRawSignalsTimeline(): RawSignalProcessingResult? {
        val result = RawSignalProcessor.process(
            rawSignalPoints,
            RawSignalProcessor.DEFAULT_MAXIMUM_ACCURACY_METERS,
        )
        rawSignalProcessing = result
        renderRawSignalsTimeline = result.points.takeIf { it.isNotEmpty() }?.let(::Timeline)
        if (activeProjectKind == TripKind.RAW_DATA) {
            rawDateBounds()?.let { (first, last) ->
                val requestedStart = selectedStartDate ?: first
                val requestedEnd = selectedEndDate ?: last
                rawProjectRangeConflict = activeProjectId != null &&
                    (requestedStart.isBefore(first) || requestedEnd.isAfter(last) || requestedEnd.isBefore(requestedStart))
                if (!rawProjectRangeConflict) {
                    selectedStartDate = requestedStart.coerceIn(first, last)
                    selectedEndDate = requestedEnd.coerceIn(selectedStartDate!!, last)
                    activeStartDate = selectedStartDate
                    activeEndDate = selectedEndDate
                }
                updateExactDateControls()
                updateSuggestedProjectTitle()
                updateRawDataAvailability()
            }
        }
        return result
    }

    private fun rawSignalsPeriod(): TimelinePeriod? {
        val points = renderRawSignalsTimeline?.points ?: return null
        if (points.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        return TimelinePeriod(
            YearMonth.from(points.first().instant.atZone(zone)),
            YearMonth.from(points.last().instant.atZone(zone)),
        )
    }

    private fun showExactDatePicker() {
        val start = selectedStartDate ?: currentPeriod()?.start?.atDay(1) ?: return
        val end = selectedEndDate ?: currentPeriod()?.endInclusive?.atEndOfMonth() ?: return
        val bounds = activeDateBounds()
        val initialStart = start.coerceIn(bounds.first, bounds.second)
        val initialEnd = end.coerceIn(initialStart, bounds.second)
        val constraints = CalendarConstraints.Builder()
            .setStart(datePickerMillis(bounds.first))
            .setEnd(datePickerMillis(bounds.second))
            .setOpenAt(datePickerMillis(initialStart))
            .setValidator(
                CompositeDateValidator.allOf(
                    listOf(
                        DateValidatorPointForward.from(datePickerMillis(bounds.first)),
                        DateValidatorPointBackward.before(datePickerMillis(bounds.second) + DAY_MILLIS),
                    ),
                ),
            )
            .build()
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.choose_exact_dates)
            .setCalendarConstraints(constraints)
            .setSelection(AndroidPair(datePickerMillis(initialStart), datePickerMillis(initialEnd)))
            .build()
        picker.addOnPositiveButtonClickListener { range ->
            val pickedStart = Instant.ofEpochMilli(range.first).atZone(ZoneOffset.UTC).toLocalDate()
            val pickedEnd = Instant.ofEpochMilli(range.second).atZone(ZoneOffset.UTC).toLocalDate()
            if (!applyExactDateRange(pickedStart, pickedEnd)) {
                Snackbar.make(binding.root, R.string.raw_data_range_conflict, Snackbar.LENGTH_LONG).show()
            }
        }
        picker.show(supportFragmentManager, "exact-date-range")
    }

    private fun resetExactDatesToSelectedMonths() {
        val period = currentPeriod() ?: return
        selectedStartDate = period.start.atDay(1)
        selectedEndDate = period.endInclusive.atEndOfMonth()
    }

    private fun updateExactDateControls() {
        editor.exactDateRangeButton.visibility = if (exactDateRangeEnabled) View.VISIBLE else View.GONE
        val start = selectedStartDate
        val end = selectedEndDate
        editor.exactDateRangeButton.text = if (start != null && end != null) {
            getString(R.string.exact_date_range, formatExactDate(start), formatExactDate(end))
        } else {
            getString(R.string.choose_exact_dates)
        }
    }

    private fun updateRawDataAvailability() {
        if (!::editor.isInitialized) return
        val visible = !BuildConfig.IS_JOURNAL_LAB &&
            currentCreateStep == CreateStep.PROJECT && activeProjectKind == TripKind.RAW_DATA
        editor.rawDataAvailabilityGroup.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        editor.rawDataAvailabilityText.text = rawDataAvailability(renderRawSignalsTimeline?.points.orEmpty())
        editor.rawDataRangeConflictGroup.visibility = if (rawProjectRangeConflict) View.VISIBLE else View.GONE
        editor.wizardContinueButton.isEnabled = !rawProjectRangeConflict
    }

    internal fun rawDataAvailability(points: List<GeoPoint>): String {
        if (points.isEmpty()) return getString(R.string.raw_data_unavailable)
        val zone = ZoneId.systemDefault()
        val first = points.minOf { it.instant }.atZone(zone).toLocalDate()
        val last = points.maxOf { it.instant }.atZone(zone).toLocalDate()
        val date = formatExactDate(first)
        return when {
            points.size == 1 -> getString(R.string.raw_data_available_one_point, date)
            first == last -> getString(R.string.raw_data_available_one_day, date)
            else -> getString(R.string.raw_data_available_range, date, formatExactDate(last))
        }
    }

    private fun useAvailableRawRange() {
        val (first, last) = rawDateBounds() ?: return
        rawProjectRangeConflict = false
        selectedStartDate = first
        selectedEndDate = last
        activeStartDate = first
        activeEndDate = last
        updateExactDateControls()
        updateSuggestedProjectTitle(force = true)
        updateProjectDateLabel()
        updateRawDataAvailability()
        selectRange()
    }

    private fun formatExactDate(date: LocalDate): String = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(resources.configuration.locales[0])
        .format(date)

    private fun datePickerMillis(date: LocalDate): Long = date
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

    private fun normalizeRange(changedStart: Boolean) {
        val startYear = selectedStartYear ?: return
        val endYear = selectedEndYear ?: return
        val start = YearMonth.of(startYear, selectedStartMonth)
        val end = YearMonth.of(endYear, selectedEndMonth)
        if (!endPeriodEnabled && activeProjectKind in setOf(TripKind.MONTHLY_RECAP, TripKind.YEARLY_RECAP)) {
            selectedEndYear = start.year
            if (activeProjectKind == TripKind.YEARLY_RECAP) {
                selectedStartMonth = 1
                selectedEndMonth = 12
            } else {
                selectedEndMonth = start.monthValue
            }
        } else if (start > end) {
            if (changedStart) {
                selectedEndYear = start.year
                selectedEndMonth = start.monthValue
            } else {
                selectedStartYear = end.year
                selectedStartMonth = end.monthValue
            }
            updateYearDropdowns()
            editor.startMonthDropdown.setText(monthNames[selectedStartMonth - 1], false)
            editor.endMonthDropdown.setText(monthNames[selectedEndMonth - 1], false)
        }
        if (exactDateRangeEnabled) {
            resetExactDatesToSelectedMonths()
            updateExactDateControls()
        }
        updateResolvedTitle()
        selectRange()
        if (currentCreateStep == CreateStep.PROJECT) onProjectPeriodChanged(updateSelection = false)
    }

    private fun updateYearDropdowns() {
        val formatter = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
        selectedStartYear?.let { editor.startYearDropdown.setText(formatter.format(it), false) }
        selectedEndYear?.let { editor.endYearDropdown.setText(formatter.format(it), false) }
    }

    private fun currentPeriod(): TimelinePeriod? {
        val startYear = selectedStartYear ?: return null
        val endYear = selectedEndYear ?: return null
        if (activeProjectKind == TripKind.YEARLY_RECAP) {
            return RecapPeriodRules.yearlyTimelinePeriod(startYear, endYear)
        }
        return TimelinePeriod(
            start = YearMonth.of(startYear, selectedStartMonth),
            endInclusive = YearMonth.of(endYear, selectedEndMonth),
        )
    }

    private fun togglePreview() {
        journey ?: return
        if (animation?.isPaused == true) {
            animation?.resume()
            updatePreviewControl(playing = true)
            return
        }
        if (animation?.isRunning == true) {
            animation?.pause()
            updatePreviewControl(playing = false)
            return
        }
        val start = if (editor.timelineSeek.progress >= 1000) {
            editor.timelineSeek.progress = 0
            showProgress(0f)
            0
        } else editor.timelineSeek.progress
        editor.timelineView.journeyDurationSeconds = selectedDurationSeconds()
        val durationMs = (TimelineAnimation.totalDurationSeconds(selectedDurationSeconds()) * 1000f).toLong()
        animation = ValueAnimator.ofInt(start, 1000).apply {
            duration = ((1000 - start) / 1000f * durationMs).toLong().coerceAtLeast(250)
            addUpdateListener { value ->
                val progress = value.animatedValue as Int
                editor.timelineSeek.progress = progress
                showProgress(progress / 1000f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    updatePreviewControl(playing = true)
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    updatePreviewControl(playing = false)
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    updatePreviewControl(playing = false)
                }
            })
            start()
        }
    }

    private fun updatePreviewControl(playing: Boolean) {
        editor.playButton.setIconResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_arrow_24)
        editor.playButton.contentDescription = getString(if (playing) R.string.pause_preview else R.string.preview)
    }

    private fun showProgress(progress: Float) {
        editor.timelineView.progress = progress
    }

    private fun configureAdvancedSettings() {
        val aspectRatioLabels = listOf(
            R.string.aspect_square,
            R.string.aspect_portrait,
            R.string.aspect_landscape,
        ).map(::getString)
        val cameraLabels = mapViewLabelResources.map(::getString)
        val compressionLabels = listOf(
            R.string.compression_off,
            R.string.compression_balanced,
            R.string.compression_strong,
            R.string.compression_stronger,
        ).map(::getString)
        val resolutionLabels = listOf(
            R.string.resolution_480,
            R.string.resolution_720,
            R.string.resolution_1080,
            R.string.resolution_1440,
            R.string.resolution_2160,
        ).map(::getString) + getString(R.string.custom_action)
        val frameRateLabels = listOf(24, 30, 60).map { getString(R.string.frame_rate_value, it) } +
            getString(R.string.custom_action)
        val tripDetectionLabels = listOf(
            R.string.trip_detection_conservative,
            R.string.trip_detection_balanced,
            R.string.trip_detection_sensitive,
        ).map(::getString)
        val localFramingLabels = listOf(
            R.string.local_framing_off,
            R.string.local_framing_balanced,
            R.string.local_framing_close,
        ).map(::getString)

        listOf(
            settingsScreen.aspectRatioDropdown to aspectRatioLabels,
            settingsScreen.cameraMovementDropdown to cameraLabels,
            settingsScreen.longTripDropdown to compressionLabels,
            settingsScreen.videoQualityDropdown to resolutionLabels,
            settingsScreen.frameRateDropdown to frameRateLabels,
            settingsScreen.tripDetectionDropdown to tripDetectionLabels,
            settingsScreen.localFramingDropdown to localFramingLabels,
        ).forEach { (dropdown, labels) ->
            dropdown.setAdapter(SelectionArrayAdapter(this, labels))
            makeDropdownOpenReliably(dropdown)
        }

        settingsScreen.aspectRatioDropdown.setOnItemClickListener { _, _, position, _ ->
            updateAdvancedSettings(
                cameraSettings.copy(
                    videoQuality = cameraSettings.videoQuality.withAspectRatio(VideoAspectRatio.entries[position]),
                ),
            )
        }
        settingsScreen.cameraMovementDropdown.setOnItemClickListener { _, _, position, _ ->
            updateAdvancedSettings(cameraSettings.withAutomaticMapView(CameraMovement.entries[position]))
        }
        settingsScreen.tripDetectionDropdown.setOnItemClickListener { _, _, position, _ ->
            updateAdvancedSettings(cameraSettings.copy(tripDetection = TripDetection.entries[position]))
        }
        settingsScreen.localFramingDropdown.setOnItemClickListener { _, _, position, _ ->
            updateAdvancedSettings(cameraSettings.copy(localFraming = LocalFraming.entries[position]))
        }
        settingsScreen.longTripDropdown.setOnItemClickListener { _, _, position, _ ->
            updateAdvancedSettings(cameraSettings.copy(longTripCompression = LongTripCompression.values()[position]))
        }
        settingsScreen.videoQualityDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == ExportResolution.entries.size) {
                showCustomResolutionDialog()
            } else {
                val selected = ExportResolution.entries[position]
                updateAdvancedSettings(
                    cameraSettings.copy(
                        exportFormat = cameraSettings.effectiveExportFormat.copy(
                            shortEdge = selected.shortEdge,
                            customResolution = false,
                        ),
                    ),
                    presetRelevantChange = false,
                )
            }
        }
        settingsScreen.frameRateDropdown.setOnItemClickListener { _, _, position, _ ->
            val presetRates = listOf(24, 30, 60)
            if (position == presetRates.size) {
                showCustomFrameRateDialog()
            } else {
                updateAdvancedSettings(
                    cameraSettings.copy(
                        exportFormat = cameraSettings.effectiveExportFormat.copy(
                            frameRate = presetRates[position],
                            customFrameRate = false,
                        ),
                    ),
                    presetRelevantChange = false,
                )
            }
        }
        settingsScreen.resetAdvancedSettingsButton.setOnClickListener {
            markPresetCustom(clearDefault = !settingsReturnToCreate)
            if (settingsReturnToCreate) {
                applyAdvancedSettings(settingsViewModel.state.value.camera)
            } else {
                settingsViewModel.resetVideoDefaults()
                applyAdvancedSettings(settingsViewModel.state.value.camera)
            }
            Snackbar.make(binding.root, R.string.video_defaults_restored, Snackbar.LENGTH_SHORT).show()
        }
        applyAdvancedSettings(settingsViewModel.state.value.camera)
    }

    private fun configurePresets() {
        presetsConfigured = true
        val defaultId = presetRepository.defaultPresetId()
        activePresetId = presetRepository.presets().firstOrNull {
            it.id == defaultId && it.values == currentPresetValues()
        }?.id
        presetOriginId = activePresetId
        editor.presetDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                markPresetCustom(preserveBuiltIn = false)
            } else {
                presetRepository.presets().getOrNull(position - 1)?.let(::applyPreset)
            }
        }
        makeDropdownOpenReliably(editor.presetDropdown)
        editor.presetSaveButton.setOnClickListener { saveCurrentPreset() }
        editor.presetMoreButton.setOnClickListener { selectedPreset()?.let(::showPresetActions) }
        renderPresetSelection()
    }

    private fun restoreDraftSettings(savedState: Bundle?) {
        savedState ?: return
        val restored = runCatching {
            CameraSettings(
                cameraMovement = CameraMovement.valueOf(savedState.getString(STATE_DRAFT_CAMERA)!!),
                longTripCompression = LongTripCompression.valueOf(savedState.getString(STATE_DRAFT_PACING)!!),
                videoQuality = VideoQuality.valueOf(savedState.getString(STATE_DRAFT_QUALITY)!!),
                exportFormat = restoredExportFormat(savedState),
                tripDetection = TripDetection.valueOf(savedState.getString(STATE_DRAFT_TRIP_DETECTION)!!),
                localFraming = LocalFraming.valueOf(savedState.getString(STATE_DRAFT_LOCAL_FRAMING)!!),
            )
        }.getOrNull() ?: return
        val restoredPresetId = savedState.getString(STATE_ACTIVE_PRESET_ID)
        activePresetId = presetRepository.presets().firstOrNull {
            it.id == restoredPresetId && it.values == PresetValues.from(restored, routeDurationSeconds)
        }?.id
        presetOriginId = savedState.getString(STATE_PRESET_ORIGIN_ID) ?: activePresetId
        applyAdvancedSettings(restored)
        renderPresetSelection()
    }

    private fun restoreCustomizationCamera(savedState: Bundle?): CameraSettings? {
        savedState ?: return null
        return runCatching {
            CameraSettings(
                cameraMovement = CameraMovement.valueOf(savedState.getString(STATE_CUSTOMIZATION_CAMERA)!!),
                longTripCompression = LongTripCompression.valueOf(savedState.getString(STATE_CUSTOMIZATION_PACING)!!),
                videoQuality = VideoQuality.valueOf(savedState.getString(STATE_CUSTOMIZATION_QUALITY)!!),
                tripDetection = TripDetection.valueOf(savedState.getString(STATE_CUSTOMIZATION_TRIP_DETECTION)!!),
                localFraming = LocalFraming.valueOf(savedState.getString(STATE_CUSTOMIZATION_LOCAL_FRAMING)!!),
            )
        }.getOrNull()
    }

    private fun restoredExportFormat(savedState: Bundle): ExportFormatSettings? = runCatching {
        ExportFormatSettings(
            shortEdge = savedState.getInt(STATE_DRAFT_EXPORT_SHORT_EDGE),
            frameRate = savedState.getInt(STATE_DRAFT_EXPORT_FRAME_RATE),
            customResolution = savedState.getBoolean(STATE_DRAFT_CUSTOM_RESOLUTION),
            customFrameRate = savedState.getBoolean(STATE_DRAFT_CUSTOM_FRAME_RATE),
        )
    }.getOrNull()

    private fun selectedPreset(): VideoPreset? = activePresetId?.let { selectedId ->
        presetRepository.presets().firstOrNull { it.id == selectedId }
    }

    private fun applyPreset(preset: VideoPreset) {
        modifiedBuiltInId = null
        activePresetId = preset.id
        presetOriginId = preset.id
        applyAdvancedSettings(preset.values.applyTo(cameraSettings))
        applyDuration(preset.values.durationSeconds)
        renderPresetSelection()
    }

    private fun markPresetCustom(clearDefault: Boolean = false, preserveBuiltIn: Boolean = true) {
        if (preserveBuiltIn) {
            selectedPreset()?.takeIf(VideoPreset::builtIn)?.let { modifiedBuiltInId = it.id }
        } else {
            modifiedBuiltInId = null
            presetOriginId = null
        }
        activePresetId = null
        if (clearDefault) presetRepository.setDefaultPresetId(null)
        if (presetsConfigured) renderPresetSelection()
    }

    private fun renderPresetSelection() {
        val presets = presetRepository.presets()
        val labels = listOf(getString(R.string.preset_custom)) + presets.map(VideoPreset::name)
        editor.presetDropdown.setAdapter(SelectionArrayAdapter(this, labels))
        val selected = selectedPreset()
        val modifiedBuiltIn = modifiedBuiltInId?.let { id -> presets.firstOrNull { it.id == id && it.builtIn } }
        val modifiedOrigin = if (selected == null) presetOriginId?.let { id -> presets.firstOrNull { it.id == id } } else null
        editor.presetDropdown.setText(
            selected?.name ?: (modifiedBuiltIn ?: modifiedOrigin)?.let { getString(R.string.preset_modified, it.name) }
            ?: getString(R.string.preset_custom),
            false,
        )
        editor.presetSummaryText.text = draftStyleSummary(selected, modifiedBuiltIn ?: modifiedOrigin)
        editor.presetMoreButton.isEnabled = selected != null && !selected.builtIn && !exportingVideo
        editor.presetSaveButton.isEnabled = selected == null && !exportingVideo
        editor.presetDropdown.isEnabled = !exportingVideo
    }

    private fun saveCurrentPreset() {
        val values = currentPresetValues()
        presetRepository.exactMatch(values)?.let { existing ->
            modifiedBuiltInId = null
            activePresetId = existing.id
            presetOriginId = existing.id
            renderPresetSelection()
            Snackbar.make(binding.root, getString(R.string.preset_already_exists, existing.name), Snackbar.LENGTH_LONG).show()
            return
        }
        val origin = presetOriginId?.let { id -> presetRepository.presets().firstOrNull { it.id == id } }
        if (origin != null && !origin.builtIn) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.save_preset_choice_title)
                .setItems(
                    arrayOf(
                        getString(R.string.overwrite_preset, origin.name),
                        getString(R.string.save_as_new_preset),
                    ),
                ) { _, position ->
                    if (position == 0) overwritePreset(origin, values) else saveNewPreset(values)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            saveNewPreset(values)
        }
    }

    private fun saveVideoDefaultsAsPreset() {
        val values = PresetValues.from(settingsViewModel.state.value.camera, VideoDuration.DEFAULT_SECONDS)
        presetRepository.exactMatch(values)?.let { existing ->
            Snackbar.make(
                binding.root,
                getString(R.string.preset_already_exists, existing.name),
                Snackbar.LENGTH_LONG,
            ).show()
            return
        }
        saveNewPreset(values)
    }

    private fun overwritePreset(origin: VideoPreset, values: PresetValues) {
        presetRepository.replace(origin.id, values)?.let { updated ->
            modifiedBuiltInId = null
            activePresetId = updated.id
            presetOriginId = updated.id
            renderPresetSelection()
            Snackbar.make(binding.root, R.string.preset_overwritten, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun saveNewPreset(values: PresetValues) {
        if (presetRepository.presets().count { !it.builtIn } >= PresetRepository.MAX_PRESETS) {
            Snackbar.make(binding.root, R.string.preset_limit_reached, Snackbar.LENGTH_LONG).show()
            return
        }
        showPresetNameDialog { name ->
            val preset = presetRepository.add(name, values)
            modifiedBuiltInId = null
            activePresetId = preset.id
            presetOriginId = preset.id
            renderPresetSelection()
            Snackbar.make(binding.root, R.string.preset_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showPresetActions(preset: VideoPreset) {
        if (preset.builtIn) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preset_actions)
            .setItems(
                arrayOf(
                    getString(R.string.rename_preset),
                    getString(R.string.set_preset_default),
                    getString(R.string.delete_preset),
                ),
            ) { _, position ->
                when (position) {
                    0 -> showPresetNameDialog(preset.name, preset.id) { name ->
                        presetRepository.rename(preset.id, name)?.let {
                            renderPresetSelection()
                            Snackbar.make(binding.root, R.string.preset_renamed, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        presetRepository.setDefaultPresetId(preset.id)
                        settingsViewModel.updateCamera(cameraSettings)
                        Snackbar.make(binding.root, R.string.preset_default_set, Snackbar.LENGTH_SHORT).show()
                    }
                    2 -> confirmDeletePreset(preset)
                }
            }
            .show()
    }

    private fun showPresetManager() {
        val presets = presetRepository.presets().filterNot(VideoPreset::builtIn)
        if (presets.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.manage_presets)
                .setMessage(R.string.no_user_presets)
                .setPositiveButton(R.string.done, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manage_presets)
            .setItems(presets.map(VideoPreset::name).toTypedArray()) { _, position ->
                showPresetActions(presets[position])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePreset(preset: VideoPreset) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_preset_title)
            .setMessage(R.string.delete_preset_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_preset) { _, _ ->
                if (presetRepository.delete(preset.id)) {
                    if (activePresetId == preset.id) activePresetId = null
                    if (presetOriginId == preset.id) presetOriginId = null
                    renderPresetSelection()
                    Snackbar.make(binding.root, R.string.preset_deleted, Snackbar.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showPresetNameDialog(
        initialName: String = "",
        excludingId: String? = null,
        onValid: (String) -> Unit,
    ) {
        val input = TextInputEditText(this).apply {
            id = R.id.presetNameInput
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(initialName)
            selectAll()
        }
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.preset_name)
            counterMaxLength = PresetRepository.MAX_NAME_CODE_POINTS
            isCounterEnabled = true
            addView(input)
        }
        val margin = (24 * resources.displayMetrics.density).toInt()
        inputLayout.setPadding(margin, 0, margin, 0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (excludingId == null) R.string.save_preset else R.string.rename_preset)
            .setView(inputLayout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_preset, null)
            .create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            when (val result = presetRepository.validateName(input.text?.toString().orEmpty(), excludingId)) {
                PresetNameResult.Empty -> inputLayout.error = getString(R.string.preset_name_empty)
                PresetNameResult.TooLong -> inputLayout.error = getString(R.string.preset_name_too_long)
                PresetNameResult.Duplicate -> inputLayout.error = getString(R.string.preset_name_duplicate)
                is PresetNameResult.Valid -> {
                    inputLayout.error = null
                    onValid(result.name)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun presetValueSummary(values: PresetValues): String = listOf(
        R.string.duration to resources.getQuantityString(
            R.plurals.duration_seconds,
            values.durationSeconds,
            values.durationSeconds,
        ),
        R.string.aspect_ratio to listOf(
            R.string.aspect_square,
            R.string.aspect_portrait,
            R.string.aspect_landscape,
        )[values.aspectRatio.ordinal],
        R.string.map_view to mapViewLabelResources[values.cameraMovement.ordinal],
    ).joinToString("\n") { (label, value) ->
        getString(R.string.preset_value_format, getString(label), if (value is Int) getString(value) else value)
    }

    private fun currentPresetValues(): PresetValues = PresetValues.from(cameraSettings, routeDurationSeconds)

    private fun draftStyleSummary(selected: VideoPreset? = selectedPreset(), modified: VideoPreset? = null): String {
        val presetLabel = selected?.name
            ?: modified?.let { getString(R.string.preset_modified, it.name) }
            ?: getString(R.string.preset_custom)
        return listOf(
            presetLabel,
            aspectRatioLabel(cameraSettings.videoQuality.aspectRatioOption),
            resources.getQuantityString(R.plurals.duration_seconds, routeDurationSeconds, routeDurationSeconds),
        ).joinToString(" · ")
    }

    private fun syncPresetMatch() {
        val previous = selectedPreset()
        val match = presetRepository.exactMatch(currentPresetValues())
        if (match != null) {
            activePresetId = match.id
            presetOriginId = match.id
            modifiedBuiltInId = null
        } else {
            previous?.takeIf(VideoPreset::builtIn)?.let { modifiedBuiltInId = it.id }
            activePresetId = null
        }
        if (presetsConfigured) renderPresetSelection()
    }

    private fun configureLocationFiltering() {
        locationFilterMode = LocationFilterMode.CONSERVATIVE
    }

    private fun configureTimelineDisplay() {
        simplifyRouteDetail = settingsViewModel.state.value.simplifyRouteDetail
        settingsScreen.simplifyRouteDetailSwitch.isChecked = simplifyRouteDetail
        settingsScreen.simplifyRouteDetailSwitch.setOnCheckedChangeListener { _, checked ->
            if (simplifyRouteDetail == checked) return@setOnCheckedChangeListener
            simplifyRouteDetail = checked
            settingsViewModel.updateSimplifyRouteDetail(checked)
            if (!BuildConfig.IS_JOURNAL_LAB) return@setOnCheckedChangeListener
            invalidateJournalRouteRequest()
            activeJournalRoute = null
            activeJournalRouteStart = null
            activeJournalRouteEndExclusive = null
            currentProjectDates()?.let { (start, end) ->
                loadJournalRouteForRange(start, end) { outcome ->
                    if (outcome == JournalRouteLoadOutcome.READY) selectRange()
                }
            }
        }
        val keepVisible = settingsViewModel.state.value.keepPastRoutesVisible
        settingsScreen.keepPastRoutesVisibleSwitch.isChecked = keepVisible
        applyAdvancedSettings(cameraSettings.copy(keepPastRoutesVisible = keepVisible))
        settingsScreen.keepPastRoutesVisibleSwitch.setOnCheckedChangeListener { _, checked ->
            settingsViewModel.updateKeepPastRoutesVisible(checked)
            applyAdvancedSettings(cameraSettings.copy(keepPastRoutesVisible = checked))
            editor.timelineView.invalidate()
        }
    }

    private fun configureDistanceUnitSelection() {
        distanceUnitPreference = settingsViewModel.state.value.distanceUnit
        val dropdown = settingsScreen.distanceUnitDropdown
        dropdown.setAdapter(SelectionArrayAdapter(this, distanceUnitLabels()))
        makeDropdownOpenReliably(dropdown)
        updateDistanceUnitLabel()
        dropdown.setOnItemClickListener { _, _, position, _ ->
            applyDistanceUnitPreference(DistanceUnitPreference.entries[position])
        }
    }

    private fun applyDistanceUnitPreference(preference: DistanceUnitPreference, save: Boolean = true) {
        distanceUnitPreference = preference
        if (save) settingsViewModel.updateDistanceUnit(preference)
        updateDistanceUnitLabel()
        editor.timelineView.renderText = currentRenderText()
        journey?.let { editor.periodSummaryText.text = selectedPeriodSummary(it, selectedIgnoredCount) }
        showProgress(editor.timelineSeek.progress / 1000f)
    }

    private fun updateDistanceUnitLabel() {
        val dropdown = settingsScreen.distanceUnitDropdown
        val labels = distanceUnitLabels()
        dropdown.setAdapter(SelectionArrayAdapter(this, labels))
        dropdown.setText(labels[distanceUnitPreference.ordinal], false)
    }

    private fun distanceUnitLabels(): List<String> {
        val automatic = getString(
            R.string.distance_unit_automatic_resolved,
            getString(R.string.distance_unit_automatic),
            distanceUnitName(DistanceUnit.automatic(systemLocale())),
        )
        return listOf(
            automatic,
            getString(R.string.distance_unit_kilometers),
            getString(R.string.distance_unit_miles),
        )
    }

    private fun distanceUnitName(unit: DistanceUnit): String = getString(
        when (unit) {
            DistanceUnit.KILOMETERS -> R.string.distance_unit_kilometers
            DistanceUnit.MILES -> R.string.distance_unit_miles
        },
    )

    private fun resolvedDistanceUnit(): DistanceUnit = distanceUnitPreference.resolve(systemLocale())

    private fun systemLocale(): Locale =
        Resources.getSystem().configuration.locales[0] ?: Locale.getDefault(Locale.Category.FORMAT)

    private fun configureLanguageSelection() {
        val labels = languageLabels()
        val dropdown = settingsScreen.languageDropdown
        dropdown.setAdapter(SelectionArrayAdapter(this, labels))
        makeDropdownOpenReliably(dropdown)
        updateLanguageSelectionLabel()
        dropdown.post(::updateLanguageSelectionLabel)
        dropdown.setOnItemClickListener { _, _, position, _ -> applyLanguageSelection(position) }
    }

    private fun languageLabels(): List<String> = listOf(
            getString(R.string.language_system_default),
            getString(R.string.language_name_en),
            getString(R.string.language_name_ko),
            getString(R.string.language_name_ja),
            getString(R.string.language_name_zh_cn),
            getString(R.string.language_name_zh_tw),
            getString(R.string.language_name_es),
            getString(R.string.language_name_fr),
            getString(R.string.language_name_de),
            getString(R.string.language_name_pt_br),
        )

    private fun applyLanguageSelection(position: Int) {
        val locales = AppLanguage.localesForSelection(position)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    private fun showOnboardingLanguagePicker() {
        val labels = languageLabels().toTypedArray()
        val selected = AppLanguage.selectionIndex(currentApplicationLanguageTags())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(labels, selected) { dialog, position ->
                dialog.dismiss()
                applyLanguageSelection(position)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateOnboardingLanguageLabel() {
        val selected = AppLanguage.selectionIndex(currentApplicationLanguageTags())
        onboarding.onboardingLanguageButton.text = languageLabels()[selected]
    }

    private fun updateLanguageSelectionLabel() {
        val dropdown = settingsScreen.languageDropdown
        val selected = AppLanguage.selectionIndex(currentApplicationLanguageTags())
        dropdown.setText(dropdown.adapter.getItem(selected).toString(), false)
    }

    private fun configureCameraPreparation() {
        editor.timelineView.onCameraPreparationChanged = { ready ->
            if (ready) {
                if (editor.timelineView.isShown) editor.timelineView.runAfterNextFrameRendered {
                    pendingImportCompletionUri?.let(timelineSourceStore::completeImport)
                    pendingImportCompletionUri = null
                } else {
                    pendingImportCompletionUri?.let(timelineSourceStore::completeImport)
                    pendingImportCompletionUri = null
                }
            }
            updateCameraPreparationUi()
        }
        editor.timelineView.onCameraPreparationFailed = { error ->
            Log.e(TAG, "Timeline camera preparation failed", error)
            updateCameraPreparationUi()
            editor.statusText.setText(R.string.preview_preparation_failed)
            Snackbar.make(binding.root, R.string.preview_preparation_failed, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.retry) {
                    editor.statusText.setText(R.string.preparing_preview)
                    editor.timelineView.retryCameraPreparation()
                }
                .show()
        }
    }

    private fun updateCameraPreparationUi() {
        val selected = journey
        val ready = editor.timelineView.isCameraReady
        val canCreate = selected?.let(::canCreateVideo) == true && ready
        editor.playButton.isEnabled = !exportingVideo && canCreate
        editor.exportButton.isEnabled = !exportingVideo && canCreate && videoFormatSupported
        editor.timelineSeek.isEnabled = !exportingVideo && ready
        if (selected != null && !ready && !exportingVideo) {
            editor.statusText.setText(R.string.preparing_preview)
        } else if (
            editor.statusText.text?.toString() in setOf(
                getString(R.string.preparing_preview),
                getString(R.string.preview_preparation_failed),
            )
        ) {
            editor.statusText.text = ""
        }
    }

    private fun rebuildRenderTimeline(reselect: Boolean) {
        val source = timeline
        if (source == null) {
            renderTimeline = null
            return
        }
        if (BuildConfig.IS_JOURNAL_LAB) {
            // JournalRouteService already applies the active detailed filter and fuses semantic
            // fallback. Journey cannot represent explicit GAP spans yet, so Lab 2 uses the
            // service's documented flattened compatibility projection.
            renderTimeline = source
            if (reselect && selectedStartYear != null && selectedEndYear != null) selectRange()
            return
        }
        val result = LocationOutlierFilter.filter(source.points, locationFilterMode)
        renderTimeline = Timeline(result.points)
        if (rawSignalPoints.isNotEmpty()) rebuildRawSignalsTimeline()
        if (reselect && selectedStartYear != null && selectedEndYear != null) selectRange()
    }

    private fun updateAdvancedSettings(
        settings: CameraSettings,
        presetRelevantChange: Boolean = true,
    ) {
        if (presetRelevantChange) markPresetCustom(clearDefault = !settingsReturnToCreate)
        if (!settingsReturnToCreate) settingsViewModel.updateCamera(settings)
        applyAdvancedSettings(settings)
        if (presetRelevantChange) syncPresetMatch()
    }

    private fun applyAdvancedSettings(settings: CameraSettings) {
        cameraSettings = settings
        editor.timelineView.cameraSettings = settings
        settingsScreen.aspectRatioDropdown.setText(
            getString(
                listOf(
                    R.string.aspect_square,
                    R.string.aspect_portrait,
                    R.string.aspect_landscape,
                )[settings.videoQuality.aspectRatioOption.ordinal],
            ),
            false,
        )
        settingsScreen.cameraMovementDropdown.setText(
            getString(
                mapViewLabelResources[settings.cameraMovement.ordinal],
            ),
            false,
        )
        settingsScreen.cameraMovementHelpText.setText(
            listOf(
                R.string.camera_fixed_summary,
                R.string.camera_steady_summary,
                R.string.camera_dynamic_summary,
                R.string.camera_close_up_summary,
            )[settings.cameraMovement.ordinal],
        )
        settingsScreen.tripDetectionDropdown.setText(
            getString(
                listOf(
                    R.string.trip_detection_conservative,
                    R.string.trip_detection_balanced,
                    R.string.trip_detection_sensitive,
                )[settings.tripDetection.ordinal],
            ),
            false,
        )
        settingsScreen.localFramingDropdown.setText(
            getString(
                listOf(
                    R.string.local_framing_off,
                    R.string.local_framing_balanced,
                    R.string.local_framing_close,
                )[settings.localFraming.ordinal],
            ),
            false,
        )
        settingsScreen.tripDetectionDropdown.isEnabled = !exportingVideo
        settingsScreen.localFramingDropdown.isEnabled = !exportingVideo
        settingsScreen.longTripDropdown.setText(
            getString(
                listOf(
                    R.string.compression_off,
                    R.string.compression_balanced,
                    R.string.compression_strong,
                    R.string.compression_stronger,
                )[settings.longTripCompression.ordinal],
            ),
            false,
        )
        settingsScreen.videoQualityDropdown.setText(
            resolutionSelectionLabel(settings),
            false,
        )
        settingsScreen.frameRateDropdown.setText(
            if (settings.effectiveExportFormat.customFrameRate) {
                getString(R.string.custom_frame_rate_selected, settings.effectiveExportFormat.frameRate)
            } else {
                getString(R.string.frame_rate_value, settings.effectiveExportFormat.frameRate)
            },
            false,
        )
        val warning = videoFormatWarning(settings.activeVideoFormat)
        videoFormatSupported = warning == null
        settingsScreen.videoFormatWarningText.text = warning
        settingsScreen.videoFormatWarningText.visibility = if (warning == null) View.GONE else View.VISIBLE
        updateCameraPreparationUi()
        showProgress(editor.timelineSeek.progress / 1000f)
    }

    private fun resolutionSelectionLabel(settings: CameraSettings): String {
        val selection = settings.effectiveExportFormat
        val format = settings.activeVideoFormat
        return if (selection.customResolution) {
            getString(R.string.custom_resolution_selected, format.width, format.height)
        } else {
            getString(R.string.preset_resolution_selected, selection.shortEdge, format.width, format.height)
        }
    }

    private fun videoFormatWarning(format: VideoFormat): String? {
        if (videoEncoderProfiles.isEmpty()) return null
        return when (val support = VideoEncoderSupport.select(format, videoEncoderProfiles)) {
            is EncoderSupport.Supported -> null
            is EncoderSupport.Unsupported -> support.reason.describe(this, format)
        }
    }

    private fun showCustomResolutionDialog() {
        val existing = cameraSettings.effectiveExportFormat
        val input = TextInputEditText(this).apply {
            id = R.id.customResolutionInput
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(String.format(Locale.ROOT, "%d", existing.shortEdge))
            selectAll()
        }
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.custom_resolution_short_edge)
            helperText = getString(
                R.string.custom_resolution_range,
                ExportFormatSettings.MIN_SHORT_EDGE,
                ExportFormatSettings.MAX_SHORT_EDGE,
            )
            addView(input)
        }
        val outputSummary = android.widget.TextView(this).apply {
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        fun updateSummary() {
            outputSummary.text = ExportFormatSettings.parseShortEdge(input.text)?.let { shortEdge ->
                val format = existing.copy(shortEdge = shortEdge).format(cameraSettings.videoQuality.aspectRatioOption)
                getString(R.string.output_dimensions, format.width, format.height)
            }.orEmpty()
        }
        input.doAfterTextChanged { updateSummary() }
        updateSummary()
        val margin = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, 0, margin, 0)
            addView(inputLayout)
            addView(outputSummary)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_resolution_title)
            .setView(container)
            .setNegativeButton(R.string.cancel) { _, _ -> applyAdvancedSettings(cameraSettings) }
            .setPositiveButton(R.string.done, null)
            .create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val shortEdge = ExportFormatSettings.parseShortEdge(input.text)
            if (shortEdge == null) {
                inputLayout.error = getString(
                    R.string.custom_resolution_range,
                    ExportFormatSettings.MIN_SHORT_EDGE,
                    ExportFormatSettings.MAX_SHORT_EDGE,
                )
            } else {
                updateAdvancedSettings(
                    cameraSettings.copy(
                        exportFormat = existing.copy(shortEdge = shortEdge, customResolution = true),
                    ),
                    presetRelevantChange = false,
                )
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { applyAdvancedSettings(cameraSettings) }
    }

    private fun showCustomFrameRateDialog() {
        val existing = cameraSettings.effectiveExportFormat
        val input = TextInputEditText(this).apply {
            id = R.id.customFrameRateInput
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(String.format(Locale.ROOT, "%d", existing.frameRate))
            selectAll()
        }
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.custom_frame_rate_title)
            helperText = getString(
                R.string.custom_frame_rate_range,
                ExportFormatSettings.MIN_FRAME_RATE,
                ExportFormatSettings.MAX_FRAME_RATE,
            )
            addView(input)
        }
        val margin = (24 * resources.displayMetrics.density).toInt()
        inputLayout.setPadding(margin, 0, margin, 0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_frame_rate_title)
            .setView(inputLayout)
            .setNegativeButton(R.string.cancel) { _, _ -> applyAdvancedSettings(cameraSettings) }
            .setPositiveButton(R.string.done, null)
            .create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val frameRate = ExportFormatSettings.parseFrameRate(input.text)
            if (frameRate == null) {
                inputLayout.error = getString(
                    R.string.custom_frame_rate_range,
                    ExportFormatSettings.MIN_FRAME_RATE,
                    ExportFormatSettings.MAX_FRAME_RATE,
                )
            } else {
                updateAdvancedSettings(
                    cameraSettings.copy(
                        exportFormat = existing.copy(frameRate = frameRate, customFrameRate = true),
                    ),
                    presetRelevantChange = false,
                )
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { applyAdvancedSettings(cameraSettings) }
    }

    private fun applyDuration(seconds: Int) {
        require(seconds in VideoDuration.MIN_SECONDS..VideoDuration.MAX_SECONDS)
        routeDurationSeconds = seconds
        editor.durationDropdown.setText(
            resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds),
            false,
        )
        editor.durationWarningText.visibility =
            if (seconds > VideoDuration.LONG_DURATION_SECONDS) View.VISIBLE else View.GONE
        animation?.cancel()
        editor.timelineView.journeyDurationSeconds = seconds
        showProgress(editor.timelineSeek.progress / 1000f)
        if (presetsConfigured) syncPresetMatch()
    }

    private fun showCustomDurationDialog() {
        val input = TextInputEditText(this).apply {
            id = R.id.customDurationInput
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(String.format(Locale.ROOT, "%d", routeDurationSeconds))
            selectAll()
        }
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.custom_duration_hint)
            helperText = getString(R.string.custom_duration_range)
            addView(input)
        }
        val margin = (24 * resources.displayMetrics.density).toInt()
        inputLayout.setPadding(margin, 0, margin, 0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_duration_title)
            .setView(inputLayout)
            .setNegativeButton(R.string.cancel) { _, _ -> applyDuration(routeDurationSeconds) }
            .setPositiveButton(R.string.done, null)
            .create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val seconds = VideoDuration.parseCustom(input.text)
            if (seconds == null) {
                inputLayout.error = getString(R.string.custom_duration_range)
            } else {
                inputLayout.error = null
                applyDuration(seconds)
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { applyDuration(routeDurationSeconds) }
    }

    private fun chooseExportDestination() {
        val format = cameraSettings.activeVideoFormat
        if (format.width.coerceAtLeast(format.height) > 1920 || format.frameRate > 30) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.demanding_format_title)
                .setMessage(
                    getString(
                        R.string.demanding_format_message,
                        format.width,
                        format.height,
                        format.frameRate,
                    ),
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.export_anyway) { _, _ -> continueExportDestination() }
                .show()
            return
        }
        continueExportDestination()
    }

    private fun continueExportDestination() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean(NOTIFICATION_PROMPTED, false)
        ) {
            preferences.edit { putBoolean(NOTIFICATION_PROMPTED, true) }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.background_video_title)
                .setMessage(R.string.background_video_message)
                .setNegativeButton(R.string.not_now) { _, _ -> openExportDestination() }
                .setPositiveButton(R.string.continue_action) { _, _ ->
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .show()
            return
        }
        openExportDestination()
    }

    private fun openExportDestination() {
        val selected = journey ?: return
        animation?.cancel()
        commitTitlePreferences()
        val project = ensureActiveProject()
        val title = resolvedTitle(selected.period)
        pendingExport = VideoExportRequest(
            outputUri = "",
            journey = selected,
            title = title,
            durationSeconds = selectedDurationSeconds(),
            renderText = currentRenderText(),
            cameraSettings = cameraSettings,
            projectId = project?.id,
            presetName = selectedPreset()?.name,
            dataSource = currentVideoDataSource(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val request = pendingExport ?: return
            pendingExport = null
            runCatching { generatedMedia.createVideoDestination(title, selected.period) }
                .onSuccess { uri -> uri?.let { startVideoExport(it, request) } }
                .onFailure {
                    editor.statusText.setText(R.string.video_request_unavailable)
                    Snackbar.make(binding.root, R.string.video_export_failed, Snackbar.LENGTH_LONG).show()
                }
        } else {
            createVideo.launch("${generatedMedia.fileBaseName(title, selected.period)}.mp4")
        }
    }

    private fun startVideoExport(uri: Uri, request: VideoExportRequest) {
        val completeRequest = request.copy(outputUri = uri.toString())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.authority != android.provider.MediaStore.AUTHORITY) {
            persistUriAccess(uri, includeWrite = true)
        }
        val saved = runCatching { VideoExportRequestStore(applicationContext).save(completeRequest) }
        if (saved.isFailure) {
            generatedMedia.discard(uri)
            editor.statusText.text = getString(R.string.video_request_unavailable)
            Snackbar.make(binding.root, R.string.video_export_failed, Snackbar.LENGTH_LONG).show()
            return
        }
        val snapshot = VideoExportSnapshot(
            status = VideoExportStatus.RUNNING,
            progress = ExportProgress(0f, ExportPhase.PREPARING_MAP, 0, 0),
            startedAtMillis = System.currentTimeMillis(),
            outputUri = completeRequest.outputUri,
            title = completeRequest.title,
        )
        videoExportViewModel.publish(snapshot)
        runCatching { VideoExportService.start(applicationContext) }.onFailure {
            generatedMedia.discard(uri)
            videoExportViewModel.publish(
                VideoExportSnapshot(
                    status = VideoExportStatus.FAILED,
                    outputUri = uri.toString(),
                    title = completeRequest.title,
                    errorMessage = getString(R.string.video_request_unavailable),
                ),
            )
        }
    }

    private fun observeVideoExport() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                videoExportViewModel.state.collect(::renderVideoExport)
            }
        }
    }

    private fun renderVideoExport(snapshot: VideoExportSnapshot) {
        when (snapshot.status) {
            VideoExportStatus.IDLE -> {
                setExporting(false)
                hideExportTray()
            }
            VideoExportStatus.RUNNING -> {
                if (lastRenderedExportStatus != VideoExportStatus.RUNNING) exportEtaEstimator.reset()
                setExporting(true)
                showRunningExportTray()
                snapshot.progress?.let(::showExportProgress)
            }
            VideoExportStatus.COMPLETE -> {
                setExporting(false)
                snapshot.outputUri?.toUri()?.let { uri ->
                    lastVideoUri = uri
                    lastVideoTitle = snapshot.title
                    editor.videoReadyGroup.visibility = View.VISIBLE
                    val hasOverview = videoMedia.cachedOverview(uri) != null
                    editor.saveAsButton.isEnabled = true
                    editor.saveOverviewButton.isEnabled = hasOverview
                    editor.shareOverviewButton.isEnabled = hasOverview
                }
                editor.statusText.text = getString(R.string.video_saved)
                showCompletedExportTray()
                if (lastRenderedExportStatus != VideoExportStatus.COMPLETE) renderVideos()
            }
            VideoExportStatus.CANCELLED -> {
                setExporting(false)
                hideExportTray()
                editor.statusText.text = getString(R.string.video_creation_cancelled)
            }
            VideoExportStatus.FAILED -> {
                setExporting(false)
                editor.statusText.setText(R.string.video_export_failed)
                showFailedExportTray(snapshot.errorMessage)
            }
        }
        lastRenderedExportStatus = snapshot.status
    }

    private fun showRunningExportTray() {
        if (currentScreen == Screen.ONBOARDING) {
            binding.exportStatusTray.visibility = View.GONE
            return
        }
        binding.exportStatusTray.visibility = View.VISIBLE
        binding.exportTrayProgress.visibility = View.VISIBLE
        binding.exportTrayCancelButton.visibility = View.VISIBLE
        binding.exportTrayWatchButton.visibility = View.GONE
        binding.exportTrayShareButton.visibility = View.GONE
        binding.exportTrayDismissButton.visibility = View.GONE
        binding.exportTrayRetryButton.visibility = View.GONE
    }

    private fun showCompletedExportTray() {
        if (currentScreen == Screen.ONBOARDING) {
            binding.exportStatusTray.visibility = View.GONE
            return
        }
        binding.exportStatusTray.visibility = View.VISIBLE
        binding.exportTrayProgress.visibility = View.GONE
        binding.exportTrayCancelButton.visibility = View.GONE
        binding.exportTrayRetryButton.visibility = View.GONE
        binding.exportTrayWatchButton.visibility = if (lastVideoUri != null) View.VISIBLE else View.GONE
        binding.exportTrayShareButton.visibility = if (lastVideoUri != null) View.VISIBLE else View.GONE
        binding.exportTrayDismissButton.visibility = View.VISIBLE
        binding.exportTrayStatusText.setText(R.string.video_ready)
        if (lastRenderedExportStatus != VideoExportStatus.COMPLETE) {
            announceExportStatus(getString(R.string.video_ready))
        }
        lastAnnouncedExportPhase = null
    }

    private fun showFailedExportTray(message: String?) {
        if (currentScreen == Screen.ONBOARDING) {
            binding.exportStatusTray.visibility = View.GONE
            return
        }
        binding.exportStatusTray.visibility = View.VISIBLE
        binding.exportTrayProgress.visibility = View.GONE
        binding.exportTrayCancelButton.visibility = View.GONE
        binding.exportTrayWatchButton.visibility = View.GONE
        binding.exportTrayShareButton.visibility = View.GONE
        binding.exportTrayDismissButton.visibility = View.GONE
        binding.exportTrayRetryButton.visibility = View.VISIBLE
        binding.exportTrayRetryButton.isEnabled = true
        binding.exportTrayStatusText.text = message ?: getString(R.string.video_export_failed)
        if (lastRenderedExportStatus != VideoExportStatus.FAILED) {
            announceExportStatus(binding.exportTrayStatusText.text)
        }
        lastAnnouncedExportPhase = null
    }

    private fun hideExportTray() {
        binding.exportStatusTray.visibility = View.GONE
        lastAnnouncedExportPhase = null
        exportEtaEstimator.reset()
    }

    private fun showExportProgress(progress: ExportProgress) {
        binding.exportTrayProgress.progress = (progress.fraction * 1000).toInt()
        if (progress.phase == ExportPhase.COMPLETE) return
        val base = when (progress.phase) {
            ExportPhase.PREPARING_MAP -> getString(
                R.string.preparing_map_progress,
                progress.completed,
                progress.total,
            )
            ExportPhase.CREATING_VIDEO -> getString(
                R.string.creating_video_progress,
                (progress.completed * 100f / progress.total.coerceAtLeast(1)).toInt(),
            )
            ExportPhase.FINISHING_VIDEO -> getString(
                R.string.finishing_video_progress,
                (progress.completed * 100f / progress.total.coerceAtLeast(1)).toInt(),
            )
            ExportPhase.COMPLETE -> return
        }
        val remaining = exportEtaEstimator.estimateRemainingSeconds(
            progress,
            SystemClock.elapsedRealtime(),
        )
        val status = if (remaining != null) {
            getString(R.string.progress_with_eta, base, formatRemainingTime(remaining))
        } else base
        editor.statusText.text = status
        binding.exportTrayStatusText.text = status
        if (lastAnnouncedExportPhase != progress.phase) {
            announceExportStatus(base)
            lastAnnouncedExportPhase = progress.phase
        }
    }

    @Suppress("DEPRECATION")
    private fun announceExportStatus(status: CharSequence) {
        binding.exportTrayStatusText.announceForAccessibility(status)
    }

    private fun retryVideoExport() {
        binding.exportTrayRetryButton.isEnabled = false
        lifecycleScope.launch {
            val request = withContext(Dispatchers.IO) {
                VideoExportRequestStore(applicationContext).load()
            }
            retryVideoExport(request)
        }
    }

    private fun retryVideoExport(request: VideoExportRequest?) {
        if (request == null) {
            videoExportViewModel.clear()
            showNewVideo(loadRemembered = true)
            Snackbar.make(binding.root, R.string.video_request_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        binding.exportTrayRetryButton.isEnabled = true
        pendingExport = request.copy(outputUri = "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val retryRequest = pendingExport ?: return
            pendingExport = null
            runCatching { generatedMedia.createVideoDestination(retryRequest.title, retryRequest.journey.period) }
                .onSuccess { uri -> uri?.let { startVideoExport(it, retryRequest) } }
                .onFailure {
                    videoExportViewModel.publish(
                        VideoExportSnapshot(
                            status = VideoExportStatus.FAILED,
                            title = retryRequest.title,
                            errorMessage = getString(R.string.video_request_unavailable),
                        ),
                    )
                }
        } else {
            createVideo.launch("${generatedMedia.fileBaseName(request.title, request.journey.period)}.mp4")
        }
    }

    private fun formatRemainingTime(seconds: Int): String = if (seconds < 90) {
        resources.getQuantityString(R.plurals.remaining_seconds, seconds.coerceAtLeast(1), seconds.coerceAtLeast(1))
    } else {
        val minutes = ceil(seconds / 60.0).toInt()
        resources.getQuantityString(R.plurals.remaining_minutes, minutes, minutes)
    }

    private fun setExporting(exporting: Boolean) {
        exportingVideo = exporting
        val canCreate = journey?.let(::canCreateVideo) == true && editor.timelineView.isCameraReady
        home.deleteAllVideosButton.isEnabled = !exporting
        editor.importButton.isEnabled = !exporting && editor.loadingGroup.visibility != View.VISIBLE
        editor.playButton.isEnabled = !exporting && canCreate
        editor.exportButton.isEnabled = !exporting && canCreate && videoFormatSupported
        editor.shareButton.isEnabled = !exporting
        editor.watchVideoButton.isEnabled = !exporting
        editor.createAnotherButton.isEnabled = !exporting
        editor.saveAsButton.isEnabled = !exporting && lastVideoUri != null
        val hasOverview = lastVideoUri?.let { videoMedia.cachedOverview(it) != null } == true
        editor.saveOverviewButton.isEnabled = !exporting && hasOverview
        editor.shareOverviewButton.isEnabled = !exporting && hasOverview
        editor.startYearDropdown.isEnabled = !exporting
        editor.endYearDropdown.isEnabled = !exporting
        editor.durationDropdown.isEnabled = !exporting
        editor.startMonthDropdown.isEnabled = !exporting
        editor.endMonthDropdown.isEnabled = !exporting
        editor.exactDateSwitch.isEnabled = !exporting
        editor.rawSignalsSwitch.isEnabled = !exporting
        editor.exactDateRangeButton.isEnabled = !exporting
        editor.ownerInput.isEnabled = !exporting
        editor.titleInput.isEnabled = !exporting
        settingsScreen.aspectRatioDropdown.isEnabled = !exporting
        settingsScreen.cameraMovementDropdown.isEnabled = !exporting
        settingsScreen.tripDetectionDropdown.isEnabled = !exporting
        settingsScreen.localFramingDropdown.isEnabled = !exporting
        settingsScreen.longTripDropdown.isEnabled = !exporting
        settingsScreen.videoQualityDropdown.isEnabled = !exporting
        settingsScreen.frameRateDropdown.isEnabled = !exporting
        settingsScreen.resetAdvancedSettingsButton.isEnabled = !exporting
        settingsScreen.simplifyRouteDetailSwitch.isEnabled = !exporting
        settingsScreen.keepPastRoutesVisibleSwitch.isEnabled = !exporting
        renderPresetSelection()
        if (exporting) editor.videoReadyGroup.visibility = View.GONE
        if (!exporting) updateCameraPreparationUi()
    }

    internal fun prepareAnotherVideo() {
        videoExportViewModel.clear()
        editor.videoReadyGroup.visibility = View.GONE
        editor.timelineSeek.progress = 0
        showProgress(0f)
        journey = null
        animation?.cancel()
        resetCreateEntry()
        showNewVideo(loadRemembered = true)
    }

    private fun configureTripDiscovery() {
        editor.detectionRangeDropdown.setOnItemClickListener { _, _, position, _ ->
            val previousRange = detectionStartDate to detectionEndDate
            if (position < detectionYears.size) {
                val year = detectionYears[position]
                selectedDetectionYear = year
                detectionStartDate = LocalDate.of(year, 1, 1)
                detectionEndDate = LocalDate.of(year, 12, 31)
                editor.detectionCustomRangeButton.visibility = View.GONE
            } else {
                selectedDetectionYear = null
                editor.detectionCustomRangeButton.visibility = View.VISIBLE
            }
            if (previousRange != (detectionStartDate to detectionEndDate)) invalidateTripSuggestionsForRange()
        }
        makeDropdownOpenReliably(editor.detectionRangeDropdown)
    }

    private fun refreshDetectionRanges() {
        detectionYears = semanticDateBounds()?.let { (start, end) ->
            (start.year..end.year).toList().sortedDescending()
        }.orEmpty()
        val labels = detectionYears.map(Int::toString) + getString(R.string.custom_range)
        editor.detectionRangeDropdown.setAdapter(SelectionArrayAdapter(this, labels))
        if (selectedDetectionYear == null && detectionStartDate != null && detectionEndDate != null) {
            editor.detectionRangeDropdown.setText(getString(R.string.custom_range), false)
            editor.detectionCustomRangeButton.visibility = View.VISIBLE
            editor.detectionCustomRangeButton.text = projectDateRange(
                requireNotNull(detectionStartDate),
                requireNotNull(detectionEndDate),
            )
            return
        }
        val year = selectedDetectionYear?.takeIf { it in detectionYears } ?: detectionYears.firstOrNull()
        if (year != null) {
            selectedDetectionYear = year
            detectionStartDate = LocalDate.of(year, 1, 1)
            detectionEndDate = LocalDate.of(year, 12, 31)
            editor.detectionRangeDropdown.setText(year.toString(), false)
            editor.detectionCustomRangeButton.visibility = View.GONE
        } else {
            editor.detectionRangeDropdown.setText(getString(R.string.custom_range), false)
            editor.detectionCustomRangeButton.visibility = View.VISIBLE
        }
    }

    private fun showTripDiscovery() {
        if (semanticDateBounds() == null) {
            showNewVideo(loadRemembered = false)
            requestTimelineImport()
            return
        }
        currentCreateStep = CreateStep.DISCOVERY
        refreshDetectionRanges()
        renderCreateStep()
    }

    private fun chooseDetectionRange() {
        val bounds = semanticDateBounds() ?: return
        val start = detectionStartDate ?: bounds.first
        val end = detectionEndDate ?: bounds.second
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.detection_range)
            .setSelection(AndroidPair(datePickerMillis(start), datePickerMillis(end)))
            .build()
        picker.addOnPositiveButtonClickListener { range ->
            detectionStartDate = Instant.ofEpochMilli(range.first).atZone(ZoneOffset.UTC).toLocalDate()
            detectionEndDate = Instant.ofEpochMilli(range.second).atZone(ZoneOffset.UTC).toLocalDate()
            selectedDetectionYear = null
            editor.detectionCustomRangeButton.text = projectDateRange(
                requireNotNull(detectionStartDate),
                requireNotNull(detectionEndDate),
            )
            invalidateTripSuggestionsForRange()
        }
        picker.show(supportFragmentManager, "trip-detection-range")
    }

    private fun runTripDetection() {
        if (tripDetectionRunning) return
        val start = detectionStartDate ?: return
        val end = detectionEndDate ?: return
        tripDiscoveryRequested = true
        val requestId = ++tripDetectionRequestId
        setTripDetectionRunning(true)
        loadJournalRouteForRange(start, end) { outcome ->
            if (requestId != tripDetectionRequestId) return@loadJournalRouteForRange
            if (outcome != JournalRouteLoadOutcome.READY) {
                finishTripDetection(requestId)
                return@loadJournalRouteForRange
            }
            val loaded = timeline
            if (loaded == null) {
                finishTripDetection(requestId)
                return@loadJournalRouteForRange
            }
            tripDetectionJob = lifecycleScope.launch {
                try {
                    val detected = detectTrips(loaded, start, end)
                    if (
                        requestId == tripDetectionRequestId &&
                        detectionStartDate == start &&
                        detectionEndDate == end
                    ) {
                        tripSuggestions = detected
                        suggestionsExpanded = false
                        renderTrips()
                    }
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.e(TAG, "Trip detection failed", error)
                    Snackbar.make(binding.root, R.string.journal_route_load_failed, Snackbar.LENGTH_LONG).show()
                } finally {
                    finishTripDetection(requestId)
                }
            }
        }
    }

    private fun setTripDetectionRunning(running: Boolean) {
        tripDetectionRunning = running
        editor.runTripDetectionButton.isEnabled = !running
        editor.runTripDetectionButton.setText(
            if (running) R.string.detecting_trips else R.string.recommend_trips,
        )
        editor.detectionRangeDropdown.isEnabled = !running
        editor.detectionCustomRangeButton.isEnabled = !running
        if (currentCreateStep == CreateStep.DISCOVERY) renderTripSuggestions()
    }

    private fun finishTripDetection(requestId: Long) {
        if (requestId != tripDetectionRequestId) return
        tripDetectionJob = null
        setTripDetectionRunning(false)
    }

    private fun cancelTripDetection() {
        if (!tripDetectionRunning) return
        tripDetectionRequestId += 1
        tripDetectionJob?.cancel()
        tripDetectionJob = null
        setTripDetectionRunning(false)
    }

    private fun invalidateTripSuggestionsForRange() {
        if (tripDetectionRunning) cancelTripDetection() else tripDetectionRequestId += 1
        tripDiscoveryRequested = false
        tripSuggestions = emptyList()
        suggestionsExpanded = false
        if (currentCreateStep == CreateStep.DISCOVERY) renderTripSuggestions()
    }

    private suspend fun refreshRequestedTripSuggestions(loaded: Timeline): List<TripSuggestion> {
        if (!tripDiscoveryRequested) return emptyList()
        val start = detectionStartDate ?: return emptyList()
        val end = detectionEndDate ?: return emptyList()
        return detectTrips(loaded, start, end)
    }

    private suspend fun detectTrips(
        loaded: Timeline,
        start: LocalDate,
        end: LocalDate,
    ): List<TripSuggestion> = withContext(Dispatchers.Default) {
        val dismissed = tripsStore.dismissedSuggestionIds()
        TripDetector.detect(
            timeline = loaded,
            request = TripDetectionRequest(start, end),
            nameResolver = OfflineDestinationNameResolver(applicationContext),
        ).filterNot { it.id in dismissed }
    }

    private fun startManualProject(kind: TripKind) {
        val zone = ZoneId.systemDefault()
        val bounds = if (kind == TripKind.RAW_DATA) rawDateBounds() else semanticDateBounds()
        if (bounds == null) return
        val latest = bounds.second
        activeProjectId = null
        activeSuggestionId = null
        activeProjectKind = kind
        rawProjectRangeConflict = false
        activeProjectTitleMode = if (kind == TripKind.TRIP) ProjectTitleMode.CUSTOM else ProjectTitleMode.AUTOMATIC
        editingProjectOnly = false
        endPeriodEnabled = false
        activeStartDate = when (kind) {
            TripKind.TRIP -> latest.minusDays(6).coerceAtLeast(bounds.first)
            TripKind.MONTHLY_RECAP -> latest.withDayOfMonth(1)
            TripKind.YEARLY_RECAP -> LocalDate.of(latest.year, 1, 1)
            TripKind.CUSTOM_RECAP -> RecapPeriodRules.customDefault(bounds.first, bounds.second, LocalDate.now(zone)).first
            TripKind.RAW_DATA -> bounds.first
        }
        activeEndDate = when (kind) {
            TripKind.TRIP -> latest
            TripKind.MONTHLY_RECAP -> latest.withDayOfMonth(1).plusMonths(1).minusDays(1)
            TripKind.YEARLY_RECAP -> LocalDate.of(latest.year, 12, 31)
            TripKind.CUSTOM_RECAP, TripKind.RAW_DATA -> bounds.second
        }
        setProjectTitle(
            if (kind == TripKind.TRIP) getString(R.string.new_trip) else suggestedProjectTitle(kind, activeStartDate!!, activeEndDate!!),
        )
        videoTitleUserEdited = false
        setAutomaticVideoTitle(editor.projectTitleInput.text?.toString().orEmpty())
        currentCreateStep = CreateStep.PROJECT
        showNewVideo(loadRemembered = true)
        applyActiveProjectDates()
        prepareActiveProjectRoute()
    }

    private fun chooseRecapKind() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_recap)
            .setItems(arrayOf(getString(R.string.monthly_recap_badge), getString(R.string.yearly_recap_badge))) { _, which ->
                startManualProject(if (which == 0) TripKind.MONTHLY_RECAP else TripKind.YEARLY_RECAP)
            }
            .show()
    }

    private fun renderTrips() {
        val projects = tripsStore.list()
        val videos = videoLibraryViewModel.records.value
        home.emptyLibraryText.visibility = if (projects.isEmpty() && videos.isEmpty()) View.VISIBLE else View.GONE
        home.deleteAllVideosButton.visibility = if (projects.isEmpty() && videos.isEmpty()) View.GONE else View.VISIBLE
        home.tripsHeading.visibility = if (projects.any { it.kind == TripKind.TRIP }) View.VISIBLE else View.GONE
        home.recapsHeading.visibility = if (projects.any { it.kind != TripKind.TRIP }) View.VISIBLE else View.GONE
        if (currentCreateStep == CreateStep.TRIP_SOURCE) renderCreateTripSources()
        if (currentCreateStep == CreateStep.DISCOVERY) renderTripSuggestions()

        home.tripsList.removeAllViews()
        home.recapsList.removeAllViews()
        projects.forEach { project ->
            val parent = if (project.kind == TripKind.TRIP) home.tripsList else home.recapsList
            addProjectCard(parent, project, videos.filter { it.projectId == project.id })
        }
    }

    private fun renderTripSuggestions() {
        val confirmedDates = tripsStore.list().map { it.startDate to it.endDate }.toSet()
        val suggestions = tripSuggestions.filterNot { it.startDate to it.endDate in confirmedDates }
        editor.emptySuggestionsText.visibility = if (suggestions.isEmpty() && !tripDetectionRunning) View.VISIBLE else View.GONE
        editor.showAllSuggestionsButton.visibility = if (suggestions.size > COLLAPSED_CREATION_COUNT) View.VISIBLE else View.GONE
        editor.showAllSuggestionsButton.isEnabled = !tripDetectionRunning
        editor.showAllSuggestionsButton.text = if (suggestionsExpanded) {
            getString(R.string.show_fewer_suggestions)
        } else {
            getString(R.string.show_all_suggestions, suggestions.size - COLLAPSED_CREATION_COUNT)
        }
        editor.suggestionsList.removeAllViews()
        (if (suggestionsExpanded) suggestions else suggestions.take(COLLAPSED_CREATION_COUNT)).forEach { suggestion ->
            val card = ItemTripBinding.inflate(layoutInflater, editor.suggestionsList, false)
            card.tripBadge.setText(if (suggestion.confidence == SuggestionConfidence.STRONG) R.string.strong_match else R.string.possible_match)
            card.tripTitle.text = tripSuggestionTitle(suggestion)
            val dayCount = suggestion.endDate.toEpochDay() - suggestion.startDate.toEpochDay() + 1
            card.tripDetails.text = getString(
                R.string.trip_suggestion_details,
                projectDateRange(suggestion.startDate, suggestion.endDate),
                dayCount,
            )
            showTripCoverage(card, coverageFor(suggestion.startDate, suggestion.endDate))
            card.tripPrimaryButton.setText(R.string.confirm_and_create)
            card.tripPrimaryButton.isEnabled = !tripDetectionRunning
            card.tripPrimaryButton.setOnClickListener { confirmSuggestion(suggestion) }
            card.tripSecondaryButton.visibility = View.VISIBLE
            card.tripSecondaryButton.setText(R.string.dismiss_suggestion)
            card.tripSecondaryButton.isEnabled = !tripDetectionRunning
            card.tripSecondaryButton.setOnClickListener {
                tripsStore.dismissSuggestion(suggestion.id)
                tripSuggestions = tripSuggestions.filterNot { it.id == suggestion.id }
                renderTripSuggestions()
            }
            editor.suggestionsList.addView(card.root)
        }
    }

    private fun addProjectCard(parent: android.widget.LinearLayout, project: TripProject, videos: List<VideoRecord>) {
        val card = ItemTripBinding.inflate(layoutInflater, parent, false)
        card.tripBadge.setText(when (project.kind) {
            TripKind.TRIP -> R.string.trip_badge
            TripKind.MONTHLY_RECAP -> R.string.monthly_recap_badge
            TripKind.YEARLY_RECAP -> R.string.yearly_recap_badge
            TripKind.CUSTOM_RECAP -> R.string.custom_recap_badge
            TripKind.RAW_DATA -> R.string.raw_data_badge
        })
        card.tripTitle.text = project.title
        card.tripDetails.text = "${projectDisplayRange(project)} · ${getString(R.string.videos_count, videos.size)}"
        if (project.kind == TripKind.TRIP) showTripCoverage(card, coverageFor(project.startDate, project.endDate))
        card.tripPrimaryButton.setText(R.string.create_video_for_trip)
        card.tripPrimaryButton.setOnClickListener { openProject(project) }
        card.tripMenuButton.visibility = View.VISIBLE
        card.tripMenuButton.setOnClickListener { anchor -> showProjectActions(anchor, project, videos) }
        videos.forEach { record ->
            val item = ItemVideoBinding.inflate(layoutInflater, card.tripVideosList, false)
            bindLibraryVideoCard(item, record) { available ->
                compactVideoSettings(record) + if (available) "" else "\n${getString(R.string.file_unavailable)}"
            }
            card.tripVideosList.addView(item.root)
        }
        card.root.setOnClickListener { openProject(project) }
        parent.addView(card.root)
    }

    private fun showProjectActions(anchor: View, project: TripProject, videos: List<VideoRecord>) {
        PopupMenu(this, anchor).apply {
            menu.add(0, PROJECT_ACTION_EDIT, 0, R.string.edit_project)
            menu.add(0, PROJECT_ACTION_DELETE, 1, R.string.delete_project)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    PROJECT_ACTION_EDIT -> {
                        openProject(project)
                        editingProjectOnly = true
                        renderCreateStep()
                        true
                    }
                    PROJECT_ACTION_DELETE -> {
                        confirmDeleteProject(project, videos)
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    private fun confirmDeleteProject(project: TripProject, videos: List<VideoRecord>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_project_title, project.title))
            .setMessage(getString(R.string.delete_project_message, videos.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_project) { _, _ -> deleteProject(project, videos) }
            .show()
    }

    private fun deleteProject(project: TripProject, videos: List<VideoRecord>) {
        lifecycleScope.launch {
            val deleted = deleteMediaRecords(videos)
            videoLibraryViewModel.removeAll(deleted)
            releaseDeletedMedia(deleted)
            val failed = videos.filterNot { it in deleted }
            if (failed.isEmpty()) tripsStore.remove(project.id)
            renderVideos()
            renderTrips()
            Snackbar.make(
                binding.root,
                if (failed.isEmpty()) {
                    getString(R.string.project_deleted, deleted.size)
                } else {
                    getString(R.string.project_delete_partial, deleted.size, failed.size)
                },
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private suspend fun deleteMediaRecords(records: List<VideoRecord>): List<VideoRecord> = withContext(Dispatchers.IO) {
        records.filter { record ->
            val uri = record.uri.toUri()
            val removed = videoMedia.delete(uri)
            if (removed) {
                videoMedia.deleteThumbnail(uri)
                videoMedia.deleteOverview(uri)
            }
            removed
        }
    }

    private fun releaseDeletedMedia(records: List<VideoRecord>) {
        records.forEach { record ->
            val uri = record.uri.toUri()
            releaseUriAccess(uri)
            if (lastVideoUri == uri) lastVideoUri = null
        }
    }

    private fun coverageFor(start: LocalDate, end: LocalDate): TripCoverage? =
        if (BuildConfig.IS_JOURNAL_LAB) {
            val zone = ZoneId.systemDefault()
            val rangeLoaded = journalRouteCovers(
                start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant(),
            )
            activeJournalRoute?.takeIf { rangeLoaded }?.let { route ->
                TripCoverageCalculator.calculateConnected(route.connectedTimelines(), start, end)
            }
        } else {
            (renderTimeline ?: timeline)?.let { TripCoverageCalculator.calculate(it, start, end) }
        }

    private fun showTripCoverage(card: ItemTripBinding, coverage: TripCoverage?) {
        if (coverage == null) {
            card.tripCoverage.setText(R.string.trip_coverage_unavailable)
            card.tripCoverage.visibility = View.VISIBLE
            return
        }
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
        val unit = resolvedDistanceUnit()
        val metrics = getString(
            R.string.trip_coverage_format,
            number.format(unit.fromKilometers(coverage.recordedMovementKm)),
            unit.symbol,
            number.format(coverage.usablePointCount),
            number.format(coverage.activeDayCount),
        )
        val quality = getString(if (coverage.limited) R.string.trip_coverage_limited else R.string.trip_coverage_good)
        card.tripCoverage.text = "$metrics\n$quality"
        card.tripCoverage.visibility = View.VISIBLE
    }

    private fun addRecapCandidates(projects: List<TripProject>) {
        val loaded = timeline ?: return
        val zone = ZoneId.systemDefault()
        val latestAvailableMonth = loaded.points.maxOfOrNull { YearMonth.from(it.instant.atZone(zone)) } ?: return
        val currentMonth = YearMonth.now(zone)
        val latestMonth = if (latestAvailableMonth >= currentMonth) currentMonth.minusMonths(1) else latestAvailableMonth
        val monthlyStart = latestMonth.atDay(1)
        val monthlyEnd = latestMonth.atEndOfMonth()
        val monthlyHasData = loaded.forRange(TimelinePeriod(latestMonth, latestMonth)).points.isNotEmpty()
        if (monthlyHasData && projects.none { it.kind == TripKind.MONTHLY_RECAP && it.startDate == monthlyStart }) {
            addRecapCandidate(
                title = suggestedProjectTitle(TripKind.MONTHLY_RECAP, monthlyStart, monthlyEnd),
                start = monthlyStart,
                end = monthlyEnd,
                kind = TripKind.MONTHLY_RECAP,
            )
        }
        loaded.years.take(2).forEach { year ->
            val start = LocalDate.of(year, 1, 1)
            if (projects.none { it.kind == TripKind.YEARLY_RECAP && it.startDate == start }) {
                addRecapCandidate(
                    title = getString(R.string.year_recap_title, year),
                    start = start,
                    end = LocalDate.of(year, 12, 31),
                    kind = TripKind.YEARLY_RECAP,
                )
            }
        }
    }

    private fun addRecapCandidate(title: String, start: LocalDate, end: LocalDate, kind: TripKind) {
        val card = ItemTripBinding.inflate(layoutInflater, home.recapsList, false)
        card.tripBadge.setText(if (kind == TripKind.MONTHLY_RECAP) R.string.monthly_recap_badge else R.string.yearly_recap_badge)
        card.tripTitle.text = title
        card.tripDetails.text = projectDateRange(start, end)
        card.tripPrimaryButton.setText(R.string.create_video_for_trip)
        card.tripPrimaryButton.setOnClickListener {
            openProject(tripsStore.create(title, start, end, kind, ProjectTitleMode.AUTOMATIC))
        }
        home.recapsList.addView(card.root)
    }

    private fun confirmSuggestion(suggestion: TripSuggestion) {
        activeProjectId = null
        activeSuggestionId = suggestion.id
        activeProjectKind = TripKind.TRIP
        activeProjectTitleMode = ProjectTitleMode.AUTOMATIC
        activeStartDate = suggestion.startDate
        activeEndDate = suggestion.endDate
        val title = tripSuggestionTitle(suggestion)
        setProjectTitle(title)
        videoTitleUserEdited = false
        setAutomaticVideoTitle(title)
        currentCreateStep = CreateStep.PROJECT
        showNewVideo(loadRemembered = true)
        applyActiveProjectDates()
        prepareActiveProjectRoute()
    }

    internal fun tripSuggestionTitle(suggestion: TripSuggestion): String = suggestion.destinationName
        ?.takeIf(String::isNotBlank)
        ?.let { getString(R.string.trip_to_destination, it) }
        ?: getString(R.string.suggested_trip_title)

    private fun openProject(project: TripProject) {
        activeProjectId = project.id
        activeSuggestionId = null
        activeProjectKind = project.kind
        activeProjectTitleMode = project.titleMode
        activeStartDate = project.startDate
        activeEndDate = project.endDate
        setProjectTitle(project.title)
        videoTitleUserEdited = false
        setAutomaticVideoTitle(project.title)
        currentCreateStep = CreateStep.PROJECT
        modifiedBuiltInId = null
        activePresetId = null
        applyRecommendedPresetIfNeeded()
        editor.saveTripButton.isEnabled = true
        showNewVideo(loadRemembered = true)
        applyActiveProjectDates()
        prepareActiveProjectRoute()
    }

    private fun prepareActiveProjectRoute() {
        if (!BuildConfig.IS_JOURNAL_LAB) return
        val start = activeStartDate ?: return
        val end = activeEndDate ?: return
        loadJournalRouteForRange(start, end) { outcome ->
            if (outcome == JournalRouteLoadOutcome.READY) applyActiveProjectDates()
        }
    }

    private fun applyActiveProjectDates() {
        val start = activeStartDate ?: return
        val end = activeEndDate ?: return
        if (timeline == null) return
        rawSignalsEnabled = !BuildConfig.IS_JOURNAL_LAB && activeProjectKind == TripKind.RAW_DATA
        if (!rawSignalsEnabled) rawProjectRangeConflict = false
        if (rawSignalsEnabled) rebuildRawSignalsTimeline()
        exactDateRangeEnabled = activeProjectKind in setOf(TripKind.TRIP, TripKind.CUSTOM_RECAP, TripKind.RAW_DATA)
        selectedStartDate = start
        selectedEndDate = end
        selectedStartYear = start.year
        selectedEndYear = end.year
        selectedStartMonth = start.monthValue
        selectedEndMonth = end.monthValue
        endPeriodEnabled = when (activeProjectKind) {
            TripKind.YEARLY_RECAP -> start.year != end.year
            TripKind.MONTHLY_RECAP -> YearMonth.from(start) != YearMonth.from(end)
            else -> false
        }
        editor.exactDateSwitch.isChecked = exactDateRangeEnabled
        editor.rawSignalsSwitch.isChecked = rawSignalsEnabled
        updateYearDropdowns()
        editor.startMonthDropdown.setText(monthNames[selectedStartMonth - 1], false)
        editor.endMonthDropdown.setText(monthNames[selectedEndMonth - 1], false)
        updateExactDateControls()
        updateProjectPeriodControls()
        selectRange()
        updateProjectDateLabel()
        updateSuggestedProjectTitle()
        updateWizardContinueAvailability()
    }

    private fun updateProjectDateLabel() {
        val dates = currentProjectDates()
        val start = dates?.first ?: activeStartDate
        val end = dates?.second ?: activeEndDate
        editor.projectDateText.text = if (start != null && end != null) {
            projectDisplayRange(activeProjectKind, start, end)
        } else {
            ""
        }
        if (activeProjectKind == TripKind.TRIP && start != null && end != null) {
            val coverage = coverageFor(start, end)
            if (coverage == null) {
                editor.projectCoverageText.setText(R.string.trip_coverage_unavailable)
                editor.projectCoverageText.visibility = View.VISIBLE
                return
            }
            val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
            val unit = resolvedDistanceUnit()
            val metrics = getString(
                R.string.trip_coverage_format,
                number.format(unit.fromKilometers(coverage.recordedMovementKm)),
                unit.symbol,
                number.format(coverage.usablePointCount),
                number.format(coverage.activeDayCount),
            )
            editor.projectCoverageText.text = if (coverage.limited) {
                "$metrics\n${getString(R.string.trip_coverage_warning)}"
            } else {
                "$metrics\n${getString(R.string.trip_coverage_good)}"
            }
            editor.projectCoverageText.visibility = View.VISIBLE
        } else {
            editor.projectCoverageText.visibility = View.GONE
        }
    }

    private fun onProjectPeriodChanged(updateSelection: Boolean = true) {
        currentProjectDates()?.let { (start, end) ->
            activeStartDate = start
            activeEndDate = end
        }
        updateSuggestedProjectTitle()
        updateProjectDateLabel()
        updateProjectPeriodControls()
        if (updateSelection) selectRange()
    }

    private fun setProjectTitle(value: String) {
        updatingProjectTitle = true
        editor.projectTitleInput.setText(value)
        updatingProjectTitle = false
        editor.resetSuggestedTitleButton.visibility =
            if (activeProjectKind != TripKind.TRIP && activeProjectTitleMode == ProjectTitleMode.CUSTOM) View.VISIBLE else View.GONE
    }

    private fun updateSuggestedProjectTitle(force: Boolean = false) {
        if (!force && activeProjectTitleMode != ProjectTitleMode.AUTOMATIC) return
        val (start, end) = currentProjectDates() ?: return
        activeProjectTitleMode = ProjectTitleMode.AUTOMATIC
        val title = suggestedProjectTitle(activeProjectKind, start, end)
        setProjectTitle(title)
        editor.resetSuggestedTitleButton.visibility = View.GONE
        if (!videoTitleUserEdited) setAutomaticVideoTitle(title)
    }

    internal fun suggestedProjectTitle(kind: TripKind, start: LocalDate, end: LocalDate): String = when (kind) {
        TripKind.TRIP -> editor.projectTitleInput.text?.toString().orEmpty().ifBlank { getString(R.string.new_trip) }
        TripKind.YEARLY_RECAP -> if (start.year == end.year) {
            getString(R.string.year_recap_title, start.year)
        } else {
            getString(R.string.year_recap_range_title, start.year, end.year)
        }
        TripKind.MONTHLY_RECAP -> when {
            YearMonth.from(start) == YearMonth.from(end) ->
                getString(R.string.month_recap_title, shortMonthNames[start.monthValue - 1], start.year)
            start.year == end.year -> getString(
                R.string.month_recap_same_year_title,
                shortMonthNames[start.monthValue - 1],
                shortMonthNames[end.monthValue - 1],
                start.year,
            )
            else -> getString(
                R.string.month_recap_cross_year_title,
                shortMonthNames[start.monthValue - 1],
                start.year,
                shortMonthNames[end.monthValue - 1],
                end.year,
            )
        }
        TripKind.CUSTOM_RECAP -> getString(
            R.string.custom_recap_title,
            formatExactDate(start),
            formatExactDate(end),
        )
        TripKind.RAW_DATA -> {
            val days = (ChronoUnit.DAYS.between(start, end) + 1L).coerceAtLeast(1L).toInt()
            resources.getQuantityString(R.plurals.recent_day_recap, days, days)
        }
    }

    internal fun isDateRangeWithinBounds(
        start: LocalDate,
        end: LocalDate,
        bounds: Pair<LocalDate, LocalDate>,
    ): Boolean = !start.isBefore(bounds.first) && !end.isAfter(bounds.second) && !end.isBefore(start)

    internal fun applyExactDateRange(start: LocalDate, end: LocalDate): Boolean {
        if (!isDateRangeWithinBounds(start, end, activeDateBounds())) return false
        rawProjectRangeConflict = false
        selectedStartDate = start
        selectedEndDate = end
        selectedStartYear = start.year
        selectedEndYear = end.year
        selectedStartMonth = start.monthValue
        selectedEndMonth = end.monthValue
        updateYearDropdowns()
        editor.startMonthDropdown.setText(monthNames[selectedStartMonth - 1], false)
        editor.endMonthDropdown.setText(monthNames[selectedEndMonth - 1], false)
        updateExactDateControls()
        updateResolvedTitle()
        selectRange()
        if (currentCreateStep == CreateStep.PROJECT) onProjectPeriodChanged()
        updateRawDataAvailability()
        return true
    }

    private fun updateProjectPeriodControls() {
        val monthly = activeProjectKind == TripKind.MONTHLY_RECAP
        val yearly = activeProjectKind == TripKind.YEARLY_RECAP
        val periodBased = monthly || yearly
        editor.periodDateControlsGroup.visibility = if (periodBased) View.VISIBLE else View.GONE
        editor.startYearLayout.visibility = if (periodBased) View.VISIBLE else View.GONE
        editor.startMonthLayout.visibility = if (monthly) View.VISIBLE else View.GONE
        editor.endYearLayout.visibility = if (periodBased && endPeriodEnabled) View.VISIBLE else View.GONE
        editor.endMonthLayout.visibility = if (monthly && endPeriodEnabled) View.VISIBLE else View.GONE
        editor.addEndPeriodButton.visibility = if (periodBased) View.VISIBLE else View.GONE
        editor.addEndPeriodButton.setText(
            when {
                endPeriodEnabled -> R.string.remove_end_period
                yearly -> R.string.add_end_year
                else -> R.string.add_end_month
            },
        )
        editor.exactDateSwitch.visibility = View.GONE
        editor.exactDateRangeButton.visibility = if (!periodBased) View.VISIBLE else View.GONE
        editor.rawSignalsSwitch.visibility = View.GONE
        editor.rawSignalsDescription.visibility =
            if (!BuildConfig.IS_JOURNAL_LAB && activeProjectKind == TripKind.RAW_DATA) View.VISIBLE else View.GONE
        updateExactDateControls()
        if (periodBased) editor.exactDateRangeButton.visibility = View.GONE
        updateRawDataAvailability()
    }

    private fun semanticDateBounds(): Pair<LocalDate, LocalDate>? {
        if (rawOnlyImport) return null
        val points = timeline?.points.orEmpty()
        val zone = ZoneId.systemDefault()
        if (points.isNotEmpty()) {
            val loadedStart = points.minOf { it.instant }
            val loadedEnd = points.maxOf { it.instant }
            val loadedBoundsCoverJournal = activeJournalRouteStart?.toEpochMilli() == Long.MIN_VALUE &&
                activeJournalRouteEndExclusive?.toEpochMilli() == Long.MAX_VALUE
            if (!BuildConfig.IS_JOURNAL_LAB || loadedBoundsCoverJournal) {
                return loadedStart.atZone(zone).toLocalDate() to loadedEnd.atZone(zone).toLocalDate()
            }
        }
        val status = activeJournalStatus ?: return null
        val startMillis = listOfNotNull(
            status.detailedStartEpochMillis,
            status.journal.semanticStartEpochMillis,
        ).minOrNull() ?: return null
        val endMillis = listOfNotNull(
            status.detailedEndEpochMillis,
            status.journal.semanticEndEpochMillis,
        ).maxOrNull() ?: return null
        return Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate() to
            Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
    }

    private fun rawDateBounds(): Pair<LocalDate, LocalDate>? {
        val points = renderRawSignalsTimeline?.points.orEmpty()
        if (points.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        return points.minOf { it.instant }.atZone(zone).toLocalDate() to
            points.maxOf { it.instant }.atZone(zone).toLocalDate()
    }

    private fun activeDateBounds(): Pair<LocalDate, LocalDate> =
        (if (!BuildConfig.IS_JOURNAL_LAB && activeProjectKind == TripKind.RAW_DATA) rawDateBounds() else semanticDateBounds())
            ?: ((selectedStartDate ?: LocalDate.now()) to (selectedEndDate ?: LocalDate.now()))

    private fun saveActiveProject(asNew: Boolean): TripProject? {
        val (start, end) = currentProjectDates() ?: return null
        val title = editor.projectTitleInput.text?.toString().orEmpty()
        val existing = if (!asNew) activeProjectId?.let { id -> tripsStore.list().firstOrNull { it.id == id } } else null
        val saved = if (existing == null) {
            tripsStore.create(title, start, end, activeProjectKind, activeProjectTitleMode)
        } else {
            existing.copy(
                title = title.trim().ifBlank { existing.title },
                startDate = start,
                endDate = end,
                titleMode = activeProjectTitleMode,
            ).also(tripsStore::upsert)
        }
        activeProjectId = saved.id
        activeStartDate = saved.startDate
        activeEndDate = saved.endDate
        editor.projectTitleInput.setText(saved.title)
        editor.saveTripButton.isEnabled = true
        updateProjectDateLabel()
        activeSuggestionId?.let { suggestionId ->
            tripSuggestions = tripSuggestions.filterNot { it.id == suggestionId }
            activeSuggestionId = null
        }
        if (!videoTitleUserEdited) setAutomaticVideoTitle(saved.title)
        renderTrips()
        Snackbar.make(binding.root, R.string.trip_saved, Snackbar.LENGTH_SHORT).show()
        return saved
    }

    private fun ensureActiveProject(): TripProject? {
        val existing = activeProjectId?.let { id -> tripsStore.list().firstOrNull { it.id == id } }
        return if (existing == null) saveActiveProject(asNew = true) else saveActiveProject(asNew = false)
    }

    private fun currentProjectDates(): Pair<LocalDate, LocalDate>? {
        if (activeProjectKind == TripKind.YEARLY_RECAP) {
            val start = selectedStartYear ?: return null
            val end = if (endPeriodEnabled) selectedEndYear ?: start else start
            return RecapPeriodRules.yearly(start, end)
        }
        if (activeProjectKind == TripKind.MONTHLY_RECAP) {
            val start = YearMonth.of(selectedStartYear ?: return null, selectedStartMonth)
            val end = if (endPeriodEnabled) {
                YearMonth.of(selectedEndYear ?: start.year, selectedEndMonth)
            } else {
                start
            }
            return RecapPeriodRules.monthly(start, end)
        }
        if (exactDateRangeEnabled) {
            val start = selectedStartDate ?: return null
            val end = selectedEndDate ?: return null
            return start to end
        }
        val period = currentPeriod() ?: return null
        return period.start.atDay(1) to period.endInclusive.atEndOfMonth()
    }

    private fun projectDateRange(start: LocalDate, end: LocalDate): String = getString(
        R.string.exact_date_range,
        formatExactDate(start),
        formatExactDate(end),
    )

    private fun projectDisplayRange(project: TripProject): String =
        projectDisplayRange(project.kind, project.startDate, project.endDate)

    private fun projectDisplayRange(kind: TripKind, start: LocalDate, end: LocalDate): String = when {
        kind == TripKind.TRIP -> projectDateRange(start, end)
        kind == TripKind.CUSTOM_RECAP || kind == TripKind.RAW_DATA -> projectDateRange(start, end)
        kind == TripKind.YEARLY_RECAP && start.year != end.year -> "${start.year}–${end.year}"
        kind == TripKind.MONTHLY_RECAP && YearMonth.from(start) != YearMonth.from(end) && start.year == end.year ->
            getString(
                R.string.period_same_year,
                monthNames[start.monthValue - 1],
                monthNames[end.monthValue - 1],
                start.year,
            )
        kind == TripKind.MONTHLY_RECAP && YearMonth.from(start) != YearMonth.from(end) -> getString(
            R.string.period_cross_year,
            monthNames[start.monthValue - 1],
            start.year,
            monthNames[end.monthValue - 1],
            end.year,
        )
        start.dayOfMonth == 1 && YearMonth.from(start) == YearMonth.from(end) && end == YearMonth.from(end).atEndOfMonth() ->
            "${monthNames[start.monthValue - 1]} ${start.year}"
        start == LocalDate.of(start.year, 1, 1) && end == LocalDate.of(start.year, 12, 31) ->
            NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }.format(start.year)
        else -> projectDateRange(start, end)
    }

    private fun renderVideos() {
        videoLibraryViewModel.refresh()
        val allRecords = videoLibraryViewModel.records.value
        val records = allRecords.filter { it.projectId == null }
        videoRenderJob?.cancel()
        videoCardJobs.forEach(Job::cancel)
        videoCardJobs.clear()
        home.unassignedVideosHeading.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        home.emptyVideosText.visibility = View.GONE
        home.showAllVideosButton.visibility = if (records.size > COLLAPSED_CREATION_COUNT) View.VISIBLE else View.GONE
        home.deleteAllVideosButton.visibility =
            if (allRecords.isEmpty() && tripsStore.list().isEmpty()) View.GONE else View.VISIBLE
        home.showAllVideosButton.text = if (videosExpanded) {
            getString(R.string.show_fewer_videos)
        } else {
            getString(R.string.show_all_videos, records.size)
        }
        home.videosList.removeAllViews()
        val visibleRecords = if (videosExpanded) records else records.take(COLLAPSED_CREATION_COUNT)
        videoRenderJob = lifecycleScope.launch {
            visibleRecords.forEach { record ->
                val item = ItemVideoBinding.inflate(layoutInflater, home.videosList, false)
                bindLibraryVideoCard(item, record) { available -> videoDetails(record, available) }
                home.videosList.addView(item.root)
            }
        }
    }

    private fun bindLibraryVideoCard(
        item: ItemVideoBinding,
        record: VideoRecord,
        details: (Boolean) -> String,
    ) {
        val uri = record.uri.toUri()
        item.root.tag = record.uri
        item.videoTitle.text = record.title
        item.videoDetails.text = details(true)
        item.videoWatchButton.isEnabled = false
        item.videoShareButton.isEnabled = false
        item.root.isClickable = false
        item.videoWatchButton.setOnClickListener { watchVideo(uri) }
        item.videoShareButton.setOnClickListener { shareVideo(uri) }
        item.videoMoreButton.setOnClickListener { showVideoActions(record) }
        item.root.setOnClickListener { watchVideo(uri) }
        videoCardJobs += lifecycleScope.launch {
            var thumbnail: android.graphics.Bitmap? = null
            var applied = false
            try {
                val available = withContext(Dispatchers.IO) {
                    videoMedia.isAvailable(uri).also { mediaAvailable ->
                        if (mediaAvailable) thumbnail = runCatching { videoMedia.createThumbnail(uri) }.getOrNull()
                    }
                }
                if (item.root.tag != record.uri) return@launch
                item.videoDetails.text = details(available)
                item.videoWatchButton.isEnabled = available
                item.videoShareButton.isEnabled = available
                item.root.isClickable = available
                thumbnail?.let { bitmap ->
                    item.videoThumbnail.setPadding(0, 0, 0, 0)
                    item.videoThumbnail.setImageBitmap(bitmap)
                }
                applied = true
            } finally {
                if (!applied) thumbnail?.recycle()
            }
        }
    }

    private fun videoDetails(record: VideoRecord, available: Boolean): String {
        val parts = mutableListOf<String>()
        if (
            record.startYear != null && record.startMonth != null &&
            record.endYear != null && record.endMonth != null
        ) {
            parts += if (
                record.startYear == record.endYear &&
                record.startMonth == 1 && record.endMonth == 12
            ) {
                record.startYear.toString()
            } else if (record.startYear == record.endYear) {
                getString(
                    R.string.period_same_year,
                    monthNames[record.startMonth - 1],
                    monthNames[record.endMonth - 1],
                    record.startYear,
                )
            } else {
                getString(
                    R.string.period_cross_year,
                    monthNames[record.startMonth - 1],
                    record.startYear,
                    monthNames[record.endMonth - 1],
                    record.endYear,
                )
            }
        }
        parts += DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(record.createdAtMillis))
        if (!available) parts += getString(R.string.file_unavailable)
        return parts.joinToString(" · ")
    }

    private fun formatVideoDuration(seconds: Int): String {
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val remainingSeconds = seconds % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainingSeconds)
        else String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    private fun showVideoActions(record: VideoRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(record.title)
            .setItems(
                arrayOf(
                    getString(R.string.watch),
                    getString(R.string.share),
                    getString(R.string.settings_details),
                    getString(R.string.delete_video),
                ),
            ) { _, which ->
                when (which) {
                    0 -> watchVideo(record.uri.toUri())
                    1 -> shareVideo(record.uri.toUri())
                    2 -> showVideoSettingsDetails(record)
                    3 -> confirmDeleteVideo(record)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun compactVideoSettings(record: VideoRecord): String {
        val snapshot = record.settingsSnapshot
            ?: return listOfNotNull(
                record.presetName,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(record.createdAtMillis)),
            ).joinToString(" · ")
        val firstLine = listOfNotNull(
            snapshot.presetName ?: getString(R.string.preset_custom),
            aspectRatioLabel(snapshot.aspectRatio),
            resources.getQuantityString(
                R.plurals.duration_seconds,
                snapshot.requestedDurationSeconds,
                snapshot.requestedDurationSeconds,
            ),
        ).joinToString(" · ")
        return firstLine
    }

    private fun showVideoSettingsDetails(record: VideoRecord) {
        val snapshot = record.settingsSnapshot
        val message = if (snapshot == null) {
            record.presetName ?: getString(R.string.settings_unavailable)
        } else {
            presetValueSummary(
                PresetValues(
                    snapshot.aspectRatio,
                    snapshot.cameraMovement,
                    snapshot.tripDetection,
                    snapshot.localFraming,
                    snapshot.longTripCompression,
                    snapshot.requestedDurationSeconds,
                ),
            ) + "\n" + getString(
                R.string.preset_value_format,
                getString(R.string.resolution),
                snapshotResolutionLabel(snapshot),
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_details)
            .setMessage(message)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    internal fun currentVideoSettingsSummary(): String = compactVideoSettings(
        VideoRecord(
            uri = "",
            title = "",
            fileName = "",
            createdAtMillis = System.currentTimeMillis(),
            durationSeconds = 0,
            presetName = selectedPreset()?.name,
            settingsSnapshot = VideoSettingsSnapshot(
                presetName = selectedPreset()?.name,
                requestedDurationSeconds = routeDurationSeconds,
                aspectRatio = cameraSettings.videoQuality.aspectRatioOption,
                cameraMovement = cameraSettings.cameraMovement,
                tripDetection = cameraSettings.tripDetection,
                localFraming = cameraSettings.localFraming,
                longTripCompression = cameraSettings.longTripCompression,
                resolution = cameraSettings.videoQuality.resolution,
                dataSource = currentVideoDataSource(),
                exportShortEdge = cameraSettings.effectiveExportFormat.shortEdge,
                exportFrameRate = cameraSettings.effectiveExportFormat.frameRate,
            ),
        ),
    )

    private fun aspectRatioLabel(value: VideoAspectRatio): String = getString(
        listOf(R.string.aspect_square, R.string.aspect_portrait, R.string.aspect_landscape)[value.ordinal],
    )

    private fun cameraMovementLabel(value: CameraMovement): String =
        getString(mapViewLabelResources[value.ordinal])

    private fun CameraSettings.withAutomaticMapView(movement: CameraMovement): CameraSettings = copy(
        cameraMovement = movement,
        tripDetection = when (movement) {
            CameraMovement.FIXED, CameraMovement.STEADY -> TripDetection.BALANCED
            CameraMovement.DYNAMIC -> TripDetection.BALANCED
            CameraMovement.CLOSE_UP -> TripDetection.SENSITIVE
        },
        localFraming = when (movement) {
            CameraMovement.FIXED, CameraMovement.STEADY -> LocalFraming.OFF
            CameraMovement.DYNAMIC -> LocalFraming.BALANCED
            CameraMovement.CLOSE_UP -> LocalFraming.CLOSE
        },
        longTripCompression = LongTripCompression.BALANCED,
    )

    private fun tripDetectionLabel(value: TripDetection): String = getString(
        listOf(R.string.trip_detection_conservative, R.string.trip_detection_balanced, R.string.trip_detection_sensitive)[value.ordinal],
    )

    private fun localFramingLabel(value: LocalFraming): String = getString(
        listOf(R.string.local_framing_off, R.string.local_framing_balanced, R.string.local_framing_close)[value.ordinal],
    )

    private fun compressionLabel(value: LongTripCompression): String = getString(
        listOf(R.string.compression_off, R.string.compression_balanced, R.string.compression_strong, R.string.compression_stronger)[value.ordinal],
    )

    private val mapViewLabelResources = listOf(
        R.string.map_view_fixed,
        R.string.map_view_wide,
        R.string.map_view_balanced,
        R.string.map_view_close_up,
    )

    private fun resolutionLabel(value: VideoResolution): String = getString(
        listOf(R.string.resolution_480, R.string.resolution_720, R.string.resolution_1080)[value.ordinal],
    )

    private fun snapshotResolutionLabel(snapshot: VideoSettingsSnapshot): String {
        val shortEdge = snapshot.exportShortEdge ?: return resolutionLabel(snapshot.resolution)
        val frameRate = snapshot.exportFrameRate ?: ExportFormatSettings.DEFAULT_FRAME_RATE
        val format = ExportFormatSettings(shortEdge, frameRate).format(snapshot.aspectRatio)
        val dimensions = if (ExportResolution.entries.any { it.shortEdge == shortEdge }) {
            getString(R.string.preset_resolution_selected, shortEdge, format.width, format.height)
        } else {
            getString(R.string.custom_resolution_selected, format.width, format.height)
        }
        return snapshot.exportFrameRate?.let {
            "$dimensions · ${getString(R.string.frame_rate_value, it)}"
        } ?: dimensions
    }

    private fun removeVideo(record: VideoRecord) {
        val uri = record.uri.toUri()
        videoLibraryViewModel.remove(record)
        videoMedia.deleteThumbnail(uri)
        renderVideos()
        renderTrips()
        var restored = false
        Snackbar.make(binding.root, R.string.video_removed, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                restored = true
                videoLibraryViewModel.upsert(record)
                renderVideos()
                renderTrips()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (!restored) releaseUriAccess(uri)
                }
            })
            .show()
    }

    private fun confirmDeleteVideo(record: VideoRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_video_title)
            .setMessage(R.string.delete_video_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteVideo(record) }
            .show()
    }

    private fun confirmDeleteAllLibraryContent() {
        val records = videoLibraryViewModel.records.value
        val projects = tripsStore.list()
        if (records.isEmpty() && projects.isEmpty()) return
        val projectCount = resources.getQuantityString(
            R.plurals.library_project_count,
            projects.size,
            projects.size,
        )
        val videoCount = resources.getQuantityString(
            R.plurals.library_video_count,
            records.size,
            records.size,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_all_library_title)
            .setMessage(getString(R.string.delete_all_library_message, projectCount, videoCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_all_library_content) { _, _ ->
                deleteAllLibraryContent(projects, records)
            }
            .show()
    }

    private fun deleteAllLibraryContent(projects: List<TripProject>, records: List<VideoRecord>) {
        home.deleteAllVideosButton.isEnabled = false
        lifecycleScope.launch {
            val deleted = deleteMediaRecords(records)
            videoLibraryViewModel.removeAll(deleted)
            releaseDeletedMedia(deleted)
            val failed = records.filterNot { it in deleted }
            val failedProjectIds = failed.mapNotNullTo(mutableSetOf(), VideoRecord::projectId)
            tripsStore.removeAll(projects.mapNotNullTo(mutableSetOf()) { project ->
                project.id.takeIf { it !in failedProjectIds }
            })
            renderVideos()
            renderTrips()
            home.deleteAllVideosButton.isEnabled = true
            Snackbar.make(
                binding.root,
                if (failed.isEmpty()) {
                    getString(R.string.library_deleted)
                } else {
                    getString(R.string.library_delete_partial, deleted.size, failed.size)
                },
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun deleteVideo(record: VideoRecord) {
        lifecycleScope.launch {
            val uri = record.uri.toUri()
            val deleted = withContext(Dispatchers.IO) { videoMedia.delete(uri) }
            if (deleted) {
                videoLibraryViewModel.remove(record)
                videoMedia.deleteThumbnail(uri)
                videoMedia.deleteOverview(uri)
                releaseUriAccess(uri)
                if (lastVideoUri == uri) {
                    lastVideoUri = null
                    editor.videoReadyGroup.visibility = View.GONE
                }
                renderVideos()
                renderTrips()
                Snackbar.make(binding.root, R.string.video_deleted, Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(binding.root, R.string.delete_video_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun persistUriAccess(uri: Uri, includeWrite: Boolean): Boolean {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val requested = read or if (includeWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(uri, requested)
        }.isSuccess
        if (!persisted && requested != read) {
            return runCatching { contentResolver.takePersistableUriPermission(uri, read) }.isSuccess
        }
        return persisted
    }

    private fun releaseUriAccess(uri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        listOf(read or write, read, write).forEach { flags ->
            runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
        }
    }

    private fun requestTimelineImport(uri: Uri? = null) {
        if (BuildConfig.IS_JOURNAL_LAB && currentScreen != Screen.SETTINGS) {
            showJournalSetup(returnToCreate = true)
        }
        val continueImport = {
            if (uri != null) {
                importTimeline(uri)
            } else {
                openTimeline.launch(arrayOf("application/json", "text/json", "text/plain"))
            }
        }
        if (preferences.getBoolean(MAP_PRIVACY_ACCEPTED, false)) {
            continueImport()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_privacy_title)
            .setMessage(R.string.map_privacy_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.privacy_policy) { _, _ -> openPrivacyPolicy() }
            .setPositiveButton(R.string.continue_action) { _, _ ->
                preferences.edit { putBoolean(MAP_PRIVACY_ACCEPTED, true) }
                continueImport()
            }
            .show()
    }

    private fun openUpdates() {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.UPDATE_URL.toUri()))
        }.isSuccess
        if (!opened && BuildConfig.UPDATE_FALLBACK_URL != BuildConfig.UPDATE_URL) {
            openWebPage(BuildConfig.UPDATE_FALLBACK_URL, R.string.update_page_unavailable)
        } else if (!opened) {
            Snackbar.make(binding.root, R.string.update_page_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun openPrivacyPolicy() {
        val language = resources.configuration.locales[0]?.language
        val url = when (language) {
            Locale.KOREAN.language -> PRIVACY_URL_KO
            Locale.JAPANESE.language -> PRIVACY_URL_JA
            else -> PRIVACY_URL
        }
        openWebPage(url, R.string.web_page_unavailable)
    }

    private fun openRestoreGuide() {
        openWebPage(
            restoreGuideUrl(resources.configuration.locales[0]?.language),
            R.string.web_page_unavailable,
        )
    }

    private fun openWebPage(url: String, errorMessage: Int) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.onFailure {
            Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun watchVideo(uri: Uri) {
        showVideoPlayer(uri)
        acknowledgeCompletedExport(uri)
    }

    private fun openExternalVideoPlayer(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            clipData = ClipData.newRawUri(getString(R.string.timeline_video), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Snackbar.make(binding.root, R.string.no_video_player, Snackbar.LENGTH_LONG).show() }
    }

    private fun shareVideo(uri: Uri) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(getString(R.string.timeline_video), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.share_travel_video)))
        acknowledgeCompletedExport(uri)
    }

    private fun acknowledgeCompletedExport(uri: Uri? = null) {
        val snapshot = videoExportViewModel.current
        if (
            snapshot.status == VideoExportStatus.COMPLETE &&
            (uri == null || snapshot.outputUri == uri.toString())
        ) {
            videoExportViewModel.clear()
        }
    }

    private fun chooseVideoCopyDestination(source: Uri) {
        pendingVideoCopyUri = source
        val record = videoLibraryViewModel.records.value.firstOrNull { it.uri == source.toString() }
        copyCompletedVideo.launch(record?.fileName ?: getString(R.string.default_video_filename))
    }

    private fun copyCompletedVideo(source: Uri, destination: Uri) {
        editor.saveAsButton.isEnabled = false
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { generatedMedia.copyVideo(source, destination) }.isSuccess
            }
            editor.saveAsButton.isEnabled = true
            Snackbar.make(
                binding.root,
                if (saved) R.string.video_copy_saved else R.string.save_as_failed,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun chooseOverviewDestination(videoUri: Uri) {
        if (videoMedia.cachedOverview(videoUri) == null) {
            Snackbar.make(binding.root, R.string.overview_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        val title = lastVideoTitle.orEmpty().ifBlank { getString(R.string.default_title) }
        val periodSuffix = videoLibraryViewModel.records.value
            .firstOrNull { it.uri == videoUri.toString() }
            ?.let(::periodFileSuffix)
        val baseName = listOfNotNull(title, periodSuffix).joinToString("-")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val source = videoMedia.cachedOverview(videoUri) ?: return
            editor.saveOverviewButton.isEnabled = false
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { generatedMedia.saveOverview(source, baseName) }.isSuccess
                }
                editor.saveOverviewButton.isEnabled = videoMedia.cachedOverview(videoUri) != null
                Snackbar.make(
                    binding.root,
                    if (saved) R.string.overview_saved else R.string.overview_save_failed,
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        } else {
            pendingOverviewVideoUri = videoUri
            createOverviewImage.launch(getString(R.string.overview_file_name, baseName))
        }
    }

    private fun copyOverviewImage(videoUri: Uri, destination: Uri) {
        editor.saveOverviewButton.isEnabled = false
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(destination, "w")?.use { output ->
                        check(videoMedia.copyOverview(videoUri, output))
                    } ?: error("Overview destination is unavailable")
                }.isSuccess
            }
            editor.saveOverviewButton.isEnabled = videoMedia.cachedOverview(videoUri) != null
            Snackbar.make(
                binding.root,
                if (saved) R.string.overview_saved else R.string.overview_save_failed,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun shareOverviewImage(videoUri: Uri) {
        val file = videoMedia.cachedOverview(videoUri)
        if (file == null) {
            Snackbar.make(binding.root, R.string.overview_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(getString(R.string.overview_image), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.share_overview_image)))
        }.onFailure { error ->
            Log.e(TAG, "Could not share overview image", error)
            Snackbar.make(binding.root, R.string.overview_share_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun currentRenderText(): RenderText {
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val distanceUnit = resolvedDistanceUnit()
        return RenderText(
            localeTag = locale.toLanguageTag(),
            fallbackTitle = getString(R.string.default_title),
            datePattern = getString(R.string.render_date_pattern),
            distanceUnit = distanceUnit.symbol,
            attribution = getString(R.string.map_attribution),
            distanceScale = distanceUnit.kilometersMultiplier,
        )
    }

    private fun periodFileSuffix(period: TimelinePeriod): String = listOf(
        "${period.startYear}-${period.startMonth.toString().padStart(2, '0')}",
        "${period.endYear}-${period.endMonth.toString().padStart(2, '0')}",
    ).joinToString("_")

    private fun periodFileSuffix(record: VideoRecord): String? {
        val startYear = record.startYear ?: return null
        val startMonth = record.startMonth ?: return null
        val endYear = record.endYear ?: return null
        val endMonth = record.endMonth ?: return null
        return listOf(
            "$startYear-${startMonth.toString().padStart(2, '0')}",
            "$endYear-${endMonth.toString().padStart(2, '0')}",
        ).joinToString("_")
    }

    private fun showExportHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_help_title)
            .setMessage(R.string.export_help_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.open_location_settings) { _, _ ->
                val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                runCatching { startActivity(settingsIntent) }
                    .onFailure {
                        Snackbar.make(binding.root, R.string.location_settings_unavailable, Snackbar.LENGTH_LONG).show()
                    }
            }
            .show()
    }

    internal fun selectedDurationSeconds(): Int = routeDurationSeconds

    internal fun pendingExportDurationSeconds(): Int? = pendingExport?.durationSeconds

    internal fun currentJourneyPoints(): List<GeoPoint> = journey?.points.orEmpty()

    internal fun currentJourneyBreakIndices(): List<Int> = journey?.breakBeforePointIndices.orEmpty()

    internal fun journalMetadataReady(): Boolean = activeJournalStatus != null

    internal fun journalRouteReady(): Boolean = activeJournalRoute != null

    internal fun currentJourneyDistanceKm(): Double = journey?.knownDistanceKm ?: 0.0

    internal fun prepareJournalRangeForTest(start: LocalDate, end: LocalDate) {
        loadJournalRouteForRange(start, end)
    }

    internal fun journalRouteCoversForTest(start: LocalDate, end: LocalDate): Boolean {
        val zone = ZoneId.systemDefault()
        return journalRouteCovers(
            start.atStartOfDay(zone).toInstant(),
            end.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    internal fun confirmSuggestionForTest(suggestion: TripSuggestion) {
        confirmSuggestion(suggestion)
    }

    internal fun startManualProjectForTest(kind: TripKind) {
        startManualProject(kind)
    }

    internal fun openProjectForTest(project: TripProject) {
        openProject(project)
    }

    internal fun selectProjectDatesForTest(start: LocalDate, end: LocalDate) {
        activeStartDate = start
        activeEndDate = end
        exactDateRangeEnabled = true
        selectedStartDate = start
        selectedEndDate = end
        selectRange()
        updateWizardContinueAvailability()
    }

    internal fun continueCreateForTest() {
        moveCreateStep(1)
    }

    internal fun installTripSuggestionsForTest(suggestions: List<TripSuggestion>) {
        tripSuggestions = suggestions
        renderTripSuggestions()
    }

    internal fun tripSuggestionCountForTest(): Int = tripSuggestions.size

    internal fun currentVideoDataSource(): VideoDataSource = when {
        BuildConfig.IS_JOURNAL_LAB -> VideoDataSource.JOURNAL
        rawSignalsEnabled -> VideoDataSource.RAW
        else -> VideoDataSource.SEMANTIC
    }

    internal fun installedVersionLabel(): String =
        getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    private fun currentApplicationLanguageTags(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
        }

    private fun makeDropdownOpenReliably(dropdown: AutoCompleteTextView) {
        dropdown.threshold = 0
        dropdown.setOnClickListener { dropdown.showDropDown() }
    }

    private enum class Screen { VIDEOS, NEW_VIDEO, SETTINGS, PLAYER, ONBOARDING }
    private enum class CreateStep { TYPE, TRIP_SOURCE, DISCOVERY, PROJECT, STYLE, PREVIEW }
    private enum class JournalRouteLoadOutcome { READY, FAILED, CANCELLED }

    private data class JournalRouteRequest(
        val id: Long,
        val start: Instant,
        val endExclusive: Instant,
        val callbacks: MutableList<(JournalRouteLoadOutcome) -> Unit>,
    )

    private data class PreparedTimeline(
        val source: Timeline,
        val render: Timeline,
        val initialJourney: Journey,
        val ignoredCount: Int,
    )

    companion object {
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val GOOGLE_TIMELINE_HELP_URL =
            "https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DAndroid"
        private const val TITLE_UPDATE_DELAY_MS = 450L
        private const val COLLAPSED_CREATION_COUNT = 3
        private const val MAP_PRIVACY_ACCEPTED = "map_privacy_accepted_v1"
        private const val NOTIFICATION_PROMPTED = "notification_prompted_v1"
        private const val STATE_SCREEN = "screen_v1"
        private const val STATE_PLAYER_URI = "player_uri_v1"
        private const val STATE_PLAYER_POSITION = "player_position_v1"
        private const val STATE_PLAYER_PLAYING = "player_playing_v1"
        private const val STATE_DRAFT_CAMERA = "draft_camera_v1"
        private const val STATE_DRAFT_PACING = "draft_pacing_v1"
        private const val STATE_DRAFT_QUALITY = "draft_quality_v1"
        private const val STATE_DRAFT_EXPORT_SHORT_EDGE = "draft_export_short_edge_v1"
        private const val STATE_DRAFT_EXPORT_FRAME_RATE = "draft_export_frame_rate_v1"
        private const val STATE_DRAFT_CUSTOM_RESOLUTION = "draft_custom_resolution_v1"
        private const val STATE_DRAFT_CUSTOM_FRAME_RATE = "draft_custom_frame_rate_v1"
        private const val STATE_DRAFT_TRIP_DETECTION = "draft_trip_detection_v1"
        private const val STATE_DRAFT_LOCAL_FRAMING = "draft_local_framing_v1"
        private const val STATE_ACTIVE_PRESET_ID = "active_preset_id_v1"
        private const val STATE_PRESET_ORIGIN_ID = "preset_origin_id_v5"
        private const val STATE_MODIFIED_BUILT_IN_ID = "modified_built_in_id_v2"
        private const val STATE_CREATE_STEP = "create_step_v2"
        private const val STATE_ACTIVE_PROJECT_ID = "active_project_id_v2"
        private const val STATE_ACTIVE_SUGGESTION_ID = "active_suggestion_id_v2"
        private const val STATE_ACTIVE_PROJECT_KIND = "active_project_kind_v2"
        private const val STATE_ACTIVE_START_DATE = "active_start_date_v2"
        private const val STATE_ACTIVE_END_DATE = "active_end_date_v2"
        private const val STATE_ACTIVE_TITLE_MODE = "active_title_mode_v4"
        private const val STATE_END_PERIOD_ENABLED = "end_period_enabled_v4"
        private const val STATE_EDITING_PROJECT_ONLY = "editing_project_only_v4"
        private const val PROJECT_ACTION_EDIT = 1
        private const val PROJECT_ACTION_DELETE = 2
        private const val STATE_VIDEO_TITLE_EDITED = "video_title_edited_v2"
        private const val STATE_DRAFT_DURATION = "draft_duration_v2"
        private const val STATE_SETTINGS_RETURN_TO_CREATE = "settings_return_to_create_v3"
        private const val STATE_JOURNAL_SETUP_MODE = "journal_setup_mode_v1"
        private const val STATE_JOURNAL_SETUP_RETURN_TO_CREATE = "journal_setup_return_to_create_v1"
        private const val STATE_PENDING_JOURNAL_REMINDER_ID = "pending_journal_reminder_id_v1"
        private const val STATE_JOURNAL_ONBOARDING_PAGE = "journal_onboarding_page_v1"
        private const val STATE_JOURNAL_ONBOARDING_REPLAY = "journal_onboarding_replay_v1"
        private const val STATE_CUSTOMIZATION_CAMERA = "customization_camera_v3"
        private const val STATE_CUSTOMIZATION_PACING = "customization_pacing_v3"
        private const val STATE_CUSTOMIZATION_QUALITY = "customization_quality_v3"
        private const val STATE_CUSTOMIZATION_TRIP_DETECTION = "customization_trip_detection_v3"
        private const val STATE_CUSTOMIZATION_LOCAL_FRAMING = "customization_local_framing_v3"
        private const val STATE_CUSTOMIZATION_PRESET_ID = "customization_preset_id_v3"
        private const val STATE_CUSTOMIZATION_MODIFIED_ID = "customization_modified_id_v3"
        internal const val ACTION_WATCH_VIDEO = "dev.mahlernim.timelinevisualizer.action.WATCH_VIDEO"
        internal const val ACTION_SHARE_VIDEO = "dev.mahlernim.timelinevisualizer.action.SHARE_VIDEO"
        const val ACTION_OPEN_JOURNAL = "dev.mahlernim.timelinevisualizer.action.OPEN_JOURNAL"
        private const val PROJECT_URL = "https://github.com/mahlernim/google-timeline-visualizer"
        private const val PRIVACY_URL =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.md"
        private const val PRIVACY_URL_KO =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.ko.md"
        private const val PRIVACY_URL_JA =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.ja.md"
        private const val RESTORE_GUIDE_URL =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/restore-google-maps-timeline.md"
        private const val RESTORE_GUIDE_URL_KO =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/restore-google-maps-timeline.ko.md"
        private const val RESTORE_GUIDE_URL_JA =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/restore-google-maps-timeline.ja.md"
        private const val TAG = "TimelineVisualizer"
        private const val JOURNAL_PROGRESS_MAX = 1_000
        private const val JOURNAL_ROUTE_PROGRESS_DELAY_MILLIS = 2_000L
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

        internal fun playbackIntent(context: Context, uri: Uri): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_WATCH_VIDEO
                data = uri
                clipData = ClipData.newRawUri(context.getString(R.string.timeline_video), uri)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }

        internal fun shareIntent(context: Context, uri: Uri): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_SHARE_VIDEO
                data = uri
                clipData = ClipData.newRawUri(context.getString(R.string.timeline_video), uri)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }

        internal fun restoreGuideUrl(language: String?): String = when (language) {
            Locale.KOREAN.language -> RESTORE_GUIDE_URL_KO
            Locale.JAPANESE.language -> RESTORE_GUIDE_URL_JA
            else -> RESTORE_GUIDE_URL
        }
    }
}
