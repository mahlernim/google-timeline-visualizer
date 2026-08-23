import re
import xml.etree.ElementTree as ET
from pathlib import Path


RES = Path(__file__).parents[1] / "app" / "src" / "main" / "res"
LOCALES = (
    "values-de",
    "values-es",
    "values-fr",
    "values-ja",
    "values-ko",
    "values-pt-rBR",
    "values-zh-rCN",
    "values-zh-rTW",
)
TOKEN = re.compile(r"%\d+\$[a-zA-Z]|%[a-zA-Z]|\{(?:name|year)\}")


def resources(folder: str):
    return {
        node.attrib["name"]: node
        for node in ET.parse(RES / folder / "strings.xml").getroot()
        if "name" in node.attrib
    }


def tokens(node):
    return sorted(set(TOKEN.findall("".join(node.itertext()))))


def test_all_trips_resources_are_localized_with_matching_placeholders():
    base = resources("values")
    names = list(base)
    trips = names[names.index("trips") :]

    for folder in LOCALES:
        localized = resources(folder)
        missing = [name for name in trips if name not in localized]
        assert not missing, f"{folder} is missing {missing}"
        for name in trips:
            assert localized[name].tag == base[name].tag, f"{folder}:{name} has the wrong resource type"
            assert tokens(localized[name]) == tokens(base[name]), f"{folder}:{name} changed placeholders"
