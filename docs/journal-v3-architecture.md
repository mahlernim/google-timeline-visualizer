# Travel Journal v3 architecture and delivery plan

- Status: proposed implementation contract
- Baseline: `origin/main` at `c5b18af`, public release `v2.4.1`
- Target: separately validated Journal Lab, followed by a production `v3.0.0` candidate

## 1. Outcome

Timeline Visualizer becomes a private Travel Journal that grows when the user imports a recent Google Maps Timeline export.

The Journal keeps useful detailed location observations from each import. Every trip, recap, preview, and video automatically uses those observations where they provide usable coverage. Google semantic segments fill periods where detailed observations were not preserved. The user never chooses a raw, semantic, or hybrid data source.

The update loop should communicate three benefits:

1. Recent route detail was preserved before it may become unavailable from future exports.
2. The Journal gained days and preserved locations.
3. New trips or recap opportunities may now be ready.

## 2. Product decisions

These decisions are settled for the v3 plan.

- Detailed observations are the canonical source for route geometry after conservative processing.
- Semantic segments are the fallback geometry and the source of visits, activities, places, and trip context.
- Source precedence is automatic and is not a user preference.
- The current Raw Data Video choice and raw-data toggle are removed from creation.
- Trip, monthly recap, yearly recap, and custom recap use the same fused Journal route.
- Repeated imports add detail. Missing records in a later file never erase accumulated observations.
- Importing the same file twice is idempotent.
- A stale file does not reset Journal freshness.
- A large mismatch never deletes or silently changes the active Journal.
- Another person's export can be opened once or stored as a separate Journal.
- Journal update reminders are optional, infrequent, and separate from video-export notifications.
- Trip suggestions are conservative, deduplicated, and in-app by default.
- The app remains local-first. Timeline contents are not uploaded to the developer.

## 3. Terminology

Use user-facing language that describes the experience rather than Google's internal schema.

| User-facing term | Meaning |
| --- | --- |
| Travel Journal | A locally maintained personal travel record |
| Journal history | The full date range available from semantic or detailed data |
| Detailed routes | Route geometry preserved from detailed location observations |
| Timeline export | The user-selected Google Maps export file |
| Update Journal | Import a newer Timeline export into a Journal |
| Timeline-only period | A period where semantic data fills missing route detail |
| Recording gap | A period where neither source can support a route |

Keep `rawSignals`, `semanticSegments`, and source classifications in diagnostics and engineering documentation. Do not use them as normal creation choices.

Use **locations** in user-facing counters and **observations** in technical schemas and diagnostics.

Suggested product line:

> Your Journal preserves the whole journey and uses the most detailed route available for each part.

Localization should translate the concept naturally. For example, Korean can use `여행 기록` and Japanese can use `旅の記録` rather than forcing a literal loanword.

## 4. Scope

### 4.1 Production v3 scope

- Durable Android Journal storage
- One primary Journal, zero or more named secondary Journals, and temporary guest import
- Creation and switching of secondary Journals when a user wants to keep another person's history
- Transactional import, matching, deduplication, and undo
- Automatic detailed-first route fusion
- Unified creation screen without Raw Data Video
- Journal status and update experience
- Gentle in-app and Android system reminders
- Post-import trip detection and bundled suggestions
- Migration of v2 projects and video metadata
- Manual Journal backup and restore
- Updated privacy, deletion, and recovery documentation
- Web single-file automatic fusion without durable accumulation or reminders
- Separately installable Journal Lab validation before production

### 4.2 Deferred scope

- Cloud synchronization operated by the developer
- Google account identity or Timeline API access
- Collaborative or shared cloud Journals
- Automatic background reading of a changed export file
- Automatic video creation or publication
- Road snapping or claims that a detailed route is ground truth
- City Visited Recap from issue #149, except for reusable Journal interfaces
- Full durable browser Journal until browser storage and portable recovery are validated

## 5. Current architecture and required boundary

The current Android app has useful components but no durable Journal boundary.

- `TimelineParser` streams current direct-array, `semanticSegments`, and supported `rawSignals.position` records.
- `TimelineParser` currently flattens semantic coordinates and discards segment identity, visit metadata, and activity metadata. Route fusion requires a structured parser result.
- `RawSignalProcessor` applies an accuracy limit, impossible-spike removal, stationary uncertainty collapse, and gap counting.
- `TimelineCache` keeps one disposable, fingerprinted binary cache in `cacheDir` and replaces it on import.
- `TimelineSourceStore` remembers one document URI and basic semantic and raw ranges.
- `TripsStore` stores projects and dismissed suggestions in shared preferences.
- `TripKind.RAW_DATA` makes data source a project type.
- `TripDetector` receives one flattened `Timeline` point stream.
- Android backup rules exclude current Timeline import state, but do not yet define a permanent Journal archive.

The v3 archive must be a new repository. It must not change `TimelineCache` from disposable cache into durable user data.

## 6. Journal data model

Use a versioned SQLite database. Room is preferred unless a short spike demonstrates a material size or streaming disadvantage.

### 6.1 Core entities

#### Journal

| Field | Purpose |
| --- | --- |
| `id` | Stable UUID used by every Journal-owned record |
| `name` | User-editable local name |
| `isPrimary` | Identifies the default Journal |
| `createdAt` | Local creation timestamp |
| `lastAdvancedAt` | Last import that advanced captured detailed data |
| `detailedCapturedThrough` | Newest valid supported detailed observation preserved |
| `detailedUsableThrough` | Newest observation accepted by the current route filter |
| `reminderEligible` | Whether recent captured detail has armed loss-risk reminders |
| `semanticStart` / `semanticEnd` | Bounds of the coverage-aware semantic projection |
| `reminderEnabled` | Journal reminder opt-in |
| `reminderState` | Throttling, snooze, and last alert state |

#### ImportBatch

| Field | Purpose |
| --- | --- |
| `id` | Stable UUID for audit and undo |
| `journalId` | Target Journal |
| `sourceHash` | Streaming SHA-256 for exact reimport detection |
| `sourceName` | Display-only filename |
| `sourceSize` | Diagnostic metadata |
| `importedAt` | Import completion time |
| `parserVersion` | Parser contract used for the import |
| `matchClassification` | `SAME`, `UNCERTAIN`, `DIFFERENT`, or user override |
| `detailedStart` / `detailedEnd` | Parsed supported position bounds |
| `semanticStart` / `semanticEnd` | Parsed semantic bounds |
| counts | Parsed, inserted, duplicate, rejected, and conflict totals |
| `status` | Staging, committed, undone, or failed |

#### DetailedObservation

Store only useful supported position fields, not Wi-Fi scans or unrelated sensor records.

| Field | Purpose |
| --- | --- |
| `id` | Internal primary key |
| `journalId` | Journal owner |
| `instant` | UTC timestamp with original precision |
| `latitude` / `longitude` | Normalized coordinate |
| `observationKey` | Stable exact-deduplication key |

Use a unique index on `journalId`, timestamp, latitude bits, and longitude bits. Preserve competing observations at the same timestamp. Do not silently choose a more visually convenient coordinate.

#### ObservationImport

Join `ImportBatch` to `DetailedObservation`. Store source accuracy and optional validated altitude, speed, or provider fields on this provenance row. The active projection uses the best accuracy from committed, non-undone imports. This allows undo to restore the prior accuracy instead of permanently overwriting it.

Undo removes the batch links and deletes only observations that have no remaining import provenance.

#### SemanticSnapshot and SemanticSegment

Semantic data uses coverage-aware snapshot precedence rather than whole-file replacement.

- Store each successfully parsed snapshot under its import batch.
- For each interval, use the newest committed snapshot that supplies usable semantic data.
- Fall back through older committed snapshots for intervals omitted by newer, partial, or platform-specific exports.
- Do not discard a snapshot while it supplies unique Journal coverage or is needed to undo the latest update.
- Garbage-collect a snapshot only after proving that committed newer data fully supersedes it and the backup and undo policy permits removal.
- Preserve segment start and end, activity or visit type, endpoints, timeline path, place identifiers used locally, and parser provenance.
- Do not persist unrelated `userLocationProfile` contents by default.

#### DerivedCoverageIsland

Coverage islands are rebuildable output, not source truth.

| Field | Purpose |
| --- | --- |
| `journalId` | Journal owner |
| `filterVersion` | Processing algorithm contract |
| `start` / `end` | Accepted detailed coverage interval |
| `pointCount` | Accepted observations in the island |
| `qualitySummary` | Accuracy and discontinuity summary |

Changing the filtering algorithm invalidates and rebuilds these rows without losing source observations.

#### TripProject and JournalActivity

- Add `journalId` to saved trips and recaps.
- Remove data source from new project identity.
- Retain legacy source metadata only for decoding and historical display.
- Store trip suggestions, dismissals, recap suggestions, update-due items, and import results as deduplicated Journal activity records.

## 7. Import pipeline

Parsing may write to isolated staging rows or app-private staging files so large imports remain bounded in memory. Active Journal queries ignore staging data. A short final database transaction makes a confirmed batch visible and updates all active references atomically.

1. Open the user-selected document with a transient read grant.
2. Create an invisible `STAGING` batch.
3. Stream the file once while calculating its hash, validating structure, and writing normalized source rows with bounded transactions.
4. Retain semantic segment identity, type, metadata, and ordered geometry instead of reducing it to one point list.
5. Calculate semantic and detailed bounds and final staging counts.
6. If the source hash already belongs to the target Journal, remove the staging batch and report an up-to-date no-op.
7. Skip identity matching only when the target Journal is empty or the user has explicitly created a new Journal for this import.
8. Otherwise compare the staged candidate with the target Journal and classify the match.
9. If the match is uncertain or different, stop before activation and show the safe destination screen.
10. In one short transaction, mark source rows committed, update semantic precedence and Journal metadata, store result counters, add invalidation markers, and activate the batch.
11. Release document access when parsing finishes. Retain a persistent source URI only for a separately disclosed feature that requires it.
12. Rebuild coverage islands, route indexes, and suggestions after activation with resumable versioned jobs.
13. Show the growth result after required derived coverage finishes. Recover it from stored batch counters and an acknowledged marker after process death.

Crashes before activation leave the active Journal unchanged. Crashes after activation reopen the committed import result rather than importing again. Startup recovery removes or resumes stale staging batches without changing the active Journal. A derived-cache or suggestion failure cannot roll back valid committed source data.

Staging rows are app-private, excluded from backup, and unavailable to route queries. Cancel removes them immediately. An unresolved destination decision expires after 24 hours and is removed on the next startup or maintenance run.

### 7.1 Missing and stale data rules

- A file without supported detailed observations can refresh semantic history but cannot advance detailed freshness.
- A file without semantic segments can add detailed observations but does not erase existing semantic coverage.
- A later file missing old observations never deletes archived observations.
- A file whose newest valid supported detailed timestamp does not advance the Journal cannot reset reminder freshness.
- Preserve valid observations even when the current route filter rejects them. Report valid observations preserved separately from observations currently usable for route geometry.
- An older file may add previously missing detail and should receive accurate backfill messaging.
- Undo is limited to the latest committed permanent update. It removes only data introduced exclusively by that batch, restores semantic precedence, invalidates derived data, and recalculates Journal status.

## 8. Journal matching and guest safety

The app has no reliable account identifier. Matching is evidence-based and must fail safely.

### 8.1 Signals

Use the following signals together:

- Exact file hash and known import provenance
- Agreement of detailed observations in overlapping time windows
- Strong simultaneous location conflicts in overlapping time windows
- Compatible semantic date and place patterns
- Optional device or platform metadata when present, never as sole identity

File name, URI, Android versus iPhone, and file size are not identity evidence.

### 8.2 Classifications

#### Likely same Journal

There is sufficient agreeing overlap and no strong contradictory evidence. Update automatically after the normal import summary.

#### Uncertain

There is too little overlap, only semantic data, or ambiguous evidence. Ask the user where to open it.

#### Likely different Journal

Substantial overlapping timestamps imply incompatible locations. Protect the current Journal and do not write.

### 8.3 Safe destination screen

Use evidence-appropriate copy.

For uncertain imports:

> **We could not confirm this Journal update**\
> There is not enough matching travel history to know whether this export belongs with your current Journal. Your current Journal has not been changed.

For likely different imports:

> **This looks like a different Travel Journal**\
> Its overlapping travel does not closely match your current Journal. Your current Journal has not been changed.

Actions:

1. **Open once** for a temporary guest video
2. **Create another Journal** for repeated use
3. **Review comparison** as an advanced path

Review shows overlap duration, agreeing periods, conflict counts, coverage changes, and the exact target Journal without exposing coordinates unnecessarily. **Add anyway** is a separate explicit action after review. It records a user override, remains eligible for Undo last update, and never changes other Journals.

Do not place a replace or delete action on this screen.

Permanent deletion belongs under **Journal settings > Manage Journal > Start over**. Require explicit confirmation and offer backup first.

### 8.4 Temporary guest Journal

- Use an isolated temporary database or database partition.
- Do not schedule reminders or mix suggestions with the primary Journal.
- Allow preview and video creation through the same fused route pipeline.
- After creation, offer **Keep as another Journal** or **Remove temporary travel data**.
- Retain a completed video if the user removes temporary source data, while marking its source Journal unavailable.
- Explain before opening that temporary travel data is retained for up to seven days of inactivity.
- Do not clean it while preview or export is running.
- A completed or cancelled export returns to the keep-or-remove decision. If the app is interrupted, recover the temporary draft until its inactivity deadline.
- Removing temporary data preserves completed videos and their thumbnails, but source-dependent editing becomes unavailable.

## 9. Detailed-first route fusion

Do not concatenate detailed and semantic points into one list. Build ordered route spans.

```text
RouteSpan
  start
  end
  source = DETAILED | SEMANTIC_PATH | SEMANTIC_ENDPOINTS | GAP
  points
  semanticSegmentId
  activityType
  quality
  transitionReason
```

### 9.1 Precedence

1. Accepted detailed observations provide geometry for their coverage islands.
2. Semantic timeline paths fill uncovered intervals.
3. Semantic activity endpoints provide the final sparse fallback.
4. Neither source produces an explicit gap.

Semantic activity, visit, place, and trip meaning remains available even when detailed geometry wins.

### 9.2 Coverage islands

Do not treat the first and last detailed dates as continuous coverage.

- Process observations with a versioned conservative filter.
- Split islands at meaningful time discontinuities.
- Use the current 30-minute discontinuity as an initial tested boundary, not a permanent universal rule.
- Retain the source accuracy and gap summary so future policies can rebuild islands.
- Partition islands by semantic intervals where semantic structure is available.

### 9.3 Fusion cases

#### Complete overlap

Use detailed geometry for the full interval. Preserve semantic labels and boundaries. Do not append the semantic path.

#### Partial overlap

Use semantic geometry before the first coverage island, detailed geometry inside the island, and semantic geometry after it. Remove semantic points covered by the detailed interval.

#### Missing import period

Use semantic geometry. Report it as Timeline-only coverage without presenting it as an error.

#### Detailed-only period

Render coverage islands and preserve real gaps. Do not infer visits or connect long discontinuities.

#### Conflicting geometry

Accepted detailed geometry remains canonical. When competing detailed coordinates share a timestamp, apply a deterministic, versioned conflict resolver using source accuracy and continuity with neighboring accepted observations. Reject the timestamp when evidence remains ambiguous. Never place every competing coordinate into the route, and do not weave between detailed and semantic sources because one path looks nicer. Record aggregate disagreement for diagnostics.

### 9.4 Visits, flights, and boundaries

- Collapse uncertain detailed movement during a semantic visit so GPS jitter does not become travel.
- A few airport observations do not replace a semantic flight. Require an actual detailed coverage island or retain the semantic transfer leg.
- Avoid duplicate semantic endpoints when they fall inside detailed coverage.
- Add a transition connector only when needed and mark its provenance.
- Split strokes at actual gaps and at map-wrap boundaries.

### 9.5 Consumers

The following must consume the same fused route spans:

- Preview
- MP4 export
- Overview image
- Distance and point summary
- Date bounds
- Camera-leg detection
- Trip coverage
- Trip detection inputs
- Pending export serialization and process-death recovery

This prevents preview, export, and displayed statistics from describing different routes.

The current pending-export format stores a flattened point list. V3 must add versioned route topology so true gaps and provenance survive foreground-service restart. Keep backward decoding for pre-v3 pending exports where safe.

## 10. Creation experience

### 10.1 Selection screen

Keep choices about the intended output:

- Trip video
- Recap video
- Custom recap

Remove:

- Raw Data Video
- Raw data badge
- Raw date selection mode
- Data-source toggle
- Raw versus semantic language in normal creation

Add the compact explanation:

> Videos automatically use your most detailed saved routes.

### 10.2 Date bounds

The active Journal range is the union of usable detailed and semantic data.

- Disable dates outside the union.
- Keep boundary dates selectable.
- Explain invalid saved selections rather than silently clamping them.
- A range may contain Timeline-only days or real gaps.
- Show a compact coverage summary before preview.

This absorbs the remaining intent of issue #161 into the Journal model.

### 10.3 Legacy migration

- Add `JOURNAL` as the source for newly generated media metadata, or remove source from new project decisions while keeping a legacy decoder.
- Map `TripKind.RAW_DATA` projects to a custom-date Journal project.
- Preserve project ID, title, date range, created time, videos, thumbnails, and overview images.
- Set migrated raw projects to custom title mode so later automatic title generation cannot replace their existing title.
- Never rewrite completed MP4 files.
- Keep historical raw-source metadata readable for old videos.
- Assign all existing projects to the migrated primary Journal.
- Before finalizing migration, ingest detailed observations from the remembered v2 source when its URI remains readable.
- If the remembered source is unavailable, preserve the project and completed media, mark its route source unavailable, and request reimport. Do not silently render a formerly raw project from semantic geometry.
- Release the legacy persisted URI permission after successful ingestion and migration.

## 11. Journal home and update experience

### 11.1 Persistent status card

Example:

> **Your Travel Journal**\
> New detail preserved 18 days ago\
> Detailed routes preserved through August 6\
> **Update Journal**

Show separately:

- Last successful import time
- Detailed data captured through date
- Semantic-history range
- Detailed coverage days
- Preserved locations
- Timeline-only periods when relevant

### 11.2 Growth result

Animate the previous coverage line extending into newly preserved intervals. Keep the animation short and respect reduced-motion settings.

Example:

> **Your Journal grew by 18 detailed days**\
> 9,842 locations preserved\
> Detailed routes now continue through August 24

Then show total Journal history and detailed coverage. Do not imply that semantic history was newly created when only detailed coverage grew.

For an exact duplicate:

> **Your Journal is up to date**\
> No new route detail was found\
> 8,721 existing locations recognized

For a small extension:

> **Your Journal grew by 2 detailed days**\
> 614 new locations preserved

For backfill:

> **A missing part of your Journal became more detailed**

### 11.3 Trip suggestions

After a committed import:

- Rerun conservative detection only for affected dates plus the necessary home-history context.
- Exclude gap connectors and low-quality detailed jitter from detection.
- Preserve stable suggestion IDs where the underlying trip remains materially the same.
- Do not recreate dismissed or already saved suggestions.
- Bundle the result, for example `3 new journeys found`.
- Require review before saving a trip or creating a video.

## 12. Freshness and reminders

Freshness is based on captured data, not the last time the import button was pressed.

### 12.1 State inputs

- `detailedCapturedThrough` for reminder risk
- `detailedUsableThrough` for current route status
- Last import that advanced detailed coverage
- Latest reminder sent
- Current snooze deadline
- Notification permission and channel state

### 12.2 Default state machine

| Age of captured detail | In-app behavior | System behavior |
| --- | --- | --- |
| No detailed data | Explain that this export provides Timeline history only | None |
| First historical import | Show available coverage without loss-risk language | None until a recent update arms reminders |
| 0 to 13 days | Quiet up-to-date status | None |
| 14 to 20 days | Gentle Journal card | None |
| 21 to 26 days | More visible update card | One reminder around day 24 |
| 27 to 30 days | Explain possible detail loss | One final reminder around day 29 |
| More than 30 days | Persistent in-app notice | None after the final boundary reminder unless the user explicitly requests another reminder |

Thresholds are product defaults based on observed exports, not a claim that Google guarantees 30 days. Keep them centrally configurable for testing.

Only a permanent import with recent valid detailed observations arms the system schedule. Semantic-only, guest, old historical, duplicate, failed, cancelled, and mismatched imports do not create an overdue notification loop.

### 12.3 Copy

Normal reminder:

> **Grow your Travel Journal**\
> Add the latest days from your Timeline export and preserve their route detail.

Near the observed boundary:

> **Preserve recent route detail**\
> It has been 29 days since your Journal captured new detail. Some of the oldest detailed locations may soon disappear from future exports.

Do not say that the whole Journal will be permanently lost. Only unpreserved route detail is at risk.

### 12.4 Android behavior

- Create a separate low-importance `Journal reminders` channel.
- Ask for Journal reminder consent contextually after a successful recent Journal update, not at startup.
- If Android notification permission is already granted for video completion, create or enable the optional Journal channel only after this consent. If permission was denied, do not repeat the system prompt after every import.
- Explain that reminders are optional and the Journal works without them.
- Use WorkManager for an inexact local check.
- A background check reads only local freshness metadata. It does not reopen the source document or use the network.
- Store throttling state so a worker retry, reboot, or app update cannot produce duplicates.
- Deep-link the notification to the matching in-app activity and Update Journal action.
- Support snooze and disable.
- Secondary and guest Journals default to reminders off.

Trip suggestions remain in-app for v3. A later opt-in digest requires separate user evidence.

## 13. Journal activity

Use a compact, local activity area rather than a social-style notification inbox.

Supported item types:

- Journal update due
- Journal update completed
- Import needs destination decision
- New trip detected
- Monthly or yearly recap ready
- Recording gap found
- Backup recommended

Each item has a stable deduplication key, created time, state, primary action, and optional dismissal. Status conditions such as an overdue Journal may return after dismissal only when the state materially changes or the throttle period expires.

Avoid lock-screen titles that expose another person's name or a travel destination by default.

## 14. Privacy, backup, deletion, and recovery

The accumulated Journal is more sensitive than a single rolling export because it consolidates detailed movement over time.

### 14.1 Storage contract

- Store the database in app-private files, never `cacheDir`.
- Do not copy the original Timeline export into app storage.
- Do not include the Journal database, WAL and sidecar files, staging files, guest data, or archive-working files in Android cloud backup or device transfer by default.
- Continue excluding temporary imports, thumbnails, and pending export state as appropriate.
- Document that clearing storage or uninstalling removes Journals unless the user made a manual backup.
- Do not log filenames, coordinates, route summaries, or Journal names to analytics or developer services.

### 14.2 Manual backup

Production v3 requires a portable, versioned backup and restore flow.

- User selects the destination through the system document picker.
- The archive contains a manifest, database version, Journal records, import provenance needed for undo, and integrity hashes.
- Protect portable archives with authenticated encryption and a user-controlled recovery secret, subject to a focused security review.
- Never display or log the recovery secret.
- Restore into a new Journal by default. Merging a backup follows the same match and conflict rules as Timeline imports.
- Validate corruption, wrong-secret, older-version, and partial-file failures without changing existing Journals.
- Stream encryption and restore validation without leaving plaintext database copies or archive-working files behind.
- Clean interrupted export, restore, and partial archive files on restart and verify that cleanup in tests.

Database corruption must not silently create an empty Journal. Quarantine the damaged database, disclose recovery options, and keep coordinates and place names out of logs.

### 14.3 Deletion

- Deleting one Journal names the exact Journal and affected projects.
- Offer backup before deletion.
- Delete only that Journal's observations, snapshots, activity, suggestions, reminders, and source-dependent draft data.
- Keep completed videos, their thumbnails, overview images, and the minimum existing media metadata needed to display them unless the user separately confirms video deletion.
- Keep projects that own completed media as unavailable-source library records. Remove empty projects with the Journal.
- Mark retained videos and projects as source unavailable so editing or regeneration does not imply that Journal data still exists.
- Keep temporary guest cleanup separate from permanent Journal deletion.

### 14.4 Security decision gate

Before production, document the threat model and decide whether app-private storage is sufficient at rest or whether database-level encryption is required. Portable backup encryption is required regardless.

## 15. Platform boundary

### 15.1 Android

Android is the first durable Journal platform because it provides app-private storage, document access, background work, notification channels, and the existing native creation pipeline.

### 15.2 iPhone exports

An iPhone Timeline export can be opened on Android as the primary, guest, or another named Journal. Platform type does not determine identity.

Before production, validate metadata-only structure and date coverage from multiple current iPhone exports. Do not promise detailed-route accumulation when an export contains no supported detailed observations.

### 15.3 Web

The v3 web change uses the same detailed-first fusion contract within the currently opened file, removes the raw-data toggle, and updates coverage and privacy copy. Reload still clears Timeline data. It does not expose Journal reminders, named Journals, or accumulated history.

Durable web Journal accumulation remains deferred until all of the following are proven:

- IndexedDB or OPFS quota behavior on target browsers
- Recovery after site-data clearing
- Portable encrypted backup and restore
- Large-history performance
- Safari and installed-PWA behavior
- Honest privacy and durability copy

Do not present browser storage as a durable Journal until those gates pass.

## 16. Validation fixtures

Committed tests use synthetic or irreversibly sanitized fixtures. Existing ignored files under `research/private/` are not a collection workflow for new v3 evidence.

Any new real-export research requires explicit consent, a task-scoped temporary location outside the repository, metadata-only diagnostic output, and a defined deletion time. Diagnostics must exclude coordinates, filenames, content hashes, place names, Journal identifiers, and account information.

Required fixture matrix:

| Case | Required proof |
| --- | --- |
| Android mixed export | Semantic plus detailed parsing and fusion |
| Android detailed-only export | Islands, gaps, warnings, and no semantic erasure |
| Android semantic-only export | Semantic refresh without freshness advance |
| Current iPhone direct-array export | Compatibility and safe classification |
| Same file twice | Exact no-op |
| Monthly overlap | Only new observations added |
| Older file after newer | Backfill without freshness regression |
| Same person, another device | Uncertain or compatible path with confirmation |
| Different people | No mutation before destination choice |
| Travel companions | Detector does not claim identity from shared routes alone |
| Partial detailed period | Semantic-detail-semantic span order |
| Missing month | Semantic fallback without invented detail |
| Flight with airport points | One transfer leg without backtracking |
| Visit with location jitter | No false local travel |
| Crash before commit | No mutation |
| Crash after commit | One recoverable result, not duplicate import |
| Corrupt database | Safe failure and restore path |
| Large multi-year Journal | Bounded memory and acceptable import, query, preview, and export time |
| Pending export restart | Route gaps and source topology survive process death |

## 17. Acceptance criteria

### Data integrity

- Reimporting the same file changes no Journal totals.
- Overlapping exports add only previously unknown observations and provenance links.
- Missing source records never remove accumulated detail.
- Undo removes only data exclusively introduced by that import.
- Parser or filter upgrades rebuild derived data without losing observations.
- Import interruption cannot leave a partially updated Journal.

### Route correctness

- Detailed geometry wins inside accepted coverage islands.
- Semantic geometry fills uncovered intervals once.
- Overlap never creates duplicate or backtracking movement.
- Visits do not animate GPS jitter as travel.
- Sparse airport samples do not duplicate a semantic flight.
- Genuine gaps remain visible and are not connected as known travel.
- Preview, export, summary, and overview use the same fused route.

### Identity safety

- Likely different imports do not mutate the active Journal.
- Uncertain imports require a destination decision.
- Guest data cannot mix with the primary Journal.
- Permanent replacement requires a separate destructive flow.
- Every committed import is identifiable, and the latest committed permanent update can be undone.

### UX and reminders

- Creation contains no raw or semantic source choice.
- Android creation and the web single-file flow both remove the raw-data selector.
- Legacy raw projects remain usable after upgrade.
- Growth totals match committed database changes.
- Duplicate imports show an accurate up-to-date result.
- Old files do not reset freshness.
- System reminders never fire daily.
- Snooze, disable, denied permission, reboot, and app update preserve throttling.
- Notification lock-screen text does not expose Journal names, destinations, or travel dates by default.
- Trip suggestions are bundled and never auto-create a video.

### Privacy and recovery

- Journal contents remain local except for existing disclosed map-tile requests.
- The database is excluded from silent cloud backup and transfer.
- Backup and restore detect corruption and wrong secrets before mutation.
- Deletion targets the selected Journal only.
- Privacy documentation describes persistence, reminders, guest data, backup, and deletion accurately.

### Localization and accessibility

- All new user-facing copy is present in every supported locale before production.
- Dates, counts, distance, and plurals are locale-aware.
- New point counters distinguish valid observations preserved from observations accepted into the current rendered route.
- Growth animation respects reduced motion.
- Growth completion produces one TalkBack announcement and a predictable focus target rather than announcing every counter step.
- Status and coverage are available to screen readers without depending on color or animation.
- Small-screen and 200 percent font-scale layouts do not hide the Update Journal or safe destination actions.
- Every dynamic day, location, trip, and gap count uses a complete localized string or plural resource.
- Existing ambiguous suggestion actions are reviewed. Prefer **Hide suggestion** over a verb that can be mistranslated as dismissing a person from employment.

## 18. Delivery sequence

### Phase 0: architecture spike

- Finalize schemas and migrations.
- Benchmark Room insertion and indexed range queries with a multi-million-observation synthetic Journal.
- Validate Android and iPhone export shapes using metadata-only diagnostics.
- Calibrate match classification and coverage-island rules.
- Define a timezone policy for local-day grouping across imports made on devices in different zones.
- Produce the backup threat model.

Gate: approved schema, bounded memory evidence, and no unresolved destructive behavior.

### Phase 1: storage and import core

- Add Journal database and repositories.
- Refactor parser output to retain semantic segments and ordered route geometry.
- Implement streaming hash, staged transactional import, provenance, deduplication, coverage-aware semantic precedence, crash recovery, and undo.
- Version pending-export serialization so route topology and gaps survive service restart, while retaining safe backward decoding.
- Migrate the current remembered source into a primary Journal without deleting the original URI state until migration succeeds.

Gate: data-integrity and crash-recovery tests.

### Phase 2: route fusion

- Add coverage islands and route spans.
- Move preview, export, summaries, camera legs, and bounds onto the fused route.
- Add issue #81-style overlap regressions and partial-gap tests.

Gate: one route contract across every consumer.

### Phase 3: creation and migration

- Remove Raw Data Video from selection.
- Migrate `TripKind.RAW_DATA` and legacy media metadata.
- Use Journal bounds and explain invalid ranges.
- Reconcile issue #161 against the new union bounds.

Gate: signed upgrade test preserving source, projects, settings, and videos.

### Phase 3b: web single-file fusion

- Reuse the route-fusion contract for the currently opened file.
- Remove the raw-data toggle and use detailed geometry automatically where available.
- Keep data in page memory and preserve the existing no-durable-storage promise.
- Update web privacy copy, source coverage, Android-web geometry parity tests, and mobile Safari checks.

Gate: reload clears Timeline data, no IndexedDB or OPFS archive is created, and representative fused routes match Android source precedence.

### Phase 4: Journal update experience

- Add Journal status, import result, growth animation, and coverage explanations.
- Add temporary guest and another-Journal destinations.
- Add import undo and explicit deletion.

Gate: same, uncertain, different, guest, duplicate, and backfill usability tests.

### Phase 5: activity, detection, and reminders

- Add Journal activity repository and UI.
- Recalculate conservative trip suggestions after imports.
- Add contextual notification opt-in, separate channel, WorkManager check, throttling, and deep links.

Gate: reminder cadence tests and no duplicate or privacy-revealing alerts.

### Phase 6: backup, privacy, and Journal Lab

- Add protected portable backup and restore.
- Update privacy, setup, deletion, recovery, and store materials.
- Publish a separately installable Journal Lab with separate app data.
- Use a distinct `dev.mahlernim.timelinevisualizer.journallab` package and immutable Lab tags.
- Add a dedicated Lab workflow that verifies the expected package, version, signing certificate continuity, checksum, immutable prerelease tag, and co-installation with production.
- Test with recent detailed, old semantic, repeated import, missed month, iPhone guest, conflicting user, large history, and corrupted backup cases.
- Add API 26 to the existing API 28, 35, and 36 device matrix for the production migration path.

Gate: real-user evidence confirms route continuity, update comprehension, reminder tolerance, and mismatch safety.

### Phase 7: production v3 candidate

- Rebase the validated implementation on the current remote baseline.
- Run Android unit, lint, device, upgrade, large-data, localization, privacy, and release tests.
- Publish an immutable v3 candidate tag through the existing release workflow.
- Verify APK checksum, signature, embedded version, Play bundle, and closed Alpha availability before broader rollout.
- Treat the production workflow's retained AAB as an artifact only. Play Console upload, review submission, rollout, and selected-tester availability remain separate required release actions.

## 19. Repository issue reconciliation

- **#145** is complete for v2.4.1. Journal v3 replaces the separate raw-range mental model rather than reopening it.
- **#161** remains relevant. Implement its visible bounds and invalid-selection behavior using unified Journal bounds.
- **#149** remains separate. It may consume Journal semantic context and fused coverage later, but it must not block the Journal archive.
- **#81** is closed but its overlap and backtracking scenario becomes a permanent route-fusion regression.
- **PR #69** remains outside Journal v3 unless separately reconciled. Location masking and durable Journal storage have different privacy contracts.

Do not create a single oversized implementation pull request. Use one tracking issue or RFC and bounded issues aligned with the delivery phases.

## 20. Production decision gates

The following are technical evidence gates, not unresolved product questions for the user:

- Room versus direct SQLite performance
- Exact match-classification thresholds
- Exact coverage-island gap thresholds by activity type
- Timezone policy for day grouping and reminder dates
- App-private database encryption at rest
- Portable backup key derivation and recovery UX
- Maximum retained semantic snapshot history
- Browser durable-storage eligibility

If evidence does not support a safe automated decision, keep the behavior conservative and require an explicit destination choice without deleting data.
