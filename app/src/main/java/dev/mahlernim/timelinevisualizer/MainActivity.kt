package dev.mahlernim.timelinevisualizer

import android.animation.ValueAnimator
import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.Insets
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.mahlernim.timelinevisualizer.data.LocationFilterMode
import dev.mahlernim.timelinevisualizer.data.LocationOutlierFilter
import dev.mahlernim.timelinevisualizer.data.TimelineParseException
import dev.mahlernim.timelinevisualizer.data.TimelineParseReason
import dev.mahlernim.timelinevisualizer.data.TimelineParser
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.databinding.ActivityMainBinding
import dev.mahlernim.timelinevisualizer.databinding.ItemVideoBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenNewVideoBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenPlayerBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenSettingsBinding
import dev.mahlernim.timelinevisualizer.databinding.ScreenVideosBinding
import dev.mahlernim.timelinevisualizer.export.ExportProgress
import dev.mahlernim.timelinevisualizer.export.ExportPhase
import dev.mahlernim.timelinevisualizer.export.VideoExportCoordinator
import dev.mahlernim.timelinevisualizer.export.VideoExportRequest
import dev.mahlernim.timelinevisualizer.export.VideoExportRequestStore
import dev.mahlernim.timelinevisualizer.export.VideoExportService
import dev.mahlernim.timelinevisualizer.export.VideoExportSnapshot
import dev.mahlernim.timelinevisualizer.export.VideoExportStatus
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.Timeline
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import dev.mahlernim.timelinevisualizer.model.TitleTemplate
import dev.mahlernim.timelinevisualizer.model.VideoDuration
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import dev.mahlernim.timelinevisualizer.render.RenderText
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import dev.mahlernim.timelinevisualizer.ui.CameraSettingsPreferences
import dev.mahlernim.timelinevisualizer.ui.LocationFilterPreferences
import dev.mahlernim.timelinevisualizer.videos.GeneratedMediaRepository
import dev.mahlernim.timelinevisualizer.videos.VideoMedia
import dev.mahlernim.timelinevisualizer.videos.VideoRecord
import dev.mahlernim.timelinevisualizer.videos.VideoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormatSymbols
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.time.YearMonth
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.core.util.Pair as AndroidPair
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var home: ScreenVideosBinding
    private lateinit var editor: ScreenNewVideoBinding
    private lateinit var settingsScreen: ScreenSettingsBinding
    private lateinit var playerScreen: ScreenPlayerBinding
    private var timeline: Timeline? = null
    private var renderTimeline: Timeline? = null
    private var journey: Journey? = null
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
    private val preferences by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private val videoStore by lazy { VideoStore(applicationContext) }
    private val videoMedia by lazy { VideoMedia(applicationContext) }
    private val generatedMedia by lazy { GeneratedMediaRepository(applicationContext) }
    private val timelineSourceStore by lazy { TimelineSourceStore(applicationContext) }
    private val cameraSettingsPreferences by lazy { CameraSettingsPreferences(applicationContext) }
    private val locationFilterPreferences by lazy { LocationFilterPreferences(applicationContext) }
    private var cameraSettings = CameraSettings.DEFAULT
    private var locationFilterMode = LocationFilterMode.CONSERVATIVE
    private var routeDurationSeconds = VideoDuration.DEFAULT_SECONDS
    private val applyTitleChanges = Runnable { commitTitlePreferences() }
    private var videoRenderGeneration = 0
    private var videosExpanded = false
    private var currentScreen = Screen.VIDEOS
    private var rememberedTimelineLoaded = false
    private var lastRenderedExportStatus = VideoExportStatus.IDLE
    private var videoPlayer: ExoPlayer? = null
    private var playerUri: Uri? = null
    private var playerPositionMs = 0L
    private var playerPlayWhenReady = true
    private var syncingBottomNavigation = false

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

    private val addExistingVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importExistingVideos(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        home = ScreenVideosBinding.bind(findViewById(R.id.videosScreen))
        editor = ScreenNewVideoBinding.bind(findViewById(R.id.newVideoScreen))
        settingsScreen = ScreenSettingsBinding.bind(findViewById(R.id.settingsScreen))
        playerScreen = ScreenPlayerBinding.bind(findViewById(R.id.playerScreen))
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (syncingBottomNavigation) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.navigationVideos -> showVideos()
                R.id.navigationCreate -> showNewVideo(loadRemembered = true)
                R.id.navigationSettings -> showSettings()
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        home.homeCancelExportButton.setOnClickListener { VideoExportService.cancel(applicationContext) }
        editor.doneButton.setOnClickListener {
            VideoExportCoordinator.clear(applicationContext)
            editor.videoReadyGroup.visibility = View.GONE
            showVideos()
        }
        editor.saveAsButton.setOnClickListener { lastVideoUri?.let(::chooseVideoCopyDestination) }
        editor.importButton.setOnClickListener { requestTimelineImport() }
        editor.exportHelpButton.setOnClickListener { showExportHelp() }
        editor.restoreTimelineHelpLink.setOnClickListener { openRestoreGuide() }
        editor.playButton.setOnClickListener { togglePreview() }
        editor.exportButton.setOnClickListener { chooseExportDestination() }
        editor.cancelExportButton.setOnClickListener { VideoExportService.cancel(applicationContext) }
        editor.shareButton.setOnClickListener { lastVideoUri?.let(::shareVideo) }
        editor.saveOverviewButton.setOnClickListener { lastVideoUri?.let(::chooseOverviewDestination) }
        editor.shareOverviewButton.setOnClickListener { lastVideoUri?.let(::shareOverviewImage) }
        editor.watchVideoButton.setOnClickListener { lastVideoUri?.let(::watchVideo) }
        editor.createAnotherButton.setOnClickListener { prepareAnotherVideo() }
        home.addExistingVideoButton.setOnClickListener { addExistingVideos.launch(arrayOf("video/mp4")) }
        home.showAllVideosButton.setOnClickListener {
            videosExpanded = !videosExpanded
            renderVideos()
        }
        home.deleteAllVideosButton.setOnClickListener { confirmDeleteAllVideos() }
        settingsScreen.privacyPolicyButton.setOnClickListener { openPrivacyPolicy() }
        settingsScreen.githubProjectButton.setOnClickListener { openWebPage(PROJECT_URL, R.string.web_page_unavailable) }
        settingsScreen.checkUpdatesButton.setOnClickListener { openUpdates() }
        playerScreen.playerBackButton.setOnClickListener { showVideos() }
        playerScreen.playerShareButton.setOnClickListener { playerUri?.let(::shareVideo) }
        playerScreen.playerMoreButton.setOnClickListener {
            playerUri?.let { uri -> videoStore.list().firstOrNull { it.uri == uri.toString() }?.let(::showVideoActions) }
        }
        playerScreen.playerExternalButton.setOnClickListener { playerUri?.let(::openExternalVideoPlayer) }
        onBackPressedDispatcher.addCallback(this) {
            if (currentScreen == Screen.VIDEOS) finish() else showVideos()
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

        editor.ownerInput.setText(preferences.getString("owner_name", null) ?: deviceName())
        editor.titleInput.setText(
            preferences.getString("title_template", null) ?: getString(R.string.default_title_template),
        )
        editor.ownerInput.doAfterTextChanged { scheduleTitleUpdate() }
        editor.titleInput.doAfterTextChanged { scheduleTitleUpdate() }
        editor.ownerInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTitlePreferences() }
        editor.titleInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTitlePreferences() }

        val durations = VideoDuration.presets.map {
            resources.getQuantityString(R.plurals.duration_seconds, it, it)
        } + getString(R.string.custom_duration)
        editor.durationDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, durations))
        applyDuration(VideoDuration.DEFAULT_SECONDS)
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
        configureLocationFiltering()
        configureMonthDropdowns()
        configureExactDates()
        renderVideos()
        lifecycleScope.launch(Dispatchers.IO) { videoMedia.pruneOverviewCache() }
        VideoExportCoordinator.restore(applicationContext)
        observeVideoExport()
        VideoExportService.resumeIfNeeded(applicationContext)

        playerUri = savedInstanceState?.getString(STATE_PLAYER_URI)?.toUri()
        playerPositionMs = savedInstanceState?.getLong(STATE_PLAYER_POSITION) ?: 0L
        playerPlayWhenReady = savedInstanceState?.getBoolean(STATE_PLAYER_PLAYING) ?: true
        val incoming = intent?.data
        if (intent?.action == ACTION_WATCH_VIDEO && incoming != null) {
            showVideoPlayer(incoming, resetPosition = savedInstanceState == null)
        } else if (incoming != null) {
            showNewVideo(loadRemembered = false)
            requestTimelineImport(incoming)
        } else when (savedInstanceState?.getString(STATE_SCREEN)) {
            Screen.NEW_VIDEO.name -> showNewVideo(loadRemembered = true)
            Screen.SETTINGS.name -> showSettings()
            Screen.PLAYER.name -> playerUri?.let { showVideoPlayer(it, resetPosition = false) } ?: showVideos()
            else -> showVideos()
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
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            if (intent.action == ACTION_WATCH_VIDEO) showVideoPlayer(uri) else {
                showNewVideo(loadRemembered = false)
                requestTimelineImport(uri)
            }
        }
    }

    private fun showVideos() {
        releaseVideoPlayer()
        currentScreen = Screen.VIDEOS
        home.root.visibility = View.VISIBLE
        editor.root.visibility = View.GONE
        settingsScreen.root.visibility = View.GONE
        playerScreen.root.visibility = View.GONE
        binding.bottomNavigation.visibility = View.VISIBLE
        if (binding.bottomNavigation.selectedItemId != R.id.navigationVideos) {
            syncingBottomNavigation = true
            binding.bottomNavigation.selectedItemId = R.id.navigationVideos
            syncingBottomNavigation = false
        }
        renderVideos()
    }

    private fun showNewVideo(loadRemembered: Boolean) {
        releaseVideoPlayer()
        currentScreen = Screen.NEW_VIDEO
        home.root.visibility = View.GONE
        editor.root.visibility = View.VISIBLE
        settingsScreen.root.visibility = View.GONE
        playerScreen.root.visibility = View.GONE
        binding.bottomNavigation.visibility = View.VISIBLE
        if (binding.bottomNavigation.selectedItemId != R.id.navigationCreate) {
            syncingBottomNavigation = true
            binding.bottomNavigation.selectedItemId = R.id.navigationCreate
            syncingBottomNavigation = false
        }
        if (loadRemembered && !rememberedTimelineLoaded && timeline == null &&
            preferences.getBoolean(MAP_PRIVACY_ACCEPTED, false)
        ) {
            rememberedTimelineLoaded = true
            timelineSourceStore.load()?.let { importTimeline(it, remembered = true) }
        }
    }

    private fun showSettings() {
        releaseVideoPlayer()
        currentScreen = Screen.SETTINGS
        home.root.visibility = View.GONE
        editor.root.visibility = View.GONE
        settingsScreen.root.visibility = View.VISIBLE
        playerScreen.root.visibility = View.GONE
        binding.bottomNavigation.visibility = View.VISIBLE
        if (binding.bottomNavigation.selectedItemId != R.id.navigationSettings) {
            syncingBottomNavigation = true
            binding.bottomNavigation.selectedItemId = R.id.navigationSettings
            syncingBottomNavigation = false
        }
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
        binding.bottomNavigation.visibility = View.GONE
        playerScreen.playerTitle.text = videoStore.list().firstOrNull { it.uri == uri.toString() }?.title
            ?: getString(R.string.timeline_video)
        playerScreen.playerErrorGroup.visibility = View.GONE
        initializeVideoPlayer()
    }

    override fun onStart() {
        super.onStart()
        if (currentScreen == Screen.PLAYER) initializeVideoPlayer()
    }

    override fun onStop() {
        releaseVideoPlayer()
        super.onStop()
    }

    private fun initializeVideoPlayer() {
        val uri = playerUri ?: return
        if (videoPlayer != null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
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
        setTimelineLoading(false)
        animation?.cancel()
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
            putString("title_template", editor.titleInput.text?.toString().orEmpty())
        }
        updateResolvedTitle()
    }

    private fun updateResolvedTitle() {
        val period = currentPeriod() ?: return
        editor.timelineView.videoTitle = resolvedTitle(period)
    }

    private fun resolvedTitle(period: TimelinePeriod): String = TitleTemplate.resolve(
        template = editor.titleInput.text?.toString().orEmpty(),
        yearLabel = period.yearLabel,
        name = editor.ownerInput.text?.toString().orEmpty().ifBlank { getString(R.string.traveler) },
        fallback = getString(R.string.default_title),
    )

    private fun importTimeline(uri: Uri, remembered: Boolean = false) {
        if (importJob?.isActive == true) return
        animation?.cancel()
        editor.editorGroup.visibility = View.GONE
        setTimelineLoading(true, R.string.opening_timeline)
        importJob = lifecycleScope.launch {
            try {
                editor.loadingStageText.setText(R.string.reading_timeline)
                val loaded = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(TimelineParser()::parse)
                        ?: throw java.io.FileNotFoundException()
                }
                editor.loadingStageText.setText(R.string.preparing_trips)
                timeline = loaded
                rebuildRenderTimeline(reselect = false)
                configureYears(loaded)
                editor.editorGroup.visibility = View.VISIBLE
                editor.statusText.text = ""
                if (!remembered) rememberTimelineSource(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TimelineParseException) {
                Log.e(TAG, "Timeline import failed", error)
                timeline = null
                renderTimeline = null
                if (remembered) {
                    timelineSourceStore.clear()
                    releaseUriAccess(uri)
                    editor.statusText.setText(R.string.timeline_file_unavailable)
                    Snackbar.make(binding.root, R.string.choose_timeline_again, Snackbar.LENGTH_LONG).show()
                } else {
                    editor.statusText.setText(timelineParseMessage(error.reason))
                    Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Timeline import failed", error)
                timeline = null
                renderTimeline = null
                if (remembered) {
                    timelineSourceStore.clear()
                    releaseUriAccess(uri)
                    editor.statusText.setText(R.string.timeline_file_unavailable)
                    Snackbar.make(binding.root, R.string.choose_timeline_again, Snackbar.LENGTH_LONG).show()
                } else {
                    editor.statusText.setText(R.string.import_failed_detail)
                    Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
                }
            } finally {
                setTimelineLoading(false)
                importJob = null
            }
        }
    }

    private fun timelineParseMessage(reason: TimelineParseReason): Int = when (reason) {
        TimelineParseReason.MALFORMED_JSON -> R.string.timeline_error_malformed
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

    private fun configureYears(loaded: Timeline) {
        val years = loaded.years
        val labels = years.map { NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }.format(it) }
        editor.startYearDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        editor.endYearDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
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
        selectedStartDate = null
        selectedEndDate = null
        editor.exactDateSwitch.isChecked = false
        updateExactDateControls()
        updateYearDropdowns()
        updateResolvedTitle()
        selectRange()
    }

    private fun selectRange() {
        val period = currentPeriod() ?: return
        val selected = if (exactDateRangeEnabled) {
            val start = selectedStartDate ?: return
            val end = selectedEndDate ?: return
            renderTimeline?.forDateRange(start, end)
        } else {
            renderTimeline?.forRange(period)
        } ?: return
        val unfiltered = if (exactDateRangeEnabled) {
            timeline?.forDateRange(selectedStartDate ?: return, selectedEndDate ?: return)
        } else {
            timeline?.forRange(period)
        }
        val ignoredCount = ((unfiltered?.points?.size ?: selected.points.size) - selected.points.size).coerceAtLeast(0)
        animation?.cancel()
        journey = selected
        editor.timelineView.journey = selected
        editor.timelineSeek.progress = 0
        showProgress(0f)
        editor.videoReadyGroup.visibility = View.GONE
        editor.periodSummaryText.text = selectedPeriodSummary(selected, ignoredCount)
        val canCreate = canCreateVideo(selected)
        editor.playButton.isEnabled = canCreate
        editor.exportButton.isEnabled = canCreate
    }

    internal fun selectedPeriodSummary(selected: Journey, ignoredCount: Int = 0): String {
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
        if (selected.points.isEmpty()) return withOutlierSummary(getString(R.string.selected_period_empty), ignoredCount)
        if (selected.points.size == 1) return withOutlierSummary(getString(R.string.selected_period_one_point), ignoredCount)
        if (selected.totalDistanceKm <= 0) {
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
                number.format(selected.totalDistanceKm),
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
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, monthNames)
        editor.startMonthDropdown.setAdapter(adapter)
        editor.endMonthDropdown.setAdapter(adapter)
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
        }
        editor.exactDateRangeButton.setOnClickListener { showExactDatePicker() }
        updateExactDateControls()
    }

    private fun showExactDatePicker() {
        val start = selectedStartDate ?: currentPeriod()?.start?.atDay(1) ?: return
        val end = selectedEndDate ?: currentPeriod()?.endInclusive?.atEndOfMonth() ?: return
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.choose_exact_dates)
            .setSelection(AndroidPair(datePickerMillis(start), datePickerMillis(end)))
            .build()
        picker.addOnPositiveButtonClickListener { range ->
            val pickedStart = Instant.ofEpochMilli(range.first).atZone(ZoneOffset.UTC).toLocalDate()
            val pickedEnd = Instant.ofEpochMilli(range.second).atZone(ZoneOffset.UTC).toLocalDate()
            selectedStartDate = pickedStart
            selectedEndDate = pickedEnd
            selectedStartYear = pickedStart.year
            selectedEndYear = pickedEnd.year
            selectedStartMonth = pickedStart.monthValue
            selectedEndMonth = pickedEnd.monthValue
            updateYearDropdowns()
            editor.startMonthDropdown.setText(monthNames[selectedStartMonth - 1], false)
            editor.endMonthDropdown.setText(monthNames[selectedEndMonth - 1], false)
            updateExactDateControls()
            updateResolvedTitle()
            selectRange()
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
        if (start > end) {
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
    }

    private fun updateYearDropdowns() {
        val formatter = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
        selectedStartYear?.let { editor.startYearDropdown.setText(formatter.format(it), false) }
        selectedEndYear?.let { editor.endYearDropdown.setText(formatter.format(it), false) }
    }

    private fun currentPeriod(): TimelinePeriod? {
        val startYear = selectedStartYear ?: return null
        val endYear = selectedEndYear ?: return null
        return TimelinePeriod(
            start = YearMonth.of(startYear, selectedStartMonth),
            endInclusive = YearMonth.of(endYear, selectedEndMonth),
        )
    }

    private fun togglePreview() {
        journey ?: return
        if (animation?.isPaused == true) {
            animation?.resume()
            editor.playButton.text = getString(R.string.pause_preview)
            return
        }
        if (animation?.isRunning == true) {
            animation?.pause()
            editor.playButton.text = getString(R.string.preview)
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
                    editor.playButton.text = getString(R.string.pause_preview)
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    editor.playButton.text = getString(R.string.preview)
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    editor.playButton.text = getString(R.string.preview)
                }
            })
            start()
        }
    }

    private fun showProgress(progress: Float) {
        editor.timelineView.progress = progress
    }

    private fun configureAdvancedSettings() {
        val cameraLabels = listOf(
            R.string.camera_fixed,
            R.string.camera_steady,
            R.string.camera_dynamic,
        ).map(::getString)
        val compressionLabels = listOf(
            R.string.compression_off,
            R.string.compression_gentle,
            R.string.compression_balanced,
            R.string.compression_strong,
        ).map(::getString)
        val qualityLabels = listOf(
            R.string.quality_standard,
            R.string.quality_high,
            R.string.quality_ultra,
        ).map(::getString)

        listOf(
            settingsScreen.cameraMovementDropdown to cameraLabels,
            settingsScreen.longTripDropdown to compressionLabels,
            settingsScreen.videoQualityDropdown to qualityLabels,
        ).forEach { (dropdown, labels) ->
            dropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
            makeDropdownOpenReliably(dropdown)
        }

        settingsScreen.cameraMovementDropdown.setOnItemClickListener { _, _, position, _ ->
            updateAdvancedSettings(cameraSettings.copy(cameraMovement = CameraMovement.values()[position]))
        }
        settingsScreen.longTripDropdown.setOnItemClickListener { _, _, position, _ ->
            updateAdvancedSettings(cameraSettings.copy(longTripCompression = LongTripCompression.values()[position]))
        }
        settingsScreen.videoQualityDropdown.setOnItemClickListener { _, _, position, _ ->
            updateAdvancedSettings(cameraSettings.copy(videoQuality = VideoQuality.values()[position]))
        }
        settingsScreen.resetAdvancedSettingsButton.setOnClickListener {
            applyAdvancedSettings(cameraSettingsPreferences.reset())
            Snackbar.make(binding.root, R.string.advanced_defaults_restored, Snackbar.LENGTH_SHORT).show()
        }
        applyAdvancedSettings(cameraSettingsPreferences.load())
    }

    private fun configureLocationFiltering() {
        val modes = listOf(LocationFilterMode.CONSERVATIVE, LocationFilterMode.OFF)
        val labels = listOf(
            getString(R.string.location_filter_conservative),
            getString(R.string.location_filter_off),
        )
        settingsScreen.locationFilterDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels),
        )
        makeDropdownOpenReliably(settingsScreen.locationFilterDropdown)
        settingsScreen.locationFilterDropdown.setOnItemClickListener { _, _, position, _ ->
            locationFilterMode = modes[position]
            locationFilterPreferences.save(locationFilterMode)
            updateLocationFilterLabel()
            rebuildRenderTimeline(reselect = true)
        }
        locationFilterMode = locationFilterPreferences.load()
        updateLocationFilterLabel()
    }

    private fun updateLocationFilterLabel() {
        settingsScreen.locationFilterDropdown.setText(
            getString(
                if (locationFilterMode == LocationFilterMode.CONSERVATIVE) {
                    R.string.location_filter_conservative
                } else {
                    R.string.location_filter_off
                },
            ),
            false,
        )
    }

    private fun rebuildRenderTimeline(reselect: Boolean) {
        val source = timeline
        if (source == null) {
            renderTimeline = null
            return
        }
        val result = LocationOutlierFilter.filter(source.points, locationFilterMode)
        renderTimeline = Timeline(result.points)
        if (reselect && selectedStartYear != null && selectedEndYear != null) selectRange()
    }

    private fun updateAdvancedSettings(settings: CameraSettings) {
        cameraSettingsPreferences.save(settings)
        applyAdvancedSettings(settings)
    }

    private fun applyAdvancedSettings(settings: CameraSettings) {
        cameraSettings = settings
        editor.timelineView.cameraSettings = settings
        settingsScreen.cameraMovementDropdown.setText(
            getString(
                listOf(R.string.camera_fixed, R.string.camera_steady, R.string.camera_dynamic)[settings.cameraMovement.ordinal],
            ),
            false,
        )
        settingsScreen.longTripDropdown.setText(
            getString(
                listOf(
                    R.string.compression_off,
                    R.string.compression_gentle,
                    R.string.compression_balanced,
                    R.string.compression_strong,
                )[settings.longTripCompression.ordinal],
            ),
            false,
        )
        settingsScreen.videoQualityDropdown.setText(
            getString(listOf(R.string.quality_standard, R.string.quality_high, R.string.quality_ultra)[settings.videoQuality.ordinal]),
            false,
        )
        showProgress(editor.timelineSeek.progress / 1000f)
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
        val title = resolvedTitle(selected.period)
        pendingExport = VideoExportRequest(
            outputUri = "",
            journey = selected,
            title = title,
            durationSeconds = selectedDurationSeconds(),
            renderText = currentRenderText(),
            cameraSettings = cameraSettings,
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
        VideoExportCoordinator.publish(applicationContext, snapshot)
        runCatching { VideoExportService.start(applicationContext) }.onFailure {
            VideoExportRequestStore(applicationContext).clear()
            generatedMedia.discard(uri)
            VideoExportCoordinator.publish(
                applicationContext,
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
                VideoExportCoordinator.state.collect(::renderVideoExport)
            }
        }
    }

    private fun renderVideoExport(snapshot: VideoExportSnapshot) {
        home.homeExportGroup.visibility = if (snapshot.status == VideoExportStatus.RUNNING) View.VISIBLE else View.GONE
        when (snapshot.status) {
            VideoExportStatus.IDLE -> setExporting(false)
            VideoExportStatus.RUNNING -> {
                setExporting(true)
                snapshot.progress?.let { showExportProgress(it, snapshot.startedAtMillis) }
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
                if (lastRenderedExportStatus != VideoExportStatus.COMPLETE) renderVideos()
            }
            VideoExportStatus.CANCELLED -> {
                setExporting(false)
                editor.statusText.text = getString(R.string.video_creation_cancelled)
            }
            VideoExportStatus.FAILED -> {
                setExporting(false)
                editor.statusText.setText(R.string.video_export_failed)
            }
        }
        lastRenderedExportStatus = snapshot.status
    }

    private fun showExportProgress(progress: ExportProgress, startedAtMillis: Long) {
        editor.exportProgress.progress = (progress.fraction * 1000).toInt()
        home.homeExportProgress.progress = (progress.fraction * 1000).toInt()
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
        val elapsedMs = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
        val remaining = if (elapsedMs >= 3_000 && progress.fraction in 0.05f..0.98f) {
            ceil(elapsedMs / 1000.0 * (1.0 - progress.fraction) / progress.fraction).toInt()
        } else null
        val status = if (remaining != null) {
            getString(R.string.progress_with_eta, base, formatRemainingTime(remaining))
        } else base
        editor.statusText.text = status
        home.homeExportStatusText.text = status
    }

    private fun formatRemainingTime(seconds: Int): String = if (seconds < 90) {
        resources.getQuantityString(R.plurals.remaining_seconds, seconds.coerceAtLeast(1), seconds.coerceAtLeast(1))
    } else {
        val minutes = ceil(seconds / 60.0).toInt()
        resources.getQuantityString(R.plurals.remaining_minutes, minutes, minutes)
    }

    private fun setExporting(exporting: Boolean) {
        val canCreate = journey?.let(::canCreateVideo) == true
        editor.exportProgress.visibility = if (exporting) View.VISIBLE else View.GONE
        editor.cancelExportButton.visibility = if (exporting) View.VISIBLE else View.GONE
        home.deleteAllVideosButton.isEnabled = !exporting
        editor.importButton.isEnabled = !exporting
        editor.playButton.isEnabled = !exporting && canCreate
        editor.exportButton.isEnabled = !exporting && canCreate
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
        editor.exactDateRangeButton.isEnabled = !exporting
        editor.ownerInput.isEnabled = !exporting
        editor.titleInput.isEnabled = !exporting
        settingsScreen.cameraMovementDropdown.isEnabled = !exporting
        settingsScreen.longTripDropdown.isEnabled = !exporting
        settingsScreen.videoQualityDropdown.isEnabled = !exporting
        settingsScreen.resetAdvancedSettingsButton.isEnabled = !exporting
        if (exporting) editor.videoReadyGroup.visibility = View.GONE
    }

    private fun prepareAnotherVideo() {
        VideoExportCoordinator.clear(applicationContext)
        editor.videoReadyGroup.visibility = View.GONE
        editor.timelineSeek.progress = 0
        showProgress(0f)
        journey?.let { editor.periodSummaryText.text = selectedPeriodSummary(it) }
    }

    private fun importExistingVideos(uris: List<Uri>) {
        home.addExistingVideoButton.isEnabled = false
        home.addExistingVideoButton.setText(R.string.adding_videos)
        lifecycleScope.launch {
            var imported = 0
            var failed = 0
            uris.forEachIndexed { index, uri ->
                persistUriAccess(uri, includeWrite = true)
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val metadata = videoMedia.inspect(uri)
                        runCatching { videoMedia.createThumbnail(uri)?.recycle() }
                        VideoRecord(
                            uri = uri.toString(),
                            title = metadata.fileName.substringBeforeLast('.').ifBlank { getString(R.string.default_title) },
                            fileName = metadata.fileName,
                            createdAtMillis = metadata.lastModifiedMillis.takeIf { it > 0 }
                                ?: System.currentTimeMillis() - index,
                            durationSeconds = metadata.durationSeconds,
                        )
                    }
                }
                result.onSuccess { record ->
                    videoStore.upsert(record)
                    imported += 1
                }.onFailure { failed += 1 }
            }
            home.addExistingVideoButton.isEnabled = true
            home.addExistingVideoButton.setText(R.string.add_videos)
            renderVideos()
            if (imported > 0) {
                Snackbar.make(
                    binding.root,
                    resources.getQuantityString(R.plurals.videos_added, imported, imported),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
            if (failed > 0) Snackbar.make(binding.root, R.string.video_import_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun renderVideos() {
        val records = videoStore.list()
        val generation = ++videoRenderGeneration
        home.emptyVideosText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        home.showAllVideosButton.visibility = if (records.size > COLLAPSED_CREATION_COUNT) View.VISIBLE else View.GONE
        home.deleteAllVideosButton.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        home.showAllVideosButton.text = if (videosExpanded) {
            getString(R.string.show_fewer_videos)
        } else {
            getString(R.string.show_all_videos, records.size)
        }
        home.videosList.removeAllViews()
        val visibleRecords = if (videosExpanded) records else records.take(COLLAPSED_CREATION_COUNT)
        visibleRecords.forEach { record ->
            val item = ItemVideoBinding.inflate(layoutInflater, home.videosList, false)
            val uri = record.uri.toUri()
            item.root.tag = record.uri
            item.videoTitle.text = record.title
            item.videoDetails.text = videoDetails(record, available = true)
            item.videoWatchButton.isEnabled = false
            item.videoShareButton.isEnabled = false
            item.root.isClickable = false
            item.videoWatchButton.setOnClickListener { watchVideo(uri) }
            item.videoShareButton.setOnClickListener { shareVideo(uri) }
            item.videoMoreButton.setOnClickListener { showVideoActions(record) }
            item.root.setOnClickListener { watchVideo(uri) }
            home.videosList.addView(item.root)

            lifecycleScope.launch {
                val mediaState = withContext(Dispatchers.IO) {
                    val available = videoMedia.isAvailable(uri)
                    val thumbnail = if (available) runCatching { videoMedia.createThumbnail(uri) }.getOrNull() else null
                    available to thumbnail
                }
                if (generation != videoRenderGeneration || item.root.tag != record.uri) {
                    mediaState.second?.recycle()
                    return@launch
                }
                val available = mediaState.first
                item.videoDetails.text = videoDetails(record, available)
                item.videoWatchButton.isEnabled = available
                item.videoShareButton.isEnabled = available
                item.root.isClickable = available
                mediaState.second?.let { thumbnail ->
                    item.videoThumbnail.setPadding(0, 0, 0, 0)
                    item.videoThumbnail.setImageBitmap(thumbnail)
                }
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
        parts += if (record.durationSeconds > 0) formatVideoDuration(record.durationSeconds)
        else getString(R.string.unknown_duration)
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
            .setItems(arrayOf(getString(R.string.remove_from_list), getString(R.string.delete_video))) { _, which ->
                if (which == 0) removeVideo(record) else confirmDeleteVideo(record)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeVideo(record: VideoRecord) {
        val uri = record.uri.toUri()
        videoStore.remove(record.uri)
        videoMedia.deleteThumbnail(uri)
        renderVideos()
        var restored = false
        Snackbar.make(binding.root, R.string.video_removed, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                restored = true
                videoStore.upsert(record)
                renderVideos()
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

    private fun confirmDeleteAllVideos() {
        val records = videoStore.list()
        if (records.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_all_videos_title)
            .setMessage(R.string.delete_all_videos_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_all_videos) { _, _ -> deleteAllVideos(records) }
            .show()
    }

    private fun deleteAllVideos(records: List<VideoRecord>) {
        home.deleteAllVideosButton.isEnabled = false
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
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
            videoStore.removeAll(deleted.mapTo(mutableSetOf(), VideoRecord::uri))
            deleted.forEach { record ->
                releaseUriAccess(record.uri.toUri())
                if (lastVideoUri?.toString() == record.uri) lastVideoUri = null
            }
            renderVideos()
            home.deleteAllVideosButton.isEnabled = true
            val failed = records.size - deleted.size
            Snackbar.make(
                binding.root,
                if (failed == 0) {
                    getString(R.string.all_videos_deleted)
                } else {
                    getString(R.string.some_videos_not_deleted, deleted.size, failed)
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
                videoStore.remove(record.uri)
                videoMedia.deleteThumbnail(uri)
                videoMedia.deleteOverview(uri)
                releaseUriAccess(uri)
                if (lastVideoUri == uri) {
                    lastVideoUri = null
                    editor.videoReadyGroup.visibility = View.GONE
                }
                renderVideos()
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
    }

    private fun chooseVideoCopyDestination(source: Uri) {
        pendingVideoCopyUri = source
        val record = videoStore.list().firstOrNull { it.uri == source.toString() }
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
        val periodSuffix = videoStore.list()
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
        return RenderText(
            localeTag = locale.toLanguageTag(),
            fallbackTitle = getString(R.string.default_title),
            datePattern = getString(R.string.render_date_pattern),
            distanceUnit = getString(R.string.distance_unit),
            attribution = getString(R.string.map_attribution),
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

    private fun deviceName(): String {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.replace('_', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return name?.takeUnless {
            it.startsWith("sdk ", ignoreCase = true) ||
                it.startsWith("generic", ignoreCase = true) ||
                it.equals("Android", ignoreCase = true)
        } ?: getString(R.string.traveler)
    }

    internal fun selectedDurationSeconds(): Int = routeDurationSeconds

    internal fun pendingExportDurationSeconds(): Int? = pendingExport?.durationSeconds

    private fun makeDropdownOpenReliably(dropdown: AutoCompleteTextView) {
        dropdown.threshold = 0
        dropdown.setOnClickListener { dropdown.showDropDown() }
    }

    private enum class Screen { VIDEOS, NEW_VIDEO, SETTINGS, PLAYER }

    companion object {
        private const val TITLE_UPDATE_DELAY_MS = 450L
        private const val COLLAPSED_CREATION_COUNT = 3
        private const val MAP_PRIVACY_ACCEPTED = "map_privacy_accepted_v1"
        private const val NOTIFICATION_PROMPTED = "notification_prompted_v1"
        private const val STATE_SCREEN = "screen_v1"
        private const val STATE_PLAYER_URI = "player_uri_v1"
        private const val STATE_PLAYER_POSITION = "player_position_v1"
        private const val STATE_PLAYER_PLAYING = "player_playing_v1"
        internal const val ACTION_WATCH_VIDEO = "dev.mahlernim.timelinevisualizer.action.WATCH_VIDEO"
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

        internal fun playbackIntent(context: Context, uri: Uri): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_WATCH_VIDEO
                data = uri
                clipData = ClipData.newRawUri(context.getString(R.string.timeline_video), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        internal fun restoreGuideUrl(language: String?): String = when (language) {
            Locale.KOREAN.language -> RESTORE_GUIDE_URL_KO
            Locale.JAPANESE.language -> RESTORE_GUIDE_URL_JA
            else -> RESTORE_GUIDE_URL
        }
    }
}
