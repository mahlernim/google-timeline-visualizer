# Timeline Visualizer for iPhone and the web

This is the browser version of Timeline Visualizer. It loads a Google Maps
`Timeline.json` export, renders the selected journey, and creates an H.264 MP4
entirely in the browser.

## Privacy

- The Timeline JSON is read locally and is not uploaded.
- No account, location permission, or broad file permission is used.
- The public site uses Cloudflare Web Analytics for aggregate site traffic. The
  application does not add Timeline contents, coordinates, selected dates,
  titles, or generated media to analytics events.
- CARTO receives requests for the map tiles needed to render the selected route.
- The browser tab must remain open while video creation is running.

## Browser support

Video creation requires the WebCodecs API and H.264 encoding. The primary target
is Safari 16.4 or newer on iPhone. The app detects browsers without WebCodecs and
disables video creation while leaving Timeline loading available.

## Local development

```bash
cd web
pnpm install --frozen-lockfile
pnpm test
pnpm build
pnpm dev
```

The Vite base path targets the default GitHub Pages project URL at
`/google-timeline-visualizer/`.

The production deployment is available at
<https://ahn-lab.org/google-timeline-visualizer/>. The Pages workflow builds and
deploys this directory from `main`. Keep the project base path unchanged unless
a dedicated custom subdomain is configured at the same time.

Set `VITE_PREVIEW=true` when building a public test deployment. Preview builds
show a visible warning.

## Current web app scope

The current implementation supports the complete private browser path.

1. Load current direct-array or older `semanticSegments` Timeline JSON, with an
   optional warned fallback for raw-only location exports.
   A built-in fictional journey is available for privacy-safe device testing.
2. Read absolute path timestamps or current minute offsets from segment start.
   When timezone data is absent, preserve the exported route order and recorded
   calendar dates so date-line travel is not reordered by the browser timezone.
3. Choose a month range or exact dates, title, and duration.
4. Choose Fixed zoom, Steady following, or Dynamic following camera movement.
5. Require explicit acknowledgement before contacting CARTO for map tiles.
6. Preview the journey on a 480 by 480 Canvas.
7. Add the Android-style 1.5-second full-route ending, encode Canvas frames as
   H.264, and mux them into an MP4.
8. Keep the screen awake when supported and allow video creation to be cancelled.
9. Preview, share, or download the completed MP4.

The browser layout, animated preview, encoded sample output, and camera following
were validated at an iPhone-sized viewport and on a physical iPhone. Longer
exports still benefit from representative-device memory, thermal, interruption,
and foreground execution testing.
