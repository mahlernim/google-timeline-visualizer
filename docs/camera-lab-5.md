# Timeline Visualizer Camera Lab 5

Camera Lab is an experimental Android build for comparing camera behavior before production integration. It installs separately from Timeline Visualizer and updates earlier Camera Lab builds without affecting the production app.

## What changed

Camera Lab 5 reduces the experiment to four camera controls while keeping the important parameters adjustable.

- Zoom style remains Fixed, Balanced, Active, or Close-up.
- Local trip framing is now one Off, Balanced, or Close control. It replaces the separate framing switch and the Wide option.
- Long-trip compression is now Off `1.00`, Balanced `0.85`, Strong `0.75`, or Stronger `0.65`.
- Trip detection remains Conservative, Balanced, or Sensitive.
- The travel slowdown mechanism has been removed. Marker progress is no longer redistributed during zoom-in.

Long-trip compression now groups consecutive detected transfer hops into one transfer episode before assigning flight time. Intermediate source points should therefore no longer make the same flight occupy more of the video. Local segments retain linear timing, while progress within a transfer remains proportional to distance. The 75 km visual route subdivision is unchanged because it affects drawing smoothness rather than video-time allocation.

The Camera Lab 4 arrival behavior is retained. Destination zoom begins during the final 25% of a detected inbound transfer, catches up without ordinary zoom momentum, and settles around the following local route.

Existing Camera Lab settings migrate as follows. Local trip framing Off remains Off, Wide becomes Balanced, and Close remains Close. Gentle long-trip compression becomes Balanced. Obsolete slowdown settings are discarded.

## What to test

Use the same Timeline file, period, duration, zoom style, and output format when comparing Camera Lab 4 and Camera Lab 5. A useful starting point based on prior testing is Active zoom, Balanced or Close local trip framing, Sensitive trip detection, and Strong compression.

1. Compare Strong `0.75` with Stronger `0.65` on a long outbound flight, destination travel, and return flight.
2. Confirm that flights with intermediate recorded points no longer feel disproportionately long.
3. Confirm that destination zoom still starts before arrival and is settled when local travel begins.
4. Confirm that removing marker slowdown does not create late zoom, marker drift, or uncomfortable simultaneous motion.
5. Compare Local trip framing Off, Balanced, and Close without changing the other settings.

Report the chosen controls and an approximate video timestamp for any uncomfortable transition. Do not upload a private Timeline file.

## Installation and privacy

The package ID is `dev.mahlernim.timelinevisualizer.cameralab`. It remains installed beside production `dev.mahlernim.timelinevisualizer`. Installing Camera Lab 5 updates an existing Camera Lab installation and retains its separate app data.

Timeline processing remains on the device. CARTO receives requests for displayed map-tile areas under the same privacy model described by the project privacy policy.

Camera Lab is not a production update and is not distributed through Google Play. Camera Lab discussion and feedback remain in issue #129.
