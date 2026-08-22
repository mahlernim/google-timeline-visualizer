package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import dev.mahlernim.timelinevisualizer.render.RenderText
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoQuality
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant

data class VideoExportRequest(
    val outputUri: String,
    val journey: Journey,
    val title: String,
    val durationSeconds: Int,
    val renderText: RenderText = RenderText.ENGLISH,
    val cameraSettings: CameraSettings = CameraSettings.DEFAULT,
) {
    val period: TimelinePeriod get() = journey.period
}

class VideoExportRequestStore(context: Context) {
    private val requestFile = File(context.filesDir, REQUEST_FILE)
    private val temporaryFile = File(context.filesDir, TEMPORARY_FILE)

    @Synchronized
    fun save(request: VideoExportRequest) {
        DataOutputStream(temporaryFile.outputStream().buffered()).use { output ->
            output.writeInt(CURRENT_FILE_VERSION)
            output.writeUTF(request.outputUri)
            output.writeUTF(request.title)
            output.writeInt(request.durationSeconds)
            output.writeInt(request.period.startYear)
            output.writeInt(request.period.startMonth)
            output.writeInt(request.period.endYear)
            output.writeInt(request.period.endMonth)
            output.writeUTF(request.renderText.localeTag)
            output.writeUTF(request.renderText.fallbackTitle)
            output.writeUTF(request.renderText.datePattern)
            output.writeUTF(request.renderText.distanceUnit)
            output.writeUTF(request.renderText.attribution)
            output.writeDouble(request.renderText.distanceScale)
            output.writeUTF(request.cameraSettings.cameraMovement.name)
            output.writeUTF(request.cameraSettings.longTripCompression.name)
            output.writeUTF(request.cameraSettings.videoQuality.name)
            output.writeUTF(request.cameraSettings.tripDetection.name)
            output.writeUTF(request.cameraSettings.localFraming.name)
            output.writeInt(request.journey.points.size)
            request.journey.points.forEach { point ->
                output.writeLong(point.instant.toEpochMilli())
                output.writeDouble(point.latitude)
                output.writeDouble(point.longitude)
            }
        }
        if (!temporaryFile.renameTo(requestFile)) {
            temporaryFile.copyTo(requestFile, overwrite = true)
            check(temporaryFile.delete()) { "Could not finish saving the video request" }
        }
    }

    @Synchronized
    fun load(): VideoExportRequest? {
        if (!requestFile.isFile) return null
        return runCatching {
            DataInputStream(requestFile.inputStream().buffered()).use { input ->
                val version = input.readInt()
                check(version in 1..CURRENT_FILE_VERSION) { "Unsupported video request" }
                val outputUri = input.readUTF()
                val title = input.readUTF()
                val durationSeconds = input.readInt()
                val startYear = input.readInt()
                val startMonth = input.readInt()
                val endYear: Int
                val endMonth: Int
                val renderText: RenderText
                val cameraSettings: CameraSettings
                if (version == 1) {
                    endYear = startYear
                    endMonth = input.readInt()
                    renderText = RenderText.ENGLISH
                    cameraSettings = CameraSettings.DEFAULT.copy(localFraming = LocalFraming.OFF)
                } else {
                    endYear = input.readInt()
                    endMonth = input.readInt()
                    val localeTag = input.readUTF()
                    val fallbackTitle = input.readUTF()
                    val datePattern = input.readUTF()
                    val distanceUnit = input.readUTF()
                    val attribution = input.readUTF()
                    val distanceScale = if (version >= 5) input.readDouble() else 1.0
                    renderText = RenderText(
                        localeTag = localeTag,
                        fallbackTitle = fallbackTitle,
                        datePattern = datePattern,
                        distanceUnit = distanceUnit,
                        attribution = attribution,
                        distanceScale = distanceScale,
                    )
                    cameraSettings = if (version >= 4) {
                        val movement = enumOrDefault(input.readUTF(), CameraMovement.STEADY)
                        val compression = enumOrDefault(input.readUTF(), LongTripCompression.BALANCED)
                        val quality = enumOrDefault(input.readUTF(), VideoQuality.STANDARD)
                        if (version in 6..7) input.readDouble()
                        val legacyFramingEnabled = if (version == 7) input.readBoolean() else false
                        val tripDetection = if (version >= 7) {
                            enumOrDefault(input.readUTF(), TripDetection.BALANCED)
                        } else {
                            TripDetection.BALANCED
                        }
                        val storedLocalFraming = if (version >= 7) {
                            enumOrDefault(input.readUTF(), LocalFraming.BALANCED)
                        } else {
                            LocalFraming.OFF
                        }
                        CameraSettings(
                            cameraMovement = movement,
                            longTripCompression = compression,
                            videoQuality = quality,
                            tripDetection = tripDetection,
                            localFraming = if (version >= 8 || legacyFramingEnabled) {
                                storedLocalFraming
                            } else {
                                LocalFraming.OFF
                            },
                        )
                    } else if (version == 3) {
                        repeat(4) { input.readUTF() }
                        CameraSettings(
                            cameraMovement = CameraMovement.STEADY,
                            longTripCompression = enumOrDefault(input.readUTF(), LongTripCompression.BALANCED),
                            videoQuality = enumOrDefault(input.readUTF(), VideoQuality.STANDARD),
                            localFraming = LocalFraming.OFF,
                        )
                    } else {
                        CameraSettings.DEFAULT.copy(localFraming = LocalFraming.OFF)
                    }
                }
                val pointCount = input.readInt().coerceIn(0, MAX_POINT_COUNT)
                val points = List(pointCount) {
                    GeoPoint(
                        instant = Instant.ofEpochMilli(input.readLong()),
                        latitude = input.readDouble(),
                        longitude = input.readDouble(),
                    )
                }
                VideoExportRequest(
                    outputUri = outputUri,
                    journey = Journey.from(
                        points,
                        TimelinePeriod(
                            start = java.time.YearMonth.of(startYear, startMonth),
                            endInclusive = java.time.YearMonth.of(endYear, endMonth),
                        ),
                    ),
                    title = title,
                    durationSeconds = durationSeconds,
                    renderText = renderText,
                    cameraSettings = cameraSettings,
                )
            }
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        requestFile.delete()
        temporaryFile.delete()
    }

    companion object {
        private const val CURRENT_FILE_VERSION = 8
        private const val MAX_POINT_COUNT = 2_000_000
        private const val REQUEST_FILE = "pending-video-export.bin"
        private const val TEMPORARY_FILE = "pending-video-export.tmp"

        private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: fallback
    }
}
