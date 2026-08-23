package dev.mahlernim.timelinevisualizer.videos

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import java.io.File
import java.text.Normalizer

class GeneratedMediaRepository(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun createVideoDestination(title: String, period: TimelinePeriod): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val baseName = fileBaseName(title, period)
        val displayName = availableName(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "$baseName.mp4")
        return resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.TITLE, title)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Timeline Visualizer")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            },
        ) ?: error("Could not create the video destination")
    }

    fun finalizeVideo(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) > 0)
        }
    }

    fun discard(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    fun copyVideo(source: Uri, destination: Uri) {
        resolver.openInputStream(source)?.buffered()?.use { input ->
            resolver.openOutputStream(destination, "w")?.buffered()?.use(input::copyTo)
                ?: error("Video destination unavailable")
        } ?: error("Video file unavailable")
    }

    fun saveOverview(source: File, displayBaseName: String): Uri {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val name = availableName(collection, "${sanitize(displayBaseName)}-overview.png")
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Timeline Visualizer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: error("Could not create the image destination")
        return try {
            source.inputStream().buffered().use { input ->
                resolver.openOutputStream(uri, "w")?.buffered()?.use(input::copyTo)
                    ?: error("Image destination unavailable")
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0)
            uri
        } catch (error: Throwable) {
            discard(uri)
            throw error
        }
    }

    internal fun fileBaseName(title: String, period: TimelinePeriod): String = listOf(
        sanitize(title),
        "%04d-%02d_%04d-%02d".format(period.startYear, period.startMonth, period.endYear, period.endMonth),
    ).filter(String::isNotBlank).joinToString("-").ifBlank { "Timeline-video" }

    private fun availableName(collection: Uri, requested: String): String {
        val stem = requested.substringBeforeLast('.')
        val extension = requested.substringAfterLast('.', "")
        var candidate = requested
        var suffix = 2
        while (exists(collection, candidate)) {
            candidate = "$stem ($suffix)${if (extension.isBlank()) "" else ".$extension"}"
            suffix += 1
        }
        return candidate
    }

    private fun exists(collection: Uri, displayName: String): Boolean = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(displayName),
        null,
    )?.use { it.moveToFirst() } == true

    internal fun sanitize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(80)
}
