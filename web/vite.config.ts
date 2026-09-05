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
      const urls = new Set(['./', './manifest.webmanifest', './icon.svg', './demo-poster.webp']);
      const visit = (name: string): void => {
        const output = bundle[name];
        if (!output || urls.has('./' + name)) return;
        urls.add('./' + name);
        if (output.type === 'chunk') {
          for (const dependency of output.imports) visit(dependency);
          const metadata = (output as typeof output & { viteMetadata?: { importedCss: Set<string> } }).viteMetadata;
          for (const css of metadata?.importedCss ?? []) urls.add('./' + css);
        }
      };
      for (const [name, output] of Object.entries(bundle)) {
        if (output.type === 'chunk' && output.isEntry && output.name === 'landing') visit(name);
      }
      for (const asset of publicAssets) {
        digest.update(asset.path);
        digest.update(asset.source);
      }
      for (const [fileName, output] of Object.entries(bundle).sort(([left], [right]) => left.localeCompare(right))) {
        digest.update(fileName);
        digest.update(output.type === 'asset' ? output.source : output.code);
      }
      const cacheName = `${SERVICE_WORKER_CACHE_PREFIX}${digest.digest('hex').slice(0, 16)}`;
      this.emitFile({
        type: 'asset',
        fileName: 'service-worker.js',
        source: renderServiceWorker(cacheName, [...urls]),
      });
    },
  };
}

export default defineConfig({
  base: '/google-timeline-visualizer/',
  plugins: [generatedServiceWorker(), {
    name: 'stable-apk-link',
    transformIndexHtml(html) {
      const url = process.env.VITE_STABLE_APK_URL || 'https://github.com/mahlernim/google-timeline-visualizer/releases/latest';
      if (!/^https:\/\/github\.com\/mahlernim\/google-timeline-visualizer\/releases\//.test(url)) throw new Error('Invalid APK release URL');
      return html.replaceAll('__APK_URL__', url.replaceAll('&', '&amp;').replaceAll('"', '&quot;'));
    },
  }],
  build: {
    target: 'safari16.4',
    manifest: true,
    rollupOptions: { input: { landing: resolve(import.meta.dirname, 'index.html'), app: resolve(import.meta.dirname, 'app/index.html') } },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
