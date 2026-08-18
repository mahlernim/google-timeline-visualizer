package dev.mahlernim.timelinevisualizer.creations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreationMediaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val media = CreationMedia(context)

    @Test
    fun generatedOverviewProvidesPngAndDeterministicThumbnail() {
        val uri = Uri.parse("content://example/generated-video")
        val overview = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        try {
            media.saveGeneratedOverview(uri, overview)
            val output = ByteArrayOutputStream()

            assertTrue(media.copyOverview(uri, output))
            assertTrue(output.toByteArray().take(8).toByteArray().contentEquals(PNG_HEADER))
            val thumbnail = media.loadThumbnail(uri)
            assertNotNull(thumbnail)
            assertEquals(320, thumbnail!!.width)
            assertEquals(320, thumbnail.height)
            thumbnail.recycle()
        } finally {
            overview.recycle()
            media.deleteThumbnail(uri)
            media.deleteOverview(uri)
        }
    }

    companion object {
        private val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
