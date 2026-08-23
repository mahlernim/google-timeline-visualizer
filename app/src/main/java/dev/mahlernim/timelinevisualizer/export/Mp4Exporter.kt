package dev.mahlernim.timelinevisualizer.export

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.muxer.BufferInfo as MuxerBufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.RenderText
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import dev.mahlernim.timelinevisualizer.render.TimelineFrame
import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import dev.mahlernim.timelinevisualizer.render.TileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

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
    @OptIn(UnstableApi::class)
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
        val videoFormat = cameraSettings.videoQuality
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
        val (journeyFrameCount, outroFrameCount) = videoFrameCounts(durationSeconds, fps)
        val frameCount = journeyFrameCount + outroFrameCount

        val requiredTiles = requiredTilesForExport(
            painter,
            journey,
            width,
            height,
            journeyFrameCount,
            outroFrameCount,
            fps,
            overviewWidth,
            overviewHeight,
            cameraSettings,
        )
        onProgress(ExportProgress(0f, ExportPhase.PREPARING_MAP, 0, requiredTiles.size))
        MapTilePreparer(
            isReady = { tileRepository.cached(it) != null },
            load = { tileRepository.load(it) },
        ).prepare(requiredTiles) { completed, total ->
            onProgress(
                ExportProgress(
                    completed.toFloat() / total.coerceAtLeast(1) * PREPARING_PROGRESS_WEIGHT,
                    ExportPhase.PREPARING_MAP,
                    completed,
                    total,
                ),
            )
        }
        val preparedTile = { tile: TileId ->
            tileRepository.cached(tile)
                ?: throw MapTilePreparationException(listOf(tile), requiredTiles.size)
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
        val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        val muxer = try {
            Mp4Muxer.Builder(SeekableMuxerOutput.of(outputStream)).build()
        } catch (error: Throwable) {
            runCatching { outputStream.close() }.onFailure(error::addSuppressed)
            throw error
        }
        muxer.addMetadataEntry(
            MdtaMetadataEntry(
                "title",
                title.toByteArray(Charsets.UTF_8),
                MdtaMetadataEntry.TYPE_INDICATOR_STRING,
            ),
        )
        var muxerStarted = false
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val yuv = ByteArray(width * height * 3 / 2)

        fun drain(endOfStream: Boolean): Boolean {
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder format changed twice" }
                        val media3Format = MediaFormatUtil.createFormatFromMediaFormat(codec.outputFormat)
                        trackIndex = muxer.addTrack(media3Format)
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
                            val media3BufferInfo = MuxerBufferInfo(
                                bufferInfo.presentationTimeUs,
                                bufferInfo.size,
                                bufferInfo.flags,
                            )
                            muxer.writeSampleData(trackIndex, encoded, media3BufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }

        var exportFailure: Throwable? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            for (frame in 0 until frameCount) {
                coroutineContext.ensureActive()
                val animationFrame = animationFrame(frame, journeyFrameCount, fps)
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
                    tiles = preparedTile,
                )
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                argbToYuv420(pixels, yuv, width, height, encoder.colorFormat)
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
                tiles = preparedTile,
            )
            onProgress(ExportProgress(1f, ExportPhase.COMPLETE, 1, 1))
            overview
        } catch (error: Throwable) {
            exportFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanUp(block: () -> Unit) {
                try {
                    block()
                } catch (error: Throwable) {
                    val priorFailure = exportFailure ?: cleanupFailure
                    if (priorFailure == null) cleanupFailure = error else priorFailure.addSuppressed(error)
                }
            }
            cleanUp { codec.stop() }
            cleanUp { codec.release() }
            cleanUp { muxer.close() }
            bitmap.recycle()
            if (exportFailure == null) cleanupFailure?.let { throw it }
        }
    }

    // Flexible YUV does not identify the planar or semi-planar byte layout required by this converter.
    @Suppress("DEPRECATION")
    private fun argbToYuv420(pixels: IntArray, output: ByteArray, width: Int, height: Int, colorFormat: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize
        for (y in 0 until height) {
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
                    if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
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

    companion object {
        const val OVERVIEW_MAX_EDGE = 1080
        private const val PREPARING_PROGRESS_WEIGHT = 0.10f
        private const val JOURNEY_PROGRESS_WEIGHT = 0.80f
        private const val FINISHING_PROGRESS_WEIGHT = 0.10f

        internal fun videoFrameCounts(durationSeconds: Int, fps: Int): Pair<Int, Int> {
            val frameCount = durationSeconds.coerceAtLeast(1) * fps.coerceAtLeast(1)
            val outroFrameCount = minOf(
                (TimelineAnimation.OUTRO_SECONDS * fps).toInt(),
                frameCount - 1,
            )
            return frameCount - outroFrameCount to outroFrameCount
        }

        internal fun overviewWidth(format: dev.mahlernim.timelinevisualizer.render.VideoQuality): Int =
            if (format.width >= format.height) {
                OVERVIEW_MAX_EDGE
            } else {
                (OVERVIEW_MAX_EDGE * format.aspectRatio).toInt().coerceAtLeast(1).toEven()
            }

        internal fun overviewHeight(format: dev.mahlernim.timelinevisualizer.render.VideoQuality): Int =
            if (format.height >= format.width) {
                OVERVIEW_MAX_EDGE
            } else {
                (OVERVIEW_MAX_EDGE / format.aspectRatio).toInt().coerceAtLeast(1).toEven()
            }

        internal fun requiredTilesForExport(
            painter: TimelinePainter,
            journey: Journey,
            width: Int,
            height: Int,
            journeyFrameCount: Int,
            outroFrameCount: Int,
            fps: Int,
            overviewWidth: Int,
            overviewHeight: Int,
            cameraSettings: CameraSettings,
        ) = buildSet {
            for (frame in 0 until journeyFrameCount + outroFrameCount) {
                addAll(
                    painter.requiredTiles(
                        painter.viewport(
                            journey,
                            animationFrame(frame, journeyFrameCount, fps),
                            width,
                            height,
                            cameraSettings,
                        ),
                    ).map { it.id },
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

        internal fun animationFrame(frame: Int, journeyFrameCount: Int, fps: Int): TimelineFrame =
            if (frame < journeyFrameCount) {
                val progress = if (journeyFrameCount == 1) 1f else frame.toFloat() / (journeyFrameCount - 1)
                TimelineFrame(progress, 0f)
            } else {
                val outroElapsed = (frame - journeyFrameCount).toFloat() / fps
                TimelineFrame(
                    1f,
                    (outroElapsed / TimelineAnimation.OUTRO_TRANSITION_SECONDS).coerceIn(0f, 1f),
                )
            }

        private fun Int.toEven(): Int = if (this % 2 == 0) this else this + 1
    }
}
