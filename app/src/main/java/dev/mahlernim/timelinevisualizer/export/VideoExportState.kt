package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VideoExportStatus { IDLE, RUNNING, COMPLETE, CANCELLED, FAILED }

data class VideoExportSnapshot(
    val status: VideoExportStatus = VideoExportStatus.IDLE,
    val progress: ExportProgress? = null,
    val startedAtMillis: Long = 0L,
    val outputUri: String? = null,
    val title: String? = null,
    val errorMessage: String? = null,
)

class VideoExportStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): VideoExportSnapshot {
        val status = runCatching {
            VideoExportStatus.valueOf(preferences.getString(KEY_STATUS, null) ?: return VideoExportSnapshot())
        }.getOrDefault(VideoExportStatus.IDLE)
        val phase = preferences.getString(KEY_PHASE, null)?.let { value ->
            runCatching { ExportPhase.valueOf(value) }.getOrNull()
        }
        val progress = phase?.let {
            ExportProgress(
                fraction = preferences.getFloat(KEY_FRACTION, 0f),
                phase = it,
                completed = preferences.getInt(KEY_COMPLETED, 0),
                total = preferences.getInt(KEY_TOTAL, 0),
            )
        }
        return VideoExportSnapshot(
            status = status,
            progress = progress,
            startedAtMillis = preferences.getLong(KEY_STARTED_AT, 0L),
            outputUri = preferences.getString(KEY_OUTPUT_URI, null),
            title = preferences.getString(KEY_TITLE, null),
            errorMessage = preferences.getString(KEY_ERROR, null),
        )
    }

    fun save(snapshot: VideoExportSnapshot) {
        preferences.edit {
            putString(KEY_STATUS, snapshot.status.name)
            putLong(KEY_STARTED_AT, snapshot.startedAtMillis)
            putString(KEY_OUTPUT_URI, snapshot.outputUri)
            putString(KEY_TITLE, snapshot.title)
            putString(KEY_ERROR, snapshot.errorMessage)
            snapshot.progress?.let { progress ->
                putString(KEY_PHASE, progress.phase.name)
                putFloat(KEY_FRACTION, progress.fraction)
                putInt(KEY_COMPLETED, progress.completed)
                putInt(KEY_TOTAL, progress.total)
            } ?: run {
                remove(KEY_PHASE)
                remove(KEY_FRACTION)
                remove(KEY_COMPLETED)
                remove(KEY_TOTAL)
            }
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES_NAME = "video_export_state"
        private const val KEY_STATUS = "status"
        private const val KEY_PHASE = "phase"
        private const val KEY_FRACTION = "fraction"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_TOTAL = "total"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_TITLE = "title"
        private const val KEY_ERROR = "error"
    }
}

object VideoExportCoordinator {
    private val mutableState = MutableStateFlow(VideoExportSnapshot())
    val state: StateFlow<VideoExportSnapshot> = mutableState.asStateFlow()
    private var restored = false

    @Synchronized
    fun restore(context: Context) {
        if (restored) return
        mutableState.value = VideoExportStateStore(context.applicationContext).load()
        restored = true
    }

    fun publish(context: Context, snapshot: VideoExportSnapshot, persist: Boolean = true) {
        mutableState.value = snapshot
        if (persist) VideoExportStateStore(context.applicationContext).save(snapshot)
    }

    fun clear(context: Context) {
        VideoExportStateStore(context.applicationContext).clear()
        mutableState.value = VideoExportSnapshot()
        VideoExportService.clearNotification(context)
    }

    internal fun resetForTest() {
        restored = false
        mutableState.value = VideoExportSnapshot()
    }
}
