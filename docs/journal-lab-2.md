# Journal Lab 2

Journal Lab is a separately installable experimental build for the Travel Journal architecture. It uses package `dev.mahlernim.timelinevisualizer.journallab` and does not replace the production app.

## Included in Lab 2

- Timeline files import into the durable primary Travel Journal
- Repeated files are recognized by their exact content hash
- Overlapping detailed observations accumulate without creating duplicate route points
- The Journal reloads from its local database when the app starts
- Preview and video export automatically use the best available route
- Detailed observations are canonical wherever they exist
- Semantic Timeline geometry fills periods that do not have detailed observations
- A mismatched update is blocked without deleting or replacing the existing Journal
- Raw-only Timeline exports can create the first Journal
- Video records identify the unified Journal as their data source

## Import identity safeguard

The first Timeline file creates the primary Journal. A later new file can extend that Journal only when it contains at least one exact detailed observation already present in the Journal. An exact reimport remains a safe no-op. Files without provable detailed overlap are blocked and the existing Journal remains unchanged.

This is deliberately conservative. Lab 2 does not yet provide a secondary Journal flow for another person or device. It also cannot use semantic-only overlap as identity proof.

## Current route limits

The durable route service preserves detailed, semantic, and gap spans separately. The current preview and export model accepts only one flat point sequence. Lab 2 therefore uses a flattened compatibility route for those screens. If two retained spans have a true gap between them, the current renderer can draw a direct connector across that gap.

The current Timeline parser also exposes semantic data as normalized points. Original activity labels, place identity, and segment boundaries are not retained yet.

Do not treat Lab 2 as the final migration format. A later Lab must carry route-span topology through preview, distance calculation, trip detection, and video rendering before the accumulated Journal is promoted to the production app.
