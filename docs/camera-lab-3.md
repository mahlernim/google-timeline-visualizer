# Timeline Visualizer Camera Lab 3

Camera Lab is an experimental Android build for comparing camera behavior before production integration. It installs separately from Timeline Visualizer and updates Camera Lab 1 or 2 without affecting the production app.

## What changed

Camera Lab 3 adds **Local trip framing** for round trips with a long transfer, local travel, and a return transfer.

- Local trip framing limits camera scope to the current local episode instead of allowing a future return trip to keep the view wide.
- The camera begins widening again shortly before the next long transfer.
- Trip detection offers Conservative, Balanced, and Sensitive presets.
- Local framing offers Wide, Balanced, and Close presets.
- The experimental behavior can be turned off for a direct comparison with Camera Lab 2 behavior.

The detector compares large route jumps with the journey's ordinary sampling distance. Camera Lab 3 intentionally exposes only preset bundles. Lower-level thresholds and departure timing are fixed for this first human evaluation.

Camera Lab 2 behavior remains available. Zoom-in stays quick, the default Travel slowdown during zoom-in remains 60%, marker travel slows during zoom-in, and saved travel is redistributed. This preserves the selected video duration. Initial personal testing found approximately 50% to 75% comfortable.

## What to test

Use a route that includes an origin area, a long transfer, destination travel, and a return transfer. Keep the same Timeline file, period, duration, zoom style, and output format when comparing settings.

1. Turn Local trip framing off and export a baseline.
2. Turn it on with Balanced trip detection and Balanced local framing.
3. If a transfer is missed, try Sensitive. If local travel is divided incorrectly, try Conservative.
4. Compare Wide, Balanced, and Close local framing after the destination is reached.

Please report whether arrival was recognized, whether local framing began too early or too late, whether ordinary local travel was divided incorrectly, and whether the local view felt too wide or too close. An approximate video timestamp is useful. Do not upload a private Timeline file.

## Installation and comparison

The package ID is `dev.mahlernim.timelinevisualizer.cameralab`. It can remain installed beside production `dev.mahlernim.timelinevisualizer`. Installing Camera Lab 3 updates an existing Camera Lab installation and retains its separate settings and app data. Camera Lab 2 slowdown values are retained. If installing Camera Lab for the first time, select the Timeline file independently inside Camera Lab.

## Privacy and limitations

Timeline processing remains on the device. CARTO receives requests for displayed map-tile areas under the same privacy model described by the project privacy policy. Do not upload or attach a private Timeline file when reporting feedback.

This first episode detector is based on adaptive relocation distance. It may miss a transfer recorded as many smaller location steps, or classify an unusually long local jump as a transfer. The presets are intended to reveal how often those cases occur before adding more algorithmic complexity.

Camera Lab is not a production update and is not distributed through Google Play. Episode framing and its preset values may change or be removed after testing.

Camera Lab discussion and feedback remain in issue #129. Production city-scale framing remains tracked in #45.
