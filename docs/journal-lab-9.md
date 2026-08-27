# Journal Lab 9

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Lab 9 goal

Introduce the Travel Journal clearly before an empty Lab asks the user to import a Timeline export, while keeping existing Journals and direct file-opening flows out of the way of onboarding.

## Included in Lab 9

- A five-page introduction explains the private Travel Journal, Google Maps Timeline as the journey source, detailed and semantic layers, regular preservation, and the available next steps.
- An empty first-run Journal Lab opens the introduction before the Library. Existing Journals continue directly to the Library.
- The final page can open a supported Timeline JSON file or the Google Maps Timeline setup path.
- Not now completes the introduction and leaves a clear Journal setup card in the Library.
- How the Travel Journal works in Settings replays the introduction and returns to Settings without changing completion state.
- Opening a supported Timeline JSON file directly bypasses onboarding so the file can be handled immediately.
- The video creation entry leads to Journal setup when no usable Journal is available.
- Onboarding progress survives activity recreation, and its page heading and announcement behavior support accessibility services.
- Production navigation remains unchanged.
- Journal Lab 2 through 8 stored data remains compatible. The Room schema version is unchanged.

## Privacy and interpretation

The introduction states that Timeline Visualizer does not track location and reads only the file selected by the user. It explains that detailed route retention is based on observed exports, not a guaranteed Google retention period. The suggested update interval is presented as a cautious recommendation.

## Validation

- Journal onboarding UI, completion storage, setup navigation, Journal Lab UI, reminder, and repository tests have focused coverage.
- Journal Lab lint, APK assembly, identity, signature continuity, checksum, Lab 8 in-place upgrade, and production co-installation remain release gates.
- The release identity is package `dev.mahlernim.timelinevisualizer.journallab`, version `3.0.0-journal-lab.9`, and label `Journal Lab`.

## Current limits

- Another person's export is still blocked safely. Temporary guest and secondary Journal destinations remain future Lab work.
- Portable Journal backup, import undo, and production v3 migration remain future work.
- Journal Lab remains experimental and does not update or replace the production app.
