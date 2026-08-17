import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAY_STORE = ROOT / "play-store"


def png_info(path: Path) -> tuple[int, int, int]:
    data = path.read_bytes()[:26]
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    assert data[12:16] == b"IHDR"
    width, height = struct.unpack(">II", data[16:24])
    return width, height, data[25]


def test_listing_text_meets_play_limits():
    for locale in ("en-US", "ko-KR"):
        listing = PLAY_STORE / "listing" / locale
        assert len((listing / "title.txt").read_text(encoding="utf-8").strip()) <= 30
        assert len((listing / "short-description.txt").read_text(encoding="utf-8").strip()) <= 80
        assert len((listing / "full-description.txt").read_text(encoding="utf-8").strip()) <= 4_000


def test_primary_graphics_meet_play_dimensions_and_formats():
    icon = PLAY_STORE / "assets" / "app-icon-512.png"
    assert png_info(icon) == (512, 512, 6)  # 32-bit RGBA PNG
    assert icon.stat().st_size <= 1_024 * 1_024

    for locale in ("en", "ko"):
        feature = PLAY_STORE / "assets" / f"feature-graphic-{locale}-1024x500.png"
        assert png_info(feature) == (1024, 500, 2)  # 24-bit RGB PNG


def test_phone_screenshots_are_current_play_recommended_size():
    for locale in ("en-US", "ko-KR"):
        screenshots = sorted((PLAY_STORE / "assets" / "screenshots" / locale).glob("*.png"))
        assert len(screenshots) == 4
        for screenshot in screenshots:
            width, height, color_type = png_info(screenshot)
            assert (width, height) == (1080, 1920)
            assert color_type == 2  # 24-bit RGB PNG, no alpha
            assert max(width, height) <= 2 * min(width, height)
            assert screenshot.stat().st_size <= 8 * 1_024 * 1_024


def test_play_release_metadata_is_consistent():
    build_file = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'versionCode = 6' in build_file
    assert 'versionName = "1.5.0"' in build_file
    assert (ROOT / "docs" / "release-notes-v1.5.0.md").is_file()
