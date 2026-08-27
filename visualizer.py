#!/usr/bin/env python3
"""
Google Timeline Visualizer
Author: @mahlernim
Description:
    Analyzes your Google Location History (Timeline.json) and generates an
    animated video of your travels.
    Features:
    - GPS outlier and spike filtering
    - Multi-aspect ratio support (Square 1:1, Portrait 9:16, Landscape 16:9)
    - Distance-based animation speed (majestic long trips, fast commutes)
    - Dynamic Camera (Smart Zoom, Smoothing, Close-up, Episode framing)
    - Web Mercator Projection for map alignment
    - Multi-tier trail rendering and overview outro transition
    - Shareable preset tokens and distance unit conversions
"""

import argparse
import bisect
import io
import json
import math
import os
import statistics
import sys
import urllib.request
import urllib.parse
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# Third-party imports
try:
    import dateutil.parser
    import matplotlib
    # Set non-interactive backend
    matplotlib.use('Agg')
    import matplotlib.animation as animation
    import matplotlib.patches as patches
    import matplotlib.pyplot as plt
    import numpy as np
    from PIL import Image
except ImportError as e:
    print(f"Error: Missing dependency {e.name}. Please run: pip install -r requirements.txt")
    sys.exit(1)

# --- CONFIGURATION DEFAULTS ---
DEFAULT_FPS = 30
DEFAULT_DURATION = 30
DEFAULT_TAIL_KM = 500
THEME_COLOR = '#e90064'
HEAD_COLOR = '#24191d'
CARD_BG_COLOR = '#fff8fa'
TEXT_PRIMARY_COLOR = '#24191d'
TEXT_SECONDARY_COLOR = '#5c4b52'
MAP_ATTRIBUTION = '© OpenStreetMap contributors © CARTO'
TILE_URL = "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png"

OUTRO_SECONDS = 1.5
OUTRO_TRANSITION_SECONDS = 1.0
EPISODE_ARRIVAL_ZOOM_START_FRACTION = 0.65


class TimelineCliError(Exception):
    """Base class for expected command-line failures."""


class TimelineParseError(TimelineCliError):
    """Raised when a Timeline export cannot be decoded."""


class NoDataFoundError(TimelineCliError):
    """Raised when an export contains no usable points for the requested period."""


class FfmpegUnavailableError(TimelineCliError):
    """Raised when Matplotlib cannot find a configured ffmpeg executable."""


# Camera behavior matches the Android renderer defaults and options.
CAMERA_MOVEMENTS = {
    'fixed': dict(context_fraction=0.10, minimum_context_km=25.0, maximum_context_km=350.0,
                  padding=2.6, minimum_span=0.00060, zoom_out_alpha=0.0,
                  zoom_in_alpha=0.0, leg_aware=False, fixed_zoom=True),
    'steady': dict(context_fraction=1.00, minimum_context_km=650.0, maximum_context_km=650.0,
                   padding=2.8, minimum_span=0.00060, zoom_out_alpha=0.14,
                   zoom_in_alpha=0.035, leg_aware=False, fixed_zoom=False),
    'dynamic': dict(context_fraction=0.10, minimum_context_km=100.0, maximum_context_km=350.0,
                    padding=2.2, minimum_span=0.00045, zoom_out_alpha=0.24,
                    zoom_in_alpha=0.06, leg_aware=True, fixed_zoom=False),
    'close_up': dict(context_fraction=0.035, minimum_context_km=6.0, maximum_context_km=120.0,
                     padding=1.7, minimum_span=0.00030, zoom_out_alpha=0.30,
                     zoom_in_alpha=0.075, leg_aware=True, fixed_zoom=False),
}

COMPRESSION_EXPONENTS = {
    'off': 1.00,
    'gentle': 0.92,
    'balanced': 0.85,
    'strong': 0.75,
    'stronger': 0.65,
}

TRIP_DETECTION_MULTIPLIERS = {
    'conservative': 1.35,
    'balanced': 1.00,
    'sensitive': 0.70,
}

LOCAL_FRAMING_SETTINGS = {
    'off': dict(enabled=False, padding_multiplier=1.00),
    'balanced': dict(enabled=True, padding_multiplier=1.00),
    'close': dict(enabled=True, padding_multiplier=0.78),
}

TRANSFER_PADDING = 2.8
CAMERA_TRACK_SAMPLES = 480
CAMERA_DEAD_ZONE_HALF = 0.20
FIXED_ZOOM_PERCENTILE = 0.80
VISUAL_ZOOM_WORK_WEIGHT = 0.35
MIN_TRANSFER_THRESHOLD_KM = 60.0
MAX_TRANSFER_THRESHOLD_KM = 120.0
TRANSFER_TO_TYPICAL_RATIO = 3.0
DEVIATION_MULTIPLIER = 6.0
MIN_CONTEXT_KM = 15.0

# Web Mercator Constants
R_EARTH = 6378137.0
MAX_EXTENT = 20037508.342789244
KM_TO_MILES = 0.621371192237334


# --- PROJECTION LOGIC ---

def latlon_to_meters(lat: float, lon: float) -> Tuple[float, float]:
    lat_clamped = max(-85.05112878, min(85.05112878, lat))
    x = R_EARTH * math.radians(lon)
    y = R_EARTH * math.log(math.tan(math.pi / 4 + math.radians(lat_clamped) / 2))
    return x, y


def meters_to_latlon(x: float, y: float) -> Tuple[float, float]:
    lon = math.degrees(x / R_EARTH)
    lat = math.degrees(2 * math.atan(math.exp(y / R_EARTH)) - math.pi / 2)
    return lat, lon


def haversine_dist(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = 6371.0088
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (
        math.sin(dlat / 2) ** 2
        + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    )
    c = 2 * math.asin(min(1.0, math.sqrt(a)))
    return R * c


def interpolate_latlon(
    lat1: float, lon1: float, lat2: float, lon2: float, fraction: float
) -> Tuple[float, float]:
    """Return a great-circle position so long hops sweep instead of teleporting."""
    if fraction <= 0:
        return lat1, lon1
    if fraction >= 1:
        return lat2, lon2
    p1, l1 = math.radians(lat1), math.radians(lon1)
    p2, l2 = math.radians(lat2), math.radians(lon2)
    ax, ay, az = math.cos(p1) * math.cos(l1), math.cos(p1) * math.sin(l1), math.sin(p1)
    bx, by, bz = math.cos(p2) * math.cos(l2), math.cos(p2) * math.sin(l2), math.sin(p2)
    dot = max(-1.0, min(1.0, ax * bx + ay * by + az * bz))
    omega = math.acos(dot)
    if math.sin(omega) < 1e-8:
        left, right = 1 - fraction, fraction
    else:
        left = math.sin((1 - fraction) * omega) / math.sin(omega)
        right = math.sin(fraction * omega) / math.sin(omega)
    x, y, z = left * ax + right * bx, left * ay + right * by, left * az + right * bz
    return math.degrees(math.atan2(z, math.sqrt(x * x + y * y))), math.degrees(math.atan2(y, x))


def position_at_distance(
    cum_dist: List[float], lats: List[float], lons: List[float], distance_km: float
) -> Tuple[float, float]:
    if not cum_dist:
        raise ValueError('A route needs at least one point')
    if len(cum_dist) == 1 or cum_dist[-1] <= 0:
        return lats[0], lons[0]
    distance = max(0.0, min(cum_dist[-1], distance_km))
    to_index = min(max(bisect.bisect_left(cum_dist, distance), 1), len(cum_dist) - 1)
    segment = cum_dist[to_index] - cum_dist[to_index - 1]
    fraction = 0.0 if segment <= 0 else (distance - cum_dist[to_index - 1]) / segment
    return interpolate_latlon(
        lats[to_index - 1], lons[to_index - 1], lats[to_index], lons[to_index], fraction,
    )


# --- OUTLIER FILTERING ---

def _speed_km_per_hour(from_point: Dict[str, Any], to_point: Dict[str, Any], distance_km: float) -> float:
    delta_seconds = (to_point['dt'] - from_point['dt']).total_seconds()
    if delta_seconds <= 0:
        return float('inf')
    return distance_km / (delta_seconds / 3600.0)


def _is_suspicious_excursion(
    before: Dict[str, Any], points: List[Dict[str, Any]], start: int, end: int, after: Dict[str, Any]
) -> bool:
    window = after['dt'] - before['dt']
    if window.total_seconds() < 0 or window > timedelta(hours=12):
        return False
    if haversine_dist(before['lat'], before['lon'], after['lat'], after['lon']) > 200.0:
        return False
    first = points[start]
    last = points[end]
    ingress_km = haversine_dist(before['lat'], before['lon'], first['lat'], first['lon'])
    egress_km = haversine_dist(last['lat'], last['lon'], after['lat'], after['lon'])
    if ingress_km < 500.0 or egress_km < 500.0:
        return False
    if _speed_km_per_hour(before, first, ingress_km) <= 1300.0:
        return False
    if _speed_km_per_hour(last, after, egress_km) <= 1300.0:
        return False
    for idx in range(start, end + 1):
        candidate = points[idx]
        if haversine_dist(first['lat'], first['lon'], candidate['lat'], candidate['lon']) > 200.0:
            return False
        if (
            haversine_dist(before['lat'], before['lon'], candidate['lat'], candidate['lon']) < 500.0
            or haversine_dist(candidate['lat'], candidate['lon'], after['lat'], after['lon']) < 500.0
        ):
            return False
    return True


def _suspicious_run_end(
    points: List[Dict[str, Any]], before: Dict[str, Any], start: int
) -> Optional[int]:
    latest_end = min(start + 3 - 1, len(points) - 2)
    for end in range(latest_end, start - 1, -1):
        if _is_suspicious_excursion(before, points, start, end, points[end + 1]):
            return end
    return None


def filter_location_outliers(
    points: List[Dict[str, Any]], mode: str = "conservative"
) -> Tuple[List[Dict[str, Any]], int]:
    """Filter impossible GPS teleport spikes using sliding window heuristics."""
    if mode == "off" or len(points) < 3:
        return points, 0
    kept = [points[0]]
    removed_count = 0
    index = 1
    while index < len(points) - 1:
        run_end = _suspicious_run_end(points, kept[-1], index)
        if run_end is None:
            kept.append(points[index])
            index += 1
        else:
            removed_count += run_end - index + 1
            index = run_end + 1
    kept.append(points[-1])
    return kept, removed_count


# --- TRIP DETECTION AND PACING ---

def transfer_threshold_km(cum_dist: List[float], trip_detection: str = 'balanced') -> float:
    multiplier = TRIP_DETECTION_MULTIPLIERS.get(trip_detection, 1.00)
    hops = [after - before for before, after in zip(cum_dist, cum_dist[1:])]
    ordinary = sorted(hop for hop in hops if 0 < hop < MAX_TRANSFER_THRESHOLD_KM)
    if not ordinary:
        return MAX_TRANSFER_THRESHOLD_KM * multiplier
    typical = statistics.median(ordinary)
    deviation = statistics.median(sorted(abs(hop - typical) for hop in ordinary))
    threshold = max(
        MIN_TRANSFER_THRESHOLD_KM,
        typical * TRANSFER_TO_TYPICAL_RATIO,
        typical + deviation * DEVIATION_MULTIPLIER,
    )
    return min(MAX_TRANSFER_THRESHOLD_KM, threshold) * multiplier


def build_legs(cum_dist: List[float], threshold_km: Optional[float] = None) -> List[Tuple[float, float, bool]]:
    if len(cum_dist) < 2 or cum_dist[-1] <= 0:
        return []
    threshold = threshold_km if threshold_km is not None else transfer_threshold_km(cum_dist)
    legs = []
    local_start = 0.0
    for before, after in zip(cum_dist, cum_dist[1:]):
        if after - before < max(1.0, threshold):
            continue
        if before > local_start:
            legs.append((local_start, before, False))
        legs.append((before, after, True))
        local_start = after
    if cum_dist[-1] > local_start:
        legs.append((local_start, cum_dist[-1], False))
    return legs


def leg_at(legs: List[Tuple[float, float, bool]], distance_km: float, total_km: float) -> Tuple[float, float, bool]:
    if not legs:
        return 0.0, total_km, False
    starts = [leg[0] for leg in legs]
    index = bisect.bisect_right(starts, max(0.0, min(total_km, distance_km))) - 1
    return legs[max(0, min(index, len(legs) - 1))]


def _endpoint_slope(first_width: float, second_width: float, first: float, second: float) -> float:
    slope = ((2 * first_width + second_width) * first - first_width * second) / (first_width + second_width)
    if slope <= 0:
        return 0.0
    return min(slope, 3 * first)


def _monotone_slopes(x_values: List[float], y_values: List[float]) -> List[float]:
    count = len(x_values) - 1
    delta = [(y_values[i + 1] - y_values[i]) / (x_values[i + 1] - x_values[i]) for i in range(count)]
    if count == 1:
        return [delta[0], delta[0]]
    slopes = [0.0] * len(x_values)
    slopes[0] = _endpoint_slope(x_values[1] - x_values[0], x_values[2] - x_values[1], delta[0], delta[1])
    for index in range(1, len(x_values) - 1):
        before_width = x_values[index] - x_values[index - 1]
        after_width = x_values[index + 1] - x_values[index]
        weight_before = 2 * after_width + before_width
        weight_after = after_width + 2 * before_width
        if delta[index - 1] <= 0 or delta[index] <= 0:
            slopes[index] = 0.0
        else:
            slopes[index] = (weight_before + weight_after) / (
                weight_before / delta[index - 1] + weight_after / delta[index]
            )
    slopes[-1] = _endpoint_slope(
        x_values[-1] - x_values[-2], x_values[-2] - x_values[-3], delta[-1], delta[-2],
    )
    return slopes


def build_journey_timing(
    cum_dist: List[float], compression: str = 'balanced', trip_detection: str = 'balanced'
):
    """Return a progress-to-raw-distance mapper without changing route geometry."""
    total_km = cum_dist[-1] if cum_dist else 0.0
    exponent = COMPRESSION_EXPONENTS.get(compression, 0.85)
    if compression == 'off' or len(cum_dist) < 2 or total_km <= 0:
        return lambda progress: total_km * max(0.0, min(1.0, progress))
    distances = [0.0]
    effective = [0.0]
    effective_total = 0.0
    for before, after in zip(cum_dist, cum_dist[1:]):
        segment = after - before
        if segment <= 0:
            continue
        effective_total += segment ** exponent
        distances.append(after)
        effective.append(effective_total)
    if effective_total <= 0 or len(distances) < 2:
        return lambda progress: total_km * max(0.0, min(1.0, progress))
    x_values = [value / effective_total for value in effective]
    slopes = _monotone_slopes(x_values, distances)

    def distance_at(progress: float) -> float:
        elapsed = max(0.0, min(1.0, progress))
        to_index = min(max(bisect.bisect_left(x_values, elapsed), 1), len(x_values) - 1)
        from_index = to_index - 1
        width = x_values[to_index] - x_values[from_index]
        t = 0.0 if width <= 0 else (elapsed - x_values[from_index]) / width
        t2, t3 = t * t, t * t * t
        return (
            (2 * t3 - 3 * t2 + 1) * distances[from_index]
            + (t3 - 2 * t2 + t) * width * slopes[from_index]
            + (-2 * t3 + 3 * t2) * distances[to_index]
            + (t3 - t2) * width * slopes[to_index]
        )

    return distance_at


# --- EASING FUNCTIONS ---

def ease_out_cubic(t: float) -> float:
    t_clamped = max(0.0, min(1.0, t))
    return 1.0 - (1.0 - t_clamped) ** 3


def ease_in_out_cubic(t: float) -> float:
    t_clamped = max(0.0, min(1.0, t))
    if t_clamped < 0.5:
        return 4.0 * t_clamped ** 3
    return 1.0 - ((-2.0 * t_clamped + 2.0) ** 3) / 2.0


def smoothstep(value: float) -> float:
    t = max(0.0, min(1.0, value))
    return t * t * (3.0 - 2.0 * t)


def lerp(a: float, b: float, fraction: float) -> float:
    return a + (b - a) * fraction


# --- MAP TILES (Web Mercator) ---

def meters_to_tile(mx: float, my: float, zoom: int) -> Tuple[int, int]:
    n = 2.0 ** zoom
    norm_x = (mx + MAX_EXTENT) / (2 * MAX_EXTENT)
    norm_y = 1.0 - (my + MAX_EXTENT) / (2 * MAX_EXTENT)
    xtile = int(norm_x * n)
    ytile = int(norm_y * n)
    return xtile, ytile


def tile_to_bounds_meters(xtile: int, ytile: int, zoom: int) -> Tuple[float, float, float, float]:
    n = 2.0 ** zoom
    tile_size = (2 * MAX_EXTENT) / n
    min_x = -MAX_EXTENT + xtile * tile_size
    max_x = min_x + tile_size
    max_y = MAX_EXTENT - ytile * tile_size
    min_y = max_y - tile_size
    return min_x, max_x, min_y, max_y


TILE_CACHE: Dict[Tuple[int, int, int], Image.Image] = {}
_tile_fetch_failures = 0


def carto_tile_url(x: int, y: int, z: int, api_key: Optional[str] = None) -> str:
    url = TILE_URL.format(z=z, x=x, y=y)
    key = os.environ.get('CARTO_BASEMAP_API_KEY', '') if api_key is None else api_key
    if not key.strip():
        return url
    return f"{url}?{urllib.parse.urlencode({'key': key.strip()})}"


def fetch_tile_img(x: int, y: int, z: int) -> Image.Image:
    global _tile_fetch_failures
    key = (x, y, z)
    if key in TILE_CACHE:
        return TILE_CACHE[key]
    url = carto_tile_url(x, y, z)
    try:
        req = urllib.request.Request(url, headers={'User-Agent': "Mozilla/5.0"})
        with urllib.request.urlopen(req) as response:
            img = Image.open(io.BytesIO(response.read())).convert('RGB')
            TILE_CACHE[key] = img
            return img
    except Exception as exc:
        _tile_fetch_failures += 1
        if _tile_fetch_failures <= 5:
            print(f"Warning: Could not fetch tile {url}: {exc}", file=sys.stderr)
        fallback = Image.new('RGB', (256, 256), (240, 240, 240))
        return fallback


def get_map_image(
    x_center: float,
    y_center: float,
    span_x: float,
    span_y: Optional[float] = None,
    width_px: int = 800,
    height_px: Optional[int] = None,
) -> Tuple[Image.Image, Tuple[float, float, float, float]]:
    if span_y is None:
        span_y = span_x
    if height_px is None:
        height_px = width_px

    target_val = (2 * MAX_EXTENT * width_px) / (256 * max(span_x, 1.0))
    zoom = int(math.log2(target_val)) if target_val > 0 else 2
    zoom = max(2, min(15, zoom))

    min_x = x_center - span_x / 2
    max_x = x_center + span_x / 2
    min_y = y_center - span_y / 2
    max_y = y_center + span_y / 2

    xt_min, yt_min = meters_to_tile(min_x, max_y, zoom)
    xt_max, yt_max = meters_to_tile(max_x, min_y, zoom)

    if yt_min > yt_max:
        yt_min, yt_max = yt_max, yt_min

    x_tiles = xt_max - xt_min + 1
    y_tiles = yt_max - yt_min + 1

    while x_tiles * y_tiles > 36 and zoom > 2:
        zoom -= 1
        xt_min, yt_min = meters_to_tile(min_x, max_y, zoom)
        xt_max, yt_max = meters_to_tile(max_x, min_y, zoom)
        if yt_min > yt_max:
            yt_min, yt_max = yt_max, yt_min
        x_tiles = xt_max - xt_min + 1
        y_tiles = yt_max - yt_min + 1

    tile_w, tile_h = 256, 256
    stitched = Image.new('RGB', (x_tiles * tile_w, y_tiles * tile_h), (242, 237, 240))

    tl_min_x, tl_max_x, tl_min_y, tl_max_y = tile_to_bounds_meters(xt_min, yt_min, zoom)
    br_min_x, br_max_x, br_min_y, br_max_y = tile_to_bounds_meters(xt_max, yt_max, zoom)

    final_min_x = tl_min_x
    final_max_x = br_max_x
    final_max_y = tl_max_y
    final_min_y = br_min_y

    for x in range(x_tiles):
        for y in range(y_tiles):
            img = fetch_tile_img(xt_min + x, yt_min + y, zoom)
            stitched.paste(img, (x * tile_w, y * tile_h))

    return stitched, (final_min_x, final_max_x, final_min_y, final_max_y)


# --- DATA PROCESSING ---

def parse_coordinate(value: Any) -> Optional[Tuple[float, float]]:
    """Parse coordinate values used by Android, iOS, and Takeout exports."""
    if isinstance(value, dict):
        value = value.get('latLng') or value.get('point')
    if not isinstance(value, str) or not value.strip():
        return None
    cleaned = value.strip().removeprefix('geo:').split('?', 1)[0].replace('°', '').replace(' ', '')
    parts = cleaned.split(',')
    if len(parts) < 2:
        return None
    try:
        lat, lon = float(parts[0]), float(parts[1])
    except ValueError:
        return None
    if abs(lat) > 1_000_000 or abs(lon) > 1_000_000:
        lat /= 10_000_000
        lon /= 10_000_000
    if not (-85.05112878 <= lat <= 85.05112878 and -180 <= lon <= 180):
        return None
    return lat, lon


def _date_in_range(
    dt: datetime,
    year: Optional[int] = None,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
) -> bool:
    if start_date is not None and end_date is not None:
        return start_date <= dt.date() <= end_date
    if start_date is not None:
        return dt.date() >= start_date
    if end_date is not None:
        return dt.date() <= end_date
    if year is not None:
        return dt.year == year
    return True


def extract_timeline_points(
    data: Any,
    year: Optional[int] = None,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
) -> List[Dict[str, Any]]:
    """Return normalized points for a period from supported Timeline JSON roots."""
    if isinstance(data, list):
        segments = data
    elif isinstance(data, dict):
        segments = data.get('semanticSegments', [])
    else:
        raise ValueError('Timeline JSON must start with an object or array')

    canonical_points = []
    standalone_path_points = []
    semantic_intervals = []

    def parse_timestamp(time_value: Any) -> Optional[datetime]:
        if isinstance(time_value, datetime):
            return time_value
        if not time_value:
            return None
        try:
            return dateutil.parser.parse(time_value)
        except (TypeError, ValueError, OverflowError):
            return None

    def path_timestamp(path_point: Dict[str, Any], start_value: Any, end_value: Any) -> Optional[datetime]:
        absolute = parse_timestamp(path_point.get('time'))
        if absolute is not None:
            return absolute
        offset_value = path_point.get('durationMinutesOffsetFromStartTime')
        if isinstance(offset_value, bool):
            return None
        try:
            offset = int(offset_value)
        except (TypeError, ValueError, OverflowError):
            return None
        if offset < 0:
            return None
        start = parse_timestamp(start_value)
        if start is None:
            return None
        try:
            timestamp = start + timedelta(minutes=offset)
        except OverflowError:
            return None
        end = parse_timestamp(end_value)
        if end is not None and timestamp > end + timedelta(minutes=1):
            return None
        return timestamp

    def add_point(output: List[Dict[str, Any]], timestamp: Optional[datetime], coordinate: Optional[Tuple[float, float]]) -> bool:
        if timestamp is None or coordinate is None:
            return False
        if _date_in_range(timestamp, year=year, start_date=start_date, end_date=end_date):
            output.append({'dt': timestamp, 'lat': coordinate[0], 'lon': coordinate[1]})
        return True

    for seg in segments:
        if not isinstance(seg, dict):
            continue
        start_time = seg.get('startTime')
        end_time = seg.get('endTime')

        activity = seg.get('activity')
        visit = seg.get('visit')
        candidate = visit.get('topCandidate') if isinstance(visit, dict) else None
        activity_start = activity.get('start') if isinstance(activity, dict) else None
        activity_end = activity.get('end') if isinstance(activity, dict) else None
        visit_location = candidate.get('placeLocation') if isinstance(candidate, dict) else None
        activity_start_coordinate = parse_coordinate(activity_start)
        activity_end_coordinate = parse_coordinate(activity_end)
        visit_coordinate = parse_coordinate(visit_location)
        has_usable_semantic_record = any(
            coord is not None
            for coord in (activity_start_coordinate, activity_end_coordinate, visit_coordinate)
        )
        path_output = canonical_points if has_usable_semantic_record else standalone_path_points

        for path_point in seg.get('timelinePath', []):
            if isinstance(path_point, dict):
                add_point(
                    path_output,
                    path_timestamp(path_point, start_time, end_time),
                    parse_coordinate(path_point.get('point')),
                )

        start = parse_timestamp(start_time) if has_usable_semantic_record else None
        end = parse_timestamp(end_time) if has_usable_semantic_record else None
        if isinstance(activity, dict):
            add_point(canonical_points, start, activity_start_coordinate)
            add_point(canonical_points, end, activity_end_coordinate)

        if isinstance(candidate, dict):
            add_point(canonical_points, start, visit_coordinate)

        if has_usable_semantic_record:
            if (
                start is not None
                and end is not None
                and start.utcoffset() is not None
                and end.utcoffset() is not None
                and end >= start
            ):
                semantic_intervals.append((start, end))

    semantic_intervals.sort(key=lambda interval: interval[0])
    merged_intervals = []
    for start, end in semantic_intervals:
        if not merged_intervals or start > merged_intervals[-1][1]:
            merged_intervals.append([start, end])
        elif end > merged_intervals[-1][1]:
            merged_intervals[-1][1] = end

    interval_index = 0
    for point in sorted(standalone_path_points, key=lambda item: item['dt']):
        timestamp = point['dt']
        if timestamp.utcoffset() is None:
            canonical_points.append(point)
            continue
        while interval_index < len(merged_intervals) and merged_intervals[interval_index][1] < timestamp:
            interval_index += 1
        covered = (
            interval_index < len(merged_intervals)
            and merged_intervals[interval_index][0] <= timestamp <= merged_intervals[interval_index][1]
        )
        if not covered:
            canonical_points.append(point)

    unique = {
        (point['dt'], point['lat'], point['lon']): point
        for point in canonical_points
    }
    return sorted(unique.values(), key=lambda point: point['dt'])


def extract_journal_route_points(
    data: Any,
    year: Optional[int] = None,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
    maximum_accuracy_meters: float = 100.0,
) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
    """Build a Journal-style detailed-first route for the selected period.

    Detailed positions use the same core filters and 30-minute coverage-island
    boundary as Journal Lab v13. Semantic geometry is retained only outside
    accepted detailed coverage.
    """
    semantic = extract_timeline_points(data, year=year, start_date=start_date, end_date=end_date)
    raw_signals = data.get('rawSignals', []) if isinstance(data, dict) else []
    detailed = []
    accuracy_rejected = 0
    for raw_signal in raw_signals:
        position = raw_signal.get('position') if isinstance(raw_signal, dict) else None
        if not isinstance(position, dict):
            continue
        coordinate = parse_coordinate(position.get('LatLng') or position.get('latLng'))
        try:
            timestamp = dateutil.parser.parse(position.get('timestamp'))
            accuracy = float(position.get('accuracyMeters'))
        except (TypeError, ValueError, OverflowError):
            continue
        if coordinate is None or not math.isfinite(accuracy) or accuracy < 0:
            continue
        if not _date_in_range(timestamp, year=year, start_date=start_date, end_date=end_date):
            continue
        if accuracy > maximum_accuracy_meters:
            accuracy_rejected += 1
            continue
        detailed.append({
            'dt': timestamp,
            'lat': coordinate[0],
            'lon': coordinate[1],
            'accuracy': accuracy,
        })

    detailed.sort(key=lambda point: point['dt'])
    normalized = []
    group_start = 0
    while group_start < len(detailed):
        group_end = group_start + 1
        while group_end < len(detailed) and detailed[group_end]['dt'] == detailed[group_start]['dt']:
            group_end += 1
        by_coordinate = {}
        for point in detailed[group_start:group_end]:
            key = (point['lat'], point['lon'])
            if key not in by_coordinate or point['accuracy'] < by_coordinate[key]['accuracy']:
                by_coordinate[key] = point
        if len(by_coordinate) == 1:
            normalized.append(next(iter(by_coordinate.values())))
        group_start = group_end

    without_spikes = []
    if len(normalized) < 3:
        without_spikes = normalized
    else:
        without_spikes.append(normalized[0])
        for index in range(1, len(normalized) - 1):
            before = without_spikes[-1]
            candidate = normalized[index]
            after = normalized[index + 1]
            window = after['dt'] - before['dt']
            rejoin_tolerance = max(0.2, (before['accuracy'] + after['accuracy']) * 2.0 / 1000.0)
            ingress = haversine_dist(before['lat'], before['lon'], candidate['lat'], candidate['lon'])
            egress = haversine_dist(candidate['lat'], candidate['lon'], after['lat'], after['lon'])
            minimum_spike = max(0.5, candidate['accuracy'] * 5.0 / 1000.0)
            ingress_hours = (candidate['dt'] - before['dt']).total_seconds() / 3600.0
            egress_hours = (after['dt'] - candidate['dt']).total_seconds() / 3600.0
            is_spike = (
                timedelta(0) <= window <= timedelta(minutes=20)
                and haversine_dist(before['lat'], before['lon'], after['lat'], after['lon']) <= rejoin_tolerance
                and ingress >= minimum_spike
                and egress >= minimum_spike
                and (ingress_hours <= 0 or ingress / ingress_hours > 250.0)
                and (egress_hours <= 0 or egress / egress_hours > 250.0)
            )
            if not is_spike:
                without_spikes.append(candidate)
        without_spikes.append(normalized[-1])

    stabilized = []
    for candidate in without_spikes:
        if not stabilized:
            stabilized.append(candidate)
            continue
        previous = stabilized[-1]
        elapsed = candidate['dt'] - previous['dt']
        uncertainty_km = max(0.025, (previous['accuracy'] + candidate['accuracy']) / 1000.0)
        overlaps = (
            timedelta(0) <= elapsed <= timedelta(minutes=10)
            and haversine_dist(previous['lat'], previous['lon'], candidate['lat'], candidate['lon']) <= uncertainty_km
        )
        if overlaps:
            if candidate['accuracy'] < previous['accuracy']:
                stabilized[-1] = candidate
        else:
            stabilized.append(candidate)

    islands = []
    for point in stabilized:
        if not islands or point['dt'] - islands[-1][-1]['dt'] > timedelta(minutes=30):
            islands.append([point])
        else:
            islands[-1].append(point)
    coverage = [(island[0]['dt'], island[-1]['dt']) for island in islands]
    semantic_backup = []
    island_index = 0
    for point in semantic:
        while island_index < len(coverage) and coverage[island_index][1] < point['dt']:
            island_index += 1
        covered = (
            island_index < len(coverage)
            and coverage[island_index][0] <= point['dt'] <= coverage[island_index][1]
        )
        if not covered:
            semantic_backup.append(point)

    combined = sorted(stabilized + semantic_backup, key=lambda point: point['dt'])
    return combined, {
        'detailed_input': len(detailed) + accuracy_rejected,
        'detailed_usable': len(stabilized),
        'detailed_islands': len(islands),
        'semantic_backup': len(semantic_backup),
    }


def parse_timeline(
    input_path: Path,
    year: Optional[int] = None,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
    location_filter: str = "conservative",
    route_source: str = "semantic",
) -> Tuple[List[datetime], List[float], List[float], List[float], List[float], List[float]]:
    print(f"Loading {input_path}...")
    try:
        with open(input_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise TimelineParseError(f"Could not read Timeline JSON: {error}") from error

    if route_source == 'journal':
        raw_points, journal_stats = extract_journal_route_points(
            data,
            year=year,
            start_date=start_date,
            end_date=end_date,
        )
        print(
            "Journal route: "
            f"{journal_stats['detailed_usable']} detailed points in "
            f"{journal_stats['detailed_islands']} islands; "
            f"{journal_stats['semantic_backup']} semantic backup points."
        )
    else:
        raw_points = extract_timeline_points(data, year=year, start_date=start_date, end_date=end_date)
    if not raw_points:
        period_str = str(year) if year is not None else f"{start_date} to {end_date}"
        raise NoDataFoundError(f"No data points found for period {period_str}.")

    filtered_points, removed_count = filter_location_outliers(raw_points, mode=location_filter)
    if removed_count > 0:
        print(f"Location outlier filter removed {removed_count} suspicious points.")

    if not filtered_points:
        period_str = str(year) if year is not None else f"{start_date} to {end_date}"
        raise NoDataFoundError(f"No usable data points found for period {period_str} after filtering.")

    print(f"Found {len(filtered_points)} usable points.")

    timestamps = []
    lats = []
    lons = []
    xs = []
    ys = []

    for p in filtered_points:
        timestamps.append(p['dt'])
        lats.append(p['lat'])
        lons.append(p['lon'])
        x, y = latlon_to_meters(p['lat'], p['lon'])
        xs.append(x)
        ys.append(y)

    cum_dist = [0.0]
    total = 0.0
    for i in range(1, len(lats)):
        d = haversine_dist(lats[i - 1], lons[i - 1], lats[i], lons[i])
        total += d
        cum_dist.append(total)

    return timestamps, xs, ys, cum_dist, lats, lons


# --- CAMERA TRACKING ENGINE ---

def raw_camera_sample(
    cum_dist: List[float],
    xs: List[float],
    ys: List[float],
    lats: List[float],
    lons: List[float],
    distance_km: float,
    movement_name: str,
    legs: List[Tuple[float, float, bool]],
    aspect: float = 1.0,
    local_framing: str = "balanced",
) -> Tuple[float, float, float, float]:
    movement = CAMERA_MOVEMENTS[movement_name]
    framing = LOCAL_FRAMING_SETTINGS.get(local_framing, LOCAL_FRAMING_SETTINGS['balanced'])
    total_km = cum_dist[-1]
    latitude, longitude = position_at_distance(cum_dist, lats, lons, distance_km)
    center_x, center_y = latlon_to_meters(latitude, longitude)
    proportional_context = max(
        movement['minimum_context_km'],
        min(movement['maximum_context_km'], total_km * movement['context_fraction']),
    )
    leg = leg_at(legs, distance_km, total_km) if movement['leg_aware'] else None
    leg_index = legs.index(leg) if (leg is not None and leg in legs) else -1
    next_local_leg = legs[leg_index + 1] if (0 <= leg_index < len(legs) - 1 and not legs[leg_index + 1][2]) else None

    transfer_arrival_blend = 0.0
    if framing['enabled'] and leg is not None and leg[2] and next_local_leg is not None:
        leg_len = leg[1] - leg[0]
        if leg_len > 0:
            frac = (distance_km - leg[0]) / leg_len
            transfer_arrival_blend = smoothstep((frac - EPISODE_ARRIVAL_ZOOM_START_FRACTION) / (1.0 - EPISODE_ARRIVAL_ZOOM_START_FRACTION))

    if leg is not None and leg[2]:
        leg_len = leg[1] - leg[0]
        if transfer_arrival_blend > 0:
            arrival_ctx = min(proportional_context, (next_local_leg[1] - next_local_leg[0]) if next_local_leg else proportional_context)
            arrival_ctx = max(MIN_CONTEXT_KM, arrival_ctx)
            context = math.exp(lerp(math.log(max(MIN_CONTEXT_KM, leg_len)), math.log(arrival_ctx), transfer_arrival_blend))
            padding = lerp(TRANSFER_PADDING, movement['padding'] * framing['padding_multiplier'], transfer_arrival_blend)
        else:
            context = leg_len
            padding = TRANSFER_PADDING
        range_start = leg[0]
        lookahead_limit = leg[1]
    else:
        padding = movement['padding'] * (framing['padding_multiplier'] if framing['enabled'] else 1.0)
        range_start = leg[0] if leg is not None else 0.0
        lookahead_limit = total_km
        context = proportional_context

    tail_distance = max(range_start, distance_km - context)
    lookahead_distance = min(lookahead_limit, distance_km + context)
    start_index = bisect.bisect_left(cum_dist, tail_distance)
    end_index = bisect.bisect_right(cum_dist, lookahead_distance)
    focus_x = list(xs[start_index:end_index])
    focus_y = list(ys[start_index:end_index])
    for edge in (tail_distance, distance_km, lookahead_distance):
        edge_lat, edge_lon = position_at_distance(cum_dist, lats, lons, edge)
        edge_x, edge_y = latlon_to_meters(edge_lat, edge_lon)
        focus_x.append(edge_x)
        focus_y.append(edge_y)

    minimum_span = movement['minimum_span'] * (2 * MAX_EXTENT)
    content_span_x = max(focus_x) - min(focus_x)
    content_span_y = max(focus_y) - min(focus_y)
    span_y = max(content_span_y * padding, (content_span_x * padding) / max(0.1, aspect), minimum_span)
    span_y = min(span_y, 0.72 * 2 * MAX_EXTENT)
    span_x = span_y * aspect
    return center_x, center_y, span_x, span_y


def build_camera_track(
    cum_dist: List[float],
    xs: List[float],
    ys: List[float],
    lats: List[float],
    lons: List[float],
    movement_name: str,
    distance_at: Any,
    aspect: float = 1.0,
    trip_detection: str = 'balanced',
    local_framing: str = 'balanced',
) -> List[Tuple[float, float, float, float]]:
    movement = CAMERA_MOVEMENTS[movement_name]
    threshold_km = transfer_threshold_km(cum_dist, trip_detection)
    legs = build_legs(cum_dist, threshold_km)
    raw = [
        raw_camera_sample(
            cum_dist, xs, ys, lats, lons,
            distance_at(sample / CAMERA_TRACK_SAMPLES),
            movement_name, legs, aspect, local_framing,
        )
        for sample in range(CAMERA_TRACK_SAMPLES + 1)
    ]
    fixed_span_y = None
    if movement['fixed_zoom']:
        spans_y = sorted(sample[3] for sample in raw)
        fixed_span_y = spans_y[int((len(spans_y) - 1) * FIXED_ZOOM_PERCENTILE)]

    track = []
    for raw_x, raw_y, raw_span_x, raw_span_y in raw:
        target_span_y = fixed_span_y if fixed_span_y is not None else raw_span_y
        target_span_x = target_span_y * aspect
        if not track:
            track.append((raw_x, raw_y, target_span_x, target_span_y))
            continue
        center_x, center_y, prev_span_x, prev_span_y = track[-1]
        alpha = movement['zoom_out_alpha'] if target_span_y > prev_span_y else movement['zoom_in_alpha']
        span_y = target_span_y if movement['fixed_zoom'] else math.exp(
            math.log(prev_span_y) + (math.log(target_span_y) - math.log(prev_span_y)) * alpha,
        )
        span_x = span_y * aspect
        dead_half_x = span_x * CAMERA_DEAD_ZONE_HALF
        dead_half_y = span_y * CAMERA_DEAD_ZONE_HALF
        if raw_x < center_x - dead_half_x:
            center_x = raw_x + dead_half_x
        elif raw_x > center_x + dead_half_x:
            center_x = raw_x - dead_half_x
        if raw_y < center_y - dead_half_y:
            center_y = raw_y + dead_half_y
        elif raw_y > center_y + dead_half_y:
            center_y = raw_y - dead_half_y
        track.append((center_x, center_y, span_x, span_y))
    return track


def camera_at(
    track: List[Tuple[float, float, float, float]], progress: float
) -> Tuple[float, float, float, float]:
    position = max(0.0, min(1.0, progress)) * (len(track) - 1)
    from_index = int(math.floor(position))
    to_index = min(from_index + 1, len(track) - 1)
    fraction = position - from_index
    before, after = track[from_index], track[to_index]
    center_x = before[0] + (after[0] - before[0]) * fraction
    center_y = before[1] + (after[1] - before[1]) * fraction
    span_x = math.exp(math.log(before[2]) + (math.log(after[2]) - math.log(before[2])) * fraction)
    span_y = math.exp(math.log(before[3]) + (math.log(after[3]) - math.log(before[3])) * fraction)
    return center_x, center_y, span_x, span_y


def build_visual_journey_timing(
    cum_dist: List[float],
    xs: List[float],
    ys: List[float],
    lats: List[float],
    lons: List[float],
    movement_name: str,
    aspect: float = 1.0,
    trip_detection: str = 'balanced',
    local_framing: str = 'balanced',
    include_zoom_work: bool = False,
):
    """Map elapsed progress to route distance using motion measured in viewport units.

    The provisional camera is built over uniform geographic distance. Each interval
    then receives time according to how much projected ground crosses the viewport.
    The optional zoom term also budgets time for scale changes, which reduces route
    travel while the camera is closing in without changing the selected duration.
    """
    total_km = cum_dist[-1] if cum_dist else 0.0
    if len(cum_dist) < 2 or total_km <= 0:
        return lambda progress: total_km * max(0.0, min(1.0, progress))

    linear_distance_at = lambda progress: total_km * max(0.0, min(1.0, progress))
    provisional_track = build_camera_track(
        cum_dist,
        xs,
        ys,
        lats,
        lons,
        movement_name,
        linear_distance_at,
        aspect=aspect,
        trip_detection=trip_detection,
        local_framing=local_framing,
    )

    distances = [total_km * sample / CAMERA_TRACK_SAMPLES for sample in range(CAMERA_TRACK_SAMPLES + 1)]
    markers = [
        latlon_to_meters(*position_at_distance(cum_dist, lats, lons, distance_km))
        for distance_km in distances
    ]
    cumulative_work = [0.0]
    for index in range(1, len(distances)):
        before = provisional_track[index - 1]
        after = provisional_track[index]
        span_x = math.sqrt(max(1.0, before[2]) * max(1.0, after[2]))
        span_y = math.sqrt(max(1.0, before[3]) * max(1.0, after[3]))
        ground_work = math.hypot(
            (markers[index][0] - markers[index - 1][0]) / span_x,
            (markers[index][1] - markers[index - 1][1]) / span_y,
        )
        zoom_work = 0.0
        if include_zoom_work:
            zoom_work = VISUAL_ZOOM_WORK_WEIGHT * abs(math.log2(after[3] / before[3]))
        cumulative_work.append(cumulative_work[-1] + ground_work + zoom_work)

    total_work = cumulative_work[-1]
    if total_work <= 0:
        return linear_distance_at
    elapsed_fractions = [value / total_work for value in cumulative_work]

    def distance_at(progress: float) -> float:
        elapsed = max(0.0, min(1.0, progress))
        to_index = min(max(bisect.bisect_left(elapsed_fractions, elapsed), 1), len(distances) - 1)
        from_index = to_index - 1
        width = elapsed_fractions[to_index] - elapsed_fractions[from_index]
        fraction = 0.0 if width <= 0 else (elapsed - elapsed_fractions[from_index]) / width
        return lerp(distances[from_index], distances[to_index], fraction)

    return distance_at


def calculate_overview_viewport(
    xs: List[float], ys: List[float], aspect: float = 1.0
) -> Tuple[float, float, float, float]:
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    center_x = (min_x + max_x) / 2.0
    center_y = (min_y + max_y) / 2.0
    content_span_x = max(max_x - min_x, 1000.0)
    content_span_y = max(max_y - min_y, 1000.0)
    span_y = max(content_span_y * 1.35, (content_span_x * 1.35) / max(0.1, aspect))
    span_x = span_y * aspect
    return center_x, center_y, span_x, span_y


# --- TITLE & FORMATTING ---

def resolve_title_template(
    template: str, year_label: str = "", name: str = "", fallback: str = "My Trips"
) -> str:
    resolved = template.replace("{year}", year_label).replace("{name}", name.strip()).strip()
    return resolved if resolved else fallback


def format_distance(distance_km: float, unit: str = "km") -> str:
    if unit == "mi":
        miles = distance_km * KM_TO_MILES
        return f"{miles:,.1f} mi"
    return f"{distance_km:,.1f} km"


def existing_file(value: str) -> Path:
    path = Path(value)
    if not path.is_file():
        raise argparse.ArgumentTypeError(f"input file does not exist or is not a file: {value}")
    return path


def ensure_ffmpeg_available() -> None:
    if not animation.writers.is_available('ffmpeg'):
        raise FfmpegUnavailableError(
            "ffmpeg is required to create MP4 video. Install ffmpeg or configure Matplotlib's animation.ffmpeg_path.",
        )


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Google Timeline Visualizer: create animated travel videos from your Timeline.json"
    )
    parser.add_argument('--input', '-i', required=True, type=existing_file, help="Path to Timeline.json")
    parser.add_argument('--year', '-y', type=int, default=None, help="Year to visualize (e.g. 2024)")
    parser.add_argument('--start-date', type=str, default=None, help="Start date (YYYY-MM-DD or YYYY-MM)")
    parser.add_argument('--end-date', type=str, default=None, help="End date (YYYY-MM-DD or YYYY-MM)")
    parser.add_argument('--output', '-o', default='travel_history.mp4', help="Output video path (.mp4)")
    parser.add_argument('--title', '-t', default="My Trips", help="Title displayed on video ({year} and {name} supported)")
    parser.add_argument('--name', default="", help="Name for title template substitution")
    parser.add_argument('--duration', '-d', type=int, default=DEFAULT_DURATION, help="Video duration in seconds (10 to 300)")
    parser.add_argument('--fps', type=int, default=DEFAULT_FPS, help="Frame rate (15 to 60 FPS)")
    parser.add_argument('--camera-movement', '-c', choices=CAMERA_MOVEMENTS.keys(), default='steady',
                        help="Camera behavior: fixed, steady, dynamic, or close_up")
    parser.add_argument('--long-trip-compression', '-p', choices=COMPRESSION_EXPONENTS.keys(), default='balanced',
                        help="Timing compression: off, gentle, balanced, strong, or stronger")
    parser.add_argument('--pacing-model', choices=['legacy', 'visual', 'visual_zoom'], default='legacy',
                        help="Pacing basis: legacy distance compression, visual ground speed, or visual speed plus zoom work")
    parser.add_argument('--trip-detection', choices=TRIP_DETECTION_MULTIPLIERS.keys(), default='balanced',
                        help="Trip detection sensitivity: conservative, balanced, sensitive")
    parser.add_argument('--local-framing', choices=LOCAL_FRAMING_SETTINGS.keys(), default='balanced',
                        help="Episode framing: off, balanced, close")
    parser.add_argument('--aspect-ratio', '-a', choices=['square', 'portrait', 'landscape'], default='square',
                        help="Aspect ratio: square (1:1), portrait (9:16), or landscape (16:9)")
    parser.add_argument('--resolution', '-r', choices=['480', '720', '1080'], default='720',
                        help="Resolution: 480p, 720p, or 1080p")
    parser.add_argument('--width', type=int, default=None, help="Custom video width in pixels")
    parser.add_argument('--height', type=int, default=None, help="Custom video height in pixels")
    parser.add_argument('--filter-outliers', choices=['conservative', 'off'], default='conservative',
                        help="GPS outlier filter: conservative or off")
    parser.add_argument('--route-source', choices=['semantic', 'journal'], default='semantic',
                        help="Route source: semantic points or Journal-style detailed-first fusion")
    parser.add_argument('--unit', choices=['km', 'mi'], default='km', help="Distance display unit: km or mi")
    return parser


def parse_date_argument(value: Optional[str]) -> Optional[date]:
    if not value:
        return None
    cleaned = value.strip()
    try:
        if len(cleaned) == 7 and '-' in cleaned:
            # YYYY-MM
            parts = cleaned.split('-')
            return date(int(parts[0]), int(parts[1]), 1)
        parsed = dateutil.parser.parse(cleaned)
        return parsed.date()
    except Exception as err:
        raise argparse.ArgumentTypeError(f"Invalid date format: {value}") from err


def main(argv: Optional[List[str]] = None) -> int:
    try:
        return _main_inner(argv)
    except KeyboardInterrupt:
        print("\nInterrupted.", file=sys.stderr)
        return 130


def _main_inner(argv: Optional[List[str]] = None) -> int:
    parser = build_argument_parser()
    args = parser.parse_args(argv)

    start_date = parse_date_argument(args.start_date)
    end_date = parse_date_argument(args.end_date)
    target_year = args.year
    if target_year is None and start_date is None and end_date is None:
        target_year = datetime.now().year

    try:
        ensure_ffmpeg_available()
        timestamps, xs, ys, cum_dist, lats, lons = parse_timeline(
            args.input,
            target_year,
            start_date,
            end_date,
            args.filter_outliers,
            args.route_source,
        )
    except TimelineCliError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1

    total_km = cum_dist[-1]
    print(f"Total distance: {format_distance(total_km, args.unit)}")

    # Resolve dimensions and aspect ratio
    aspect_map = {'square': 1.0, 'portrait': 9.0 / 16.0, 'landscape': 16.0 / 9.0}
    aspect = aspect_map[args.aspect_ratio]

    res_sizes = {
        '480': {'square': (480, 480), 'portrait': (480, 854), 'landscape': (854, 480)},
        '720': {'square': (720, 720), 'portrait': (720, 1280), 'landscape': (1280, 720)},
        '1080': {'square': (1080, 1080), 'portrait': (1080, 1920), 'landscape': (1920, 1080)},
    }
    default_w, default_h = res_sizes[args.resolution][args.aspect_ratio]
    width_px = args.width if args.width else default_w
    height_px = args.height if args.height else default_h
    aspect = width_px / float(height_px)

    fps = max(15, min(60, args.fps))
    journey_duration = max(10, min(300, args.duration))
    journey_frames = journey_duration * fps
    outro_frames = int(OUTRO_SECONDS * fps)
    total_frames = journey_frames + outro_frames

    if args.pacing_model == 'legacy':
        distance_at = build_journey_timing(cum_dist, args.long_trip_compression, args.trip_detection)
    else:
        distance_at = build_visual_journey_timing(
            cum_dist,
            xs,
            ys,
            lats,
            lons,
            args.camera_movement,
            aspect=aspect,
            trip_detection=args.trip_detection,
            local_framing=args.local_framing,
            include_zoom_work=args.pacing_model == 'visual_zoom',
        )
    print(f"Pacing model: {args.pacing_model}")
    print(f"Target: {journey_duration}s (+{OUTRO_SECONDS}s outro) @ {fps}fps. Resolution: {width_px}x{height_px}")

    # Build camera track and frame calculations
    camera_track = build_camera_track(
        cum_dist, xs, ys, lats, lons,
        args.camera_movement, distance_at,
        aspect=aspect,
        trip_detection=args.trip_detection,
        local_framing=args.local_framing,
    )
    overview_cx, overview_cy, overview_span_x, overview_span_y = calculate_overview_viewport(xs, ys, aspect)

    frame_data = []
    for i in range(total_frames):
        elapsed_sec = i / float(fps)
        if elapsed_sec <= journey_duration:
            j_progress = elapsed_sec / float(journey_duration)
            o_progress = 0.0
        else:
            j_progress = 1.0
            o_progress = min(1.0, (elapsed_sec - journey_duration) / OUTRO_TRANSITION_SECONDS)
        d = distance_at(j_progress)
        idx = min(max(bisect.bisect_right(cum_dist, d) - 1, 0), len(cum_dist) - 1)
        pt_x, pt_y = latlon_to_meters(*position_at_distance(cum_dist, lats, lons, d))

        # Viewport calculation with outro blend
        cam_cx, cam_cy, cam_span_x, cam_span_y = camera_at(camera_track, j_progress)
        if o_progress > 0:
            blend_factor = ease_out_cubic(o_progress)
            cur_cx = cam_cx + (overview_cx - cam_cx) * blend_factor
            cur_cy = cam_cy + (overview_cy - cam_cy) * blend_factor
            cur_span_x = math.exp(math.log(cam_span_x) + (math.log(overview_span_x) - math.log(cam_span_x)) * blend_factor)
            cur_span_y = math.exp(math.log(cam_span_y) + (math.log(overview_span_y) - math.log(cam_span_y)) * blend_factor)
        else:
            cur_cx, cur_cy, cur_span_x, cur_span_y = cam_cx, cam_cy, cam_span_x, cam_span_y

        frame_data.append({
            'idx': idx,
            'd': d,
            'head': (pt_x, pt_y),
            'cx': cur_cx,
            'cy': cur_cy,
            'span_x': cur_span_x,
            'span_y': cur_span_y,
            'j_prog': j_progress,
            'o_prog': o_progress,
        })

    # Year label for title
    year_label = str(target_year) if target_year is not None else f"{timestamps[0].year}"
    resolved_title = resolve_title_template(args.title, year_label=year_label, name=args.name)

    # Set up Matplotlib figure
    fig_w_inch = width_px / 100.0
    fig_h_inch = height_px / 100.0
    fig, ax = plt.subplots(figsize=(fig_w_inch, fig_h_inch), dpi=100)
    fig.subplots_adjust(left=0, bottom=0, right=1, top=1, wspace=0, hspace=0)
    ax.axis('off')

    # Initial Map
    init_f = frame_data[0]
    init_img, init_ext = get_map_image(init_f['cx'], init_f['cy'], init_f['span_x'], init_f['span_y'], width_px, height_px)
    map_layer = ax.imshow(init_img, extent=init_ext, aspect='equal')

    # Route layers: background old trail, recent trail, overview trail, and head marker
    scale = min(width_px, height_px) / 720.0
    old_trail_line, = ax.plot([], [], color=THEME_COLOR, alpha=0.34, linewidth=3.5 * scale)
    recent_trail_line, = ax.plot([], [], color=THEME_COLOR, alpha=1.0, linewidth=6.0 * scale)
    overview_trail_line, = ax.plot([], [], color=THEME_COLOR, alpha=0.0, linewidth=3.0 * scale)
    head_glow = ax.scatter([], [], s=(22 * scale) ** 2, color=THEME_COLOR, alpha=0.5, zorder=5)
    head_point = ax.scatter([], [], s=(12 * scale) ** 2, color=HEAD_COLOR, edgecolors=THEME_COLOR, linewidths=2.5 * scale, zorder=6)

    # Card overlay at top center
    card_width_ratio = min(0.85, 420.0 * scale / width_px)
    card_height_ratio = 0.12 * (width_px / height_px)
    card_patch = patches.FancyBboxPatch(
        (0.5 - card_width_ratio / 2, 0.96 - card_height_ratio),
        card_width_ratio, card_height_ratio,
        boxstyle="round,pad=0.015,rounding_size=0.03",
        transform=ax.transAxes,
        facecolor=CARD_BG_COLOR,
        alpha=0.88,
        edgecolor='none',
        zorder=7,
    )
    ax.add_patch(card_patch)

    title_text = ax.text(
        0.5, 0.93, resolved_title, transform=ax.transAxes,
        color=TEXT_PRIMARY_COLOR, fontsize=max(10, int(15 * scale)), fontweight='bold', ha='center', va='top', zorder=8
    )
    subtitle_text = ax.text(
        0.5, 0.88, '', transform=ax.transAxes,
        color=TEXT_SECONDARY_COLOR, fontsize=max(8, int(11 * scale)), ha='center', va='top', zorder=8
    )
    attribution_text = ax.text(
        0.98, 0.02, MAP_ATTRIBUTION, transform=ax.transAxes,
        color=TEXT_PRIMARY_COLOR, alpha=0.78, fontsize=max(7, int(8 * scale)), ha='right', va='bottom', zorder=8
    )

    def update(i: int) -> Tuple[Any, ...]:
        f = frame_data[i]
        cx, cy = f['cx'], f['cy']
        span_x, span_y = f['span_x'], f['span_y']
        frame_idx = f['idx']
        head_x, head_y = f['head']

        ax.set_xlim(cx - span_x / 2.0, cx + span_x / 2.0)
        ax.set_ylim(cy - span_y / 2.0, cy + span_y / 2.0)

        # Dynamic map tile update
        if i % 4 == 0:
            img, ext = get_map_image(cx, cy, span_x, span_y, width_px, height_px)
            map_layer.set_data(img)
            map_layer.set_extent(ext)

        # Active journey trail
        active_alpha = 1.0 - ease_out_cubic(f['o_prog'])
        if active_alpha > 0.01:
            traveled_x = list(xs[:frame_idx + 1]) + [head_x]
            traveled_y = list(ys[:frame_idx + 1]) + [head_y]
            old_trail_line.set_data(traveled_x, traveled_y)
            old_trail_line.set_alpha(0.34 * active_alpha)

            recent_start_km = max(0.0, f['d'] - max(80.0, total_km * 0.16))
            recent_idx = bisect.bisect_left(cum_dist, recent_start_km)
            recent_x = list(xs[recent_idx:frame_idx + 1]) + [head_x]
            recent_y = list(ys[recent_idx:frame_idx + 1]) + [head_y]
            recent_trail_line.set_data(recent_x, recent_y)
            recent_trail_line.set_alpha(1.0 * active_alpha)

            head_glow.set_offsets([[head_x, head_y]])
            head_glow.set_alpha(0.5 * active_alpha)
            head_point.set_offsets([[head_x, head_y]])
            head_point.set_alpha(1.0 * active_alpha)
        else:
            old_trail_line.set_data([], [])
            recent_trail_line.set_data([], [])
            head_glow.set_offsets(np.empty((0, 2)))
            head_point.set_offsets(np.empty((0, 2)))

        # Outro overview trail
        if f['o_prog'] > 0:
            overview_alpha = (190.0 / 255.0) * ease_in_out_cubic(f['o_prog'])
            overview_trail_line.set_data(xs, ys)
            overview_trail_line.set_alpha(overview_alpha)
        else:
            overview_trail_line.set_data([], [])

        # Subtitle text: date and distance
        curr_dt = timestamps[frame_idx]
        formatted_dist = format_distance(f['d'], args.unit)
        subtitle_text.set_text(f"{curr_dt.strftime('%B %Y')}  •  {formatted_dist}")

        return map_layer, old_trail_line, recent_trail_line, overview_trail_line, head_glow, head_point, subtitle_text

    print(f"Generating {len(frame_data)} frames...")
    ani = animation.FuncAnimation(fig, update, frames=len(frame_data), blit=False)

    print(f"Saving to {args.output}...")
    ani.save(args.output, writer='ffmpeg', fps=fps, dpi=100)
    if _tile_fetch_failures > 5:
        print(f"Warning: {_tile_fetch_failures} tile fetches failed (first 5 shown above). Map may have blank areas.", file=sys.stderr)
    print("Done!")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
