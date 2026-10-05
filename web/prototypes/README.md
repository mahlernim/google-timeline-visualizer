# CARTO vector experiment

This opt-in experiment compares the existing raster renderer with CARTO Positron through MapLibre. It does not switch the production web app or Android release to vector maps. Android's MapLibre dependency is debug-only.

Use synthetic routes for comparison. Never put a real API key in source, a command argument, a report, or a committed environment file.

## Web

Supply `VITE_CARTO_BASEMAP_API_KEY` in the shell environment. From `web`, run `pnpm exec vite --config vite.prototype.config.ts --host 127.0.0.1 --port 4173`. Open `http://127.0.0.1:4173/google-timeline-visualizer/prototypes/index.html`.

The comparison exports three 10-second H.264 MP4s with 240 frames each, covering raster, per-frame vector rendering, and vector background reuse. It preserves the existing route overlays and camera movement. Evidence is written under `app/build/vector-reuse-web/<run timestamp>`, preserving each run. The order control supports raster-first and reuse-first comparisons. Cancellation may leave partial evidence. A complete run contains three MP4s and three metrics JSON files. Older baseline evidence used `app/build/vector-prototype-web`.

The local evidence endpoint accepts bounded, whitelisted filenames from the loopback origin. It is only included by the prototype Vite configuration.

## Android

Supply `CARTO_BASEMAP_API_KEY` at build time. Run `:app:connectedGithubDebugAndroidTest` with `-Pandroid.testInstrumentationRunnerArguments.class=dev.mahlernim.timelinevisualizer.VectorBasemapBenchmarkTest` and `-Pandroid.testInstrumentationRunnerArguments.runVectorBenchmark=true`. Use an isolated emulator or test device. The benchmark is skipped unless explicitly enabled.

The test compares 240 480-pixel frames per route, including the outro, for all three modes. Add `-Pandroid.testInstrumentationRunnerArguments.benchmarkOrder=reverse` for the reverse order. It checks camera projection and opaque, nonuniform map content. It writes images and `metrics.json` to the target app's external files directory under `vector-reuse-comparison`. Copy these files before another Android run because matching filenames are overwritten. It does not encode Android MP4s or measure physical-device performance.

The prototype authenticates CARTO requests with the key, Android package, and actual installed signing certificate. A restricted test key must allow the debug signing certificate. Production should use separate web and Android keys. Allow all applicable Play signing certificates and the independently verified GitHub APK certificate on the Android key. New restricted keys have not been exercised by this experiment.

## Observations on 2026-10-05

Single desktop runs at 480 pixels exported the city, long-distance, and date-line journeys in roughly 0.9 to 1.0 seconds with raster and 5.0 to 5.3 seconds with vector. Vector tile request transformations numbered 8 to 9 versus 17 to 24 observed raster tile loads. Style, glyph, and sprite requests are additional. These are not billing measurements, and caches may be warm. Totals include three saved PNG frames.

The vector loop currently waits for MapLibre's idle event for every frame, limiting throughput near display refresh. This implementation needs export optimization before migration.

The Android host-GPU emulator rendered the city sample in 2.7 seconds with raster and 30.8 seconds with vector. The long-distance sample took 1.5 and 4.9 seconds respectively. Caches were warm. Native heap values are process snapshots, not isolated overhead or peak memory. A software-GPU run produced corrupt snapshots and was rejected after visual inspection. Do not extrapolate emulator timing to phones.

Positron retains the pale appearance but changes roads, labels, and their placement. Screenshots and encoded web videos show aligned route overlays. Web cancellation was exercised during active vector rendering. Android cancellation and full video export still need device validation.

Before production migration, optimize the render pipeline, test representative phones and output sizes, measure cold and warm resource use and peak memory, verify restricted-key rejection and acceptance, and add the linked CARTO logo requested by the provider. Existing textual attribution is retained in this prototype but is not completion of the provider's logo requirement.


## Background reuse experiment

The reuse mode renders a 1.5-times-wider and taller map into one temporary bitmap. The existing camera then crops that bitmap for each video frame. A new render is required when coverage is insufficient or output scale differs by more than 2%. Unwrapped Mercator coordinates preserve date-line crossings. Web constraining is overridden to keep the requested Mercator camera exact for oversized and wide views.

Reuse is approximate. Bilinear sampling can soften labels and roads, and refreshing the larger viewport can alter label placement. The 480-pixel output has a 720-pixel background bitmap of 2,073,600 bytes. At 1080 pixels this grows to 10,497,600 bytes. These numbers exclude MapLibre textures, caches, and encoder memory. Overscan can fetch extra tiles.

Two opposite-order desktop runs per route at 480 pixels showed the following export times. These are bounded local observations with warm caches, not a production performance guarantee.

| Route | Per-frame vector | Reuse | Reuse map renders | Vector / reuse tile transforms |
| --- | --- | --- | --- | --- |
| City | 5.04 to 5.05 s | 2.56 to 2.70 s | 75 / 240 | 8 / 10 |
| Long | 4.91 s | 1.96 to 2.05 s | 33 / 240 | 9 / 16 |
| Date line | 4.83 to 4.88 s | 1.54 to 1.61 s | 19 / 240 | 8 / 10 |

A single 1080-pixel city run took 5.31 s with per-frame vector rendering and 3.48 s with reuse. Raster took 2.15 s. Cancellation during active reuse restored the controls.

Two opposite-order host-GPU emulator runs took 5.68 to 6.68 s with per-frame city rendering and 4.40 to 5.48 s with reuse. Long-route results were 4.45 to 4.50 s and 1.57 to 1.62 s. The reuse mode rendered 86 city backgrounds and 25 long-route backgrounds for 240 frames each. These Android runs are rendering-only, with no MP4 encoding or physical-phone validation. Differences from the earlier 61-frame trial include frame sampling and emulator/cache state, so compare modes within these runs rather than timing against the earlier trial.

The experiment improves vector throughput but retains a speed and visual tradeoff relative to the raster baseline. No production renderer or key configuration is switched by this branch.
