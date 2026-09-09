package dev.mahlernim.timelinevisualizer.videos

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import dev.mahlernim.timelinevisualizer.R
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.min

data class VideoMetadata(val fileName: String, val durationSeconds: Int, val lastModifiedMillis: Long)

class VideoMedia(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun inspect(uri: Uri): VideoMetadata {
        var fileName: String? = null
        var lastModified = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { fileName = cursor.getString(it) }
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !cursor.isNull(it) }?.let { lastModified = cursor.getLong(it) }
                }
            }
        }
        val duration = runCatching {
            MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ((it + 500) / 1_000).toInt() } ?: 0
                } finally { retriever.release() }
            }
        }.getOrDefault(0)
        return VideoMetadata(fileName?.takeIf(String::isNotBlank) ?: context.getString(R.string.default_video_filename), duration.coerceAtLeast(0), lastModified)
    }

    fun isAvailable(uri: Uri): Boolean = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    fun delete(uri: Uri): Boolean {
        if (runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)) return true
        return runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    fun loadThumbnail(uri: Uri): Bitmap? = thumbnailFile(uri).takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.absolutePath) }

    fun createThumbnail(uri: Uri): Bitmap? {
        loadThumbnail(uri)?.let { return it }
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // Decode for the library card, not at the video's potentially 4K resolution.
                val scaledFrame = try {
                    retriever.getScaledFrameAtTime(
                        -1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_SIZE, THUMBNAIL_SIZE,
                    )
                } catch (_: RuntimeException) {
                    null
                }
                // Some media decoders cannot provide a scaled frame. Preserve the legacy fallback for them.
                scaledFrame ?: retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                // Android 8.0 does not provide scaled frame extraction.
                retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            retriever.release()
        } ?: return null
        val scale = min(THUMBNAIL_SIZE.toFloat() / frame.width, THUMBNAIL_SIZE.toFloat() / frame.height).coerceAtMost(1f)
        val width = (frame.width * scale).toInt().coerceAtLeast(1)
        val height = (frame.height * scale).toInt().coerceAtLeast(1)
        val thumbnail = if (width == frame.width && height == frame.height) frame else frame.scale(width, height).also { frame.recycle() }
        val destination = thumbnailFile(uri)
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        return thumbnail
    }

    fun saveGeneratedOverview(uri: Uri, overview: Bitmap) {
        val png = overviewFile(uri)
        png.parentFile?.mkdirs()
        FileOutputStream(png).use { check(overview.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val scale = min(THUMBNAIL_SIZE.toFloat() / overview.width, THUMBNAIL_SIZE.toFloat() / overview.height)
        val thumbnail = overview.scale((overview.width * scale).toInt().coerceAtLeast(1), (overview.height * scale).toInt().coerceAtLeast(1))
        try {
            val destination = thumbnailFile(uri)
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        } finally { if (thumbnail !== overview) thumbnail.recycle() }
    }

    fun cachedOverview(uri: Uri): File? = overviewFile(uri).takeIf(File::isFile)
    fun copyOverview(uri: Uri, output: OutputStream): Boolean {
        val source = cachedOverview(uri) ?: return false
        source.inputStream().buffered().use { it.copyTo(output) }
        return true
    }
    fun deleteOverview(uri: Uri) { overviewFile(uri).delete() }
    fun deleteThumbnail(uri: Uri) { thumbnailFile(uri).delete() }
    fun pruneOverviewCache(nowMillis: Long = System.currentTimeMillis()) {
        overviewDirectory().listFiles()?.forEach { if (nowMillis - it.lastModified() > OVERVIEW_CACHE_MAX_AGE_MS) it.delete() }
    }
    private fun thumbnailFile(uri: Uri) = File(File(context.filesDir, "creation-thumbnails"), "${uriKey(uri)}.jpg")
    private fun overviewFile(uri: Uri) = File(overviewDirectory(), "${uriKey(uri)}.png")
    private fun overviewDirectory() = File(context.cacheDir, "overview-images")
    private fun uriKey(uri: Uri) = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val THUMBNAIL_SIZE = 320
        private const val OVERVIEW_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
