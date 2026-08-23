from datetime import date
import json

from visualizer import (
    extract_timeline_points,
    format_distance,
    parse_coordinate,
    resolve_title_template,
)


def test_takeout_object_root():
    data = {
        "semanticSegments": [
            {
                "startTime": "2025-01-01T00:00:00Z",
                "timelinePath": [
                    {"point": "37.5°, 127.0°", "time": "2025-01-01T01:00:00Z"},
                ],
                "visit": {"topCandidate": {"placeLocation": {"latLng": "37.6°, 127.1°"}}},
            }
        ]
    }
    points = extract_timeline_points(data, 2025)
    assert [(p["lat"], p["lon"]) for p in points] == [(37.6, 127.1), (37.5, 127.0)]


def test_ios_array_root_and_activity():
    data = [
        {
            "startTime": "2024-05-01T01:00:00Z",
            "endTime": "2024-05-01T02:00:00Z",
            "activity": {"start": "geo:35.1,129.1", "end": {"latLng": "geo:35.2,129.2"}},
        },
        {
            "startTime": "2024-05-01T03:00:00Z",
            "visit": {"topCandidate": {"placeLocation": "geo:35.3,129.3"}},
        },
    ]
    points = extract_timeline_points(data, 2024)
    assert [(p["lat"], p["lon"]) for p in points] == [(35.1, 129.1), (35.2, 129.2), (35.3, 129.3)]


def test_standalone_paths_are_suppressed_only_during_semantic_coverage():
    data = [
        {
            "startTime": "2026-05-11T08:00:00Z",
            "endTime": "2026-05-11T22:00:00Z",
            "activity": {"start": "10,10", "end": "20,20"},
        },
        {
            "startTime": "2026-05-12T08:00:00Z",
            "endTime": "2026-05-12T22:00:00Z",
            "activity": {"start": "20,20", "end": "10,10"},
        },
        {
            "startTime": "2026-05-11T08:00:00Z",
            "endTime": "2026-05-12T23:00:00Z",
            "timelinePath": [
                {"point": "20,20", "time": "2026-05-11T13:00:00Z"},
                {"point": "10,10", "time": "2026-05-11T17:00:00Z"},
                {"point": "10,10", "time": "2026-05-12T13:00:00Z"},
                {"point": "20,20", "time": "2026-05-12T17:00:00Z"},
                {"point": "30,30", "time": "2026-05-12T22:30:00Z"},
            ],
        },
    ]

    points = extract_timeline_points(data, 2026)
    assert [(point["dt"].isoformat(), point["lat"]) for point in points] == [
        ("2026-05-11T08:00:00+00:00", 10.0),
        ("2026-05-11T22:00:00+00:00", 20.0),
        ("2026-05-12T08:00:00+00:00", 20.0),
        ("2026-05-12T22:00:00+00:00", 10.0),
        ("2026-05-12T22:30:00+00:00", 30.0),
    ]


def test_path_detail_inside_the_same_semantic_segment_is_preserved():
    data = [{
        "startTime": "2026-01-01T00:00:00Z",
        "endTime": "2026-01-01T02:00:00Z",
        "activity": {"start": "10,10", "end": "20,20"},
        "timelinePath": [{"point": "15,15", "time": "2026-01-01T01:00:00Z"}],
    }]

    points = extract_timeline_points(data, 2026)
    assert [point["lat"] for point in points] == [10.0, 15.0, 20.0]


def test_coordinate_parser_supports_e7_and_rejects_invalid():
    assert parse_coordinate("375000000,1270000000") == (37.5, 127.0)
    assert parse_coordinate("geo:91,127") is None
    assert parse_coordinate(None) is None


def test_offset_path_timestamps_match_absolute_time_behavior():
    data = [
        {
            "startTime": "2026-01-01T00:00:00Z",
            "endTime": "2026-01-01T02:00:00Z",
            "timelinePath": [
                {"point": "37.0,127.0", "durationMinutesOffsetFromStartTime": "15"},
                {"point": "37.1,127.1", "durationMinutesOffsetFromStartTime": 60},
                {
                    "point": "37.2,127.2",
                    "time": "2026-01-01T01:30:00Z",
                    "durationMinutesOffsetFromStartTime": "5",
                },
            ],
        }
    ]

    points = extract_timeline_points(data, 2026)
    assert [point["dt"].isoformat() for point in points] == [
        "2026-01-01T00:15:00+00:00",
        "2026-01-01T01:00:00+00:00",
        "2026-01-01T01:30:00+00:00",
    ]


def test_invalid_offset_path_timestamps_are_ignored():
    data = [
        {
            "startTime": "2026-01-01T00:00:00Z",
            "endTime": "2026-01-01T01:00:00Z",
            "timelinePath": [
                {"point": "37.0,127.0", "durationMinutesOffsetFromStartTime": "-1"},
                {"point": "37.1,127.1", "durationMinutesOffsetFromStartTime": "unknown"},
                {"point": "37.2,127.2", "durationMinutesOffsetFromStartTime": "120"},
            ],
            "visit": {"topCandidate": {"placeLocation": "37.3,127.3"}},
        }
    ]

    points = extract_timeline_points(data, 2026)
    assert [(point["lat"], point["lon"]) for point in points] == [(37.3, 127.3)]


def test_date_range_filtering():
    data = [
        {
            "startTime": "2024-06-05T10:00:00Z",
            "visit": {"topCandidate": {"placeLocation": "37.1,127.1"}},
        },
        {
            "startTime": "2024-06-15T10:00:00Z",
            "visit": {"topCandidate": {"placeLocation": "37.2,127.2"}},
        },
        {
            "startTime": "2024-06-25T10:00:00Z",
            "visit": {"topCandidate": {"placeLocation": "37.3,127.3"}},
        },
        {
            "startTime": "2024-07-05T10:00:00Z",
            "visit": {"topCandidate": {"placeLocation": "37.4,127.4"}},
        },
    ]

    # Select period from June 10 to June 20
    points = extract_timeline_points(
        data, start_date=date(2024, 6, 10), end_date=date(2024, 6, 20)
    )
    assert [(p["lat"], p["lon"]) for p in points] == [(37.2, 127.2)]


def test_title_template_expansion():
    assert resolve_title_template("{year} Trips", year_label="2024", name="Alice") == "2024 Trips"
    assert resolve_title_template("{name}'s {year} Journey", year_label="2024", name="Alice") == "Alice's 2024 Journey"
    assert resolve_title_template("", fallback="My Trips") == "My Trips"


def test_distance_formatting():
    assert format_distance(100.0, "km") == "100.0 km"
    assert format_distance(100.0, "mi") == "62.1 mi"
