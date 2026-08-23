package dev.mahlernim.timelinevisualizer.videos

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import java.time.YearMonth
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeneratedMediaRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = GeneratedMediaRepository(context)
    private val period = TimelinePeriod(YearMonth.of(2025, 1), YearMonth.of(2026, 8))

    @Test
    fun videoDestinationUsesPendingMediaStoreValuesAndFinalizes() {
        val uri = repository.createVideoDestination("Mina 서울/東京", period)!!
        val values = context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.IS_PENDING,
            ),
            null,
            null,
            null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            List(5) { cursor.getString(it) }
        }

        assertEquals("Mina 서울 東京-2025-01_2026-08.mp4", values[0])
        assertEquals("Mina 서울/東京", values[1])
        assertEquals("video/mp4", values[2])
        assertEquals("Movies/Timeline Visualizer", values[3].trimEnd('/'))
        assertEquals("1", values[4])

        repository.finalizeVideo(uri)
        val pending = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.IS_PENDING),
            null,
            null,
            null,
        )!!.use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        assertEquals(0, pending)
    }

    @Test
    fun collisionsUseNumericSuffixAndDiscardRemovesPendingItem() {
        val first = repository.createVideoDestination("여행", period)!!
        val second = repository.createVideoDestination("여행", period)!!
        assertNotEquals(first, second)
        val secondName = context.contentResolver.query(second, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)!!.use {
            it.moveToFirst(); it.getString(0)
        }
        assertTrue(secondName.endsWith(" (2).mp4"))

        repository.discard(second)
        assertFalse(repositoryIsAvailable(second))
        repository.discard(first)
    }

    @Test
    @Config(sdk = [28])
    fun androidEightUsesSystemPickerFallback() {
        assertEquals(null, repository.createVideoDestination("Journey", period))
    }

    @Test
    fun completedVideoCanBeCopiedWithoutRenderingAndDeleted() {
        val source = repository.createVideoDestination("Source", period)!!
        context.contentResolver.openOutputStream(source, "w")!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        repository.finalizeVideo(source)
        val destination = repository.createVideoDestination("Copy", period)!!

        repository.copyVideo(source, destination)
        repository.finalizeVideo(destination)

        val copied = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
        assertTrue(copied.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(VideoMedia(context).delete(destination))
        repository.discard(source)
    }

    @Test
    fun overviewIsSavedOnlyWhenRequested() {
        val source = File(context.cacheDir, "overview-source.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val uri = repository.saveOverview(source, "서울 東京")
        val values = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING),
            null,
            null,
            null,
        )!!.use { cursor -> cursor.moveToFirst(); List(3) { cursor.getString(it) } }
        assertEquals("서울 東京-overview.png", values[0])
        assertEquals("Pictures/Timeline Visualizer", values[1].trimEnd('/'))
        assertEquals("0", values[2])
        repository.discard(uri)
    }

    private fun repositoryIsAvailable(uri: android.net.Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
