# Timeline Visualizer 2.1.0

This release improves route cleanup, video timing, and bottom navigation across
different Android system layouts.

- Keep all bottom-navigation icons and labels visible when Android uses gesture
  navigation, three-button navigation, landscape, or a constrained window.
- Conservatively ignore isolated GPS outliers that require impossible travel away
  from and immediately back to the surrounding route.
- Show the ignored-point count and provide an Off setting that immediately restores
  every location from the selected Timeline.
- Keep filtering local and non-destructive. The original Timeline JSON is never
  modified or replaced.
- Choose Custom to enter any whole-number journey duration from 10 through 300
  seconds. Existing presets and the 30-second default remain available.
- Show a reminder about rendering time and storage for durations over 60 seconds.

This release implements issues #36, #37, and #39. Existing saved videos, settings,
remembered Timeline access, and unfinished exports remain compatible.
