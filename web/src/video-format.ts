import type { OverlayText } from './renderer';

export interface ExportOptions {
  durationSeconds: number;
  /** Frozen once for the whole export, so every frame carries the same text. */
  overlay: OverlayText;
  format: ResolvedVideoFormat;
  onProgress?: (fraction: number) => void;
  signal?: AbortSignal;
}

export type VideoAspectRatio = 'square' | 'portrait' | 'landscape';
export type VideoFormatKey = `${VideoAspectRatio}-${number}`;
export const VIDEO_SHORT_EDGES = [480, 720, 1080, 1440, 2160] as const;
export const VIDEO_FRAME_RATES = [15, 24, 30, 60, 120] as const;
export type VideoFrameRate = (typeof VIDEO_FRAME_RATES)[number];

export interface VideoFormat {
  key: VideoFormatKey;
  aspectRatio: VideoAspectRatio;
  shortEdge: number;
  width: number;
  height: number;
  frameRate: number;
  bitrate: number;
  /** Probed in order; the first supported string wins. */
  codecCandidates: readonly string[];
}

export interface ResolvedVideoFormat extends VideoFormat {
  codec: string;
}

export const DEFAULT_VIDEO_FORMAT_KEY: VideoFormatKey = 'square-480';

// H.264 macroblocks are 16 by 16, so PicSizeInMbs = ceil(width / 16) * ceil(height / 16).
// A codec string avc1.PPCCLL pins profile_idc, constraint flags and level_idc, and mediabunny
// passes fullCodecString straight to VideoEncoder.configure with no fallback and no level
// derivation. Its own string builder checks only MaxFS and MaxBR, never MaxMBPS, so the levels
// below are picked here against Annex A Table A-1 instead:
//   480x480      900 MB at 24 fps =  21,600 MB/s -> level 3.0 (2.2 allows only 20,250 MB/s)
//   720x720    2,025 MB at 24 fps =  48,600 MB/s -> level 3.1 (3.0 MaxFS is only 1,620)
//   1080x1080  4,624 MB at 24 fps = 110,976 MB/s -> level 3.2 (3.1 MaxFS is only 3,600)
//   1080x1920  8,160 MB at 30 fps = 244,800 MB/s -> level 4.0 (MaxMBPS 245,760, 0.4% spare)
//   1920x1080  8,160 MB at 30 fps = 244,800 MB/s -> level 4.0
// Level 4.1 shares MaxFS and MaxMBPS with 4.0, so the extra headroom candidate is 4.2 (2a).
// Baseline (42) comes first because it is the string already shipping and is implemented by
// every VideoToolbox encoder; High (64) is what mediabunny itself would generate.
function even(value: number): number {
  return Math.floor(value / 2) * 2;
}

function androidBitrate(width: number, height: number, frameRate: number, aspectRatio: VideoAspectRatio): number {
  const shortEdge = Math.min(width, height);
  const legacy = shortEdge === 480 ? (aspectRatio === 'square' ? 2_500_000 : 3_500_000)
    : shortEdge === 720 ? (aspectRatio === 'square' ? 5_000_000 : 7_000_000)
      : shortEdge === 1080 ? (aspectRatio === 'square' ? 8_000_000 : 12_000_000)
        : null;
  const legacyRate = aspectRatio === 'square' ? 24 : 30;
  const calculated = legacy === null
    ? Math.floor(width * height * frameRate * 19 / 100)
    : Math.floor(legacy * frameRate / legacyRate);
  return Math.max(1_500_000, Math.min(40_000_000, calculated));
}

export function buildVideoFormat(
  aspectRatio: VideoAspectRatio,
  shortEdge: number,
  frameRate = aspectRatio === 'square' ? 24 : 30,
): VideoFormat {
  if (!Number.isInteger(shortEdge) || shortEdge < 480 || shortEdge > 2160) {
    throw new RangeError('Video short edge must be a whole number from 480 through 2160.');
  }
  if (!Number.isInteger(frameRate) || frameRate < 15 || frameRate > 120) {
    throw new RangeError('Frame rate must be a whole number from 15 through 120.');
  }
  const longEdge = even(shortEdge * 16 / 9);
  const [width, height] = aspectRatio === 'square' ? [shortEdge, shortEdge]
    : aspectRatio === 'portrait' ? [shortEdge, longEdge] : [longEdge, shortEdge];
  const bitrate = androidBitrate(width, height, frameRate, aspectRatio);
  return {
    key: `${aspectRatio}-${shortEdge}`,
    aspectRatio,
    shortEdge,
    width,
    height,
    frameRate,
    bitrate,
    codecCandidates: codecCandidates(width, height, frameRate, bitrate),
  };
}

/** Maps every size and frame-rate combination to the codec string that works, or null. */
export type VideoFormatSupport = ReadonlyMap<string, string | null>;

export function hasVideoEncoder(): boolean {
  return typeof globalThis.VideoEncoder !== 'undefined';
}

export function videoFormatByKey(key: string): VideoFormat | null {
  return VIDEO_FORMATS.find((format) => format.key === key) ?? null;
}

const AVC_LEVELS = [
  { code: '1f', maxFs: 3_600, maxMbps: 108_000, maxBitrate: 14_000_000 },
  { code: '20', maxFs: 5_120, maxMbps: 216_000, maxBitrate: 20_000_000 },
  { code: '28', maxFs: 8_192, maxMbps: 245_760, maxBitrate: 20_000_000 },
  { code: '2a', maxFs: 8_704, maxMbps: 522_240, maxBitrate: 50_000_000 },
  { code: '32', maxFs: 22_080, maxMbps: 589_824, maxBitrate: 50_000_000 },
  { code: '33', maxFs: 36_864, maxMbps: 983_040, maxBitrate: 50_000_000 },
  { code: '34', maxFs: 36_864, maxMbps: 2_073_600, maxBitrate: 50_000_000 },
] as const;

function codecCandidates(width: number, height: number, frameRate: number, bitrate: number): string[] {
  const macroblocks = Math.ceil(width / 16) * Math.ceil(height / 16);
  const levelIndex = AVC_LEVELS.findIndex((level) => level.maxFs >= macroblocks
    && level.maxMbps >= macroblocks * frameRate
    && level.maxBitrate >= bitrate);
  if (levelIndex < 0) return [];
  const levels = AVC_LEVELS.slice(levelIndex, levelIndex + 2);
  return levels.flatMap((level) => [`avc1.4200${level.code}`, `avc1.6400${level.code}`]);
}

export const VIDEO_FORMATS: readonly VideoFormat[] = (['square', 'portrait', 'landscape'] as const)
  .flatMap((aspectRatio) => VIDEO_SHORT_EDGES.map((shortEdge) => buildVideoFormat(aspectRatio, shortEdge)));

/** Builds a concrete format while preserving the proven legacy configuration exactly. */
export function videoFormatAtFrameRate(
  format: VideoFormat,
  frameRate: number,
): VideoFormat {
  if (frameRate === format.frameRate) return format;
  return buildVideoFormat(format.aspectRatio, format.shortEdge, frameRate);
}

export function videoFormatSupportKey(format: VideoFormat): string {
  return `${format.key}@${format.frameRate}`;
}

export const ALL_VIDEO_FORMATS: readonly VideoFormat[] = VIDEO_FORMATS.flatMap((format) =>
  VIDEO_FRAME_RATES.map((frameRate) => videoFormatAtFrameRate(format, frameRate)));

async function probeCodec(format: VideoFormat, codec: string): Promise<boolean> {
  try {
    const result = await VideoEncoder.isConfigSupported({
      codec,
      width: format.width,
      height: format.height,
      bitrate: format.bitrate,
      framerate: format.frameRate,
      hardwareAcceleration: 'no-preference',
    });
    return result.supported === true;
  } catch {
    return false;
  }
}

async function resolveCodecString(format: VideoFormat): Promise<string | null> {
  for (const codec of format.codecCandidates) {
    if (await probeCodec(format, codec)) return codec;
  }
  return null;
}

export async function probeVideoFormat(format: VideoFormat): Promise<string | null> {
  if (!hasVideoEncoder()) return null;
  return resolveCodecString(format);
}

export function resolveVideoFormat(
  format: VideoFormat,
  support: VideoFormatSupport,
): ResolvedVideoFormat | null {
  const codec = support.get(videoFormatSupportKey(format)) ?? null;
  return codec === null ? null : { ...format, codec };
}

export function isMp4(buffer: ArrayBuffer): boolean {
  if (buffer.byteLength < 12) return false;
  const bytes = new Uint8Array(buffer, 4, 8);
  return String.fromCharCode(...bytes).startsWith('ftyp');
}


/** The UI owns one queue, so exact-format probes never run concurrently. */
export function createFormatProbe(): (format: VideoFormat) => Promise<string | null> {
  const cache = new Map<string, Promise<string | null>>();
  let tail: Promise<unknown> = Promise.resolve();
  return (format) => {
    const key = videoFormatSupportKey(format);
    const cached = cache.get(key);
    if (cached) return cached;
    const result = tail.then(() => probeVideoFormat(format));
    tail = result.catch(() => undefined);
    cache.set(key, result);
    return result;
  };
}

export const MAX_OUTPUT_BYTES = 64 * 1024 * 1024;
export function estimatedOutputBytes(format: VideoFormat, durationSeconds: number): number {
  // Allow bitrate variability and metadata before committing encoding resources.
  return Math.ceil(format.bitrate * durationSeconds / 8 * 1.25) + 1024 * 1024;
}
