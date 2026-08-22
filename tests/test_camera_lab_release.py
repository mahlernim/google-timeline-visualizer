from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_camera_lab_has_a_separate_identity_and_release_only_build():
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/camera-lab-release.yml").read_text(encoding="utf-8")

    assert 'create("cameraLab")' in gradle
    assert 'applicationIdSuffix = ".cameralab"' in gradle
    assert 'versionCode = 35' in gradle
    assert 'versionNameSuffix = "-camera-lab.5"' in gradle
    assert 'buildConfigField("boolean", "IS_CAMERA_LAB", "true")' in gradle
    assert "DEFAULT_ZOOM_IN_TRAVEL_SLOWDOWN" not in gradle
    assert 'buildConfigField("boolean", "DEFAULT_EPISODE_FRAMING", "true")' in gradle
    assert 'releases/tag/camera-lab-5' in gradle
    lab_strings = (ROOT / "app/src/cameraLab/res/values/strings.xml").read_text(encoding="utf-8")
    assert "Timeline Visualizer Camera Lab" in lab_strings

    assert 'tags:\n      - "camera-lab-*"' in workflow
    assert "assembleCameraLabRelease" in workflow
    assert "dev.mahlernim.timelinevisualizer.cameralab" in workflow
    assert 'release_number="${RELEASE_TAG#camera-lab-}"' in workflow
    assert 'expected_version_code="$(awk' in workflow
    assert 'expected_version_name="${base_version_name}-camera-lab.${release_number}"' in workflow
    assert '--notes-file "docs/${RELEASE_TAG}.md"' in workflow
    assert "--prerelease" in workflow
    assert "bundlePlayRelease" not in workflow
    assert ".aab" not in workflow


def test_camera_lab_release_notes_explain_side_by_side_testing_and_privacy():
    notes = (ROOT / "docs/camera-lab-5.md").read_text(encoding="utf-8")

    assert "installs separately" in notes
    assert "dev.mahlernim.timelinevisualizer.cameralab" in notes
    assert "Local trip framing" in notes
    assert "final 25%" in notes
    assert "slowdown mechanism has been removed" in notes
    assert "consecutive detected transfer hops" in notes
    assert "75 km visual route subdivision is unchanged" in notes
    assert "do not upload a private timeline file" in notes.lower()
    assert "not distributed through Google Play" in notes
