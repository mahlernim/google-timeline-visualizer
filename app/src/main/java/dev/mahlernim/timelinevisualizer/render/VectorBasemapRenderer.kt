package dev.mahlernim.timelinevisualizer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import dev.mahlernim.timelinevisualizer.BuildConfig
import dev.mahlernim.timelinevisualizer.R
import dev.mahlernim.timelinevisualizer.data.CartoAuthentication
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.log2
import kotlin.math.sinh

/** One bounded background reused while the camera stays within its coverage. */
class VectorBasemapRenderer private constructor(context: Context, private val width: Int, private val height: Int) {
    private val dimensions = snapshotDimensions(width, height)
    private val mapWidth = dimensions.first
    private val mapHeight = dimensions.second
    private var closed = false
    private val snapshotter = MapSnapshotter(context, MapSnapshotter.Options(mapWidth, mapHeight)
        .withPixelRatio(1f).withLogo(false).withAttribution(false)
        .withStyleBuilder(Style.Builder().fromUri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json")))
    private var coverage: Viewport? = null
    private var requestedCoverage: Viewport? = null
    private var background: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val logo = requireNotNull(context.getDrawable(R.drawable.carto_logo))

    suspend fun draw(canvas: Canvas, view: Viewport) {
        check(!closed) { "Vector renderer is closed" }
        val previous = requestedCoverage
        if (previous == null || !reusable(previous, view)) {
            val next = expanded(view)
            val geometry = snapshotGeometry(next, mapWidth)
            try {
                withContext(Dispatchers.Main) {
                    val rendered = withTimeout(30_000) {
                        suspendCancellableCoroutine<RenderedBackground> { continuation ->
                            val position = camera(geometry.viewport, geometry.width)
                            snapshotter.setSize(geometry.width, geometry.height)
                            snapshotter.setCameraPosition(position)
                            continuation.invokeOnCancellation {
                                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) snapshotter.cancel()
                                else android.os.Handler(android.os.Looper.getMainLooper()).post { snapshotter.cancel() }
                            }
                            snapshotter.start({ snapshot ->
                                if (!continuation.isActive || closed) snapshot.bitmap.recycle()
                                else {
                                    // Native cameras constrain the poles and round snapshot dimensions.
                                    // Recover the actual Mercator coverage instead of assuming the requested camera.
                                    val target = requireNotNull(position.target)
                                    val center = snapshot.pixelForLatLng(target)
                                    val dx = minOf((next.maxX - next.minX) / 8, .01)
                                    val probe = snapshot.pixelForLatLng(LatLng(target.latitude, target.longitude + dx * 360))
                                    val unitsPerPixel = dx / (probe.x - center.x)
                                    val cx = (geometry.viewport.minX + geometry.viewport.maxX) / 2
                                    val cy = (geometry.viewport.minY + geometry.viewport.maxY) / 2
                                    val minX = cx - center.x * unitsPerPixel
                                    val minY = cy - center.y * unitsPerPixel
                                    val actual = next.copy(minX = minX, maxX = minX + snapshot.bitmap.width * unitsPerPixel,
                                        minY = minY, maxY = minY + snapshot.bitmap.height * unitsPerPixel)
                                    if (!unitsPerPixel.isFinite() || unitsPerPixel <= 0 ||
                                        abs(actual.maxX - actual.minX - (next.maxX - next.minX)) > unitsPerPixel * 2) {
                                        snapshot.bitmap.recycle()
                                        continuation.resumeWithException(VectorMapException())
                                    } else continuation.resume(RenderedBackground(snapshot.bitmap, actual)) { _, discarded, _ ->
                                        discarded.bitmap.recycle()
                                    }
                                }
                            }, { _ ->
                                if (continuation.isActive) continuation.resumeWithException(VectorMapException())
                            })
                        }
                    }
                    background?.recycle()
                    background = rendered.bitmap
                    coverage = rendered.viewport
                    requestedCoverage = next
                }
            } catch (_: TimeoutCancellationException) {
                throw VectorMapException()
            }
        }
        val cached = requireNotNull(coverage)
        val sx = width.toDouble()/(view.maxX-view.minX)*(cached.maxX-cached.minX)/requireNotNull(background).width
        val sy = height.toDouble()/(view.maxY-view.minY)*(cached.maxY-cached.minY)/requireNotNull(background).height
        val tx = (cached.minX-view.minX)/(view.maxX-view.minX)*width
        val ty = (cached.minY-view.minY)/(view.maxY-view.minY)*height
        val transform = Matrix().apply { setValues(floatArrayOf(sx.toFloat(),0f,tx.toFloat(),0f,sy.toFloat(),ty.toFloat(),0f,0f,1f)) }
        canvas.drawColor(0xFFFAFAF8.toInt())
        canvas.drawBitmap(requireNotNull(background), transform, paint)
    }

    fun drawLogo(canvas: Canvas) {
        val w = (minOf(width,height)*.15).toInt().coerceAtLeast(66)
        val h = w*64/164
        canvas.drawRect(4f,(height-h-8).toFloat(),(w+12).toFloat(),(height-4).toFloat(),Paint().apply { color=0xE6FFFFFF.toInt() })
        logo.setBounds(8,height-h-6,w+8,height-6)
        logo.draw(canvas)
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.Main) {
        closed = true
        snapshotter.cancel()
        background?.recycle()
        background = null
        coverage = null
        requestedCoverage = null
    }

    private data class RenderedBackground(val bitmap: Bitmap, val viewport: Viewport)

    private fun camera(view: Viewport, pixelWidth: Int): CameraPosition = CameraPosition.Builder()
        .target(LatLng(atan(sinh(Math.PI*(1-view.minY-view.maxY)))*180/Math.PI,(view.minX+view.maxX)/2*360-180))
        .zoom(log2(pixelWidth/(512*(view.maxX-view.minX)))).build()

    companion object {
        private var initialized = false
        suspend fun create(context: Context, width: Int, height: Int): VectorBasemapRenderer = withContext(Dispatchers.Main) {
            if (BuildConfig.CARTO_BASEMAP_API_KEY.isBlank()) throw VectorMapException()
            require(width > 0 && height > 0)
            val app = context.applicationContext
            val activityManager = app.getSystemService(android.app.ActivityManager::class.java)
            if (activityManager.deviceConfigurationInfo.reqGlEsVersion < 0x30000) throw VectorMapException()
            if (!initialized) {
                org.maplibre.android.log.Logger.setVerbosity(org.maplibre.android.log.Logger.NONE)
                MapLibre.getInstance(app)
                val authentication = CartoAuthentication(app)
                HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val carto = request.url.isHttps && CartoAuthentication.isCartoHost(host)
                    chain.proceed(if (carto) request.newBuilder()
                        .url(request.url.newBuilder().setQueryParameter("key",BuildConfig.CARTO_BASEMAP_API_KEY).build())
                        .apply { authentication.headers.forEach { (name, value) -> header(name, value) } }.build() else request)
                }.build())
                initialized = true
            }
            try {
                withTimeout(30_000) { dev.mahlernim.timelinevisualizer.data.VectorMapCache.prepare(app) }
            } catch (_: TimeoutCancellationException) {
                throw VectorMapException()
            }
            VectorBasemapRenderer(app, width, height)
        }
        internal data class SnapshotGeometry(val viewport: Viewport, val width: Int, val height: Int)

        internal fun snapshotGeometry(view: Viewport, preferredWidth: Int): SnapshotGeometry {
            // Native cameras cannot show space beyond Mercator's poles. Render only the
            // valid latitude range, then place it in the original output viewport.
            val clipped = view.copy(minY = view.minY.coerceAtLeast(0.0), maxY = view.maxY.coerceAtMost(1.0))
            val spanX = clipped.maxX - clipped.minX
            val width = maxOf(preferredWidth, kotlin.math.ceil(spanX * 512).toInt())
            val height = kotlin.math.floor(width * (clipped.maxY - clipped.minY) / spanX).toInt().coerceAtLeast(1)
            if (width > 4096 || height > 4096 || clipped.minY >= clipped.maxY) throw VectorMapException()
            return SnapshotGeometry(clipped, width, height)
        }

        // Includes overscan. Capping both dimensions bounds the CPU bitmap to 64 MiB.
        internal fun snapshotDimensions(width: Int, height: Int): Pair<Int, Int> {
            val scale = minOf(1.5, 4096.0 / maxOf(width, height))
            return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        }

        internal fun expanded(v: Viewport): Viewport {
            val dx = (v.maxX - v.minX) * .25
            val dy = (v.maxY - v.minY) * .25
            return v.copy(minX = v.minX - dx, maxX = v.maxX + dx, minY = v.minY - dy, maxY = v.maxY + dy)
        }
        internal fun reusable(c: Viewport, v: Viewport): Boolean =
            abs((c.maxX - c.minX) / 1.5 / (v.maxX - v.minX) - 1) <= .02 &&
                v.minX >= c.minX && v.maxX <= c.maxX && v.minY.coerceAtLeast(0.0) >= c.minY - 1e-6 &&
                v.maxY.coerceAtMost(1.0) <= c.maxY + 1e-6
    }
}

/** Deliberately excludes native messages, which can contain authenticated URLs. */
class VectorMapException : RuntimeException("Vector map unavailable")
