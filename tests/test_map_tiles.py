import math

import pytest
from PIL import Image

import visualizer


@pytest.mark.parametrize(('x', 'y'), [(-1.1, 0), (0, 1.1)])
def test_negative_fractional_tile_coordinates_are_floored(x, y):
    extent = visualizer.MAX_EXTENT
    actual = visualizer.meters_to_tile(x * extent, y * extent, 2)
    assert actual == (math.floor((x + 1) * 2), math.floor((1 - y) * 2))


@pytest.mark.parametrize(('x', 'y'), [(1, 0), (-1, 0), (0, 1), (0, -1), (0, 0)])
def test_boundary_tiles_use_valid_requests_and_keep_world_placement(monkeypatch, x, y):
    calls = []
    def fetch(tx, ty, zoom):
        assert 0 <= tx < 2 ** zoom
        assert 0 <= ty < 2 ** zoom
        calls.append((tx, ty, zoom))
        return Image.new('RGB', (256, 256), (tx, ty, 100))
    monkeypatch.setattr(visualizer, 'fetch_tile_img', fetch)
    extent = visualizer.MAX_EXTENT
    image, bounds = visualizer.get_map_image(x * extent, y * extent, extent, width_px=256)
    assert calls
    zoom = calls[0][2]
    tile_size = 2 * extent / 2 ** zoom
    start_x = round((bounds[0] + extent) / tile_size)
    start_y = round((extent - bounds[3]) / tile_size)
    for column in range(image.width // 256):
        for row in range(image.height // 256):
            world_y = start_y + row
            expected = ((start_x + column) % (2 ** zoom), world_y, 100) if 0 <= world_y < 2 ** zoom else (242, 237, 240)
            assert image.getpixel((column * 256 + 128, row * 256 + 128)) == expected
    assert bounds[0] <= x * extent - extent / 2
    assert bounds[1] >= x * extent + extent / 2
    assert bounds[2] <= y * extent - extent / 2
    assert bounds[3] >= y * extent + extent / 2
