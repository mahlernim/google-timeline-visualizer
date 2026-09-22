package dev.mahlernim.timelinevisualizer.render

data class TimelineFrame(
    val journeyProgress: Float,
    val outroProgress: Float,
    val recapProgress: Float = 0f,
)

object TimelineAnimation {
    // The selected duration includes the zoom, a brief fade, and a readable recap hold.
    const val OUTRO_SECONDS = 4.5f
    const val OUTRO_TRANSITION_SECONDS = 1.0f
    const val RECAP_FADE_SECONDS = 0.25f

    fun outroDurationSeconds(selectedDurationSeconds: Int): Float =
        minOf(OUTRO_SECONDS, totalDurationSeconds(selectedDurationSeconds) * 0.45f)

    fun outroFrame(outroElapsedSeconds: Float, endingSeconds: Float = OUTRO_SECONDS): TimelineFrame {
        val scale = (endingSeconds / OUTRO_SECONDS).coerceIn(0f, 1f)
        val zoomSeconds = (OUTRO_TRANSITION_SECONDS * scale).coerceAtLeast(0.001f)
        val fadeSeconds = (RECAP_FADE_SECONDS * scale).coerceAtLeast(0.001f)
        return TimelineFrame(
            journeyProgress = 1f,
            outroProgress = (outroElapsedSeconds / zoomSeconds).coerceIn(0f, 1f),
            recapProgress = ((outroElapsedSeconds - zoomSeconds) / fadeSeconds).coerceIn(0f, 1f),
        )
    }

    fun totalDurationSeconds(selectedDurationSeconds: Int): Float =
        selectedDurationSeconds.coerceAtLeast(1).toFloat()

    fun frameAtOverallProgress(overallProgress: Float, selectedDurationSeconds: Int): TimelineFrame {
        val elapsedSeconds = overallProgress.coerceIn(0f, 1f) * totalDurationSeconds(selectedDurationSeconds)
        return frameAtElapsedSeconds(elapsedSeconds, selectedDurationSeconds)
    }

    fun frameAtElapsedSeconds(elapsedSeconds: Float, selectedDurationSeconds: Int): TimelineFrame {
        val totalSeconds = totalDurationSeconds(selectedDurationSeconds)
        val endingSeconds = outroDurationSeconds(selectedDurationSeconds)
        val journeySeconds = totalSeconds - endingSeconds
        if (elapsedSeconds <= journeySeconds) {
            val journeyProgress = if (journeySeconds == 0f) 1f else elapsedSeconds / journeySeconds
            return TimelineFrame(journeyProgress.coerceIn(0f, 1f), 0f)
        }
        val outroElapsed = elapsedSeconds - journeySeconds
        return outroFrame(outroElapsed, endingSeconds)
    }
}
