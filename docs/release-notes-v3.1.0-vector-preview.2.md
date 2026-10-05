# Vector map export preview

Test vector basemaps in Android MP4 exports and the web preview. Raster remains the default.

- Android users can enable **Settings > Vector map export (preview)**. Export dimensions are limited to 1920 pixels on either side. The interactive Android map still uses raster tiles.
- The web ZIP includes a README with local preview instructions. Enable **Use vector maps (preview)** at the Preview step. The public website remains on the stable version.
- Background reuse reduces repeated vector rendering. In synthetic tests, vectors requested fewer map tiles but exported more slowly than raster. Labels can look softer or appear in different places.
- CARTO branding and OpenStreetMap attribution appear with the maps. Location files and video encoding remain on device.

This is a GitHub prerelease for testing. It is not a Google Play update or a completed production migration. Compare short clips with the same settings, including city trips, long trips, and portrait or landscape videos. Please report missing map areas, misplaced routes, export failures, and the device used.
