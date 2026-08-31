from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
DATABASE_FILES = {
    "travel-journal.db",
    "travel-journal.db-shm",
    "travel-journal.db-wal",
}
SENSITIVE_PREFERENCES = {
    "creations.xml",
    "timeline_source.xml",
    "video_export_state.xml",
    "journal_reminders.xml",
    "video-presets.xml",
    "display.xml",
    "trips_lab.xml",
}
SENSITIVE_FILES = {
    "timeline-imports/",
    "creation-thumbnails/",
    "pending-video-export.bin",
    "pending-video-export.tmp",
    "pending-video-destination.bin",
    "pending-video-destination.tmp",
}


def database_exclusions(path: Path):
    root = ET.parse(path).getroot()
    return {
        element.attrib["path"]
        for element in root.iter("exclude")
        if element.attrib.get("domain") == "database"
    }


def shared_preference_exclusions(root):
    return {
        element.attrib["path"]
        for element in root.iter("exclude")
        if element.attrib.get("domain") == "sharedpref"
    }


def file_exclusions(root):
    return {
        element.attrib["path"]
        for element in root.iter("exclude")
        if element.attrib.get("domain") == "file"
    }


def test_journal_database_is_excluded_from_legacy_cloud_backup():
    path = ROOT / "app/src/main/res/xml/backup_rules.xml"
    exclusions = database_exclusions(path)
    assert DATABASE_FILES <= exclusions
    root = ET.parse(path).getroot()
    assert SENSITIVE_PREFERENCES <= shared_preference_exclusions(root)
    assert SENSITIVE_FILES <= file_exclusions(root)


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
        assert SENSITIVE_PREFERENCES <= shared_preference_exclusions(section)
        assert SENSITIVE_FILES <= file_exclusions(section)
