# Journal Lab 21

Journal Lab 21 experiments with semantic-aware camera framing for Travel Journal videos.

- Activity records provide the global start, destination, and time boundaries of a trip.
- Detailed Journal observations remain the geometry followed by the marker and route trail.
- A long activity stays widely framed even when it is composed of many short, squiggly route steps.
- The camera begins closing near arrival and uses a tighter local view for detailed movement around the destination.
- Missing or unusable activity context falls back to the existing geometric camera behavior.
- Semantic episode context is preserved when an interrupted video export resumes.
- Generated tests cover dense long-distance geometry, destination detail, route gaps, and pending-export recovery without personal Timeline data.

This is an experimental build for issue #203. Journal Lab installs separately from Timeline Visualizer and upgrades Journal Lab 20 in place. Timeline files and the Travel Journal remain on the device.
