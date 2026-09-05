export const SERVICE_WORKER_CACHE_PREFIX = 'timeline-visualizer-web-';

export function renderServiceWorker(cacheName: string, precacheUrls: readonly string[]): string {
  const manifest = [...new Set(precacheUrls)].sort();
  return `const CACHE_PREFIX = ${JSON.stringify(SERVICE_WORKER_CACHE_PREFIX)};
const CACHE_NAME = ${JSON.stringify(cacheName)};
const APP_SHELL = './';
const PRECACHE_URLS = ${JSON.stringify(manifest)};

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(PRECACHE_URLS)));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(
      keys
        .filter((key) => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME)
        .map((key) => caches.delete(key)),
    )).then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET' || new URL(event.request.url).origin !== self.location.origin) return;
  event.respondWith(
    fetch(event.request).then((response) => {
      const url = new URL(event.request.url);
      const base = new URL('./', self.location.href).pathname;
      const appAsset = url.pathname.startsWith(base + 'assets/') && /\\.(js|css)$/.test(url.pathname);
      const shell = url.pathname === base || url.pathname === base + 'app/' || url.pathname === base + 'app/index.html';
      if (response.ok && !url.search && (appAsset || shell)) {
        const copy = response.clone();
        event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy)).catch(() => undefined));
      }
      return response;
    }).catch(async () => {
      const cached = await caches.match(event.request);
      if (cached) return cached;
      if (event.request.mode === 'navigate') {
        const shell = await caches.match(APP_SHELL);
        if (shell) return shell;
      }
      throw new Error('Offline resource is not cached.');
    }),
  );
});
`;
}
