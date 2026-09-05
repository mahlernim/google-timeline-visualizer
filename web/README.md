# Timeline Visualizer web preview

The public landing page is at <https://ahn-lab.org/google-timeline-visualizer/>.
The browser application is at <https://ahn-lab.org/google-timeline-visualizer/app/>.

The landing page uses a small prerecorded real journey shared by the publisher. Android visitors see
compact Play testing and APK options first. PC, iPhone, iPad, and unknown devices
see the web app first. Device detection changes the order, never redirects anyone.

## Browser workflow

1. Import a current direct-array or semantic Timeline JSON.
2. Choose months or exact dates. The latest available month is selected initially.
3. Accept the map notice and preview the journey.
4. Check the selected browser codec, then create, share, or download an MP4.

Simple mode uses square 480p, 15 fps, 15 seconds, and the steady camera. It also
offers portrait/landscape and durations of 15, 20, 30, 45, or 60 seconds.
Advanced settings expose camera movement, location filtering, and raw signals.
Resolution choices are 480p, 720p, 1024p, and 1920p (the short edge), with frame
rates of 15, 30, or 60 fps. Raw accuracy stays at the existing 100-meter default
without a separate control. All numeric video settings use validated selections.
Every new session starts in simple mode. Unsupported export configurations remain
explicit and never silently change the user's selection.

Import runs in a cancellable worker using 64 KiB input chunks. It scans available
dates and builds an in-memory index of semantic record byte ranges. The first pass
still validates and decodes the complete file. The second pass seeks only blocks
that overlap the selected dates or necessary semantic coverage, and retains only
selected points and neighboring filter context. The index preserves full-file
ordering and timezone signals, uses no coordinate arrays, and is bounded to 2,048
blocks by coalescing adjacent ranges. Pathological disconnected arrays fall back
to a complete streamed pass. Choosing another file or leaving the session releases
the index. It is not a persistent preprocessed copy. The selected input is limited to 100,000 points, with at most
100,000 additional context points. A single JSON record larger than 16 MiB is
rejected before it can grow without bound. Oversized imports ask for a shorter
range or use of Android instead of silently dropping locations.

Raw processing carries its filtering state from the beginning of the stream so
short date ranges preserve stabilization anchors. Unordered raw exports use
additional passes with 10,000-point sort batches instead of loading all locations
into memory. This can take longer, and remains cancellable.

The preview uses at most a 640-pixel longest edge and 15 fps, independent of export
resolution. Preview and export share a fading trail based on 2.5 seconds of travel,
bounded to 80 through 2,000 km and never longer than the journey. Older routes
clear during travel; the complete route appears in the ending overview. Maps are loaded as each frame needs them, using two simultaneous
requests and a 32 MiB decoded-image cache. Tiles are drawn before eviction, including
frames that need more tiles than fit in the cache. Failed map loads report an error
rather than creating a video with missing tiles.

The encoder loads only when entering Export. Only the selected configuration is
probed, with serialized, cached probes. Full-size canvases exist only during export.
MP4 output reserves metadata space using the known frame count and is limited to
64 MiB, checked both by a conservative estimate and while writing actual output.
Cancel waits for resource cleanup before another job can start.

## Privacy and browser support

- Timeline files, coordinates, dates, titles, frames, and generated media stay local.
- No account, location permission, or broad file permission is needed.
- Cloudflare Web Analytics measures aggregate site traffic. The application does not
  add private Timeline contents or generated media to analytics events.
- CARTO receives map tile requests only after the user accepts the map notice.
- Static application assets may be cached after use. The service worker initially
  precaches only landing essentials, never the encoder, import worker, demo video,
  Timeline data, generated videos, or map tiles.
- No Timeline or generated-video persistence is added. Reloading clears the session.
- Preview requires a modern Canvas-capable browser. Export additionally requires
  WebCodecs with H.264 encoding. Keep the tab open during export. Preview pauses
  when the tab is hidden. Codec support is not a guarantee that a phone has enough
  resources for every advanced configuration.

## Development and deployment

```sh
cd web
pnpm install --frozen-lockfile
pnpm test
pnpm build
pnpm dev
```

Use `VITE_CARTO_BASEMAP_API_KEY` for the existing public CARTO project credential.
Local environment overrides belong in ignored `.env*.local` files.

The Pages workflow deploys from main, resolves the latest stable GitHub APK during
the build, and also refreshes after a stable release or successful Android release
workflow. No browser-side GitHub API request is needed. Local builds fall back to
the stable release page unless `VITE_STABLE_APK_URL` is supplied.
Service-worker registration includes the deployment run and attempt in its URL,
preventing a CDN-cached older worker from preloading obsolete application modules.

The build verifies the landing JavaScript's 30 KiB gzip budget, the demo's 750 KiB
budget, poster size, fast-start metadata, and the service worker's deferred assets.
Keep the existing project base path.

## Demo provenance

`public/demo-mahlerlab.mp4` contains the first ten seconds of the publisher's
`timeline video.mp4`, supplied and explicitly authorized for public use by mahlerlab.
`public/demo-mahlerlab.webp` is a frame from that excerpt. The source file is not
included. The new asset URLs prevent stale fictional media in browser or CDN caches.
The clip preserves the source pacing and existing map attribution.

```sh
ffmpeg -i "timeline video.mp4" -t 10 -vf "fps=15,scale=480:480" -c:v libx264 -crf 27 -preset slow -pix_fmt yuv420p -movflags +faststart -an -map_metadata -1 -metadata "title=Timeline Visualizer by mahlerlab" public/demo-mahlerlab.mp4
ffmpeg -ss 2 -i public/demo-mahlerlab.mp4 -frames:v 1 -c:v libwebp -quality 75 public/demo-mahlerlab.webp
```

The clip is H.264/yuv420p, 480×480, 150 frames, ten seconds, without audio.
It loops with inline play/pause controls. Reduced motion, data saving, and autoplay
restrictions require manual playback. Neither the video nor application modules
are precached on a cold landing visit. The fictional JSON remains a parser fixture
and is not offered as an import action.
