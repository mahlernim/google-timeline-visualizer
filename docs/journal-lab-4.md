# Journal Lab 4

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Lab 4 goal

Preserve supported Google semantic segment boundaries and provenance so the best-available route
can distinguish observed continuity from unsupported gaps.

## Included in Lab 4

- Supported activity, visit, and path records retain their source boundaries, declared coverage,
  explicit activity type, and place identifier.
- Detailed observations remain canonical. Semantic records fill only uncovered time ranges.
- Independent semantic records stay separated unless detailed observations provide concrete
  overlapping continuity. Preview, distance, trip coverage, and pending export honor those gaps.
- Large records can be stored in connected chunks without changing their route topology.
- Journal Lab 2 and 3 flattened semantic imports remain connected through parser-version-aware
  compatibility handling.
- Newer semantic snapshots replace older data only inside their declared coverage. Older snapshots
  can still fill uncovered history.
- Overlapping standalone path history is secondary to structured activity and visit coverage from
  the same snapshot.

## Validation

- Structured parser, importer, route fusion, route service, and dedicated Journal Lab UI tests pass.
- The complete GitHub production unit-test suite passes.
- Journal Lab lint passes.
- Production and Journal Lab debug APK assembly passes.
- Packaging checks pass with 3 tests.
- APK inspection confirms production identity `dev.mahlernim.timelinevisualizer` version `2.4.1`
  and Journal Lab identity `dev.mahlernim.timelinevisualizer.journallab` version
  `3.0.0-journal-lab.4`, labeled `Journal Lab`.

## Current limits

- No stable developer schema was identified in Google's public documentation for the current
  on-device Timeline export. Parsing is conservative and covers only fields proven by maintained
  fixtures.
- Confidence values, probabilities, distance hints, timezone hints, place names and addresses,
  semantic place types, and alternative activity candidates are not retained yet.
- Legacy `timelineObjects` and `locations` export formats remain unsupported.
- Pending exports preserve route gaps but do not yet preserve semantic labels or provenance.
- Reminders, import celebration, secondary-journal management, and undo remain future Lab work.
- The shared production `MainActivityTest` suite is not fully flavor-adapted for the Lab's
  intentional Journal-first copy and hidden raw-data choice. Dedicated Journal Lab UI tests cover
  the Lab behavior.

Journal Lab remains a separate experimental build. It does not update or replace the production app. Do not treat its database as the final production migration format until the implementation, upgrade path, and recovery behavior have been verified.
