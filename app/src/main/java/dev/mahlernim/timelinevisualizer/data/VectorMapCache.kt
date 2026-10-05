package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import dev.mahlernim.timelinevisualizer.render.VectorMapException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Conservatively clears the whole ambient cache at least once every 30 days. */
internal object VectorMapCache {
    private val lock = Mutex()
    private var sizeConfigured = false
    private var pathConfigured = false

    suspend fun prepare(context: Context) = lock.withLock {
        val manager = OfflineManager.getInstance(context)
        if (!pathConfigured) {
            val directory = File(context.cacheDir, "carto-vector").apply { mkdirs() }
            if (FileSource.getResourcesCachePath(context) != directory.absolutePath) {
                // Also clears resources left in the SDK's default files directory by preview builds.
                awaitOperation(manager::clearAmbientCache)
                suspendCancellableCoroutine<Unit> { continuation ->
                    FileSource.setResourcesCachePath(directory.absolutePath,
                        object : FileSource.ResourcesCachePathChangeCallback {
                            override fun onSuccess(path: String) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            override fun onError(message: String) {
                                if (continuation.isActive) continuation.resumeWithException(VectorMapException())
                            }
                        })
                }
            }
            pathConfigured = true
        }
        if (!sizeConfigured) {
            awaitOperation { manager.setMaximumAmbientCacheSize(128L * 1024 * 1024, it) }
            sizeConfigured = true
        }
        val preferences = context.getSharedPreferences("vector-map-cache", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val clearedAt = preferences.getLong("cleared-at", 0)
        if (mustClear(clearedAt, now)) {
            awaitOperation(manager::clearAmbientCache)
            preferences.edit().putLong("cleared-at", now).apply()
        }
    }

    internal fun mustClear(clearedAt: Long, now: Long): Boolean =
        clearedAt <= 0 || now < clearedAt || now - clearedAt >= CARTO_TILE_CACHE_MAX_AGE_MILLIS

    private suspend fun awaitOperation(operation: (OfflineManager.FileSourceCallback) -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            operation(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onError(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(VectorMapException())
                }
            })
        }
}
