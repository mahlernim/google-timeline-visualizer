# Timeline Visualizer Camera Lab 4

Camera Lab is an experimental Android build for comparing camera behavior before production integration. It installs separately from Timeline Visualizer and updates earlier Camera Lab builds without affecting the production app.

## What changed

Camera Lab 4 fixes delayed zoom-in after a long transfer. Camera Lab 3 waited until the flight endpoint before requesting the destination frame, then allowed ordinary zoom smoothing to carry momentum into local travel.

- Arrival zoom now starts during the final 25% of a detected long transfer.
- The transfer frame closes progressively around the destination rather than changing only after arrival.
- The arrival response follows this planned transition directly instead of applying the ordinary delayed zoom-in filter.
- Local episode framing is synchronized with the marker's duration-preserving travel progress.
- The camera cannot widen for the return transfer while the marker is still completing arrival or destination travel.

Camera Lab 3 controls remain unchanged. Local trip framing defaults to On, Trip detection defaults to Balanced, Local framing defaults to Balanced, and Travel slowdown during zoom-in defaults to 60%.

## What to test

Use a route with a long inbound flight, destination travel, and a return flight. Keep the same Timeline file, period, duration, zoom style, long-trip compression, and output format when comparing Camera Lab 3 and Camera Lab 4.

1. Watch the final quarter of the inbound flight.
2. Confirm that zoom-in begins before arrival rather than after local travel starts.
3. Confirm that the destination view is substantially settled immediately after arrival.
4. Confirm that the camera remains focused on destination travel and does not widen early for the return flight.
5. Report any arrival where zoom starts too early, feels abrupt, or still finishes late. Include an approximate video timestamp, but do not upload a private Timeline file.

## Installation and privacy

The package ID is `dev.mahlernim.timelinevisualizer.cameralab`. It remains installed beside production `dev.mahlernim.timelinevisualizer`. Installing Camera Lab 4 updates an existing Camera Lab installation and retains its separate settings and app data.

Timeline processing remains on the device. CARTO receives requests for displayed map-tile areas under the same privacy model described by the project privacy policy.

Camera Lab is not a production update and is not distributed through Google Play. Camera Lab discussion and feedback remain in issue #129.
