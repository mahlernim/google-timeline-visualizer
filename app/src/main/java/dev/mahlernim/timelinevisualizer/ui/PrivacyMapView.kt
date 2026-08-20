package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.WebMercator
import dev.mahlernim.timelinevisualizer.privacy.PrivacyArea
import dev.mahlernim.timelinevisualizer.render.TileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh

class PrivacyMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val tiles = TileRepository(context.applicationContext)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loading = ConcurrentHashMap.newKeySet<TileId>()
    private var points: List<GeoPoint> = emptyList()
    private var areas: List<PrivacyArea> = emptyList()
    private var centerX = 0.5
    private var centerY = 0.5
    private var zoomLevel = 2.0
    private var shouldFitTimeline = true

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 34, 93)
        alpha = 190
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 103, 80, 164)
        style = Paint.Style.FILL
    }
    private val areaStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(103, 80, 164)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val candidateFillPaint = Paint(areaFillPaint).apply { color = Color.argb(90, 198, 40, 102) }
    private val candidateStrokePaint = Paint(areaStrokePaint).apply { color = Color.rgb(198, 40, 102) }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(33, 33, 33)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 35, 35, 35)
        textAlign = Paint.Align.RIGHT
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }

    var candidateRadiusKm: Double = PrivacyArea.DEFAULT_RADIUS_KM
        set(value) {
            field = value.coerceIn(PrivacyArea.MIN_RADIUS_KM, PrivacyArea.MAX_RADIUS_KM)
            invalidate()
        }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldWorldPerPixel = worldPerPixel()
            val focusWorldX = centerX + (detector.focusX - width / 2f) * oldWorldPerPixel
            val focusWorldY = centerY + (detector.focusY - height / 2f) * oldWorldPerPixel
            zoomLevel = (zoomLevel + log2(detector.scaleFactor.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val newWorldPerPixel = worldPerPixel()
            centerX = focusWorldX - (detector.focusX - width / 2f) * newWorldPerPixel
            centerY = focusWorldY - (detector.focusY - height / 2f) * newWorldPerPixel
            clampCenter()
            shouldFitTimeline = false
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onScroll(
            first: MotionEvent?,
            current: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (scaleDetector.isInProgress) return false
            val worldPerPixel = worldPerPixel()
            centerX += distanceX * worldPerPixel
            centerY += distanceY * worldPerPixel
            clampCenter()
            shouldFitTimeline = false
            invalidate()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            zoomBy(1.0, event.x, event.y)
            return true
        }
    })

    init {
        isFocusable = true
        isClickable = true
        contentDescription = context.getString(dev.mahlernim.timelinevisualizer.R.string.privacy_map_description)
    }

    fun setTimeline(points: List<GeoPoint>) {
        this.points = points
        shouldFitTimeline = true
        if (width > 0 && height > 0) fitTimeline()
        invalidate()
    }

    fun setPrivacyAreas(areas: List<PrivacyArea>) {
        this.areas = areas
        invalidate()
    }

    fun focusOn(area: PrivacyArea) {
        val projected = WebMercator.project(area.latitude, area.longitude)
        centerX = projected.x
        centerY = projected.y
        zoomLevel = radiusZoom(area.radiusKm)
        candidateRadiusKm = area.radiusKm
        shouldFitTimeline = false
        clampCenter()
        invalidate()
    }

    fun fitTimeline() {
        if (points.isEmpty() || width <= 0 || height <= 0) return
        val projected = points.map(WebMercator::project)
        val xs = WebMercator.shortestWrappedX(projected.map { it.x })
        val minX = xs.minOrNull() ?: 0.5
        val maxX = xs.maxOrNull() ?: 0.5
        val minY = projected.minOf { it.y }
        val maxY = projected.maxOf { it.y }
        val spanX = max(maxX - minX, MIN_FIT_SPAN) * FIT_PADDING
        val spanY = max(maxY - minY, MIN_FIT_SPAN) * FIT_PADDING
        centerX = (minX + maxX) / 2.0
        centerY = (minY + maxY) / 2.0
        zoomLevel = min(
            log2(width.coerceAtLeast(1) / (TILE_SIZE * spanX)),
            log2(height.coerceAtLeast(1) / (TILE_SIZE * spanY)),
        ).coerceIn(MIN_ZOOM, MAX_ZOOM)
        shouldFitTimeline = false
        clampCenter()
        invalidate()
    }

    fun zoomIn() = zoomBy(1.0, width / 2f, height / 2f)

    fun zoomOut() = zoomBy(-1.0, width / 2f, height / 2f)

    fun selectedPoint(): GeoPoint {
        val normalizedX = ((centerX % 1.0) + 1.0) % 1.0
        val longitude = normalizedX * 360.0 - 180.0
        val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * centerY))))
        return GeoPoint(Instant.EPOCH, latitude, longitude)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.coroutineContext[Job]!!.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (shouldFitTimeline && points.isNotEmpty()) fitTimeline()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(235, 239, 242))
        drawTiles(canvas)
        drawRoute(canvas)
        areas.forEach { drawArea(canvas, it.latitude, it.longitude, it.radiusKm, areaFillPaint, areaStrokePaint) }
        val candidate = selectedPoint()
        drawArea(
            canvas,
            candidate.latitude,
            candidate.longitude,
            candidateRadiusKm,
            candidateFillPaint,
            candidateStrokePaint,
        )
        drawCrosshair(canvas)
        canvas.drawText(context.getString(dev.mahlernim.timelinevisualizer.R.string.map_attribution), width - 8f, height - 8f, attributionPaint)
    }

    private fun drawTiles(canvas: Canvas) {
        val tileZoom = floor(zoomLevel).toInt().coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        val count = 1 shl tileZoom
        val worldPerPixel = worldPerPixel()
        val minX = centerX - width * worldPerPixel / 2.0
        val maxX = centerX + width * worldPerPixel / 2.0
        val minY = centerY - height * worldPerPixel / 2.0
        val maxY = centerY + height * worldPerPixel / 2.0
        val xMin = floor(minX * count).toInt()
        val xMax = floor(maxX * count).toInt()
        val yMin = floor(minY * count).toInt().coerceIn(0, count - 1)
        val yMax = floor(maxY * count).toInt().coerceIn(0, count - 1)
        for (worldX in xMin..xMax) {
            val tileX = Math.floorMod(worldX, count)
            for (tileY in yMin..yMax) {
                val id = TileId(tileZoom, tileX, tileY)
                val bitmap = tiles.cached(id)
                if (bitmap == null) {
                    if (loading.add(id)) {
                        scope.launch {
                            try {
                                tiles.load(id)
                            } finally {
                                loading.remove(id)
                                invalidate()
                            }
                        }
                    }
                    continue
                }
                val leftWorld = worldX.toDouble() / count
                val topWorld = tileY.toDouble() / count
                val left = ((leftWorld - minX) / worldPerPixel).toFloat()
                val top = ((topWorld - minY) / worldPerPixel).toFloat()
                val size = (1.0 / count / worldPerPixel).toFloat()
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + size + 1f, top + size + 1f), null)
            }
        }
    }

    private fun drawRoute(canvas: Canvas) {
        if (points.size < 2) return
        val stride = ceil(points.size / MAX_ROUTE_POINTS.toDouble()).toInt().coerceAtLeast(1)
        val path = Path()
        var drew = false
        var index = 0
        while (index < points.size) {
            val screen = toScreen(points[index].latitude, points[index].longitude)
            if (!drew) {
                path.moveTo(screen.first, screen.second)
                drew = true
            } else {
                path.lineTo(screen.first, screen.second)
            }
            index += stride
        }
        if ((points.lastIndex % stride) != 0) {
            val end = toScreen(points.last().latitude, points.last().longitude)
            path.lineTo(end.first, end.second)
        }
        canvas.drawPath(path, routePaint)
    }

    private fun drawArea(
        canvas: Canvas,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        fill: Paint,
        stroke: Paint,
    ) {
        val center = toScreen(latitude, longitude)
        val northLatitude = (latitude + radiusKm / KM_PER_LATITUDE_DEGREE).coerceAtMost(85.0)
        val north = toScreen(northLatitude, longitude)
        val radiusPixels = max(resources.displayMetrics.density * 4f, kotlin.math.abs(north.second - center.second))
        canvas.drawCircle(center.first, center.second, radiusPixels, fill)
        canvas.drawCircle(center.first, center.second, radiusPixels, stroke)
    }

    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val length = resources.displayMetrics.density * 18f
        val gap = resources.displayMetrics.density * 5f
        canvas.drawLine(cx - length, cy, cx - gap, cy, crosshairPaint)
        canvas.drawLine(cx + gap, cy, cx + length, cy, crosshairPaint)
        canvas.drawLine(cx, cy - length, cx, cy - gap, crosshairPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + length, crosshairPaint)
        canvas.drawCircle(cx, cy, resources.displayMetrics.density * 3f, crosshairPaint)
    }

    private fun toScreen(latitude: Double, longitude: Double): Pair<Float, Float> {
        val projected = WebMercator.project(latitude, longitude)
        var x = projected.x
        while (x - centerX > 0.5) x -= 1.0
        while (x - centerX < -0.5) x += 1.0
        val worldPerPixel = worldPerPixel()
        return (
            width / 2f + ((x - centerX) / worldPerPixel).toFloat()
        ) to (
            height / 2f + ((projected.y - centerY) / worldPerPixel).toFloat()
        )
    }

    private fun zoomBy(delta: Double, focusX: Float, focusY: Float) {
        val oldWorldPerPixel = worldPerPixel()
        val focusWorldX = centerX + (focusX - width / 2f) * oldWorldPerPixel
        val focusWorldY = centerY + (focusY - height / 2f) * oldWorldPerPixel
        zoomLevel = (zoomLevel + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newWorldPerPixel = worldPerPixel()
        centerX = focusWorldX - (focusX - width / 2f) * newWorldPerPixel
        centerY = focusWorldY - (focusY - height / 2f) * newWorldPerPixel
        clampCenter()
        shouldFitTimeline = false
        invalidate()
    }

    private fun worldPerPixel(): Double = 1.0 / (TILE_SIZE * 2.0.pow(zoomLevel))

    private fun clampCenter() {
        val halfHeight = height.coerceAtLeast(1) * worldPerPixel() / 2.0
        centerY = centerY.coerceIn(halfHeight.coerceAtMost(0.5), (1.0 - halfHeight).coerceAtLeast(0.5))
    }

    private fun radiusZoom(radiusKm: Double): Double {
        val diameterWorld = max(radiusKm * 2.0 / 40_075.0, MIN_FIT_SPAN)
        return log2(width.coerceAtLeast(320) / (TILE_SIZE * diameterWorld * 2.4)).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    companion object {
        private const val TILE_SIZE = 256.0
        private const val MIN_ZOOM = 1.0
        private const val MAX_ZOOM = 18.0
        private const val MIN_TILE_ZOOM = 1
        private const val MAX_TILE_ZOOM = 18
        private const val MIN_FIT_SPAN = 0.001
        private const val FIT_PADDING = 1.35
        private const val MAX_ROUTE_POINTS = 5_000
        private const val KM_PER_LATITUDE_DEGREE = 110.574
    }
}
