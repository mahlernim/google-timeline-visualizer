import { describe, expect, it } from 'vitest';
import {
  frameAtElapsedSeconds,
  frameAtOverallProgress,
  totalDurationSeconds,
} from './animation';

describe('timeline ending', () => {
  it('includes the Android ending within the selected duration', () => {
    expect(totalDurationSeconds(30)).toBe(30);
    expect(totalDurationSeconds(75)).toBe(75);
  });

  it('zooms to the overview for one second and holds it for the last half-second', () => {
    expect(frameAtElapsedSeconds(28.5, 30)).toEqual({ journeyProgress: 1, outroProgress: 0 });
    expect(frameAtElapsedSeconds(29.5, 30)).toEqual({ journeyProgress: 1, outroProgress: 1 });
    expect(frameAtElapsedSeconds(29.9, 30)).toEqual({ journeyProgress: 1, outroProgress: 1 });
  });

  it('maps overall export progress across the journey and ending', () => {
    expect(frameAtOverallProgress(1, 10)).toEqual({ journeyProgress: 1, outroProgress: 1 });
    expect(frameAtOverallProgress(0, 10)).toEqual({ journeyProgress: 0, outroProgress: 0 });
  });
});
