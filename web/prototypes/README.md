# CARTO vector experiment

This opt-in experiment compares the existing raster renderer with CARTO Positron through MapLibre. It does not switch the production web app or Android release to vector maps. Android's MapLibre dependency is debug-only.

Use synthetic routes for comparison. Never put a real API key in source, a command argument, a report, or a committed environment file.

## Web

Supply `VITE_CARTO_BASEMAP_API_KEY` in the shell environment. From `web`, run `pnpm exec vite --config vite.prototype.config.ts --host 127.0.0.1 --port 4173`. Open `http://127.0.0.1:4173/google-timeline-visualizer/prototypes/index.html`.

The comparison exports two 10-second H.264 MP4s with 240 frames each. It preserves the existing route overlays and camera movement. Evidence is written under `app/build/vector-prototype-web`. Each new comparison overwrites matching evidence filenames, so copy a completed run before repeating it. Cancellation may leave partial PNG evidence and a completed raster result.

The local evidence endpoint accepts bounded, whitelisted filenames from the loopback origin. It is only included by the prototype Vite configuration.

## Android

Supply `CARTO_BASEMAP_API_KEY` at build time. Run `:app:connectedGithubDebugAndroidTest` with `-Pandroid.testInstrumentationRunnerArguments.class=dev.mahlernim.timelinevisualizer.VectorBasemapBenchmarkTest` and `-Pandroid.testInstrumentationRunnerArguments.runVectorBenchmark=true`. Use an isolated emulator or test device. The benchmark is skipped unless explicitly enabled.

The test compares 61 sampled 480-pixel frames per route, including the outro. It checks camera projection and opaque, nonuniform map content. It writes images and `metrics.json` to the target app's external files directory under `vector-comparison`. It does not encode Android MP4s or measure physical-device performance.

The prototype authenticates CARTO requests with the key, Android package, and actual installed signing certificate. A restricted test key must allow the debug signing certificate. Production should use separate web and Android keys. Allow all applicable Play signing certificates and the independently verified GitHub APK certificate on the Android key. New restricted keys have not been exercised by this experiment.

## Observations on 2026-10-05

Single desktop runs at 480 pixels exported the city, long-distance, and date-line journeys in roughly 0.9 to 1.0 seconds with raster and 5.0 to 5.3 seconds with vector. Vector tile request transformations numbered 8 to 9 versus 17 to 24 observed raster tile loads. Style, glyph, and sprite requests are additional. These are not billing measurements, and caches may be warm. Totals include three saved PNG frames.

The vector loop currently waits for MapLibre's idle event for every frame, limiting throughput near display refresh. This implementation needs export optimization before migration.

The Android host-GPU emulator rendered the city sample in 2.7 seconds with raster and 30.8 seconds with vector. The long-distance sample took 1.5 and 4.9 seconds respectively. Caches were warm. Native heap values are process snapshots, not isolated overhead or peak memory. A software-GPU run produced corrupt snapshots and was rejected after visual inspection. Do not extrapolate emulator timing to phones.

Positron retains the pale appearance but changes roads, labels, and their placement. Screenshots and encoded web videos show aligned route overlays. Web cancellation was exercised during active vector rendering. Android cancellation and full video export still need device validation.

Before production migration, optimize the render pipeline, test representative phones and output sizes, measure cold and warm resource use and peak memory, verify restricted-key rejection and acceptance, and add the linked CARTO logo requested by the provider. Existing textual attribution is retained in this prototype but is not completion of the provider's logo requirement.
