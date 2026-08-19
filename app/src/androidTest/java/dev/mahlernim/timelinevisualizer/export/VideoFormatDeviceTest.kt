package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.RenderText
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import dev.mahlernim.timelinevisualizer.render.VideoFormatPreset
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs real exports on the attached device and reads the resulting MP4 back.
 *
 * The acceptance rule this guards is that the file matches the chosen format exactly. Nothing in
 * the pipeline may round a dimension or drop a frame rate to something the encoder found easier.
 */
@RunWith(AndroidJUnit4::class)
class VideoFormatDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theDeviceEncoderReportsWhichPresetsItCanProduce() {
        val profiles = VideoEncoderSupport.deviceProfiles()
        assertTrue("The device exposed no H.264 encoder at all", profiles.isNotEmpty())

        val report = VideoFormatPreset.values().mapNotNull { preset ->
            preset.format?.let { preset.name to VideoEncoderSupport.select(it, profiles) }
        }
        report.forEach { (name, support) -> Log.i(TAG, "ENCODER $name -> $support") }

        // Square is the default and the app is unusable without it.
        assertTrue(
            "The device cannot encode the default square format",
            VideoEncoderSupport.select(VideoFormatPreset.DEFAULT.format!!, profiles) is EncoderSupport.Supported,
        )
    }

    @Test
    fun eachSupportedPresetProducesAFileWithExactlyTheRequestedShape() {
        val profiles = VideoEncoderSupport.deviceProfiles()

        VideoFormatPreset.values().forEach { preset ->
            val format = preset.format ?: return@forEach
            if (VideoEncoderSupport.select(format, profiles) !is EncoderSupport.Supported) {
                Log.i(TAG, "SKIP ${preset.name}: unsupported on this device")
                return@forEach
            }
            if (!worthEncodingHere(format, profiles)) {
                Log.i(TAG, "SKIP ${preset.name}: too large for a software encoder")
                return@forEach
            }
            verifyExport(preset.name, CameraSettings(videoFormatPreset = preset))
        }
    }

    @Test
    fun aCustomFormatIsHonouredExactly() {
        val custom = VideoFormat.custom(1280, 720, 60)
        val profiles = VideoEncoderSupport.deviceProfiles()
        if (VideoEncoderSupport.select(custom, profiles) !is EncoderSupport.Supported) return
        if (!worthEncodingHere(custom, profiles)) return

        verifyExport(
            "CUSTOM_1280x720x60",
            CameraSettings(videoFormatPreset = VideoFormatPreset.CUSTOM, customFormat = custom),
        )
    }

    @Test
    fun anImpossibleFormatIsRefusedBeforeAnythingIsWritten() {
        // Far beyond any real H.264 encoder, so the exporter must refuse rather than substitute.
        val impossible = VideoFormat(7680, 7680, 60, 200_000_000)
        val destination = File(context.cacheDir, "impossible.mp4").also(File::delete)

        val error = runCatching {
            runBlocking {
                exporter().export(
                    Uri.fromFile(destination),
                    journey(),
                    "Impossible",
                    DURATION_SECONDS,
                    RenderText.ENGLISH,
                    CameraSettings(videoFormatPreset = VideoFormatPreset.CUSTOM, customFormat = impossible),
                ) {}
            }
        }.exceptionOrNull()

        assertTrue("Expected a typed refusal, got $error", error is UnsupportedVideoFormatException)
        assertTrue("A refused export must not leave a file behind", !destination.isFile)
    }

    private fun verifyExport(label: String, settings: CameraSettings) {
        val expected = settings.videoFormat
        val destination = File(context.cacheDir, "device-export-$label.mp4").also(File::delete)
        val phases = mutableListOf<ExportPhase>()
        var lastFraction = -1f

        val overview = runBlocking {
            exporter().export(
                Uri.fromFile(destination),
                journey(),
                "Device check",
                DURATION_SECONDS,
                RenderText.ENGLISH,
                settings,
            ) { progress ->
                if (phases.lastOrNull() != progress.phase) phases.add(progress.phase)
                assertTrue(
                    "$label progress went backwards: $lastFraction -> ${progress.fraction}",
                    progress.fraction >= lastFraction - 1e-4f,
                )
                lastFraction = progress.fraction
            }
        }

        assertTrue("$label produced no file", destination.isFile)
        assertTrue("$label produced an empty file", destination.length() > 0)

        val track = videoTrackFormat(destination)
        assertEquals("$label width", expected.width, track.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals("$label height", expected.height, track.getInteger(MediaFormat.KEY_HEIGHT))

        val measuredFrameRate = measuredFrameRate(destination)
        assertEquals("$label frame rate", expected.frameRate.toDouble(), measuredFrameRate, 1.0)

        assertEquals("$label ended on the wrong phase", ExportPhase.COMPLETE, phases.last())
        assertEquals("$label overview width", Mp4Exporter.overviewWidth(expected), overview.width)
        assertEquals("$label overview height", Mp4Exporter.overviewHeight(expected), overview.height)
        overview.recycle()

        Log.i(
            TAG,
            "EXPORT $label -> ${expected.width}x${expected.height}@${expected.frameRate} verified, " +
                "${destination.length()} bytes, ${"%.2f".format(measuredFrameRate)} fps measured",
        )
        destination.delete()
    }

    private fun videoTrackFormat(file: File): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return format
            }
            error("No video track in ${file.name}")
        } finally {
            extractor.release()
        }
    }

    /** Frames divided by duration, so a substituted frame rate cannot hide behind metadata. */
    private fun measuredFrameRate(file: File): Double {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val expectedFrames = (DURATION_SECONDS + TimelineAnimation.OUTRO_SECONDS)
            assertTrue("Only $frames frames in ${file.name}", frames > expectedFrames)
            return frames * 1000.0 / durationMs
        } finally {
            retriever.release()
        }
    }

    /**
     * Keeps the sweep bounded on emulators. A software encoder can technically accept 2160p or
     * 60 fps, but takes long enough to make a CI run unreliable, and the shapes that exercise the
     * layout and migration logic are all at or below 1080p anyway.
     *
     * The question is whether the encoder that would actually run this format is hardware-backed,
     * not whether the device lists one somewhere: an emulator can advertise a hardware decoder
     * next to the software encoder that does the real work.
     */
    private fun worthEncodingHere(format: VideoFormat, profiles: List<EncoderProfile>): Boolean {
        val chosen = VideoEncoderSupport.select(format, profiles) as? EncoderSupport.Supported ?: return false
        if (profiles.firstOrNull { it.name == chosen.name }?.hardwareAccelerated == true) return true
        return format.longEdge <= SOFTWARE_ENCODER_MAX_EDGE && format.frameRate <= SOFTWARE_ENCODER_MAX_FRAME_RATE
    }

    private fun exporter() = Mp4Exporter(context.contentResolver, TileRepository(context))

    private fun journey() = Journey.from(
        listOf(
            GeoPoint(Instant.parse("2025-01-01T00:00:00Z"), 37.5665, 126.9780),
            GeoPoint(Instant.parse("2025-05-01T00:00:00Z"), 37.4563, 126.7052),
            GeoPoint(Instant.parse("2025-09-01T00:00:00Z"), 35.1796, 129.0756),
            GeoPoint(Instant.parse("2025-12-01T00:00:00Z"), 33.4996, 126.5312),
        ),
        2025,
    )

    private companion object {
        const val TAG = "VideoFormatDeviceTest"
        const val SOFTWARE_ENCODER_MAX_EDGE = 1920
        const val SOFTWARE_ENCODER_MAX_FRAME_RATE = 30

        /** Short, but above the sample-count floor the exporter assumes for real durations. */
        const val DURATION_SECONDS = 3
    }
}
