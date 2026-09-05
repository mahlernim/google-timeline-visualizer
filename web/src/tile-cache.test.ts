import { describe, expect, it, vi } from 'vitest';
import { TileCache } from './tile-cache';

describe('decoded tile cache', () => {
  it('reuses tiles, evicts least recently used images, and closes all resources', async () => {
    const load = vi.fn(async () => ({ width: 256, height: 256, close: vi.fn() }));
    const cache = new TileCache(load, 2 * 256 * 256 * 4);
    const first = await cache.get('one');
    const second = await cache.get('two');
    await cache.get('one');
    await cache.get('three');
    expect(load).toHaveBeenCalledTimes(3);
    expect(second.close).toHaveBeenCalledOnce();
    expect(first.close).not.toHaveBeenCalled();
    expect(cache.residentBytes).toBe(2 * 256 * 256 * 4);
    cache.dispose();
    expect(first.close).toHaveBeenCalledOnce();
    expect(cache.residentBytes).toBe(0);
    await expect(cache.get('four')).rejects.toMatchObject({ name: 'AbortError' });
  });
  it('shares in-flight requests and never downloads more than two tiles concurrently', async () => {
    let active = 0;
    let peak = 0;
    const cache = new TileCache(async () => {
      active += 1; peak = Math.max(peak, active);
      await new Promise((resolve) => setTimeout(resolve, 1));
      active -= 1;
      return { width: 256, height: 256, close() {} };
    });
    const images = await Promise.all(['a', 'a', 'b', 'c', 'd', 'e'].map((key) => cache.get(key)));
    expect(images[0]).toBe(images[1]);
    expect(peak).toBe(2);
    cache.dispose();
  });
  it('closes late images when cancelled during decoding', async () => {
    let release!: (image: { width: number; height: number; close: () => void }) => void;
    const cache = new TileCache<{ width: number; height: number; close: () => void }>(() => new Promise((resolve) => { release = resolve; }));
    const pending = cache.get('a');
    cache.dispose();
    const close = vi.fn();
    release({ width: 256, height: 256, close });
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(close).toHaveBeenCalledOnce();
  });
});
