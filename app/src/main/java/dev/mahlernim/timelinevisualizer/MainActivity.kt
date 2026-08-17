package dev.mahlernim.timelinevisualizer

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.mahlernim.timelinevisualizer.creations.CreationMedia
import dev.mahlernim.timelinevisualizer.creations.CreationRecord
import dev.mahlernim.timelinevisualizer.creations.CreationStore
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.data.TimelineParser
import dev.mahlernim.timelinevisualizer.databinding.ActivityMainBinding
import dev.mahlernim.timelinevisualizer.databinding.ItemCreationBinding
import dev.mahlernim.timelinevisualizer.export.ExportPhase
import dev.mahlernim.timelinevisualizer.export.ExportProgress
import dev.mahlernim.timelinevisualizer.export.Mp4Exporter
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.Timeline
import dev.mahlernim.timelinevisualizer.model.TitleTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormatSymbols
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var timeline: Timeline? = null
    private var journey: Journey? = null
    private var animation: ValueAnimator? = null
    private var exportJob: Job? = null
    private var pendingExport: ExportRequest? = null
    private var lastVideoUri: Uri? = null
    private var selectedYear: Int? = null
    private var selectedStartMonth = 1
    private var selectedEndMonth = 12
    private val titleHandler = Handler(Looper.getMainLooper())
    private val monthNames by lazy { DateFormatSymbols.getInstance().months.take(12) }
    private val preferences by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private val creationStore by lazy { CreationStore(applicationContext) }
    private val creationMedia by lazy { CreationMedia(applicationContext) }
    private val applyTitleChanges = Runnable { commitTitlePreferences() }
    private var creationRenderGeneration = 0
    private var creationsExpanded = false

    private val openTimeline = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTimeline(uri)
    }

    private val createVideo = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) exportVideo(uri, request)
    }

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
        binding.cancelExportButton.setOnClickListener { exportJob?.cancel() }
        binding.shareButton.setOnClickListener { lastVideoUri?.let(::shareVideo) }
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

        val durations = listOf(15, 30, 60, 90).map { resources.getQuantityString(R.plurals.duration_seconds, it, it) }
        binding.durationDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, durations))
        binding.durationDropdown.setText(resources.getQuantityString(R.plurals.duration_seconds, 30, 30), false)
        makeDropdownOpenReliably(binding.durationDropdown)
        configureMonthDropdowns()
        renderCreations()

        intent?.data?.let { requestTimelineImport(it) }
    }

    override fun onDestroy() {
        titleHandler.removeCallbacks(applyTitleChanges)
        animation?.cancel()
        exportJob?.cancel()
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
        val year = selectedYear ?: return
        binding.timelineView.videoTitle = resolvedTitle(year)
    }

    private fun resolvedTitle(year: Int): String = TitleTemplate.resolve(
        template = binding.titleInput.text?.toString().orEmpty(),
        year = year,
        name = binding.ownerInput.text?.toString().orEmpty().ifBlank { getString(R.string.traveler) },
        fallback = getString(R.string.default_title),
    )

    private fun importTimeline(uri: Uri) {
        animation?.cancel()
        binding.importButton.isEnabled = false
        binding.editorGroup.visibility = View.GONE
        binding.statusText.text = getString(R.string.reading_timeline)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(TimelineParser()::parse)
                        ?: error("The selected file could not be opened")
                }
            }
            binding.importButton.isEnabled = true
            result.onSuccess { loaded ->
                timeline = loaded
                configureYears(loaded)
                binding.editorGroup.visibility = View.VISIBLE
            }.onFailure { error ->
                timeline = null
                binding.statusText.text = error.message ?: getString(R.string.import_failed)
                Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun configureYears(loaded: Timeline) {
        val years = loaded.years
        binding.yearDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, years.map(Int::toString)),
        )
        makeDropdownOpenReliably(binding.yearDropdown)
        binding.yearDropdown.setOnItemClickListener { _, _, position, _ -> selectYear(years[position]) }
        binding.yearDropdown.setText(String.format(Locale.getDefault(), "%d", years.first()), false)
        selectYear(years.first())
    }

    private fun selectYear(year: Int) {
        selectedYear = year
        updateResolvedTitle()
        selectRange()
    }

    private fun selectRange() {
        val year = selectedYear ?: return
        val selected = timeline?.forRange(year, selectedStartMonth, selectedEndMonth) ?: return
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
        val period = if (selectedStartMonth == 1 && selectedEndMonth == 12) {
            selected.year.toString()
        } else {
            "${monthNames[selectedStartMonth - 1]}–${monthNames[selectedEndMonth - 1]} ${selected.year}"
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
            if (selectedStartMonth > selectedEndMonth) {
                selectedEndMonth = selectedStartMonth
                binding.endMonthDropdown.setText(monthNames[selectedEndMonth - 1], false)
            }
            selectRange()
        }
        binding.endMonthDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedEndMonth = position + 1
            if (selectedEndMonth < selectedStartMonth) {
                selectedStartMonth = selectedEndMonth
                binding.startMonthDropdown.setText(monthNames[selectedStartMonth - 1], false)
            }
            selectRange()
        }
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
        val durationMs = selectedDurationSeconds() * 1000L
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
        val selected = journey ?: return
        animation?.cancel()
        commitTitlePreferences()
        val title = resolvedTitle(selected.year)
        pendingExport = ExportRequest(
            selected,
            title,
            selectedDurationSeconds(),
            selectedStartMonth,
            selectedEndMonth,
        )
        val slug = title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "timeline" }
        createVideo.launch("$slug-${selected.year}.mp4")
    }

    private fun exportVideo(uri: Uri, request: ExportRequest) {
        val startedAt = SystemClock.elapsedRealtime()
        setExporting(true)
        exportJob = lifecycleScope.launch {
            try {
                Mp4Exporter(contentResolver, TileRepository(applicationContext)).export(
                    uri,
                    request.journey,
                    request.title,
                    request.durationSeconds,
                ) { progress ->
                    runOnUiThread { showExportProgress(progress, startedAt) }
                }
                persistUriAccess(uri, includeWrite = true)
                registerCreatedVideo(uri, request)
                lastVideoUri = uri
                binding.videoReadyGroup.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.video_saved)
                Snackbar.make(binding.root, R.string.video_saved, Snackbar.LENGTH_LONG)
                    .setAction(R.string.watch_video) { watchVideo(uri) }
                    .show()
            } catch (_: CancellationException) {
                deleteIncompleteVideo(uri)
                binding.statusText.text = getString(R.string.video_creation_cancelled)
                Snackbar.make(binding.root, R.string.video_creation_cancelled, Snackbar.LENGTH_LONG).show()
            } catch (error: Throwable) {
                deleteIncompleteVideo(uri)
                binding.statusText.text = error.message ?: getString(R.string.video_export_failed)
                Snackbar.make(binding.root, R.string.video_export_failed, Snackbar.LENGTH_LONG).show()
            } finally {
                setExporting(false)
                exportJob = null
            }
        }
    }

    private fun showExportProgress(progress: ExportProgress, startedAt: Long) {
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
            ExportPhase.COMPLETE -> return
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
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

    private fun deleteIncompleteVideo(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
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
        binding.yearDropdown.isEnabled = !exporting
        binding.durationDropdown.isEnabled = !exporting
        binding.startMonthDropdown.isEnabled = !exporting
        binding.endMonthDropdown.isEnabled = !exporting
        binding.ownerInput.isEnabled = !exporting
        binding.titleInput.isEnabled = !exporting
        if (exporting) {
            binding.videoReadyGroup.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun prepareAnotherVideo() {
        binding.videoReadyGroup.visibility = View.GONE
        binding.timelineSeek.progress = 0
        showProgress(0f)
        journey?.let { binding.statusText.text = journeySummary(it) }
    }

    private fun registerCreatedVideo(uri: Uri, request: ExportRequest) {
        val record = CreationRecord(
            uri = uri.toString(),
            title = request.title,
            fileName = "${request.title}.mp4",
            createdAtMillis = System.currentTimeMillis(),
            durationSeconds = request.durationSeconds,
            year = request.journey.year,
            startMonth = request.startMonth,
            endMonth = request.endMonth,
        )
        creationStore.upsert(record)
        renderCreations()
        lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                runCatching { creationMedia.inspect(uri) }.getOrNull()
            }
            if (metadata != null) {
                creationStore.upsert(
                    record.copy(
                        fileName = metadata.fileName,
                        durationSeconds = metadata.durationSeconds.takeIf { it > 0 } ?: request.durationSeconds,
                    ),
                )
                renderCreations()
            }
        }
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
        if (record.year != null && record.startMonth != null && record.endMonth != null) {
            parts += if (record.startMonth == 1 && record.endMonth == 12) {
                record.year.toString()
            } else {
                "${monthNames[record.startMonth - 1]}–${monthNames[record.endMonth - 1]} ${record.year}"
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

    private fun persistUriAccess(uri: Uri, includeWrite: Boolean) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val requested = read or if (includeWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(uri, requested)
        }.isSuccess
        if (!persisted && requested != read) {
            runCatching { contentResolver.takePersistableUriPermission(uri, read) }
        }
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
        val url = if (language == Locale.KOREAN.language) PRIVACY_URL_KO else PRIVACY_URL
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
            clipData = ClipData.newRawUri("Timeline video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Snackbar.make(binding.root, R.string.no_video_player, Snackbar.LENGTH_LONG).show() }
    }

    private fun shareVideo(uri: Uri) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Timeline video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.share_travel_video)))
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

    private data class ExportRequest(
        val journey: Journey,
        val title: String,
        val durationSeconds: Int,
        val startMonth: Int,
        val endMonth: Int,
    )

    companion object {
        private const val TITLE_UPDATE_DELAY_MS = 450L
        private const val COLLAPSED_CREATION_COUNT = 3
        private const val MAP_PRIVACY_ACCEPTED = "map_privacy_accepted_v1"
        private const val PROJECT_URL = "https://github.com/mahlernim/google-timeline-visualizer"
        private const val PRIVACY_URL =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.md"
        private const val PRIVACY_URL_KO =
            "https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.ko.md"
    }
}
