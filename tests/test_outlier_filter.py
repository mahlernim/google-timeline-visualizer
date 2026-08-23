from datetime import datetime, timedelta
import json
from pathlib import Path

from visualizer import extract_timeline_points, filter_location_outliers


def test_clean_route_is_preserved():
    start = datetime(2026, 1, 1, 0, 0, 0)
    points = [
        {"dt": start + timedelta(hours=i), "lat": 37.0 + i * 0.1, "lon": 127.0 + i * 0.1}
        for i in range(10)
    ]
    filtered, removed = filter_location_outliers(points, mode="conservative")
    assert len(filtered) == 10
    assert removed == 0


def test_isolated_gps_spike_is_removed():
    start = datetime(2026, 1, 1, 0, 0, 0)
    points = [
        {"dt": start, "lat": 37.5, "lon": 127.0},
        # Spike jumping 8000+ km away to London and back in 10 minutes (implausible speed > 1300 km/h)
        {"dt": start + timedelta(minutes=10), "lat": 51.5, "lon": -0.1},
        {"dt": start + timedelta(minutes=20), "lat": 37.51, "lon": 127.01},
    ]
    filtered, removed = filter_location_outliers(points, mode="conservative")
    assert len(filtered) == 2
    assert removed == 1
    assert filtered[0]["lat"] == 37.5
    assert filtered[1]["lat"] == 37.51


def test_multi_point_excursion_cluster_is_removed():
    start = datetime(2026, 1, 1, 0, 0, 0)
    points = [
        {"dt": start, "lat": 37.5, "lon": 127.0},
        # 2-point spike in London
        {"dt": start + timedelta(minutes=5), "lat": 51.5, "lon": -0.1},
        {"dt": start + timedelta(minutes=10), "lat": 51.51, "lon": -0.09},
        {"dt": start + timedelta(minutes=20), "lat": 37.52, "lon": 127.02},
    ]
    filtered, removed = filter_location_outliers(points, mode="conservative")
    assert len(filtered) == 2
    assert removed == 2
    assert filtered[0]["lat"] == 37.5
    assert filtered[1]["lat"] == 37.52


def test_filter_mode_off_preserves_all_points():
    start = datetime(2026, 1, 1, 0, 0, 0)
    points = [
        {"dt": start, "lat": 37.5, "lon": 127.0},
        {"dt": start + timedelta(minutes=10), "lat": 51.5, "lon": -0.1},
        {"dt": start + timedelta(minutes=20), "lat": 37.51, "lon": 127.01},
    ]
    filtered, removed = filter_location_outliers(points, mode="off")
    assert len(filtered) == 3
    assert removed == 0


def test_outlier_fixture_file():
    fixture_path = Path(__file__).resolve().parents[1] / "test-fixtures" / "outlier-sample.json"
    with open(fixture_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    raw = extract_timeline_points(data, 2026)
    assert len(raw) == 3
    filtered, removed = filter_location_outliers(raw, mode="conservative")
    assert len(filtered) == 2
    assert removed == 1
    assert abs(filtered[0]["lat"] - 37.5665) < 1e-4
    assert abs(filtered[1]["lat"] - 37.5700) < 1e-4
