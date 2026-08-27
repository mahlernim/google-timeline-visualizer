# Journal Lab 5

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Lab 5 goal

Make large initial imports and later Journal growth visibly responsive while reducing repeated work.

## Included in Lab 5

- Large imports report distinct reading, identity checking, saving, and journey preparation phases.
- Settings shows active progress while a Journal is growing instead of only disabling its buttons.
- Journal identity checking uses bounded deterministic samples rather than counting every overlapping observation.
- Detailed observations are saved in database batches, reducing per-point transaction and query overhead.
- Route arbitration uses merged coverage intervals and ordered sweeps instead of repeatedly scanning every exclusion interval or detailed island for every point.
- Exact duplicate imports avoid an unnecessary full route rebuild.
- Journal Lab 2, 3, and 4 stored data remains compatible. Detailed observations remain canonical and unsupported transitions remain explicit gaps.

## Validation

- Journal import, route fusion, route service, and dedicated Journal Lab UI tests pass.
- Journal Lab lint and APK assembly pass.
- Packaging checks pass with the Lab 5 identity.
- APK inspection confirms Journal Lab identity `dev.mahlernim.timelinevisualizer.journallab` version `3.0.0-journal-lab.5`, labeled `Journal Lab`.

## Current limits

- Reading and parsing still examines the complete selected JSON file. This is required to validate and ingest changed content safely.
- Progress within Android's document provider read is based on bytes received and may pause while the provider supplies data.
- Route preparation still derives the requested route from stored observations. Lifetime-scale materialized route caching remains future work.
- No stable developer schema was identified in Google's public documentation for the current on-device Timeline export. Parsing remains conservative.
- Reminders, import celebration, secondary-journal management, and undo remain future Lab work.

Journal Lab remains a separate experimental build. It does not update or replace the production app. Do not treat its database as the final production migration format until the implementation, upgrade path, and recovery behavior have been verified.
