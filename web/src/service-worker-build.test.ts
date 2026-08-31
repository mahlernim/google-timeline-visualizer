import { describe, expect, it } from 'vitest';

import { renderServiceWorker, SERVICE_WORKER_CACHE_PREFIX } from './service-worker-build';

describe('service worker build', () => {
  it('preloads every generated static URL once', () => {
    const source = renderServiceWorker(`${SERVICE_WORKER_CACHE_PREFIX}abc123`, [
      './assets/app.js',
      './',
      './icon.svg',
      './assets/app.js',
    ]);

    expect(source).toContain('const PRECACHE_URLS = ["./","./assets/app.js","./icon.svg"]');
    expect(source).toContain('cache.addAll(PRECACHE_URLS)');
  });

  it('deletes only obsolete caches owned by this app', () => {
    const source = renderServiceWorker(`${SERVICE_WORKER_CACHE_PREFIX}current`, ['./']);

    expect(source).toContain('key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME');
    expect(source).not.toContain('keys.filter((key) => key !== CACHE_NAME)');
  });
});
