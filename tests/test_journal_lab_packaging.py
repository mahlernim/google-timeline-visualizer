import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_FILE = ROOT / "app" / "build.gradle.kts"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
LAB_WORKFLOW = ROOT / ".github" / "workflows" / "journal-lab.yml"
VALIDATE_WORKFLOW = ROOT / ".github" / "workflows" / "validate.yml"


def test_journal_lab_has_a_separate_installation_identity() -> None:
    build_file = BUILD_FILE.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")

    lab_flavor = re.search(
        r'create\("journalLab"\) \{(?P<body>.*?)\n        \}',
        build_file,
        re.DOTALL,
    )
    assert lab_flavor is not None
    assert 'applicationId = "dev.mahlernim.timelinevisualizer.journallab"' in lab_flavor.group("body")
    assert 'versionCode = 20' in lab_flavor.group("body")
    assert 'versionName = "3.0.0-journal-lab.20"' in lab_flavor.group("body")
    assert 'buildConfigField("boolean", "IS_JOURNAL_LAB", "true")' in lab_flavor.group("body")
    assert 'releases/tag/journal-lab-20' in lab_flavor.group("body")
    assert 'manifestPlaceholders["appLabel"] = "Journal Lab"' in lab_flavor.group("body")
    assert 'android:label="${appLabel}"' in manifest


def test_travel_journal_is_enabled_for_production_flavors() -> None:
    build_file = BUILD_FILE.read_text(encoding="utf-8")

    for flavor in ("github", "play"):
        match = re.search(
            rf'create\("{flavor}"\) \{{(?P<body>.*?)\n        \}}',
            build_file,
            re.DOTALL,
        )
        assert match is not None
        assert 'buildConfigField("boolean", "IS_JOURNAL_LAB", "true")' in match.group("body")

    assert 'versionCode = 45' in build_file
    assert 'versionName = "3.0.3"' in build_file


def test_normal_validation_builds_the_journal_lab_variant() -> None:
    workflow = VALIDATE_WORKFLOW.read_text(encoding="utf-8")

    assert "testGithubDebugUnitTest testPlayDebugUnitTest lint" in workflow
    assert "assembleGithubDebug assemblePlayDebug assembleJournalLabDebug" in workflow
    assert "testJournalLabDebugUnitTest" in workflow
    assert "--tests dev.mahlernim.timelinevisualizer.JournalLabUiTest" in workflow
    assert "--tests dev.mahlernim.timelinevisualizer.JournalOnboardingUiTest" in workflow
    assert "--tests dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStoreTest" in workflow
    assert "--tests dev.mahlernim.timelinevisualizer.journal.JournalSetupNavigationTest" in workflow


def test_lab_release_is_immutable_verified_and_coinstallable() -> None:
    workflow = LAB_WORKFLOW.read_text(encoding="utf-8")

    for required in (
        'tags:\n      - "journal-lab-*"',
        "testGithubDebugUnitTest lintJournalLabRelease assembleGithubRelease assembleJournalLabRelease",
        "--tests dev.mahlernim.timelinevisualizer.JournalLabUiTest",
        "--tests dev.mahlernim.timelinevisualizer.JournalOnboardingUiTest",
        "--tests dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStoreTest",
        "--tests dev.mahlernim.timelinevisualizer.journal.JournalSetupNavigationTest",
        'test "$package_name" = "dev.mahlernim.timelinevisualizer.journallab"',
        'test "$application_label" = "Journal Lab"',
        "EXPECTED_VERSION_CODE: ${{ inputs.expected_version_code || '20' }}",
        "EXPECTED_VERSION_NAME: ${{ inputs.expected_version_name || '3.0.0-journal-lab.20' }}",
        'test "$LAB_RELEASE_TAG" = "journal-lab-$EXPECTED_VERSION_CODE"',
        'test "$version_name" = "$EXPECTED_VERSION_NAME"',
        'test "$lab_cert" = "$production_cert"',
        "adb install app/build/outputs/apk/github/release/app-github-release.apk",
        'git worktree add --detach "$RUNNER_TEMP/previous-lab" "$PREVIOUS_LAB_COMMIT"',
        '(cd "$RUNNER_TEMP/previous-lab" && ./gradlew :app:assembleJournalLabRelease --stacktrace)',
        'adb install "$RUNNER_TEMP/previous-lab/app/build/outputs/apk/journalLab/release/app-journalLab-release.apk"',
        "adb install -r app/build/outputs/apk/journalLab/release/app-journalLab-release.apk",
        'if gh release view "$LAB_RELEASE_TAG" >/dev/null 2>&1; then',
        'echo "Release $LAB_RELEASE_TAG already exists and will not be replaced."',
        'sha256sum "$lab_apk" > "$lab_apk.sha256"',
        '--notes-file "docs/${LAB_RELEASE_TAG}.md"',
        "--prerelease",
        "--verify-tag",
    ):
        assert required in workflow

    assert "--tests 'dev.mahlernim.timelinevisualizer.journal.reminder.*'" in workflow
    assert "--tests 'dev.mahlernim.timelinevisualizer.journal.route.*'" in workflow
    assert "--tests dev.mahlernim.timelinevisualizer.journal.JournalRepositoryTest" in workflow
