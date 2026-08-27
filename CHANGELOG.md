# Changelog

## Unreleased

- Add a close-up camera mode to the web app.
- Add distance-unit selection to the web app.
- Document the web app's distance units and close-up mode.

## 3.0.2

- Remove clusters of consecutive impossible raw-signal readings, which previously survived because the spike filter only ever inspected a single point between its neighbours.
- Harmonize the Korean word for video on 동영상 across the app, the web app, and the store listing; the app had been using two words for the same English string on adjacent buttons.
- Improve desktop CLI error handling and remove dead code, contributed by @smshozab.
- Rewrite the Play Store listing in all nine locales to describe the Travel Journal release. Store metadata only; not part of the app.
- Set Android version code 44 and version name 3.0.2.

## 3.0.1

- Complete the localization of the 3.0.0 Travel Journal release across de, es, fr, ja, ko, pt-BR, zh-CN, and zh-TW: translate sixteen onboarding and disclosure strings that shipped marked untranslatable, retranslate twelve strings the Travel Journal rename left describing the old behavior, and correct machine-translation defects including "Library" as a book library, "Dismiss" as firing an employee, and counts whose placeholder bound to the wrong noun.
- Follow the zh-TW terminology review from #108 for strings added after it, and align the Korean bottom navigation on 라이브러리 · 만들기 · 설정.
- Remove preset sharing. The share button, the `https://ahn-lab.org/google-timeline-visualizer/?preset=` and `timelinevisualizer://preset` link handlers, the preset token codec, and the web app's link-forwarding card are gone from the Android app, the web app, and the `--preset` option of the desktop CLI. Saved presets, including built-in ones, are unaffected. Preset links shared from an earlier version no longer open.
- Set Android version code 43 and version name 3.0.1.

## 3.0.0

- Make Travel Journal the main Android experience, building a private on-device Journal from an imported `Timeline.json` that grows with later exports.
- Use detailed route observations where available and fill remaining periods with the best available Timeline history.
- Find trips, save projects, create monthly or yearly recaps, and reuse preserved route detail.
- Add optional quiet reminders that help preserve recent detail before it disappears from later exports.
- Show the current phase during long route preparation after a short wait.
- Give meaningful local arrivals more room in close-up camera timing while keeping the total adjustment bounded.
- Exclude the Travel Journal from Android backup and device transfer.
- Set Android version code 42 and version name 3.0.0.

## 2.4.2

- Restore map backgrounds after CARTO began requiring authenticated basemap requests.
- Use the project's CARTO key for official Android and web releases.
- Remove cached map tiles automatically after 30 days on Android.
- Set Android version code 41 and version name 2.4.2.

## 2.4.1

- Show clearer Raw Data availability text for a project covering a single date or a single location.
- Show the effective Raw date range in the web app.
- Add explicit frame rate controls to the web app.
- Set Android version code 40 and version name 2.4.1.

## 2.4.0

- Add the Trips and Recaps workflow with local trip detection, saved projects, and Library thumbnails.
- Create trip and recap videos from detected trips, a custom recap period, or the available raw location range.
- Allow recap titles to include the owner name.
- Use the available recent range with a fixed 100 m accuracy filter for Raw videos.
- Add advanced video settings and presets to the creation flow.
- Set Android version code 39 and version name 2.4.0.

## 2.3.5

- Add flexible export quality settings with 480p, 720p, 1080p, 1440p, 2160p, and custom resolutions.
- Add 24, 30, 60, and custom frame rate choices.
- Check the device's H.264 encoders, prefer hardware encoding, and retry compatible alternatives.
- Block unsupported export formats before encoding starts.
- Set Android version code 38 and version name 2.3.5.

## 2.3.4

- Fix generated videos running 1.5 seconds longer than the selected duration.
- Include the 1.5-second route overview ending within the selected total, so a 90-second selection creates a 90-second video.
- Set Android version code 37 and version name 2.3.4.

## 2.3.3

- Fix the Watch screen going black after switching to another app and back while a video was playing.
- Embed the resolved video title in exported MP4 metadata for media players and galleries.
- Upgrade the desktop Python CLI to match the Android feature set.
- Set Android version code 36 and version name 2.3.3.

## 2.3.2

- Deliver completed video notifications on a normal alert channel while progress updates stay quiet.
- Make Watch and Share in the system notification open the app's playback and sharing flows.
- Dismiss the completion notification after either action.
- Set Android version code 35 and version name 2.3.2.

## 2.3.1

- Save the five camera and aspect-ratio choices from Video defaults as a reusable preset.
- Show saved presets in Create video, applying them to the current draft until Set as default is chosen.
- Rename, delete, and share saved presets from the same flow.
- Share compact HTTPS preset links that carry only those five settings, never Timeline data, locations, dates, owner names, titles, filenames, or resolution.
- Show every value from an opened preset link for confirmation before anything is applied or saved.
- Preserve preset drafts across recreation.
- Set Android version code 34 and version name 2.3.1.

## 2.3.0

- Group aspect ratio, zoom style, long-trip detection, local trip framing, and long-trip pacing into one coherent set of video defaults.
- Separate export resolution, regional preferences, and Timeline cleanup into their own settings sections.
- Apply the Camera Lab results, including earlier destination framing and grouped long transfers.
- Remove the experimental zoom slowdown.
- Set Android version code 33 and version name 2.3.0.

## 2.2.13

- Fix issue where the video-generation notification remained visible in the system tray after the user clicked Done in the app.

## 2.2.12

- Render every map tile intersecting portrait and landscape export frames instead of truncating the visible tile grid.
- Preserve complete right-side and lower-edge map coverage during preview, video rendering, and overview generation.
- Add regression coverage for high-resolution portrait and landscape tile grids that require more than 36 tiles.
- Set Android version code 31 and version name 2.2.12.

## 2.2.11

- Wait for video-rendering throughput to stabilize before showing an estimated remaining time.
- Calculate the estimate from recent journey-frame throughput instead of fixed whole-export phase weights.
- Hide the estimate during map preparation, finishing, and materially unstable rendering speeds.
- Use the same estimator for the in-app progress tray and foreground notification.
- Set Android version code 30 and version name 2.2.11.

## 2.2.10

- Move settings, video-export state, and the creations list behind lifecycle-aware ViewModels.
- Replace the creations thumbnail generation counter with structured coroutine cancellation and retain the stable row identity guard.
- Preserve the v2.2.9 preprocessed Timeline cache and v2.2.8 completed-export acknowledgment behavior.
- Make the desktop CLI reject missing input files and unavailable FFmpeg before parsing or frame preparation.
- Raise typed Timeline parsing and no-data errors instead of terminating from reusable parsing code.
- Document the intentionally process-lifetime desktop tile cache and add focused failure-path tests.
- Set Android version code 29 and version name 2.2.10.

## 2.2.9

- Save normalized semantic and raw-signal points in the app-private cache after a successful Timeline import.
- Open remembered Timeline files from a compact versioned cache when the source URI, size, and modification time still match.
- Always reprocess a manually selected Timeline file so users can explicitly refresh cached data.
- Fall back safely to the original JSON when cached data is missing, stale, incompatible, or damaged.
- Set Android version code 28 and version name 2.2.9.

## 2.2.8

- Clear the completed Video ready tray after watching or sharing the matching video.
- Clear the tray when the user intentionally opens or reopens My videos.
- Keep unacknowledged completions visible across automatic app startup and screen restoration.
- Preserve every generated video when its transient completion state is cleared.
- Set Android version code 27 and version name 2.2.8.

## 2.2.7

- Prepare every map tile used by every video frame and the final overview before encoding begins.
- Load missing map tiles with four bounded workers and retry transient failures once.
- Stop with a localized connection message instead of silently creating a video with missing map areas.
- Use unique atomic cache writes and preserve export cancellation during tile preparation.
- Set Android version code 26 and version name 2.2.7.

## 2.2.6

- Add a Done action to dismiss the persistent Video ready tray.
- Clear only the completed export status when the tray is dismissed.
- Keep the generated video available in My videos after dismissal.
- Prevent the dismissed completion tray from returning after the app restarts.
- Set Android version code 25 and version name 2.2.6.

## 2.2.5

- Add Automatic, Kilometers, and Miles distance-unit choices.
- Resolve Automatic from the device region and show the resolved unit directly in Settings.
- Apply the selected unit consistently to selected-period summaries, previews, and exported videos.
- Restore Automatic together with the other video defaults.
- Preserve kilometers internally for route processing and convert only user-facing distances.
- Translate the new distance-unit setting in all nine supported app languages.
- Set Android version code 24 and version name 2.2.5.

## 2.2.4

- Keep video-generation progress visible above bottom navigation while scrolling or switching app tabs.
- Show the current generation phase, percentage, estimated remaining time when available, and Cancel in one persistent tray.
- Change the tray to Watch and Share when a video is ready, or Retry when generation fails.
- Keep the preview playback scrubber separate from generation progress.
- Announce major generation phase and result changes without announcing every percentage update.
- Preserve the existing export service, background notifications, saved export recovery, and cancellation behavior.
- Translate the new Retry action in all nine supported app languages.
- Set Android version code 23 and version name 2.2.4.

## 2.2.3

- Recognize raw location records that may accompany or replace processed Timeline visits and trips.
- Warn before using a raw-only export and offer to open Google Maps so the user can restore or confirm Timeline before exporting again.
- Keep raw location processing optional when processed visits and trips are available.
- Reduce raw-data noise with a configurable accuracy limit, stationary uncertainty collapse, and short impossible-jump rejection without averaging coordinates.
- Mark raw-data distance as an estimate and report excluded raw points.
- Add the fallback, controls, and warning to Android and the web app.
- Translate the Android experience in all nine supported app languages.
- Credit `@PeaShooterR` for the raw location import mode contributed in PR #84.
- Set Android version code 22 and version name 2.2.3.

## 2.2.2

- Follow the Android system setting with matching light and dark app interfaces.
- Keep Road map tiles and exported video appearance unchanged in both system themes.
- Extend the bottom-navigation surface behind the gesture indicator or navigation-button area.
- Preserve complete bottom-navigation icons and labels without applying the system inset twice.
- Set Android version code 21 and version name 2.2.2.

## 2.2.1

- Prevent repeated or backtracking routes when independent semantic and path histories cover the same time.
- Treat activity and visit segments as authoritative while retaining standalone path points outside their coverage.
- Preserve path detail stored inside the same semantic segment and keep path-only exports unchanged.
- Apply matching reconciliation behavior to Android, the web app, and the Python renderer.
- Keep processing local without changing the source Timeline file.
- Set Android version code 20 and version name 2.2.1.

## 2.2.0

- Add fixed portrait 1080×1920 and landscape 1920×1080 video formats.
- Keep the existing square 480p, 720p, and 1080p formats and the square 480p default unchanged.
- Match the preview and generated journey overview to the selected output aspect ratio.
- Keep titles, dates, attribution, markers, and the ending overview proportionate in every format.
- Check H.264 encoder size, frame-rate, bitrate, alignment, and color-layout support before preparing map tiles.
- Disable unsupported output with a localized message instead of silently substituting another format.
- Preserve existing saved settings and pending exports without a storage migration.
- Retain the original route-stroke appearance for square videos.
- Keep every Settings dropdown choice available after selection and navigation.
- Add an in-app language selector for System default and all nine supported languages.
- Show the installed version name and version code in Settings.
- Replace deprecated Gson leniency handling and document the required legacy H.264 color layouts without changing import or export behavior.
- Credit Rafiqi Rachmat (`@akunlainfiqi`) for the format-preset design and implementation contributed in PR #57.
- Set Android version code 19 and version name 2.2.0.

## 2.1.3

- Fix invalid translated preview date patterns that terminated the first frame in German, Spanish, French, and Portuguese.
- Render a first preview frame in every supported language during regression testing.
- Show the initial Timeline preview without synchronously building the complete camera track.
- Build all 481 smooth camera positions in the background and keep playback controls disabled until ready.
- Reuse exact route-range bounds instead of rescanning dense routes for every camera position.
- Reduce temporary allocations in route projection, location filtering, range selection, and transfer preparation.
- Replace padded import fixtures with point-dense compact and long-gap device coverage.
- Preserve Timeline points, filtering, ordering, distances, route geometry, and completed camera behavior.
- Open Create video on a cold launch when the video library is empty, while preserving export recovery and restored navigation state.
- Explain that an empty Timeline export may mean Timeline was not enabled and Google had no location data to export.
- Set Android version code 18 and version name 2.1.3.

## 2.1.2

- Keep interpolated route samples virtual instead of retaining millions of route objects.
- Replace route-sized first-frame projection collections with compact source-point indexes.
- Use primitive arrays for transfer-threshold statistics to avoid boxed number collections.
- Add a dense long-gap import fixture below 16 MB and Android API 36 device coverage.
- Preserve Timeline points, filtering, ordering, distances, and rendered route geometry.
- Set Android version code 17 and version name 2.1.2.

## 2.1.1

- Reduce peak memory while normalizing large Timeline JSON exports.
- Prepare filtered Timeline data and the initial Journey away from the UI thread.
- Avoid building a second unfiltered Journey only to count ignored locations.
- Stop automatically reopening a remembered Timeline after an interrupted import.
- Add generated 45 MB import coverage without storing personal Timeline data.
- Set Android version code 16 and version name 2.1.1.

## 2.1.0

- Prevent system navigation insets from clipping bottom-navigation icons and labels.
- Add conservative, local GPS outlier filtering with a persistent Off option and an ignored-point count.
- Keep the original Timeline JSON unchanged and use one filtered Journey for preview, seeking, overview, and export.
- Add custom whole-number journey durations from 10 through 300 seconds while keeping the existing presets and 30-second default.
- Warn when a duration over 60 seconds may require more rendering time and storage.
- Keep all nine supported app languages complete.
- Set Android version code 15 and version name 2.1.0.

## 2.0.1

- Parse current Timeline path points that store a minute offset from the segment
  start instead of an absolute timestamp.
- Prefer valid absolute path timestamps and safely ignore invalid, negative, or
  out-of-range offsets.
- Distinguish malformed JSON, older Google Takeout formats, raw-only exports,
  and exports without usable locations.
- Keep Android and Python Timeline parsing behavior aligned.
- Correct the Play submission checklist and English privacy-policy wording.
- Set Android version code 14 and version name 2.0.1.

## 2.0.0

- Add bottom navigation for My videos, Create video, and Settings while preserving unfinished creation state.
- Add a full-screen in-app Media3 video player with seeking, sharing, error handling, and external-player fallback.
- Add Steady, Fixed, and Dynamic camera movement settings with improved long-movement anticipation and asymmetric zoom smoothing.
- Add long-trip compression with Balanced `0.85` as the default while preserving route geometry and total duration.
- Add 480p, 720p, and 1080p video quality options and exact date ranges.
- Add deletion of every available library video after confirmation.
- Keep Android preview, seeking, export timing, and the Python renderer aligned.
- Add Simplified Chinese, Traditional Chinese, Spanish, French, German, and Brazilian Portuguese.
- Regenerate English, Korean, and Japanese store screenshots for the new navigation.
- Set Android version code 13 and version name 2.0.0.

## 1.9.0

- Implements issues #21, #23, and #24.
- Split the Videos library from the focused New video workflow.
- Add a live Selected period summary using the exact Journey used for preview and rendering.
- Automatically save videos through MediaStore on Android 10 and later, with pending-item cleanup and collision-safe names.
- Keep Android 8 and 9 on the permission-safe system Save As flow.
- Add Save as copying and optional Journey overview saving without rerendering.
- Standardize English, Korean, and Japanese terminology and state messages.
- Refresh the Material 3 light identity, semantic palette, canonical SVG mark, and monochrome launcher icon.
- Regenerate localized store screenshots and update privacy, listing, and release documentation.

## 1.8.1

- Implements GitHub issue #19.
- Add a localized in-app link for restoring an encrypted Google Maps Timeline
  backup before exporting a new JSON file.
- Add English, Korean, and Japanese restoration guides with accessible step
  diagrams, official Google help links, and clear recovery limits.
- Clarify that backup restoration, JSON export, and Timeline Visualizer import
  are separate operations.
- Polish user-facing app copy, installation instructions, listings, and release
  documentation across all three supported languages.
- Replace the rarely used 75-second and 90-second choices with 10-second and
  20-second options. Available durations are now 10, 15, 20, 30, 45, and 60 seconds.

## 1.8.0

- Implements GitHub issues #11 through #17 as one coordinated release.
- Keep the complete final route inside the visible map below the title card in previews, videos, thumbnails, and overview images.
- Use the final journey overview as the deterministic thumbnail for newly created videos.
- Save or share a 1080 × 1080 journey overview PNG after video creation.
- Remember and reopen the most recently used Timeline document when Android retains access.
- Show active, stage-aware feedback while large Timeline files are opened and prepared.
- Select inclusive month ranges across multiple years with explicit start and end years.
- Add complete Japanese app, renderer, privacy, documentation, and Play Store resources.
- Localize rendered dates, numbers, distance units, fallback titles, and user-facing failures.

## 1.7.0

- Keep video creation responsive on dense yearly Timelines by drawing a bounded,
  pixel-simplified trail instead of reprocessing the complete route every frame.
- Fade older travel behind the moving marker while keeping the newest route clear.
- Add a 1.5-second ending that zooms out, reveals the complete journey, and holds
  the finished overview for half a second.
- Add 45-second and 75-second journey durations.
- Prefer a device-reported hardware H.264 encoder when a compatible one is available.
- Show a separate finishing stage while the overview ending is rendered.

## 1.6.1

- Start videos with a clean map instead of drawing the entire future route.
- Reveal the traveled route progressively behind the moving position marker.
- Keep the stronger recent trail, stabilized camera, and long-distance tracking.

## 1.6.0

- Continue video creation in a foreground media-processing service when the user
  switches apps or turns off the screen.
- Show progress and estimated time in an optional Android notification, with a
  Cancel action and Watch and Share actions when the video is ready.
- Preserve the pending route and progress in private app storage so interrupted
  work can restart after Android recreates the app process.
- Remove incomplete output and temporary route data after cancellation or failure.

## 1.5.0

- Add a central camera dead zone so routine back-and-forth travel does not move
  the entire map on every frame.
- Smooth camera scale changes and add zoom hysteresis to prevent rapid zoom
  breathing around tile-level boundaries.
- Precompute a deterministic camera track so preview, seeking, replay, tile
  preparation, and final MP4 generation use the same view.
- Preserve every Timeline point and retain adaptive tracking for long-distance trips.

## 1.4.0

- Add a clear first-load disclosure before map-area tile coordinates and normal
  network information are sent to CARTO, with an option to cancel.
- Add direct English and Korean privacy-policy links inside the app.
- Route **Check for updates** to GitHub Releases for direct installs and to Google
  Play for Play-distributed installs.
- Add a separately labeled project source link and prevent cleartext network traffic.
- Add an adaptive launcher icon and explicit English and Korean language support.
- Prepare a signed Android App Bundle and complete bilingual Play listing materials.

## 1.3.0

- Add a persistent Creations library for generated and imported MP4 videos.
- Keep durable access to user-selected videos and show thumbnails, titles, dates,
  durations, and Timeline periods when available.
- Add Watch, Share, Remove from list, and separately confirmed Delete video actions.
- Detect moved or deleted files without removing their library entries automatically.
- Add multi-select import for videos made before the Creations library was added.
- Add a user-facing link to check the latest GitHub release.
- Keep movement processing unchanged; small valid movements remain part of the route.

## 1.2.0

- Make page scrolling responsive by caching preview frames and prepared route geometry.
- Save reusable title templates with `{year}` and `{name}` placeholders, and apply
  typing changes after a short delay or when the field loses focus.
- Rename the main actions to Load Timeline, Preview, and Create video.
- Add cancellation with incomplete-file cleanup during video creation.
- Show phase-aware progress and an estimated time remaining once enough progress
  has been measured.
- Add a Video ready panel for watching, sharing, or creating another video.
- Refine and proofread the English and Korean guidance.

## 1.1.0

- Add smooth great-circle interpolation and camera tracking for long trips.
- Add start and end month selection; the full year remains the default.
- Build the default title from the selected year and an editable device name.
- Add in-app Timeline export instructions and a shortcut to Location settings.
- Add a visible Share button for the most recently exported video.
- Restart playback from the beginning when Play is pressed after completion.
- Add English and Korean installation and usage guides.
- Preserve and test iOS export support contributed by @keenranger in #2.

## 1.0.0

- Introduce the native Android app with local Timeline JSON import, preview, and
  H.264 MP4 export.
- Support current Android/iOS exports and older semantic-segment exports.
