# Location masking prototype test

This APK is an experimental build for evaluating location-selection usability. It installs beside the production app as **Timeline Visualizer Prototype** and uses separate app data.

It explores the safe-sharing idea proposed by [@Xerdiosa in PR 69](https://github.com/mahlernim/google-timeline-visualizer/pull/69) and discussed in [issue 66](https://github.com/mahlernim/google-timeline-visualizer/issues/66). It is a usability experiment, not an acceptance or rejection of that draft pull request.

## Install

Download `TimelineVisualizer-PrivacyPrototype-1.apk` from the GitHub prerelease assets. Do not use an Actions build artifact for user testing. Android may ask you to allow installation from the browser or file manager that opened the APK.

The prototype does not replace or update the production app. Uninstall **Timeline Visualizer Prototype** when the test is finished. If Android reports a signing conflict with a different prototype build, uninstall that older prototype before installing this one.

## Important limitations

- Location masking reduces visible precision. It does not guarantee anonymity.
- Locations outside selected circles remain precise.
- The prototype hides recorded Timeline points inside each circle. It also hides a route segment when sparse Timeline data crosses a circle without recording a point inside it.
- Always review the protected preview and generated video before sharing it.
- Keep your Timeline JSON on your device. Do not attach it to feedback reports.

## Suggested test

Please try these tasks without reading implementation details first.

1. Import a Timeline file.
2. Hide one familiar private location.
3. Add a second location with a different radius.
4. Find a place outside the map's initial view using pan and pinch zoom.
5. Edit and remove a hidden location.
6. Review the protected preview and create a protected video.

## Feedback

Reply on [issue 66](https://github.com/mahlernim/google-timeline-visualizer/issues/66) without sharing coordinates, screenshots containing private routes, or Timeline files.

Please report:

- Which task was difficult or unclear
- Whether you understood what would remain visible
- Approximate time needed to hide the first location
- Device model and Android version
- Whether you would use this feature
