import json
import shutil
import subprocess
from datetime import date

import pytest

import visualizer
from visualizer import (
    FfmpegUnavailableError,
    NoDataFoundError,
    TimelineParseError,
    build_argument_parser,
    ensure_ffmpeg_available,
    ffmpeg_writer,
    parse_date_argument,
    parse_timeline,
)


def test_missing_input_is_rejected_by_argparse(tmp_path):
    missing = tmp_path / 'missing.json'

    with pytest.raises(SystemExit) as error:
        build_argument_parser().parse_args(['--input', str(missing)])

    assert error.value.code == 2


def test_directory_input_is_rejected_by_argparse(tmp_path):
    with pytest.raises(SystemExit) as error:
        build_argument_parser().parse_args(['--input', str(tmp_path)])

    assert error.value.code == 2


@pytest.mark.parametrize('arguments', [
    ['--fps', '14'],
    ['--fps', '121'],
    ['--duration', '9'],
    ['--duration', '301'],
    ['--width', '481'],
    ['--height', '4096'],
])
def test_export_values_outside_android_limits_are_rejected(tmp_path, arguments):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')
    with pytest.raises(SystemExit) as error:
        build_argument_parser().parse_args(['--input', str(timeline), *arguments])
    assert error.value.code == 2


def test_malformed_json_raises_typed_error(tmp_path):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('{not json', encoding='utf-8')

    with pytest.raises(TimelineParseError) as error:
        parse_timeline(timeline, 2026)

    assert isinstance(error.value.__cause__, json.JSONDecodeError)


def test_invalid_utf8_raises_typed_error(tmp_path):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_bytes(b'\xff\xfe')

    with pytest.raises(TimelineParseError) as error:
        parse_timeline(timeline, 2026)

    assert isinstance(error.value.__cause__, UnicodeDecodeError)


def test_missing_file_raises_typed_error_when_parser_is_called_directly(tmp_path):
    with pytest.raises(TimelineParseError) as error:
        parse_timeline(tmp_path / 'missing.json', 2026)

    assert isinstance(error.value.__cause__, OSError)


def test_no_matching_year_raises_no_data_error(tmp_path):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')

    with pytest.raises(NoDataFoundError, match='2026'):
        parse_timeline(timeline, 2026)


def test_ffmpeg_availability_uses_matplotlib_configuration(monkeypatch):
    monkeypatch.setattr(visualizer.animation.writers, 'is_available', lambda name: name == 'ffmpeg')

    ensure_ffmpeg_available()


def test_missing_ffmpeg_raises_typed_error(monkeypatch):
    monkeypatch.setattr(visualizer.animation.writers, 'is_available', lambda _name: False)

    with pytest.raises(FfmpegUnavailableError, match='ffmpeg is required'):
        ensure_ffmpeg_available()


def test_ffmpeg_writer_embeds_resolved_title(monkeypatch):
    captured = {}
    monkeypatch.setattr(visualizer.animation, 'FFMpegWriter', lambda **kwargs: captured.update(kwargs) or object())
    writer = ffmpeg_writer(120, 'My Journey')
    assert writer is not None
    assert captured == {'fps': 120, 'metadata': {'title': 'My Journey'}}


@pytest.mark.skipif(shutil.which('ffmpeg') is None or shutil.which('ffprobe') is None, reason='FFmpeg tools unavailable')
def test_real_ffmpeg_output_has_requested_rate_duration_dimensions_and_title(tmp_path):
    figure = visualizer.plt.figure(figsize=(1, 1), dpi=100)
    visualizer.plt.plot([0, 1], [0, 1])
    clip = visualizer.animation.FuncAnimation(figure, lambda _frame: (), frames=15)
    output = tmp_path / 'probe.mp4'
    clip.save(output, writer=ffmpeg_writer(15, 'Parity Probe'), dpi=100)
    visualizer.plt.close(figure)
    details = json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-select_streams', 'v:0',
        '-show_entries', 'stream=width,height,avg_frame_rate:format=duration:format_tags=title',
        '-of', 'json', str(output),
    ], text=True, encoding='utf-8'))
    stream = details['streams'][0]
    assert (stream['width'], stream['height']) == (100, 100)
    assert stream['avg_frame_rate'] == '15/1'
    assert float(details['format']['duration']) == pytest.approx(1.0, abs=0.05)
    assert details['format']['tags']['title'] == 'Parity Probe'


def test_main_checks_ffmpeg_before_parsing(monkeypatch, tmp_path, capsys):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')
    parsed = False

    def fail_ffmpeg():
        raise FfmpegUnavailableError('ffmpeg unavailable')

    def mark_parsed(*_args):
        nonlocal parsed
        parsed = True

    monkeypatch.setattr(visualizer, 'ensure_ffmpeg_available', fail_ffmpeg)
    monkeypatch.setattr(visualizer, 'parse_timeline', mark_parsed)

    assert visualizer.main(['--input', str(timeline)]) == 1
    assert not parsed
    assert 'ffmpeg unavailable' in capsys.readouterr().err


def test_main_reports_typed_parse_failure(monkeypatch, tmp_path, capsys):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')
    monkeypatch.setattr(visualizer, 'ensure_ffmpeg_available', lambda: None)
    monkeypatch.setattr(
        visualizer,
        'parse_timeline',
        lambda *_args: (_ for _ in ()).throw(NoDataFoundError('no matching points')),
    )

    assert visualizer.main(['--input', str(timeline)]) == 1
    assert 'no matching points' in capsys.readouterr().err


@pytest.mark.parametrize(('value', 'expected'), [
    ('2024-06', date(2024, 6, 1)),
    ('2024-06-17', date(2024, 6, 17)),
])
def test_start_date_month_uses_first_day(value, expected):
    assert parse_date_argument(value) == expected


@pytest.mark.parametrize(('value', 'expected'), [
    ('2024-06', date(2024, 6, 30)),
    ('2024-02', date(2024, 2, 29)),
    ('2025-02', date(2025, 2, 28)),
    ('2024-06-17', date(2024, 6, 17)),
])
def test_end_date_month_uses_last_day(value, expected):
    assert parse_date_argument(value, end_of_month=True) == expected


@pytest.mark.parametrize(('width', 'height'), [(3840, 2160), (2160, 3840), (3440, 1440)])
def test_custom_large_dimensions_are_accepted(tmp_path, width, height):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')
    args = build_argument_parser().parse_args([
        '--input', str(timeline), '--width', str(width), '--height', str(height)])
    assert visualizer.resolve_video_dimensions(args.resolution, args.aspect_ratio, args.width, args.height) == (width, height)


@pytest.mark.parametrize(('arguments', 'message'), [
    (['--width', '478'], 'short edge'),
    (['--width', '2162', '--height', '2162'], 'short edge'),
    (['--width', '481'], 'even'),
    (['--height', '2161'], 'even'),
    (['--width', '3842'], 'long-edge limit'),
    (['--resolution', '2160', '--aspect-ratio', 'portrait', '--width', '2560'], 'short edge'),
])
def test_invalid_resolved_dimensions_fail_before_rendering(tmp_path, monkeypatch, capsys, arguments, message):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')
    monkeypatch.setattr(visualizer, 'ensure_ffmpeg_available', lambda: pytest.fail('validation must precede rendering'))
    with pytest.raises(SystemExit) as error:
        visualizer.main(['--input', str(timeline), *arguments])
    assert error.value.code == 2
    assert message in capsys.readouterr().err


def test_one_custom_axis_resolves_against_the_preset():
    assert visualizer.resolve_video_dimensions('2160', 'landscape', height=1440) == (3840, 1440)
    assert visualizer.resolve_video_dimensions('2160', 'portrait', width=1440) == (1440, 3840)


def test_formats_match_shared_fixture_and_custom_pairs():
    from pathlib import Path
    expected = json.loads((Path(__file__).parents[1] / 'test-fixtures/platform-parity-expected.json').read_text())
    for short_edge, aspects in expected['videoDimensions'].items():
        for aspect, dimensions in aspects.items():
            assert visualizer.resolve_video_dimensions(short_edge, aspect) == tuple(dimensions)
            assert visualizer.resolve_video_dimensions('480', 'square', *dimensions) == tuple(dimensions)
