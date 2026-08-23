package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineAnimationTest {
    @Test
    fun selectedDurationIncludesTheOneAndAHalfSecondEnding() {
        assertEquals(30f, TimelineAnimation.totalDurationSeconds(30), 0.001f)
        assertEquals(75f, TimelineAnimation.totalDurationSeconds(75), 0.001f)
    }

    @Test
    fun endingZoomCompletesBeforeTheFinalHalfSecondHold() {
        val journeyEnd = TimelineAnimation.frameAtElapsedSeconds(28.5f, 30)
        val transitionEnd = TimelineAnimation.frameAtElapsedSeconds(29.5f, 30)
        val heldFrame = TimelineAnimation.frameAtElapsedSeconds(29.9f, 30)

        assertEquals(1f, journeyEnd.journeyProgress, 0.001f)
        assertEquals(0f, journeyEnd.outroProgress, 0.001f)
        assertEquals(1f, transitionEnd.journeyProgress, 0.001f)
        assertEquals(1f, transitionEnd.outroProgress, 0.001f)
        assertEquals(1f, heldFrame.outroProgress, 0.001f)
    }
}
