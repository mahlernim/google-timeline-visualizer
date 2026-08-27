from pathlib import Path

import visualizer


ROOT = Path(__file__).resolve().parents[1]


def test_python_tile_url_encodes_the_project_key() -> None:
    url = visualizer.carto_tile_url(6985, 3172, 13, "project key/value")

    assert url == (
        "https://a.basemaps.cartocdn.com/light_all/13/6985/3172.png"
        "?key=project+key%2Fvalue"
    )


def test_pages_build_requires_the_same_carto_project_key() -> None:
    workflow = (ROOT / ".github" / "workflows" / "pages.yml").read_text(encoding="utf-8")

    assert "VITE_CARTO_BASEMAP_API_KEY: ${{ secrets.CARTO_BASEMAP_API_KEY }}" in workflow
    assert 'test -n "$VITE_CARTO_BASEMAP_API_KEY"' in workflow
