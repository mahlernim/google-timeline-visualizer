import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';

import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';

import { renderServiceWorker, SERVICE_WORKER_CACHE_PREFIX } from './src/service-worker-build.ts';

interface PublicAsset {
  readonly path: string;
  readonly source: Buffer;
}

function collectPublicAssets(directory: string, root = directory): PublicAsset[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const absolute = join(directory, entry.name);
    if (entry.isDirectory()) return collectPublicAssets(absolute, root);
    if (!entry.isFile() || entry.name === '.nojekyll' || entry.name === 'service-worker.js') return [];
    return [{
      path: relative(root, absolute).split(sep).join('/'),
      source: readFileSync(absolute),
    }];
  });
}

function generatedServiceWorker(): Plugin {
  const publicDirectory = resolve(import.meta.dirname, 'public');
  const publicAssets = statSync(publicDirectory).isDirectory() ? collectPublicAssets(publicDirectory) : [];
  return {
    name: 'generated-service-worker',
    generateBundle(_options, bundle) {
      const digest = createHash('sha256');
      const urls = ['./', ...publicAssets.map((asset) => `./${asset.path}`)];
      for (const asset of publicAssets) {
        digest.update(asset.path);
        digest.update(asset.source);
      }
      for (const [fileName, output] of Object.entries(bundle).sort(([left], [right]) => left.localeCompare(right))) {
        urls.push(fileName === 'index.html' ? './' : `./${fileName}`);
        digest.update(fileName);
        digest.update(output.type === 'asset' ? output.source : output.code);
      }
      const cacheName = `${SERVICE_WORKER_CACHE_PREFIX}${digest.digest('hex').slice(0, 16)}`;
      this.emitFile({
        type: 'asset',
        fileName: 'service-worker.js',
        source: renderServiceWorker(cacheName, urls),
      });
    },
  };
}

export default defineConfig({
  base: '/google-timeline-visualizer/',
  plugins: [generatedServiceWorker()],
  build: {
    target: 'safari16.4',
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
