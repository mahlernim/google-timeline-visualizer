import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createJourneyMp4,
  DEFAULT_VIDEO_FORMAT_KEY,
  isMp4,
  probeVideoFormats,
  resolveVideoFormat,
  VIDEO_FORMATS,
  videoFormatByKey,
} from './video';
import type { ResolvedVideoFormat, VideoFormat, VideoFormatKey } from './video';
import type { PreparedJourney } from './types';

// The encoder is the resource under test, so mediabunny is replaced by a recorder that
// reports whether the Output was released. drawFrame needs a real canvas, which node has not.
const encoder = vi.hoisted(() => ({
  start: vi.fn(async () => undefined),
  add: vi.fn(async () => undefined),
  finalize: vi.fn(async () => undefined),
  cancel: vi.fn(async () => undefined),
  buffer: null as ArrayBuffer | null,
}));

vi.mock('./renderer', () => ({ drawFrame: vi.fn() }));

vi.mock('mediabunny', () => ({
  BufferTarget: class {
    get buffer(): ArrayBuffer | null {
      return encoder.buffer;
    }
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
  it('lists the five Android formats with standard first and unique keys', () => {
    expect(VIDEO_FORMATS).toHaveLength(5);
    expect(new Set(VIDEO_FORMATS.map((format) => format.key)).size).toBe(5);
    expect(VIDEO_FORMATS[0].key).toBe(DEFAULT_VIDEO_FORMAT_KEY);
    expect(videoFormatByKey(DEFAULT_VIDEO_FORMAT_KEY)).not.toBeNull();
  });

  it('matches the Android CameraSettings dimensions, frame rates and bitrates', () => {
    expect(VIDEO_FORMATS.map((format) => [
      format.width,
      format.height,
      format.frameRate,
      format.bitrate,
    ])).toEqual([
      [480, 480, 24, 2_500_000],
      [720, 720, 24, 5_000_000],
      [1080, 1080, 24, 8_000_000],
      [1080, 1920, 30, 12_000_000],
      [1920, 1080, 30, 12_000_000],
    ]);
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

  it('counts the macroblocks that force the 1080 formats above level 3.1', () => {
    expect(VIDEO_FORMATS.map(macroblocks)).toEqual([900, 2025, 4624, 8160, 8160]);
  });
});

describe('probeVideoFormats', () => {
  it('takes the first candidate and probes each format once when everything is supported', async () => {
    const isConfigSupported = vi.fn(async () => ({ supported: true }));
    stubEncoder(isConfigSupported);

    const support = await probeVideoFormats();

    expect(isConfigSupported).toHaveBeenCalledTimes(5);
    VIDEO_FORMATS.forEach((format) => {
      expect(support.get(format.key)).toBe(format.codecCandidates[0]);
    });
  });

  it('falls through to the next candidate when the first is rejected', async () => {
    stubEncoder(async (config: { codec: string }) => ({
      supported: config.codec !== VIDEO_FORMATS[2].codecCandidates[0],
    }));

    const support = await probeVideoFormats();

    expect(support.get('ultra')).toBe(VIDEO_FORMATS[2].codecCandidates[1]);
    expect(support.get('standard')).toBe(VIDEO_FORMATS[0].codecCandidates[0]);
  });

  it('reports null for a format whose candidates all fail, leaving others intact', async () => {
    const failing = new Set<string>(VIDEO_FORMATS[4].codecCandidates);
    stubEncoder(async (config: { codec: string; width: number }) => ({
      supported: !(config.width === 1920 && failing.has(config.codec)),
    }));

    const support = await probeVideoFormats();

    expect(support.get('landscape')).toBeNull();
    expect(support.get('portrait')).toBe(VIDEO_FORMATS[3].codecCandidates[0]);
  });

  it('treats a throwing isConfigSupported as unsupported without rejecting', async () => {
    stubEncoder(() => {
      throw new TypeError('malformed codec string');
    });

    const support = await probeVideoFormats();

    expect([...support.values()]).toEqual([null, null, null, null, null]);
  });

  it('treats an undefined supported flag as unsupported', async () => {
    stubEncoder(async () => ({ supported: undefined }));

    const support = await probeVideoFormats();

    expect([...support.values()]).toEqual([null, null, null, null, null]);
  });

  it('reports every format as unavailable without touching a missing VideoEncoder', async () => {
    vi.stubGlobal('VideoEncoder', undefined);

    const support = await probeVideoFormats();

    expect(support.size).toBe(5);
    expect([...support.values()]).toEqual([null, null, null, null, null]);
  });

  it('probes the five formats in parallel', async () => {
    const started: number[] = [];
    let release = (): void => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    stubEncoder(async (config: { width: number }) => {
      started.push(config.width);
      await gate;
      return { supported: true };
    });

    const pending = probeVideoFormats();
    await Promise.resolve();
    expect(started).toHaveLength(5);

    release();
    await pending;
  });
});

describe('resolveVideoFormat', () => {
  const support = new Map<VideoFormatKey, string | null>([
    ['standard', 'avc1.42001f'],
    ['high', 'avc1.42001f'],
    ['ultra', null],
    ['portrait', 'avc1.420028'],
    ['landscape', 'avc1.420028'],
  ]);

  it('returns null for an unknown key', () => {
    expect(resolveVideoFormat('cinema', support)).toBeNull();
  });

  it('returns null for a known key the browser cannot encode', () => {
    expect(resolveVideoFormat('ultra', support)).toBeNull();
  });

  it('carries the table values plus the probed codec', () => {
    expect(resolveVideoFormat('portrait', support)).toEqual({
      ...VIDEO_FORMATS[3],
      codec: 'avc1.420028',
    });
  });
});

describe('createJourneyMp4', () => {
  const format: ResolvedVideoFormat = { ...VIDEO_FORMATS[4], codec: 'avc1.420028' };
  const journey = {} as PreparedJourney;
  const canvas = { width: format.width, height: format.height } as HTMLCanvasElement;
  const options = {
    durationSeconds: 1,
    overlay: { title: 'Trip', periodLabel: 'March 2026' },
    format,
  };

  function mp4Buffer(): ArrayBuffer {
    return new Uint8Array([0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109]).buffer;
  }

  beforeEach(() => {
    stubEncoder(async () => ({ supported: true }));
    encoder.start.mockClear().mockResolvedValue(undefined);
    encoder.add.mockClear().mockResolvedValue(undefined);
    encoder.finalize.mockClear().mockResolvedValue(undefined);
    encoder.cancel.mockClear().mockResolvedValue(undefined);
    encoder.buffer = mp4Buffer();
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
