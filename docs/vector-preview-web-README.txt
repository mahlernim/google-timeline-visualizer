Timeline Visualizer vector preview

Extract the complete ZIP. Serve this folder over localhost, for example with
python -m http.server 8080
Then open http://localhost:8080/google-timeline-visualizer/app/ in Chrome or Edge.
Opening index.html directly as a file does not support video export.

Select a Timeline file or google-timeline-visualizer/sample-timeline.json,
continue to Preview, and enable
"Use vector maps (preview)" before accepting map loading. Raster is the default.
Map requests go to CARTO. Timeline files and video encoding remain on device.

This preview uses bounded reuse of vector map backgrounds. It can create softer
labels and different label placement, and remains slower than raster in our
synthetic benchmarks. Try short videos first. It does not replace the live site.
