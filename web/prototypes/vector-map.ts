import { Map as LibreMap } from 'maplibre-gl';
import type { RenderSize, Viewport } from '../src/types';

export function vectorCamera(view: Viewport, size: RenderSize) {
  const x = (view.minX + view.maxX) / 2;
  const y = (view.minY + view.maxY) / 2;
  return {
    center: [x * 360 - 180, Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180 / Math.PI] as [number, number],
    zoom: Math.log2(size.width / (512 * (view.maxX - view.minX))),
  };
}

/** Only send the project credential to CARTO's basemap service. */
export function authenticatedCartoUrl(raw: string, key: string): string {
  const url = new URL(raw);
  if (url.protocol === 'https:' && (url.hostname === 'basemaps.cartocdn.com' || url.hostname.endsWith('.basemaps.cartocdn.com'))) {
    url.searchParams.set('key', key);
  }
  return url.toString();
}

/** Prototype only. One renderer and resource cache per run, with exact camera synchronization. */
export class VectorMap {
  private readonly map: LibreMap;
  private readonly container: HTMLDivElement;
  private failure: Error | null = null;
  private cancelPending?: () => void;
  readonly requests: Record<string, number> = {};

  constructor(size: RenderSize, key: string) {
    this.container = document.createElement('div');
    Object.assign(this.container.style, { position: 'fixed', left: '-10000px', top: '0', width: `${size.width}px`, height: `${size.height}px` });
    document.body.append(this.container);
    this.map = new LibreMap({
      container: this.container, interactive: false, attributionControl: false,
      style: authenticatedCartoUrl('https://basemaps.cartocdn.com/gl/positron-gl-style/style.json', key),
      center: [126.98, 37.56], zoom: 10, pixelRatio: 1,
      fadeDuration: 0, renderWorldCopies: true,
      canvasContextAttributes: { preserveDrawingBuffer: true },
      transformRequest: (url, type) => {
        this.requests[type ?? 'unknown'] = (this.requests[type ?? 'unknown'] ?? 0) + 1;
        return { url: authenticatedCartoUrl(url, key) };
      },
    });
    // Never retain provider error text because it can include credential-bearing URLs.
    this.map.on('error', () => { this.failure = new Error('Vector resource failed to load'); this.cancelPending?.(); });
  }

  async draw(canvas: HTMLCanvasElement, view: Viewport, signal?: AbortSignal): Promise<void> {
    signal?.throwIfAborted();
    if (this.failure) throw this.failure;
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => {
        clearTimeout(timeout); this.map.off('idle', done);
        signal?.removeEventListener('abort', abort); this.cancelPending = undefined;
      };
      const fail = (error: Error) => { cleanup(); reject(error); };
      const done = () => { cleanup(); resolve(); };
      const abort = () => fail(new DOMException('Cancelled', 'AbortError'));
      const timeout = setTimeout(() => fail(new Error('Vector frame timed out')), 30_000);
      this.cancelPending = () => fail(this.failure ?? new Error('Vector renderer disposed'));
      this.map.once('idle', done);
      signal?.addEventListener('abort', abort, { once: true });
      this.map.jumpTo(vectorCamera(view, canvas));
      this.map.triggerRepaint();
    });
    signal?.throwIfAborted();
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Canvas unavailable');
    context.drawImage(this.map.getCanvas(), 0, 0, canvas.width, canvas.height);
  }

  dispose(): void {
    this.cancelPending?.(); this.map.remove(); this.container.remove();
  }
}
