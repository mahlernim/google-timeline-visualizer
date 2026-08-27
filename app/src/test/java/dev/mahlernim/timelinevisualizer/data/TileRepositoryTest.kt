package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.render.TileId
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TileRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val currentCache = File(context.cacheDir, "carto-tiles-v2")
    private val legacyCache = File(context.cacheDir, "carto-tiles")

    @Before
    fun setUp() {
        currentCache.deleteRecursively()
        legacyCache.deleteRecursively()
    }

    @After
    fun tearDown() {
        currentCache.deleteRecursively()
        legacyCache.deleteRecursively()
    }

    @Test
    fun cartoUrlIncludesAnEncodedProjectKey() {
        val url = cartoTileUrl(TileId(13, 6985, 3172), "project key/value")

        assertEquals("/light_all/13/6985/3172.png", url.path)
        assertEquals("key=project+key%2Fvalue", url.query)
    }

    @Test
    fun tileExactlyThirtyDaysOldRemainsAvailable() {
        val now = 1_800_000_000_000L
        val id = TileId(5, 1, 2)
        val file = writeTile(id, now - CARTO_TILE_CACHE_MAX_AGE_MILLIS)
        val repository = repository(now)

        assertNotNull(repository.cached(id))
        assertTrue(file.isFile)
    }

    @Test
    fun tileOlderThanThirtyDaysIsDeleted() {
        val now = 1_800_000_000_000L
        val id = TileId(5, 1, 2)
        val file = writeTile(id, now - CARTO_TILE_CACHE_MAX_AGE_MILLIS - 1)
        val repository = repository(now)

        assertNull(repository.cached(id))
        assertFalse(file.exists())
    }

    @Test
    fun oldWatermarkedCacheIsRemovedBeforeAnyDownload() = runBlocking {
        val oldTile = File(legacyCache.apply { mkdirs() }, "5_1_2.png").apply { writeText("old") }
        val repository = repository(1_800_000_000_000L)

        assertNull(repository.load(TileId(5, 1, 2)))
        assertFalse(oldTile.exists())
        assertFalse(legacyCache.exists())
    }

    private fun repository(now: Long) = TileRepository(
        context = context,
        apiKey = "test-key",
        nowMillis = { now },
        connectionFactory = { throw IOException("No network in unit tests") },
    )

    private fun writeTile(id: TileId, lastModified: Long): File {
        val file = File(currentCache.apply { mkdirs() }, "${id.zoom}_${id.x}_${id.y}.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        file.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        assertTrue(file.setLastModified(lastModified))
        return file
    }
}
