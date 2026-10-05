package dev.mahlernim.timelinevisualizer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VectorOverlayBoundaryTest {
    @Test fun overlaysPreserveTheProvidedVectorBackground() {
        val journey = Journey.from(listOf(
            GeoPoint(Instant.EPOCH,37.5,127.0),
            GeoPoint(Instant.EPOCH.plusSeconds(3600),37.6,127.1),
        ),1970)
        val bitmap = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        val painter = TimelinePainter()
        painter.draw(Canvas(bitmap),480,480,journey,TimelineFrame(.5f,0f),10,"Test",
            tiles = { error("The vector overlay pass must not request raster tiles") }, drawMapBackground=false)
        assertEquals(Color.MAGENTA,bitmap.getPixel(0,0))
        assertNotEquals(Color.MAGENTA,bitmap.getPixel(240,45))
        painter.draw(Canvas(bitmap),480,480,journey,TimelineFrame(.5f,0f),10,"Test",tiles = { null })
        assertNotEquals(Color.MAGENTA,bitmap.getPixel(0,0))
        bitmap.recycle()
    }
}
