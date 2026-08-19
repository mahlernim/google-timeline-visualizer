import struct
import re
import xml.etree.ElementTree as ET
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
    for locale in (
        "en-US",
        "ko-KR",
        "ja-JP",
        "zh-CN",
        "zh-TW",
        "es-ES",
        "fr-FR",
        "de-DE",
        "pt-BR",
    ):
        listing = PLAY_STORE / "listing" / locale
        assert len((listing / "title.txt").read_text(encoding="utf-8").strip()) <= 30
        assert len((listing / "short-description.txt").read_text(encoding="utf-8").strip()) <= 80
        assert len((listing / "full-description.txt").read_text(encoding="utf-8").strip()) <= 4_000


def test_primary_graphics_meet_play_dimensions_and_formats():
    icon = PLAY_STORE / "assets" / "app-icon-512.png"
    assert png_info(icon) == (512, 512, 6)  # 32-bit RGBA PNG
    assert icon.stat().st_size <= 1_024 * 1_024

    for locale in ("en", "ko", "ja"):
        feature = PLAY_STORE / "assets" / f"feature-graphic-{locale}-1024x500.png"
        assert png_info(feature) == (1024, 500, 2)  # 24-bit RGB PNG


def test_phone_screenshots_are_current_play_recommended_size():
    for locale in ("en-US", "ko-KR", "ja-JP"):
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
    version_code = re.search(r"versionCode = (\d+)", build_file).group(1)
    version_name = re.search(r'versionName = "([^"]+)"', build_file).group(1)
    checklist = (PLAY_STORE / "submission-checklist.md").read_text(encoding="utf-8")

    assert f"Version name `{version_name}` and version code `{version_code}`" in checklist
    assert (ROOT / "docs" / f"release-notes-v{version_name}.md").is_file()


def test_android_locales_have_matching_resources_and_placeholders():
    files = {
        "en": ROOT / "app/src/main/res/values/strings.xml",
        "ko": ROOT / "app/src/main/res/values-ko/strings.xml",
        "ja": ROOT / "app/src/main/res/values-ja/strings.xml",
        "zh-CN": ROOT / "app/src/main/res/values-zh-rCN/strings.xml",
        "zh-TW": ROOT / "app/src/main/res/values-zh-rTW/strings.xml",
        "es": ROOT / "app/src/main/res/values-es/strings.xml",
        "fr": ROOT / "app/src/main/res/values-fr/strings.xml",
        "de": ROOT / "app/src/main/res/values-de/strings.xml",
        "pt-BR": ROOT / "app/src/main/res/values-pt-rBR/strings.xml",
    }

    def resources(path: Path):
        result = {}
        for element in ET.parse(path).getroot():
            name = element.attrib["name"]
            text = " ".join(element.itertext())
            placeholders = sorted(set(re.findall(r"%\d+\$[a-zA-Z]|\{(?:year|name)\}", text)))
            result[(element.tag, name)] = placeholders
        return result

    localized = {locale: resources(path) for locale, path in files.items()}
    for locale, values in localized.items():
        assert values == localized["en"], locale


def test_localized_restoration_guides_are_complete_and_accessible():
    guides = {
        "en": ROOT / "docs/restore-google-maps-timeline.md",
        "ko": ROOT / "docs/restore-google-maps-timeline.ko.md",
        "ja": ROOT / "docs/restore-google-maps-timeline.ja.md",
    }
    diagrams = {
        "en": ROOT / "docs/images/restore-timeline-en.svg",
        "ko": ROOT / "docs/images/restore-timeline-ko.svg",
        "ja": ROOT / "docs/images/restore-timeline-ja.svg",
    }
    for locale, guide in guides.items():
        text = guide.read_text(encoding="utf-8")
        assert f"images/restore-timeline-{locale}.svg" in text
        assert "support.google.com/maps/answer/6258979" in text
        assert "Timeline Visualizer" in text or "타임라인 비주얼라이저" in text or "タイムライン可視化" in text

        svg = ET.parse(diagrams[locale]).getroot()
        namespace = {"svg": "http://www.w3.org/2000/svg"}
        assert svg.find("svg:title", namespace).text.strip()
        assert svg.find("svg:desc", namespace).text.strip()

    assert "docs/restore-google-maps-timeline.md" in (ROOT / "README.md").read_text(encoding="utf-8")
    assert "docs/restore-google-maps-timeline.ko.md" in (ROOT / "README.ko.md").read_text(encoding="utf-8")
    assert "docs/restore-google-maps-timeline.ja.md" in (ROOT / "README.ja.md").read_text(encoding="utf-8")


def test_canonical_terminology_and_compact_buttons():
    glossary = (ROOT / "docs/terminology.md").read_text(encoding="utf-8")
    for term in ("Timeline file", "Journey", "Video", "Selected period", "Journey overview"):
        assert term in glossary

    current_copy = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (
            ROOT / "app/src/main/res/values/strings.xml",
            ROOT / "README.md",
            ROOT / "play-store/listing/en-US/full-description.txt",
            ROOT / "play-store/assets/alt-text.md",
        )
    )
    assert "Get JSON" not in current_copy
    assert "Creations" not in current_copy
    assert "Export Timeline data" in current_copy

    for layout_name in ("screen_videos.xml", "screen_new_video.xml"):
        root = ET.parse(ROOT / "app/src/main/res/layout" / layout_name).getroot()
        for element in root.iter():
            if element.tag.endswith("MaterialButton"):
                assert element.attrib.get("{http://schemas.android.com/apk/res/android}maxLines") == "1"


def test_screenshot_alt_text_covers_every_localized_image():
    alt_text = (PLAY_STORE / "assets/alt-text.md").read_text(encoding="utf-8")
    for stem in ("01 Videos", "02 Timeline file", "03 Selected period", "04 Video saved"):
        assert stem in alt_text
    for stem in ("01 영상", "02 타임라인 파일", "03 선택 기간", "04 영상 저장 완료"):
        assert stem in alt_text
    for stem in ("01 動画", "02 タイムライン ファイル", "03 選択期間", "04 動画を保存"):
        assert stem in alt_text
