# Journal Lab 6

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Lab 6 goal

Make repeat imports safer and faster, including when a user selects an older Timeline export.

## Included in Lab 6

- Older same-Journal exports are treated as historical backfill and cannot become the preferred semantic snapshot merely because they were imported later.
- Exact duplicate imports remain no-ops.
- Repeated detailed observations avoid unnecessary processing and preserve the Journal high-water mark.
- Repeat imports rebuild only the changed route window with continuity padding instead of reconstructing the lifetime Journal.
- Large raw-signal normalization uses less temporary memory.
- Travel Journal setup and growth use one canonical ingestion surface with visible progress.
- Create routes users without a usable Journal to setup instead of maintaining a second file picker.
- Journal Lab 2 through 5 stored data remains compatible.

## Validation

- Journal import, route service, parser, and dedicated Journal Lab UI tests pass.
- Journal Lab lint and APK assembly pass.
- Packaging checks pass with the Lab 6 identity.
- APK inspection confirms package `dev.mahlernim.timelinevisualizer.journallab`, version `3.0.0-journal-lab.6`, and label `Journal Lab`.

## Current limits

- A changed JSON must still be read completely so the app can detect all updates and validate its complete source hash.
- This Lab does not delete or replace an existing Journal when identity evidence is insufficient.
- Journal Lab remains experimental and does not update or replace the production app.
