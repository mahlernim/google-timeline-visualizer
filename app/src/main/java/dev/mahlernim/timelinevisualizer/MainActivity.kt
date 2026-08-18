package dev.mahlernim.timelinevisualizer

import android.animation.ValueAnimator
import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.mahlernim.timelinevisualizer.creations.CreationMedia
import dev.mahlernim.timelinevisualizer.creations.CreationRecord
import dev.mahlernim.timelinevisualizer.creations.CreationStore
import dev.mahlernim.timelinevisualizer.data.TimelineParser
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.databinding.ActivityMainBinding
import dev.mahlernim.timelinevisualizer.databinding.ItemCreationBinding
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
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import dev.mahlernim.timelinevisualizer.render.RenderText
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
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var timeline: Timeline? = null
    private var journey: Journey? = null
    private var animation: ValueAnimator? = null
    private var pendingExport: VideoExportRequest? = null
    private var lastVideoUri: Uri? = null
    private var lastVideoTitle: String? = null
    private var pendingOverviewVideoUri: Uri? = null
    private var importJob: Job? = null
    private var selectedStartYear: Int? = null
    private var selectedEndYear: Int? = null
    private var selectedStartMonth = 1
    private var selectedEndMonth = 12
    private val titleHandler = Handler(Looper.getMainLooper())
    private val monthNames by lazy { DateFormatSymbols.getInstance().months.take(12) }
    private val preferences by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private val creationStore by lazy { CreationStore(applicationContext) }
    private val creationMedia by lazy { CreationMedia(applicationContext) }
    private val timelineSourceStore by lazy { TimelineSourceStore(applicationContext) }
    private val applyTitleChanges = Runnable { commitTitlePreferences() }
    private var creationRenderGeneration = 0
    private var creationsExpanded = false
    private var lastRenderedExportStatus = VideoExportStatus.IDLE

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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.importButton.setOnClickListener { requestTimelineImport() }
        binding.exportHelpButton.setOnClickListener { showExportHelp() }
        binding.playButton.setOnClickListener { togglePreview() }
        binding.exportButton.setOnClickListener { chooseExportDestination() }
        binding.cancelExportButton.setOnClickListener { VideoExportService.cancel(applicationContext) }
        binding.shareButton.setOnClickListener { lastVideoUri?.let(::shareVideo) }
        binding.saveOverviewButton.setOnClickListener { lastVideoUri?.let(::chooseOverviewDestination) }
        binding.shareOverviewButton.setOnClickListener { lastVideoUri?.let(::shareOverviewImage) }
        binding.watchVideoButton.setOnClickListener { lastVideoUri?.let(::watchVideo) }
        binding.createAnotherButton.setOnClickListener { prepareAnotherVideo() }
        binding.addExistingVideoButton.setOnClickListener { addExistingVideos.launch(arrayOf("video/mp4")) }
        binding.showAllCreationsButton.setOnClickListener {
            creationsExpanded = !creationsExpanded
            renderCreations()
        }
        binding.privacyPolicyButton.setOnClickListener { openPrivacyPolicy() }
        binding.githubProjectButton.setOnClickListener { openWebPage(PROJECT_URL, R.string.web_page_unavailable) }
        binding.checkUpdatesButton.setOnClickListener { openUpdates() }
        binding.timelineSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    animation?.cancel()
                    showProgress(progress / 1000f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.ownerInput.setText(preferences.getString("owner_name", null) ?: deviceName())
        binding.titleInput.setText(
            preferences.getString("title_template", null) ?: getString(R.string.default_title_template),
        )
        binding.ownerInput.doAfterTextChanged { scheduleTitleUpdate() }
        binding.titleInput.doAfterTextChanged { scheduleTitleUpdate() }
        binding.ownerInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTitlePreferences() }
        binding.titleInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTitlePreferences() }

        val durations = listOf(15, 30, 45, 60, 75, 90).map {
            resources.getQuantityString(R.plurals.duration_seconds, it, it)
        }
        binding.durationDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, durations))
        binding.durationDropdown.setText(resources.getQuantityString(R.plurals.duration_seconds, 30, 30), false)
        binding.timelineView.journeyDurationSeconds = 30
        binding.timelineView.renderText = currentRenderText()
        binding.durationDropdown.setOnItemClickListener { _, _, _, _ ->
            animation?.cancel()
            binding.timelineView.journeyDurationSeconds = selectedDurationSeconds()
            showProgress(binding.timelineSeek.progress / 1000f)
        }
        makeDropdownOpenReliably(binding.durationDropdown)
        configureMonthDropdowns()
        renderCreations()
        lifecycleScope.launch(Dispatchers.IO) { creationMedia.pruneOverviewCache() }
        VideoExportCoordinator.restore(applicationContext)
        observeVideoExport()
        VideoExportService.resumeIfNeeded(applicationContext)

        val incoming = intent?.data
        if (incoming != null) {
            requestTimelineImport(incoming)
        } else if (preferences.getBoolean(MAP_PRIVACY_ACCEPTED, false)) {
            timelineSourceStore.load()?.let { importTimeline(it, remembered = true) }
        }
    }

    override fun onDestroy() {
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
            putString("owner_name", binding.ownerInput.text?.toString().orEmpty())
            putString("title_template", binding.titleInput.text?.toString().orEmpty())
        }
        updateResolvedTitle()
    }

    private fun updateResolvedTitle() {
        val period = currentPeriod() ?: return
        binding.timelineView.videoTitle = resolvedTitle(period)
    }

    private fun resolvedTitle(period: TimelinePeriod): String = TitleTemplate.resolve(
        template = binding.titleInput.text?.toString().orEmpty(),
        yearLabel = period.yearLabel,
        name = binding.ownerInput.text?.toString().orEmpty().ifBlank { getString(R.string.traveler) },
        fallback = getString(R.string.default_title),
    )

    private fun importTimeline(uri: Uri, remembered: Boolean = false) {
        if (importJob?.isActive == true) return
        animation?.cancel()
        binding.editorGroup.visibility = View.GONE
        setTimelineLoading(true, R.string.opening_timeline)
        importJob = lifecycleScope.launch {
            try {
                binding.loadingStageText.setText(R.string.reading_timeline)
                val loaded = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(TimelineParser()::parse)
                        ?: throw java.io.FileNotFoundException()
                }
                binding.loadingStageText.setText(R.string.preparing_trips)
                timeline = loaded
                configureYears(loaded)
                binding.editorGroup.visibility = View.VISIBLE
                if (!remembered) rememberTimelineSource(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Timeline import failed", error)
                timeline = null
                if (remembered) {
                    timelineSourceStore.clear()
                    releaseUriAccess(uri)
                    binding.statusText.setText(R.string.remembered_timeline_unavailable)
                    Snackbar.make(binding.root, R.string.choose_timeline_again, Snackbar.LENGTH_LONG).show()
                } else {
                    binding.statusText.setText(R.string.import_failed_detail)
                    Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
                }
            } finally {
                setTimelineLoading(false)
                importJob = null
            }
        }
    }

    private fun setTimelineLoading(loading: Boolean, stage: Int = R.string.opening_timeline) {
        binding.loadingGroup.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.loadingStageText.setText(stage)
        binding.importButton.isEnabled = !loading
        binding.exportHelpButton.isEnabled = !loading
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
        binding.startYearDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        binding.endYearDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        makeDropdownOpenReliably(binding.startYearDropdown)
        makeDropdownOpenReliably(binding.endYearDropdown)
        binding.startYearDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedStartYear = years[position]
            normalizeRange(changedStart = true)
        }
        binding.endYearDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedEndYear = years[position]
            normalizeRange(changedStart = false)
        }
        selectedStartYear = years.first()
        selectedEndYear = years.first()
        updateYearDropdowns()
        updateResolvedTitle()
        selectRange()
    }

    private fun selectRange() {
        val period = currentPeriod() ?: return
        val selected = timeline?.forRange(period) ?: return
        animation?.cancel()
        journey = selected
        binding.timelineView.journey = selected
        binding.timelineSeek.progress = 0
        showProgress(0f)
        binding.videoReadyGroup.visibility = View.GONE
        binding.statusText.text = journeySummary(selected)
        val canCreate = selected.points.size >= 2 && selected.totalDistanceKm > 0
        binding.playButton.isEnabled = canCreate
        binding.exportButton.isEnabled = canCreate
    }

    private fun journeySummary(selected: Journey): String {
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
        val range = selected.period
        val period = if (
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
        return getString(
            R.string.journey_summary,
            number.format(selected.points.size),
            number.format(selected.totalDistanceKm),
            period,
        )
    }

    private fun configureMonthDropdowns() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, monthNames)
        binding.startMonthDropdown.setAdapter(adapter)
        binding.endMonthDropdown.setAdapter(adapter)
        binding.startMonthDropdown.setText(monthNames.first(), false)
        binding.endMonthDropdown.setText(monthNames.last(), false)
        makeDropdownOpenReliably(binding.startMonthDropdown)
        makeDropdownOpenReliably(binding.endMonthDropdown)
        binding.startMonthDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedStartMonth = position + 1
            normalizeRange(changedStart = true)
        }
        binding.endMonthDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedEndMonth = position + 1
            normalizeRange(changedStart = false)
        }
    }

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
            binding.startMonthDropdown.setText(monthNames[selectedStartMonth - 1], false)
            binding.endMonthDropdown.setText(monthNames[selectedEndMonth - 1], false)
        }
        updateResolvedTitle()
        selectRange()
    }

    private fun updateYearDropdowns() {
        val formatter = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
        selectedStartYear?.let { binding.startYearDropdown.setText(formatter.format(it), false) }
        selectedEndYear?.let { binding.endYearDropdown.setText(formatter.format(it), false) }
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
            binding.playButton.text = getString(R.string.pause_preview)
            return
        }
        if (animation?.isRunning == true) {
            animation?.pause()
            binding.playButton.text = getString(R.string.preview)
            return
        }
        val start = if (binding.timelineSeek.progress >= 1000) {
            binding.timelineSeek.progress = 0
            showProgress(0f)
            0
        } else binding.timelineSeek.progress
        binding.timelineView.journeyDurationSeconds = selectedDurationSeconds()
        val durationMs = (TimelineAnimation.totalDurationSeconds(selectedDurationSeconds()) * 1000f).toLong()
        animation = ValueAnimator.ofInt(start, 1000).apply {
            duration = ((1000 - start) / 1000f * durationMs).toLong().coerceAtLeast(250)
            addUpdateListener { value ->
                val progress = value.animatedValue as Int
                binding.timelineSeek.progress = progress
                showProgress(progress / 1000f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    binding.playButton.text = getString(R.string.pause_preview)
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    binding.playButton.text = getString(R.string.preview)
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    binding.playButton.text = getString(R.string.preview)
                }
            })
            start()
        }
    }

    private fun showProgress(progress: Float) {
        binding.timelineView.progress = progress
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
        )
        val slug = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "timeline" }
        val periodSuffix = periodFileSuffix(selected.period)
        createVideo.launch("$slug-$periodSuffix.mp4")
    }

    private fun startVideoExport(uri: Uri, request: VideoExportRequest) {
        val completeRequest = request.copy(outputUri = uri.toString())
        persistUriAccess(uri, includeWrite = true)
        val saved = runCatching { VideoExportRequestStore(applicationContext).save(completeRequest) }
        if (saved.isFailure) {
            runCatching { contentResolver.delete(uri, null, null) }
            binding.statusText.text = getString(R.string.video_request_unavailable)
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
        VideoExportService.start(applicationContext)
    }

    private fun observeVideoExport() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VideoExportCoordinator.state.collect(::renderVideoExport)
            }
        }
    }

    private fun renderVideoExport(snapshot: VideoExportSnapshot) {
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
                    binding.videoReadyGroup.visibility = View.VISIBLE
                    val hasOverview = creationMedia.cachedOverview(uri) != null
                    binding.saveOverviewButton.isEnabled = hasOverview
                    binding.shareOverviewButton.isEnabled = hasOverview
                }
                binding.statusText.text = getString(R.string.video_saved)
                if (lastRenderedExportStatus != VideoExportStatus.COMPLETE) renderCreations()
            }
            VideoExportStatus.CANCELLED -> {
                setExporting(false)
                binding.statusText.text = getString(R.string.video_creation_cancelled)
            }
            VideoExportStatus.FAILED -> {
                setExporting(false)
                binding.statusText.setText(R.string.video_export_failed)
            }
        }
        lastRenderedExportStatus = snapshot.status
    }

    private fun showExportProgress(progress: ExportProgress, startedAtMillis: Long) {
        binding.exportProgress.progress = (progress.fraction * 1000).toInt()
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
        binding.statusText.text = if (remaining != null) {
            getString(R.string.progress_with_eta, base, formatRemainingTime(remaining))
        } else base
    }

    private fun formatRemainingTime(seconds: Int): String = if (seconds < 90) {
        resources.getQuantityString(R.plurals.remaining_seconds, seconds.coerceAtLeast(1), seconds.coerceAtLeast(1))
    } else {
        val minutes = ceil(seconds / 60.0).toInt()
        resources.getQuantityString(R.plurals.remaining_minutes, minutes, minutes)
    }

    private fun setExporting(exporting: Boolean) {
        val canCreate = journey?.let { it.points.size >= 2 && it.totalDistanceKm > 0 } == true
        binding.exportProgress.visibility = if (exporting) View.VISIBLE else View.GONE
        binding.cancelExportButton.visibility = if (exporting) View.VISIBLE else View.GONE
        binding.importButton.isEnabled = !exporting
        binding.playButton.isEnabled = !exporting && canCreate
        binding.exportButton.isEnabled = !exporting && canCreate
        binding.shareButton.isEnabled = !exporting
        binding.watchVideoButton.isEnabled = !exporting
        binding.createAnotherButton.isEnabled = !exporting
        val hasOverview = lastVideoUri?.let { creationMedia.cachedOverview(it) != null } == true
        binding.saveOverviewButton.isEnabled = !exporting && hasOverview
        binding.shareOverviewButton.isEnabled = !exporting && hasOverview
        binding.startYearDropdown.isEnabled = !exporting
        binding.endYearDropdown.isEnabled = !exporting
        binding.durationDropdown.isEnabled = !exporting
        binding.startMonthDropdown.isEnabled = !exporting
        binding.endMonthDropdown.isEnabled = !exporting
        binding.ownerInput.isEnabled = !exporting
        binding.titleInput.isEnabled = !exporting
        if (exporting) binding.videoReadyGroup.visibility = View.GONE
    }

    private fun prepareAnotherVideo() {
        VideoExportCoordinator.clear(applicationContext)
        binding.videoReadyGroup.visibility = View.GONE
        binding.timelineSeek.progress = 0
        showProgress(0f)
        journey?.let { binding.statusText.text = journeySummary(it) }
    }

    private fun importExistingVideos(uris: List<Uri>) {
        binding.addExistingVideoButton.isEnabled = false
        binding.addExistingVideoButton.setText(R.string.adding_videos)
        lifecycleScope.launch {
            var imported = 0
            var failed = 0
            uris.forEachIndexed { index, uri ->
                persistUriAccess(uri, includeWrite = true)
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val metadata = creationMedia.inspect(uri)
                        runCatching { creationMedia.createThumbnail(uri)?.recycle() }
                        CreationRecord(
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
                    creationStore.upsert(record)
                    imported += 1
                }.onFailure { failed += 1 }
            }
            binding.addExistingVideoButton.isEnabled = true
            binding.addExistingVideoButton.setText(R.string.add_existing_videos)
            renderCreations()
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

    private fun renderCreations() {
        val records = creationStore.list()
        val generation = ++creationRenderGeneration
        binding.emptyCreationsText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.showAllCreationsButton.visibility = if (records.size > COLLAPSED_CREATION_COUNT) View.VISIBLE else View.GONE
        binding.showAllCreationsButton.text = if (creationsExpanded) {
            getString(R.string.show_fewer_creations)
        } else {
            getString(R.string.show_all_creations, records.size)
        }
        binding.creationsList.removeAllViews()
        val visibleRecords = if (creationsExpanded) records else records.take(COLLAPSED_CREATION_COUNT)
        visibleRecords.forEach { record ->
            val item = ItemCreationBinding.inflate(layoutInflater, binding.creationsList, false)
            val uri = record.uri.toUri()
            item.root.tag = record.uri
            item.creationTitle.text = record.title
            item.creationDetails.text = creationDetails(record, available = true)
            item.creationWatchButton.isEnabled = false
            item.creationShareButton.isEnabled = false
            item.root.isClickable = false
            item.creationWatchButton.setOnClickListener { watchVideo(uri) }
            item.creationShareButton.setOnClickListener { shareVideo(uri) }
            item.creationMoreButton.setOnClickListener { showCreationActions(record) }
            item.root.setOnClickListener { watchVideo(uri) }
            binding.creationsList.addView(item.root)

            lifecycleScope.launch {
                val mediaState = withContext(Dispatchers.IO) {
                    val available = creationMedia.isAvailable(uri)
                    val thumbnail = if (available) runCatching { creationMedia.createThumbnail(uri) }.getOrNull() else null
                    available to thumbnail
                }
                if (generation != creationRenderGeneration || item.root.tag != record.uri) {
                    mediaState.second?.recycle()
                    return@launch
                }
                val available = mediaState.first
                item.creationDetails.text = creationDetails(record, available)
                item.creationWatchButton.isEnabled = available
                item.creationShareButton.isEnabled = available
                item.root.isClickable = available
                mediaState.second?.let { thumbnail ->
                    item.creationThumbnail.setPadding(0, 0, 0, 0)
                    item.creationThumbnail.setImageBitmap(thumbnail)
                }
            }
        }
    }

    private fun creationDetails(record: CreationRecord, available: Boolean): String {
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

    private fun showCreationActions(record: CreationRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(record.title)
            .setItems(arrayOf(getString(R.string.remove_from_list), getString(R.string.delete_video))) { _, which ->
                if (which == 0) removeCreation(record) else confirmDeleteCreation(record)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeCreation(record: CreationRecord) {
        val uri = record.uri.toUri()
        creationStore.remove(record.uri)
        creationMedia.deleteThumbnail(uri)
        renderCreations()
        var restored = false
        Snackbar.make(binding.root, R.string.video_removed, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                restored = true
                creationStore.upsert(record)
                renderCreations()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (!restored) releaseUriAccess(uri)
                }
            })
            .show()
    }

    private fun confirmDeleteCreation(record: CreationRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_video_title)
            .setMessage(R.string.delete_video_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteCreation(record) }
            .show()
    }

    private fun deleteCreation(record: CreationRecord) {
        lifecycleScope.launch {
            val uri = record.uri.toUri()
            val deleted = withContext(Dispatchers.IO) { creationMedia.delete(uri) }
            if (deleted) {
                creationStore.remove(record.uri)
                creationMedia.deleteThumbnail(uri)
                creationMedia.deleteOverview(uri)
                releaseUriAccess(uri)
                if (lastVideoUri == uri) {
                    lastVideoUri = null
                    binding.videoReadyGroup.visibility = View.GONE
                }
                renderCreations()
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

    private fun openWebPage(url: String, errorMessage: Int) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.onFailure {
            Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun watchVideo(uri: Uri) {
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

    private fun chooseOverviewDestination(videoUri: Uri) {
        if (creationMedia.cachedOverview(videoUri) == null) {
            Snackbar.make(binding.root, R.string.overview_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        pendingOverviewVideoUri = videoUri
        val title = lastVideoTitle.orEmpty().ifBlank { getString(R.string.default_title) }
        val periodSuffix = creationStore.list()
            .firstOrNull { it.uri == videoUri.toString() }
            ?.let(::periodFileSuffix)
        val baseName = listOfNotNull(title, periodSuffix).joinToString("-")
        createOverviewImage.launch(getString(R.string.overview_file_name, baseName))
    }

    private fun copyOverviewImage(videoUri: Uri, destination: Uri) {
        binding.saveOverviewButton.isEnabled = false
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(destination, "w")?.use { output ->
                        check(creationMedia.copyOverview(videoUri, output))
                    } ?: error("Overview destination is unavailable")
                }.isSuccess
            }
            binding.saveOverviewButton.isEnabled = creationMedia.cachedOverview(videoUri) != null
            Snackbar.make(
                binding.root,
                if (saved) R.string.overview_saved else R.string.overview_save_failed,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun shareOverviewImage(videoUri: Uri) {
        val file = creationMedia.cachedOverview(videoUri)
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

    private fun periodFileSuffix(record: CreationRecord): String? {
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

    private fun selectedDurationSeconds(): Int = Regex("\\d+")
        .find(binding.durationDropdown.text.toString())
        ?.value
        ?.toIntOrNull()
        ?: 30

    private fun makeDropdownOpenReliably(dropdown: AutoCompleteTextView) {
        dropdown.threshold = 0
        dropdown.setOnClickListener { dropdown.showDropDown() }
    }

    companion object {
        private const val TITLE_UPDATE_DELAY_MS = 450L
        private const val COLLAPSED_CREATION_COUNT = 3
        private const val MAP_PRIVACY_ACCEPTED = "map_privacy_accepted_v1"
        private const val NOTIFICATION_PROMPTED = "notification_prompted_v1"
        private const val PROJECT_URL = "https://github.com/mahlernim/google-timeline-visualizer"
        private const val PRIVACY_URL =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.md"
        private const val PRIVACY_URL_KO =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.ko.md"
        private const val PRIVACY_URL_JA =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.ja.md"
        private const val TAG = "TimelineVisualizer"
    }
}
