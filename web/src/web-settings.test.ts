import { describe, expect, it } from 'vitest';
import { validateWebSettings } from './web-settings';
import { buildVideoFormat, estimatedOutputBytes, MAX_OUTPUT_BYTES } from './video-format';

describe('web preset validation', () => {
  it('supports the requested intermediate sizes and rejects values outside the menus', () => {
    for (const size of [480, 720, 1024, 1920]) for (const fps of [15, 30, 60]) {
      for (const seconds of [15, 20, 30, 45, 60]) expect(validateWebSettings(size, fps, seconds)).toBe(true);
      for (const aspect of ['square', 'landscape', 'portrait'] as const) {
        const format = buildVideoFormat(aspect, size, fps);
        expect(Math.min(format.width, format.height)).toBe(size);
        expect(format.width % 2).toBe(0);
        expect(format.height % 2).toBe(0);
      }
    }
    expect(validateWebSettings(1080, 30, 15)).toBe(false);
    expect(validateWebSettings(480, 120, 15)).toBe(false);
    expect(validateWebSettings(480, 15, 300)).toBe(false);
    expect(validateWebSettings(NaN, 15, 15)).toBe(false);
  });
  it('keeps the output limit active for expensive presets', () => {
    expect(estimatedOutputBytes(buildVideoFormat('portrait', 1920, 60), 60)).toBeGreaterThan(MAX_OUTPUT_BYTES);
    expect(estimatedOutputBytes(buildVideoFormat('square', 480, 15), 15)).toBeLessThan(MAX_OUTPUT_BYTES);
  });
});
