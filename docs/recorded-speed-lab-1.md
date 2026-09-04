# Recorded Speed LAB 1

Experimental Android prerelease based on Timeline Visualizer v3.0.14.

This build always uses recorded movement timing. There is no pacing selector or visual-speed fallback. Changing the map view, camera, aspect ratio or preset cannot change movement timing when the selected video duration stays the same.

## Install and try

Download and install `RecordedSpeedLab-1.apk`. The orange stopwatch icon, LAB mark and **Recorded Speed LAB** launcher name distinguish this app from Timeline Visualizer and the earlier local Speed Lab.

- Package `dev.mahlernim.timelinevisualizer.recordedspeedlab`
- Version `3.0.14-recorded-speed-lab.1`, version code `1`
- Installs alongside the regular app and the earlier local test without replacing their data
- Import your Timeline file into the separate app, select a range, and preview or export normally
- Experimental notices appear in Settings and the video creation screen

![Regular and experimental app identities](https://raw.githubusercontent.com/mahlernim/google-timeline-visualizer/recorded-speed-lab-1/docs/images/recorded-speed-lab-identity.png)

## Timing behavior

Recorded activity duration determines time allocation. When activity context is unavailable, timestamped route-point intervals are used if they are no more than 30 minutes apart. The selected video length still controls overall compression, with the existing 1.5-second outro included in that length.

Stationary periods consume no playback time. Unsupported intervals, inferred transfers, nonpositive time differences and implausible speed estimates are skipped with a cut. They never receive time based on distance, zoom or camera framing. A range with no usable recorded movement cannot be previewed or exported and shows an explanation.

Smooth monotone interpolation joins supported movement intervals. It preserves source arrival times and does not smooth across skipped distance gaps. Camera preparation and lightweight preview use the same recorded timing. Camera-dependent arrival minimum-time allocations are bypassed.

## Experimental limitations

Recorded speed is an interval average, not instantaneous ground truth. Sparse geometry can underestimate distance. Activity records can include brief stops. The prototype treats estimates below 0.5 km/h as stationary and estimates over 1300 km/h as unsupported. Flights with usable activity records remain supported even when point observations are sparse.

Skipped intervals may cause visible jumps. Existing route geometry and distance labels are retained, so total route distance can include intervals skipped by playback. This build tests timing behavior and does not claim to repair missing location data.

All Timeline processing remains local. Map tile requests follow the existing app behavior. This prerelease is distributed through GitHub only and does not replace the stable release or publish an update to Google Play.

## Validation

The release workflow gates publication on focused timing, camera independence, export persistence and animation tests, experimental-variant lint, and Android device checks. The device checks exercise the experimental identity, no-movement guard and a real short MP4 encode using offline test tiles. Signed APK identity and signature are checked before publishing. A signed Android App Bundle is retained as a workflow artifact, with no Play Console submission.
