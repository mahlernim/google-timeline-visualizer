from visualizer import (
    build_argument_parser,
    build_camera_track,
    build_journey_timing,
    build_legs,
    build_visual_journey_timing,
    ease_in_out_cubic,
    ease_out_cubic,
    latlon_to_meters,
)


def progress_at_distance(distance_at, target):
    low, high = 0.0, 1.0
    for _ in range(60):
        middle = (low + high) / 2
        if distance_at(middle) < target:
            low = middle
        else:
            high = middle
    return (low + high) / 2


def test_cli_defaults_match_android(tmp_path):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')
    args = build_argument_parser().parse_args(['--input', str(timeline)])
    assert args.input == timeline
    assert args.camera_movement == 'steady'
    assert args.long_trip_compression == 'balanced'
    assert args.pacing_model == 'legacy'


def test_balanced_compression_reduces_long_segments_share_without_changing_duration():
    cumulative = [0.0, 10.0, 1010.0, 1020.0]
    linear = build_journey_timing(cumulative, 'off')
    balanced = build_journey_timing(cumulative, 'balanced')
    linear_share = progress_at_distance(linear, 1010.0) - progress_at_distance(linear, 10.0)
    balanced_share = progress_at_distance(balanced, 1010.0) - progress_at_distance(balanced, 10.0)

    assert balanced_share < linear_share
    assert balanced(0.0) == 0.0
    assert abs(balanced(1.0) - cumulative[-1]) < 1e-6


def test_stronger_compression_reduces_share_further():
    cumulative = [0.0, 10.0, 1010.0, 1020.0]
    balanced = build_journey_timing(cumulative, 'balanced')
    stronger = build_journey_timing(cumulative, 'stronger')
    balanced_share = progress_at_distance(balanced, 1010.0) - progress_at_distance(balanced, 10.0)
    stronger_share = progress_at_distance(stronger, 1010.0) - progress_at_distance(stronger, 10.0)

    assert stronger_share < balanced_share


def test_visual_pacing_gives_a_zoomed_out_transfer_less_time_than_linear_distance():
    lats = [37.50, 37.55, 48.85, 48.90]
    lons = [127.00, 127.05, 2.35, 2.40]
    cumulative = [0.0, 10.0, 1010.0, 1020.0]
    projected = [latlon_to_meters(lat, lon) for lat, lon in zip(lats, lons)]
    xs = [point[0] for point in projected]
    ys = [point[1] for point in projected]
    linear = build_journey_timing(cumulative, 'off')
    visual = build_visual_journey_timing(
        cumulative, xs, ys, lats, lons, 'close_up', local_framing='close',
    )
    linear_share = progress_at_distance(linear, 1010.0) - progress_at_distance(linear, 10.0)
    visual_share = progress_at_distance(visual, 1010.0) - progress_at_distance(visual, 10.0)

    assert visual_share < linear_share
    assert visual(0.0) == 0.0
    assert visual(1.0) == cumulative[-1]
    samples = [visual(index / 100.0) for index in range(101)]
    assert samples == sorted(samples)


def test_visual_zoom_pacing_preserves_endpoints_and_monotonic_progress():
    lats = [37.50, 37.55, 48.85, 48.90]
    lons = [127.00, 127.05, 2.35, 2.40]
    cumulative = [0.0, 10.0, 1010.0, 1020.0]
    projected = [latlon_to_meters(lat, lon) for lat, lon in zip(lats, lons)]
    xs = [point[0] for point in projected]
    ys = [point[1] for point in projected]
    visual_zoom = build_visual_journey_timing(
        cumulative,
        xs,
        ys,
        lats,
        lons,
        'close_up',
        local_framing='close',
        include_zoom_work=True,
    )

    samples = [visual_zoom(index / 100.0) for index in range(101)]
    assert samples[0] == 0.0
    assert samples[-1] == cumulative[-1]
    assert samples == sorted(samples)


def test_adaptive_transfer_detection_separates_a_long_hop():
    cumulative = [0.0, 3.0, 8.0, 308.0, 312.0]
    legs = build_legs(cumulative)
    assert legs == [(0.0, 8.0, False), (8.0, 308.0, True), (308.0, 312.0, False)]


def test_fixed_camera_keeps_one_span():
    lats = [37.5, 37.55, 35.68, 35.70]
    lons = [126.9, 127.0, 139.69, 139.72]
    cumulative = [0.0, 12.0, 1165.0, 1169.0]
    projected = [latlon_to_meters(lat, lon) for lat, lon in zip(lats, lons)]
    xs = [point[0] for point in projected]
    ys = [point[1] for point in projected]
    distance_at = build_journey_timing(cumulative, 'off')
    track = build_camera_track(cumulative, xs, ys, lats, lons, 'fixed', distance_at)

    spans = [frame[2] for frame in track]
    assert max(spans) - min(spans) < 1e-6


def test_close_up_camera_builds_valid_track():
    lats = [37.5, 37.51, 37.52, 37.53]
    lons = [127.0, 127.01, 127.02, 127.03]
    cumulative = [0.0, 1.2, 2.4, 3.6]
    projected = [latlon_to_meters(lat, lon) for lat, lon in zip(lats, lons)]
    xs = [point[0] for point in projected]
    ys = [point[1] for point in projected]
    distance_at = build_journey_timing(cumulative, 'off')
    track = build_camera_track(cumulative, xs, ys, lats, lons, 'close_up', distance_at)
    assert len(track) == 481
    for frame in track:
        assert frame[2] > 0
        assert frame[3] > 0


def test_portrait_and_landscape_aspect_ratio_scaling():
    lats = [37.5, 37.6]
    lons = [127.0, 127.1]
    cumulative = [0.0, 15.0]
    projected = [latlon_to_meters(lat, lon) for lat, lon in zip(lats, lons)]
    xs = [point[0] for point in projected]
    ys = [point[1] for point in projected]
    distance_at = build_journey_timing(cumulative, 'off')

    portrait_track = build_camera_track(cumulative, xs, ys, lats, lons, 'steady', distance_at, aspect=9.0 / 16.0)
    landscape_track = build_camera_track(cumulative, xs, ys, lats, lons, 'steady', distance_at, aspect=16.0 / 9.0)

    # Portrait width span should be 9/16 of height span
    assert abs(portrait_track[0][2] - portrait_track[0][3] * (9.0 / 16.0)) < 1e-6
    # Landscape width span should be 16/9 of height span
    assert abs(landscape_track[0][2] - landscape_track[0][3] * (16.0 / 9.0)) < 1e-6


def test_outro_easing_bounds():
    assert ease_out_cubic(0.0) == 0.0
    assert ease_out_cubic(1.0) == 1.0
    assert ease_in_out_cubic(0.0) == 0.0
    assert ease_in_out_cubic(1.0) == 1.0
    assert 0.0 < ease_out_cubic(0.5) <= 1.0
    assert 0.0 < ease_in_out_cubic(0.5) <= 1.0
