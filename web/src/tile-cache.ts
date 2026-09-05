export const TILE_CACHE_BYTES = 32 * 1024 * 1024;
export interface DecodedTile { width: number; height: number; close?: () => void }
interface Entry<T> { image: T; bytes: number }

/** Decoded images have explicit ownership. Eviction and disposal release their backing store. */
export class TileCache<T extends DecodedTile> {
  private entries = new Map<string, Entry<T>>();
  private pending = new Map<string, Promise<T>>();
  private controller = new AbortController();
  private bytes = 0;
  private active = 0;
  private waiters: Array<() => void> = [];
  constructor(
    private readonly load: (key: string, signal: AbortSignal) => Promise<T>,
    readonly budget = TILE_CACHE_BYTES,
  ) {}
  get residentBytes(): number { return this.bytes; }
  get size(): number { return this.entries.size; }
  async get(key: string): Promise<T> {
    this.controller.signal.throwIfAborted();
    const cached = this.entries.get(key);
    if (cached) {
      this.entries.delete(key);
      this.entries.set(key, cached);
      return cached.image;
    }
    const pending = this.pending.get(key);
    if (pending) return pending;
    const job = this.fetch(key);
    this.pending.set(key, job);
    try { return await job; } finally { this.pending.delete(key); }
  }
  private async fetch(key: string): Promise<T> {
    if (this.active >= 2) await new Promise<void>((resolve) => this.waiters.push(resolve));
    else this.active += 1;
    this.controller.signal.throwIfAborted();
    try {
      const image = await this.load(key, this.controller.signal);
      if (this.controller.signal.aborted) { image.close?.(); this.controller.signal.throwIfAborted(); }
      const bytes = image.width * image.height * 4;
      if (bytes > this.budget) { image.close?.(); throw new Error('Map tile exceeds the image budget.'); }
      while (this.bytes + bytes > this.budget) {
        const oldest = this.entries.entries().next().value;
        if (!oldest) break;
        this.entries.delete(oldest[0]);
        this.bytes -= oldest[1].bytes;
        oldest[1].image.close?.();
      }
      this.entries.set(key, { image, bytes });
      this.bytes += bytes;
      return image;
    } finally {
      const resume = this.waiters.shift();
      if (resume) resume(); else this.active -= 1;
    }
  }
  dispose(): void {
    this.controller.abort();
    for (const { image } of this.entries.values()) image.close?.();
    this.entries.clear();
    this.bytes = 0;
    for (const resume of this.waiters.splice(0)) resume();
  }
}
