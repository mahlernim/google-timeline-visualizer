package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.render.VectorBasemapRenderer
import dev.mahlernim.timelinevisualizer.render.Viewport
import org.junit.Assert.*
import org.junit.Test

class VectorMapPolicyTest {
    @Test fun credentialsOnlyBelongToCartoBasemapHosts() {
        assertTrue(CartoAuthentication.isCartoHost("basemaps.cartocdn.com"))
        assertTrue(CartoAuthentication.isCartoHost("tiles.basemaps.cartocdn.com"))
        assertFalse(CartoAuthentication.isCartoHost("basemaps.cartocdn.com.example.com"))
        assertFalse(CartoAuthentication.isCartoHost("evilbasemaps.cartocdn.com"))
    }

    @Test fun cacheExpiresOnFirstUseAtThirtyDaysAndAfterClockRollback() {
        val start = 1_800_000_000_000L
        assertTrue(VectorMapCache.mustClear(0, start))
        assertTrue(VectorMapCache.mustClear(start, start - 1))
        assertFalse(VectorMapCache.mustClear(start, start + CARTO_TILE_CACHE_MAX_AGE_MILLIS - 1))
        assertTrue(VectorMapCache.mustClear(start, start + CARTO_TILE_CACHE_MAX_AGE_MILLIS))
    }

    @Test fun oversizedVideoFormatsKeepSnapshotMemoryBoundedAndAspectRatio() {
        for ((width, height) in listOf(480 to 480, 3412 to 1920, 1920 to 3412, 3840 to 2160)) {
            val (w, h) = VectorBasemapRenderer.snapshotDimensions(width, height)
            assertTrue(w <= 4096 && h <= 4096)
            assertTrue(w.toLong() * h * 4 <= 64L * 1024 * 1024)
            assertEquals(width.toDouble() / height, w.toDouble() / h, 0.002)
        }
    }

    @Test fun reuseRejectsUncoveredPanAndZoomChanges() {
        val view = Viewport(.45, .55, .45, .55, 8)
        val cached = VectorBasemapRenderer.expanded(view)
        assertTrue(VectorBasemapRenderer.reusable(cached, view.copy(minX=.46, maxX=.56)))
        assertFalse(VectorBasemapRenderer.reusable(cached, view.copy(minX=.5, maxX=.6)))
        assertFalse(VectorBasemapRenderer.reusable(cached, view.copy(minX=.46, maxX=.54)))
    }
}
