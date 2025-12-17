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
    from tqdm import tqdm
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
LOOKAHEAD_KM = 500 
MIN_ZOOM_SPAN_METERS = 5000 

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

def parse_timeline(input_path, year):
    print(f"Loading {input_path}...")
    try:
        with open(input_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        print(f"Error reading JSON: {e}")
        sys.exit(1)
        
    segments = data.get('semanticSegments', [])
    print(f"Parsing {len(segments)} segments for year {year}...")
    
    points = [] # dicts of {dt, lat, lon}
    
    for seg in segments:
        # Check time
        start_str = seg.get('startTime')
        if not start_str: continue
        try:
            dt = dateutil.parser.parse(start_str)
        except: continue
        
        if dt.year != year:
            continue
            
        # 1. timelinePath
        path = seg.get('timelinePath', [])
        if path:
            for p in path:
                pt_str = p.get('point')
                tm_str = p.get('time')
                if pt_str and tm_str:
                    try:
                        # "37.123°, 127.123°"
                        parts = pt_str.replace('°','').split(',')
                        lat = float(parts[0])
                        lon = float(parts[1])
                        t = dateutil.parser.parse(tm_str)
                        points.append({'dt':t, 'lat':lat, 'lon':lon})
                    except: pass
        
        # 2. Visit (Top Candidate)
        if 'visit' in seg:
            visit = seg['visit']
            if 'topCandidate' in visit:
                 loc = visit['topCandidate'].get('placeLocation', {})
                 latlng = loc.get('latLng')
                 if latlng:
                     try:
                        parts = latlng.replace('°','').split(',')
                        lat = float(parts[0])
                        lon = float(parts[1])
                        # Use segment start time for visit anchor
                        points.append({'dt':dt, 'lat':lat, 'lon':lon})
                     except: pass

    if not points:
        print(f"No data points found for year {year}.")
        sys.exit(1)
        
    # Sort
    points.sort(key=lambda x: x['dt'])
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
    frame_indices = []
    for d in frames_dist:
        idx = bisect.bisect_left(cum_dist, d)
        idx = min(idx, len(cum_dist)-1)
        frame_indices.append(idx)
        
    # Camera Calculation
    print("Calculating camera path...")
    cam_centers = []
    cam_spans = []
    
    curr_x, curr_y = xs[0], ys[0]
    curr_span = 10000.0 # Start with 10km view
    
    for i, frame_d in enumerate(frames_dist):
        idx = frame_indices[i]
        
        # Lookahead
        target_d = frame_d + LOOKAHEAD_KM
        look_idx = bisect.bisect_left(cum_dist, target_d)
        look_idx = min(look_idx, len(cum_dist)-1)
        
        # Get bounds of window
        w_xs = xs[idx : look_idx+1] or [xs[idx]]
        w_ys = ys[idx : look_idx+1] or [ys[idx]]
        
        min_x, max_x = min(w_xs), max(w_xs)
        min_y, max_y = min(w_ys), max(w_ys)
        
        span_x = max_x - min_x
        span_y = max_y - min_y
        
        target_span = max(span_x, span_y, MIN_ZOOM_SPAN_METERS) * 3.0
        
        # Center Target (Current Position implies following the dot)
        t_x, t_y = xs[idx], ys[idx]
        
        # Update
        curr_x += (t_x - curr_x) * SMOOTHING_FACTOR
        curr_y += (t_y - curr_y) * SMOOTHING_FACTOR
        curr_span += (target_span - curr_span) * (SMOOTHING_FACTOR * 0.5)
        
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
            
        # Path
        _xs = xs[:frame_idx+1]
        _ys = ys[:frame_idx+1]
        path_line.set_data(_xs, _ys)
        
        # Tail
        curr_km = cum_dist[frame_idx]
        start_km = max(0, curr_km - DEFAULT_TAIL_KM)
        start_idx = bisect.bisect_left(cum_dist, start_km)
        
        txs = xs[start_idx : frame_idx+1]
        tys = ys[start_idx : frame_idx+1]
        tail_line.set_data(txs, tys)
        
        if _xs:
            head_point.set_data([_xs[-1]], [_ys[-1]])
            
        if timestamps:
            date_text.set_text(timestamps[frame_idx].strftime('%B %Y'))
            
        return map_layer, path_line, tail_line, head_point, date_text,

    print(f"Generating {len(frame_indices)} frames...")
    ani = animation.FuncAnimation(fig, update, frames=len(frame_indices), blit=False)
    
    print(f"Saving to {args.output}...")
    with tqdm(total=len(frame_indices), desc="Rendering", unit="frame") as pbar:
        ani.save(args.output, writer='ffmpeg', fps=DEFAULT_FPS, dpi=100,
                 progress_callback=lambda i, n: pbar.update(1))
    print("Done!")

if __name__ == "__main__":
    main()
