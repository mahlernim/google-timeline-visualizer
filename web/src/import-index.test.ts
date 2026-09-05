import { describe, expect, it } from 'vitest';
import { TimelineIndexBuilder, MAX_INDEX_BLOCKS } from './import-index';

describe('bounded date index', () => {
  it('compacts contiguous blocks without losing dates, byte coverage, or covering intervals', () => {
    const builder = new TimelineIndexBuilder();
    const count = MAX_INDEX_BLOCKS * 4;
    for (let i = 0; i < count; i += 1) {
      builder.add({ start: i * 200_000, end: (i + 1) * 200_000 - 1, group: 0,
        firstDate: i === count - 1 ? '2000-01-01' : '2026-01-01', lastDate: '2026-12-31',
        intervalStart: i === 0 ? 0 : Infinity, intervalEnd: i === 0 ? 100 : -Infinity });
    }
    const blocks = builder.finish()!;
    expect(blocks.length).toBeLessThanOrEqual(MAX_INDEX_BLOCKS);
    expect(blocks[0]).toMatchObject({ start: 0, intervalStart: 0, intervalEnd: 100 });
    expect(blocks.at(-1)).toMatchObject({ end: count * 200_000 - 1, firstDate: '2000-01-01' });
    for (let i = 1; i < blocks.length; i += 1) expect(blocks[i].start).toBe(blocks[i - 1].end + 1);
  });
  it('falls back to streaming when too many disconnected arrays cannot be compacted', () => {
    const builder = new TimelineIndexBuilder();
    for (let i = 0; i <= MAX_INDEX_BLOCKS; i += 1) builder.add({
      start: i * 100, end: i * 100 + 50, group: i, firstDate: '2026-01-01', lastDate: '2026-01-02',
      intervalStart: Infinity, intervalEnd: -Infinity,
    });
    expect(builder.finish()).toBeUndefined();
    expect(builder.blocks).toHaveLength(0);
  });
});
