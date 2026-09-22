package dev.mahlernim.timelinevisualizer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecapLayoutStressTest {
    @Test
    fun largeCountsAndLongLocalizedLabelsKeepPrimaryTextReadable() {
        for (tag in listOf("de", "vi")) {
            for ((width, height) in listOf(1080 to 608, 608 to 1080, 608 to 608)) {
                for (personality in RecapPersonalityId.entries) {
                    for (mode in listOf(RecapTransportMode.TRANSIT, RecapTransportMode.UNKNOWN)) {
                        val snapshot = RecapSnapshot(
                            totalDistanceKm = 100_000.0,
                            movementDays = 1_095,
                            dominantMode = mode,
                            dominantModeShare = if (mode == RecapTransportMode.TRANSIT) 0.75 else null,
                            personalityId = personality,
                            personalityValue = when (personality) {
                                RecapPersonalityId.STREAK_MODE_UNLOCKED -> 365.0
                                RecapPersonalityId.LONG_HAUL_ENERGY -> 2_500.0
                                RecapPersonalityId.MAIN_CHARACTER_ON_THE_MOVE -> 1_095.0
                                else -> 0.75
                            },
                            analogyKind = RecapAnalogyKind.EARTH,
                            analogyCount = 2.5,
                        )
                        val description = "$tag ${width}x$height $personality $mode"
                        val layout = RecapCardLayout.create(width, height, snapshot, RenderText.ENGLISH.copy(localeTag = tag))
                        val phoneScale = 320f / width
                        for (block in layout.blocks) {
                            assertTrue("$description ${block.role} left", block.x >= 0f)
                            assertTrue("$description ${block.role} top", block.y >= 0f)
                            assertTrue("$description ${block.role} right", block.right <= width)
                            assertTrue("$description ${block.role} bottom", block.bottom < height - 20f)
                            for (line in 0 until block.layout.lineCount) {
                                assertTrue("$description ${block.role} line width", block.layout.getLineWidth(line) <= block.layout.width + 1f)
                                assertEquals("$description ${block.role} truncated", 0, block.layout.getEllipsisCount(line))
                            }
                            val fontAtPhoneWidth = block.layout.paint.textSize * phoneScale
                            val minimum = when (block.role) {
                                "distance", "second-value" -> 24f
                                "punchline" -> 18f
                                "distance-label", "second-label" -> 12f
                                else -> null
                            }
                            if (minimum != null) {
                                assertTrue("$description ${block.role} is ${fontAtPhoneWidth}px at 320px viewing width, minimum ${minimum}px",
                                    fontAtPhoneWidth >= minimum)
                            }
                        }
                        for ((upper, lower) in listOf("brand" to "distance", "distance" to "distance-label",
                            "second-value" to "second-label", "distance-label" to "analogy",
                            "second-label" to "analogy", "analogy" to "punchline", "punchline" to "evidence")) {
                            assertTrue("$description $upper overlaps $lower",
                                layout.blocks.single { it.role == upper }.bottom <= layout.blocks.single { it.role == lower }.y)
                        }
                    }
                }
            }
        }
    }
}
