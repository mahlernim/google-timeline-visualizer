package dev.mahlernim.timelinevisualizer.videos

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoThumbnailDecodeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val media = VideoMedia(context)

    @Test
    fun scaledFramesKeepTheirAspectAndAreReusedFromCache() {
        listOf(320 to 180, 180 to 320, 320 to 320).forEachIndexed { index, (width, height) ->
            val uri = Uri.parse("content://example/scaled-thumbnail-$index")
            val source = DataSource.toDataSource(context, uri)
            val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // No full-sized frame is registered: calling getFrameAtTime would fail this test.
            ShadowMediaMetadataRetriever.addScaledFrame(source, -1L, 320, 320, frame)
            try {
                val thumbnail = media.createThumbnail(uri)
                assertSame(frame, thumbnail)
                assertEquals(width, thumbnail!!.width)
                assertEquals(height, thumbnail.height)

                ShadowMediaMetadataRetriever.addException(source, IllegalStateException("Cache should avoid decoding"))
                val cached = media.createThumbnail(uri)
                assertNotNull(cached)
                assertEquals(width, cached!!.width)
                assertEquals(height, cached.height)
                cached.recycle()
            } finally {
                frame.recycle()
                media.deleteThumbnail(uri)
            }
        }
    }

    @Test
    fun unavailableScaledFrameRetainsTheFullFrameCompatibilityFallback() {
        val uri = Uri.parse("content://example/no-scaled-thumbnail")
        val fullFrame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        ShadowMediaMetadataRetriever.addFrame(context, uri, -1L, fullFrame)
        try {
            val thumbnail = media.createThumbnail(uri)
            assertNotNull(thumbnail)
            assertEquals(320, thumbnail!!.width)
            assertEquals(180, thumbnail.height)
            assertTrue(fullFrame.isRecycled)
            thumbnail.recycle()
        } finally {
            if (!fullFrame.isRecycled) fullFrame.recycle()
            media.deleteThumbnail(uri)
        }
    }

    @Test
    @Config(sdk = [26])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun android26RetainsTheFullFrameCompatibilityFallback() {
        val uri = Uri.parse("content://example/legacy-thumbnail")
        val fullFrame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        ShadowMediaMetadataRetriever.addFrame(context, uri, -1L, fullFrame)
        try {
            val thumbnail = media.createThumbnail(uri)
            assertNotNull(thumbnail)
            assertEquals(320, thumbnail!!.width)
            assertEquals(180, thumbnail.height)
            assertTrue(fullFrame.isRecycled)
            thumbnail.recycle()
        } finally {
            if (!fullFrame.isRecycled) fullFrame.recycle()
            media.deleteThumbnail(uri)
        }
    }
}
