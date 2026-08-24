import base64
from visualizer import (
    DEFAULT_DURATION,
    MAX_DURATION_SECONDS,
    MIN_DURATION_SECONDS,
    PRESET_FORMAT,
    PRESET_LEGACY_FORMAT,
    PresetValues,
    decode_preset,
    encode_preset,
)


def _token(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _decode_raw(token: str) -> bytes:
    return base64.urlsafe_b64decode(token + "=" * ((4 - len(token) % 4) % 4))


def test_preset_codec_roundtrip():
    values = PresetValues(
        camera_movement="close_up",
        aspect_ratio="portrait",
        trip_detection="sensitive",
        local_framing="close",
        long_trip_compression="stronger",
        duration_seconds=120,
    )
    token = encode_preset(values)
    assert len(token) <= 24
    decoded = decode_preset(token)
    assert decoded is not None
    assert decoded.camera_movement == "close_up"
    assert decoded.aspect_ratio == "portrait"
    assert decoded.trip_detection == "sensitive"
    assert decoded.local_framing == "close"
    assert decoded.long_trip_compression == "stronger"
    assert decoded.duration_seconds == 120


def test_preset_codec_all_options_roundtrip():
    for cam in ("fixed", "steady", "dynamic", "close_up"):
        for aspect in ("square", "portrait", "landscape"):
            for trip in ("conservative", "balanced", "sensitive"):
                for framing in ("off", "balanced", "close"):
                    for comp in ("off", "balanced", "strong", "stronger"):
                        v = PresetValues(cam, aspect, trip, framing, comp)
                        token = encode_preset(v)
                        dec = decode_preset(token)
                        assert dec is not None
                        assert dec.camera_movement == cam
                        assert dec.aspect_ratio == aspect
                        assert dec.trip_detection == trip
                        assert dec.local_framing == framing
                        assert dec.long_trip_compression == comp


def test_preset_codec_tolerates_extra_bytes_and_reserved_bits():
    values = PresetValues(
        camera_movement="close_up",
        aspect_ratio="portrait",
        trip_detection="sensitive",
        local_framing="close",
        long_trip_compression="stronger",
    )
    raw = bytearray(base64.urlsafe_b64decode(encode_preset(values) + "=="))
    raw[2] |= 0b11110000
    raw.extend([7, 8])
    extended_token = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    decoded = decode_preset(extended_token)
    assert decoded is not None
    assert decoded.camera_movement == "close_up"
    assert decoded.aspect_ratio == "portrait"


def test_invalid_tokens_rejected():
    assert decode_preset("") is None
    assert decode_preset("not!urlsafe") is None
    assert decode_preset("a" * 20) is None
    assert decode_preset("a" * 25) is None


def test_unsupported_format_byte_rejected():
    """A format byte this build does not know is refused. 0xA2 and 0xA1 are both known."""
    unsupported = _token(bytes([0xA3, 0, 0, 30, 0]))
    assert decode_preset(unsupported) is None


def test_truncated_current_format_rejected():
    """0xA2 promises a duration in bytes 3 and 4, so a three-byte payload is malformed."""
    assert decode_preset(_token(bytes([PRESET_FORMAT, 0, 0]))) is None


def test_decodes_android_token_with_duration():
    """Cross-platform vector: the byte layout PresetCodec.kt emits must decode here.

    PresetCodec.encode packs camera | aspect << 2 | trip << 4 | framing << 6 into byte 1,
    the pacing ordinal into byte 2, and the duration little-endian into bytes 3 and 4.
    """
    packed = 3 | (1 << 2) | (2 << 4) | (2 << 6)
    android_token = _token(bytes([PRESET_FORMAT, packed, 3, 45 & 0xFF, 45 >> 8]))
    decoded = decode_preset(android_token)
    assert decoded is not None
    assert decoded.camera_movement == "close_up"
    assert decoded.aspect_ratio == "portrait"
    assert decoded.trip_detection == "sensitive"
    assert decoded.local_framing == "close"
    assert decoded.long_trip_compression == "stronger"
    assert decoded.duration_seconds == 45


def test_encoded_token_uses_current_format():
    raw = _decode_raw(encode_preset(PresetValues()))
    assert raw[0] == PRESET_FORMAT
    assert len(raw) == 5


def test_legacy_token_defaults_duration():
    """The three-byte 0xA1 layout predates the duration field."""
    legacy = _token(bytes([PRESET_LEGACY_FORMAT, 0, 0]))
    decoded = decode_preset(legacy)
    assert decoded is not None
    assert decoded.duration_seconds == DEFAULT_DURATION


def test_duration_outside_supported_range_rejected():
    for seconds in (MIN_DURATION_SECONDS - 1, MAX_DURATION_SECONDS + 1, 0, 0xFFFF):
        token = _token(bytes([PRESET_FORMAT, 0, 0, seconds & 0xFF, (seconds >> 8) & 0xFF]))
        assert decode_preset(token) is None, seconds


def test_duration_range_boundaries_accepted():
    for seconds in (MIN_DURATION_SECONDS, MAX_DURATION_SECONDS):
        token = _token(bytes([PRESET_FORMAT, 0, 0, seconds & 0xFF, (seconds >> 8) & 0xFF]))
        decoded = decode_preset(token)
        assert decoded is not None, seconds
        assert decoded.duration_seconds == seconds


def test_every_duration_in_range_round_trips():
    for seconds in range(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS + 1):
        values = PresetValues(duration_seconds=seconds)
        decoded = decode_preset(encode_preset(values))
        assert decoded is not None, seconds
        assert decoded.duration_seconds == seconds
