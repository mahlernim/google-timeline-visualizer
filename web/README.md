# Timeline Visualizer web preview

The public landing page is at <https://ahn-lab.org/google-timeline-visualizer/>.
The browser application is at <https://ahn-lab.org/google-timeline-visualizer/app/>.

The landing page uses a small prerecorded fictional journey. Android visitors see
compact Play testing and APK options first. PC, iPhone, iPad, and unknown devices
see the web app first. Device detection changes the order, never redirects anyone.

## Browser workflow

1. Import a current direct-array or semantic Timeline JSON, or try the fictional sample.
2. Choose months or exact dates. The latest available month is selected initially.
3. Accept the map notice and preview the journey.
4. Check the selected browser codec, then create, share, or download an MP4.

Simple mode uses square 480p, 15 fps, 15 seconds, and the steady camera. It also
offers portrait/landscape and 10- or 30-second duration. Advanced settings expose
camera movement, location filtering, raw signals, accuracy, custom duration
10–300 seconds, resolution 480–2160, and whole frame rates 15–120 fps.
Every new session starts in simple mode. Unsupported export configurations remain
explicit and never silently change the user's selection.

Import runs in a cancellable worker using 64 KiB input chunks. It scans available
dates, rereads the chosen range, and retains only selected points and neighboring
filter context. The selected input is limited to 100,000 points, with at most
100,000 additional context points. A single JSON record larger than 16 MiB is
rejected before it can grow without bound. Oversized imports ask for a shorter
range or use of Android instead of silently dropping locations.

Raw processing carries its filtering state from the beginning of the stream so
short date ranges preserve stabilization anchors. Unordered raw exports use
additional passes with 10,000-point sort batches instead of loading all locations
into memory. This can take longer, and remains cancellable.

The preview uses at most a 640-pixel longest edge and 15 fps, independent of export
resolution. Maps are loaded as each frame needs them, using two simultaneous
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

The build verifies the landing JavaScript's 30 KiB gzip budget, the demo's 750 KiB
budget, poster size, fast-start metadata, and the service worker's deferred assets.
Keep the existing project base path.

## Fictional demo provenance

`public/demo-journey.mp4` and `public/demo-poster.webp` are generated solely from
`public/sample-timeline.json`, never a personal Timeline. The source was exported
through the web UI at square 480p, 15 fps, 15 seconds, steady camera, kilometers,
and the title “Fictional journey”. The route and map attribution come from the
same renderer as user previews.

The landing clip compresses the complete source journey to eight seconds.

```sh
ffmpeg -i fictional-source.mp4 -vf "setpts=8/15*PTS,fps=15,scale=480:480" -t 8 -c:v libx264 -crf 29 -preset slow -pix_fmt yuv420p -movflags +faststart -an -metadata "title=Fictional journey" public/demo-journey.mp4
ffmpeg -ss 2 -i public/demo-journey.mp4 -frames:v 1 -c:v libwebp -quality 75 public/demo-poster.webp
```

The clip is H.264/yuv420p, 480×480, 120 frames, eight seconds, without audio.
