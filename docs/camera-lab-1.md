# Timeline Visualizer Camera Lab 1

Camera Lab is an experimental Android build for comparing new zoom behavior before any production integration. It installs separately from Timeline Visualizer and does not replace the production app.

## What to test

- Fixed zoom follows the route without changing scale.
- Balanced zoom uses gentle zoom changes and remains the default.
- Active zoom adjusts framing more actively across a journey.
- Close-up zoom stays closer to local travel and still widens for long transfers.
- Zoom-in movement reduction ranges from 0% to 100% in 5% steps. Higher values slow map-center movement more while zooming in. Zoom-out movement is unchanged.

Start with 0%, 30%, 60%, 80%, and 100%. Use the same Timeline file, period, duration, zoom style, and output format when comparing values. Record the selected percentage and approximate video timestamp if motion feels uncomfortable.

## Installation and comparison

The package ID is `dev.mahlernim.timelinevisualizer.cameralab`. It can remain installed beside production `dev.mahlernim.timelinevisualizer`. Android keeps their settings and app data separate, so select the Timeline file independently in each app.

The launcher uses a blue Camera Lab icon with a LAB badge. The Settings screen also identifies the experimental build.

## Privacy and limitations

Timeline processing remains on the device. CARTO receives requests for displayed map-tile areas under the same privacy model described by the project privacy policy. Do not upload or attach a private Timeline file when reporting feedback.

Camera Lab is not a production update and is not distributed through Google Play. The experimental terminology, Close-up preset, and slowdown algorithm may change or be removed after testing.

Related issues are #129 and #45.
