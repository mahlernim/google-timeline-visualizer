package dev.mahlernim.timelinevisualizer.render

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import dev.mahlernim.timelinevisualizer.BuildConfig
import dev.mahlernim.timelinevisualizer.R
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
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.log2
import kotlin.math.sinh

/** Export-only preview. Holds one temporary oversized background at a time. */
class VectorBasemapRenderer private constructor(context: Context, private val width: Int, private val height: Int) {
    private val mapWidth = (width * 1.5).toInt()
    private val mapHeight = (height * 1.5).toInt()
    private val snapshotter = MapSnapshotter(context, MapSnapshotter.Options(mapWidth,mapHeight)
        .withPixelRatio(1f).withLogo(false).withAttribution(false)
        .withStyleBuilder(Style.Builder().fromUri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json")))
    private var coverage: Viewport? = null
    private var background: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val logo = requireNotNull(context.getDrawable(R.drawable.carto_logo))

    suspend fun draw(canvas: Canvas, view: Viewport) {
        val previous = coverage
        if (previous == null || !reusable(previous,view)) {
            val next = expanded(view)
            val bitmap = withContext(Dispatchers.Main) {
                withTimeout(30_000) {
                    suspendCancellableCoroutine<Bitmap> { continuation ->
                        val position = camera(next)
                        snapshotter.setCameraPosition(position)
                        continuation.invokeOnCancellation {
                            android.os.Handler(android.os.Looper.getMainLooper()).post { snapshotter.cancel() }
                        }
                        snapshotter.start({ snapshot ->
                            if (!continuation.isActive) snapshot.bitmap.recycle()
                            else {
                                val center = snapshot.pixelForLatLng(position.target!!)
                                if (abs(center.x-mapWidth/2f)>2f || abs(center.y-mapHeight/2f)>2f) {
                                    snapshot.bitmap.recycle()
                                    continuation.resumeWithException(IllegalStateException("Vector map camera is unsupported. Switch to raster and retry."))
                                } else continuation.resume(snapshot.bitmap)
                            }
                        }, { _ -> if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Vector map could not load. Switch to raster and retry.")) })
                    }
                }
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
            require(BuildConfig.CARTO_BASEMAP_API_KEY.isNotBlank()) { "Vector map key is unavailable in this build." }
            require(maxOf(width,height)<=1920) { "Vector preview supports dimensions up to 1920 pixels. Choose a smaller format or raster." }
            val app = context.applicationContext
            if (!initialized) {
                MapLibre.getInstance(app)
                @Suppress("DEPRECATION")
                val signatures = if (Build.VERSION.SDK_INT >= 28) app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
                    else app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_SIGNATURES).signatures!!
                val certificate = MessageDigest.getInstance("SHA-1").digest(signatures.first().toByteArray()).joinToString(":") { "%02X".format(it) }
                HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val carto = request.url.isHttps && (host == "basemaps.cartocdn.com" || host.endsWith(".basemaps.cartocdn.com"))
                    chain.proceed(if (carto) request.newBuilder()
                        .url(request.url.newBuilder().setQueryParameter("key",BuildConfig.CARTO_BASEMAP_API_KEY).build())
                        .header("X-Android-Package",app.packageName).header("X-Android-Cert",certificate).build() else request)
                }.build())
                initialized = true
            }
            VectorBasemapRenderer(app,width,height)
        }
        internal fun expanded(v: Viewport): Viewport {
            val dx=(v.maxX-v.minX)*.25; val dy=(v.maxY-v.minY)*.25
            return v.copy(minX=v.minX-dx,maxX=v.maxX+dx,minY=v.minY-dy,maxY=v.maxY+dy)
        }
        internal fun reusable(c: Viewport,v: Viewport): Boolean =
            abs((c.maxX-c.minX)/1.5/(v.maxX-v.minX)-1)<=.02 && v.minX>=c.minX && v.maxX<=c.maxX && v.minY>=c.minY && v.maxY<=c.maxY
    }
}
