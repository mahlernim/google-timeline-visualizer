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
    fun parse(input: InputStream): Timeline = try {
        parseSupportedTimeline(input)
    } catch (error: TimelineParseException) {
        throw error
    } catch (error: MalformedJsonException) {
        throw TimelineParseException(TimelineParseReason.MALFORMED_JSON, "Timeline JSON is malformed", error)
    } catch (error: EOFException) {
        throw TimelineParseException(TimelineParseReason.MALFORMED_JSON, "Timeline JSON is incomplete", error)
    } catch (error: IllegalStateException) {
        throw TimelineParseException(TimelineParseReason.MALFORMED_JSON, "Timeline JSON has an invalid structure", error)
    }

    private fun parseSupportedTimeline(input: InputStream): Timeline {
        val points = mutableListOf<GeoPoint>()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.strictness = Strictness.LENIENT
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> readSegments(reader, points)
                JsonToken.BEGIN_OBJECT -> readRootObject(reader, points)
                else -> throw TimelineParseException(
                    TimelineParseReason.UNSUPPORTED_FORMAT,
                    "Timeline JSON must start with an object or array",
                )
            }
        }

        val normalized = points
            .asSequence()
            .filter { it.latitude in -85.05112878..85.05112878 && it.longitude in -180.0..180.0 }
            .distinctBy { Triple(it.instant.toEpochMilli(), it.latitude, it.longitude) }
            .sortedBy { it.instant }
            .toList()

        if (normalized.isEmpty()) {
            throw TimelineParseException(
                TimelineParseReason.NO_USABLE_LOCATIONS,
                "No supported location points were found in this export",
            )
        }
        return Timeline(normalized)
    }

    private fun readRootObject(reader: JsonReader, points: MutableList<GeoPoint>) {
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
                    reader.skipValue()
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

enum class TimelineParseReason {
    MALFORMED_JSON,
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
