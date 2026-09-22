package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAnimationTest {
    @Test
    fun selectedDurationIncludesTheEnding() {
        assertEquals(30f, TimelineAnimation.totalDurationSeconds(30), 0.001f)
        assertEquals(75f, TimelineAnimation.totalDurationSeconds(75), 0.001f)
    }

    @Test
    fun recapIsFullyVisibleForMoreThanThreeSecondsAtEverySupportedDuration() {
        for (duration in listOf(10, 15, 20, 30, 75, 300)) {
            val journeyEnd = TimelineAnimation.frameAtElapsedSeconds(duration - 4.5f, duration)
            val transitionEnd = TimelineAnimation.frameAtElapsedSeconds(duration - 3.5f, duration)
            val holdStart = TimelineAnimation.frameAtElapsedSeconds(duration - 3.25f, duration)
            val heldFrame = TimelineAnimation.frameAtElapsedSeconds(duration - 0.1f, duration)
            assertEquals(1f, journeyEnd.journeyProgress, 0.001f)
            assertEquals(0f, journeyEnd.outroProgress, 0.001f)
            assertEquals(1f, transitionEnd.outroProgress, 0.001f)
            assertEquals(0f, transitionEnd.recapProgress, 0.001f)
            assertEquals(1f, holdStart.recapProgress, 0.001f)
            assertEquals(1f, heldFrame.recapProgress, 0.001f)
        }
    }

    @Test
    fun shortApiDurationsStillReserveMovementAndReachTheRecap() {
        for (duration in 1..9) {
            assertTrue(TimelineAnimation.outroDurationSeconds(duration) < duration * 0.5f)
            assertEquals(1f, TimelineAnimation.frameAtOverallProgress(1f, duration).recapProgress, 0.001f)
        }
    }
}
