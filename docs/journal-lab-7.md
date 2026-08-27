# Journal Lab 7

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Lab 7 goal

Keep travel videos continuous when detailed observations or semantic route geometry are incomplete, without changing the canonical observations saved in the Travel Journal.

## Included in Lab 7

- Available route components are connected with explicit inferred transfers instead of splitting the video at every unsupported interval.
- Inferred transfers retain their source classification and reason. No fabricated intermediate observations are saved to the Journal database.
- Detailed observations remain canonical and semantic records remain the fallback.
- January 2026 regression coverage includes adjacent semantic records with mismatched endpoints and significant semantic-only gaps.
- The first import says that the Travel Journal is being created. Later imports keep the Journal growth wording.
- Suggested trips report the number of additional hidden suggestions.
- Per-video Advanced settings includes the active frame rate.
- The video title field describes the literal title shown in the video. Legacy title placeholders remain compatible without being promoted in the main creation flow.
- Journal Lab 2 through 6 stored data remains compatible.

## Performance and storage

- Transfer derivation is linear in the prepared route spans and stores only lightweight in-memory edges.
- Video interpolation remains bounded by the renderer's existing per-segment sample cap.
- Inferred transfers animate the chronology but do not increase the known-source distance shown in the video or used by route analytics.
- The local database keeps only parsed detailed observations and semantic records. Inferred transfer geometry is not accumulated as source data.

## Validation

- Journal route fusion, selection, patching, import, and Journal Lab UI tests pass.
- Journal Lab lint and APK assembly pass.
- Packaging checks pass with the Lab 7 identity.
- APK inspection confirms package `dev.mahlernim.timelinevisualizer.journallab`, version `3.0.0-journal-lab.7`, and label `Journal Lab`.

## Current limits

- Inferred transfers show chronology, not a verified road, rail, walking, or flight route.
- A changed JSON must still be read completely so the app can detect all updates and validate its complete source hash.
- Journal Lab remains experimental and does not update or replace the production app.
