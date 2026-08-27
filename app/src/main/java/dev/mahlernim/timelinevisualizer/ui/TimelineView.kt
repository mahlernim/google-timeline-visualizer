package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.View
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.TileId
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import dev.mahlernim.timelinevisualizer.render.RenderText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val painter = TimelinePainter()
    private val tiles = TileRepository(context.applicationContext)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loading = ConcurrentHashMap.newKeySet<TileId>()
    private var frame: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var frameDirty = true
    private var redrawPosted = false
    private val afterNextFrameRendered = mutableListOf<() -> Unit>()
    private var cameraPreparationJob: Job? = null
    private var cameraPreparationGeneration = 0

    var onCameraPreparationChanged: ((ready: Boolean) -> Unit)? = null
    var onCameraPreparationFailed: ((error: Throwable) -> Unit)? = null
    var isCameraReady: Boolean = false
        private set

    var journey: Journey? = null
        set(value) {
            field = value
            markFrameDirty()
            restartCameraPreparation()
        }
    var progress: Float = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            markFrameDirty()
        }
    var journeyDurationSeconds: Int = 30
        set(value) {
            val next = value.coerceAtLeast(1)
            if (field == next) return
            field = next
            markFrameDirty()
        }
    var videoTitle: String = ""
        set(value) {
            if (field == value) return
            field = value
            markFrameDirty()
        }
    var renderText: RenderText = RenderText.ENGLISH
        set(value) {
            if (field == value) return
            field = value
            markFrameDirty()
        }
    var cameraSettings: CameraSettings = CameraSettings.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            previewAspectRatio = value.videoQuality.aspectRatio
            markFrameDirty()
            restartCameraPreparation()
        }

    var previewAspectRatio: Float = 1f
        private set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.coroutineContext[Job]!!.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        if (frame == null && width > 0 && height > 0) allocateFrame(width, height)
        restartCameraPreparation()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        cameraPreparationJob = null
        frame?.recycle()
        frame = null
        frameCanvas = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        allocateFrame(w, h)
        frameDirty = true
        if (w != oldw || h != oldh) restartCameraPreparation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val maximumHeight = (MAX_PREVIEW_HEIGHT_DP * resources.displayMetrics.density).roundToInt()
            .coerceAtLeast(1)
        val (width, height) = previewSize(availableWidth, maximumHeight, previewAspectRatio)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = journey ?: return
        if (data.points.isEmpty()) return
        if (!frameDirty) {
            frame?.let {
                canvas.drawBitmap(it, 0f, 0f, null)
                notifyFrameRendered()
            }
            return
        }
        val animationFrame = TimelineAnimation.frameAtOverallProgress(progress, journeyDurationSeconds)
        val tilesToLoad = buildSet {
            val tileProgress = if (isCameraReady) {
                listOf(progress, (progress + 0.015f).coerceAtMost(1f), (progress + 0.03f).coerceAtMost(1f))
            } else {
                listOf(progress)
            }
            tileProgress.forEach {
                val lookahead = TimelineAnimation.frameAtOverallProgress(it, journeyDurationSeconds)
                addAll(
                    painter.requiredTiles(
                        painter.viewport(
                            data,
                            lookahead,
                            width,
                            height,
                            cameraSettings,
                            allowCameraTrackBuild = isCameraReady,
                        ),
                    )
                        .map { tile -> tile.id },
                )
            }
        }
        tilesToLoad.forEach { tile ->
            if (tiles.cached(tile) == null && loading.add(tile)) {
                scope.launch {
                    tiles.load(tile)
                    loading.remove(tile)
                    markFrameDirty()
                }
            }
        }
        val target = frame ?: return
        val targetCanvas = frameCanvas ?: return
        painter.draw(
            targetCanvas,
            width,
            height,
            data,
            animationFrame,
            journeyDurationSeconds,
            videoTitle,
            renderText,
            cameraSettings,
            allowCameraTrackBuild = isCameraReady,
            tiles = tiles::cached,
        )
        frameDirty = false
        canvas.drawBitmap(target, 0f, 0f, null)
        notifyFrameRendered()
    }

    fun runAfterNextFrameRendered(action: () -> Unit) {
        afterNextFrameRendered += action
        markFrameDirty()
    }

    fun retryCameraPreparation() {
        restartCameraPreparation()
    }

    private fun restartCameraPreparation() {
        cameraPreparationJob?.cancel()
        cameraPreparationGeneration += 1
        val generation = cameraPreparationGeneration
        val data = journey
        val targetWidth = width
        val targetHeight = height
        val settings = cameraSettings
        if (data == null || data.points.isEmpty()) {
            setCameraReady(data != null)
            return
        }
        setCameraReady(false)
        if (targetWidth <= 0 || targetHeight <= 0 || !isAttachedToWindow) return

        cameraPreparationJob = scope.launch(Dispatchers.Default) {
            try {
                val preparation = TimelinePainter().buildCameraTrackForBackground(
                    data,
                    targetWidth,
                    targetHeight,
                    settings,
                ) { _, _ ->
                    coroutineContext.ensureActive()
                }
                withContext(Dispatchers.Main.immediate) {
                    if (generation != cameraPreparationGeneration || journey !== data || cameraSettings != settings) {
                        return@withContext
                    }
                    painter.installCameraPreparation(data, targetWidth, targetHeight, settings, preparation)
                    setCameraReady(true)
                    markFrameDirty()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (generation == cameraPreparationGeneration) onCameraPreparationFailed?.invoke(error)
                }
            }
        }
    }

    private fun setCameraReady(ready: Boolean) {
        if (isCameraReady == ready) return
        isCameraReady = ready
        onCameraPreparationChanged?.invoke(ready)
    }

    private fun allocateFrame(width: Int, height: Int) {
        frame?.recycle()
        if (width <= 0 || height <= 0) {
            frame = null
            frameCanvas = null
            return
        }
        frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        frameCanvas = Canvas(frame!!)
    }

    private fun markFrameDirty() {
        frameDirty = true
        if (redrawPosted) return
        redrawPosted = true
        postInvalidateOnAnimation()
        post {
            redrawPosted = false
        }
    }

    private fun notifyFrameRendered() {
        if (afterNextFrameRendered.isEmpty()) return
        val actions = afterNextFrameRendered.toList()
        afterNextFrameRendered.clear()
        actions.forEach { action -> post { action() } }
    }

    companion object {
        private const val MAX_PREVIEW_HEIGHT_DP = 460

        internal fun previewSize(maximumWidth: Int, maximumHeight: Int, aspectRatio: Float): Pair<Int, Int> {
            val safeWidth = maximumWidth.coerceAtLeast(1)
            val safeHeight = maximumHeight.coerceAtLeast(1)
            val safeAspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
            var width = safeWidth
            var height = (width / safeAspect).roundToInt().coerceAtLeast(1)
            if (height > safeHeight) {
                height = safeHeight
                width = (height * safeAspect).roundToInt().coerceIn(1, safeWidth)
            }
            return width to height
        }
    }
}
