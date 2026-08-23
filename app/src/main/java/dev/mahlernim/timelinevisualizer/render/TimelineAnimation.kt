package dev.mahlernim.timelinevisualizer.render

data class TimelineFrame(
    val journeyProgress: Float,
    val outroProgress: Float,
)

object TimelineAnimation {
    const val OUTRO_SECONDS = 1.5f
    const val OUTRO_TRANSITION_SECONDS = 1.0f

    fun totalDurationSeconds(selectedDurationSeconds: Int): Float =
        selectedDurationSeconds.coerceAtLeast(1).toFloat()

    fun frameAtOverallProgress(overallProgress: Float, selectedDurationSeconds: Int): TimelineFrame {
        val elapsedSeconds = overallProgress.coerceIn(0f, 1f) * totalDurationSeconds(selectedDurationSeconds)
        return frameAtElapsedSeconds(elapsedSeconds, selectedDurationSeconds)
    }

    fun frameAtElapsedSeconds(elapsedSeconds: Float, selectedDurationSeconds: Int): TimelineFrame {
        val totalSeconds = totalDurationSeconds(selectedDurationSeconds)
        val journeySeconds = (totalSeconds - OUTRO_SECONDS).coerceAtLeast(0f)
        if (elapsedSeconds <= journeySeconds) {
            val journeyProgress = if (journeySeconds == 0f) 1f else elapsedSeconds / journeySeconds
            return TimelineFrame(journeyProgress.coerceIn(0f, 1f), 0f)
        }
        val outroElapsed = elapsedSeconds - journeySeconds
        return TimelineFrame(
            journeyProgress = 1f,
            outroProgress = (outroElapsed / OUTRO_TRANSITION_SECONDS).coerceIn(0f, 1f),
        )
    }
}
