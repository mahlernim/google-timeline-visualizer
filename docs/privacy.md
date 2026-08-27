# Privacy

[한국어](privacy.ko.md) · [日本語](privacy.ja.md)

**Effective date:** August 27, 2026

**Developer:** MahlerLab

**Contact:** [mahlerlabdiy@gmail.com](mailto:mahlerlabdiy@gmail.com)

Timeline Visualizer is designed to process sensitive location history locally.

## Data the app can access

Your Timeline file is never uploaded. The app reads only the Timeline file and imported MP4 documents that the user explicitly
chooses in Android's system document picker. It does not request device location,
Google account access, contacts, photos, advertising identifiers, or broad storage
permission.

## Data storage

The selected JSON file is not copied into app storage. After a successful import,
the app saves useful journey history from that file in an app-private Travel Journal
database. The Journal can accumulate detailed locations and semantic Timeline
history across later imports so older route detail remains available on the device.
The app also records import provenance needed to reject duplicate or mismatched
files. This database is more sensitive than one Timeline export because it can
consolidate movement history over time. It is excluded from Android cloud backup
and device transfer.

While a video is being created, the selected route points and export settings are
temporarily stored in private app storage so creation can continue when the app is
no longer on screen and can restart if Android recreates the app process. This
temporary export data is deleted after completion, cancellation, or failure and is
excluded from Android backup and device transfer. Generated videos are written
through Android's media storage interfaces. On Android 10 and later, completed MP4
files are saved through MediaStore under
`Movies/Timeline Visualizer`. Android 8 and 9 use the system Save As picker.
Cached basemap image tiles remain in the app's temporary cache for no more than
30 days. The app deletes older tiles automatically. They can also be removed by
clearing the app cache or uninstalling the app.

After a successful Timeline import, the app also stores the selected document URI
so it can request access to the same document on the next launch. Replacing the
selection replaces this URI.

On Android 13 and newer, the app may request notification permission so it can
show video progress and a completion alert. Declining this permission does not
stop video creation and does not grant access to any personal data.

Travel Journal reminders are optional. If enabled, an inexact local background
check reads only the Journal's freshness metadata and may show one or two quiet
notifications when a newer Timeline export could preserve recent route detail.
The check does not reopen the source document, use device location, or contact a
developer server. Reminder preferences are stored locally and excluded from
Android backup and device transfer.

For the Videos library, the app stores a local index containing the selected
video URI, title, filename, duration, creation date, and Timeline period when
available. A small thumbnail is stored in private app storage. The app requests
persistent access only to MP4 files that the user creates or explicitly adds.
Newly generated videos use a small final-overview thumbnail in private storage.
A 1080 × 1080 overview PNG may remain temporarily in app cache for the completion
screen's save and share actions. No visible PNG is created unless the user chooses
where to save it.

Android backup and device-transfer rules exclude the Videos index and thumbnails
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

Video presets are stored only in private app storage. They are never shared off the
device and are excluded from Android backup and device transfer.

All network requests made by the app use encrypted HTTPS connections. Under
CARTO's current Basemap Terms, CARTO processes request information on the
developer's behalf, truncates IP addresses when requests arrive, and stores the
resulting request logs in the United States for 30 days. CARTO may also process
this information under its privacy notice and data-processing agreement.

## Deleting data

Use Android's **Settings → Apps → Timeline Visualizer → Storage & cache → Clear
cache** to remove cached map tiles and other temporary files. Clearing the cache
does not delete the Travel Journal. Use **Clear storage** or uninstall the app to
remove the Travel Journal, reminder metadata, Videos index, thumbnails, and all
other private application data. The Travel Journal is not recoverable unless the
source Timeline history is still available for import. Removing
an entry from Videos does not delete the MP4. Use the separately confirmed
**Delete video** action, or delete the file from its saved location, to remove the
actual video.

## Third-party map sources

Map tiles are provided by CARTO and use OpenStreetMap data. Their terms and
privacy practices apply to tile requests:

- [CARTO privacy notice](https://carto.com/privacy/)
- [CARTO Basemap Terms](https://carto.com/legal/basemap-terms/)
- [OpenStreetMap privacy policy](https://osmfoundation.org/wiki/Privacy_Policy)
