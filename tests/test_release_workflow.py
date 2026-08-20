from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
PROTOTYPE_WORKFLOW = ROOT / ".github" / "workflows" / "privacy-prototype.yml"


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


def test_privacy_prototype_workflow_is_isolated_from_production_releases() -> None:
    release_workflow = WORKFLOW.read_text(encoding="utf-8")
    prototype_workflow = PROTOTYPE_WORKFLOW.read_text(encoding="utf-8")

    assert '- "v*"' in release_workflow
    assert "codex/privacy-location-prototype" in prototype_workflow
    assert "gh release create" not in prototype_workflow
    assert "ANDROID_SIGNING_KEY_BASE64" not in prototype_workflow


def test_privacy_prototype_workflow_verifies_identity_and_uploads_both_files() -> None:
    workflow = PROTOTYPE_WORKFLOW.read_text(encoding="utf-8")

    assert "testPrototypeDebugUnitTest lintPrototypeDebug assemblePrototypeDebug" in workflow
    assert "apksigner\" verify --verbose" in workflow
    assert "dev.mahlernim.timelinevisualizer.privacyprototype" in workflow
    assert "2.1.3-privacy-prototype.1" in workflow
    assert "app-prototype-debug.apk.sha256" in workflow
    assert "retention-days: 30" in workflow


def test_repository_normalizes_text_without_touching_release_binaries() -> None:
    attributes = (ROOT / ".gitattributes").read_text(encoding="utf-8")

    assert "* text=auto eol=lf" in attributes
    for extension in ("png", "mp4", "apk", "aab", "jks", "keystore"):
        assert f"*.{extension} binary !eol" in attributes
