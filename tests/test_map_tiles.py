import io

import pytest
from PIL import Image

import visualizer
from visualizer import MapTileUnavailableError, fetch_tile_img


@pytest.fixture(autouse=True)
def clear_tile_state():
    """The tile cache and failure set live for the process, so reset them per test."""
    visualizer.TILE_CACHE.clear()
    visualizer.FAILED_TILES.clear()
    yield
    visualizer.TILE_CACHE.clear()
    visualizer.FAILED_TILES.clear()


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def read(self):
        return self._payload

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


def png_bytes(color=(1, 2, 3)):
    buffer = io.BytesIO()
    Image.new('RGB', (256, 256), color).save(buffer, format='PNG')
    return buffer.getvalue()


def test_tile_request_carries_a_timeout(monkeypatch):
    seen = []

    def urlopen(request, timeout=None):
        seen.append(timeout)
        return FakeResponse(png_bytes())

    monkeypatch.setattr(visualizer.urllib.request, 'urlopen', urlopen)

    fetch_tile_img(1, 2, 3)

    assert seen == [visualizer.TILE_FETCH_TIMEOUT_SECONDS]


def test_successful_tile_is_served_from_cache(monkeypatch):
    calls = []

    def urlopen(request, timeout=None):
        calls.append(request.full_url)
        return FakeResponse(png_bytes())

    monkeypatch.setattr(visualizer.urllib.request, 'urlopen', urlopen)

    first = fetch_tile_img(1, 2, 3)
    second = fetch_tile_img(1, 2, 3)

    assert len(calls) == 1
    assert first is second


def test_transient_failure_is_retried(monkeypatch):
    attempts = []

    def urlopen(request, timeout=None):
        attempts.append(request.full_url)
        if len(attempts) == 1:
            raise OSError('transient')
        return FakeResponse(png_bytes())

    monkeypatch.setattr(visualizer.urllib.request, 'urlopen', urlopen)

    fetch_tile_img(4, 5, 6)

    assert len(attempts) == visualizer.TILE_FETCH_ATTEMPTS
    assert (4, 5, 6) not in visualizer.FAILED_TILES


def test_failed_tile_is_not_requested_again(monkeypatch):
    """A frame loop asks for the same tile repeatedly; a known miss must not retry."""
    calls = []

    def urlopen(request, timeout=None):
        calls.append(request.full_url)
        raise OSError('offline')

    monkeypatch.setattr(visualizer.urllib.request, 'urlopen', urlopen)

    placeholder = fetch_tile_img(7, 8, 9)
    for _ in range(50):
        assert fetch_tile_img(7, 8, 9) is placeholder

    assert len(calls) == visualizer.TILE_FETCH_ATTEMPTS


def test_too_many_missing_tiles_stops_the_export(monkeypatch):
    def urlopen(request, timeout=None):
        raise OSError('offline')

    monkeypatch.setattr(visualizer.urllib.request, 'urlopen', urlopen)

    for index in range(visualizer.MAX_FAILED_TILES):
        fetch_tile_img(index, 0, 5)

    with pytest.raises(MapTileUnavailableError, match='map tiles'):
        fetch_tile_img(visualizer.MAX_FAILED_TILES, 0, 5)


def test_missing_tile_error_is_a_cli_error():
    assert issubclass(MapTileUnavailableError, visualizer.TimelineCliError)
