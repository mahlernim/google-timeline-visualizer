package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationRecommendationTest {
    @Test
    fun usesActualViewportTravelAndZoomWork() {
        val frames = listOf(
            frame(centerX = 0.10, spanY = 0.10),
            frame(centerX = 0.60, spanY = 0.10),
            frame(centerX = 0.60, spanY = 0.025),
        )

        assertEquals(15, DurationRecommendation.recommend(frames, aspect = 1.0, largeTransferCount = 0))
        assertEquals(15, DurationRecommendation.recommend(frames, aspect = 1.0, largeTransferCount = 2))
    }

    @Test
    fun wrapsDatelineMovementAndCapsRecommendationsAtOneMinute() {
        val wrapped = listOf(frame(centerX = 0.99), frame(centerX = 0.01))
        assertEquals(10, DurationRecommendation.recommend(wrapped, aspect = 1.0, largeTransferCount = 0))

        val extreme = (0..300).map { frame(centerX = it * 0.01, spanY = 0.001) }
        assertEquals(60, DurationRecommendation.recommend(extreme, aspect = 1.0, largeTransferCount = 0))
    }

    @Test
    fun recommendsBeyondTheDefaultForAJumpHeavyRoute() {
        val frames = (0..25).map { frame(centerX = it * 0.01, spanY = 0.01) }

        assertEquals(40, DurationRecommendation.recommend(frames, aspect = 1.0, largeTransferCount = 3))
    }

    private fun frame(centerX: Double, spanY: Double = 0.10) = TimelinePainter.CameraFrame(
        centerX = centerX,
        centerY = 0.50,
        spanY = spanY,
        zoom = 5,
    )
}
