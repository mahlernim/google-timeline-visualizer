# Privacy prototype 2 test

This experimental APK tests a continuity-preserving alternative for issue 66. It
installs beside the production app as **Timeline Visualizer Privacy** and uses a
separate application ID and separate app data.

## What changed

Prototype 1 removed Timeline points and route segments inside selected circles.
That could hide too much of a trip and leave large visible gaps.

Prototype 2 keeps the proposal's useful idea of one stable replacement point per
privacy circle, with one safety adjustment. It does not use the selected circle
center as the visible replacement. Each circle receives a stable stand-in point
offset by 35% to 80% of its radius. The stand-in remains inside the selected
circle, but the exact center is not placed in the preview or video.

Points outside selected circles remain precise. Segments identified as flights
remain unchanged. This feature reduces visible precision but does not guarantee
anonymity. Always review the full protected preview and video before sharing.

## Suggested comparison

Use the fictional fixture linked in issue 66 or another non-sensitive test file.
Do not upload personal Timeline data.

1. Import the Timeline file.
2. Enable Safe sharing mode.
3. Center a privacy circle on one recurring location.
4. Compare route continuity with prototype 1 and the unprotected preview.
5. Confirm that the exact selected center is not used as the stand-in.
6. Try two overlapping circles and two different radii.
7. Confirm that flights and points outside the circles remain unchanged.

Report whether the stand-in makes the trip understandable, whether its behavior is
clear, and whether any remaining route still reveals too much. Do not attach exact
coordinates, personal routes, or screenshots that contain private locations.
