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
    private val snapshotter = MapSnapshotter(context, MapSnapshotter.Options(mapWidth,mapHeight)
        .withPixelRatio(1f).withLogo(false).withAttribution(false)
        .withStyleBuilder(Style.Builder().fromUri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json")))
    private var coverage: Viewport? = null
    private var background: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val logo = requireNotNull(context.getDrawable(R.drawable.carto_logo))

    suspend fun draw(canvas: Canvas, view: Viewport) {
        check(!closed) { "Vector renderer is closed" }
        val previous = coverage
        if (previous == null || !reusable(previous,view)) {
            val next = expanded(view)
            val bitmap = try { withContext(Dispatchers.Main) {
                withTimeout(30_000) {
                    suspendCancellableCoroutine<Bitmap> { continuation ->
                        val position = camera(next)
                        snapshotter.setCameraPosition(position)
                        continuation.invokeOnCancellation {
                            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) snapshotter.cancel()
                            else android.os.Handler(android.os.Looper.getMainLooper()).post { snapshotter.cancel() }
                        }
                        snapshotter.start({ snapshot ->
                            if (!continuation.isActive || closed) snapshot.bitmap.recycle()
                            else {
                                val center = snapshot.pixelForLatLng(position.target!!)
                                val probe = snapshot.pixelForLatLng(LatLng(position.target!!.latitude,
                                    position.target!!.longitude + (next.maxX-next.minX)*90))
                                if (abs(center.x-mapWidth/2f)>2f || abs(center.y-mapHeight/2f)>2f ||
                                    abs(probe.x - center.x - mapWidth/4f) > 2f) {
                                    snapshot.bitmap.recycle()
                                    continuation.resumeWithException(VectorMapException())
                                } else continuation.resume(snapshot.bitmap)
                            }
                        }, { _ -> if (continuation.isActive) continuation.resumeWithException(VectorMapException()) })
                    }
                }
            }
            } catch (_: TimeoutCancellationException) {
                throw VectorMapException()
            }
            background?.recycle()
            background = bitmap
            coverage = next
        }
        val cached = requireNotNull(coverage)
        val sx = width.toDouble()/(view.maxX-view.minX)*(cached.maxX-cached.minX)/mapWidth
        val sy = height.toDouble()/(view.maxY-view.minY)*(cached.maxY-cached.minY)/mapHeight
        val tx = (cached.minX-view.minX)/(view.maxX-view.minX)*width
        val ty = (cached.minY-view.minY)/(view.maxY-view.minY)*height
        val transform = Matrix().apply { setValues(floatArrayOf(sx.toFloat(),0f,tx.toFloat(),0f,sy.toFloat(),ty.toFloat(),0f,0f,1f)) }
        canvas.drawBitmap(requireNotNull(background),transform,paint)
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
    }

    private fun camera(view: Viewport): CameraPosition = CameraPosition.Builder()
        .target(LatLng(atan(sinh(Math.PI*(1-view.minY-view.maxY)))*180/Math.PI,(view.minX+view.maxX)/2*360-180))
        .zoom(log2(mapWidth/(512*(view.maxX-view.minX)))).build()

    companion object {
        private var initialized = false
        suspend fun create(context: Context, width: Int, height: Int): VectorBasemapRenderer = withContext(Dispatchers.Main) {
            if (BuildConfig.CARTO_BASEMAP_API_KEY.isBlank()) throw VectorMapException()
            require(width > 0 && height > 0)
            val app = context.applicationContext
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
            dev.mahlernim.timelinevisualizer.data.VectorMapCache.prepare(app)
            VectorBasemapRenderer(app,width,height)
        }
        // Includes overscan. Capping both dimensions bounds the CPU bitmap to 64 MiB.
        internal fun snapshotDimensions(width: Int, height: Int): Pair<Int, Int> {
            val scale = minOf(1.5, 4096.0 / maxOf(width, height))
            return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        }

        internal fun expanded(v: Viewport): Viewport {
            val dx=(v.maxX-v.minX)*.25; val dy=(v.maxY-v.minY)*.25
            return v.copy(minX=v.minX-dx,maxX=v.maxX+dx,minY=v.minY-dy,maxY=v.maxY+dy)
        }
        internal fun reusable(c: Viewport,v: Viewport): Boolean =
            abs((c.maxX-c.minX)/1.5/(v.maxX-v.minX)-1)<=.02 && v.minX>=c.minX && v.maxX<=c.maxX && v.minY>=c.minY && v.maxY<=c.maxY
    }
}

/** Deliberately excludes native messages, which can contain authenticated URLs. */
class VectorMapException : RuntimeException("Vector map unavailable")
