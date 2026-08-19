package dev.mahlernim.timelinevisualizer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDurationTest {
    @Test
    fun acceptsInclusiveBoundariesAndWholeNumbers() {
        assertEquals(10, VideoDuration.parseCustom("10"))
        assertEquals(125, VideoDuration.parseCustom(" 125 "))
        assertEquals(300, VideoDuration.parseCustom("300"))
    }

    @Test
    fun rejectsMissingFractionalAndOutOfRangeValues() {
        listOf(null, "", "9", "301", "10.5", "five", "+60").forEach {
            assertNull("Expected invalid duration: $it", VideoDuration.parseCustom(it))
        }
    }
}
