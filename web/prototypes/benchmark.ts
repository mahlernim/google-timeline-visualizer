import { StreamTarget, CanvasSource, Mp4OutputFormat, Output, Quality } from 'mediabunny';
import { Mp4Buffer } from '../src/mp4-buffer';
import { cameraViewportAt, blendViewport } from '../src/camera';
import { easeOutCubic, frameAtElapsedSeconds } from '../src/animation';
import { prepareJourney, drawJourneyFrame, drawFrame, releaseJourney } from '../src/renderer';
import type { GeoPoint } from '../src/types';
import { ReusedVectorMap, VectorMap } from './vector-map';

const key = import.meta.env.VITE_CARTO_BASEMAP_API_KEY?.trim() ?? '';
const overlay = { title: 'Synthetic comparison', periodLabel: 'Seoul · 서울 · 東京', separator: ' · ', formatDistance: (km: number) => `${km.toFixed(0)} km` };
const routes: Record<string, number[][]> = {
  city: [[37.55,126.92],[37.57,126.98],[37.58,127.02],[37.54,127.06],[37.50,127.04]],
  long: [[37.56,126.98],[36.35,127.38],[35.18,129.08],[34.69,135.50],[35.68,139.69]],
  dateline: [[35.68,139.69],[40,170],[40,-170],[37.77,-122.42]],
};
let controller: AbortController;
const select = (id: string) => document.getElementById(id) as HTMLSelectElement;
const status = document.getElementById('status')!;
const results: unknown[] = [];
const urls: string[] = [];
const modes = ['raster', 'vector', 'reuse'];
let runId = '';
async function save(name: string, body: Blob | string) {
  const response = await fetch(`/_prototype-output/${runId}/${name}`,{method:'POST',body});
  if (!response.ok) throw new Error('Run with vite.prototype.config.ts to preserve comparison evidence');
}
document.getElementById('cancel')!.onclick = () => controller?.abort();
document.getElementById('run')!.onclick = async () => {
  const run = document.getElementById('run') as HTMLButtonElement;
  const cancel = document.getElementById('cancel') as HTMLButtonElement;
  run.disabled = true; cancel.disabled = false; controller = new AbortController();
  results.length = 0;
  runId = String(Date.now());
  urls.splice(0).forEach(url => URL.revokeObjectURL(url));
  for (const mode of modes) document.getElementById(`${mode}-result`)!.replaceChildren();
  document.getElementById('metrics')!.textContent = '';
  status.textContent = 'Preparing comparison';
  const size = Number(select('size').value);
  const route = select('route').value;
  const points: GeoPoint[] = routes[route].map(([latitude,longitude], i) => ({latitude,longitude,instant:new Date(Date.UTC(2026,0,1+i))}));
  try {
    if (!key) throw new Error('A CARTO test key must be supplied to the development server');
    for (const mode of select('order').value === 'reverse' ? [...modes].reverse() : modes) {
      performance.clearResourceTimings();
      const canvas = document.getElementById(mode) as HTMLCanvasElement;
      canvas.width = canvas.height = size;
      const begun = performance.now();
      const journey = await prepareJourney(points, canvas, 'close-up', 10, controller.signal);
      const vector = mode === 'reuse' ? new ReusedVectorMap(canvas, key) : mode === 'vector' ? new VectorMap(canvas, key) : null;
      const buffer = new Mp4Buffer();
      const target = new StreamTarget(new WritableStream({ write: ({data, position}) => buffer.write(data, position) }));
      const output = new Output({ format: new Mp4OutputFormat(), target });
      const source = new CanvasSource(canvas, { codec: 'avc', quality: new Quality({ bitrate: 2_500_000 }), keyFrameInterval: 1 });
      output.addVideoTrack(source, { frameRate: 24 });
      const frameMs: number[] = [];
      let firstFrameMs = 0;
      try {
        await output.start();
        for (let index = 0; index < 240; index++) {
          controller.signal.throwIfAborted();
          const frame = frameAtElapsedSeconds(index / 24, 10);
          const start = performance.now();
          if (vector) {
            const current = cameraViewportAt(journey.cameraTrack, frame.journeyProgress);
            const view = frame.outroProgress <= 0 ? current : blendViewport(current, journey.overviewViewport, easeOutCubic(frame.outroProgress), journey.size);
            await vector.draw(canvas, view, controller.signal);
            drawFrame(canvas, journey, frame, overlay, false);
          } else await drawJourneyFrame(canvas, journey, frame, overlay, controller.signal);
          frameMs.push(performance.now() - start);
          if (!index) firstFrameMs = performance.now() - begun;
          await source.add(index / 24, 1 / 24);
          if ([0,120,239].includes(index)) {
            const picture = await new Promise<Blob>((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('Snapshot unavailable'))));
            await save(`${route}-${mode}-${size}-${index}.png`,picture);
          }
          status.textContent = `${mode} frame ${index + 1} / 240`;
        }
        await output.finalize();
        const blob = buffer.blob();
        const url = URL.createObjectURL(blob); urls.push(url);
        const holder = document.getElementById(`${mode}-result`)!;
        const video = document.createElement('video'); video.controls = true; video.src = url;
        const link = document.createElement('a'); link.href = url; link.download = `${route}-${mode}-${size}.mp4`; link.textContent = 'Download video';
        holder.replaceChildren(video, link);
        const carto = performance.getEntriesByType('resource').filter(entry => new URL(entry.name).hostname.endsWith('basemaps.cartocdn.com'));
        const sorted = frameMs.slice(1).sort((a,b)=>a-b);
        const metrics = {runId,mode,route,size,frames:240,mapRenders:vector?.renders,reusedFrames:vector instanceof ReusedVectorMap ? vector.reusedFrames : 0,backgroundCacheBytes:vector instanceof ReusedVectorMap ? vector.cacheBytes : 0,firstFrameMs,totalMs:performance.now()-begun,
          steadyFrameMedianMs:sorted[Math.floor(sorted.length*.5)],steadyFrameP95Ms:sorted[Math.floor(sorted.length*.95)],
          cartoResourceLoads:carto.length,vectorRequests:vector?.requests,mp4Bytes:blob.size,
          rasterDecodedCacheBytes:journey.tileCache?.residentBytes,
          userAgent:navigator.userAgent,notes:'Single run. Browser cache may be warm. Resource loads are not billed requests. Native/browser memory and real-device performance not measured. Total includes three PNG evidence captures.'};
        results.push(metrics);
        await save(`${route}-${mode}-${size}.mp4`,blob);
        await save(`${route}-${mode}-${size}.json`,JSON.stringify(metrics,null,2));
        document.getElementById('metrics')!.textContent = JSON.stringify(results,null,2);
      } catch (error) { await output.cancel().catch(()=>{}); throw error; }
      finally { vector?.dispose(); releaseJourney(journey); }
    }
    status.textContent = `Comparison complete (${runId})`;
  } catch (error) { status.textContent = controller.signal.aborted ? 'Comparison cancelled' : error instanceof Error ? error.message : 'Comparison failed'; }
  finally { run.disabled = false; cancel.disabled = true; }
};
