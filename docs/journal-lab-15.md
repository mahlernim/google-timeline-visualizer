# Journal Lab 15

Journal Lab 15 makes Create state changes deterministic before the user can continue.

- Confirmed, manual, and saved trips refresh Continue immediately when their route is already prepared.
- Uncovered date changes disable Continue before background route preparation starts.
- A superseded route request can no longer overwrite the user's newer cached selection.
- Sparse Journal periods remain usable even when a boundary year contains no recorded point.
- Changing the Find trips period clears suggestions from the previous period.
- Route completion callbacks and progress updates are isolated from stale or failed requests.

This remains a separately installable experimental build. It does not replace the production app and does not upload Timeline data.
