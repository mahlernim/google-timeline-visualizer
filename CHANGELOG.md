# Changelog

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
