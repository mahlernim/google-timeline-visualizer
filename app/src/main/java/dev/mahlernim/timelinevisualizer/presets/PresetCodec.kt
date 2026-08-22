package dev.mahlernim.timelinevisualizer.presets

import dev.mahlernim.timelinevisualizer.render.CameraMovement
import dev.mahlernim.timelinevisualizer.render.LocalFraming
import dev.mahlernim.timelinevisualizer.render.LongTripCompression
import dev.mahlernim.timelinevisualizer.render.TripDetection
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import dev.mahlernim.timelinevisualizer.model.VideoDuration
import java.util.Base64

sealed interface PresetDecodeResult {
    data class Success(val values: PresetValues) : PresetDecodeResult
    data object Invalid : PresetDecodeResult
    data object Unsupported : PresetDecodeResult
}

object PresetCodec {
    private const val LEGACY_FORMAT = 0xA1
    private const val FORMAT = 0xA2
    private const val MIN_BYTES = 3
    private const val MAX_BYTES = 8
    const val MAX_TOKEN_LENGTH = 24

    fun encode(values: PresetValues): String {
        val packed = values.cameraMovement.ordinal or
            (values.aspectRatio.ordinal shl 2) or
            (values.tripDetection.ordinal shl 4) or
            (values.localFraming.ordinal shl 6)
        val bytes = byteArrayOf(
            FORMAT.toByte(),
            packed.toByte(),
            values.longTripCompression.ordinal.toByte(),
            (values.durationSeconds and 0xff).toByte(),
            (values.durationSeconds ushr 8).toByte(),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(token: String): PresetDecodeResult {
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH || !token.matches(TOKEN_PATTERN)) {
            return PresetDecodeResult.Invalid
        }
        val bytes = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull()
            ?: return PresetDecodeResult.Invalid
        if (bytes.size !in MIN_BYTES..MAX_BYTES) return PresetDecodeResult.Invalid
        val format = bytes[0].toInt() and 0xff
        if (format != FORMAT && format != LEGACY_FORMAT) return PresetDecodeResult.Unsupported
        val packed = bytes[1].toInt() and 0xff
        val camera = CameraMovement.entries.getOrNull(packed and 0b11)
            ?: return PresetDecodeResult.Invalid
        val aspect = VideoAspectRatio.entries.getOrNull((packed ushr 2) and 0b11)
            ?: return PresetDecodeResult.Invalid
        val trip = TripDetection.entries.getOrNull((packed ushr 4) and 0b11)
            ?: return PresetDecodeResult.Invalid
        val framing = LocalFraming.entries.getOrNull((packed ushr 6) and 0b11)
            ?: return PresetDecodeResult.Invalid
        val pacing = LongTripCompression.entries.getOrNull(bytes[2].toInt() and 0b11)
            ?: return PresetDecodeResult.Invalid
        val durationSeconds = if (format == FORMAT) {
            val low = bytes.getOrNull(3)?.toInt()?.and(0xff) ?: return PresetDecodeResult.Invalid
            val high = bytes.getOrNull(4)?.toInt()?.and(0xff) ?: return PresetDecodeResult.Invalid
            (low or (high shl 8)).takeIf { it in VideoDuration.MIN_SECONDS..VideoDuration.MAX_SECONDS }
                ?: return PresetDecodeResult.Invalid
        } else {
            30
        }
        return PresetDecodeResult.Success(PresetValues(aspect, camera, trip, framing, pacing, durationSeconds))
    }

    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]+$")
}
