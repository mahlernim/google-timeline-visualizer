import type { TimelineBlock } from './import-types';

export const MAX_INDEX_BLOCKS = 2048;
const INITIAL_BLOCK_BYTES = 256 * 1024;

/** Coarse byte ranges retain dates, never coordinates. Compact before the index can grow. */
export class TimelineIndexBuilder {
  blocks: TimelineBlock[] = [];
  private targetBytes = INITIAL_BLOCK_BYTES;
  private available = true;

  add(block: TimelineBlock): void {
    if (!this.available) return;
    const previous = this.blocks.at(-1);
    if (previous && previous.group === block.group && block.end - previous.start <= this.targetBytes) {
      this.merge(previous, block);
      return;
    }
    if (this.blocks.length >= MAX_INDEX_BLOCKS) {
      const compact: TimelineBlock[] = [];
      for (const entry of this.blocks) {
        const last = compact.at(-1);
        if (last && last.group === entry.group && entry.end - last.start <= this.targetBytes * 2) {
          this.merge(last, entry);
        } else compact.push(entry);
      }
      this.targetBytes *= 2;
      this.blocks = compact;
      // Pathological repeated top-level arrays can prevent safe contiguous merging.
      // Fall back to streaming the file instead of increasing retained metadata.
      if (compact.length >= MAX_INDEX_BLOCKS) { this.available = false; this.blocks = []; return; }
    }
    this.blocks.push(block);
  }

  finish(): TimelineBlock[] | undefined { return this.available ? this.blocks : undefined; }

  private merge(target: TimelineBlock, source: TimelineBlock): void {
    target.end = source.end;
    if (source.firstDate < target.firstDate) target.firstDate = source.firstDate;
    if (source.lastDate > target.lastDate) target.lastDate = source.lastDate;
    target.intervalStart = Math.min(target.intervalStart, source.intervalStart);
    target.intervalEnd = Math.max(target.intervalEnd, source.intervalEnd);
  }
}
