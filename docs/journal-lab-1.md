# Journal Lab 1

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Included in Lab 1

- Travel Journal wording on the Timeline management and creation screens
- Automatic best-available route wording
- No Raw Data Video choice in the Lab creation screen
- A versioned Room database schema for Journals, imports, detailed observations, provenance, and semantic snapshots
- Transactional and idempotent repository behavior for overlapping imports
- A safety gate that prevents uncertain or likely-different imports from mutating a selected Journal without an explicit destination decision
- A detailed-first route-span contract with semantic fallback and explicit detailed-data gaps
- Local backup and device-transfer exclusions for the Journal database
- A dedicated prerelease workflow with package, version, signature, checksum, and co-installation checks

## Important limit

Lab 1 does not yet connect the current Timeline file picker to the durable Journal repository. Preview, video export, trip detection, and distance summaries also still use the existing v2 route pipeline. Do not rely on this build yet to preserve recent detailed routes permanently.

The next Lab milestone will stream parsed Timeline exports into staging batches, activate matching imports, and feed the fused route spans to preview and export.
