import visualizer
from visualizer import (
    build_argument_parser,
    build_camera_track,
    build_journey_timing,
    build_legs,
    ease_in_out_cubic,
    ease_out_cubic,
    latlon_to_meters,
    raw_camera_sample,
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


def arrival_span(local_leg_km):
    """Camera span while arriving at a local leg of the given length after a long transfer."""
    cumulative = [0.0, 2.5, 5.0, 500.0, 500.0 + local_leg_km / 2, 500.0 + local_leg_km]
    lats = [37.50, 37.52, 37.54, 13.15, 13.16, 13.17]
    lons = [127.00, 127.02, 127.04, 123.75, 123.76, 123.77]
    projected = [latlon_to_meters(lat, lon) for lat, lon in zip(lats, lons)]
    xs = [point[0] for point in projected]
    ys = [point[1] for point in projected]
    legs = build_legs(cumulative)
    assert legs[1][2], 'the middle leg must be detected as a transfer'
    assert not legs[2][2], 'the arrival leg must be local'
    # 470 km sits past EPISODE_ARRIVAL_ZOOM_START_FRACTION of the transfer leg,
    # so the arrival blend is active.
    return raw_camera_sample(
        cumulative, xs, ys, lats, lons, 470.0, 'close_up', legs, local_framing='balanced',
    )[3]


def test_short_arrival_legs_are_not_flattened_to_one_span():
    """The arrival blend must follow the real leg length.

    A floor above ordinary city distances would clamp both of these to the same
    value and erase the difference, which is what MIN_CONTEXT_KM = 15.0 did while
    TimelinePainter.kt used 0.001.
    """
    tight = arrival_span(3.0)
    loose = arrival_span(12.0)

    assert tight < loose


def test_log_floor_stays_below_real_leg_lengths():
    """LOG_FLOOR_KM only guards log(0); it must never shape framing."""
    assert visualizer.LOG_FLOOR_KM == 0.001
    assert visualizer.LOG_FLOOR_KM < min(
        movement['minimum_context_km'] for movement in visualizer.CAMERA_MOVEMENTS.values()
    )
