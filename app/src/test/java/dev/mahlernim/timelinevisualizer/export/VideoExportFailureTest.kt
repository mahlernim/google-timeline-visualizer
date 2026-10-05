package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.R
import dev.mahlernim.timelinevisualizer.render.TileId
import dev.mahlernim.timelinevisualizer.render.VideoFormat
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoExportFailureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun classifiesMapFailureAsRetryable() {
        assertFailure(
            MapTilePreparationException(listOf(TileId(2, 1, 1)), 4),
            VideoExportFailureKind.MAP_UNAVAILABLE,
            R.string.map_tiles_unavailable,
        )
    }

    @Test
    fun vectorFailureGivesLocalizedRecoveryWithoutProviderUrls() {
        assertFailure(
            dev.mahlernim.timelinevisualizer.render.VectorMapException(),
            VideoExportFailureKind.MAP_UNAVAILABLE,
            R.string.vector_map_failed,
        )
    }

    @Test
    fun classifiesCodecFlags() {
        assertEquals(VideoExportFailureKind.ENCODER_TEMPORARY, classifyCodecFailure(true, false))
        assertEquals(VideoExportFailureKind.ENCODER_TEMPORARY, classifyCodecFailure(false, true))
        assertEquals(VideoExportFailureKind.RESOURCE_LIMIT, classifyCodecFailure(false, false))
    }

    @Test
    fun classifiesUnsupportedFormat() {
        val exception = UnsupportedVideoFormatException(
            EncoderSupport.Reason.FRAME_RATE,
            VideoFormat(1920, 1080, 60, 8_000_000),
        )
        val failure = classifyVideoExportFailure(context, exception)

        assertEquals(VideoExportFailureKind.FORMAT_UNSUPPORTED, failure.kind)
        assertEquals(exception.reason.describe(context, exception.format), failure.message)
    }

    @Test
    fun classifiesResourceLimit() {
        assertFailure(
            OutOfMemoryError(),
            VideoExportFailureKind.RESOURCE_LIMIT,
            R.string.video_export_resource_limit,
        )
    }

    @Test
    fun classifiesWrappedStorageFullBeforeGenericIo() {
        val exception = IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))

        assertFailure(
            exception,
            VideoExportFailureKind.STORAGE_FULL,
            R.string.video_export_storage_full,
        )
    }

    @Test
    fun classifiesOutputFailures() {
        listOf(SecurityException(), FileNotFoundException(), IOException()).forEach { exception ->
            assertFailure(
                exception,
                VideoExportFailureKind.OUTPUT_UNAVAILABLE,
                R.string.video_export_output_unavailable,
            )
        }
    }

    @Test
    fun classifiesInsufficientJourneyData() {
        assertFailure(
            InsufficientJourneyDataException(),
            VideoExportFailureKind.INSUFFICIENT_DATA,
            R.string.video_export_insufficient_data,
        )
    }

    @Test
    fun unknownFailureOffersOneRetry() {
        assertFailure(
            IllegalStateException("local detail must not be shown"),
            VideoExportFailureKind.UNKNOWN,
            R.string.video_export_unknown,
        )
    }

    private fun assertFailure(error: Throwable, kind: VideoExportFailureKind, messageRes: Int) {
        val failure = classifyVideoExportFailure(context, error)

        assertEquals(kind, failure.kind)
        assertEquals(context.getString(messageRes), failure.message)
    }
}
