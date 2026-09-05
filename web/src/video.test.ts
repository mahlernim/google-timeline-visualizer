import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ALL_VIDEO_FORMATS,
  buildVideoFormat,
  createJourneyMp4,
  DEFAULT_VIDEO_FORMAT_KEY,
  isMp4,
  createFormatProbe,
  MAX_OUTPUT_BYTES,
  resolveVideoFormat,
  VIDEO_FRAME_RATES,
  VIDEO_FORMATS,
  videoFormatAtFrameRate,
  videoFormatByKey,
  videoFormatSupportKey,
} from './video';
import type { ResolvedVideoFormat, VideoFormat, VideoFrameRate } from './video';
import type { PreparedJourney } from './types';

// The encoder is the resource under test, so mediabunny is replaced by a recorder that
// reports whether the Output was released. drawFrame needs a real canvas, which node has not.
const encoder = vi.hoisted(() => ({
  start: vi.fn(async () => undefined),
  add: vi.fn(async () => undefined),
  finalize: vi.fn(async () => undefined),
  cancel: vi.fn(async () => undefined),
  buffer: null as ArrayBuffer | null,
  writer: null as WritableStreamDefaultWriter<{ data: Uint8Array; position: number }> | null,
}));

vi.mock('./renderer', () => ({ drawJourneyFrame: vi.fn(async () => undefined) }));

vi.mock('mediabunny', () => ({
  StreamTarget: class {
    constructor(stream: WritableStream<{ data: Uint8Array; position: number }>) { encoder.writer = stream.getWriter(); }
  },
  CanvasSource: class {
    add = encoder.add;
  },
  Mp4OutputFormat: class {},
  Quality: class {},
  Output: class {
    start = encoder.start;
    finalize = encoder.finalize;
    cancel = encoder.cancel;
    addVideoTrack = (): void => undefined;
    setMetadataTags = (): void => undefined;
  },
}));

// H.264 Annex A Table A-1, matching mediabunny's own AVC_LEVEL_TABLE.
const AVC_LEVELS: Record<string, { maxFs: number; maxMbps: number; maxBr: number }> = {
  '16': { maxFs: 1620, maxMbps: 20250, maxBr: 4_000_000 },
  '1e': { maxFs: 1620, maxMbps: 40500, maxBr: 10_000_000 },
  '1f': { maxFs: 3600, maxMbps: 108000, maxBr: 14_000_000 },
  '20': { maxFs: 5120, maxMbps: 216000, maxBr: 20_000_000 },
  '28': { maxFs: 8192, maxMbps: 245760, maxBr: 20_000_000 },
  '29': { maxFs: 8192, maxMbps: 245760, maxBr: 50_000_000 },
  '2a': { maxFs: 8704, maxMbps: 522240, maxBr: 50_000_000 },
  '32': { maxFs: 22080, maxMbps: 589824, maxBr: 50_000_000 },
  '33': { maxFs: 36864, maxMbps: 983040, maxBr: 50_000_000 },
  '34': { maxFs: 36864, maxMbps: 2073600, maxBr: 50_000_000 },
};

function macroblocks(format: VideoFormat): number {
  return Math.ceil(format.width / 16) * Math.ceil(format.height / 16);
}

function stubEncoder(isConfigSupported: unknown): void {
  vi.stubGlobal('VideoEncoder', { isConfigSupported });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('isMp4', () => {
  it('accepts an ISO base media file signature', () => {
    const bytes = new Uint8Array([0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109]);
    expect(isMp4(bytes.buffer)).toBe(true);
  });

  it('rejects short and unrelated output', () => {
    expect(isMp4(new ArrayBuffer(4))).toBe(false);
    expect(isMp4(new TextEncoder().encode('not-an-mp4-file').buffer)).toBe(false);
  });
});

describe('video format table', () => {
  it('lists every Android aspect and named short edge with the default first', () => {
    expect(VIDEO_FORMATS).toHaveLength(15);
    expect(new Set(VIDEO_FORMATS.map((format) => format.key)).size).toBe(15);
    expect(VIDEO_FORMATS[0].key).toBe(DEFAULT_VIDEO_FORMAT_KEY);
    expect(videoFormatByKey(DEFAULT_VIDEO_FORMAT_KEY)).not.toBeNull();
  });

  it('matches representative Android dimensions, defaults and bitrate calculation', () => {
    expect(buildVideoFormat('square', 480)).toMatchObject({ width: 480, height: 480, frameRate: 24, bitrate: 2_500_000 });
    expect(buildVideoFormat('portrait', 1080)).toMatchObject({ width: 1080, height: 1920, frameRate: 30, bitrate: 12_000_000 });
    expect(buildVideoFormat('landscape', 2160)).toMatchObject({ width: 3840, height: 2160, frameRate: 30 });
  });

  it('accepts custom Android-bounded resolution and frame rate values', () => {
    expect(buildVideoFormat('landscape', 1442, 119)).toMatchObject({
      width: 2562,
      height: 1442,
      frameRate: 119,
    });
    expect(() => buildVideoFormat('square', 478, 30)).toThrow(RangeError);
    expect(() => buildVideoFormat('square', 480, 121)).toThrow(RangeError);
  });

  it('uses even dimensions, which avc encoding requires', () => {
    VIDEO_FORMATS.forEach((format) => {
      expect(format.width % 2).toBe(0);
      expect(format.height % 2).toBe(0);
    });
  });

  it('offers only well-formed avc1 candidate strings', () => {
    VIDEO_FORMATS.forEach((format) => {
      expect(format.codecCandidates.length).toBeGreaterThan(0);
      format.codecCandidates.forEach((codec) => {
        expect(codec).toMatch(/^avc1\.[0-9a-f]{6}$/);
      });
    });
  });

  it('picks a first candidate whose level clears MaxFS, MaxMBPS and MaxBR', () => {
    VIDEO_FORMATS.forEach((format) => {
      const level = AVC_LEVELS[format.codecCandidates[0].slice(-2)];
      expect(level).toBeDefined();
      expect(level.maxFs).toBeGreaterThanOrEqual(macroblocks(format));
      expect(level.maxMbps).toBeGreaterThanOrEqual(macroblocks(format) * format.frameRate);
      expect(level.maxBr).toBeGreaterThanOrEqual(format.bitrate);
    });
  });

  it('counts the macroblocks that force the 2160 portrait format to level 5.1', () => {
    expect(macroblocks(buildVideoFormat('portrait', 2160))).toBe(32400);
  });

  it('builds Android rate variants for every size without changing defaults', () => {
    expect(VIDEO_FRAME_RATES).toEqual([15, 24, 30, 60, 120]);
    expect(ALL_VIDEO_FORMATS).toHaveLength(75);
    VIDEO_FORMATS.forEach((format) => {
      expect(videoFormatAtFrameRate(format, format.frameRate as VideoFrameRate)).toBe(format);
    });
  });

  it('scales bitrate with frame rate within the Android bounds', () => {
    expect(videoFormatAtFrameRate(VIDEO_FORMATS[0], 60).bitrate).toBe(6_250_000);
    const portrait = buildVideoFormat('portrait', 1080);
    expect(videoFormatAtFrameRate(portrait, 24).bitrate).toBe(9_600_000);
    expect(videoFormatAtFrameRate(portrait, 60).bitrate).toBe(24_000_000);
  });

  it('assigns an AVC level that clears every generated combination', () => {
    ALL_VIDEO_FORMATS.filter((format) => format.codecCandidates.length > 0).forEach((format) => {
      const level = AVC_LEVELS[format.codecCandidates[0].slice(-2)];
      expect(level).toBeDefined();
      expect(level.maxFs).toBeGreaterThanOrEqual(macroblocks(format));
      expect(level.maxMbps).toBeGreaterThanOrEqual(macroblocks(format) * format.frameRate);
      expect(level.maxBr).toBeGreaterThanOrEqual(format.bitrate);
    });
  });
});

describe('selected-format probes', () => {
  it('serializes configurations and caches success without probing unrelated sizes', async () => {
    let release = (): void => undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const called = vi.fn(async () => { await gate; return { supported: true }; });
    stubEncoder(called);
    const probe = createFormatProbe();
    const first = probe(buildVideoFormat('square', 480, 15));
    const duplicate = probe(buildVideoFormat('square', 480, 15));
    const second = probe(buildVideoFormat('landscape', 720, 30));
    expect(duplicate).toBe(first);
    await Promise.resolve();
    expect(called).toHaveBeenCalledTimes(1);
    release();
    await Promise.all([first, second]);
    expect(called).toHaveBeenCalledTimes(2);
    await probe(buildVideoFormat('square', 480, 15));
    expect(called).toHaveBeenCalledTimes(2);
  });

  it('caches unsupported configurations and tries candidates only for that configuration', async () => {
    const called = vi.fn(async (_config: { width: number; framerate: number }) => ({ supported: false }));
    stubEncoder(called);
    const probe = createFormatProbe();
    const format = buildVideoFormat('square', 480, 15);
    expect(await probe(format)).toBeNull();
    expect(await probe(format)).toBeNull();
    expect(called).toHaveBeenCalledTimes(format.codecCandidates.length);
    expect(called.mock.calls.every(([config]) => config.width === 480 && config.framerate === 15)).toBe(true);
  });

  it('handles missing and rejecting encoder APIs without affecting preview', async () => {
    vi.stubGlobal('VideoEncoder', undefined);
    expect(await createFormatProbe()(VIDEO_FORMATS[0])).toBeNull();
    stubEncoder(async () => { throw new TypeError('Unsupported codec'); });
    expect(await createFormatProbe()(VIDEO_FORMATS[0])).toBeNull();
  });
});

describe('resolveVideoFormat', () => {
  const supportedPortrait = buildVideoFormat('portrait', 1080);
  const unsupportedSquare = VIDEO_FORMATS[2];
  const support = new Map<string, string | null>([
    [videoFormatSupportKey(unsupportedSquare), null],
    [videoFormatSupportKey(supportedPortrait), 'avc1.420028'],
  ]);

  it('returns null for a combination the browser cannot encode', () => {
    expect(resolveVideoFormat(unsupportedSquare, support)).toBeNull();
  });

  it('carries the table values plus the probed codec', () => {
    expect(resolveVideoFormat(supportedPortrait, support)).toEqual({
      ...supportedPortrait,
      codec: 'avc1.420028',
    });
  });

  it('does not silently reuse support from a different frame rate', () => {
    expect(resolveVideoFormat(videoFormatAtFrameRate(supportedPortrait, 60), support)).toBeNull();
  });
});

describe('createJourneyMp4', () => {
  const format: ResolvedVideoFormat = { ...VIDEO_FORMATS[4], codec: 'avc1.420028' };
  const journey = {} as PreparedJourney;
  const canvas = { width: format.width, height: format.height } as HTMLCanvasElement;
  const options = {
    durationSeconds: 1,
    overlay: {
      title: 'Trip',
      periodLabel: 'March 2026',
      separator: ' 쨌 ',
      formatDistance: (kilometers: number) => `${Math.round(kilometers)} km`,
    },
    format,
  };

  function mp4Buffer(): ArrayBuffer {
    return new Uint8Array([0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109]).buffer;
  }

  beforeEach(() => {
    stubEncoder(async () => ({ supported: true }));
    encoder.start.mockClear().mockResolvedValue(undefined);
    encoder.add.mockClear().mockResolvedValue(undefined);
    encoder.finalize.mockClear().mockImplementation(async () => {
      if (encoder.buffer) await encoder.writer!.write({ data: new Uint8Array(encoder.buffer), position: 0 });
    });
    encoder.cancel.mockClear().mockResolvedValue(undefined);
    encoder.buffer = mp4Buffer();
  });

  it('rejects an oversized estimate before starting the encoder', async () => {
    await expect(createJourneyMp4(canvas, journey, { ...options, durationSeconds: 300 })).rejects.toThrow('outputTooLarge');
    expect(encoder.start).not.toHaveBeenCalled();
  });

  it('stops and releases the encoder when actual output crosses the limit', async () => {
    encoder.add.mockImplementationOnce(async () => {
      await encoder.writer!.write({ data: new Uint8Array(1), position: MAX_OUTPUT_BYTES });
    });
    await expect(createJourneyMp4(canvas, journey, options)).rejects.toThrow('outputTooLarge');
    expect(encoder.cancel).toHaveBeenCalledTimes(1);
    expect(encoder.finalize).not.toHaveBeenCalled();
  });

  it('releases a partially started encoder on startup failure', async () => {
    encoder.start.mockRejectedValueOnce(new Error('Allocation failed'));
    await expect(createJourneyMp4(canvas, journey, options)).rejects.toThrow('Allocation failed');
    expect(encoder.cancel).toHaveBeenCalledTimes(1);
  });

  it('releases the encoder when a frame fails to encode', async () => {
    // isConfigSupported answers from a static profile list, so a MediaCodec instance that
    // cannot be allocated only surfaces here. Without cleanup the Output keeps that encoder
    // and every buffered sample alive, and the next attempt allocates another one beside it.
    const failure = new Error('Encoding error');
    encoder.add.mockRejectedValueOnce(failure);

    await expect(createJourneyMp4(canvas, journey, options)).rejects.toBe(failure);
    expect(encoder.cancel).toHaveBeenCalledTimes(1);
    expect(encoder.finalize).not.toHaveBeenCalled();
  });

  it('releases the encoder when finalizing fails', async () => {
    const failure = new Error('Muxing error');
    encoder.finalize.mockRejectedValueOnce(failure);

    await expect(createJourneyMp4(canvas, journey, options)).rejects.toBe(failure);
    expect(encoder.cancel).toHaveBeenCalledTimes(1);
  });

  it('releases the encoder when the output is not a usable MP4', async () => {
    encoder.buffer = null;

    await expect(createJourneyMp4(canvas, journey, options)).rejects.toThrow('did not produce');
    expect(encoder.cancel).toHaveBeenCalledTimes(1);
  });

  it('releases the encoder when the export is cancelled', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(createJourneyMp4(canvas, journey, { ...options, signal: controller.signal }))
      .rejects.toMatchObject({ name: 'AbortError' });
    expect(encoder.cancel).toHaveBeenCalledTimes(1);
    expect(encoder.add).not.toHaveBeenCalled();
  });

  it('keeps the original failure when releasing the encoder also fails', async () => {
    const failure = new Error('Encoding error');
    encoder.add.mockRejectedValueOnce(failure);
    encoder.cancel.mockRejectedValueOnce(new Error('Output has already been canceled.'));

    await expect(createJourneyMp4(canvas, journey, options)).rejects.toBe(failure);
  });

  it('finalizes without cancelling when every frame encodes', async () => {
    const blob = await createJourneyMp4(canvas, journey, options);

    expect(blob.type).toBe('video/mp4');
    expect(encoder.add).toHaveBeenCalledTimes(options.durationSeconds * format.frameRate);
    expect(encoder.finalize).toHaveBeenCalledTimes(1);
    expect(encoder.cancel).not.toHaveBeenCalled();
  });

  // drawFrame only checks the aspect ratio, so this runtime check is the last thing standing
  // between a preview-sized canvas and an MP4 encoded from the wrong number of pixels. The
  // create handler restores the format size before its first await rather than relying on it.
  it('refuses a canvas still at the preview size', async () => {
    const previewSized = { width: 996, height: 560 } as HTMLCanvasElement;

    await expect(createJourneyMp4(previewSized, journey, options))
      .rejects.toThrow('The preview is not using the selected video format size.');
    expect(encoder.start).not.toHaveBeenCalled();
  });

  it('refuses a canvas left at another format size', async () => {
    const otherFormat = { width: 1080, height: 1080 } as HTMLCanvasElement;

    await expect(createJourneyMp4(otherFormat, journey, options))
      .rejects.toThrow('The preview is not using the selected video format size.');
    expect(encoder.start).not.toHaveBeenCalled();
  });
});


it('matches shared cross-platform preset dimensions', () => {
  const expected = JSON.parse(readFileSync(new URL('../../test-fixtures/platform-parity-expected.json', import.meta.url), 'utf8'));
  for (const [edge, aspects] of Object.entries(expected.videoDimensions)) {
    for (const [aspect, dimensions] of Object.entries(aspects as Record<string, number[]>)) {
      const format = buildVideoFormat(aspect as 'square' | 'portrait' | 'landscape', Number(edge), 30);
      expect([format.width, format.height]).toEqual(dimensions);
    }
  }
});
