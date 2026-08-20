package dev.mahlernim.timelinevisualizer.data

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.MalformedJsonException
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class TimelineParser {
    fun parse(input: InputStream): Timeline = parseDocument(input, includeRawSignals = false).timeline

    fun parseWithRawSignals(input: InputStream): ParsedTimeline = parseDocument(input, includeRawSignals = true)

    private fun parseDocument(input: InputStream, includeRawSignals: Boolean): ParsedTimeline = try {
        parseSupportedTimeline(input, includeRawSignals)
    } catch (error: TimelineParseException) {
        throw error
    } catch (error: MalformedJsonException) {
        throw TimelineParseException(TimelineParseReason.MALFORMED_JSON, "Timeline JSON is malformed", error)
    } catch (error: EOFException) {
        throw TimelineParseException(TimelineParseReason.MALFORMED_JSON, "Timeline JSON is incomplete", error)
    } catch (error: IllegalStateException) {
        throw TimelineParseException(TimelineParseReason.MALFORMED_JSON, "Timeline JSON has an invalid structure", error)
    }

    private fun parseSupportedTimeline(input: InputStream, includeRawSignals: Boolean): ParsedTimeline {
        val points = mutableListOf<GeoPoint>()
        val rawSignalPoints = mutableListOf<RawSignalPoint>()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.strictness = Strictness.LENIENT
            val rootToken = try {
                reader.peek()
            } catch (_: EOFException) {
                throw TimelineParseException(
                    TimelineParseReason.EMPTY_EXPORT,
                    "Timeline JSON is empty",
                )
            }
            when (rootToken) {
                JsonToken.BEGIN_ARRAY -> readSegments(reader, points)
                JsonToken.BEGIN_OBJECT -> readRootObject(
                    reader,
                    points,
                    rawSignalPoints.takeIf { includeRawSignals },
                )
                else -> throw TimelineParseException(
                    TimelineParseReason.UNSUPPORTED_FORMAT,
                    "Timeline JSON must start with an object or array",
                )
            }
        }

        val normalized = normalize(points)

        if (normalized.isEmpty()) {
            throw TimelineParseException(
                TimelineParseReason.NO_USABLE_LOCATIONS,
                "No supported location points were found in this export",
            )
        }
        return ParsedTimeline(
            timeline = Timeline(normalized),
            rawSignals = normalizeRawSignals(rawSignalPoints),
        )
    }

    private fun normalizeRawSignals(points: MutableList<RawSignalPoint>): List<RawSignalPoint> {
        points.sortWith(compareBy { it.point.instant })
        return points.distinctBy { Triple(it.point.instant, it.point.latitude, it.point.longitude) }
    }

    private fun normalize(points: MutableList<GeoPoint>): List<GeoPoint> {
        var validCount = 0
        points.forEach { point ->
            if (point.latitude in -85.05112878..85.05112878 && point.longitude in -180.0..180.0) {
                points[validCount] = point
                validCount += 1
            }
        }
        if (validCount < points.size) points.subList(validCount, points.size).clear()

        // Stable millisecond ordering groups possible duplicates while retaining the first
        // occurrence from the source, matching distinctBy's existing behavior without a
        // document-sized HashSet of boxed Triple keys.
        points.sortWith(compareBy { it.instant.toEpochMilli() })
        var writeIndex = 0
        var groupStart = 0
        while (groupStart < points.size) {
            val epochMillis = points[groupStart].instant.toEpochMilli()
            var groupEnd = groupStart + 1
            while (groupEnd < points.size && points[groupEnd].instant.toEpochMilli() == epochMillis) {
                groupEnd += 1
            }
            val keptGroupStart = writeIndex
            for (readIndex in groupStart until groupEnd) {
                val candidate = points[readIndex]
                var duplicate = false
                for (keptIndex in keptGroupStart until writeIndex) {
                    val kept = points[keptIndex]
                    if (
                        kept.latitude.toBits() == candidate.latitude.toBits() &&
                        kept.longitude.toBits() == candidate.longitude.toBits()
                    ) {
                        duplicate = true
                        break
                    }
                }
                if (!duplicate) {
                    points[writeIndex] = candidate
                    writeIndex += 1
                }
            }
            groupStart = groupEnd
        }
        if (writeIndex < points.size) points.subList(writeIndex, points.size).clear()

        // The second stable sort restores exact Instant ordering while preserving source order
        // for points with identical timestamps.
        points.sortWith(compareBy(GeoPoint::instant))
        return points
    }

    private fun readRootObject(
        reader: JsonReader,
        points: MutableList<GeoPoint>,
        rawSignalPoints: MutableList<RawSignalPoint>?,
    ) {
        var foundSegments = false
        var foundRawSignals = false
        var foundLegacyFormat = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "semanticSegments" -> {
                    foundSegments = true
                    readSegments(reader, points)
                }
                "rawSignals" -> {
                    foundRawSignals = true
                    if (rawSignalPoints != null) readRawSignals(reader, rawSignalPoints) else reader.skipValue()
                }
                "timelineObjects", "locations" -> {
                    foundLegacyFormat = true
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!foundSegments) {
            val reason = when {
                foundLegacyFormat -> TimelineParseReason.LEGACY_FORMAT
                foundRawSignals -> TimelineParseReason.RAW_SIGNALS_ONLY
                else -> TimelineParseReason.UNSUPPORTED_FORMAT
            }
            throw TimelineParseException(reason, "This JSON does not contain semanticSegments")
        }
    }

    private fun readRawSignals(reader: JsonReader, output: MutableList<RawSignalPoint>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            var point: RawSignalPoint? = null
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "position") point = readRawPosition(reader)
                else reader.skipValue()
            }
            reader.endObject()
            point?.let(output::add)
        }
        reader.endArray()
    }

    private fun readRawPosition(reader: JsonReader): RawSignalPoint? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var coordinate: String? = null
        var timestamp: String? = null
        var accuracyMeters: Double? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "LatLng", "latLng" -> coordinate = reader.readStringOrNull()
                "timestamp" -> timestamp = reader.readStringOrNull()
                "accuracyMeters" -> accuracyMeters = reader.readDoubleOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters < 0.0) return null
        val instant = parseInstant(timestamp) ?: return null
        val parsedCoordinate = parseCoordinate(coordinate) ?: return null
        return RawSignalPoint(
            point = GeoPoint(instant, parsedCoordinate.first, parsedCoordinate.second),
            accuracyMeters = accuracyMeters,
        )
    }

    private fun readSegments(reader: JsonReader, points: MutableList<GeoPoint>) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) readSegment(reader, points) else reader.skipValue()
        }
        reader.endArray()
    }

    private fun readSegment(reader: JsonReader, output: MutableList<GeoPoint>) {
        var startTime: String? = null
        var endTime: String? = null
        var visitLocation: String? = null
        var activityStart: String? = null
        var activityEnd: String? = null
        val path = mutableListOf<TimedCoordinate>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "startTime" -> startTime = reader.readStringOrNull()
                "endTime" -> endTime = reader.readStringOrNull()
                "timelinePath" -> readTimelinePath(reader, path)
                "visit" -> visitLocation = readVisit(reader)
                "activity" -> {
                    val activity = readActivity(reader)
                    activityStart = activity.first
                    activityEnd = activity.second
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        path.forEach { timed ->
            val instant = parseInstant(timed.time)
                ?: parseOffsetInstant(startTime, endTime, timed.offsetMinutes)
            val coordinate = parseCoordinate(timed.coordinate)
            if (instant != null && coordinate != null) {
                output += GeoPoint(instant, coordinate.first, coordinate.second)
            }
        }

        addPoint(output, startTime, visitLocation)
        addPoint(output, startTime, activityStart)
        addPoint(output, endTime, activityEnd)
    }

    private fun readTimelinePath(reader: JsonReader, output: MutableList<TimedCoordinate>) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            var point: String? = null
            var time: String? = null
            var offsetMinutes: Long? = null
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "point" -> point = reader.readCoordinateValue()
                    "time" -> time = reader.readStringOrNull()
                    "durationMinutesOffsetFromStartTime" -> offsetMinutes = reader.readLongOrNull()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (point != null && (time != null || offsetMinutes != null)) {
                output += TimedCoordinate(point, time, offsetMinutes)
            }
        }
        reader.endArray()
    }

    private fun readVisit(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var location: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "topCandidate" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "placeLocation") location = reader.readCoordinateValue()
                    else reader.skipValue()
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return location
    }

    private fun readActivity(reader: JsonReader): Pair<String?, String?> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null to null
        }
        var start: String? = null
        var end: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start" -> start = reader.readCoordinateValue()
                "end" -> end = reader.readCoordinateValue()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return start to end
    }

    private fun JsonReader.readCoordinateValue(): String? = when (peek()) {
        JsonToken.STRING -> nextString()
        JsonToken.BEGIN_OBJECT -> {
            var coordinate: String? = null
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "latLng", "point" -> coordinate = readStringOrNull()
                    else -> skipValue()
                }
            }
            endObject()
            coordinate
        }
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.readStringOrNull(): String? = when (peek()) {
        JsonToken.STRING -> nextString()
        JsonToken.NULL -> {
            nextNull()
            null
        }
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.readLongOrNull(): Long? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString().toLongOrNull()
        JsonToken.NULL -> {
            nextNull()
            null
        }
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.readDoubleOrNull(): Double? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString().toDoubleOrNull()
        JsonToken.NULL -> {
            nextNull()
            null
        }
        else -> {
            skipValue()
            null
        }
    }

    private fun addPoint(output: MutableList<GeoPoint>, time: String?, rawCoordinate: String?) {
        val instant = parseInstant(time)
        val coordinate = parseCoordinate(rawCoordinate)
        if (instant != null && coordinate != null) {
            output += GeoPoint(instant, coordinate.first, coordinate.second)
        }
    }

    internal fun parseCoordinate(raw: String?): Pair<Double, Double>? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
            .removePrefix("geo:")
            .substringBefore('?')
            .replace("°", "")
            .replace(" ", "")
        val pieces = cleaned.split(',')
        if (pieces.size < 2) return null
        var latitude = pieces[0].toDoubleOrNull() ?: return null
        var longitude = pieces[1].toDoubleOrNull() ?: return null
        if (kotlin.math.abs(latitude) > 1_000_000 || kotlin.math.abs(longitude) > 1_000_000) {
            latitude /= 10_000_000.0
            longitude /= 10_000_000.0
        }
        if (latitude !in -85.05112878..85.05112878 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    internal fun parseInstant(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(raw).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun parseOffsetInstant(startRaw: String?, endRaw: String?, offsetMinutes: Long?): Instant? {
        if (offsetMinutes == null || offsetMinutes < 0) return null
        val start = parseInstant(startRaw) ?: return null
        val instant = runCatching {
            start.plusSeconds(Math.multiplyExact(offsetMinutes, 60L))
        }.getOrNull() ?: return null
        val end = parseInstant(endRaw)
        if (end != null && instant.isAfter(end.plusSeconds(60))) return null
        return instant
    }

    private data class TimedCoordinate(
        val coordinate: String,
        val time: String?,
        val offsetMinutes: Long?,
    )

}

data class ParsedTimeline(
    val timeline: Timeline,
    val rawSignals: List<RawSignalPoint>,
)

data class RawSignalPoint(
    val point: GeoPoint,
    val accuracyMeters: Double,
)

enum class TimelineParseReason {
    MALFORMED_JSON,
    EMPTY_EXPORT,
    LEGACY_FORMAT,
    RAW_SIGNALS_ONLY,
    UNSUPPORTED_FORMAT,
    NO_USABLE_LOCATIONS,
}

class TimelineParseException(
    val reason: TimelineParseReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
