package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/** Compact, versioned storage for one bounded projection point chunk. */
object RouteProjectionPointCodec {
    const val FORMAT_VERSION = 1
    const val MAX_POINTS_PER_CHUNK = 1_024
    private const val BYTES_PER_POINT = Long.SIZE_BYTES + Double.SIZE_BYTES + Double.SIZE_BYTES

    fun encode(points: List<GeoPoint>): ByteArray {
        require(points.size <= MAX_POINTS_PER_CHUNK)
        val output = ByteBuffer.allocate(points.size * BYTES_PER_POINT).order(ByteOrder.BIG_ENDIAN)
        points.forEach { point ->
            output.putLong(point.instant.toEpochMilli())
            output.putDouble(point.latitude)
            output.putDouble(point.longitude)
        }
        return output.array()
    }

    fun decode(data: ByteArray, pointCount: Int, formatVersion: Int): List<GeoPoint> {
        require(formatVersion == FORMAT_VERSION) { "Unsupported route projection point format" }
        require(pointCount in 0..MAX_POINTS_PER_CHUNK)
        require(data.size == pointCount * BYTES_PER_POINT) { "Invalid route projection chunk size" }
        val input = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        return List(pointCount) {
            GeoPoint(
                instant = Instant.ofEpochMilli(input.long),
                latitude = input.double,
                longitude = input.double,
            )
        }
    }
}
