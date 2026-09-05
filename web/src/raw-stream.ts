import { isShortImpossibleRawSpike, rawSignalsOverlap } from './timeline';
import type { RawSignalPoint } from './timeline';
import { ImportError, MAX_SELECTED_POINTS } from './import-types';

/** Canonical raw processing in timestamp order, retaining only pending boundary state. */
export class RawSignalStream {
  private group = new Map<string, RawSignalPoint>();
  private time = -Infinity;
  private before: RawSignalPoint | null = null;
  private pending: RawSignalPoint | null = null;
  private stable: RawSignalPoint | null = null;
  constructor(private accuracy: number | null, private emit: (point: RawSignalPoint) => void,
    private unique: (point: RawSignalPoint) => void = () => undefined) {}
  push(point: RawSignalPoint): void {
    const time = point.instant.getTime();
    if (time !== this.time) { this.flushGroup(); this.time = time; }
    const key = point.latitude + ':' + point.longitude;
    const previous = this.group.get(key);
    if (!previous || point.accuracyMeters < previous.accuracyMeters) this.group.set(key, point);
    if (this.group.size > MAX_SELECTED_POINTS) throw new ImportError('rangeTooLarge');
  }
  private flushGroup(): void {
    for (const point of this.group.values()) {
      this.unique(point);
      if (this.accuracy !== null && point.accuracyMeters > this.accuracy) continue;
      if (!this.before) { this.before = point; this.stabilize(point); }
      else {
        if (this.pending && !isShortImpossibleRawSpike(this.before, this.pending, point)) {
          this.before = this.pending;
          this.stabilize(this.pending);
        }
        this.pending = point;
      }
    }
    this.group.clear();
  }
  private stabilize(point: RawSignalPoint): void {
    if (!this.stable) { this.stable = point; return; }
    if (rawSignalsOverlap(this.stable, point)) {
      if (point.accuracyMeters < this.stable.accuracyMeters) this.stable = point;
    } else { this.emit(this.stable); this.stable = point; }
  }
  finish(): void {
    this.flushGroup();
    if (this.pending) this.stabilize(this.pending);
    if (this.stable) this.emit(this.stable);
    this.before = this.pending = this.stable = null;
  }
}

export interface IndexedRawPoint { point: RawSignalPoint; index: number }
export const compareRaw = (a: IndexedRawPoint, b: IndexedRawPoint): number =>
  a.point.instant.getTime() - b.point.instant.getTime() || a.index - b.index;

/** Keep the earliest batch in a max heap, even when the source file is unordered. */
export class RawBatch {
  private heap: IndexedRawPoint[] = [];
  constructor(private capacity = 10_000) {}
  push(value: IndexedRawPoint): void {
    if (this.heap.length < this.capacity) {
      let index = this.heap.length;
      this.heap.push(value);
      while (index > 0) {
        const parent = (index - 1) >> 1;
        if (compareRaw(this.heap[parent], value) >= 0) break;
        this.heap[index] = this.heap[parent]; index = parent;
      }
      this.heap[index] = value;
    } else if (compareRaw(value, this.heap[0]) < 0) {
      let index = 0;
      while (index * 2 + 1 < this.heap.length) {
        let child = index * 2 + 1;
        if (child + 1 < this.heap.length && compareRaw(this.heap[child + 1], this.heap[child]) > 0) child += 1;
        if (compareRaw(value, this.heap[child]) >= 0) break;
        this.heap[index] = this.heap[child]; index = child;
      }
      this.heap[index] = value;
    }
  }
  sorted(): IndexedRawPoint[] { return this.heap.sort(compareRaw); }
}
