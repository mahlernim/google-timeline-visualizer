from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_web_app_links_its_own_privacy_policy() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    policy = (ROOT / "docs" / "privacy-web.md").read_text(encoding="utf-8")

    assert "docs/privacy-web.md" in html
    assert "Cloudflare Web Analytics" in policy
    assert "Timeline file contents" in policy


def test_web_privacy_copy_does_not_claim_analytics_are_absent() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    readme = (ROOT / "web" / "README.md").read_text(encoding="utf-8")

    assert "No account, location permission, or Timeline upload is required." in html
    assert "No account, location permission, analytics, or upload is required." not in html
    assert "Cloudflare Web Analytics" in readme


def test_web_app_links_directly_to_public_play_enrollment() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

    assert "https://play.google.com/apps/testing/dev.mahlernim.timelinevisualizer" in html
    assert "https://github.com/mahlernim/google-timeline-visualizer/discussions/165" not in html
    assert 'https://forms.gle/mmM1ErM8nwtxNHez9' in html
    assert 'href="/google-timeline-visualizer/app/"' in html
    app = (ROOT / 'web' / 'app' / 'index.html').read_text(encoding='utf-8')
    assert 'docs/privacy-web.md' in app


def test_web_advertising_is_prepared_but_not_enabled() -> None:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    policy = (ROOT / "docs" / "privacy-web.md").read_text(encoding="utf-8")

    assert '<meta name="google-adsense-account" content="ca-pub-4628399954212784"' in html
    assert '<link rel="canonical" href="https://ahn-lab.org/google-timeline-visualizer/"' in html
    assert "pagead2.googlesyndication.com" not in html
    assert "adsbygoogle" not in html
    assert "Production advertising is not currently active" in policy
    assert "Timeline file contents" in policy
