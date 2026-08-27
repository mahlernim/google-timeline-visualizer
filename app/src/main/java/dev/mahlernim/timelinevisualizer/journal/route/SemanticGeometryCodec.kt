package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Stable storage codec for semantic path geometry retained by Journal imports. */
object SemanticGeometryCodec {
    data class Geometry(
        val points: List<GeoPoint>,
        val continuityGroup: String? = null,
        val partIndex: Int? = null,
        val partCount: Int? = null,
    )

    fun encode(points: List<GeoPoint>): String = buildString {
        append('[')
        points.forEachIndexed { index, point ->
            if (index > 0) append(',')
            append("{\"instantEpochMillis\":")
            append(point.instant.toEpochMilli())
            append(",\"latitude\":")
            append(point.latitude)
            append(",\"longitude\":")
            append(point.longitude)
            append('}')
        }
        append(']')
    }

    fun decode(value: String?): List<GeoPoint> {
        return decodeGeometry(value).points
    }

    /**
     * Encodes one storage part of a single source semantic record.
     *
     * The group metadata is the only supported signal for joining separate database rows. Plain
     * array geometry from Journal Lab 2 and 3 deliberately has no implied cross-row continuity.
     */
    fun encodePart(
        points: List<GeoPoint>,
        continuityGroup: String,
        partIndex: Int,
        partCount: Int,
    ): String {
        require(continuityGroup.isNotBlank())
        require(partIndex >= 0 && partIndex < partCount)
        return buildString {
            append("{\"version\":")
            append(STRUCTURED_GEOMETRY_VERSION)
            append(",\"continuityGroup\":")
            appendJsonString(continuityGroup)
            append(",\"partIndex\":")
            append(partIndex)
            append(",\"partCount\":")
            append(partCount)
            append(",\"points\":")
            append(encode(points))
            append('}')
        }
    }

    fun decodeGeometry(value: String?): Geometry {
        if (value.isNullOrBlank()) return Geometry(emptyList())
        return runCatching {
            val trimmed = value.trim()
            val root = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            val values = if (root != null) {
                root.optJSONArray("points") ?: JSONArray()
            } else {
                JSONArray(trimmed)
            }
            Geometry(
                points = buildList<GeoPoint> {
                    for (index in 0 until values.length()) {
                        decodePoint(values.opt(index))?.let(::add)
                    }
                },
                continuityGroup = root?.optString("continuityGroup")?.takeIf { it.isNotBlank() },
                partIndex = root?.intOrNull("partIndex"),
                partCount = root?.intOrNull("partCount"),
            )
        }.getOrDefault(Geometry(emptyList()))
    }

    private fun decodePoint(value: Any?): GeoPoint? {
        val decoded = when (value) {
            is JSONObject -> {
                val epochMillis = value.longOrNull("instantEpochMillis", "epochMillis", "timestamp")
                val latitude = value.doubleOrNull("latitude", "lat")
                val longitude = value.doubleOrNull("longitude", "lng", "lon")
                Triple(epochMillis, latitude, longitude)
            }
            is JSONArray -> Triple(
                value.numberOrNull(0)?.toLong(),
                value.numberOrNull(1)?.toDouble(),
                value.numberOrNull(2)?.toDouble(),
            )
            else -> return null
        }
        val epochMillis = decoded.first ?: return null
        val latitude = decoded.second ?: return null
        val longitude = decoded.third ?: return null
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        return GeoPoint(Instant.ofEpochMilli(epochMillis), latitude, longitude)
    }

    private fun JSONObject.longOrNull(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
        if (!has(name) || isNull(name)) null else opt(name).asNumber()?.toLong()
    }

    private fun JSONObject.doubleOrNull(vararg names: String): Double? = names.firstNotNullOfOrNull { name ->
        if (!has(name) || isNull(name)) null else opt(name).asNumber()?.toDouble()
    }

    private fun JSONArray.numberOrNull(index: Int): Number? = opt(index).asNumber()

    private fun JSONObject.intOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else opt(name).asNumber()?.toInt()

    private fun Any?.asNumber(): Number? = when (this) {
        is Number -> this
        is String -> toDoubleOrNull()
        else -> null
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private const val STRUCTURED_GEOMETRY_VERSION = 2
}
