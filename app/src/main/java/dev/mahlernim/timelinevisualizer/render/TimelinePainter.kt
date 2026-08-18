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
import java.text.NumberFormat
import java.util.Locale
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
    private val oldTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 0, 100)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 55
    }
    private val middleTrailPaint = Paint(oldTrailPaint).apply {
        strokeWidth = 6f
        alpha = 135
    }
    private val recentTrailPaint = Paint(oldTrailPaint).apply {
        strokeWidth = 8f
        alpha = 255
    }
    private val overviewRoutePaint = Paint(oldTrailPaint).apply {
        strokeWidth = 3.5f
        alpha = 255
    }
    private val overviewCompositePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        return viewport(journey, TimelineFrame(progress, 0f), width, height)
    }

    fun viewport(journey: Journey, frame: TimelineFrame, width: Int, height: Int): Viewport {
        if (width <= 0 || height <= 0) return rawViewport(journey, frame.journeyProgress, width, height)
        val journeyViewport = cameraTrack(journey, width, height).viewportAt(frame.journeyProgress)
        if (frame.outroProgress <= 0f) return journeyViewport
        return blendViewport(
            journeyViewport,
            overviewViewport(journey, width, height),
            easeOutCubic(frame.outroProgress),
            width,
            height,
        )
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

    private fun overviewViewport(journey: Journey, width: Int, height: Int): Viewport {
        val prepared = prepare(journey)
        val minX = prepared.unwrappedX.minOrNull() ?: 0.5
        val maxX = prepared.unwrappedX.maxOrNull() ?: minX
        val minY = prepared.projected.minOfOrNull { it.y } ?: 0.5
        val maxY = prepared.projected.maxOfOrNull { it.y } ?: minY
        val contentCenterX = (minX + maxX) / 2.0
        val contentCenterY = (minY + maxY) / 2.0
        val contentSpanX = (maxX - minX).coerceAtLeast(MIN_VIEWPORT_SPAN)
        val contentSpanY = (maxY - minY).coerceAtLeast(MIN_VIEWPORT_SPAN)
        val safe = overviewSafeArea(width, height)
        val worldPerPixel = max(
            contentSpanX / safe.width().coerceAtLeast(1f),
            contentSpanY / safe.height().coerceAtLeast(1f),
        ).times(OVERVIEW_PADDING)
        val spanX = (worldPerPixel * width).coerceAtLeast(MIN_VIEWPORT_SPAN)
        val spanY = (worldPerPixel * height).coerceIn(MIN_VIEWPORT_SPAN, MAX_OVERVIEW_VIEWPORT_SPAN)
        val minViewportX = contentCenterX - safe.centerX() * worldPerPixel
        var minViewportY = contentCenterY - safe.centerY() * worldPerPixel
        if (spanY <= 1.0) minViewportY = minViewportY.coerceIn(0.0, 1.0 - spanY)
        val zoom = floor(log2(width.coerceAtLeast(1) / (256.0 * spanX))).toInt()
            .coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        return Viewport(
            minViewportX,
            minViewportX + spanX,
            minViewportY,
            minViewportY + spanY,
            zoom,
        )
    }

    internal fun overviewSafeArea(width: Int, height: Int): RectF {
        val scale = width / 720f
        return RectF(
            OVERVIEW_SIDE_INSET * scale,
            OVERLAY_BOTTOM * scale + OVERVIEW_HEADER_GAP * scale,
            width - OVERVIEW_SIDE_INSET * scale,
            height - OVERVIEW_BOTTOM_INSET * scale,
        )
    }

    private fun blendViewport(
        from: Viewport,
        to: Viewport,
        fraction: Float,
        width: Int,
        height: Int,
    ): Viewport {
        val amount = fraction.coerceIn(0f, 1f).toDouble()
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        val fromCenterX = (from.minX + from.maxX) / 2.0
        val toCenterX = unwrapNear((to.minX + to.maxX) / 2.0, fromCenterX)
        val centerX = lerp(fromCenterX, toCenterX, amount)
        val centerY = lerp((from.minY + from.maxY) / 2.0, (to.minY + to.maxY) / 2.0, amount)
        val fromSpanY = (from.maxY - from.minY).coerceAtLeast(MIN_VIEWPORT_SPAN)
        val toSpanY = (to.maxY - to.minY).coerceAtLeast(MIN_VIEWPORT_SPAN)
        val spanY = kotlin.math.exp(lerp(ln(fromSpanY), ln(toSpanY), amount))
            .coerceIn(MIN_VIEWPORT_SPAN, MAX_OVERVIEW_VIEWPORT_SPAN)
        val spanX = spanY * aspect
        val adjustedCenterY = clampCenterY(centerY, spanY)
        val zoom = floor(log2(width.coerceAtLeast(1) / (256.0 * spanX))).toInt()
            .coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        return Viewport(
            centerX - spanX / 2.0,
            centerX + spanX / 2.0,
            adjustedCenterY - spanY / 2.0,
            adjustedCenterY + spanY / 2.0,
            zoom,
        )
    }

    private fun trailWindowDistance(journey: Journey, journeyDurationSeconds: Int): Double {
        if (journey.totalDistanceKm <= 0.0) return 0.0
        val distance = journey.totalDistanceKm * TRAIL_VISIBLE_SECONDS / journeyDurationSeconds.coerceAtLeast(1)
        return min(journey.totalDistanceKm, distance.coerceIn(MIN_TRAIL_KM, MAX_TRAIL_KM))
    }

    private fun easeOutCubic(value: Float): Float {
        val inverse = 1f - value.coerceIn(0f, 1f)
        return 1f - inverse * inverse * inverse
    }

    private fun easeInOutCubic(value: Float): Float {
        val amount = value.coerceIn(0f, 1f)
        return if (amount < 0.5f) 4f * amount * amount * amount else {
            val inverse = -2f * amount + 2f
            1f - inverse * inverse * inverse / 2f
        }
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
        draw(
            canvas,
            width,
            height,
            journey,
            TimelineFrame(progress.coerceIn(0f, 1f), 0f),
            DEFAULT_JOURNEY_DURATION_SECONDS,
            title,
            RenderText.ENGLISH,
            tiles,
        )
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        journey: Journey,
        frame: TimelineFrame,
        journeyDurationSeconds: Int,
        title: String,
        renderText: RenderText = RenderText.ENGLISH,
        tiles: (TileId) -> Bitmap?,
    ) {
        if (journey.points.isEmpty() || width <= 0 || height <= 0) return
        val viewport = viewport(journey, frame, width, height)
        val prepared = prepare(journey)
        drawBackground(canvas, width, height)
        drawTiles(canvas, width, height, viewport, tiles)

        val current = journey.positionAt(frame.journeyProgress)
        val currentScreen = screenPoint(current, prepared, viewport, width, height)
        val trailWindow = trailWindowDistance(journey, journeyDurationSeconds)
        val trailStart = max(0.0, current.distanceKm - trailWindow)
        val visibleTrail = current.distanceKm - trailStart
        val oldEnd = trailStart + visibleTrail * 0.45
        val middleEnd = trailStart + visibleTrail * 0.75
        val activeAlpha = (255 * (1f - easeOutCubic(frame.outroProgress))).toInt().coerceIn(0, 255)
        drawRouteRange(
            canvas, journey, prepared, viewport, width, height,
            trailStart, min(oldEnd, current.distanceKm), oldTrailPaint, activeAlpha,
        )
        drawRouteRange(
            canvas, journey, prepared, viewport, width, height,
            min(oldEnd, current.distanceKm), min(middleEnd, current.distanceKm), middleTrailPaint, activeAlpha,
        )
        drawRouteRange(
            canvas, journey, prepared, viewport, width, height,
            min(middleEnd, current.distanceKm), current.distanceKm, recentTrailPaint, activeAlpha,
        )

        if (frame.outroProgress > 0f) {
            overviewCompositePaint.alpha = (OVERVIEW_ROUTE_ALPHA * easeInOutCubic(frame.outroProgress))
                .toInt()
                .coerceIn(0, 255)
            val layer = canvas.saveLayer(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                overviewCompositePaint,
            )
            drawRouteRange(
                canvas, journey, prepared, viewport, width, height,
                0.0, journey.totalDistanceKm, overviewRoutePaint, 255,
            )
            canvas.restoreToCount(layer)
        }

        val head = currentScreen
        val markerAlpha = (255 * (1f - easeOutCubic(frame.outroProgress))).toInt().coerceIn(0, 255)
        val previousHeadAlpha = headPaint.alpha
        val previousRingAlpha = headRingPaint.alpha
        headPaint.alpha = markerAlpha
        headRingPaint.alpha = markerAlpha
        if (markerAlpha > 0) {
            canvas.drawCircle(head.first, head.second, width * 0.013f, headPaint)
            canvas.drawCircle(head.first, head.second, width * 0.017f, headRingPaint)
        }
        headPaint.alpha = previousHeadAlpha
        headRingPaint.alpha = previousRingAlpha
        drawOverlay(canvas, width, height, current, title, renderText)
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

    private fun drawOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        position: JourneyPosition,
        title: String,
        renderText: RenderText,
    ) {
        val scale = width / 720f
        val card = RectF(34f * scale, 28f * scale, width - 34f * scale, OVERLAY_BOTTOM * scale)
        canvas.drawRoundRect(card, 24f * scale, 24f * scale, cardPaint)
        titlePaint.textSize = 34f * scale
        bodyPaint.textSize = 20f * scale
        attributionPaint.textSize = 13f * scale
        val displayTitle = title.ifBlank { renderText.fallbackTitle }
        val availableWidth = card.width() - 36f * scale
        while (titlePaint.textSize > 20f * scale && titlePaint.measureText(displayTitle) > availableWidth) {
            titlePaint.textSize -= 1f * scale
        }
        val fittedTitle = if (titlePaint.measureText(displayTitle) <= availableWidth) displayTitle else {
            val count = titlePaint.breakText(displayTitle, true, availableWidth - titlePaint.measureText("…"), null)
            displayTitle.take(count.coerceAtLeast(1)).trimEnd() + "…"
        }
        canvas.drawText(fittedTitle, width / 2f, 72f * scale, titlePaint)
        val date = DateTimeFormatter.ofPattern(renderText.datePattern, renderText.locale)
            .format(position.point.instant.atZone(ZoneId.systemDefault()))
        val distance = position.distanceKm
        val number = NumberFormat.getNumberInstance(renderText.locale).apply { maximumFractionDigits = 0 }
        canvas.drawText(
            "$date  ·  ${number.format(distance)} ${renderText.distanceUnit}",
            width / 2f,
            108f * scale,
            bodyPaint,
        )
        canvas.drawText(renderText.attribution, width - 12f * scale, height - 12f * scale, attributionPaint)
    }

    private fun worldToScreen(point: WorldPoint, viewport: Viewport, width: Int, height: Int): Pair<Float, Float> {
        val x = unwrapNear(point.x, (viewport.minX + viewport.maxX) / 2.0)
        val sx = ((x - viewport.minX) / (viewport.maxX - viewport.minX) * width).toFloat()
        val sy = ((point.y - viewport.minY) / (viewport.maxY - viewport.minY) * height).toFloat()
        return sx to sy
    }

    private fun drawRouteRange(
        canvas: Canvas,
        journey: Journey,
        prepared: PreparedJourney,
        viewport: Viewport,
        width: Int,
        height: Int,
        startDistance: Double,
        endDistance: Double,
        paint: Paint,
        alphaScale: Int,
    ) {
        if (endDistance <= startDistance || prepared.distances.isEmpty() || alphaScale <= 0) return
        val start = journey.positionAtDistance(startDistance)
        val end = journey.positionAtDistance(endDistance)
        val startScreen = screenPoint(start, prepared, viewport, width, height)
        val endScreen = screenPoint(end, prepared, viewport, width, height)
        val firstIndex = prepared.lowerBound(startDistance)
        val lastIndex = prepared.upperBound(endDistance)
        val path = Path()
        path.moveTo(startScreen.first, startScreen.second)
        var lastX = startScreen.first
        var lastY = startScreen.second
        var index = firstIndex
        while (index <= lastIndex && index in prepared.projected.indices) {
            val projected = prepared.projected[index]
            val screen = worldToScreen(
                WorldPoint(prepared.unwrappedX[index], projected.y),
                viewport,
                width,
                height,
            )
            val dx = screen.first - lastX
            val dy = screen.second - lastY
            if (dx * dx + dy * dy >= MIN_ROUTE_PIXEL_SPACING * MIN_ROUTE_PIXEL_SPACING) {
                path.lineTo(screen.first, screen.second)
                lastX = screen.first
                lastY = screen.second
            }
            index++
        }
        path.lineTo(endScreen.first, endScreen.second)
        val previousAlpha = paint.alpha
        paint.alpha = (previousAlpha * alphaScale / 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(path, paint)
        paint.alpha = previousAlpha
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
        private const val CAMERA_CONTEXT_KM = 650.0
        private const val DEFAULT_JOURNEY_DURATION_SECONDS = 30
        private const val TRAIL_VISIBLE_SECONDS = 2.5
        private const val MIN_TRAIL_KM = 80.0
        private const val MAX_TRAIL_KM = 2_000.0
        private const val MIN_ROUTE_PIXEL_SPACING = 1.35f
        private const val OVERVIEW_ROUTE_ALPHA = 190
        private const val OVERVIEW_PADDING = 1.22
        private const val OVERLAY_BOTTOM = 132f
        private const val OVERVIEW_SIDE_INSET = 34f
        private const val OVERVIEW_HEADER_GAP = 20f
        private const val OVERVIEW_BOTTOM_INSET = 34f
        private const val CAMERA_TRACK_SAMPLES = 480
        private const val CAMERA_DEAD_ZONE_HALF = 0.20
        private const val ZOOM_OUT_ALPHA = 0.32
        private const val ZOOM_IN_ALPHA = 0.065
        private const val TILE_ZOOM_HYSTERESIS = 0.15
        private const val MIN_VIEWPORT_SPAN = 0.0003
        private const val MAX_VIEWPORT_SPAN = 0.72
        private const val MAX_OVERVIEW_VIEWPORT_SPAN = 1.25
        private const val MIN_TILE_ZOOM = 2
        private const val MAX_TILE_ZOOM = 15
    }
}
