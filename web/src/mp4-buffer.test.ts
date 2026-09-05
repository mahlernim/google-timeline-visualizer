import { describe, expect, it } from 'vitest';
import { Mp4Buffer } from './mp4-buffer';
import { MAX_OUTPUT_BYTES } from './video-format';

describe('bounded MP4 storage', () => {
  it('preserves cross-block writes and late metadata patches in the downloadable Blob', async () => {
    const buffer = new Mp4Buffer();
    const original = new Uint8Array(600_000).fill(7);
    original.set(new TextEncoder().encode('ftyp'), 4);
    buffer.write(original, 0);
    buffer.write(new Uint8Array([1, 2, 3, 4]), 512 * 1024 - 2);
    const blob = buffer.blob();
    buffer.dispose();
    expect(blob.size).toBe(original.length);
    expect(blob.type).toBe('video/mp4');
    const actual = new Uint8Array(await blob.arrayBuffer());
    expect([...actual.slice(512 * 1024 - 3, 512 * 1024 + 3)]).toEqual([7, 1, 2, 3, 4, 7]);
    expect(actual.at(-1)).toBe(7);
  });
  it('rejects overflow before allocating storage and permits a valid retry', () => {
    const buffer = new Mp4Buffer();
    expect(() => buffer.write(new Uint8Array(1), MAX_OUTPUT_BYTES)).toThrow('outputTooLarge');
    expect(() => buffer.blob()).toThrow('Invalid MP4');
    buffer.write(new TextEncoder().encode('0000ftyp0000'), 0);
    expect(buffer.blob().size).toBe(12);
  });
});
