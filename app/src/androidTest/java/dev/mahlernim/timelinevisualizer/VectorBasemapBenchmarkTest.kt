package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.TimelineFrame
import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import dev.mahlernim.timelinevisualizer.render.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan
import kotlin.math.log2
import kotlin.math.sinh

/** Opt-in network benchmark, run only with a project key and synthetic routes. */
@RunWith(AndroidJUnit4::class)
class VectorBasemapBenchmarkTest {
    @Test fun compareSyntheticRoutes() = runBlocking {
        assumeTrue("Opt in to the network benchmark", InstrumentationRegistry.getArguments().getString("runVectorBenchmark") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(BuildConfig.CARTO_BASEMAP_API_KEY.isNotBlank()) { "Supply a CARTO benchmark key at build time" }
        val output = File(context.getExternalFilesDir(null), "vector-reuse-comparison").apply { mkdirs() }
        val vectorRequests = AtomicInteger()
        val rasterRequests = AtomicInteger()
        val certificate = signingSha1(context)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val carto = request.url.isHttps && (host == "basemaps.cartocdn.com" || host.endsWith(".basemaps.cartocdn.com"))
            val authenticated = if (carto) {
                vectorRequests.incrementAndGet()
                request.newBuilder().url(request.url.newBuilder().setQueryParameter("key", BuildConfig.CARTO_BASEMAP_API_KEY).build())
                    .header("X-Android-Package", context.packageName)
                    .header("X-Android-Cert", certificate).build()
            } else request
            chain.proceed(authenticated)
        }.build()
        withContext(Dispatchers.Main) {
            MapLibre.getInstance(context)
            HttpRequestUtil.setOkHttpClient(client)
        }
        val report = JSONArray()
        try {
            for ((name, coordinates) in listOf(
                "city" to listOf(37.55 to 126.92,37.57 to 126.98,37.58 to 127.02,37.54 to 127.06,37.50 to 127.04),
                "long" to listOf(37.56 to 126.98,36.35 to 127.38,35.18 to 129.08,34.69 to 135.50,35.68 to 139.69),
            )) {
                val journey = Journey.from(coordinates.mapIndexed { index, (lat, lon) ->
                    GeoPoint(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index * 86400L), lat, lon)
                }, 2026)
                val settings = CameraSettings.DEFAULT.copy(cameraMovement = CameraMovement.CLOSE_UP)
                val modes = listOf("raster", "vector", "reuse")
                val reverse = InstrumentationRegistry.getArguments().getString("benchmarkOrder") == "reverse"
                for (mode in if (reverse) modes.reversed() else modes) {
                    val painter = TimelinePainter()
                    val tiles = TileRepository(context, connectionFactory = { url ->
                        rasterRequests.incrementAndGet()
                        (url.openConnection() as java.net.HttpURLConnection).apply {
                            setRequestProperty("X-Android-Package", context.packageName)
                            setRequestProperty("X-Android-Cert", certificate)
                        }
                    })
                    val beforeRequests = if (mode != "raster") vectorRequests.get() else rasterRequests.get()
                    val begun = SystemClock.elapsedRealtimeNanos()
                    val mapSize = if (mode == "reuse") 720 else 480
                    var cachedView: Viewport? = null
                    var cachedBitmap: Bitmap? = null
                    var mapRenders = 0
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    val snapshotter = if (mode != "raster") withContext(Dispatchers.Main) {
                        MapSnapshotter(context, MapSnapshotter.Options(mapSize,mapSize)
                            .withPixelRatio(1f).withLogo(false).withAttribution(false)
                            .withStyleBuilder(org.maplibre.android.maps.Style.Builder().fromUri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json")))
                    } else null
                    val canvasBitmap = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
                    val times = mutableListOf<Double>()
                    var firstFrameMs = 0.0
                    try {
                        for (index in 0 until 240) {
                            val frame = if (index < 192) TimelineFrame(index / 191f,0f) else TimelineFrame(1f,(index-191)/48f)
                            val view = painter.viewport(journey,frame,480,480,settings)
                            val start = SystemClock.elapsedRealtimeNanos()
                            val canvas = Canvas(canvasBitmap)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            if (snapshotter != null) {
                                if (mode != "reuse" || cachedView == null || !reusable(cachedView!!,view)) {
                                    val backgroundView = if (mode == "reuse") expanded(view) else view
                                    val image = capture(snapshotter, backgroundView, mapSize)
                                    val center = image.pixelForLatLng(camera(backgroundView,mapSize).target!!)
                                    assertEquals(mapSize / 2f, center.x, 2f)
                                    assertEquals(mapSize / 2f, center.y, 2f)
                                    cachedBitmap?.recycle()
                                    cachedBitmap = image.bitmap
                                    cachedView = backgroundView
                                    mapRenders++
                                }
                                val background = cachedBitmap!!
                                val coverage = cachedView!!
                                val cropX = (view.minX-coverage.minX)/(coverage.maxX-coverage.minX)*mapSize
                                val cropY = (view.minY-coverage.minY)/(coverage.maxY-coverage.minY)*mapSize
                                val cropWidth = (view.maxX-view.minX)/(coverage.maxX-coverage.minX)*mapSize
                                val cropHeight = (view.maxY-view.minY)/(coverage.maxY-coverage.minY)*mapSize
                                assertTrue("Crop must stay inside cached image",cropX >= -0.001 && cropY >= -0.001 && cropX+cropWidth <= mapSize+0.001 && cropY+cropHeight <= mapSize+0.001)
                                val sx = 480 / cropWidth
                                val sy = 480 / cropHeight
                                val transform = Matrix().apply { setValues(floatArrayOf(sx.toFloat(),0f,(-cropX*sx).toFloat(),0f,sy.toFloat(),(-cropY*sy).toFloat(),0f,0f,1f)) }
                                canvas.drawBitmap(background,transform,paint)
                                if (index == 0) File(output,"$name-$mode-raw.png").outputStream().use {
                                    canvasBitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                                }
                                var opaque = 0
                                val colors = mutableSetOf<Int>()
                                for (y in 20 until 460 step 20) for (x in 20 until 460 step 20) {
                                    val pixel = background.getPixel(x*mapSize/480,y*mapSize/480)
                                    if (android.graphics.Color.alpha(pixel) >= 250) opaque++
                                    colors.add(pixel)
                                }
                                assertTrue("Vector background must be opaque",opaque >= 480)
                                assertTrue("Vector background must contain map detail", colors.size > 3)
                            } else {
                                for (tile in painter.requiredTiles(view)) requireNotNull(tiles.load(tile.id)) { "Raster tile failed" }
                            }
                            painter.draw(canvas,480,480,journey,frame,10,"Synthetic comparison",cameraSettings=settings,
                                tiles = { tiles.cached(it) }, drawMapBackground = mode == "raster")
                            times.add((SystemClock.elapsedRealtimeNanos()-start)/1e6)
                            if (index == 0) firstFrameMs = (SystemClock.elapsedRealtimeNanos()-begun)/1e6
                            if (index in listOf(0,120,239)) File(output,"$name-$mode-$index.png").outputStream().use {
                                canvasBitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                            }
                        }
                        val sorted = times.drop(1).sorted()
                        report.put(JSONObject().put("route",name).put("mode",mode).put("order",if (reverse) "reverse" else "forward").put("frames",240).put("mapRenders",mapRenders).put("reusedFrames",if (mode == "reuse") 240-mapRenders else 0)
                            .put("backgroundCacheBytes",if (mode == "reuse") mapSize*mapSize*4 else 0)
                            .put("firstFrameMs",firstFrameMs).put("medianFrameMs",sorted[sorted.size/2])
                            .put("p95FrameMs",sorted[(sorted.size*.95).toInt()])
                            .put("totalMs",(SystemClock.elapsedRealtimeNanos()-begun)/1e6)
                            .put("httpRequests",(if (mode != "raster") vectorRequests.get() else rasterRequests.get())-beforeRequests)
                            .put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize())
                            .put("sdk",Build.VERSION.SDK_INT).put("device",Build.MODEL)
                            .put("notes","Single host-GPU emulator run, 240 frames, rendering only, no MP4 encoding. Disk/network caches may be warm. Memory is a process snapshot, not peak."))
                        File(output,"metrics.json").writeText(report.toString(2))
                        println("VECTOR_BENCHMARK $name $mode renders=$mapRenders complete")
                    } finally {
                        cachedBitmap?.recycle()
                        canvasBitmap.recycle()
                        withContext(Dispatchers.Main) { snapshotter?.cancel() }
                    }
                }
            }
            assertTrue(report.length() == 6)
        } finally {
            withContext(Dispatchers.Main) { HttpRequestUtil.setOkHttpClient(null) }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun camera(view: Viewport, size: Int): CameraPosition {
        val latitude = atan(sinh(Math.PI*(1-2*(view.minY+view.maxY)/2))) * 180 / Math.PI
        val longitude = (view.minX+view.maxX)/2 * 360-180
        return CameraPosition.Builder().target(LatLng(latitude,longitude))
            .zoom(log2(size/(512*(view.maxX-view.minX)))).build()
    }

    private suspend fun capture(snapshotter: MapSnapshotter, view: Viewport, size: Int): MapSnapshot = withContext(Dispatchers.Main) {
        withTimeout(30_000) {
            suspendCancellableCoroutine { continuation ->
                snapshotter.setCameraPosition(camera(view,size))
                continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { snapshotter.cancel() } }
                snapshotter.start({ image ->
                    if (continuation.isActive) continuation.resume(image) else image.bitmap.recycle()
                }, { _ -> if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Vector snapshot failed")) })
            }
        }
    }

    private fun expanded(view: Viewport): Viewport {
        val dx = (view.maxX-view.minX)*0.25
        val dy = (view.maxY-view.minY)*0.25
        return view.copy(minX=view.minX-dx,maxX=view.maxX+dx,minY=view.minY-dy,maxY=view.maxY+dy)
    }

    private fun reusable(cached: Viewport, view: Viewport): Boolean {
        val scale = (cached.maxX-cached.minX)/1.5/(view.maxX-view.minX)
        return kotlin.math.abs(scale-1) <= 0.02 && view.minX >= cached.minX && view.maxX <= cached.maxX && view.minY >= cached.minY && view.maxY <= cached.maxY
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(context: Context): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) context.packageManager
            .getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
        else context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNATURES).signatures!!
        return MessageDigest.getInstance("SHA-1").digest(signatures.first().toByteArray()).joinToString(":") { "%02X".format(it) }
    }
}
