#!/usr/bin/env python3
"""
Google Timeline Visualizer
Author: @mahlernim
Description:
    Analyzes your Google Location History (Timeline.json) and generates a beautiful
    animated video of your travels for a specific year.
    Features:
    - Distance-based animation speed (majestic long trips, fast commutes)
    - Dynamic Camera (Smart Zoom & Smoothing)
    - Web Mercator Projection for perfect map alignment
    - Privacy-friendly (Month-only timestamps)
"""

import json
import argparse
import math
import sys
import io
import urllib.request
import bisect
from datetime import datetime
from pathlib import Path

# Third-party imports
try:
    import numpy as np
    import matplotlib
    # Set non-interactive backend
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import matplotlib.animation as animation
    import matplotlib.font_manager as fm
    from PIL import Image
    import dateutil.parser
except ImportError as e:
    print(f"Error: Missing dependency {e.name}. Please run: pip install -r requirements.txt")
    sys.exit(1)

# --- CONFIGURATION DEFAULTS ---
DEFAULT_FPS = 30
DEFAULT_DURATION = 90
DEFAULT_TAIL_KM = 500
THEME_COLOR = '#ff0055' # Pink/Red
TILE_URL = "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png"

# Camera Physics
SMOOTHING_FACTOR = 0.1
LOCAL_CONTEXT_KM = 25
MIN_ZOOM_SPAN_METERS = 5000
LOCAL_PADDING = 1.8
TRANSFER_PADDING = 2.8
# A single hop this long is a flight or another untracked transfer. Road and city travel arrives as
# densely sampled Timeline paths, so it never reaches this in one step.
TRANSFER_MIN_KM = 120
# The camera leaves a city faster than it settles back into the next one.
ZOOM_OUT_ALPHA = 0.32
ZOOM_IN_ALPHA = 0.18

# Web Mercator Constants
R_EARTH = 6378137.0
MAX_EXTENT = 20037508.342789244

# --- PROJECTION LOGIC ---

def latlon_to_meters(lat, lon):
    x = R_EARTH * math.radians(lon)
    y = R_EARTH * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))
    return x, y

def meters_to_latlon(x, y):
    lon = math.degrees(x / R_EARTH)
    lat = math.degrees(2 * math.atan(math.exp(y / R_EARTH)) - math.pi / 2)
    return lat, lon

def interpolate_latlon(lat1, lon1, lat2, lon2, fraction):
    """Great-circle position between two points, so long hops sweep instead of teleporting."""
    if fraction <= 0:
        return lat1, lon1
    if fraction >= 1:
        return lat2, lon2
    p1, l1 = math.radians(lat1), math.radians(lon1)
    p2, l2 = math.radians(lat2), math.radians(lon2)
    ax, ay, az = math.cos(p1) * math.cos(l1), math.cos(p1) * math.sin(l1), math.sin(p1)
    bx, by, bz = math.cos(p2) * math.cos(l2), math.cos(p2) * math.sin(l2), math.sin(p2)
    omega = math.acos(max(-1.0, min(1.0, ax * bx + ay * by + az * bz)))
    if math.sin(omega) < 1e-8:
        left, right = 1 - fraction, fraction
    else:
        left = math.sin((1 - fraction) * omega) / math.sin(omega)
        right = math.sin(fraction * omega) / math.sin(omega)
    x, y, z = left * ax + right * bx, left * ay + right * by, left * az + right * bz
    return math.degrees(math.atan2(z, math.sqrt(x * x + y * y))), math.degrees(math.atan2(y, x))


def build_legs(cum_dist):
    """Split the route at hops long enough to be a flight or another untracked transfer.

    Returns (start_km, end_km, is_transfer) tuples covering the whole route. The camera frames a
    local leg on its own, so a distant city cannot widen the view while it is still far away.
    """
    total = cum_dist[-1]
    if total <= 0:
        return [(0.0, 0.0, False)]
    legs = []
    leg_start = 0.0
    for i in range(1, len(cum_dist)):
        if cum_dist[i] - cum_dist[i - 1] < TRANSFER_MIN_KM:
            continue
        if cum_dist[i - 1] > leg_start:
            legs.append((leg_start, cum_dist[i - 1], False))
        legs.append((cum_dist[i - 1], cum_dist[i], True))
        leg_start = cum_dist[i]
    # A journey ending on a transfer leaves nothing behind it, so skip the empty remainder.
    if total > leg_start:
        legs.append((leg_start, total, False))
    return legs


def leg_at(legs, distance_km):
    index = bisect.bisect_right([leg[0] for leg in legs], distance_km) - 1
    return legs[max(0, min(index, len(legs) - 1))]


def position_at_distance(cum_dist, lats, lons, distance_km):
    """Interpolated position at an odometer distance, rather than snapping to the next point."""
    total = cum_dist[-1]
    d = max(0.0, min(total, distance_km))
    to = min(max(bisect.bisect_left(cum_dist, d), 1), len(cum_dist) - 1)
    segment = cum_dist[to] - cum_dist[to - 1]
    fraction = 0.0 if segment <= 0 else (d - cum_dist[to - 1]) / segment
    return interpolate_latlon(lats[to - 1], lons[to - 1], lats[to], lons[to], fraction)


def haversine_dist(lat1, lon1, lat2, lon2):
    R = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c

# --- MAP TILES (Web Mercator) ---

def meters_to_tile(mx, my, zoom):
    n = 2.0 ** zoom
    norm_x = (mx + MAX_EXTENT) / (2 * MAX_EXTENT)
    norm_y = 1.0 - (my + MAX_EXTENT) / (2 * MAX_EXTENT)
    xtile = int(norm_x * n)
    ytile = int(norm_y * n)
    return xtile, ytile

def tile_to_bounds_meters(xtile, ytile, zoom):
    n = 2.0 ** zoom
    tile_size = (2 * MAX_EXTENT) / n
    min_x = -MAX_EXTENT + xtile * tile_size
    max_x = min_x + tile_size
    max_y = MAX_EXTENT - ytile * tile_size
    min_y = max_y - tile_size
    return min_x, max_x, min_y, max_y

TILE_CACHE = {}
def fetch_tile_img(x, y, z):
    key = (x, y, z)
    if key in TILE_CACHE: return TILE_CACHE[key]
    url = TILE_URL.format(z=z, x=x, y=y)
    try:
        req = urllib.request.Request(url, headers={'User-Agent': "Mozilla/5.0"})
        with urllib.request.urlopen(req) as response:
            img = Image.open(io.BytesIO(response.read())).convert('RGB')
            TILE_CACHE[key] = img
            return img
    except Exception:
        # Return fallback tile (gray)
        return Image.new('RGB', (256, 256), (240, 240, 240))

def get_map_image(x_center, y_center, span, width_px=800):
    # Calculate target zoom
    # Resolution (m/px) needed = span / width_px
    # Base resolution (z=0) = (2 * MAX_EXTENT) / 256
    # Res at z = Base / 2^z
    # 2^z = (Base / Res_needed) = (2*MAX / 256) / (span / width)
    # 2^z = (2 * MAX * width) / (256 * span)
    
    target_val = (2 * MAX_EXTENT * width_px) / (256 * max(span, 1.0))
    zoom = int(math.log2(target_val)) if target_val > 0 else 2
    zoom = max(2, min(15, zoom)) # Limit zoom range
    
    min_x = x_center - span/2
    max_x = x_center + span/2
    min_y = y_center - span/2
    max_y = y_center + span/2
    
    xt_min, yt_min = meters_to_tile(min_x, max_y, zoom)
    xt_max, yt_max = meters_to_tile(max_x, min_y, zoom)
    
    if yt_min > yt_max: yt_min, yt_max = yt_max, yt_min
    
    x_tiles = xt_max - xt_min + 1
    y_tiles = yt_max - yt_min + 1
    
    # Safety clamp: if view is too huge, reduce zoom
    while x_tiles * y_tiles > 25 and zoom > 2:
        zoom -= 1
        xt_min, yt_min = meters_to_tile(min_x, max_y, zoom)
        xt_max, yt_max = meters_to_tile(max_x, min_y, zoom)
        if yt_min > yt_max: yt_min, yt_max = yt_max, yt_min
        x_tiles = xt_max - xt_min + 1
        y_tiles = yt_max - yt_min + 1

    tile_w, tile_h = 256, 256
    stitched = Image.new('RGB', (x_tiles * tile_w, y_tiles * tile_h))
    
    # Calculate exact bounds of the stitched image
    tl_min_x, tl_max_x, tl_min_y, tl_max_y = tile_to_bounds_meters(xt_min, yt_min, zoom)
    br_min_x, br_max_x, br_min_y, br_max_y = tile_to_bounds_meters(xt_max, yt_max, zoom)
    
    final_min_x = tl_min_x
    final_max_x = br_max_x # technically bounds of xt_max tile
    final_max_y = tl_max_y
    final_min_y = br_min_y
    
    for x in range(x_tiles):
        for y in range(y_tiles):
            img = fetch_tile_img(xt_min + x, yt_min + y, zoom)
            stitched.paste(img, (x * tile_w, y * tile_h))
            
    return stitched, (final_min_x, final_max_x, final_min_y, final_max_y)

# --- DATA PROCESSING ---

def parse_coordinate(value):
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


def extract_timeline_points(data, year):
    """Return normalized points for a year from supported Timeline JSON roots."""
    if isinstance(data, list):
        segments = data
    elif isinstance(data, dict):
        segments = data.get('semanticSegments', [])
    else:
        raise ValueError('Timeline JSON must start with an object or array')

    points = []

    def add_point(time_value, coordinate_value):
        coordinate = parse_coordinate(coordinate_value)
        if not time_value or coordinate is None:
            return
        try:
            timestamp = dateutil.parser.parse(time_value)
        except (TypeError, ValueError, OverflowError):
            return
        if timestamp.year == year:
            points.append({'dt': timestamp, 'lat': coordinate[0], 'lon': coordinate[1]})

    for seg in segments:
        if not isinstance(seg, dict):
            continue
        start_time = seg.get('startTime')
        end_time = seg.get('endTime')

        for path_point in seg.get('timelinePath', []):
            if isinstance(path_point, dict):
                add_point(path_point.get('time'), path_point.get('point'))

        activity = seg.get('activity')
        if isinstance(activity, dict):
            add_point(start_time, activity.get('start'))
            add_point(end_time, activity.get('end'))

        visit = seg.get('visit')
        if isinstance(visit, dict):
            candidate = visit.get('topCandidate')
            if isinstance(candidate, dict):
                add_point(start_time, candidate.get('placeLocation'))

    unique = {
        (point['dt'], point['lat'], point['lon']): point
        for point in points
    }
    return sorted(unique.values(), key=lambda point: point['dt'])

def parse_timeline(input_path, year):
    print(f"Loading {input_path}...")
    try:
        with open(input_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        print(f"Error reading JSON: {e}")
        sys.exit(1)
        
    segments = data if isinstance(data, list) else data.get('semanticSegments', []) if isinstance(data, dict) else []
    print(f"Parsing {len(segments)} segments for year {year}...")
    points = extract_timeline_points(data, year)

    if not points:
        print(f"No data points found for year {year}.")
        sys.exit(1)
        
    print(f"Found {len(points)} valid points.")
    
    # Process into arrays & Project
    timestamps = []
    lats = []
    lons = []
    xs = []
    ys = []
    
    for p in points:
        timestamps.append(p['dt'])
        lats.append(p['lat'])
        lons.append(p['lon'])
        x, y = latlon_to_meters(p['lat'], p['lon'])
        xs.append(x)
        ys.append(y)
        
    # Calculate Distances (Haversine) for animation timing
    cum_dist = [0.0]
    total = 0.0
    for i in range(1, len(lats)):
        d = haversine_dist(lats[i-1], lons[i-1], lats[i], lons[i])
        total += d
        cum_dist.append(total)
        
    return timestamps, xs, ys, cum_dist, lats, lons

def main():
    parser = argparse.ArgumentParser(description="Google Timeline Visualizer")
    parser.add_argument('--input', '-i', required=True, help="Path to Timeline.json")
    parser.add_argument('--year', '-y', type=int, default=datetime.now().year, help="Year to visualize")
    parser.add_argument('--output', '-o', default='travel_history.mp4', help="Output video path")
    parser.add_argument('--title', '-t', default="My Trips", help="Title displayed on video")
    
    args = parser.parse_args()
    
    # Load
    timestamps, xs, ys, cum_dist, lats, lons = parse_timeline(args.input, args.year)
    
    total_km = cum_dist[-1]
    print(f"Total distance: {total_km:.1f} km")
    
    # Prepare Frame Indices (Distance Based)
    total_frames = DEFAULT_FPS * DEFAULT_DURATION
    km_per_frame = total_km / total_frames
    
    print(f"Target: {DEFAULT_DURATION}s @ {DEFAULT_FPS}fps. {km_per_frame:.3f} km/frame")
    
    frames_dist = [i * km_per_frame for i in range(total_frames)]
    # frame_indices is the last point already travelled through; the marker itself is interpolated
    # so a flight sweeps across the map instead of freezing on the arrival point.
    frame_indices = []
    frame_points = []
    for d in frames_dist:
        idx = min(max(bisect.bisect_right(cum_dist, d) - 1, 0), len(cum_dist) - 1)
        frame_indices.append(idx)
        lat, lon = position_at_distance(cum_dist, lats, lons, d)
        frame_points.append(latlon_to_meters(lat, lon))

    legs = build_legs(cum_dist)
    transfers = sum(1 for leg in legs if leg[2])
    print(f"Route split into {len(legs)} legs ({transfers} long-distance transfers)")

    # Camera Calculation
    print("Calculating camera path...")
    cam_centers = []
    cam_spans = []
    
    curr_x, curr_y = frame_points[0]
    curr_span = None

    for i, frame_d in enumerate(frames_dist):
        leg_start, leg_end, is_transfer = leg_at(legs, frame_d)
        # A transfer spans its whole hop so the flight reads as one wide move; a local leg keeps a
        # short window, and clamping to the leg stops the next city widening the view too early.
        context = (leg_end - leg_start) if is_transfer else LOCAL_CONTEXT_KM
        padding = TRANSFER_PADDING if is_transfer else LOCAL_PADDING
        tail_d = max(leg_start, frame_d - context)
        look_d = min(leg_end, frame_d + context)

        lo = bisect.bisect_left(cum_dist, tail_d)
        hi = bisect.bisect_right(cum_dist, look_d)
        w_xs = list(xs[lo:hi])
        w_ys = list(ys[lo:hi])
        for edge in (tail_d, look_d):
            e_x, e_y = latlon_to_meters(*position_at_distance(cum_dist, lats, lons, edge))
            w_xs.append(e_x)
            w_ys.append(e_y)

        span_x = max(w_xs) - min(w_xs)
        span_y = max(w_ys) - min(w_ys)
        target_span = max(span_x, span_y, MIN_ZOOM_SPAN_METERS) * padding

        # Center Target (Current Position implies following the dot)
        t_x, t_y = frame_points[i]

        # Update
        curr_x += (t_x - curr_x) * SMOOTHING_FACTOR
        curr_y += (t_y - curr_y) * SMOOTHING_FACTOR
        if curr_span is None:
            curr_span = target_span
        else:
            # Zoom in logarithmic space, and settle back into a city slower than we leave one.
            alpha = ZOOM_OUT_ALPHA if target_span > curr_span else ZOOM_IN_ALPHA
            curr_span = math.exp(math.log(curr_span) + (math.log(target_span) - math.log(curr_span)) * alpha)

        cam_centers.append((curr_x, curr_y))
        cam_spans.append(curr_span)

    # Visualization
    print("Setting up animation...")
    fig, ax = plt.subplots(figsize=(10,10))
    # Remove whitespace
    fig.subplots_adjust(left=0, bottom=0, right=1, top=1, wspace=None, hspace=None)
    ax.axis('off')
    
    # Init Layers
    # Initial Map
    init_cx, init_cy = cam_centers[0]
    init_span = cam_spans[0]
    init_img, init_ext = get_map_image(init_cx, init_cy, init_span)
    
    map_layer = ax.imshow(init_img, extent=init_ext, aspect='equal')
    
    path_line, = ax.plot([], [], color=THEME_COLOR, alpha=0.5, linewidth=2)
    tail_line, = ax.plot([], [], color=THEME_COLOR, linewidth=4, alpha=1.0)
    head_point, = ax.plot([], [], color='black', marker='o', markersize=8, markeredgecolor=THEME_COLOR)
    
    # UI Elements
    title_text = ax.text(0.5, 0.95, args.title, transform=ax.transAxes, 
                         color='black', fontsize=16, fontweight='bold', ha='center', va='top',
                         bbox=dict(facecolor='white', alpha=0.5, edgecolor='none', pad=5))
                         
    date_text = ax.text(0.5, 0.90, '', transform=ax.transAxes, 
                        color='gray', fontsize=14, ha='center', va='top',
                        bbox=dict(facecolor='white', alpha=0.5, edgecolor='none', pad=3))

    def update(i):
        frame_idx = frame_indices[i]
        cx, cy = cam_centers[i]
        span = cam_spans[i]
        
        ax.set_xlim(cx - span/2, cx + span/2)
        ax.set_ylim(cy - span/2, cy + span/2)
        
        # dynamic map update (every 5 frames)
        if i % 5 == 0:
            img, ext = get_map_image(cx, cy, span)
            map_layer.set_data(img)
            map_layer.set_extent(ext)
            
        # Path, ending at the interpolated marker rather than the last point passed
        head_x, head_y = frame_points[i]
        _xs = list(xs[:frame_idx+1]) + [head_x]
        _ys = list(ys[:frame_idx+1]) + [head_y]
        path_line.set_data(_xs, _ys)

        # Tail
        curr_km = frames_dist[i]
        start_km = max(0, curr_km - DEFAULT_TAIL_KM)
        start_idx = bisect.bisect_left(cum_dist, start_km)

        txs = list(xs[start_idx : frame_idx+1]) + [head_x]
        tys = list(ys[start_idx : frame_idx+1]) + [head_y]
        tail_line.set_data(txs, tys)

        head_point.set_data([head_x], [head_y])

        if timestamps:
            date_text.set_text(timestamps[frame_idx].strftime('%B %Y'))
            
        return map_layer, path_line, tail_line, head_point, date_text,

    print(f"Generating {len(frame_indices)} frames...")
    ani = animation.FuncAnimation(fig, update, frames=len(frame_indices), blit=False)
    
    print(f"Saving to {args.output}...")
    ani.save(args.output, writer='ffmpeg', fps=DEFAULT_FPS, dpi=100)
    print("Done!")

if __name__ == "__main__":
    main()
