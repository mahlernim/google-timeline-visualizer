package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.mahlernim.timelinevisualizer.R

/** A compact, non-geographic cue for the outbound, destination, and return story. */
class TripRoutePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        routePaint.color = ContextCompat.getColor(context, R.color.interactive)
        pointPaint.color = ContextCompat.getColor(context, R.color.brand)
        val centerY = height / 2f - density * 4f
        val startX = paddingLeft + density * 12f
        val destinationX = width - paddingRight - density * 12f
        canvas.drawLine(startX, centerY, destinationX, centerY, routePaint)
        canvas.drawCircle(startX, centerY, density * 4f, pointPaint)
        canvas.drawCircle(destinationX, centerY, density * 5f, pointPaint)
        canvas.drawLine(destinationX, centerY + density * 8f, startX, centerY + density * 8f, routePaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((36f * density).toInt(), MeasureSpec.EXACTLY))
    }
}
