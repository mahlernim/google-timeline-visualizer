import re
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


@pytest.mark.parametrize("directory", ["values-in", "values-vi"])
def test_new_languages_cover_resources_and_preserve_format_arguments(directory):
    resources = ROOT / "app/src/main/res"
    source = {
        element.attrib["name"]: element
        for element in ET.parse(resources / "values/strings.xml").getroot()
        if element.attrib.get("translatable") != "false"
    }
    localized_elements = list(ET.parse(resources / directory / "strings.xml").getroot())
    localized = {element.attrib["name"]: element for element in localized_elements}
    assert len(localized) == len(localized_elements), "Duplicate resource names"
    assert source.keys() <= localized.keys(), source.keys() - localized.keys()

    def arguments(text):
        return sorted(re.findall(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]", text))

    for name, original in source.items():
        translated = localized[name]
        assert translated.tag == original.tag, name
        if original.tag == "plurals":
            # Both languages use CLDR's single 'other' cardinal category.
            assert [item.attrib["quantity"] for item in translated] == ["other"], name
            baseline = original.find("item[@quantity='other']")
            text = "".join(translated[0].itertext())
            assert text.strip(), name
            assert arguments(text) == arguments("".join(baseline.itertext())), name
        else:
            text = "".join(translated.itertext())
            assert text.strip(), name
            assert arguments(text) == arguments("".join(original.itertext())), name


def test_language_selector_matches_android_locale_configuration():
    source = (
        ROOT
        / "app/src/main/java/dev/mahlernim/timelinevisualizer/ui/AppLanguage.kt"
    ).read_text(encoding="utf-8")
    source_tags = re.search(r"supportedTags = listOf\(([^)]+)\)", source).group(1)
    supported = re.findall(r'"([^"]+)"', source_tags)

    locale_config = ET.parse(ROOT / "app/src/main/res/xml/locales_config.xml").getroot()
    configured = [element.attrib[f"{ANDROID_NS}name"] for element in locale_config]

    assert supported == configured


def test_appcompat_persists_selected_language_before_android_13():
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    service = next(
        element
        for element in application.findall("service")
        if element.attrib.get(f"{ANDROID_NS}name")
        == "androidx.appcompat.app.AppLocalesMetadataHolderService"
    )
    metadata = service.find("meta-data")

    assert service.attrib[f"{ANDROID_NS}enabled"] == "false"
    assert service.attrib[f"{ANDROID_NS}exported"] == "false"
    assert metadata.attrib[f"{ANDROID_NS}name"] == "autoStoreLocales"
    assert metadata.attrib[f"{ANDROID_NS}value"] == "true"
