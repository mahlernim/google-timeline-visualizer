# Journal Lab 10

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Lab 10 goal

Make the Travel Journal clearer, faster to reopen, and more rewarding to maintain with updated Timeline files.

## Included in Lab 10

- App startup restores Journal metadata without rebuilding the complete route. Full route preparation now waits until Create needs it.
- Settings and first-import confirmation show separate Detailed routes and Journey history ranges and counts.
- The first action is Import Timeline. After a Journal exists, the action becomes Import updated Timeline and the help link becomes Get updated Timeline file.
- Reminders start enabled on platforms where notification permission is already available. Android versions that require permission still ask before enabling them.
- The five onboarding pages use the supplied polished vector illustrations, recolored for the app identity with light and dark theme palettes.
- Video defaults can be saved directly as a preset beside Manage presets.
- Journal Lab 2 through 9 stored data remains compatible. The Room schema version is unchanged.

## Performance behavior

Library startup reads compact Journal metadata only. Entering Create prepares the fused detailed and semantic route and displays progress while that work is active. Importing an updated Timeline still parses the selected file and commits new records safely, but reopening the app no longer repeats that full route reconstruction.

## Privacy and interpretation

Detailed route points remain the preferred source and semantic journey history remains the fallback. The app reports what exists in the selected export and does not claim a guaranteed Google retention period.

## Validation

- Journal startup, import, status, reminder, onboarding, preset, repository, and route behavior have focused automated coverage.
- Journal Lab lint, APK assembly, identity, checksum, Lab 9 in-place upgrade, and production co-installation remain release gates.
- The release identity is package `dev.mahlernim.timelinevisualizer.journallab`, version `3.0.0-journal-lab.10`, and label `Journal Lab`.

## Current limits

- Another person's export is still blocked safely. Temporary guest and secondary Journal destinations remain future Lab work.
- Portable Journal backup, import undo, and production v3 migration remain future work.
- Journal Lab remains experimental and does not update or replace the production app.
