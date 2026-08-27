from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
JOURNAL_LAB_WORKFLOW = ROOT / ".github" / "workflows" / "journal-lab.yml"


def test_release_workflow_verifies_apk_before_publishing() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    verify_index = workflow.index("- name: Verify signed GitHub APK")
    publish_index = workflow.index("- name: Publish GitHub release")

    assert verify_index < publish_index
    for required_check in (
        '"$build_tools_dir/apksigner" verify --verbose --print-certs',
        '"$build_tools_dir/aapt" dump badging',
        'test "$package_name" = "dev.mahlernim.timelinevisualizer"',
        'test "$version_code" = "$expected_version_code"',
        'test "$version_name" = "$expected_version_name"',
        'test "$version_name" = "${RELEASE_TAG#v}"',
        'sha256sum "$apk_path"',
    ):
        assert required_check in workflow


def test_release_workflow_can_update_an_existing_release() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "workflow_dispatch:" in workflow
    assert "Existing release tag to build and update" in workflow
    assert 'ref: ${{ env.RELEASE_TAG }}' in workflow
    assert 'gh release view "$RELEASE_TAG"' in workflow
    assert 'gh release upload "$RELEASE_TAG" "$apk" "$apk.sha256" --clobber' in workflow


def test_release_workflow_uses_production_version_when_flavors_override_it() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "expected_version_code=\"$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*$/\\1/p' app/build.gradle.kts | head -n 1)\"" in workflow
    assert "expected_version_name=\"$(sed -nE 's/^[[:space:]]*versionName = \"([^\"]+)\".*$/\\1/p' app/build.gradle.kts | head -n 1)\"" in workflow


def test_release_workflow_requires_the_carto_project_key() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "CARTO_BASEMAP_API_KEY: ${{ secrets.CARTO_BASEMAP_API_KEY }}" in workflow
    assert 'test -n "$CARTO_BASEMAP_API_KEY"' in workflow


def test_repository_normalizes_text_without_touching_release_binaries() -> None:
    attributes = (ROOT / ".gitattributes").read_text(encoding="utf-8")

    assert "* text=auto eol=lf" in attributes
    for extension in ("png", "mp4", "apk", "aab", "jks", "keystore"):
        assert f"*.{extension} binary !eol" in attributes


def test_journal_lab_workflow_can_rebuild_an_existing_immutable_tag() -> None:
    workflow = JOURNAL_LAB_WORKFLOW.read_text(encoding="utf-8")

    for required in (
        "workflow_dispatch:",
        "Existing immutable Journal Lab tag",
        "LAB_RELEASE_TAG: ${{ inputs.release_tag || github.ref_name }}",
        "ref: ${{ env.LAB_RELEASE_TAG }}",
        'test "$(git describe --tags --exact-match)" = "$LAB_RELEASE_TAG"',
        'test "$LAB_RELEASE_TAG" = "journal-lab-$EXPECTED_VERSION_CODE"',
        'if gh release view "$LAB_RELEASE_TAG" >/dev/null 2>&1; then',
        '--verify-tag',
    ):
        assert required in workflow
