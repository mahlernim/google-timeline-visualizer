# Google Play submission checklist

## Developer account

- Identity verification approved
- Contact phone and developer email verified
- Real Android device verification completed
- Main Gmail invited as an administrator after account approval

## App setup

- App name: **Timeline Visualizer**
- Default language: **English (United States)**
- App or game: **App**
- Free or paid: **Free**
- Package name: `dev.mahlernim.timelinevisualizer`
- Support email: `mahlerlabdiy@gmail.com`
- Privacy policy: `https://github.com/mahlernim/google-timeline-visualizer/blob/main/docs/privacy.md`

## Release

- Version name `2.3.4` and version code `37`
- Upload the signed `playRelease` Android App Bundle
- On first enrollment, preserve the existing app-signing key so GitHub and Play installs remain update-compatible
- Register a separate upload key for later Play releases
- Confirm version code is higher than every previously uploaded bundle
- Complete an internal test before starting the required closed test
- For a new personal account, keep at least 12 testers opted in continuously for 14 days before applying for production access

## Store presence and policy

- Upload the 512×512 app icon and 1024×500 feature graphic
- Paste and proofread the listing text for English, Korean, Japanese, Simplified
  Chinese, Traditional Chinese, Spanish, French, German, and Brazilian Portuguese
- Upload the four current screenshots and localized feature graphic for English,
  Korean, and Japanese. Use the English graphics as the fallback for the other
  six locales
- Complete Data safety, content rating, target audience, ads, and app-access forms
- Confirm the public privacy policy and support address work without signing in
- Confirm the Play build opens Google Play, not GitHub Releases, for updates
- Complete the foreground-service declarations for **Media processing** and the
  older-Android **Data sync** compatibility path, explaining user-initiated
  on-device MP4 encoding and the ongoing Cancel notification
- Declare notification permission use for background progress and completion alerts
