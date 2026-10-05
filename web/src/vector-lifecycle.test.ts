import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Viewport } from './types';
import { VectorMapError } from './errors';

const state = vi.hoisted(() => ({ maps: [] as any[], failInitialize: false }));
vi.mock('maplibre-gl', () => ({
  Map: class {
    handlers = new Map<string, Set<() => void>>();
    remove = vi.fn();
    jumpTo = vi.fn();
    triggerRepaint = vi.fn();
    constructor(readonly options: any) {
      if (state.failInitialize) throw new Error('provider URL with private key');
      state.maps.push(this);
    }
    on(event: string, fn: () => void) {
      if (!this.handlers.has(event)) this.handlers.set(event, new Set());
      this.handlers.get(event)!.add(fn);
    }
    once(event: string, fn: () => void) { this.on(event, fn); }
    off(event: string, fn: () => void) { this.handlers.get(event)?.delete(fn); }
    emit(event: string) { for (const fn of [...this.handlers.get(event) ?? []]) fn(); }
    getCanvas() { return {}; }
  },
}));
import { backgroundSize, ReusedVectorMap, VectorMap, vectorCamera } from './vector/vector-map';

const view: Viewport = { minX: .9, maxX: 1.1, minY: .4, maxY: .6, zoom: 2 };
const size = { width: 480, height: 480 };
const drawImage = vi.fn();
const removed = vi.fn();
const output = { ...size, getContext: () => ({ drawImage }) } as unknown as HTMLCanvasElement;
beforeEach(() => {
  state.maps = [];
  state.failInitialize = false;
  vi.stubGlobal('document', {
    body: { append: vi.fn() },
    createElement: () => ({ style: {}, setAttribute: vi.fn(), remove: removed, getContext: () => ({ drawImage }) }),
  });
});
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); vi.useRealTimers(); });

describe('vector rendering lifecycle', () => {
  it('loads no map until draw and starts at the actual route', async () => {
    const renderer = new VectorMap(size, 'test');
    expect(state.maps).toHaveLength(0);
    const pending = renderer.draw(output, view);
    const map = state.maps[0];
    expect(map.options.center).toEqual(vectorCamera(view, size).center);
    expect(drawImage).not.toHaveBeenCalled();
    map.emit('idle');
    await pending;
    expect(drawImage).toHaveBeenCalledOnce();
    renderer.dispose();
    renderer.dispose();
    expect(map.remove).toHaveBeenCalledOnce();
    expect(removed).toHaveBeenCalledOnce();
  });
  it('cancels an outstanding frame, detaches its listeners, and paints nothing', async () => {
    const renderer = new VectorMap(size, 'test');
    const controller = new AbortController();
    const pending = renderer.draw(output, view, controller.signal);
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(state.maps[0].handlers.get('idle').size).toBe(0);
    expect(drawImage).not.toHaveBeenCalled();
    renderer.dispose();
  });
  it.each(['error', 'webglcontextlost'])('rejects %s without returning a partial frame', async (event) => {
    const renderer = new VectorMap(size, 'test');
    const pending = renderer.draw(output, view);
    state.maps[0].emit(event);
    await expect(pending).rejects.toBeInstanceOf(VectorMapError);
    await expect(renderer.draw(output, view)).rejects.toBeInstanceOf(VectorMapError);
    expect(drawImage).not.toHaveBeenCalled();
    renderer.dispose();
  });
  it('bounds a stalled load and removes the idle listener', async () => {
    vi.useFakeTimers();
    const renderer = new VectorMap(size, 'test');
    const pending = expect(renderer.draw(output, view)).rejects.toBeInstanceOf(VectorMapError);
    await vi.advanceTimersByTimeAsync(30_000);
    await pending;
    expect(state.maps[0].handlers.get('idle').size).toBe(0);
    expect(drawImage).not.toHaveBeenCalled();
    renderer.dispose();
  });
  it('sanitizes initialization errors and removes the offscreen element', async () => {
    state.failInitialize = true;
    const renderer = new VectorMap(size, 'test');
    await expect(renderer.draw(output, view)).rejects.toThrow('Vector map unavailable');
    expect(removed).toHaveBeenCalledOnce();
  });
  it('reuses a complete background but rejects cached drawing after disposal', async () => {
    const renderer = new ReusedVectorMap(size, 'test');
    const first = renderer.draw(output, view);
    state.maps[0].emit('idle');
    await first;
    await renderer.draw(output, view);
    expect(state.maps[0].jumpTo).toHaveBeenCalledOnce();
    renderer.dispose();
    renderer.dispose();
    drawImage.mockClear();
    await expect(renderer.draw(output, view)).rejects.toBeInstanceOf(VectorMapError);
    expect(drawImage).not.toHaveBeenCalled();
    expect(state.maps[0].remove).toHaveBeenCalledOnce();
  });
  it('settles a pending frame when disposed', async () => {
    const renderer = new ReusedVectorMap(size, 'test');
    const pending = renderer.draw(output, view);
    renderer.dispose();
    await expect(pending).rejects.toBeInstanceOf(VectorMapError);
    expect(drawImage).not.toHaveBeenCalled();
  });
  it('bounds high-resolution backgrounds while retaining their aspect ratio', () => {
    expect(backgroundSize(size)).toEqual({ width: 720, height: 720 });
    for (const size of [{ width: 7680, height: 4320 }, { width: 4320, height: 7680 }, { width: 4320, height: 4320 }]) {
      const pixels = backgroundSize(size);
      expect(Math.max(pixels.width, pixels.height)).toBe(4096);
      expect(pixels.width * pixels.height * 4).toBeLessThanOrEqual(64 * 1024 ** 2);
      expect(pixels.width / pixels.height).toBeCloseTo(size.width / size.height, 3);
    }
  });
});
