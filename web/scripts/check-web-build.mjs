import { readFileSync, statSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../dist');
const manifest = JSON.parse(readFileSync(resolve(root, '.vite/manifest.json'), 'utf8'));
const js = new Set();
const visit = (key) => {
  const entry = manifest[key];
  if (!entry) throw new Error('Missing manifest entry ' + key);
  if (js.has(entry.file)) return;
  js.add(entry.file);
  for (const dependency of entry.imports ?? []) visit(dependency);
};
visit('index.html');
const compressedBytes = [...js].reduce((sum, file) => sum + gzipSync(readFileSync(resolve(root, file))).length, 0);
if (compressedBytes > 30 * 1024) throw new Error('Landing JavaScript exceeds 30 KiB compressed');
const sw = readFileSync(resolve(root, 'service-worker.js'), 'utf8');
const precache = JSON.parse(/const PRECACHE_URLS = (.*);/.exec(sw)[1]);
for (const url of precache) {
  if (/\.js$/.test(url) && !js.has(url.slice(2))) throw new Error('Service worker preloads app-only code ' + url);
  if (/\.mp4$|sample-timeline|import\.worker/.test(url)) throw new Error('Service worker preloads deferred media or data');
  if (url !== './' && !statSync(resolve(root, url.slice(2))).isFile()) throw new Error('Missing precached file ' + url);
}
const demo = readFileSync(resolve(root, 'demo-journey.mp4'));
if (demo.length > 750 * 1024) throw new Error('Landing demo exceeds 750 KiB');
if (demo.indexOf(Buffer.from('moov')) > demo.indexOf(Buffer.from('mdat'))) throw new Error('Demo needs fast-start metadata');
if (statSync(resolve(root, 'demo-poster.webp')).size > 60 * 1024) throw new Error('Demo poster exceeds 60 KiB');
console.log('Landing JS ' + compressedBytes + ' gzip bytes, demo ' + demo.length + ' bytes. Deferred app and media verified.');
