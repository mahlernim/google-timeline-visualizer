package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.time.LocalDate

data class TimelineSourceMetadata(
    val fileName: String,
    val importedAtMillis: Long,
    val semanticStart: LocalDate?,
    val semanticEnd: LocalDate?,
    val rawStart: LocalDate?,
    val rawEnd: LocalDate?,
    val fileSizeBytes: Long? = null,
)

class TimelineSourceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Uri? = preferences.getString(KEY_URI, null)?.let(Uri::parse)

    fun replace(uri: Uri): Boolean = preferences.edit()
        .putString(KEY_URI, uri.toString())
        .commit()

    fun metadata(): TimelineSourceMetadata? {
        val importedAt = preferences.getLong(KEY_IMPORTED_AT, 0L)
        if (importedAt <= 0L) return null
        return TimelineSourceMetadata(
            fileName = preferences.getString(KEY_FILE_NAME, null).orEmpty(),
            importedAtMillis = importedAt,
            semanticStart = preferences.localDate(KEY_SEMANTIC_START),
            semanticEnd = preferences.localDate(KEY_SEMANTIC_END),
            rawStart = preferences.localDate(KEY_RAW_START),
            rawEnd = preferences.localDate(KEY_RAW_END),
            fileSizeBytes = preferences.getLong(KEY_FILE_SIZE, -1L).takeIf { it >= 0L },
        )
    }

    fun updateMetadata(metadata: TimelineSourceMetadata) {
        preferences.edit {
            putString(KEY_FILE_NAME, metadata.fileName)
            putLong(KEY_IMPORTED_AT, metadata.importedAtMillis)
            putOptionalDate(KEY_SEMANTIC_START, metadata.semanticStart)
            putOptionalDate(KEY_SEMANTIC_END, metadata.semanticEnd)
            putOptionalDate(KEY_RAW_START, metadata.rawStart)
            putOptionalDate(KEY_RAW_END, metadata.rawEnd)
            if (metadata.fileSizeBytes == null) remove(KEY_FILE_SIZE)
            else putLong(KEY_FILE_SIZE, metadata.fileSizeBytes)
        }
    }

    fun beginImport(uri: Uri): Boolean = preferences.edit()
        .putString(KEY_IMPORT_IN_PROGRESS_URI, uri.toString())
        .commit()

    fun completeImport(uri: Uri) {
        if (importInProgress() == uri) {
            preferences.edit().remove(KEY_IMPORT_IN_PROGRESS_URI).commit()
        }
    }

    fun recoverInterruptedImport(): Uri? {
        val pending = importInProgress() ?: return null
        val remembered = load()
        val interruptedRemembered = remembered?.takeIf { it == pending }
        preferences.edit().apply {
            remove(KEY_IMPORT_IN_PROGRESS_URI)
            if (interruptedRemembered != null) {
                remove(KEY_URI)
                remove(KEY_FILE_NAME)
                remove(KEY_IMPORTED_AT)
                remove(KEY_SEMANTIC_START)
                remove(KEY_SEMANTIC_END)
                remove(KEY_RAW_START)
                remove(KEY_RAW_END)
                remove(KEY_FILE_SIZE)
            }
        }.commit()
        return interruptedRemembered
    }

    fun clear(): Uri? {
        val previous = load()
        preferences.edit {
            remove(KEY_URI)
            remove(KEY_IMPORT_IN_PROGRESS_URI)
            remove(KEY_FILE_NAME)
            remove(KEY_IMPORTED_AT)
            remove(KEY_SEMANTIC_START)
            remove(KEY_SEMANTIC_END)
            remove(KEY_RAW_START)
            remove(KEY_RAW_END)
            remove(KEY_FILE_SIZE)
        }
        return previous
    }

    internal fun importInProgress(): Uri? =
        preferences.getString(KEY_IMPORT_IN_PROGRESS_URI, null)?.let(Uri::parse)

    internal fun clearForTest() {
        preferences.edit { clear() }
    }

    private fun android.content.SharedPreferences.localDate(key: String): LocalDate? =
        getString(key, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun android.content.SharedPreferences.Editor.putOptionalDate(key: String, value: LocalDate?) {
        if (value == null) remove(key) else putString(key, value.toString())
    }

    companion object {
        private const val PREFERENCES_NAME = "timeline_source"
        private const val KEY_URI = "document_uri_v1"
        private const val KEY_IMPORT_IN_PROGRESS_URI = "import_in_progress_uri_v1"
        private const val KEY_FILE_NAME = "file_name_v1"
        private const val KEY_IMPORTED_AT = "imported_at_v1"
        private const val KEY_SEMANTIC_START = "semantic_start_v1"
        private const val KEY_SEMANTIC_END = "semantic_end_v1"
        private const val KEY_RAW_START = "raw_start_v1"
        private const val KEY_RAW_END = "raw_end_v1"
        private const val KEY_FILE_SIZE = "file_size_v2"
    }
}
