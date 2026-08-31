# Platform parity

Android is the canonical Timeline Visualizer product. The Python CLI and web app
follow Android where the behavior is portable. Browser encoder limits and operating
system integrations remain explicit instead of being hidden behind silent fallbacks.

| Capability | Android | Python CLI | Web app |
| --- | --- | --- | --- |
| Direct-array and semantic Timeline parsing | Yes | Yes | Yes |
| Raw location fallback and conservative filtering | Yes | Yes | Yes |
| Exact dates and cross-year periods | Yes | Yes | Yes |
| Fixed, Balanced, Active, and Close-up cameras | Yes | Yes | Yes |
| Android-aligned visual pacing | Canonical | Default | Camera-aligned |
| Kilometers and miles | Yes | Yes | Yes, including Automatic |
| Total duration from 10 through 300 seconds | Yes | Yes | Yes |
| Square, portrait, and landscape video | Yes | Yes | Yes |
| Short-edge resolution from 480 through 2160 | Device-dependent | FFmpeg-dependent | WebCodecs-dependent |
| Frame rate | Device-dependent, 15 through 240 fps with up to three decimals | FFmpeg-dependent, whole 15 through 120 fps | WebCodecs-dependent, whole 15 through 120 fps |
| MP4 title metadata | Yes | Yes | Yes |
| Capability check before export | Android codec probe | Argument and FFmpeg validation | Exact WebCodecs probe |
| Timeline data stays local | Yes | Yes | Yes |
| Travel Journal and incremental imports | Yes | No | No |
| Trip discovery, saved projects, and recaps | Yes | No | No |
| Preservation reminders | Yes | No | No |
| Background export and notifications | Yes | No | No |
| On-device video library | Yes | No | No |

The Android-only rows depend on persistent private application storage or Android
services. They are not parity defects for the CLI or web app. A future port should
be treated as a separate product design and privacy review.

When Android changes a portable parser, filter, camera, timing, overlay, export, or
privacy behavior, update the corresponding Python and TypeScript tests and this table
in the same change or record why the platform cannot support it.
