from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_camera_lab_has_a_separate_identity_and_release_only_build():
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/camera-lab-release.yml").read_text(encoding="utf-8")

    assert 'create("cameraLab")' in gradle
    assert 'applicationIdSuffix = ".cameralab"' in gradle
    assert 'versionCode = 32' in gradle
    assert 'versionNameSuffix = "-camera-lab.2"' in gradle
    assert 'buildConfigField("boolean", "IS_CAMERA_LAB", "true")' in gradle
    assert 'buildConfigField("double", "DEFAULT_ZOOM_IN_TRAVEL_SLOWDOWN", "0.60")' in gradle
    lab_strings = (ROOT / "app/src/cameraLab/res/values/strings.xml").read_text(encoding="utf-8")
    assert "Timeline Visualizer Camera Lab" in lab_strings

    assert 'tags:\n      - "camera-lab-*"' in workflow
    assert "assembleCameraLabRelease" in workflow
    assert "dev.mahlernim.timelinevisualizer.cameralab" in workflow
    assert "--prerelease" in workflow
    assert "bundlePlayRelease" not in workflow
    assert ".aab" not in workflow


def test_camera_lab_release_notes_explain_side_by_side_testing_and_privacy():
    notes = (ROOT / "docs/camera-lab-2.md").read_text(encoding="utf-8")

    assert "installs separately" in notes
    assert "dev.mahlernim.timelinevisualizer.cameralab" in notes
    assert "0%, 30%, 60%, 80%, and 100%" in notes
    assert "preserves the selected video duration" in notes
    assert "Do not upload or attach a private Timeline file" in notes
    assert "not distributed through Google Play" in notes
