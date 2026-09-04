package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import android.media.MediaCodec
import android.system.ErrnoException
import android.system.OsConstants
import dev.mahlernim.timelinevisualizer.R
import java.io.FileNotFoundException
import java.io.IOException

enum class VideoExportFailureKind(val retryable: Boolean) {
    MAP_UNAVAILABLE(true),
    ENCODER_TEMPORARY(true),
    FORMAT_UNSUPPORTED(false),
    RESOURCE_LIMIT(false),
    STORAGE_FULL(false),
    OUTPUT_UNAVAILABLE(false),
    INSUFFICIENT_DATA(false),
    UNKNOWN(true),
}

internal data class VideoExportFailure(
    val kind: VideoExportFailureKind,
    val message: String,
)

internal fun classifyVideoExportFailure(context: Context, error: Throwable): VideoExportFailure {
    val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    causes.filterIsInstance<UnsupportedVideoFormatException>().firstOrNull()?.let { unsupported ->
        return VideoExportFailure(
            VideoExportFailureKind.FORMAT_UNSUPPORTED,
            unsupported.reason.describe(context, unsupported.format),
        )
    }
    if (causes.any { it is MapTilePreparationException }) {
        return VideoExportFailure(
            VideoExportFailureKind.MAP_UNAVAILABLE,
            context.getString(R.string.map_tiles_unavailable),
        )
    }
    if (causes.any { it is NoRecordedMovementException }) {
        return VideoExportFailure(VideoExportFailureKind.INSUFFICIENT_DATA,
            context.getString(R.string.recorded_speed_no_movement))
    }
    if (causes.any { it is InsufficientJourneyDataException }) {
        return VideoExportFailure(
            VideoExportFailureKind.INSUFFICIENT_DATA,
            context.getString(R.string.video_export_insufficient_data),
        )
    }
    causes.filterIsInstance<MediaCodec.CodecException>().firstOrNull()?.let { codec ->
        val kind = classifyCodecFailure(codec.isTransient, codec.isRecoverable)
        return VideoExportFailure(
            kind,
            context.getString(
                if (kind.retryable) R.string.video_export_encoder_temporary else R.string.video_export_resource_limit,
            ),
        )
    }
    if (causes.any { it is OutOfMemoryError }) {
        return VideoExportFailure(
            VideoExportFailureKind.RESOURCE_LIMIT,
            context.getString(R.string.video_export_resource_limit),
        )
    }
    if (causes.filterIsInstance<ErrnoException>().any { it.errno == OsConstants.ENOSPC }) {
        return VideoExportFailure(
            VideoExportFailureKind.STORAGE_FULL,
            context.getString(R.string.video_export_storage_full),
        )
    }
    if (causes.any { it is SecurityException || it is FileNotFoundException || it is IOException }) {
        return VideoExportFailure(
            VideoExportFailureKind.OUTPUT_UNAVAILABLE,
            context.getString(R.string.video_export_output_unavailable),
        )
    }
    return VideoExportFailure(
        VideoExportFailureKind.UNKNOWN,
        context.getString(R.string.video_export_unknown),
    )
}

internal fun classifyCodecFailure(isTransient: Boolean, isRecoverable: Boolean): VideoExportFailureKind =
    if (isTransient || isRecoverable) {
        VideoExportFailureKind.ENCODER_TEMPORARY
    } else {
        VideoExportFailureKind.RESOURCE_LIMIT
    }

private const val MAX_CAUSE_DEPTH = 16
