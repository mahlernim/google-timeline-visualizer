# Privacy

[한국어](privacy.ko.md) · [日本語](privacy.ja.md)

**Effective date:** August 18, 2026

**Developer:** MahlerLab

**Contact:** [mahlerlabdiy@gmail.com](mailto:mahlerlabdiy@gmail.com)

Timeline Visualizer is designed to process sensitive location history locally.

## Data the app can access

The app reads only Timeline JSON and MP4 documents that the user explicitly
chooses in Android's system document picker. It does not request device location,
Google account access, contacts, photos, advertising identifiers, or broad storage
permission.

## Data storage

The selected JSON is not copied into app storage. While a video is being created,
the selected route points and export settings are temporarily stored in private
app storage so creation can continue when the app is no longer on screen and can
restart if Android recreates the app process. This temporary export data is
deleted after completion, cancellation, or failure and is excluded from Android
backup and device transfer. Generated videos are written to the destination the
user chooses. Cached basemap image tiles may remain in the app's temporary cache
and can be removed by clearing the app cache or uninstalling the app.

After a successful Timeline import, the app stores only the selected document URI
so it can request access to the same document on the next launch. Replacing the
selection replaces this URI. The Timeline contents are not duplicated.

On Android 13 and newer, the app may request notification permission so it can
show video progress and a completion alert. Declining this permission does not
stop video creation and does not grant access to any personal data.

For the Creations library, the app stores a local index containing the selected
video URI, title, filename, duration, creation date, and Timeline period when
available. A small thumbnail is stored in private app storage. The app requests
persistent access only to MP4 files that the user creates or explicitly adds.
Newly generated videos use a small final-overview thumbnail in private storage.
A 1080 × 1080 overview PNG may remain temporarily in app cache for the completion
screen's save and share actions. No visible PNG is created unless the user chooses
where to save it.

Android backup and device-transfer rules exclude the Creations index and thumbnails
so video references and preview images are not copied to another device.

## Network use

The app requests raster map tiles from CARTO. Those requests contain standard
zoom/x/y tile identifiers and normal network metadata such as an IP address and
user agent. Tile identifiers correspond to geographic areas in the selected
Timeline and may reveal those areas to CARTO. Before the first Timeline is loaded,
the app explains this transfer and lets the user cancel. The app does not send the
Timeline JSON, a list of route coordinates, video frames, titles, or generated
videos to CARTO or to the developer.

The application has no analytics, advertising, crash-reporting, login, or
developer-operated server.

All network requests made by the app use encrypted HTTPS connections. CARTO may
process network and tile-request information under its own privacy notice.

## Deleting data

Use Android's **Settings → Apps → Timeline Visualizer → Storage & cache → Clear
cache** to remove cached map tiles. Clear storage or uninstall the app to remove
the Creations index and thumbnails along with all other application data. Removing
an entry from Creations does not delete the MP4. Use the separately confirmed
**Delete video** action, or delete the file from its saved location, to remove the
actual video.

## Third-party map sources

Map tiles are provided by CARTO and use OpenStreetMap data. Their terms and
privacy practices apply to tile requests:

- [CARTO privacy notice](https://carto.com/privacy/)
- [OpenStreetMap privacy policy](https://osmfoundation.org/wiki/Privacy_Policy)
