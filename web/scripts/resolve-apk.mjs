const repository = 'mahlernim/google-timeline-visualizer';
const response = await fetch('https://api.github.com/repos/' + repository + '/releases/latest', {
  headers: { Accept: 'application/vnd.github+json', ...(process.env.GH_TOKEN ? { Authorization: 'Bearer ' + process.env.GH_TOKEN } : {}) },
});
if (!response.ok) throw new Error('Could not resolve the latest stable APK');
const release = await response.json();
const apk = release.assets?.find((asset) => /^TimelineVisualizer-v[\d.]+\.apk$/.test(asset.name));
if (release.draft || release.prerelease || !apk?.browser_download_url?.startsWith('https://github.com/' + repository + '/releases/download/')) {
  throw new Error('Latest stable release has no verified APK download link');
}
console.log('url=' + apk.browser_download_url);
