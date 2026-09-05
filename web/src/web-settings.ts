export const WEB_RESOLUTIONS = [480, 720, 1024, 1920] as const;
export const WEB_FRAME_RATES = [15, 30, 60] as const;
export const WEB_DURATIONS = [15, 20, 30, 45, 60] as const;
export const DEFAULT_RAW_ACCURACY = 100;

export function validateWebSettings(resolution: number, fps: number, duration: number): boolean {
  return (WEB_RESOLUTIONS as readonly number[]).includes(resolution)
    && (WEB_FRAME_RATES as readonly number[]).includes(fps)
    && (WEB_DURATIONS as readonly number[]).includes(duration);
}
