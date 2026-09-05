import { StreamTarget, CanvasSource, Mp4OutputFormat, Output, Quality } from 'mediabunny';
import { Mp4Buffer } from './mp4-buffer';
import { frameAtElapsedSeconds, OUTRO_SECONDS } from './animation';
import { AppError } from './errors';
import { drawJourneyFrame } from './renderer';
import type { PreparedJourney } from './types';

import { hasVideoEncoder, estimatedOutputBytes, MAX_OUTPUT_BYTES } from './video-format';
import type { ExportOptions } from './video-format';
export * from './video-format';

export async function createJourneyMp4(
  canvas: HTMLCanvasElement,
  journey: PreparedJourney,
  options: ExportOptions,
): Promise<Blob> {
  if (!hasVideoEncoder()) {
    throw new AppError('errorNoEncoder', 'This browser cannot create MP4 video. Use Safari 16.4 or newer.');
  }

  if (estimatedOutputBytes(options.format, options.durationSeconds) > MAX_OUTPUT_BYTES) throw new Error('outputTooLarge');
  const { width, height, frameRate: fps, bitrate, codec } = options.format;
  if (canvas.width !== width || canvas.height !== height) {
    throw new AppError('errorCanvasSize', 'The preview is not using the selected video format size.');
  }

  const frameDuration = 1 / fps;
  const frameCount = Math.max(1, Math.round(options.durationSeconds * fps));
  const outroFrameCount = Math.min(Math.round(OUTRO_SECONDS * fps), frameCount - 1);
  const journeyFrameCount = frameCount - outroFrameCount;
  const buffer = new Mp4Buffer();
  const target = new StreamTarget(new WritableStream({
    write: ({ data, position }) => { buffer.write(data, position); },
  }));
  target.onwrite = (_start, end) => { if (end > MAX_OUTPUT_BYTES) throw new Error('outputTooLarge'); };
  const output = new Output({
    format: new Mp4OutputFormat({ fastStart: 'reserve' }),
    target,
  });
  const source = new CanvasSource(canvas, {
    codec: 'avc',
    fullCodecString: codec,
    quality: new Quality({ bitrate }),
    keyFrameInterval: 1,
    hardwareAcceleration: 'no-preference',
  });
  output.addVideoTrack(source, { frameRate: fps, maximumPacketCount: frameCount });
  output.setMetadataTags({ title: options.overlay.title });


  let cancelPromise: Promise<void> | null = null;
  const cancelOutput = (): Promise<void> => cancelPromise ??= output.cancel().catch(() => undefined);
  const onAbort = (): void => { void cancelOutput(); };
  options.signal?.addEventListener('abort', onAbort, { once: true });

  // Include start failures in cancellation so retries cannot retain codec resources.
  try {
    options.signal?.throwIfAborted();
    await output.start();
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
      await drawJourneyFrame(canvas, journey, animationFrame, options.overlay, options.signal);
      await source.add(frame * frameDuration, frameDuration, { keyFrame: frame % fps === 0 });
      options.onProgress?.((frame + 1) / frameCount);
    }

    await output.finalize();
    try { return buffer.blob(); }
    catch { throw new AppError('errorEncoderOutput', 'The video encoder did not produce a valid MP4 file.'); }
  } catch (error) {
    // cancel() is a no-op once finalize() has run, and its own failure must never
    // replace the error that actually stopped the export.
    await cancelOutput();
    throw error;
  } finally {
    options.signal?.removeEventListener('abort', onAbort);
    buffer.dispose();
  }
}
