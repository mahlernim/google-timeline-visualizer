import { describe, expect, it } from 'vitest';
import {
  blendViewport,
  buildCameraTrack,
  cameraViewportAt,
  overviewViewport,
} from './camera';
import { cumulativeDistances, overviewRouteSegments, unwrapJourneyPoints } from './geo';
import {
  ASPECT_EPSILON,
  cartoTileUrl,
  drawFrame,
  MAP_ATTRIBUTION,
  MIN_PREVIEW_SHORT_EDGE,
  previewCanvasSize,
  requiredTiles,
} from './renderer';
import type { GeoPoint, PreparedJourney, RenderSize, TimelineFrame, Viewport } from './types';

const FORMATS: RenderSize[] = [
  { width: 480, height: 480 },
  { width: 720, height: 720 },
  { width: 1080, height: 1080 },
  { width: 1080, height: 1920 },
  { width: 1920, height: 1080 },
];

const LANDSCAPE = FORMATS[4];
const PORTRAIT = FORMATS[3];

function label(size: RenderSize): string {
  return `${size.width}x${size.height}`;
}

function aspect(size: RenderSize): number {
  return size.width / size.height;
}

function relativeAspectError(size: RenderSize, format: RenderSize): number {
  return Math.abs(aspect(size) - aspect(format)) / aspect(format);
}

// drawFrame only ever calls context methods and writes context properties, so a Proxy that
// records every call is enough to compare two renderings of the same frame.
interface RecordedCall {
  method: string;
  args: unknown[];
}

function recordingCanvas(width: number, height: number): {
  canvas: HTMLCanvasElement;
  calls: RecordedCall[];
} {
  const calls: RecordedCall[] = [];
  const context = new Proxy({}, {
    get: (_target, property: string | symbol) => (...args: unknown[]): void => {
      calls.push({ method: String(property), args });
    },
    // Property writes carry the stroke widths, the shadow blur and the font size, so they are
    // recorded in call order alongside the methods. Discarding them would leave the scaling of
    // every stroke untested while the path geometry still compared equal.
    set: (_target, property: string | symbol, value: unknown): boolean => {
      calls.push({ method: `set:${String(property)}`, args: [value] });
      return true;
    },
  });
  const canvas = {
    width,
    height,
    getContext: () => context,
  } as unknown as HTMLCanvasElement;
  return { canvas, calls };
}

// prepareJourney downloads every tile the animation can reach. 200 samples put both values the
// tests draw on the grid exactly: journeyProgress 0.42 is 84/200, and the outro blend fraction
// easeOutCubic(0.5) is 0.875, which is 175/200.
const TILE_SAMPLES = 200;

const KOREAN_ROUTE: Array<[number, number]> = [
  [37.5665, 126.9780],
  [37.4563, 126.7052],
  [36.3504, 127.3845],
  [35.8714, 128.6014],
  [35.1796, 129.0756],
];

const preparedCache = new Map<string, PreparedJourney>();

/** Mirrors prepareJourney without the network: every reachable tile key maps to one dummy image. */
function preparedAt(size: RenderSize): PreparedJourney {
  const cached = preparedCache.get(label(size));
  if (cached) return cached;
  const points: GeoPoint[] = KOREAN_ROUTE.map(([latitude, longitude], index) => ({
    instant: new Date(index * 60_000),
    latitude,
    longitude,
  }));
  const worldPoints = unwrapJourneyPoints(points);
  const cumulativeDistanceKm = cumulativeDistances(points);
  const journey = {
    points,
    worldPoints,
    cumulativeDistanceKm,
    totalDistanceKm: cumulativeDistanceKm.at(-1) ?? 0,
  };
  const cameraTrack = buildCameraTrack(journey, size, 'steady');
  const segments = overviewRouteSegments(worldPoints);
  const endingOverview = overviewViewport({ ...journey, worldPoints: segments.flat() }, size);
  const image = {} as HTMLImageElement;
  const tiles = new Map<string, HTMLImageElement>();
  const collect = (viewport: Viewport): void => {
    for (const tile of requiredTiles(viewport)) {
      tiles.set(`${tile.zoom}/${tile.x}/${tile.y}`, image);
    }
  };
  for (let sample = 0; sample <= TILE_SAMPLES; sample += 1) {
    collect(cameraViewportAt(cameraTrack, sample / TILE_SAMPLES));
  }
  const journeyEnd = cameraViewportAt(cameraTrack, 1);
  for (let sample = 0; sample <= TILE_SAMPLES; sample += 1) {
    collect(blendViewport(journeyEnd, endingOverview, sample / TILE_SAMPLES, size));
  }
  const result: PreparedJourney = {
    ...journey,
    overviewRouteSegments: segments,
    size,
    cameraTrack,
    overviewViewport: endingOverview,
    tiles,
  };
  preparedCache.set(label(size), result);
  return result;
}

// Positions and lengths scale with the canvas; angles, colours, alphas and text do not. The
// 'set:' entries are the widths a regression would most plausibly leave unscaled, and every
// property not listed here has to match exactly, which pins globalAlpha and the colours too.
const SCALED_ARGS: Record<string, number[]> = {
  clearRect: [0, 1, 2, 3],
  fillRect: [0, 1, 2, 3],
  moveTo: [0, 1],
  lineTo: [0, 1],
  arc: [0, 1, 2],
  roundRect: [0, 1, 2, 3, 4],
  fillText: [1, 2, 3],
  drawImage: [1, 2, 3, 4],
  'set:lineWidth': [0],
  'set:shadowBlur': [0],
  'set:font': [0],
};

/** Splits `700 34px -apple-system` into the px size and the text on either side of it. */
function splitFontSize(value: unknown): { size: number; rest: string } {
  const match = /^(.*?)(\d+(?:\.\d+)?)px(.*)$/.exec(String(value));
  if (!match) throw new Error(`Expected a font with a px size, got ${String(value)}`);
  return { size: Number(match[2]), rest: `${match[1]}|${match[3]}` };
}

function render(canvasSize: RenderSize, journey: PreparedJourney, frame: TimelineFrame): RecordedCall[] {
  const { canvas, calls } = recordingCanvas(canvasSize.width, canvasSize.height);
  drawFrame(canvas, journey, frame, {
    title: 'Seoul to Busan',
    periodLabel: 'March 2026',
    separator: ' · ',
    formatDistance: (kilometers) => `${Math.round(kilometers)} km`,
  });
  return calls;
}

function expectProportional(big: RecordedCall[], small: RecordedCall[], factor: number): void {
  expect(small.map((call) => call.method)).toEqual(big.map((call) => call.method));
  big.forEach((call, index) => {
    const other = small[index];
    const scaled = SCALED_ARGS[call.method] ?? [];
    call.args.forEach((argument, position) => {
      if (!scaled.includes(position)) {
        expect(other.args[position]).toBe(argument);
        return;
      }
      if (typeof argument === 'number') {
        expect(other.args[position]).toBeCloseTo(argument / factor, 6);
        return;
      }
      if (call.method === 'set:font') {
        const bigFont = splitFontSize(argument);
        const smallFont = splitFontSize(other.args[position]);
        expect(smallFont.rest).toBe(bigFont.rest);
        expect(smallFont.size).toBeCloseTo(bigFont.size / factor, 6);
        return;
      }
      expect(other.args[position]).toBe(argument);
    });
  });
}

function drawImageCount(calls: RecordedCall[]): number {
  return calls.filter((call) => call.method === 'drawImage').length;
}

interface PreviewSizeRow {
  name: string;
  format: RenderSize;
  cssWidth: number;
  ratio: number;
  expected: RenderSize;
}

function row(
  format: RenderSize,
  cssWidth: number,
  ratio: number,
  width: number,
  height: number,
): PreviewSizeRow {
  return {
    name: `${label(format)} at ${cssWidth} css px and dpr ${ratio} becomes ${width}x${height}`,
    format,
    cssWidth,
    ratio,
    expected: { width, height },
  };
}

// Layout widths derived from style.css: a 390 px phone leaves 332 css px inside the card and
// 313 for a portrait preview, a desktop window leaves 576 and 334.
const PREVIEW_SIZE_TABLE: PreviewSizeRow[] = [
  row(FORMATS[0], 332, 1, 332, 332),
  row(FORMATS[0], 332, 2, 480, 480),
  row(FORMATS[0], 332, 3, 480, 480),
  row(FORMATS[1], 332, 2, 664, 664),
  row(FORMATS[1], 332, 3, 720, 720),
  row(FORMATS[2], 332, 2, 664, 664),
  row(FORMATS[2], 332, 3, 996, 996),
  row(PORTRAIT, 313, 1, 313, 556),
  row(PORTRAIT, 313, 2, 626, 1113),
  row(PORTRAIT, 313, 3, 939, 1669),
  row(LANDSCAPE, 332, 2, 664, 374),
  row(LANDSCAPE, 332, 3, 996, 560),
  row(FORMATS[0], 576, 1, 480, 480),
  row(FORMATS[1], 576, 1, 576, 576),
  row(FORMATS[2], 576, 2, 1080, 1080),
  row(PORTRAIT, 334, 2, 668, 1188),
  row(LANDSCAPE, 576, 1, 576, 324),
  row(LANDSCAPE, 576, 2, 1152, 648),
  row(LANDSCAPE, 576, 3, 1728, 972),
  row(LANDSCAPE, 0, 2, 1920, 1080),
  row(PORTRAIT, Number.NaN, 2, 1080, 1920),
  row(FORMATS[1], 332, 0, 332, 332),
  row(FORMATS[1], 332, undefined as unknown as number, 332, 332),
  row(FORMATS[0], -5, 2, 480, 480),
  row(LANDSCAPE, Number.POSITIVE_INFINITY, 1, 1920, 1080),
  row(FORMATS[2], 4000, 3, 1080, 1080),
  row(LANDSCAPE, 10, 1, 427, 240),
  row(PORTRAIT, 10, 1, 240, 427),
];

const SCAN_CSS_WIDTHS = [1, 10, 313, 332, 576, 1200, 4000];
const SCAN_RATIOS = [1, 2, 3];

function scan(visit: (size: RenderSize, format: RenderSize) => void): void {
  FORMATS.forEach((format) => {
    SCAN_CSS_WIDTHS.forEach((cssWidth) => {
      SCAN_RATIOS.forEach((ratio) => {
        visit(previewCanvasSize(format, cssWidth, ratio), format);
      });
    });
  });
}

describe('previewCanvasSize', () => {
  it.each(PREVIEW_SIZE_TABLE)('$name', ({ format, cssWidth, ratio, expected }) => {
    expect(previewCanvasSize(format, cssWidth, ratio)).toEqual(expected);
  });

  it('never exceeds the format, so the preview is never sharper than the video', () => {
    scan((size, format) => {
      expect(size.width).toBeLessThanOrEqual(format.width);
      expect(size.height).toBeLessThanOrEqual(format.height);
    });
  });

  it('keeps the short edge at the floor', () => {
    scan((size, format) => {
      expect(Math.min(size.width, size.height))
        .toBeGreaterThanOrEqual(Math.min(MIN_PREVIEW_SHORT_EDGE, Math.min(format.width, format.height)));
    });
  });

  it('returns whole device pixels', () => {
    scan((size) => {
      expect(Number.isInteger(size.width)).toBe(true);
      expect(Number.isInteger(size.height)).toBe(true);
    });
  });

  it('always lands inside the aspect ratio the drawFrame guard allows', () => {
    scan((size, format) => {
      const error = relativeAspectError(size, format);
      expect(error).toBeLessThanOrEqual(ASPECT_EPSILON);
      // Pins the observed worst case, 1920x1080 rounding to 440x248, so lowering the short
      // edge floor far enough to drift further fails here before it reaches the guard.
      expect(error).toBeLessThanOrEqual(0.0021);
    });
  });

  it('falls back to the exact format size when the width cannot be measured', () => {
    [0, -5, Number.NaN, Number.POSITIVE_INFINITY].forEach((cssWidth) => {
      FORMATS.forEach((format) => {
        expect(previewCanvasSize(format, cssWidth, 2)).toEqual(format);
      });
    });
  });

  it('treats a missing or unusable device pixel ratio as 1', () => {
    const unusable = [0, -1, Number.NaN, undefined as unknown as number];
    FORMATS.forEach((format) => {
      unusable.forEach((ratio) => {
        expect(previewCanvasSize(format, 332, ratio)).toEqual(previewCanvasSize(format, 332, 1));
      });
    });
  });

  it('never shrinks as the measured width grows', () => {
    const widths = [1, 10, 50, 100, 200, 313, 332, 400, 576, 800, 1200, 2000, 4000];
    FORMATS.forEach((format) => {
      SCAN_RATIOS.forEach((ratio) => {
        let previous = previewCanvasSize(format, widths[0], ratio);
        widths.slice(1).forEach((cssWidth) => {
          const next = previewCanvasSize(format, cssWidth, ratio);
          expect(next.width).toBeGreaterThanOrEqual(previous.width);
          expect(next.height).toBeGreaterThanOrEqual(previous.height);
          previous = next;
        });
      });
    });
  });
});

describe('drawFrame on a proportionally smaller canvas', () => {
  const journeyFrame: TimelineFrame = { journeyProgress: 0.42, outroProgress: 0 };
  const outroFrame: TimelineFrame = { journeyProgress: 1, outroProgress: 0.5 };

  it('composes the journey identically at half the device pixels', () => {
    const journey = preparedAt(LANDSCAPE);
    expectProportional(
      render(LANDSCAPE, journey, journeyFrame),
      render({ width: 960, height: 540 }, journey, journeyFrame),
      2,
    );
  });

  it('composes the outro identically at half the device pixels', () => {
    const journey = preparedAt(LANDSCAPE);
    expectProportional(
      render(LANDSCAPE, journey, outroFrame),
      render({ width: 960, height: 540 }, journey, outroFrame),
      2,
    );
  });

  it('still finds the downloaded tiles during the outro', () => {
    // blendViewport derives its tile zoom from the size it is given. Passing the canvas size
    // instead of the prepared size drops the zoom by a level, every tile lookup misses, and
    // the map goes blank for the whole outro.
    const journey = preparedAt(LANDSCAPE);
    const big = drawImageCount(render(LANDSCAPE, journey, outroFrame));
    const small = drawImageCount(render({ width: 960, height: 540 }, journey, outroFrame));
    expect(big).toBeGreaterThan(0);
    expect(small).toBe(big);
  });
});

describe('drawFrame aspect ratio guard', () => {
  const frame: TimelineFrame = { journeyProgress: 0.5, outroProgress: 0 };
  const message = 'The prepared journey does not match the canvas aspect ratio.';

  it.each([
    { width: 960, height: 540 },
    { width: 640, height: 360 },
    { width: 427, height: 240 },
  ])('accepts a landscape journey on a $width by $height canvas', (size) => {
    expect(() => render(size, preparedAt(LANDSCAPE), frame)).not.toThrow();
  });

  it('accepts a portrait journey on a 240 by 427 canvas', () => {
    expect(() => render({ width: 240, height: 427 }, preparedAt(PORTRAIT), frame)).not.toThrow();
  });

  it('rejects a square journey on a landscape canvas', () => {
    expect(() => render(LANDSCAPE, preparedAt(FORMATS[2]), frame)).toThrow(message);
  });

  it('rejects a portrait journey on a square canvas', () => {
    expect(() => render(FORMATS[2], preparedAt(PORTRAIT), frame)).toThrow(message);
  });

  it('rejects a canvas that was never sized', () => {
    expect(() => render({ width: 0, height: 0 }, preparedAt(LANDSCAPE), frame)).toThrow(message);
  });

  it.each(FORMATS)('accepts the exact $width by $height export size', (format) => {
    expect(() => render(format, preparedAt(format), frame)).not.toThrow();
  });

  it.each(FORMATS)('accepts every preview size for $width by $height', (format) => {
    [
      { cssWidth: 313, ratio: 1 },
      { cssWidth: 332, ratio: 2 },
      { cssWidth: 332, ratio: 3 },
      { cssWidth: 576, ratio: 2 },
      { cssWidth: 10, ratio: 1 },
    ].forEach(({ cssWidth, ratio }) => {
      const size = previewCanvasSize(format, cssWidth, ratio);
      expect(() => render(size, preparedAt(format), frame)).not.toThrow();
    });
  });
});

describe('map attribution', () => {
  // Burned into the MP4, where it outlives the app locale and cannot be corrected afterwards.
  // Android and web keep the same developer-owned string after a localized Android value once
  // dropped '© CARTO' entirely. The constant still needs to be pinned here.
  it('names both providers', () => {
    expect(MAP_ATTRIBUTION).toContain('OpenStreetMap');
    expect(MAP_ATTRIBUTION).toContain('CARTO');
    expect(MAP_ATTRIBUTION).toBe('© OpenStreetMap contributors © CARTO');
  });

  it('is drawn into every frame', () => {
    const { canvas, calls } = recordingCanvas(480, 480);
    drawFrame(canvas, preparedAt(FORMATS[0]), { journeyProgress: 0.5, outroProgress: 0 }, {
      title: 'Seoul to Busan',
      periodLabel: 'March 2026',
      separator: ' · ',
      formatDistance: (kilometers) => `${Math.round(kilometers * 0.621371192237334)} mi`,
    });
    const drawn = calls.filter((call) => call.method === 'fillText').map((call) => call.args[0]);
    expect(drawn).toContain(MAP_ATTRIBUTION);
    expect(drawn).toContain('Seoul to Busan');
    const halfwayMiles = Math.round(preparedAt(FORMATS[0]).totalDistanceKm * 0.5 * 0.621371192237334);
    expect(drawn).toContain(`March 2026 · ${halfwayMiles} mi`);
  });
});

describe('CARTO project key', () => {
  it('adds the encoded key without changing the tile path', () => {
    const url = new URL(cartoTileUrl({ zoom: 13, x: 6985, y: 3172 }, 'project key/value'));

    expect(url.pathname).toBe('/light_all/13/6985/3172.png');
    expect(url.searchParams.get('key')).toBe('project key/value');
  });
});
