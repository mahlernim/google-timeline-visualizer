# Journal Lab 8

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Lab 8 goal

Help people maintain their Travel Journal with a rewarding update result, clear freshness status, and optional quiet reminders before recent route detail may disappear from later exports.

## Included in Lab 8

- The Journal card shows detailed routes preserved through date, preserved detailed-location count, semantic history range, last successful import, and a freshness message.
- Freshness is based on the newest detailed observation that remains usable in the reconstructed route. Selecting a file does not make the Journal appear newer.
- Initial, advanced, historical backfill, semantic-only, and unchanged imports receive distinct result wording.
- A short progress animation celebrates Journal creation and meaningful growth. Reduced-motion settings are respected.
- A recent meaningful detailed import can offer optional Travel Journal reminders.
- Reminders use a separate low-importance channel and two inexact local checks around days 24 and 29.
- Duplicate, older, semantic-only, failed, cancelled, and mismatched imports do not reset reminder freshness.
- Reminder work revalidates the active Journal and usable route anchor before showing anything, so stale or repeated work is harmless.
- Notification copy does not include places, destinations, or another person's name.
- Update Journal opens the Journal card. Remind me in 3 days and Turn off are available directly from the reminder.
- Journal Lab 2 through 7 stored data remains compatible. The Room schema version is unchanged.

## Important interpretation

The reminder window reflects observed Timeline export behavior. It is not a claim that Google guarantees a fixed retention period. The Journal warns only about recent route detail that has not yet been preserved. Existing Journal history is not described as being at risk.

## Validation

- Freshness boundaries, reminder decisions, persistent reminder state, import invariants, and Journal Lab UI behavior have focused unit coverage.
- All supported locale resources preserve matching placeholders.
- Journal Lab lint, APK assembly, identity, signature continuity, checksum, Lab 7 in-place upgrade, and production co-installation remain release gates.

## Current limits

- Reminders are Android-inexact and may arrive later under battery restrictions.
- Another person's export is still blocked safely. Temporary guest and secondary Journal destinations remain future Lab work.
- Portable Journal backup, import undo, and production v3 migration remain future work.
