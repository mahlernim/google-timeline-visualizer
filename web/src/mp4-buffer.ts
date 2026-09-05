import { MAX_OUTPUT_BYTES } from './video-format';

const BLOCK_BYTES = 512 * 1024;

/** Random-access blocks allow the MP4 header to be finalized without duplicating a growing buffer. */
export class Mp4Buffer {
  private blocks: Uint8Array<ArrayBuffer>[] = [];
  private length = 0;
  write(data: Uint8Array, position: number): void {
    const end = position + data.byteLength;
    if (!Number.isSafeInteger(position) || position < 0 || end > MAX_OUTPUT_BYTES) throw new Error('outputTooLarge');
    while (this.blocks.length * BLOCK_BYTES < end) this.blocks.push(new Uint8Array(BLOCK_BYTES));
    let offset = 0;
    while (offset < data.length) {
      const destination = position + offset;
      const blockOffset = destination % BLOCK_BYTES;
      const count = Math.min(BLOCK_BYTES - blockOffset, data.length - offset);
      this.blocks[Math.floor(destination / BLOCK_BYTES)].set(data.subarray(offset, offset + count), blockOffset);
      offset += count;
    }
    this.length = Math.max(this.length, end);
  }
  blob(): Blob {
    if (this.length < 12) throw new Error('Invalid MP4 output');
    const signature = String.fromCharCode(...this.blocks[0].subarray(4, 8));
    if (signature !== 'ftyp') throw new Error('Invalid MP4 output');
    return new Blob(this.blocks.map((block, index) => block.subarray(0, Math.min(BLOCK_BYTES, this.length - index * BLOCK_BYTES))), { type: 'video/mp4' });
  }
  dispose(): void { this.blocks = []; this.length = 0; }
}
