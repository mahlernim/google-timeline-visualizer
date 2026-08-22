# Timeline Visualizer Camera Lab 2

Camera Lab is an experimental Android build for comparing new zoom behavior before any production integration. It installs separately from Timeline Visualizer and updates Camera Lab 1 without affecting the production app.

## What changed

Camera Lab 1 reduced camera panning during zoom-in. Testing showed that this could let the location marker drift too far from the center. Camera Lab 2 replaces that behavior with **Travel slowdown during zoom-in**.

- Zoom-in keeps the same quick camera timing.
- Higher values slow marker travel while the viewport zooms in.
- The camera continues following the marker within a tighter central zone.
- Saved travel time is redistributed across non-zooming sections, which preserves the selected video duration.
- Zoom-out does not trigger a slowdown.

The four experimental zoom styles remain Fixed zoom, Balanced zoom, Active zoom, and Close-up zoom.

## What to test

Start with 0%, 30%, 60%, 80%, and 100%. Use the same Timeline file, period, duration, zoom style, and output format when comparing values. Check whether zoom-in stays quick, the marker remains comfortably framed, and the later compensation is smooth rather than feeling like a catch-up jump.

Record the selected percentage and approximate video timestamp if motion feels uncomfortable. Feedback about marker framing, travel speed, compensation, and preferred values is especially useful.

## Installation and comparison

The package ID is `dev.mahlernim.timelinevisualizer.cameralab`. It can remain installed beside production `dev.mahlernim.timelinevisualizer`. Installing Camera Lab 2 updates Camera Lab 1 and retains its separate settings and app data. If installing it for the first time, select the Timeline file independently inside Camera Lab.

## Privacy and limitations

Timeline processing remains on the device. CARTO receives requests for displayed map-tile areas under the same privacy model described by the project privacy policy. Do not upload or attach a private Timeline file when reporting feedback.

Camera Lab is not a production update and is not distributed through Google Play. The experimental terminology, Close-up preset, travel slowdown, and compensation algorithm may change or be removed after testing.

Related issues are #129 and #45.
