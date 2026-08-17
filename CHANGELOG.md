# Changelog

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
