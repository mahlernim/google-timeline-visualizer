import base64
from visualizer import (
    PresetValues,
    decode_preset,
    encode_preset,
)


def test_preset_codec_roundtrip():
    values = PresetValues(
        camera_movement="close_up",
        aspect_ratio="portrait",
        trip_detection="sensitive",
        local_framing="close",
        long_trip_compression="stronger",
    )
    token = encode_preset(values)
    assert len(token) <= 16
    decoded = decode_preset(token)
    assert decoded is not None
    assert decoded.camera_movement == "close_up"
    assert decoded.aspect_ratio == "portrait"
    assert decoded.trip_detection == "sensitive"
    assert decoded.local_framing == "close"
    assert decoded.long_trip_compression == "stronger"


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
    unsupported = base64.urlsafe_b64encode(bytes([0xA2, 0, 0])).decode("ascii").rstrip("=")
    assert decode_preset(unsupported) is None
