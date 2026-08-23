import { BufferTarget, CanvasSource, Mp4OutputFormat, Output, Quality } from 'mediabunny';
import { frameAtElapsedSeconds, OUTRO_SECONDS } from './animation';
import { AppError } from './errors';
import { drawFrame } from './renderer';
import type { OverlayText } from './renderer';
import type { PreparedJourney } from './types';

export interface ExportOptions {
  durationSeconds: number;
  /** Frozen once for the whole export, so every frame carries the same text. */
  overlay: OverlayText;
  format: ResolvedVideoFormat;
  onProgress?: (fraction: number) => void;
  signal?: AbortSignal;
}

export type VideoFormatKey = 'standard' | 'high' | 'ultra' | 'portrait' | 'landscape';

export interface VideoFormat {
  key: VideoFormatKey;
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

export const DEFAULT_VIDEO_FORMAT_KEY: VideoFormatKey = 'standard';

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
export const VIDEO_FORMATS: readonly VideoFormat[] = [
  {
    key: 'standard',
    width: 480,
    height: 480,
    frameRate: 24,
    bitrate: 2_500_000,
    codecCandidates: ['avc1.42001f', 'avc1.64001f'],
  },
  {
    key: 'high',
    width: 720,
    height: 720,
    frameRate: 24,
    bitrate: 5_000_000,
    codecCandidates: ['avc1.42001f', 'avc1.64001f'],
  },
  {
    key: 'ultra',
    width: 1080,
    height: 1080,
    frameRate: 24,
    bitrate: 8_000_000,
    codecCandidates: ['avc1.420020', 'avc1.640020', 'avc1.420028'],
  },
  {
    key: 'portrait',
    width: 1080,
    height: 1920,
    frameRate: 30,
    bitrate: 12_000_000,
    codecCandidates: ['avc1.420028', 'avc1.640028', 'avc1.42002a'],
  },
  {
    key: 'landscape',
    width: 1920,
    height: 1080,
    frameRate: 30,
    bitrate: 12_000_000,
    codecCandidates: ['avc1.420028', 'avc1.640028', 'avc1.42002a'],
  },
];

/** Maps every format key to the codec string that works, or null when the browser cannot encode it. */
export type VideoFormatSupport = ReadonlyMap<VideoFormatKey, string | null>;

export function hasVideoEncoder(): boolean {
  return typeof globalThis.VideoEncoder !== 'undefined';
}

export function videoFormatByKey(key: string): VideoFormat | null {
  return VIDEO_FORMATS.find((format) => format.key === key) ?? null;
}

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

/**
 * Probes every format once. Formats run in parallel, candidates within a format run in order.
 * Never rejects: a missing VideoEncoder or a throwing isConfigSupported both resolve to null.
 * A browser that fails even 480 by 480 Baseline cannot encode H.264 at all, which the
 * compatibility status already reports, so no format is ever silently swapped for another.
 */
export async function probeVideoFormats(): Promise<VideoFormatSupport> {
  if (!hasVideoEncoder()) {
    return new Map(VIDEO_FORMATS.map((format) => [format.key, null]));
  }
  const entries = await Promise.all(
    VIDEO_FORMATS.map(async (format): Promise<[VideoFormatKey, string | null]> => [
      format.key,
      await resolveCodecString(format),
    ]),
  );
  return new Map(entries);
}

export function resolveVideoFormat(
  key: string,
  support: VideoFormatSupport,
): ResolvedVideoFormat | null {
  const format = videoFormatByKey(key);
  if (!format) return null;
  const codec = support.get(format.key) ?? null;
  return codec === null ? null : { ...format, codec };
}

export function isMp4(buffer: ArrayBuffer): boolean {
  if (buffer.byteLength < 12) return false;
  const bytes = new Uint8Array(buffer, 4, 8);
  return String.fromCharCode(...bytes).startsWith('ftyp');
}

export async function createJourneyMp4(
  canvas: HTMLCanvasElement,
  journey: PreparedJourney,
  options: ExportOptions,
): Promise<Blob> {
  if (!hasVideoEncoder()) {
    throw new AppError('errorNoEncoder', 'This browser cannot create MP4 video. Use Safari 16.4 or newer.');
  }

  const { width, height, frameRate: fps, bitrate, codec } = options.format;
  if (canvas.width !== width || canvas.height !== height) {
    throw new AppError('errorCanvasSize', 'The preview is not using the selected video format size.');
  }

  const frameDuration = 1 / fps;
  const frameCount = Math.max(1, Math.round(options.durationSeconds * fps));
  const outroFrameCount = Math.min(Math.round(OUTRO_SECONDS * fps), frameCount - 1);
  const journeyFrameCount = frameCount - outroFrameCount;
  const target = new BufferTarget();
  const output = new Output({
    format: new Mp4OutputFormat({ fastStart: 'in-memory' }),
    target,
  });
  const source = new CanvasSource(canvas, {
    codec: 'avc',
    fullCodecString: codec,
    quality: new Quality({ bitrate }),
    keyFrameInterval: 1,
    hardwareAcceleration: 'no-preference',
  });
  output.addVideoTrack(source, { frameRate: fps });
  output.setMetadataTags({ title: options.overlay.title });
  await output.start();

  // Every failure after start() has to reach output.cancel(): only cancel force-closes
  // the VideoEncoder and drops the samples that fastStart 'in-memory' keeps buffered.
  // A rejected source.add - an encoder that MediaCodec could not allocate reports its
  // error asynchronously, long after isConfigSupported said yes - would otherwise leave
  // the encoder open, and every retry would stack another one on top.
  try {
    for (let frame = 0; frame < frameCount; frame += 1) {
      if (options.signal?.aborted) {
        throw new DOMException('Video creation was cancelled.', 'AbortError');
      }
      const animationFrame = frame < journeyFrameCount
        ? {
          journeyProgress: journeyFrameCount === 1 ? 1 : frame / (journeyFrameCount - 1),
          outroProgress: 0,
        }
        : frameAtElapsedSeconds(
          options.durationSeconds - outroFrameCount / fps + (frame - journeyFrameCount) / fps,
          options.durationSeconds,
        );
      drawFrame(canvas, journey, animationFrame, options.overlay);
      await source.add(frame * frameDuration, frameDuration, { keyFrame: frame % fps === 0 });
      options.onProgress?.((frame + 1) / frameCount);
    }

    await output.finalize();
    if (!target.buffer) {
      throw new AppError('errorEncoderOutput', 'The video encoder did not produce an MP4 file.');
    }
    if (!isMp4(target.buffer)) {
      throw new AppError('errorEncoderInvalid', 'The video encoder produced an invalid MP4 file.');
    }
    return new Blob([target.buffer], { type: 'video/mp4' });
  } catch (error) {
    // cancel() is a no-op once finalize() has run, and its own failure must never
    // replace the error that actually stopped the export.
    await output.cancel().catch(() => undefined);
    throw error;
  }
}
