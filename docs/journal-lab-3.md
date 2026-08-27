# Journal Lab 3

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Lab 3 goal

Lab 3 is intended to advance the accumulated Travel Journal beyond the flat compatibility route used by Lab 2. Its goal is to preserve meaningful route structure wherever the Journal combines detailed observations, semantic Timeline history, and true gaps.

## Included in Lab 3

- Preview, overview images, and MP4 export split the drawn route at explicit Journal gaps
- Distance summaries exclude movement across explicit gaps
- Camera movement and route timing use only connected route sections
- Trip coverage excludes movement across explicit gaps
- Month, year, and exact-date selection preserve gap topology
- Pending video exports retain gap boundaries across foreground-service or process restart
- Pending-export format 12 remains backward compatible with formats 1 through 11
- Production timelines remain fully connected and retain their existing behavior
- Journal imports, detailed-first fusion, mismatch protection, and startup restoration from Lab 2 remain available

## Current limits

Lab 3 can preserve only gaps already identified by the canonical Journal route service. The current parser normalizes semantic history into points and does not retain original activity, place, or segment boundaries. Separate semantic segments can therefore still appear connected when the parser did not provide enough structure to prove a gap.

Pending video exports retain continuity breaks but do not yet retain detailed versus semantic source labels. Trip suggestions remain point-based because the current detector groups daily locations and does not calculate movement between adjacent points. Gap-aware trip coverage is kept separate from that detector.

Lab 3 does not yet include the planned import-growth animation, freshness reminders, secondary Journal flow, import undo, or final production migration.

Journal Lab remains experimental. Do not treat its database as the final production migration format until the implementation, upgrade path, and recovery behavior have been verified.
