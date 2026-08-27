# Timeline Visualizer web app privacy

**Effective date:** August 26, 2026

**Developer:** MahlerLab

**Contact:** [mahlerlabdiy@gmail.com](mailto:mahlerlabdiy@gmail.com)

Timeline Visualizer processes sensitive location history locally in the browser.

## Timeline data

The web app reads only the `Timeline.json` file that you explicitly select. The
file, route coordinates, selected dates, title, video frames, and generated MP4
are not uploaded to the developer or to an application server. Video processing
and MP4 creation happen in the browser tab.

The web app does not request a Google account, device location, contacts, photos,
advertising identifier, or broad file permission.

## Hosting and aggregate traffic analytics

The public site is hosted with GitHub Pages and served through Cloudflare. Normal
web requests expose standard network information such as the IP address, user
agent, requested page, and request timing to those hosting providers.

The hosted site uses Cloudflare Web Analytics to measure aggregate site traffic.
Timeline file contents, route coordinates, selected dates, titles, video frames,
and generated media are not added to analytics events by the application.

## Map requests

After you accept the map privacy notice, the web app requests raster map tiles
from CARTO. Those requests contain zoom, x, and y tile identifiers plus normal
network information such as the IP address and user agent. Tile identifiers
correspond to geographic areas in the selected journey and may reveal those
areas to CARTO.

The web app does not send the Timeline JSON, a complete route list, titles, video
frames, or generated videos to CARTO. You can load and inspect a Timeline file
without accepting the notice or requesting map tiles.

Under CARTO's current Basemap Terms, CARTO processes request information on the
developer's behalf, truncates IP addresses when requests arrive, and stores the
resulting request logs in the United States for 30 days. CARTO may also process
this information under its privacy notice and data-processing agreement.

## Browser storage

Selected Timeline data and generated videos remain in the current browser page.
The service worker may cache static application files so the interface can load
reliably. It does not cache the selected Timeline JSON or generated MP4. Closing
or reloading the page clears the active Timeline data. Browser site settings can
be used to remove cached application files.

Map tiles are held only in the current page's memory while preparing or rendering
a journey. The application service worker does not place CARTO tiles in persistent
browser storage.

## Third parties

- [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)
- [Cloudflare Privacy Policy](https://www.cloudflare.com/privacypolicy/)
- [Cloudflare Web Analytics](https://www.cloudflare.com/web-analytics/)
- [CARTO Privacy Notice](https://carto.com/privacy/)
- [CARTO Basemap Terms](https://carto.com/legal/basemap-terms/)
- [OpenStreetMap Privacy Policy](https://osmfoundation.org/wiki/Privacy_Policy)
