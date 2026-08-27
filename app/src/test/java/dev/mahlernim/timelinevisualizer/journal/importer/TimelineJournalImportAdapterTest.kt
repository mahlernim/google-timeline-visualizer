package dev.mahlernim.timelinevisualizer.journal.importer

import dev.mahlernim.timelinevisualizer.journal.JournalMatchClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class TimelineJournalImportAdapterTest {
    private val adapter = TimelineJournalImportAdapter()

    @Test
    fun convertsDetailedAndSemanticParserOutputWithoutInventingMetadata() {
        val source =
            """
            {
              "semanticSegments": [{
                "startTime": "2026-01-01T00:00:00Z",
                "endTime": "2026-01-01T01:00:00Z",
                "activity": {"start": "37.0,127.0", "end": "37.2,127.2"},
                "timelinePath": [{"point": "37.1,127.1", "time": "2026-01-01T00:30:00Z"}]
              }],
              "rawSignals": [{
                "position": {
                  "LatLng": "37.15,127.15",
                  "timestamp": "2026-01-01T00:45:00Z",
                  "accuracyMeters": 12.5
                }
              }]
            }
            """.trimIndent().toByteArray()

        val result = adapter.adapt(
            input = ByteArrayInputStream(source),
            sourceName = "timeline.json",
            importedAtEpochMillis = 1_800_000_000_000,
            matchClassification = JournalMatchClassification.LIKELY_SAME,
        )

        assertEquals(sha256(source), result.sourceHash)
        assertEquals(source.size.toLong(), result.sourceSize)
        assertEquals("timeline.json", result.sourceName)
        assertEquals(TimelineJournalImportAdapter.PARSER_VERSION, result.parserVersion)
        assertEquals(1, result.detailedObservations.size)
        assertEquals(12.5, result.detailedObservations.single().accuracyMeters!!, 0.0)
        assertNull(result.detailedObservations.single().altitudeMeters)
        assertNull(result.detailedObservations.single().speedMetersPerSecond)
        assertNull(result.detailedObservations.single().provider)

        val semantic = result.semanticSegments.single()
        assertEquals("ACTIVITY", semantic.kind)
        assertNull(semantic.activityType)
        assertNull(semantic.placeId)
        assertEquals(true, semantic.geometryJson?.contains("\"continuityGroup\":\"source:0\"") == true)
        assertEquals(true, semantic.geometryJson?.contains("\"partCount\":1") == true)
        assertEquals(true, semantic.geometryJson?.contains("\"instantEpochMillis\":1767225600000") == true)
        assertEquals(true, semantic.geometryJson?.contains("\"instantEpochMillis\":1767229200000") == true)
    }

    @Test
    fun hashesTrailingBytesAndLeavesCallerOwnedStreamOpen() {
        val source = (
            """{"rawSignals":[{"position":{"LatLng":"37.1,127.1","timestamp":"2026-02-01T00:00:00Z","accuracyMeters":10}}]}""" +
                "   \n"
            ).toByteArray()
        val input = CloseTrackingInputStream(source)

        val result = adapter.adapt(
            input = input,
            sourceName = null,
            importedAtEpochMillis = 1,
            matchClassification = JournalMatchClassification.NEW_JOURNAL,
        )

        assertEquals(sha256(source), result.sourceHash)
        assertEquals(source.size.toLong(), result.sourceSize)
        assertEquals(1, result.detailedObservations.size)
        assertEquals(emptyList<Any>(), result.semanticSegments)
        assertFalse(input.closed)
    }

    @Test
    fun reportsThrottledByteProgressFromZeroThroughTheCompleteDocument() {
        val filler = " ".repeat(600_000)
        val source = (
            """{"rawSignals":[{"position":{"LatLng":"37.1,127.1","timestamp":"2026-02-01T00:00:00Z","accuracyMeters":10}}]}""" +
                filler
            ).toByteArray()
        val reports = mutableListOf<Long>()

        adapter.adapt(
            input = ByteArrayInputStream(source),
            sourceName = "large.json",
            importedAtEpochMillis = 1,
            matchClassification = JournalMatchClassification.NEW_JOURNAL,
            onBytesRead = reports::add,
        )

        assertEquals(0L, reports.first())
        assertEquals(source.size.toLong(), reports.last())
        assertTrue(reports.zipWithNext().all { (before, after) -> after > before })
        assertTrue(reports.size < 20)
    }

    @Test
    fun chunksLargeSemanticGeometryAtTheStorageBoundary() {
        val pointCount = TimelineJournalImportAdapter.MAX_SEMANTIC_POINTS_PER_SEGMENT + 1
        val source = buildString {
            append("[{\"startTime\":\"2020-01-01T00:00:00Z\",\"timelinePath\":[")
            repeat(pointCount) { index ->
                if (index > 0) append(',')
                append("{\"point\":\"37.0,127.0\",\"durationMinutesOffsetFromStartTime\":")
                append(index)
                append('}')
            }
            append("]}]")
        }.toByteArray()

        val result = adapter.adapt(
            input = ByteArrayInputStream(source),
            sourceName = "large.json",
            importedAtEpochMillis = 1,
            matchClassification = JournalMatchClassification.NEW_JOURNAL,
        )

        assertEquals(2, result.semanticSegments.size)
        assertEquals(0, result.detailedObservations.size)
        assertEquals(1577836800000, result.semanticSegments.first().startEpochMillis)
        assertEquals(1578436800000, result.semanticSegments.last().endEpochMillis)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
