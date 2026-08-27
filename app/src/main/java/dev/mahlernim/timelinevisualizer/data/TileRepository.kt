package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.mahlernim.timelinevisualizer.BuildConfig
import dev.mahlernim.timelinevisualizer.render.TileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets

internal const val CARTO_TILE_CACHE_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1_000

internal fun cartoTileUrl(id: TileId, apiKey: String): URL {
    val base = "https://a.basemaps.cartocdn.com/light_all/${id.zoom}/${id.x}/${id.y}.png"
    if (apiKey.isBlank()) return URL(base)
    val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())
    return URL("$base?key=$encodedKey")
}

class TileRepository internal constructor(
    context: Context,
    private val apiKey: String = BuildConfig.CARTO_BASEMAP_API_KEY.trim(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    private data class MemoryTile(val bitmap: Bitmap, val expiresAtMillis: Long)

    private val legacyCacheDirectory = File(context.cacheDir, "carto-tiles")
    private val cacheDirectory = File(context.cacheDir, "carto-tiles-v2").apply { mkdirs() }
    private val cachePolicyLock = Any()
    @Volatile private var cachePolicyEnforced = false
    private val memory = object : LruCache<String, MemoryTile>(32 * 1024) {
        override fun sizeOf(key: String, value: MemoryTile): Int = value.bitmap.byteCount / 1024
    }

    fun cached(id: TileId): Bitmap? {
        val key = id.key()
        val now = nowMillis()
        memory.get(key)?.let { cached ->
            if (now <= cached.expiresAtMillis) return cached.bitmap
            memory.remove(key)
        }
        val file = File(cacheDirectory, "$key.png")
        if (!file.isFile) return null
        val expiresAt = expirationTime(file.lastModified())
        if (now > expiresAt) {
            file.delete()
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            file.delete()
            return null
        }
        memory.put(key, MemoryTile(bitmap, expiresAt))
        return bitmap
    }

    suspend fun load(id: TileId): Bitmap? = withContext(Dispatchers.IO) {
        enforceCachePolicy()
        cached(id)?.let { return@withContext it }
        val key = id.key()
        val target = File(cacheDirectory, "$key.png")
        val temp = File.createTempFile("$key-", ".tmp", cacheDirectory)
        var connection: HttpURLConnection? = null
        try {
            connection = connectionFactory(cartoTileUrl(id, apiKey))
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "TimelineVisualizer-Android/1.0")
            if (connection.responseCode !in 200..299) return@withContext null
            connection.inputStream.use { input -> temp.outputStream().use(input::copyTo) }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setLastModified(nowMillis())
            cached(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
            connection?.disconnect()
        }
    }

    private fun enforceCachePolicy() {
        if (cachePolicyEnforced) return
        synchronized(cachePolicyLock) {
            if (cachePolicyEnforced) return
            legacyCacheDirectory.deleteRecursively()
            val now = nowMillis()
            cacheDirectory.listFiles()?.forEach { file ->
                if (file.extension == "tmp" || now > expirationTime(file.lastModified())) file.delete()
            }
            cachePolicyEnforced = true
        }
    }

    private fun expirationTime(lastModified: Long): Long {
        if (lastModified <= 0) return Long.MIN_VALUE
        return if (lastModified > Long.MAX_VALUE - CARTO_TILE_CACHE_MAX_AGE_MILLIS) {
            Long.MAX_VALUE
        } else {
            lastModified + CARTO_TILE_CACHE_MAX_AGE_MILLIS
        }
    }

    private fun TileId.key(): String = "${zoom}_${x}_${y}"
}
