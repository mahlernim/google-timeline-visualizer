from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
DATABASE_FILES = {
    "travel-journal.db",
    "travel-journal.db-shm",
    "travel-journal.db-wal",
}


def database_exclusions(path: Path):
    root = ET.parse(path).getroot()
    return {
        element.attrib["path"]
        for element in root.iter("exclude")
        if element.attrib.get("domain") == "database"
    }


def test_journal_database_is_excluded_from_legacy_cloud_backup():
    exclusions = database_exclusions(ROOT / "app/src/main/res/xml/backup_rules.xml")
    assert DATABASE_FILES <= exclusions


def test_journal_database_is_excluded_from_cloud_backup_and_device_transfer():
    root = ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot()
    for section_name in ("cloud-backup", "device-transfer"):
        section = root.find(section_name)
        exclusions = {
            element.attrib["path"]
            for element in section.findall("exclude")
            if element.attrib.get("domain") == "database"
        }
        assert DATABASE_FILES <= exclusions
