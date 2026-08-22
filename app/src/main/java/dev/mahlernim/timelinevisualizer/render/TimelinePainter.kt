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
import dev.mahlernim.timelinevisualizer.model.JourneyLeg
import dev.mahlernim.timelinevisualizer.model.JourneyPosition
import dev.mahlernim.timelinevisualizer.model.MutableRenderSampleLocation
import dev.mahlernim.timelinevisualizer.model.WebMercator
import dev.mahlernim.timelinevisualizer.model.WorldPoint
import java.time.ZoneId
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
    private var cachedCameraSettings = CameraSettings.DEFAULT
    private var cachedCameraTrack: CameraTrack? = null
    private var cachedTimingJourney: Journey? = null
    private var cachedCompression = LongTripCompression.BALANCED
    private var cachedTripDetection = TripDetection.BALANCED
    private var cachedTiming: JourneyTiming? = null
    internal val cameraRoutePointEvaluations: Long
        get() = cachedPrepared?.pointEvaluations ?: 0L
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

    private fun overlayScale(width: Int, height: Int): Float = min(width, height) / 720f

    internal fun overlayCard(width: Int, height: Int): RectF {
        val scale = overlayScale(width, height)
        val cardWidth = min(width - CARD_SIDE_INSET * 2f * scale, MAX_CARD_WIDTH * scale)
        val left = (width - cardWidth) / 2f
        return RectF(left, CARD_TOP * scale, left + cardWidth, OVERLAY_BOTTOM * scale)
    }

    internal fun routeWorldPoint(journey: Journey, index: Int): WorldPoint =
        prepare(journey).worldPointAt(index)

    fun viewport(
        journey: Journey,
        progress: Float,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings = CameraSettings.DEFAULT,
        allowCameraTrackBuild: Boolean = true,
    ): Viewport {
        return viewport(
            journey,
            TimelineFrame(progress, 0f),
            width,
            height,
            cameraSettings,
            allowCameraTrackBuild,
        )
    }

    fun viewport(
        journey: Journey,
        frame: TimelineFrame,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings = CameraSettings.DEFAULT,
        allowCameraTrackBuild: Boolean = true,
    ): Viewport {
        if (width <= 0 || height <= 0) {
            return rawViewport(journey, frame.journeyProgress, width, height, cameraSettings)
        }
        val track = cachedCameraTrack(journey, width, height, cameraSettings)
            ?: if (allowCameraTrackBuild) cameraTrack(journey, width, height, cameraSettings) else null
        val journeyViewport = track?.viewportAt(frame.journeyProgress)
            ?: lightweightViewport(journey, frame.journeyProgress, width, height, cameraSettings)
        if (frame.outroProgress <= 0f) return journeyViewport
        return blendViewport(
            journeyViewport,
            overviewViewport(journey, width, height),
            easeOutCubic(frame.outroProgress),
            width,
            height,
        )
    }

    private fun lightweightViewport(
        journey: Journey,
        progress: Float,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings,
    ): Viewport {
        val current = if (progress <= 0f) {
            journey.positionAtDistance(0.0)
        } else {
            playbackPosition(journey, progress, cameraSettings)
        }
        val movement = cameraSettings.cameraMovement
        val proportionalContextKm = (journey.totalDistanceKm * movement.contextFraction)
            .coerceIn(movement.minimumContextKm, movement.maximumContextKm)
        val tail = journey.positionAtDistance(max(0.0, current.distanceKm - proportionalContextKm)).point
        val lookahead = journey.positionAtDistance(min(journey.totalDistanceKm, current.distanceKm + proportionalContextKm)).point
        val center = WebMercator.project(current.point)
        val before = WebMercator.project(tail)
        val after = WebMercator.project(lookahead)
        val beforeX = unwrapNear(before.x, center.x)
        val afterX = unwrapNear(after.x, center.x)
        val contentSpanX = max(0.00015, max(center.x, max(beforeX, afterX)) - min(center.x, min(beforeX, afterX)))
        val contentSpanY = max(0.00015, max(center.y, max(before.y, after.y)) - min(center.y, min(before.y, after.y)))
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        val spanY = max(contentSpanY * movement.padding, contentSpanX * movement.padding / aspect)
            .coerceIn(movement.minimumViewportSpan, MAX_VIEWPORT_SPAN)
        val spanX = spanY * aspect
        val minY = (center.y - spanY / 2).coerceAtLeast(0.0)
        val maxY = (center.y + spanY / 2).coerceAtMost(1.0)
        val zoom = floor(log2(width.coerceAtLeast(1) / (256.0 * spanX))).toInt()
            .coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        return Viewport(center.x - spanX / 2, center.x + spanX / 2, minY, maxY, zoom)
    }

    private fun rawViewport(
        journey: Journey,
        progress: Float,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings,
        useRangeIndex: Boolean = true,
        episodeLegsOverride: List<JourneyLeg>? = null,
    ): Viewport {
        val prepared = prepare(journey)
        val current = playbackPosition(journey, progress, cameraSettings)
        val movement = cameraSettings.cameraMovement
        val proportionalContextKm = (journey.totalDistanceKm * movement.contextFraction)
            .coerceIn(movement.minimumContextKm, movement.maximumContextKm)
        val episodeLegs = episodeLegsOverride ?: cameraEpisodeLegs(journey, cameraSettings)
        val leg = journey.legAt(current.distanceKm, episodeLegs).takeIf { movement.legAware }
        val legIndex = leg?.let(episodeLegs::indexOf) ?: -1
        val nextLocalLeg = episodeLegs.getOrNull(legIndex + 1)?.takeIf { !it.isTransfer }
        val transferArrivalBlend = if (
            cameraSettings.episodeFramingEnabled &&
            leg?.isTransfer == true &&
            nextLocalLeg != null &&
            leg.lengthKm > 0.0
        ) {
            smoothstep(
                ((current.distanceKm - leg.startKm) / leg.lengthKm - EPISODE_ARRIVAL_ZOOM_START_FRACTION) /
                    (1.0 - EPISODE_ARRIVAL_ZOOM_START_FRACTION),
            )
        } else {
            0.0
        }
        val contextKm = if (leg?.isTransfer == true) {
            if (transferArrivalBlend > 0.0) {
                val arrivalContextKm = min(
                    proportionalContextKm,
                    nextLocalLeg?.lengthKm ?: proportionalContextKm,
                ).coerceAtLeast(MIN_CONTEXT_KM)
                kotlin.math.exp(
                    lerp(
                        ln(leg.lengthKm.coerceAtLeast(MIN_CONTEXT_KM)),
                        ln(arrivalContextKm),
                        transferArrivalBlend,
                    ),
                )
            } else {
                leg.lengthKm
            }
        } else {
            proportionalContextKm
        }
        val padding = when {
            leg?.isTransfer == true -> lerp(
                TRANSFER_PADDING,
                movement.padding * cameraSettings.localFraming.paddingMultiplier,
                transferArrivalBlend,
            )
            cameraSettings.episodeFramingEnabled ->
                movement.padding * cameraSettings.localFraming.paddingMultiplier
            else -> movement.padding
        }
        val rangeStartKm = leg?.startKm ?: 0.0
        val lookaheadLimitKm = when {
            leg == null -> journey.totalDistanceKm
            leg.isTransfer -> nextLocalLeg?.endKm ?: leg.endKm
            !cameraSettings.episodeFramingEnabled -> journey.totalDistanceKm
            else -> {
                val nextTransfer = episodeLegs.getOrNull(legIndex + 1)?.takeIf { it.isTransfer }
                val departureLeadKm = min(
                    EPISODE_DEPARTURE_LEAD_MAX_KM,
                    leg.lengthKm * EPISODE_DEPARTURE_LEAD_FRACTION,
                )
                if (nextTransfer != null && leg.endKm - current.distanceKm <= departureLeadKm) {
                    nextTransfer.endKm
                } else {
                    leg.endKm
                }
            }
        }
        val tailDistance = max(rangeStartKm, current.distanceKm - contextKm)
        val lookaheadDistance = min(lookaheadLimitKm, current.distanceKm + contextKm)
        val routeReferenceX = prepared.referenceX(current.distanceKm)
        val centerPoint = WebMercator.project(current.point)
        val centerX = unwrapNear(centerPoint.x, routeReferenceX)
        val centerY = centerPoint.y
        var minFocusX = centerX
        var maxFocusX = centerX
        var minFocusY = centerY
        var maxFocusY = centerY
        fun include(point: WorldPoint) {
            val x = unwrapNear(point.x, centerX)
            minFocusX = min(minFocusX, x)
            maxFocusX = max(maxFocusX, x)
            minFocusY = min(minFocusY, point.y)
            maxFocusY = max(maxFocusY, point.y)
        }
        include(WebMercator.project(journey.positionAtDistance(tailDistance).point))
        val start = prepared.lowerBound(tailDistance)
        val end = prepared.upperBound(lookaheadDistance)
        if (useRangeIndex) {
            prepared.boundsForRange(start, end, centerX)?.let { bounds ->
                minFocusX = min(minFocusX, bounds.minX)
                maxFocusX = max(maxFocusX, bounds.maxX)
                minFocusY = min(minFocusY, bounds.minY)
                maxFocusY = max(maxFocusY, bounds.maxY)
            }
        } else {
            for (index in start..end) {
                if (index in 0 until prepared.size) {
                    prepared.countPointEvaluation()
                    include(prepared.worldPointAt(index))
                }
            }
        }
        include(WebMercator.project(journey.positionAtDistance(lookaheadDistance).point))
        val contentSpanX = max(0.00015, maxFocusX - minFocusX)
        val contentSpanY = max(0.00015, maxFocusY - minFocusY)
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        var spanY = max(contentSpanY * padding, contentSpanX * padding / aspect)
        spanY = spanY.coerceIn(movement.minimumViewportSpan, 0.72)
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

    internal fun rawViewportForTest(
        journey: Journey,
        progress: Float,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings = CameraSettings.DEFAULT,
        useRangeIndex: Boolean,
    ): Viewport = rawViewport(journey, progress, width, height, cameraSettings, useRangeIndex)

    private fun playbackPosition(
        journey: Journey,
        progress: Float,
        cameraSettings: CameraSettings,
    ): JourneyPosition {
        if (
            cachedTimingJourney !== journey ||
            cachedCompression != cameraSettings.longTripCompression ||
            cachedTripDetection != cameraSettings.tripDetection
        ) {
            cachedTimingJourney = journey
            cachedCompression = cameraSettings.longTripCompression
            cachedTripDetection = cameraSettings.tripDetection
            cachedTiming = JourneyTiming.create(
                journey,
                cameraSettings.longTripCompression,
                cameraSettings.tripDetection,
            )
        }
        return journey.positionAtDistance(cachedTiming!!.distanceAt(progress))
    }

    private fun cameraTrack(
        journey: Journey,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings,
    ): CameraTrack {
        if (
            cachedCameraJourney === journey &&
            cachedCameraWidth == width &&
            cachedCameraHeight == height &&
            cachedCameraSettings == cameraSettings
        ) {
            return cachedCameraTrack!!
        }
        val track = buildCameraTrack(journey, width, height, cameraSettings)
        cachedCameraJourney = journey
        cachedCameraWidth = width
        cachedCameraHeight = height
        cachedCameraSettings = cameraSettings
        cachedCameraTrack = track
        return track
    }

    private fun cachedCameraTrack(
        journey: Journey,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings,
    ): CameraTrack? = cachedCameraTrack?.takeIf {
        cachedCameraJourney === journey &&
            cachedCameraWidth == width &&
            cachedCameraHeight == height &&
            cachedCameraSettings == cameraSettings
    }

    internal fun buildCameraTrackForBackground(
        journey: Journey,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): CameraPreparation {
        val track = buildCameraTrack(journey, width, height, cameraSettings, onProgress)
        return CameraPreparation(track, prepare(journey))
    }

    internal fun installCameraPreparation(
        journey: Journey,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings,
        preparation: CameraPreparation,
    ) {
        cachedJourney = journey
        cachedPrepared = preparation.prepared
        cachedCameraJourney = journey
        cachedCameraWidth = width
        cachedCameraHeight = height
        cachedCameraSettings = cameraSettings
        cachedCameraTrack = preparation.track
    }

    private fun buildCameraTrack(
        journey: Journey,
        width: Int,
        height: Int,
        cameraSettings: CameraSettings,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): CameraTrack {
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        val movement = cameraSettings.cameraMovement
        val episodeLegs = cameraEpisodeLegs(journey, cameraSettings)
        val rawSamples = (0..CAMERA_TRACK_SAMPLES).map { sample ->
            val progress = sample.toFloat() / CAMERA_TRACK_SAMPLES
            val raw = rawViewport(
                journey,
                progress,
                width,
                height,
                cameraSettings,
                episodeLegsOverride = episodeLegs,
            )
            val result = RawCameraSample(
                viewport = raw,
                marker = WebMercator.project(playbackPosition(journey, progress, cameraSettings).point),
                arrivalZoomIntensity = episodeArrivalZoomIntensity(
                    journey,
                    progress,
                    cameraSettings,
                    episodeLegs,
                ),
            )
            val completed = sample + 1
            if (sample == 0 || completed % CAMERA_PROGRESS_INTERVAL == 0 || sample == CAMERA_TRACK_SAMPLES) {
                onProgress(completed, CAMERA_TRACK_SAMPLES + 1)
            }
            result
        }
        val fixedSpanY = if (movement.fixedZoom) {
            val spans = rawSamples.map { it.viewport.maxY - it.viewport.minY }.sorted()
            spans[(spans.lastIndex * FIXED_ZOOM_PERCENTILE).toInt()]
                .coerceIn(movement.minimumViewportSpan, MAX_VIEWPORT_SPAN)
        } else {
            null
        }
        val baseFrames = ArrayList<CameraFrame>(CAMERA_TRACK_SAMPLES + 1)
        var previous: CameraFrame? = null
        rawSamples.forEach { sample ->
            val raw = sample.viewport
            val rawCenterX = (raw.minX + raw.maxX) / 2.0
            val rawCenterY = (raw.minY + raw.maxY) / 2.0
            val rawSpanY = fixedSpanY ?: (raw.maxY - raw.minY)
                .coerceAtLeast(movement.minimumViewportSpan)
            val marker = sample.marker
            val frame = if (previous == null) {
                CameraFrame(
                    rawCenterX,
                    clampCenterY(rawCenterY, rawSpanY),
                    rawSpanY,
                    tileZoom(width, aspect, rawSpanY),
                )
            } else {
                val zoomAlpha = if (rawSpanY > previous.spanY) {
                    movement.zoomOutAlpha
                } else {
                    lerp(
                        movement.zoomInAlpha,
                        EPISODE_ARRIVAL_ZOOM_ALPHA,
                        sample.arrivalZoomIntensity,
                    )
                }
                val spanY = if (movement.fixedZoom) {
                    rawSpanY
                } else {
                    kotlin.math.exp(lerp(ln(previous.spanY), ln(rawSpanY), zoomAlpha))
                        .coerceIn(movement.minimumViewportSpan, MAX_VIEWPORT_SPAN)
                }
                val spanX = spanY * aspect
                val markerX = unwrapNear(marker.x, previous.centerX)
                var desiredCenterX = previous.centerX
                var desiredCenterY = previous.centerY
                val deadHalfX = spanX * CAMERA_DEAD_ZONE_HALF
                val deadHalfY = spanY * CAMERA_DEAD_ZONE_HALF
                desiredCenterX = when {
                    markerX < desiredCenterX - deadHalfX -> markerX + deadHalfX
                    markerX > desiredCenterX + deadHalfX -> markerX - deadHalfX
                    else -> desiredCenterX
                }
                desiredCenterY = when {
                    marker.y < desiredCenterY - deadHalfY -> marker.y + deadHalfY
                    marker.y > desiredCenterY + deadHalfY -> marker.y - deadHalfY
                    else -> desiredCenterY
                }
                var centerX = desiredCenterX
                var centerY = desiredCenterY
                val safetyHalfX = spanX * CAMERA_CENTER_ZONE_HALF
                val safetyHalfY = spanY * CAMERA_CENTER_ZONE_HALF
                centerX = centerX.coerceIn(markerX - safetyHalfX, markerX + safetyHalfX)
                centerY = centerY.coerceIn(marker.y - safetyHalfY, marker.y + safetyHalfY)
                centerY = clampCenterY(centerY, spanY)
                val continuousZoom = log2(width.coerceAtLeast(1) / (256.0 * spanX))
                val tileZoom = stabilizedTileZoom(previous.zoom, continuousZoom)
                CameraFrame(centerX, centerY, spanY, tileZoom)
            }
            baseFrames.add(frame)
            previous = frame
        }
        val frames = recenterCameraFrames(
            journey,
            cameraSettings,
            baseFrames,
            width,
            height,
            aspect,
            episodeLegs,
        )
        return CameraTrack(frames, aspect)
    }

    private fun episodeArrivalZoomIntensity(
        journey: Journey,
        progress: Float,
        cameraSettings: CameraSettings,
        episodeLegsOverride: List<JourneyLeg>? = null,
    ): Double {
        if (!cameraSettings.episodeFramingEnabled || !cameraSettings.cameraMovement.legAware) return 0.0
        val currentDistanceKm = playbackPosition(journey, progress, cameraSettings).distanceKm
        val legs = episodeLegsOverride ?: cameraEpisodeLegs(journey, cameraSettings)
        val leg = journey.legAt(currentDistanceKm, legs)
        val legIndex = legs.indexOf(leg)
        if (leg.isTransfer) {
            if (legs.getOrNull(legIndex + 1)?.isTransfer != false || leg.lengthKm <= 0.0) return 0.0
            val fraction = (currentDistanceKm - leg.startKm) / leg.lengthKm
            return smoothstep(
                (fraction - EPISODE_ARRIVAL_ZOOM_START_FRACTION) /
                    (1.0 - EPISODE_ARRIVAL_ZOOM_START_FRACTION),
            )
        }
        if (legs.getOrNull(legIndex - 1)?.isTransfer != true || leg.lengthKm <= 0.0) return 0.0
        val sampleDistanceKm = journey.totalDistanceKm / CAMERA_TRACK_SAMPLES
        val holdDistanceKm = min(
            leg.lengthKm,
            sampleDistanceKm * EPISODE_ARRIVAL_HOLD_SAMPLES,
        ).coerceAtLeast(MIN_CONTEXT_KM)
        val settleDistanceKm = min(
            leg.lengthKm,
            max(EPISODE_ARRIVAL_SETTLE_MAX_KM, holdDistanceKm),
        ).coerceAtLeast(holdDistanceKm)
        val distanceAfterArrivalKm = currentDistanceKm - leg.startKm
        if (distanceAfterArrivalKm <= holdDistanceKm || settleDistanceKm <= holdDistanceKm) return 1.0
        return 1.0 - smoothstep(
            (distanceAfterArrivalKm - holdDistanceKm) / (settleDistanceKm - holdDistanceKm),
        )
    }

    private fun smoothstep(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun recenterCameraFrames(
        journey: Journey,
        cameraSettings: CameraSettings,
        baseFrames: List<CameraFrame>,
        width: Int,
        height: Int,
        aspect: Double,
        episodeLegs: List<JourneyLeg>,
    ): List<CameraFrame> {
        val frames = ArrayList<CameraFrame>(baseFrames.size)
        var previous: CameraFrame? = null
        baseFrames.forEachIndexed { index, originalBase ->
            val actualProgress = index.toFloat() / baseFrames.lastIndex.coerceAtLeast(1)
            val base = episodeAlignedFrame(
                journey,
                cameraSettings,
                actualProgress,
                originalBase,
                width,
                height,
                aspect,
                episodeLegs,
            )
            val projectedMarker = WebMercator.project(
                playbackPosition(journey, actualProgress, cameraSettings).point,
            )
            val marker = if (previous == null) {
                projectedMarker
            } else {
                projectedMarker.copy(x = unwrapNear(projectedMarker.x, previous.centerX))
            }
            val frame = if (previous == null) {
                base.copy(centerX = marker.x, centerY = clampCenterY(marker.y, base.spanY))
            } else {
                val markerX = unwrapNear(marker.x, previous.centerX)
                val spanX = base.spanY * aspect
                val deadHalfX = spanX * CAMERA_DEAD_ZONE_HALF
                val deadHalfY = base.spanY * CAMERA_DEAD_ZONE_HALF
                var centerX = when {
                    markerX < previous.centerX - deadHalfX -> markerX + deadHalfX
                    markerX > previous.centerX + deadHalfX -> markerX - deadHalfX
                    else -> previous.centerX
                }
                var centerY = when {
                    marker.y < previous.centerY - deadHalfY -> marker.y + deadHalfY
                    marker.y > previous.centerY + deadHalfY -> marker.y - deadHalfY
                    else -> previous.centerY
                }
                centerX = centerX.coerceIn(markerX - deadHalfX, markerX + deadHalfX)
                centerY = centerY.coerceIn(marker.y - deadHalfY, marker.y + deadHalfY)
                base.copy(centerX = centerX, centerY = clampCenterY(centerY, base.spanY))
            }
            frames.add(frame)
            previous = frame
        }
        return frames
    }

    private fun episodeAlignedFrame(
        journey: Journey,
        cameraSettings: CameraSettings,
        actualProgress: Float,
        base: CameraFrame,
        width: Int,
        height: Int,
        aspect: Double,
        episodeLegs: List<JourneyLeg>,
    ): CameraFrame {
        if (!cameraSettings.episodeFramingEnabled || !cameraSettings.cameraMovement.legAware) return base
        val distanceKm = playbackPosition(journey, actualProgress, cameraSettings).distanceKm
        val leg = journey.legAt(distanceKm, episodeLegs)
        val alignment = if (leg.isTransfer) {
            episodeArrivalZoomIntensity(journey, actualProgress, cameraSettings, episodeLegs)
        } else {
            1.0
        }
        if (alignment <= 0.0) return base
        val alignedViewport = rawViewport(
            journey,
            actualProgress,
            width,
            height,
            cameraSettings,
            episodeLegsOverride = episodeLegs,
        )
        val alignedSpanY = (alignedViewport.maxY - alignedViewport.minY)
            .coerceAtLeast(cameraSettings.cameraMovement.minimumViewportSpan)
        if (alignedSpanY >= base.spanY) return base
        val spanY = kotlin.math.exp(lerp(ln(base.spanY), ln(alignedSpanY), alignment))
        return base.copy(
            spanY = spanY,
            zoom = tileZoom(width, aspect, spanY),
        )
    }

    private fun cameraEpisodeLegs(
        journey: Journey,
        cameraSettings: CameraSettings,
    ): List<JourneyLeg> = if (
        cameraSettings.episodeFramingEnabled && cameraSettings.cameraMovement.legAware
    ) {
        journey.legsForThreshold(
            journey.transferThresholdKm * cameraSettings.tripDetection.thresholdMultiplier,
        )
    } else {
        journey.legs
    }

    private fun tileZoom(width: Int, aspect: Double, spanY: Double): Int =
        floor(log2(width.coerceAtLeast(1) / (256.0 * spanY * aspect))).toInt()
            .coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)

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
        var minX = 0.5
        var maxX = 0.5
        var minY = 0.5
        var maxY = 0.5
        if (prepared.size > 0) {
            val first = prepared.worldPointAt(0)
            minX = first.x
            maxX = first.x
            minY = first.y
            maxY = first.y
            for (index in 1 until prepared.size) {
                val point = prepared.worldPointAt(index)
                minX = min(minX, point.x)
                maxX = max(maxX, point.x)
                minY = min(minY, point.y)
                maxY = max(maxY, point.y)
            }
        }
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
        val scale = overlayScale(width, height)
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
        }
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        journey: Journey,
        progress: Float,
        title: String,
        cameraSettings: CameraSettings = CameraSettings.DEFAULT,
        allowCameraTrackBuild: Boolean = true,
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
            cameraSettings,
            allowCameraTrackBuild,
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
        cameraSettings: CameraSettings = CameraSettings.DEFAULT,
        allowCameraTrackBuild: Boolean = true,
        tiles: (TileId) -> Bitmap?,
    ) {
        if (journey.points.isEmpty() || width <= 0 || height <= 0) return
        val viewport = viewport(journey, frame, width, height, cameraSettings, allowCameraTrackBuild)
        val prepared = if (allowCameraTrackBuild) prepare(journey) else null
        drawBackground(canvas, width, height)
        drawTiles(canvas, width, height, viewport, tiles)

        val current = if (!allowCameraTrackBuild && frame.journeyProgress <= 0f) {
            journey.positionAtDistance(0.0)
        } else {
            playbackPosition(journey, frame.journeyProgress, cameraSettings)
        }
        val currentScreen = if (prepared == null) {
            val projected = WebMercator.project(current.point)
            worldToScreen(projected, viewport, width, height)
        } else {
            screenPoint(current, prepared, viewport, width, height)
        }
        val trailWindow = trailWindowDistance(journey, journeyDurationSeconds)
        val trailStart = max(0.0, current.distanceKm - trailWindow)
        val visibleTrail = current.distanceKm - trailStart
        val oldEnd = trailStart + visibleTrail * 0.45
        val middleEnd = trailStart + visibleTrail * 0.75
        val activeAlpha = (255 * (1f - easeOutCubic(frame.outroProgress))).toInt().coerceIn(0, 255)
        if (prepared != null) {
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
        }

        if (frame.outroProgress > 0f && prepared != null) {
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
            val markerEdge = min(width, height).toFloat()
            canvas.drawCircle(head.first, head.second, markerEdge * 0.013f, headPaint)
            canvas.drawCircle(head.first, head.second, markerEdge * 0.017f, headRingPaint)
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
        val scale = overlayScale(width, height)
        val card = overlayCard(width, height)
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
        canvas.drawText(fittedTitle, card.centerX(), 72f * scale, titlePaint)
        val date = renderText.dateFormatter.format(position.point.instant.atZone(ZoneId.systemDefault()))
        canvas.drawText(
            "$date  ·  ${renderText.formatDistance(position.distanceKm)}",
            card.centerX(),
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
        if (endDistance <= startDistance || prepared.size == 0 || alphaScale <= 0) return
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
        while (index <= lastIndex && index in 0 until prepared.size) {
            val projected = prepared.worldPointAt(index)
            val screen = worldToScreen(
                projected,
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
        val prepared = PreparedJourney(journey)
        cachedJourney = journey
        cachedPrepared = prepared
        return prepared
    }

    private fun unwrapNear(value: Double, reference: Double): Double {
        var result = value
        while (result - reference > 0.5) result -= 1.0
        while (result - reference < -0.5) result += 1.0
        return result
    }

    internal class PreparedJourney(private val journey: Journey) {
        private val unwrappedPointX = DoubleArray(journey.points.size)
        private val pointY = DoubleArray(journey.points.size)
        private val blockBounds = mutableMapOf<Int, RouteBounds>()
        var pointEvaluations: Long = 0L
            private set
        val size: Int get() = journey.renderPath.size

        init {
            for (index in journey.points.indices) {
                val projected = WebMercator.project(journey.points[index])
                pointY[index] = projected.y
                unwrappedPointX[index] = if (index == 0) projected.x else {
                    unwrap(projected.x, unwrappedPointX[index - 1])
                }
            }
        }

        fun worldPointAt(index: Int): WorldPoint {
            val point = MutableWorldPoint()
            fillWorldPoint(index, point)
            return WorldPoint(point.x, point.y)
        }

        private fun fillWorldPoint(index: Int, point: MutableWorldPoint) {
            val location = point.location
            if (!journey.fillRenderSampleLocation(index, location)) {
                point.x = unwrappedPointX.firstOrNull() ?: 0.5
                point.y = pointY.firstOrNull() ?: 0.5
                return
            }
            if (location.step == location.steps) {
                point.x = unwrappedPointX[location.toPointIndex]
                point.y = pointY[location.toPointIndex]
                return
            }
            val from = location.toPointIndex - 1
            val interpolated = Journey.interpolate(
                journey.points[from],
                journey.points[location.toPointIndex],
                location.fraction,
            )
            val projected = WebMercator.project(interpolated)
            val reference = unwrappedPointX[from] +
                (unwrappedPointX[location.toPointIndex] - unwrappedPointX[from]) * location.fraction
            point.x = unwrap(projected.x, reference)
            point.y = projected.y
        }

        fun countPointEvaluation() {
            pointEvaluations += 1
        }

        fun boundsForRange(firstIndex: Int, lastIndex: Int, referenceX: Double): RouteBounds? {
            if (size == 0) return null
            val first = firstIndex.coerceIn(0, size - 1)
            val last = lastIndex.coerceIn(-1, size - 1)
            if (first > last) return null

            val result = MutableRouteBounds()
            val point = MutableWorldPoint()
            var index = first
            while (index <= last && index % ROUTE_BOUNDS_BLOCK_SIZE != 0) {
                includePoint(result, point, index, referenceX)
                index += 1
            }
            while (index + ROUTE_BOUNDS_BLOCK_SIZE - 1 <= last) {
                val block = index / ROUTE_BOUNDS_BLOCK_SIZE
                val bounds = blockBounds.getOrPut(block) {
                    calculateBlockBounds(index, index + ROUTE_BOUNDS_BLOCK_SIZE - 1)
                }
                val adjustedMinX = unwrap(bounds.minX, referenceX)
                val adjustedMaxX = unwrap(bounds.maxX, referenceX)
                val minShift = adjustedMinX - bounds.minX
                val maxShift = adjustedMaxX - bounds.maxX
                if (minShift == maxShift) {
                    val adjusted = RouteBounds(
                        minX = adjustedMinX,
                        maxX = adjustedMaxX,
                        minY = bounds.minY,
                        maxY = bounds.maxY,
                    )
                    result.include(adjusted)
                } else {
                    for (pointIndex in index until index + ROUTE_BOUNDS_BLOCK_SIZE) {
                        includePoint(result, point, pointIndex, referenceX)
                    }
                }
                index += ROUTE_BOUNDS_BLOCK_SIZE
            }
            while (index <= last) {
                includePoint(result, point, index, referenceX)
                index += 1
            }
            return result.toRouteBounds()
        }

        private fun calculateBlockBounds(firstIndex: Int, lastIndex: Int): RouteBounds {
            val point = MutableWorldPoint()
            evaluatedWorldPoint(firstIndex, point)
            val firstX = point.x
            val firstY = point.y
            val result = MutableRouteBounds().apply { include(firstX, firstY) }
            for (index in firstIndex + 1..lastIndex) {
                evaluatedWorldPoint(index, point)
                result.include(point.x, point.y)
            }
            return result.toRouteBounds()
        }

        private fun includePoint(
            bounds: MutableRouteBounds,
            point: MutableWorldPoint,
            index: Int,
            referenceX: Double,
        ) {
            evaluatedWorldPoint(index, point)
            val x = unwrap(point.x, referenceX)
            bounds.include(x, point.y)
        }

        private fun evaluatedWorldPoint(index: Int, point: MutableWorldPoint) {
            pointEvaluations += 1
            fillWorldPoint(index, point)
        }

        private fun distanceAt(index: Int): Double = journey.renderPath[index].distanceKm

        fun lowerBound(value: Double): Int {
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (distanceAt(middle) < value) low = middle + 1 else high = middle
            }
            return low.coerceAtMost((size - 1).coerceAtLeast(0))
        }

        fun upperBound(value: Double): Int {
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (distanceAt(middle) <= value) low = middle + 1 else high = middle
            }
            return low - 1
        }

        fun referenceX(distanceKm: Double): Double {
            if (journey.points.isEmpty()) return 0.5
            val position = journey.positionAtDistance(distanceKm)
            val projected = WebMercator.project(position.point)
            val reference = if (position.fromIndex == position.toIndex) {
                unwrappedPointX[position.fromIndex]
            } else {
                unwrappedPointX[position.fromIndex] +
                    (unwrappedPointX[position.toIndex] - unwrappedPointX[position.fromIndex]) * position.segmentFraction
            }
            return unwrap(projected.x, reference)
        }

        private fun unwrap(value: Double, reference: Double): Double {
            var result = value
            while (result - reference > 0.5) result -= 1.0
            while (result - reference < -0.5) result += 1.0
            return result
        }
    }

    internal data class CameraFrame(
        val centerX: Double,
        val centerY: Double,
        val spanY: Double,
        val zoom: Int,
    )

    private data class RawCameraSample(
        val viewport: Viewport,
        val marker: WorldPoint,
        val arrivalZoomIntensity: Double,
    )

    internal data class RouteBounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
    )

    private class MutableRouteBounds {
        private var initialized = false
        private var minX = 0.0
        private var maxX = 0.0
        private var minY = 0.0
        private var maxY = 0.0

        fun include(x: Double, y: Double) {
            if (!initialized) {
                initialized = true
                minX = x
                maxX = x
                minY = y
                maxY = y
                return
            }
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
        }

        fun include(bounds: RouteBounds) {
            include(bounds.minX, bounds.minY)
            include(bounds.maxX, bounds.maxY)
        }

        fun toRouteBounds(): RouteBounds = RouteBounds(minX, maxX, minY, maxY)
    }

    private class MutableWorldPoint(
        var x: Double = 0.0,
        var y: Double = 0.0,
        val location: MutableRenderSampleLocation = MutableRenderSampleLocation(),
    )

    internal class CameraTrack(
        internal val frames: List<CameraFrame>,
        private val aspect: Double,
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

    internal data class CameraPreparation(
        val track: CameraTrack,
        val prepared: PreparedJourney,
    )

    companion object {
        private const val TRANSFER_PADDING = 2.8
        private const val EPISODE_DEPARTURE_LEAD_FRACTION = 0.15
        private const val EPISODE_DEPARTURE_LEAD_MAX_KM = 50.0
        private const val EPISODE_ARRIVAL_ZOOM_START_FRACTION = 0.75
        private const val EPISODE_ARRIVAL_ZOOM_ALPHA = 1.00
        private const val EPISODE_ARRIVAL_HOLD_SAMPLES = 2.0
        private const val EPISODE_ARRIVAL_SETTLE_MAX_KM = 25.0
        private const val MIN_CONTEXT_KM = 0.001
        private const val DEFAULT_JOURNEY_DURATION_SECONDS = 30
        private const val TRAIL_VISIBLE_SECONDS = 2.5
        private const val MIN_TRAIL_KM = 80.0
        private const val MAX_TRAIL_KM = 2_000.0
        private const val MIN_ROUTE_PIXEL_SPACING = 1.35f
        private const val OVERVIEW_ROUTE_ALPHA = 190
        private const val OVERVIEW_PADDING = 1.22
        private const val OVERLAY_BOTTOM = 132f
        private const val CARD_TOP = 28f
        private const val CARD_SIDE_INSET = 34f
        private const val MAX_CARD_WIDTH = 720f - CARD_SIDE_INSET * 2f
        private const val OVERVIEW_SIDE_INSET = 34f
        private const val OVERVIEW_HEADER_GAP = 20f
        private const val OVERVIEW_BOTTOM_INSET = 34f
        private const val CAMERA_TRACK_SAMPLES = 480
        private const val CAMERA_PROGRESS_INTERVAL = 32
        private const val ROUTE_BOUNDS_BLOCK_SIZE = 256
        private const val CAMERA_DEAD_ZONE_HALF = 0.20
        private const val CAMERA_CENTER_ZONE_HALF = 0.20
        private const val FIXED_ZOOM_PERCENTILE = 0.80
        private const val TILE_ZOOM_HYSTERESIS = 0.15
        private const val MIN_VIEWPORT_SPAN = 0.0003
        private const val MAX_VIEWPORT_SPAN = 0.72
        private const val MAX_OVERVIEW_VIEWPORT_SPAN = 1.25
        private const val MIN_TILE_ZOOM = 2
        private const val MAX_TILE_ZOOM = 15
    }
}
