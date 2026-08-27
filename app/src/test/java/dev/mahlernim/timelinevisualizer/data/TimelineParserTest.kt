package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.Journey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant

class TimelineParserTest {
    private val parser = TimelineParser()

    @Test
    fun parsesObjectRootWithTimelinePathAndVisit() {
        val timeline = parse(
            """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-01-03T09:00:00Z",
                  "timelinePath": [
                    {"point": "37.5000°, 127.0000°", "time": "2025-01-03T09:01:00Z"},
                    {"point": "37.6000°, 127.1000°", "time": "2025-01-03T10:01:00Z"}
                  ]
                },
                {
                  "startTime": "2025-02-01T12:00:00+09:00",
                  "visit": {"topCandidate": {"placeLocation": {"latLng": "37.7000°, 127.2000°"}}}
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, timeline.points.size)
        assertEquals(listOf(2025), timeline.years)
        assertEquals(37.7, timeline.points.last().latitude, 0.00001)
    }

    @Test
    fun parsesIosArrayWithActivitiesAndStringVisit() {
        val timeline = parse(
            """
            [
              {
                "startTime": "2024-05-01T01:00:00Z",
                "endTime": "2024-05-01T02:00:00Z",
                "activity": {
                  "start": "geo:35.1000,129.1000",
                  "end": {"latLng": "geo:35.2000,129.2000"}
                }
              },
              {
                "startTime": "2024-05-01T03:00:00Z",
                "visit": {"topCandidate": {"placeLocation": "geo:35.3000,129.3000"}}
              }
            ]
            """.trimIndent(),
        )

        assertEquals(3, timeline.points.size)
        assertEquals(35.1, timeline.points.first().latitude, 0.00001)
        assertEquals(35.3, timeline.points.last().latitude, 0.00001)
    }

    @Test
    fun suppressesStandalonePathPointsCoveredBySemanticSegments() {
        val timeline = parse(
            """
            [
              {
                "startTime": "2026-05-11T08:00:00Z",
                "endTime": "2026-05-11T22:00:00Z",
                "activity": {"start": "10,10", "end": "20,20"}
              },
              {
                "startTime": "2026-05-12T08:00:00Z",
                "endTime": "2026-05-12T22:00:00Z",
                "activity": {"start": "20,20", "end": "10,10"}
              },
              {
                "startTime": "2026-05-11T08:00:00Z",
                "endTime": "2026-05-12T23:00:00Z",
                "timelinePath": [
                  {"point": "20,20", "time": "2026-05-11T13:00:00Z"},
                  {"point": "10,10", "time": "2026-05-11T17:00:00Z"},
                  {"point": "10,10", "time": "2026-05-12T13:00:00Z"},
                  {"point": "20,20", "time": "2026-05-12T17:00:00Z"},
                  {"point": "30,30", "time": "2026-05-12T22:30:00Z"}
                ]
              }
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Triple("2026-05-11T08:00:00Z", 10.0, 10.0),
                Triple("2026-05-11T22:00:00Z", 20.0, 20.0),
                Triple("2026-05-12T08:00:00Z", 20.0, 20.0),
                Triple("2026-05-12T22:00:00Z", 10.0, 10.0),
                Triple("2026-05-12T22:30:00Z", 30.0, 30.0),
            ),
            timeline.points.map { Triple(it.instant.toString(), it.latitude, it.longitude) },
        )
    }

    @Test
    fun keepsPathDetailInsideTheSameSemanticSegment() {
        val timeline = parse(
            """
            [{
              "startTime": "2026-01-01T00:00:00Z",
              "endTime": "2026-01-01T02:00:00Z",
              "activity": {"start": "10,10", "end": "20,20"},
              "timelinePath": [
                {"point": "15,15", "time": "2026-01-01T01:00:00Z"}
              ]
            }]
            """.trimIndent(),
        )

        assertEquals(
            listOf(10.0, 15.0, 20.0),
            timeline.points.map { it.latitude },
        )
    }

    @Test
    fun acceptsE7CoordinatesAndRemovesDuplicates() {
        val timeline = parse(
            """
            [{
              "startTime": "2026-01-01T00:00:00Z",
              "visit": {"topCandidate": {"placeLocation": "375000000,1270000000"}},
              "timelinePath": [
                {"point": "375000000,1270000000", "time": "2026-01-01T00:00:00Z"}
              ]
            }]
            """.trimIndent(),
        )

        assertEquals(1, timeline.points.size)
        assertEquals(37.5, timeline.points.single().latitude, 0.00001)
        assertEquals(127.0, timeline.points.single().longitude, 0.00001)
        assertEquals(null, parser.parseCoordinate("geo:91,127"))
    }

    @Test
    fun normalizationPreservesExistingOrderingAndDeduplicationSemantics() {
        val timeline = parse(
            """
            [{
              "timelinePath": [
                {"point": "37.2,127.2", "time": "2026-01-01T00:00:02Z"},
                {"point": "37.1,127.1", "time": "2026-01-01T00:00:01.000500Z"},
                {"point": "37.3,127.3", "time": "2026-01-01T00:00:01.000100Z"},
                {"point": "37.2,127.2", "time": "2026-01-01T00:00:02Z"},
                {"point": "37.1,127.1", "time": "2026-01-01T00:00:01.000700Z"},
                {"point": "37.4,127.4", "time": "2026-01-01T00:00:01.000500Z"}
              ]
            }]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Triple("2026-01-01T00:00:01.000100Z", 37.3, 127.3),
                Triple("2026-01-01T00:00:01.000500Z", 37.1, 127.1),
                Triple("2026-01-01T00:00:01.000500Z", 37.4, 127.4),
                Triple("2026-01-01T00:00:02Z", 37.2, 127.2),
            ),
            timeline.points.map { Triple(it.instant.toString(), it.latitude, it.longitude) },
        )
    }

    @Test
    fun parsesStringAndNumericOffsetsFromSegmentStart() {
        val timeline = parse(
            """
            [{
              "startTime": "2026-01-01T00:00:00Z",
              "endTime": "2026-01-01T02:00:00Z",
              "timelinePath": [
                {"point": "37.0,127.0", "durationMinutesOffsetFromStartTime": "15"},
                {"point": "37.1,127.1", "durationMinutesOffsetFromStartTime": 60}
              ]
            }]
            """.trimIndent(),
        )

        assertEquals(
            listOf(Instant.parse("2026-01-01T00:15:00Z"), Instant.parse("2026-01-01T01:00:00Z")),
            timeline.points.map { it.instant },
        )
    }

    @Test
    fun validAbsolutePathTimeTakesPriorityOverOffset() {
        val timeline = parse(
            """
            [{
              "startTime": "2026-01-01T00:00:00Z",
              "endTime": "2026-01-01T02:00:00Z",
              "timelinePath": [{
                "point": "37.0,127.0",
                "time": "2026-01-01T01:30:00Z",
                "durationMinutesOffsetFromStartTime": "5"
              }]
            }]
            """.trimIndent(),
        )

        assertEquals(Instant.parse("2026-01-01T01:30:00Z"), timeline.points.single().instant)
    }

    @Test
    fun ignoresInvalidAndOutOfRangeOffsets() {
        val timeline = parse(
            """
            [{
              "startTime": "2026-01-01T00:00:00Z",
              "endTime": "2026-01-01T01:00:00Z",
              "timelinePath": [
                {"point": "37.0,127.0", "durationMinutesOffsetFromStartTime": "-1"},
                {"point": "37.1,127.1", "durationMinutesOffsetFromStartTime": "unknown"},
                {"point": "37.2,127.2", "durationMinutesOffsetFromStartTime": "120"}
              ],
              "visit": {"topCandidate": {"placeLocation": "37.3,127.3"}}
            }]
            """.trimIndent(),
        )

        assertEquals(1, timeline.points.size)
        assertEquals(37.3, timeline.points.single().latitude, 0.00001)
    }

    @Test
    fun classifiesUnsupportedTimelineFormats() {
        assertEquals(
            TimelineParseReason.LEGACY_FORMAT,
            parseFailure("""{"timelineObjects": []}""").reason,
        )
        assertEquals(
            TimelineParseReason.RAW_SIGNALS_ONLY,
            parseFailure("""{"rawSignals": []}""").reason,
        )
        assertEquals(
            TimelineParseReason.NO_USABLE_LOCATIONS,
            parseFailure("""{"semanticSegments": []}""").reason,
        )
        assertEquals(TimelineParseReason.EMPTY_EXPORT, parseFailure("").reason)
        assertEquals(TimelineParseReason.EMPTY_EXPORT, parseFailure("  \n\t").reason)
        assertEquals(
            TimelineParseReason.MALFORMED_JSON,
            parseFailure("""{"semanticSegments": [""").reason,
        )
    }

    @Test
    fun exposesRawPositionsWithoutChangingSemanticTimeline() {
        val parsed = parser.parseWithRawSignals(
            ByteArrayInputStream(
                """
                {
                  "semanticSegments": [{
                    "startTime": "2026-01-01T00:00:00Z",
                    "visit": {"topCandidate": {"placeLocation": "37.0,127.0"}}
                  }],
                  "rawSignals": [
                    {"position": {"LatLng": "37.1,127.1", "timestamp": "2026-02-01T00:00:00Z", "accuracyMeters": 20}},
                    {"position": {"latLng": "37.2,127.2", "timestamp": "2026-02-01T00:01:00Z", "accuracyMeters": "101"}},
                    {"activityRecord": {"timestamp": "2026-02-01T00:02:00Z"}}
                  ]
                }
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(1, parsed.timeline?.points?.size)
        assertEquals(37.0, parsed.timeline!!.points.single().latitude, 0.00001)
        assertEquals(2, parsed.rawSignals.size)
        assertEquals(37.1, parsed.rawSignals.first().point.latitude, 0.00001)
        assertEquals(20.0, parsed.rawSignals.first().accuracyMeters, 0.00001)
    }

    @Test
    fun returnsUsableRawOnlyExportForExplicitFallback() {
        val parsed = parser.parseWithRawSignals(
            ByteArrayInputStream(
                """
                {"rawSignals": [
                  {"position": {"LatLng": "37.1,127.1", "timestamp": "2026-02-01T00:00:00Z", "accuracyMeters": 20}},
                  {"position": {"LatLng": "37.1,127.1", "timestamp": "2026-02-01T00:00:00Z", "accuracyMeters": 10}},
                  {"wifiScan": {"timestamp": "2026-02-01T00:01:00Z"}}
                ]}
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(null, parsed.timeline)
        assertEquals(1, parsed.rawSignals.size)
        assertEquals(10.0, parsed.rawSignals.single().accuracyMeters, 0.00001)
        assertEquals(
            TimelineParseReason.RAW_SIGNALS_ONLY,
            parseFailure(
                """{"rawSignals":[{"position":{"LatLng":"37.1,127.1","timestamp":"2026-02-01T00:00:00Z","accuracyMeters":10}}]}""",
            ).reason,
        )
    }

    @Test
    fun rawNormalizationKeepsFirstCoordinateOrderAndBestAccuracyWithinAMillisecond() {
        val parsed = parser.parseWithRawSignals(
            ByteArrayInputStream(
                """
                {"rawSignals": [
                  {"position": {"LatLng": "37.1,127.1", "timestamp": "2026-02-01T00:00:00.000100Z", "accuracyMeters": 20}},
                  {"position": {"LatLng": "37.2,127.2", "timestamp": "2026-02-01T00:00:00.000200Z", "accuracyMeters": 30}},
                  {"position": {"LatLng": "37.1,127.1", "timestamp": "2026-02-01T00:00:00.000300Z", "accuracyMeters": 10}},
                  {"position": {"LatLng": "37.1,127.1", "timestamp": "2026-02-01T00:00:01Z", "accuracyMeters": 5}}
                ]}
                """.trimIndent().toByteArray(),
            ),
        )

        assertEquals(
            listOf(37.1, 37.2, 37.1),
            parsed.rawSignals.map { it.point.latitude },
        )
        assertEquals(
            listOf(10.0, 30.0, 5.0),
            parsed.rawSignals.map { it.accuracyMeters },
        )
        assertEquals(
            Instant.parse("2026-02-01T00:00:00.000300Z"),
            parsed.rawSignals.first().point.instant,
        )
    }

    @Test(expected = TimelineParseException::class)
    fun rejectsUnsupportedJson() {
        parse("""{"locations": []}""")
    }

    @Test
    fun journeyUsesDistanceBasedProgress() {
        val timeline = parse(
            """
            [{
              "startTime": "2025-01-01T00:00:00Z",
              "timelinePath": [
                {"point": "37.0,127.0", "time": "2025-01-01T00:00:00Z"},
                {"point": "37.0,127.1", "time": "2025-01-01T01:00:00Z"},
                {"point": "37.0,128.0", "time": "2025-01-01T02:00:00Z"}
              ]
            }]
            """.trimIndent(),
        )
        val journey: Journey = timeline.forYear(2025)

        assertTrue(journey.totalDistanceKm > 80)
        assertEquals(2, journey.pointIndexAt(0.5f))
    }

    @Test
    fun parsesLargeTimelineWithoutLoadingTheDocumentAsOneString() {
        val source = File.createTempFile("large-timeline-", ".json")
        try {
            writeLargeTimeline(source, 45L * 1024 * 1024)

            val timeline = source.inputStream().buffered().use(parser::parse)

            assertTrue(source.length() >= 45L * 1024 * 1024)
            assertTrue(timeline.points.size > 300_000)
            assertTrue(timeline.points.asSequence().zipWithNext().all { (before, after) -> before.instant <= after.instant })
        } finally {
            source.delete()
        }
    }

    @Test
    fun reconcilesThousandsOfOverlappingSemanticAndPathSegments() {
        val segmentCount = 3_000
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val json = buildString {
            append('[')
            repeat(segmentCount) { index ->
                if (index > 0) append(',')
                val start = base.plusSeconds(index * 120L)
                val end = start.plusSeconds(60)
                append(
                    "{\"startTime\":\"$start\",\"endTime\":\"$end\"," +
                        "\"activity\":{\"start\":\"10,10\",\"end\":\"20,20\"}}",
                )
            }
            repeat(segmentCount) { index ->
                append(',')
                val start = base.plusSeconds(index * 120L)
                val end = start.plusSeconds(60)
                val middle = start.plusSeconds(30)
                append(
                    "{\"startTime\":\"$start\",\"endTime\":\"$end\"," +
                        "\"timelinePath\":[{\"point\":\"30,30\",\"time\":\"$middle\"}]}",
                )
            }
            append(']')
        }

        val timeline = parse(json)

        assertEquals(segmentCount * 2, timeline.points.size)
        assertTrue(timeline.points.none { it.latitude == 30.0 })
    }

    private fun parse(json: String) = parser.parse(ByteArrayInputStream(json.toByteArray()))

    private fun parseFailure(json: String): TimelineParseException = try {
        parse(json)
        throw AssertionError("Expected TimelineParseException")
    } catch (error: TimelineParseException) {
        error
    }

    private fun writeLargeTimeline(file: File, minimumBytes: Long) {
        file.bufferedWriter().use { writer ->
            writer.write("{\"semanticSegments\":[")
            var firstSegment = true
            var pointIndex = 0
            var segmentCount = 0
            while (true) {
                if (!firstSegment) writer.write(','.code)
                firstSegment = false
                writer.write("{\"startTime\":\"2020-01-01T00:00:00Z\",\"timelinePath\":[")
                repeat(20) { offset ->
                    if (offset > 0) writer.write(','.code)
                    val minute = pointIndex
                    val latitude = 35.0 + (pointIndex % 100_000) / 1_000_000.0
                    val longitude = 126.0 + (pointIndex % 100_000) / 1_000_000.0
                    writer.write(
                        "{\"point\":\"$latitude,$longitude\"," +
                            "\"durationMinutesOffsetFromStartTime\":$minute}",
                    )
                    pointIndex += 1
                }
                writer.write("]}")
                segmentCount += 1
                if (segmentCount % 100 == 0) {
                    writer.flush()
                    if (file.length() >= minimumBytes) break
                }
            }
            writer.write("]}")
        }
    }
}
