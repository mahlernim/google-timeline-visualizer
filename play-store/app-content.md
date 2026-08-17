# Play Console app content

Use these answers as the submission baseline and verify them against the final
Play bundle before submitting.

## Basic declarations

- App category: **Video Players & Editors**
- Contains ads: **No**
- App access: **All functionality is available without an account or login**
- Target audience: **18 and over**
- News app: **No**
- Government app: **No**
- Financial features: **No**
- Health features: **No**
- Account creation: **No**; an account-deletion flow is not applicable

## Data safety

The Timeline JSON, route list, user-entered name and title, Creations index,
thumbnails, and MP4 videos are processed only on the device. Do not declare those
items as collected solely because the app accesses them locally.

For a conservative declaration, include the following CARTO map-tile traffic:

| Data type | Handling | Purpose | Required? | Notes |
| --- | --- | --- | --- | --- |
| Precise location | Collected and shared | App functionality | Optional | Tile coordinates can identify areas smaller than 3 km². The user can cancel before loading. |
| Device or other identifiers | Collected and shared | App functionality, fraud prevention/security | Optional | Standard HTTPS requests expose an IP address and user agent to CARTO. |

- Data is encrypted in transit: **Yes**
- Users can request deletion from the developer: **No developer-operated server stores user data**
- Data is processed ephemerally: **Do not claim this unless CARTO confirms that the relevant request data is not retained beyond the real-time request**

The final answers must remain consistent with `docs/privacy.md`, the first-load
disclosure, and CARTO's current privacy practices.

## Content rating

Complete the IARC questionnaire accurately. The app contains no violence, sexual
content, gambling, controlled substances, user interaction, or unrestricted web
browsing. It opens only the privacy policy, project page, update destination, and
the phone's Location settings through explicit buttons.

## Reviewer instructions

Timeline Visualizer does not require login. To test it, select **Load Timeline**,
accept or cancel the map privacy disclosure, and choose a compatible Timeline JSON.
The repository includes `test-fixtures/seoul-bohol-sample.json` as a synthetic test
file containing no real user's location history.
