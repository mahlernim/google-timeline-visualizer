# Timeline Visualizer privacy prototype 2.1

This is an experimental side build for issue 66. It installs beside the production
app as **Timeline Visualizer Privacy** and does not change the main release version.

Prototype 2 preserves route continuity by replacing points inside each selected
privacy circle with one stable stand-in. Unlike the direct center-point proposal,
the stand-in is offset within the selected circle, so the exact selected center is
not placed in the preview or exported video.

This reduces visible precision but does not guarantee anonymity. Locations outside
selected circles remain precise. Flights remain unchanged. Review the full preview
and generated video before sharing.

See the [testing guide](https://github.com/mahlernim/google-timeline-visualizer/blob/codex/privacy-prototype-2/docs/privacy-prototype-2-testing.md)
and share non-sensitive feedback in [issue 66](https://github.com/mahlernim/google-timeline-visualizer/issues/66).
