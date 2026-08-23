# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Timeline Visualizer turns a Google Maps Timeline export (`Timeline.json`) into an
animated MP4 of the user's travels. The same core algorithm (camera movement,
journey timing/compression, map-tile fetching, GPS outlier filtering) is
implemented **three times, independently**:

| Surface | Language | Path | Distribution |
|---|---|---|---|
| Android app | Kotlin | `app/` | APK on GitHub Releases + Play Store (two flavors) |
| iPhone/web app | TypeScript | `web/` | Static site on GitHub Pages, renders MP4 client-side via `mediabunny` |
| Original CLI | Python | `visualizer.py` | `pip install -r requirements.txt`, run directly |

**When changing animation, camera, timing, or filtering behavior, check whether
the same change is needed in the other two implementations.** Numeric constants
(e.g. `CameraMovement` values) are intentionally kept identical across Kotlin,
TypeScript, and Python — see `app/src/main/java/dev/mahlernim/timelinevisualizer/render/CameraSettings.kt`,
`web/src/camera.ts`, and the `CAMERA_MOVEMENTS` dict in `visualizer.py`.

## Commands

### Android (`app/`, from repo root)

```
./gradlew test lint assembleGithubDebug assemblePlayDebug   # what CI runs on every PR
./gradlew test                                                # unit tests (JVM/Robolectric)
./gradlew test --tests "*.VideoFormatDeviceTest"               # single test class
./gradlew connectedGithubDebugAndroidTest                      # instrumented tests, needs a device/emulator
```

Two product flavors exist along the `distribution` dimension: `github` and `play`
(different update-check URLs). Build/test tasks are flavor-qualified, e.g.
`assembleGithubDebug`, `bundlePlayRelease`. Release signing reads
`ANDROID_SIGNING_STORE_FILE`/`_PASSWORD`/`_KEY_ALIAS`/`_KEY_PASSWORD` from the
environment; there is no local keystore checked in.

CI also runs a device smoke test matrix (API 28/35/36) via
`connectedGithubDebugAndroidTest` on an emulator — see `.github/workflows/validate.yml`.

### Web (`web/`, uses pnpm)

```
pnpm install --frozen-lockfile
pnpm test                # vitest run
pnpm test -- camera       # run tests matching a filename/pattern
pnpm typecheck            # tsc -b --pretty false
pnpm build                # tsc -b && vite build
pnpm dev                  # local dev server
```

### Python CLI (repo root, Python 3.13)

```
python -m pip install -r requirements-dev.txt
python -m pytest
python -m pytest tests/test_camera.py -k some_test    # single test
./visualizer.py <Timeline.json> <year> [options]        # run the CLI directly
```

## Android app architecture

Single-Activity, no Fragments, no Jetpack Compose: `MainActivity.kt` (~2,700
lines) owns all four screens via ViewBinding over one `activity_main.xml`, with
per-screen layouts (`screen_new_video.xml`, `screen_videos.xml`,
`screen_player.xml`, `screen_settings.xml`) shown/hidden inside it. There is no
DI framework (no Hilt/Dagger) — collaborators are constructed directly.

Package layout under `app/src/main/java/dev/mahlernim/timelinevisualizer/`:

- `model/` — plain data types (`TimelineModels`, `TitleTemplate`, `VideoDuration`).
- `data/` — Timeline JSON parsing (`TimelineParser`), GPS outlier filtering
  (`LocationOutlierFilter`), map tile fetching/caching (`TileRepository`),
  preprocessed-timeline caching (`TimelineCache`), and the remembered
  document reference (`TimelineSourceStore`).
- `render/` — the animation/camera math shared conceptually with `web/` and
  `visualizer.py`: `CameraSettings` (movement/compression/trip-detection/video
  quality enums), `JourneyTiming`, `TimelineAnimation`, `TimelinePainter`
  (draws frames), `RenderText` (localized overlay strings).
- `export/` — MP4 rendering pipeline: `VideoExportService` (foreground
  service) → `Mp4Exporter` (frame-by-frame encode via media3-muxer) using
  `MapTilePreparer` and gated by `VideoEncoderSupport` (queries
  `MediaCodecList` for real device encoder capabilities before allowing a
  format). `VideoExportRequest`/`VideoExportRequestStore` persist an in-flight
  export as a **hand-rolled versioned binary format** (`CURRENT_FILE_VERSION`,
  currently 8) so an export survives process death; each version bump adds a
  migration branch in `load()` — never remove old branches, and bump the
  version when the persisted shape changes. `ExportEtaEstimator` and
  `VideoExportState`/`VideoExportViewModel` track progress.
- `presets/` — shareable video presets (`VideoPreset`, `PresetCodec`,
  `PresetLink`, `PresetRepository`) that encode settings into a shareable
  link/token, consumed by both the Android app and the web app.
- `videos/` — the local video library (`VideoStore`, `VideoMedia`,
  `GeneratedMediaRepository`, `VideoLibraryViewModel`) tracking generated MP4s
  outside app-private storage, with thumbnails and "file unavailable" handling
  when a file is moved/deleted externally.
- `ui/` — settings persistence (`CameraSettingsPreferences`,
  `DistanceUnitPreferences`, `LocationFilterPreferences`, `AppLanguage`),
  `SettingsViewModel`, and view helpers.

`VideoQuality` (in `render/CameraSettings.kt`) is a **closed enum** of 9 fixed
width/height/frameRate/bitrate combinations (square/portrait/landscape ×
480p/720p/1080p, plus a couple of intermediate sizes) — there is currently no
arbitrary custom resolution or frame rate. `VideoEncoderSupport.evaluate()`
checks a requested `VideoQuality` against the device's actual encoder
capabilities (size range, alignment, max frame rate for that size, bitrate
range, color format) and returns a typed `Unsupported` reason rather than
silently substituting a different format.

The app supports 9 languages (en + `values-{de,es,fr,ja,ko,pt-rBR,zh-rCN,zh-rTW}`);
the web app mirrors the same set under `web/src/locales/`.

## Web app architecture (`web/`)

Vanilla TypeScript (no framework), Vite build, Vitest for tests — one
`*.test.ts` alongside each module. `main.ts` wires DOM elements directly to
the modules: `timeline.ts` (parsing/date range selection), `geo.ts`/`camera.ts`
(the ported animation math), `renderer.ts` (canvas drawing, shared concept
with `TimelinePainter.kt`), `outlier.ts` (GPS filtering, mirrors
`LocationOutlierFilter.kt`), `video.ts` (MP4 encoding via `mediabunny`,
mirrors `Mp4Exporter.kt`/`VideoEncoderSupport.kt`), `preset-link.ts` (mirrors
`presets/` on Android), and `i18n.ts`/`i18n-dom.ts`.

## Python CLI (`visualizer.py`)

Single-file script; matplotlib/PIL for rendering, ffmpeg for final encode.
Mirrors the camera/timing/tile logic in `CAMERA_MOVEMENTS`,
`build_journey_timing`, `build_camera_track`, and `get_map_image`. Custom
exceptions (`TimelineParseError`, `NoDataFoundError`, `FfmpegUnavailableError`)
subclass `TimelineCliError` and are caught at the CLI boundary for clean error
messages instead of tracebacks.

## Tests

- Android unit tests: `app/src/test/` (40 files, JVM + Robolectric).
- Android instrumented tests: `app/src/androidTest/` (device/emulator-only,
  includes `VideoFormatDeviceTest` which asserts against real
  `MediaCodecList` capabilities).
- Web: `web/src/*.test.ts` beside each module, run with `pnpm test`.
- Python: `tests/*.py`, run with `pytest`, covering the parser, camera math,
  CLI error handling, app language configuration, and even Play Store/release
  material consistency (`test_play_store_materials.py`,
  `test_release_workflow.py`).
