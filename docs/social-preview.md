# Social preview card

The public social card is `web/public/social-card.png`. Its map panel is based on a frame at 8.5 seconds from the already-public `web/public/demo-mahlerlab.mp4`, not a newly disclosed private Timeline. The final card was composed with the built-in image generation tool using that frame as a visual reference. It preserves the app map palette, route appearance and map attribution. It is a generated composition, not a pixel-exact screenshot.

The final prompt required replacing the invented city map with the actual public demo map, preserving its geography, colors, route, labels, title overlay and attribution. It specified the branding “The original Timeline Visualizer”, headline “Your places, in motion.”, the on-device privacy badge and the canonical project URL, with the map on the right.

Both public entry pages include static Open Graph and Twitter metadata so crawlers can discover the card without JavaScript. The initial code-authored vector draft is retained in `docs/images/social-card.svg` as design provenance and is not served.
