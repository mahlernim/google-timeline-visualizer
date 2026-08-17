package dev.mahlernim.timelinevisualizer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.JourneyPosition
import dev.mahlernim.timelinevisualizer.model.WebMercator
import dev.mahlernim.timelinevisualizer.model.WorldPoint
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

data class TileId(val zoom: Int, val x: Int, val y: Int)
data class VisibleTile(val id: TileId, val worldX: Int)

data class Viewport(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val zoom: Int,
)

class TimelinePainter {
    private var cachedJourney: Journey? = null
    private var cachedPrepared: PreparedJourney? = null
    private var cachedCameraJourney: Journey? = null
    private var cachedCameraWidth = 0
    private var cachedCameraHeight = 0
    private var cachedCameraTrack: CameraTrack? = null
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 0, 100)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 125
    }
    private val tailPaint = Paint(routePaint).apply {
        strokeWidth = 8f
        alpha = 255
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 25, 29)
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 2f, Color.argb(90, 0, 0, 0))
    }
    private val headRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 0, 100)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 25, 29)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 75, 82)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 36, 25, 29)
        textAlign = Paint.Align.RIGHT
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 248, 250)
    }

    fun viewport(journey: Journey, progress: Float, width: Int, height: Int): Viewport {
        if (width <= 0 || height <= 0) return rawViewport(journey, progress, width, height)
        return cameraTrack(journey, width, height).viewportAt(progress)
    }

    private fun rawViewport(journey: Journey, progress: Float, width: Int, height: Int): Viewport {
        val prepared = prepare(journey)
        val current = journey.positionAt(progress)
        val tailDistance = max(0.0, current.distanceKm - CAMERA_CONTEXT_KM)
        val lookaheadDistance = min(journey.totalDistanceKm, current.distanceKm + CAMERA_CONTEXT_KM)
        val focus = buildList {
            add(journey.positionAtDistance(tailDistance).point)
            val start = prepared.lowerBound(tailDistance)
            val end = prepared.upperBound(lookaheadDistance)
            for (index in start..end) {
                if (index in prepared.projected.indices) add(journey.renderPath[index].point)
            }
            add(current.point)
            add(journey.positionAtDistance(lookaheadDistance).point)
        }.map(WebMercator::project)

        val routeReferenceX = prepared.referenceX(current.distanceKm)
        val centerPoint = WebMercator.project(current.point)
        val centerX = unwrapNear(centerPoint.x, routeReferenceX)
        val wrappedX = focus.map { unwrapNear(it.x, centerX) }
        val ys = focus.map { it.y }
        val centerY = centerPoint.y
        val contentSpanX = max(0.00015, (wrappedX.maxOrNull() ?: centerX) - (wrappedX.minOrNull() ?: centerX))
        val contentSpanY = max(0.00015, (ys.maxOrNull() ?: centerY) - (ys.minOrNull() ?: centerY))
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        var spanY = max(contentSpanY * 2.8, contentSpanX * 2.8 / aspect)
        spanY = spanY.coerceIn(0.0003, 0.72)
        val spanX = spanY * aspect
        val minY = (centerY - spanY / 2).coerceAtLeast(0.0)
        val maxY = (centerY + spanY / 2).coerceAtMost(1.0)
        val adjustedSpanY = maxY - minY
        val minX = centerX - spanX / 2
        val maxX = centerX + spanX / 2
        val zoom = floor(log2(width.coerceAtLeast(1) / (256.0 * max(maxX - minX, adjustedSpanY * aspect)))).toInt()
            .coerceIn(2, 15)
        return Viewport(minX, maxX, minY, maxY, zoom)
    }

    private fun cameraTrack(journey: Journey, width: Int, height: Int): CameraTrack {
        if (
            cachedCameraJourney === journey &&
            cachedCameraWidth == width &&
            cachedCameraHeight == height
        ) {
            return cachedCameraTrack!!
        }
        val track = buildCameraTrack(journey, width, height)
        cachedCameraJourney = journey
        cachedCameraWidth = width
        cachedCameraHeight = height
        cachedCameraTrack = track
        return track
    }

    private fun buildCameraTrack(journey: Journey, width: Int, height: Int): CameraTrack {
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        val frames = ArrayList<CameraFrame>(CAMERA_TRACK_SAMPLES + 1)
        var previous: CameraFrame? = null
        for (sample in 0..CAMERA_TRACK_SAMPLES) {
            val progress = sample.toFloat() / CAMERA_TRACK_SAMPLES
            val raw = rawViewport(journey, progress, width, height)
            val rawCenterX = (raw.minX + raw.maxX) / 2.0
            val rawCenterY = (raw.minY + raw.maxY) / 2.0
            val rawSpanY = (raw.maxY - raw.minY).coerceAtLeast(MIN_VIEWPORT_SPAN)
            val marker = WebMercator.project(journey.positionAt(progress).point)
            val frame = if (previous == null) {
                CameraFrame(rawCenterX, rawCenterY, rawSpanY, raw.zoom)
            } else {
                val zoomAlpha = if (rawSpanY > previous.spanY) ZOOM_OUT_ALPHA else ZOOM_IN_ALPHA
                val spanY = kotlin.math.exp(
                    lerp(ln(previous.spanY), ln(rawSpanY), zoomAlpha),
                ).coerceIn(MIN_VIEWPORT_SPAN, MAX_VIEWPORT_SPAN)
                val spanX = spanY * aspect
                val markerX = unwrapNear(marker.x, previous.centerX)
                var centerX = previous.centerX
                var centerY = previous.centerY
                val deadHalfX = spanX * CAMERA_DEAD_ZONE_HALF
                val deadHalfY = spanY * CAMERA_DEAD_ZONE_HALF
                centerX = when {
                    markerX < centerX - deadHalfX -> markerX + deadHalfX
                    markerX > centerX + deadHalfX -> markerX - deadHalfX
                    else -> centerX
                }
                centerY = when {
                    marker.y < centerY - deadHalfY -> marker.y + deadHalfY
                    marker.y > centerY + deadHalfY -> marker.y - deadHalfY
                    else -> centerY
                }
                centerY = clampCenterY(centerY, spanY)
                val continuousZoom = log2(width.coerceAtLeast(1) / (256.0 * spanX))
                val tileZoom = stabilizedTileZoom(previous.zoom, continuousZoom)
                CameraFrame(centerX, centerY, spanY, tileZoom)
            }
            frames.add(frame)
            previous = frame
        }
        return CameraTrack(frames, aspect)
    }

    private fun stabilizedTileZoom(previous: Int, continuous: Double): Int {
        var zoom = previous
        while (zoom < MAX_TILE_ZOOM && continuous >= zoom + 1.0 + TILE_ZOOM_HYSTERESIS) zoom++
        while (zoom > MIN_TILE_ZOOM && continuous < zoom - TILE_ZOOM_HYSTERESIS) zoom--
        return zoom.coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
    }

    private fun clampCenterY(centerY: Double, spanY: Double): Double {
        val half = spanY / 2.0
        return if (half >= 0.5) 0.5 else centerY.coerceIn(half, 1.0 - half)
    }

    private fun lerp(start: Double, end: Double, fraction: Double): Double =
        start + (end - start) * fraction

    fun requiredTiles(viewport: Viewport): List<VisibleTile> {
        val count = 1 shl viewport.zoom
        val xMin = floor(viewport.minX * count).toInt()
        val xMax = floor(viewport.maxX * count).toInt()
        val yMin = floor(viewport.minY * count).toInt().coerceIn(0, count - 1)
        val yMax = floor(viewport.maxY * count).toInt().coerceIn(0, count - 1)
        return buildList {
            for (worldX in xMin..xMax) {
                val normalizedX = ((worldX % count) + count) % count
                for (y in yMin..yMax) add(VisibleTile(TileId(viewport.zoom, normalizedX, y), worldX))
            }
        }.take(36)
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        journey: Journey,
        progress: Float,
        title: String,
        tiles: (TileId) -> Bitmap?,
    ) {
        if (journey.points.isEmpty() || width <= 0 || height <= 0) return
        val viewport = viewport(journey, progress, width, height)
        val prepared = prepare(journey)
        drawBackground(canvas, width, height)
        drawTiles(canvas, width, height, viewport, tiles)

        val screen = prepared.projected.mapIndexed { index, point ->
            worldToScreen(WorldPoint(prepared.unwrappedX[index], point.y), viewport, width, height)
        }
        drawSamplePath(canvas, screen.indices, screen, routePaint)

        val current = journey.positionAt(progress)
        val traveledEnd = prepared.upperBound(current.distanceKm)
        val traveledIndices = if (traveledEnd >= 0) 0..traveledEnd else IntRange.EMPTY
        val currentScreen = screenPoint(current, prepared, viewport, width, height)
        drawSamplePath(canvas, traveledIndices, screen, routePaint, currentScreen)

        val tailDistance = max(0.0, current.distanceKm - TRAIL_KM)
        val tailStartScreen = screenPoint(journey.positionAtDistance(tailDistance), prepared, viewport, width, height)
        val tailStart = prepared.lowerBound(tailDistance)
        val tailEnd = prepared.upperBound(current.distanceKm)
        val tailIndices = if (tailStart <= tailEnd) tailStart..tailEnd else IntRange.EMPTY
        drawSamplePath(canvas, tailIndices, screen, tailPaint, currentScreen, tailStartScreen)

        val head = currentScreen
        canvas.drawCircle(head.first, head.second, width * 0.013f, headPaint)
        canvas.drawCircle(head.first, head.second, width * 0.017f, headRingPaint)
        drawOverlay(canvas, width, height, current, title)
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int) {
        val gradient = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(250, 246, 247), Color.rgb(224, 232, 239)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = gradient })
    }

    private fun drawTiles(
        canvas: Canvas,
        width: Int,
        height: Int,
        viewport: Viewport,
        tiles: (TileId) -> Bitmap?,
    ) {
        val count = 1 shl viewport.zoom
        for (tile in requiredTiles(viewport)) {
            val bitmap = tiles(tile.id) ?: continue
            val leftWorld = tile.worldX.toDouble() / count
            val rightWorld = (tile.worldX + 1).toDouble() / count
            val topWorld = tile.id.y.toDouble() / count
            val bottomWorld = (tile.id.y + 1).toDouble() / count
            val left = ((leftWorld - viewport.minX) / (viewport.maxX - viewport.minX) * width).toFloat()
            val right = ((rightWorld - viewport.minX) / (viewport.maxX - viewport.minX) * width).toFloat()
            val top = ((topWorld - viewport.minY) / (viewport.maxY - viewport.minY) * height).toFloat()
            val bottom = ((bottomWorld - viewport.minY) / (viewport.maxY - viewport.minY) * height).toFloat()
            canvas.drawBitmap(bitmap, null, RectF(left, top, right + 1, bottom + 1), null)
        }
    }

    private fun drawOverlay(canvas: Canvas, width: Int, height: Int, position: JourneyPosition, title: String) {
        val scale = width / 720f
        val card = RectF(34f * scale, 28f * scale, width - 34f * scale, 132f * scale)
        canvas.drawRoundRect(card, 24f * scale, 24f * scale, cardPaint)
        titlePaint.textSize = 34f * scale
        bodyPaint.textSize = 20f * scale
        attributionPaint.textSize = 13f * scale
        val displayTitle = title.ifBlank { "My Trips" }
        val availableWidth = card.width() - 36f * scale
        while (titlePaint.textSize > 20f * scale && titlePaint.measureText(displayTitle) > availableWidth) {
            titlePaint.textSize -= 1f * scale
        }
        val fittedTitle = if (titlePaint.measureText(displayTitle) <= availableWidth) displayTitle else {
            val count = titlePaint.breakText(displayTitle, true, availableWidth - titlePaint.measureText("…"), null)
            displayTitle.take(count.coerceAtLeast(1)).trimEnd() + "…"
        }
        canvas.drawText(fittedTitle, width / 2f, 72f * scale, titlePaint)
        val date = DATE_FORMAT.format(position.point.instant.atZone(ZoneId.systemDefault()))
        val distance = position.distanceKm
        canvas.drawText("$date  ·  ${String.format(Locale.US, "%,.0f", distance)} km", width / 2f, 108f * scale, bodyPaint)
        canvas.drawText("© OpenStreetMap  © CARTO", width - 12f * scale, height - 12f * scale, attributionPaint)
    }

    private fun worldToScreen(point: WorldPoint, viewport: Viewport, width: Int, height: Int): Pair<Float, Float> {
        val x = unwrapNear(point.x, (viewport.minX + viewport.maxX) / 2.0)
        val sx = ((x - viewport.minX) / (viewport.maxX - viewport.minX) * width).toFloat()
        val sy = ((point.y - viewport.minY) / (viewport.maxY - viewport.minY) * height).toFloat()
        return sx to sy
    }

    private fun drawSamplePath(
        canvas: Canvas,
        indices: Iterable<Int>,
        screen: List<Pair<Float, Float>>,
        paint: Paint,
        end: Pair<Float, Float>? = null,
        start: Pair<Float, Float>? = null,
    ) {
        val path = Path()
        var moved = false
        if (start != null) {
            path.moveTo(start.first, start.second)
            moved = true
        }
        for (index in indices) {
            val point = screen[index]
            if (!moved) path.moveTo(point.first, point.second) else path.lineTo(point.first, point.second)
            moved = true
        }
        if (end != null) {
            if (!moved) path.moveTo(end.first, end.second) else path.lineTo(end.first, end.second)
            moved = true
        }
        if (moved) canvas.drawPath(path, paint)
    }

    private fun screenPoint(
        position: JourneyPosition,
        prepared: PreparedJourney,
        viewport: Viewport,
        width: Int,
        height: Int,
    ): Pair<Float, Float> {
        val projected = WebMercator.project(position.point)
        val reference = prepared.referenceX(position.distanceKm)
        return worldToScreen(WorldPoint(unwrapNear(projected.x, reference), projected.y), viewport, width, height)
    }

    private fun prepare(journey: Journey): PreparedJourney {
        if (cachedJourney === journey) return cachedPrepared!!
        val projected = journey.renderPath.map { WebMercator.project(it.point) }
        val prepared = PreparedJourney(
            projected = projected,
            unwrappedX = unwrapRoute(projected.map { it.x }),
            distances = DoubleArray(journey.renderPath.size) { journey.renderPath[it].distanceKm },
        )
        cachedJourney = journey
        cachedPrepared = prepared
        return prepared
    }

    private fun unwrapRoute(values: List<Double>): List<Double> {
        if (values.isEmpty()) return emptyList()
        return buildList {
            add(values.first())
            for (index in 1..values.lastIndex) add(unwrapNear(values[index], last()))
        }
    }

    private fun unwrapNear(value: Double, reference: Double): Double {
        var result = value
        while (result - reference > 0.5) result -= 1.0
        while (result - reference < -0.5) result += 1.0
        return result
    }

    private data class PreparedJourney(
        val projected: List<WorldPoint>,
        val unwrappedX: List<Double>,
        val distances: DoubleArray,
    ) {
        fun lowerBound(value: Double): Int {
            var low = 0
            var high = distances.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (distances[middle] < value) low = middle + 1 else high = middle
            }
            return low.coerceAtMost(distances.lastIndex.coerceAtLeast(0))
        }

        fun upperBound(value: Double): Int {
            var low = 0
            var high = distances.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (distances[middle] <= value) low = middle + 1 else high = middle
            }
            return low - 1
        }

        fun referenceX(distanceKm: Double): Double {
            if (distances.isEmpty()) return 0.5
            val after = lowerBound(distanceKm)
            val before = (after - 1).coerceAtLeast(0)
            val nearest = if (
                after in distances.indices &&
                kotlin.math.abs(distances[after] - distanceKm) < kotlin.math.abs(distances[before] - distanceKm)
            ) after else before
            return unwrappedX[nearest]
        }
    }

    private data class CameraFrame(
        val centerX: Double,
        val centerY: Double,
        val spanY: Double,
        val zoom: Int,
    )

    private data class CameraTrack(
        val frames: List<CameraFrame>,
        val aspect: Double,
    ) {
        fun viewportAt(progress: Float): Viewport {
            if (frames.size == 1) return frames.first().toViewport(aspect)
            val position = progress.coerceIn(0f, 1f) * frames.lastIndex
            val fromIndex = floor(position).toInt().coerceIn(0, frames.lastIndex)
            val toIndex = (fromIndex + 1).coerceAtMost(frames.lastIndex)
            val fraction = position - fromIndex
            val from = frames[fromIndex]
            val to = frames[toIndex]
            val centerX = from.centerX + (to.centerX - from.centerX) * fraction
            val centerY = from.centerY + (to.centerY - from.centerY) * fraction
            val spanY = kotlin.math.exp(
                ln(from.spanY) + (ln(to.spanY) - ln(from.spanY)) * fraction,
            )
            val zoom = if (fraction < 0.5) from.zoom else to.zoom
            return CameraFrame(centerX, centerY, spanY, zoom).toViewport(aspect)
        }

        private fun CameraFrame.toViewport(aspect: Double): Viewport {
            val halfY = spanY / 2.0
            val halfX = spanY * aspect / 2.0
            return Viewport(centerX - halfX, centerX + halfX, centerY - halfY, centerY + halfY, zoom)
        }
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")
        private const val CAMERA_CONTEXT_KM = 650.0
        private const val TRAIL_KM = 650.0
        private const val CAMERA_TRACK_SAMPLES = 480
        private const val CAMERA_DEAD_ZONE_HALF = 0.20
        private const val ZOOM_OUT_ALPHA = 0.32
        private const val ZOOM_IN_ALPHA = 0.065
        private const val TILE_ZOOM_HYSTERESIS = 0.15
        private const val MIN_VIEWPORT_SPAN = 0.0003
        private const val MAX_VIEWPORT_SPAN = 0.72
        private const val MIN_TILE_ZOOM = 2
        private const val MAX_TILE_ZOOM = 15
    }
}
