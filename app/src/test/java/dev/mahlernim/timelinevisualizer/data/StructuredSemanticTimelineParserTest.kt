package dev.mahlernim.timelinevisualizer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class StructuredSemanticTimelineParserTest {
    private val parser = TimelineParser()

    @Test
    fun directArrayRetainsKindsMetadataBoundariesAndOrderedGeometry() {
        val parsed = parseFixture("timeline/structured-direct-array.json")

        assertEquals(2, parsed.semanticSegments.size)
        val activity = parsed.semanticSegments[0]
        assertEquals(0, activity.sourceOrdinal)
        assertEquals(StructuredSemanticSegmentKind.ACTIVITY, activity.kind)
        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), activity.start)
        assertEquals(Instant.parse("2026-04-01T01:00:00Z"), activity.end)
        assertEquals("IN_PASSENGER_VEHICLE", activity.activityType)
        assertNull(activity.placeId)
        assertEquals(listOf(37.5, 37.6, 37.7), activity.geometry.map { it.latitude })

        val visit = parsed.semanticSegments[1]
        assertEquals(1, visit.sourceOrdinal)
        assertEquals(StructuredSemanticSegmentKind.VISIT, visit.kind)
        assertEquals("synthetic-place-direct", visit.placeId)
        assertNull(visit.activityType)
        assertEquals(listOf(37.8), visit.geometry.map { it.latitude })
    }

    @Test
    fun objectRootRetainsSourceOrdinalsPathBoundaryAndMixedSegment() {
        val parsed = parseFixture("timeline/structured-object-root.json")

        assertEquals(2, parsed.semanticSegments.size)
        val path = parsed.semanticSegments[0]
        assertEquals(1, path.sourceOrdinal)
        assertEquals(StructuredSemanticSegmentKind.PATH, path.kind)
        assertEquals(listOf(35.0, 35.1), path.geometry.map { it.latitude })

        val mixed = parsed.semanticSegments[1]
        assertEquals(2, mixed.sourceOrdinal)
        assertEquals(StructuredSemanticSegmentKind.ACTIVITY_AND_VISIT, mixed.kind)
        assertEquals("WALKING", mixed.activityType)
        assertEquals("synthetic-place-object", mixed.placeId)
        assertEquals(listOf(35.3, 35.2, 35.4), mixed.geometry.map { it.latitude })
    }

    @Test
    fun structuredOutputDoesNotChangeFlattenedTimeline() {
        val bytes = fixtureBytes("timeline/structured-direct-array.json")

        val legacy = parser.parse(ByteArrayInputStream(bytes))
        val structured = parser.parseWithRawSignals(ByteArrayInputStream(bytes))

        assertEquals(legacy, structured.timeline)
        assertEquals(listOf(37.5, 37.6, 37.7, 37.8), legacy.points.map { it.latitude })
    }

    @Test
    fun standalonePathRetainsItsOwnRecordWhenFlatteningSuppressesOverlap() {
        val parsed = parser.parseWithRawSignals(
            ByteArrayInputStream(
                """
                [
                  {
                    "startTime": "2026-06-01T00:00:00Z",
                    "endTime": "2026-06-01T02:00:00Z",
                    "activity": {"start": "10,10", "end": "20,20"}
                  },
                  {
                    "startTime": "2026-06-01T00:00:00Z",
                    "endTime": "2026-06-01T02:00:00Z",
                    "timelinePath": [{"point": "15,15", "time": "2026-06-01T01:00:00Z"}]
                  }
                ]
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(listOf(10.0, 20.0), parsed.timeline!!.points.map { it.latitude })
        assertEquals(StructuredSemanticSegmentKind.PATH, parsed.semanticSegments[1].kind)
        assertEquals(listOf(15.0), parsed.semanticSegments[1].geometry.map { it.latitude })
    }

    private fun parseFixture(path: String): ParsedTimeline = parser.parseWithRawSignals(
        ByteArrayInputStream(fixtureBytes(path)),
    )

    private fun fixtureBytes(path: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
        "Missing fixture $path"
    }.use { it.readBytes() }
}
