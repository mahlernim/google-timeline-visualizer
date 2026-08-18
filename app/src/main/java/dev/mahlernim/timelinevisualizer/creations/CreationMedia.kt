package dev.mahlernim.timelinevisualizer.creations

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import dev.mahlernim.timelinevisualizer.R
import java.io.OutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min

data class VideoMetadata(
    val fileName: String,
    val durationSeconds: Int,
    val lastModifiedMillis: Long,
)

class CreationMedia(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun inspect(uri: Uri): VideoMetadata {
        var fileName: String? = null
        var lastModified = 0L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) fileName = cursor.getString(nameIndex)
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) lastModified = cursor.getLong(modifiedIndex)
                }
            }
        }

        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.let { ((it + 500L) / 1_000L).toInt() }
                    ?: 0
            } finally {
                retriever.release()
            }
        }.getOrDefault(0)
        return VideoMetadata(
            fileName = fileName?.takeIf(String::isNotBlank) ?: context.getString(R.string.default_video_filename),
            durationSeconds = duration.coerceAtLeast(0),
            lastModifiedMillis = lastModified,
        )
    }

    fun isAvailable(uri: Uri): Boolean = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    fun delete(uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, uri)
    }.getOrDefault(false)

    fun loadThumbnail(uri: Uri): Bitmap? = thumbnailFile(uri).takeIf(File::isFile)?.let { file ->
        BitmapFactory.decodeFile(file.absolutePath)
    }

    fun createThumbnail(uri: Uri): Bitmap? {
        loadThumbnail(uri)?.let { return it }
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        } ?: return null

        val scale = min(THUMBNAIL_SIZE.toFloat() / frame.width, THUMBNAIL_SIZE.toFloat() / frame.height)
            .coerceAtMost(1f)
        val width = (frame.width * scale).toInt().coerceAtLeast(1)
        val height = (frame.height * scale).toInt().coerceAtLeast(1)
        val thumbnail = if (width == frame.width && height == frame.height) frame else {
            frame.scale(width, height).also { frame.recycle() }
        }
        val destination = thumbnailFile(uri)
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        return thumbnail
    }

    fun saveGeneratedOverview(uri: Uri, overview: Bitmap) {
        val png = overviewFile(uri)
        png.parentFile?.mkdirs()
        FileOutputStream(png).use { output ->
            check(overview.compress(Bitmap.CompressFormat.PNG, 100, output))
        }

        val scale = min(THUMBNAIL_SIZE.toFloat() / overview.width, THUMBNAIL_SIZE.toFloat() / overview.height)
        val thumbnail = overview.scale(
            (overview.width * scale).toInt().coerceAtLeast(1),
            (overview.height * scale).toInt().coerceAtLeast(1),
        )
        try {
            val destination = thumbnailFile(uri)
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 88, output))
            }
        } finally {
            if (thumbnail !== overview) thumbnail.recycle()
        }
    }

    fun cachedOverview(uri: Uri): File? = overviewFile(uri).takeIf(File::isFile)

    fun copyOverview(uri: Uri, output: OutputStream): Boolean {
        val source = cachedOverview(uri) ?: return false
        source.inputStream().buffered().use { input -> input.copyTo(output) }
        return true
    }

    fun deleteOverview(uri: Uri) {
        overviewFile(uri).delete()
    }

    fun pruneOverviewCache(nowMillis: Long = System.currentTimeMillis()) {
        overviewDirectory().listFiles()?.forEach { file ->
            if (nowMillis - file.lastModified() > OVERVIEW_CACHE_MAX_AGE_MS) file.delete()
        }
    }

    fun deleteThumbnail(uri: Uri) {
        thumbnailFile(uri).delete()
    }

    private fun thumbnailFile(uri: Uri): File {
        return File(File(context.filesDir, "creation-thumbnails"), "${uriKey(uri)}.jpg")
    }

    private fun overviewFile(uri: Uri): File = File(overviewDirectory(), "${uriKey(uri)}.png")

    private fun overviewDirectory(): File = File(context.cacheDir, "overview-images")

    private fun uriKey(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val THUMBNAIL_SIZE = 320
        private const val OVERVIEW_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
