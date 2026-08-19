package dev.mahlernim.timelinevisualizer.export

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.TimelineFrame
import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import dev.mahlernim.timelinevisualizer.render.RenderText
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max

enum class ExportPhase { PREPARING_MAP, CREATING_VIDEO, FINISHING_VIDEO, COMPLETE }

data class ExportProgress(
    val fraction: Float,
    val phase: ExportPhase,
    val completed: Int,
    val total: Int,
)

class Mp4Exporter(
    private val contentResolver: ContentResolver,
    private val tileRepository: TileRepository,
) {
    suspend fun export(
        destination: Uri,
        journey: Journey,
        title: String,
        durationSeconds: Int,
        renderText: RenderText,
        cameraSettings: CameraSettings = CameraSettings.DEFAULT,
        onProgress: (ExportProgress) -> Unit,
    ): Bitmap = withContext(Dispatchers.Default) {
        require(journey.points.size >= 2) { "At least two location points are needed" }
        val videoFormat = cameraSettings.videoFormat
        // Checked before any tiles are fetched so an impossible format costs no bandwidth.
        val encoder = when (val support = VideoEncoderSupport.evaluate(videoFormat)) {
            is EncoderSupport.Supported -> support
            is EncoderSupport.Unsupported -> throw UnsupportedVideoFormatException(support.reason, videoFormat)
        }
        val width = videoFormat.width
        val height = videoFormat.height
        val fps = videoFormat.frameRate
        val overviewWidth = overviewWidth(videoFormat)
        val overviewHeight = overviewHeight(videoFormat)
        val painter = TimelinePainter()

        val sampleCount = max(durationSeconds * 2, ceil(journey.totalDistanceKm / 250.0).toInt())
            .coerceIn(20, durationSeconds * 8)
        val requiredTiles = buildSet {
            for (sample in 0..sampleCount) {
                val progress = sample.toFloat() / sampleCount
                addAll(
                    painter.requiredTiles(painter.viewport(journey, progress, width, height, cameraSettings))
                        .map { it.id },
                )
            }
            for (sample in 0..OUTRO_TILE_SAMPLES) {
                val outroProgress = sample.toFloat() / OUTRO_TILE_SAMPLES
                val frame = TimelineFrame(1f, outroProgress)
                addAll(
                    painter.requiredTiles(painter.viewport(journey, frame, width, height, cameraSettings))
                        .map { it.id },
                )
            }
            addAll(
                painter.requiredTiles(
                    painter.viewport(
                        journey,
                        TimelineFrame(1f, 1f),
                        overviewWidth,
                        overviewHeight,
                        cameraSettings,
                    ),
                ).map { it.id },
            )
        }
        onProgress(ExportProgress(0f, ExportPhase.PREPARING_MAP, 0, requiredTiles.size))
        requiredTiles.forEachIndexed { index, tile ->
            coroutineContext.ensureActive()
            tileRepository.load(tile)
            onProgress(
                ExportProgress(
                    (index + 1f) / requiredTiles.size.coerceAtLeast(1) * PREPARING_PROGRESS_WEIGHT,
                    ExportPhase.PREPARING_MAP,
                    index + 1,
                    requiredTiles.size,
                ),
            )
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, encoder.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, videoFormat.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(encoder.name)
        val descriptor = contentResolver.openFileDescriptor(destination, "rwt")
            ?: error("Could not open the selected output file")
        val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val yuv = ByteArray(width * height * 3 / 2)
        val bands = rowBands(height)

        fun drain(endOfStream: Boolean): Boolean {
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder format changed twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outputIndex)
                            ?: error("Encoder returned an empty output buffer")
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                        if (bufferInfo.size > 0) {
                            check(muxerStarted) { "Encoder produced data before its output format" }
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val journeyFrameCount = durationSeconds * fps
            val outroFrameCount = (TimelineAnimation.OUTRO_SECONDS * fps).toInt()
            val frameCount = journeyFrameCount + outroFrameCount
            for (frame in 0 until frameCount) {
                coroutineContext.ensureActive()
                val animationFrame = if (frame < journeyFrameCount) {
                    val progress = if (journeyFrameCount == 1) 1f else frame.toFloat() / (journeyFrameCount - 1)
                    TimelineFrame(progress, 0f)
                } else {
                    val outroFrame = frame - journeyFrameCount
                    val outroElapsed = outroFrame.toFloat() / fps
                    TimelineFrame(
                        1f,
                        (outroElapsed / TimelineAnimation.OUTRO_TRANSITION_SECONDS).coerceIn(0f, 1f),
                    )
                }
                var inputIndex: Int
                do {
                    inputIndex = codec.dequeueInputBuffer(10_000)
                    drain(false)
                } while (inputIndex < 0)

                val canvas = Canvas(bitmap)
                painter.draw(
                    canvas,
                    width,
                    height,
                    journey,
                    animationFrame,
                    durationSeconds,
                    title,
                    renderText,
                    cameraSettings,
                    tileRepository::cached,
                )
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                argbToYuv420(pixels, yuv, width, height, encoder.colorFormat, bands)
                val input = codec.getInputBuffer(inputIndex) ?: error("Encoder input buffer is unavailable")
                input.clear()
                check(input.capacity() >= yuv.size) { "Encoder input buffer is too small" }
                input.put(yuv)
                codec.queueInputBuffer(inputIndex, 0, yuv.size, frame * 1_000_000L / fps, 0)
                drain(false)
                val phase = if (frame < journeyFrameCount) ExportPhase.CREATING_VIDEO else ExportPhase.FINISHING_VIDEO
                val phaseChanged = frame == journeyFrameCount
                if (frame % fps == 0 || phaseChanged || frame == frameCount - 1) {
                    val phaseCompleted = if (phase == ExportPhase.CREATING_VIDEO) {
                        frame + 1
                    } else {
                        frame - journeyFrameCount + 1
                    }
                    val phaseTotal = if (phase == ExportPhase.CREATING_VIDEO) journeyFrameCount else outroFrameCount
                    onProgress(
                        ExportProgress(
                            if (phase == ExportPhase.CREATING_VIDEO) {
                                PREPARING_PROGRESS_WEIGHT +
                                    JOURNEY_PROGRESS_WEIGHT * phaseCompleted / phaseTotal.coerceAtLeast(1)
                            } else {
                                PREPARING_PROGRESS_WEIGHT + JOURNEY_PROGRESS_WEIGHT +
                                    FINISHING_PROGRESS_WEIGHT * phaseCompleted / phaseTotal.coerceAtLeast(1)
                            },
                            phase,
                            phaseCompleted,
                            phaseTotal,
                        ),
                    )
                }
            }

            var eosQueued = false
            while (!eosQueued) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, frameCount * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                } else {
                    drain(false)
                }
            }
            while (!drain(true)) coroutineContext.ensureActive()
            val overview = Bitmap.createBitmap(overviewWidth, overviewHeight, Bitmap.Config.ARGB_8888)
            painter.draw(
                Canvas(overview),
                overviewWidth,
                overviewHeight,
                journey,
                TimelineFrame(1f, 1f),
                durationSeconds,
                title,
                renderText,
                cameraSettings,
                tileRepository::cached,
            )
            onProgress(ExportProgress(1f, ExportPhase.COMPLETE, 1, 1))
            overview
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            descriptor.close()
            bitmap.recycle()
        }
    }

    private suspend fun argbToYuv420(
        pixels: IntArray,
        output: ByteArray,
        width: Int,
        height: Int,
        colorFormat: Int,
        bands: List<IntRange>,
    ) {
        if (bands.size <= 1) {
            argbToYuv420Rows(pixels, output, width, height, 0 until height, colorFormat)
            return
        }
        coroutineScope {
            bands.map { rows ->
                async { argbToYuv420Rows(pixels, output, width, height, rows, colorFormat) }
            }.awaitAll()
        }
    }

    companion object {
        /**
         * Splits the frame into row bands for parallel colour conversion.
         *
         * Every band starts on an even row so the 2x2 chroma subsampling in [argbToYuv420Rows] stays
         * aligned and each band can compute its own chroma offsets independently.
         */
        internal fun rowBands(height: Int): List<IntRange> {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val bandCount = cores.coerceAtMost((height / MIN_BAND_ROWS).coerceAtLeast(1))
            if (bandCount <= 1) return listOf(0 until height)
            var rowsPerBand = (height + bandCount - 1) / bandCount
            if (rowsPerBand % 2 != 0) rowsPerBand += 1
            return buildList {
                var start = 0
                while (start < height) {
                    add(start until (start + rowsPerBand).coerceAtMost(height))
                    start += rowsPerBand
                }
            }
        }

        /**
         * Converts [rows] of an ARGB frame into the planar or semi-planar YUV420 layout the encoder
         * asked for. [rows] must start on an even row; offsets are derived from that start rather than
         * from running counters, so bands can be converted concurrently.
         */
        internal fun argbToYuv420Rows(
            pixels: IntArray,
            output: ByteArray,
            width: Int,
            height: Int,
            rows: IntRange,
            colorFormat: Int,
        ) {
            val frameSize = width * height
            val chromaWidth = (width + 1) / 2
            val chromaRowsBefore = rows.first / 2
            var yIndex = rows.first * width
            var uIndex = frameSize + chromaRowsBefore * chromaWidth
            var vIndex = frameSize + frameSize / 4 + chromaRowsBefore * chromaWidth
            var uvIndex = frameSize + chromaRowsBefore * chromaWidth * 2
            val planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            for (y in rows) {
                for (x in 0 until width) {
                    val color = pixels[y * width + x]
                    val r = color shr 16 and 0xff
                    val g = color shr 8 and 0xff
                    val b = color and 0xff
                    val yValue = ((66 * r + 129 * g + 25 * b + 128 shr 8) + 16).coerceIn(0, 255)
                    output[yIndex++] = yValue.toByte()
                    if (y % 2 == 0 && x % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128 shr 8) + 128).coerceIn(0, 255).toByte()
                        val v = ((112 * r - 94 * g - 18 * b + 128 shr 8) + 128).coerceIn(0, 255).toByte()
                        if (planar) {
                            output[uIndex++] = u
                            output[vIndex++] = v
                        } else {
                            output[uvIndex++] = u
                            output[uvIndex++] = v
                        }
                    }
                }
            }
        }

        private const val OUTRO_TILE_SAMPLES = 12
        private const val MIN_BAND_ROWS = 64
        private const val PREPARING_PROGRESS_WEIGHT = 0.10f
        private const val JOURNEY_PROGRESS_WEIGHT = 0.80f
        private const val FINISHING_PROGRESS_WEIGHT = 0.10f
        const val OVERVIEW_MAX_EDGE = 1080

        /**
         * The still image saved alongside the video keeps the export's aspect ratio with its long
         * edge at [OVERVIEW_MAX_EDGE], so a square export still produces the 1080x1080 image it
         * always has.
         */
        fun overviewWidth(format: VideoFormat): Int = overviewEdge(format.width, format.longEdge)

        fun overviewHeight(format: VideoFormat): Int = overviewEdge(format.height, format.longEdge)

        private fun overviewEdge(edge: Int, longEdge: Int): Int =
            (edge.toLong() * OVERVIEW_MAX_EDGE / longEdge.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    }
}
