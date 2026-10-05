import { Map as LibreMap } from 'maplibre-gl';
import type { RenderSize, Viewport } from '../types';
import { VectorMapError } from '../errors';
import { OVERSCAN, expandedViewport, reusableBackground, cropRectangle } from './background-cache';

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
  if (key && url.protocol === 'https:' && (url.hostname === 'basemaps.cartocdn.com' || url.hostname.endsWith('.basemaps.cartocdn.com'))) {
    url.searchParams.set('key', key);
  }
  return url.toString();
}

/** One renderer per journey. Initialize at the first requested view after consent. */
export class VectorMap {
  private map?: LibreMap;
  private container?: HTMLDivElement;
  private failure = false;
  private disposed = false;
  private cancelPending?: () => void;

  constructor(private readonly size: RenderSize, private readonly key: string) {}

  private initialize(view: Viewport): LibreMap {
    const container = document.createElement('div');
    this.container = container;
    container.setAttribute('aria-hidden', 'true');
    Object.assign(container.style, { position: 'fixed', left: '-10000px', top: '0', width: `${this.size.width}px`, height: `${this.size.height}px` });
    document.body.append(container);
    try {
      const map = new LibreMap({
        container, interactive: false, attributionControl: false,
        style: authenticatedCartoUrl('https://basemaps.cartocdn.com/gl/positron-gl-style/style.json', this.key),
        ...vectorCamera(view, this.size), minZoom: -2, pixelRatio: 1,
        maxCanvasSize: [this.size.width, this.size.height],
        // Keep the app's exact Mercator viewport, including wide overviews.
        transformConstrain: (center, zoom) => ({ center, zoom }),
        fadeDuration: 0, renderWorldCopies: true,
        canvasContextAttributes: { preserveDrawingBuffer: true },
        transformRequest: (url) => ({ url: authenticatedCartoUrl(url, this.key) }),
      });
      this.map = map;
      // Never retain provider errors. Their text can include credential-bearing URLs.
      const fail = () => { this.failure = true; this.cancelPending?.(); };
      map.on('error', fail);
      map.on('webglcontextlost', fail);
      return map;
    } catch {
      this.dispose();
      throw new VectorMapError();
    }
  }

  async draw(canvas: HTMLCanvasElement, view: Viewport, signal?: AbortSignal): Promise<void> {
    signal?.throwIfAborted();
    if (this.disposed || this.failure) throw new VectorMapError();
    const map = this.map ?? this.initialize(view);
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => {
        clearTimeout(timeout);
        map.off('idle', done);
        signal?.removeEventListener('abort', abort);
        this.cancelPending = undefined;
      };
      const fail = (error: Error) => { cleanup(); reject(error); };
      const done = () => { cleanup(); resolve(); };
      const abort = () => fail(new DOMException('Cancelled', 'AbortError'));
      const timeout = setTimeout(() => fail(new VectorMapError()), 30_000);
      this.cancelPending = () => fail(new VectorMapError());
      map.once('idle', done);
      signal?.addEventListener('abort', abort, { once: true });
      try {
        map.jumpTo(vectorCamera(view, this.size));
        map.triggerRepaint();
      } catch { fail(new VectorMapError()); }
    });
    signal?.throwIfAborted();
    if (this.disposed || this.failure) throw new VectorMapError();
    const context = canvas.getContext('2d');
    if (!context) throw new VectorMapError();
    context.drawImage(map.getCanvas(), 0, 0, canvas.width, canvas.height);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cancelPending?.();
    try { this.map?.remove(); }
    finally { this.container?.remove(); this.map = undefined; this.container = undefined; }
  }
}

/** Bound the background bitmap to 64 MiB, excluding GPU and encoder memory. */
export function backgroundSize(size: RenderSize): RenderSize {
  const scale = Math.min(OVERSCAN, 4096 / Math.max(size.width, size.height));
  return { width: Math.max(1, Math.round(size.width * scale)), height: Math.max(1, Math.round(size.height * scale)) };
}

/** One oversized bitmap. Refresh after a 2% scale change or a coverage miss. */
export class ReusedVectorMap {
  private readonly background = document.createElement('canvas');
  private readonly renderer: VectorMap;
  private cached?: Viewport;
  private disposed = false;

  constructor(size: RenderSize, key: string) {
    const pixels = backgroundSize(size);
    this.background.width = pixels.width;
    this.background.height = pixels.height;
    this.renderer = new VectorMap(pixels, key);
  }

  async draw(canvas: HTMLCanvasElement, view: Viewport, signal?: AbortSignal) {
    signal?.throwIfAborted();
    if (this.disposed) throw new VectorMapError();
    if (!this.cached || !reusableBackground(this.cached, view)) {
      const expanded = expandedViewport(view);
      await this.renderer.draw(this.background, expanded, signal);
      this.cached = expanded;
    }
    signal?.throwIfAborted();
    if (this.disposed) throw new VectorMapError();
    const crop = cropRectangle(this.cached, view, this.background);
    const context = canvas.getContext('2d');
    if (!context) throw new VectorMapError();
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    context.drawImage(this.background, crop.x, crop.y, crop.width, crop.height, 0, 0, canvas.width, canvas.height);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cached = undefined;
    try { this.renderer.dispose(); }
    finally { this.background.width = this.background.height = 0; }
  }
}
