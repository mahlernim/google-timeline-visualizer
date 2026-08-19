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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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
    private var afterNextFrameRendered: (() -> Unit)? = null

    var journey: Journey? = null
        set(value) {
            field = value
            markFrameDirty()
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
            markFrameDirty()
        }

    /**
     * Width divided by height of the video that will be exported. The preview matches it so the
     * user sees the real framing before committing to an export.
     */
    var previewAspect: Float = 1f
        set(value) {
            val next = if (value.isFinite() && value > 0f) value else 1f
            if (field == next) return
            field = next
            requestLayout()
            markFrameDirty()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.coroutineContext[Job]!!.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        if (frame == null && width > 0 && height > 0) allocateFrame(width, height)
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        frame?.recycle()
        frame = null
        frameCanvas = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        allocateFrame(w, h)
        frameDirty = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val maxHeight = (MAX_PREVIEW_DP * density).toInt()
        val minHeight = (MIN_PREVIEW_DP * density).toInt()
        // Fit the export aspect inside the available box: a tall portrait preview loses width
        // rather than pushing the buttons below it off screen.
        val height = (availableWidth / previewAspect).toInt().coerceIn(minHeight, maxHeight)
        val width = (height * previewAspect).toInt().coerceIn(1, availableWidth)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
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
            listOf(progress, (progress + 0.015f).coerceAtMost(1f), (progress + 0.03f).coerceAtMost(1f)).forEach {
                val lookahead = TimelineAnimation.frameAtOverallProgress(it, journeyDurationSeconds)
                addAll(
                    painter.requiredTiles(painter.viewport(data, lookahead, width, height, cameraSettings))
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
            tiles::cached,
        )
        frameDirty = false
        canvas.drawBitmap(target, 0f, 0f, null)
        notifyFrameRendered()
    }

    fun runAfterNextFrameRendered(action: () -> Unit) {
        afterNextFrameRendered = action
        markFrameDirty()
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
        val action = afterNextFrameRendered ?: return
        afterNextFrameRendered = null
        post(action)
    }

    private companion object {
        const val MIN_PREVIEW_DP = 240
        const val MAX_PREVIEW_DP = 460
    }
}
