from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "privacy-prototype.yml"
MAIN_RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"


def test_privacy_prototype_release_is_isolated_from_main_versions() -> None:
    prototype = WORKFLOW.read_text(encoding="utf-8")
    main = MAIN_RELEASE_WORKFLOW.read_text(encoding="utf-8")

    assert '"privacy-prototype-2"' in prototype
    assert 'tags:\n      - "v*"' in main
    assert 'tags:\n      - "v*"' not in prototype
    assert "dev.mahlernim.timelinevisualizer.privacyprototype" in prototype
    assert "2.2.5-privacy-prototype.2" in prototype


def test_privacy_prototype_release_verifies_and_publishes_required_artifacts() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "testPrivacyPrototypeReleaseUnitTest" in workflow
    assert "lintPrivacyPrototypeRelease" in workflow
    assert "assemblePrivacyPrototypeRelease" in workflow
    assert "bundlePrivacyPrototypeRelease" in workflow
    assert 'apksigner\" verify --verbose --print-certs' in workflow
    assert "app-privacyPrototype-release.aab" in workflow
    assert 'sha256sum "$apk"' in workflow
    assert "--prerelease" in workflow
