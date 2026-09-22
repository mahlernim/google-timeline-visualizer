package dev.mahlernim.timelinevisualizer.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import java.text.NumberFormat
import kotlin.math.min

/** A measured layout shared by preview and export, including languages without word spaces. */
internal class RecapCardLayout private constructor(val blocks: List<Block>) {
    data class Block(val role: String, val x: Float, val y: Float, val layout: StaticLayout) {
        val bottom: Float get() = y + layout.height
        val right: Float get() = x + layout.width
    }

    fun draw(canvas: Canvas) {
        blocks.forEach { block ->
            val save = canvas.save()
            canvas.translate(block.x, block.y)
            block.layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    companion object {
        private val WHITE = Color.rgb(255, 248, 253)
        private val PINK = Color.rgb(255, 181, 207)
        private val MUTED = Color.rgb(222, 209, 223)

        fun create(width: Int, height: Int, snapshot: RecapSnapshot, text: RenderText): RecapCardLayout {
            val scale = min(width, height) / 608f
            val landscape = width > height * 1.25f
            val margin = (if (landscape) 60f else 40f) * scale
            val available = width - margin * 2f
            val gap = (if (landscape) 68f else 30f) * scale
            val columnWidth = (available - gap) / 2f
            val localizer = RecapLocalizer(text)
            val displayedDistance = snapshot.totalDistanceKm * text.distanceScale
            val distanceFormat = NumberFormat.getNumberInstance(text.locale).apply {
                maximumFractionDigits = when {
                    displayedDistance < 1.0 -> 2
                    displayedDistance < 10.0 -> 1
                    else -> 0
                }
            }
            val distanceNumber = if (displayedDistance > 0.0 && displayedDistance < 0.01) {
                "<${distanceFormat.format(0.01)}"
            } else distanceFormat.format(displayedDistance)
            val distance = SpannableString("$distanceNumber ${text.distanceUnit}").apply {
                setSpan(RelativeSizeSpan(0.52f), distanceNumber.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val hasTransport = snapshot.dominantModeShare != null && snapshot.dominantMode != RecapTransportMode.UNKNOWN
            val secondValue = if (hasTransport) localizer.formatPercent(snapshot.dominantModeShare!!)
                else localizer.formatCount(snapshot.movementDays)
            val secondLabel = if (hasTransport) localizer.transportName(snapshot.dominantMode)
                else localizer.movementDaysLabel
            val top = if (landscape) 38f * scale else maxOf(45f * scale, height * 0.13f)

            fun build(sizeFactor: Float): List<Block> {
                val s = scale * sizeFactor
                val blocks = mutableListOf<Block>()
                fun add(role: String, value: CharSequence, x: Float, y: Float, maxWidth: Float,
                        fontSize: Float, maxLines: Int, color: Int = WHITE, bold: Boolean = false): Block {
                    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                        this.color = color
                        textSize = fontSize * s
                        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                    }
                    fun measure() = StaticLayout.Builder.obtain(value, 0, value.length, paint, maxWidth.toInt().coerceAtLeast(1))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .setBreakStrategy(if (role == "punchline") Layout.BREAK_STRATEGY_BALANCED else Layout.BREAK_STRATEGY_HIGH_QUALITY)
                        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                        .build()
                    var layout = measure()
                    // Keep every translated word. Shrink only when wrapping exceeds the measured space.
                    fun overflows() = layout.lineCount > maxLines ||
                        (0 until layout.lineCount).any { layout.getLineWidth(it) > layout.width + 0.5f }
                    while (overflows() && paint.textSize > 4f * scale) {
                        paint.textSize *= 0.97f
                        layout = measure()
                    }
                    return Block(role, x, y, layout).also(blocks::add)
                }
                val brand = add("brand", "TIMELINE VISUALIZER", margin, top, available, 40f, 1, PINK)
                val metricTop = brand.bottom + (if (landscape) 28f else 42f) * s
                val metricSize = if (landscape) 132f else 108f
                val first = add("distance", distance, margin, metricTop, columnWidth, metricSize, 1)
                val second = add("second-value", secondValue, margin + columnWidth + gap, metricTop, columnWidth, metricSize, 1)
                val labelTop = maxOf(first.bottom, second.bottom) + 8f * s
                val firstLabel = add("distance-label", localizer.totalDistanceLabel, margin, labelTop, columnWidth, 54f, 2)
                val otherLabel = add("second-label", secondLabel, margin + columnWidth + gap, labelTop, columnWidth, 54f, 2)
                val labelsBottom = maxOf(firstLabel.bottom, otherLabel.bottom)
                val analogyBottom = if (snapshot.analogyCount > 0.0) {
                    add("analogy", localizer.analogy(snapshot.analogyKind, snapshot.analogyCount), margin,
                        labelsBottom + 12f * s, available, 36f, 2, MUTED).bottom
                } else labelsBottom
                val punchline = add("punchline", localizer.personalityTitle(snapshot.personalityId), margin,
                    analogyBottom + (if (landscape) 28f else 46f) * s, available, 72f,
                    if (landscape) 2 else 3, PINK, bold = true)
                add("evidence", localizer.personalityEvidence(snapshot), margin,
                    punchline.bottom + 12f * s, available, 30f, if (landscape) 2 else 3, MUTED)
                return blocks
            }

            var factor = 1f
            var blocks = build(factor)
            val bottomLimit = height - 44f * scale
            while (blocks.last().bottom > bottomLimit && factor > 0.4f) {
                factor *= 0.97f
                blocks = build(factor)
            }
            return RecapCardLayout(blocks)
        }
    }
}
