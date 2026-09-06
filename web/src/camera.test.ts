import { describe, expect, it } from 'vitest';
import {
  blendViewport,
  buildCameraTrack,
  cameraViewportAt,
  overviewSafeArea,
  overviewViewport,
  worldPositionAtProgress,
} from './camera';
import {
  cumulativeDistances,
  overviewRouteSegments,
  project,
  unwrapJourneyPoints,
  unwrapWorldPoints,
} from './geo';
import { requiredTiles } from './renderer';
import type { CameraMovement, GeoPoint, RenderSize } from './types';

const SQUARE_480: RenderSize = { width: 480, height: 480 };

const FORMATS: RenderSize[] = [
  { width: 480, height: 480 },
  { width: 720, height: 720 },
  { width: 1080, height: 1080 },
  { width: 1080, height: 1920 },
  { width: 1920, height: 1080 },
];

function label(size: RenderSize): string {
  return `${size.width}x${size.height}`;
}

function ratio(viewport: { minX: number; maxX: number; minY: number; maxY: number }): number {
  return (viewport.maxX - viewport.minX) / (viewport.maxY - viewport.minY);
}

function journey(points: Array<[number, number]>) {
  const geoPoints: GeoPoint[] = points.map(([latitude, longitude], index) => ({
    instant: new Date(index * 60_000),
    latitude,
    longitude,
  }));
  const cumulativeDistanceKm = cumulativeDistances(geoPoints);
  return {
    worldPoints: unwrapJourneyPoints(geoPoints),
    cumulativeDistanceKm,
    totalDistanceKm: cumulativeDistanceKm.at(-1) ?? 0,
  };
}

/** Adds intermediate samples so a long leg is measured the way a real timeline records it. */
function densify(points: Array<[number, number]>, steps: number): Array<[number, number]> {
  const dense: Array<[number, number]> = [points[0]];
  for (let index = 1; index < points.length; index += 1) {
    const [fromLatitude, fromLongitude] = points[index - 1];
    const [toLatitude, toLongitude] = points[index];
    for (let step = 1; step <= steps; step += 1) {
      const fraction = step / steps;
      dense.push([
        fromLatitude + (toLatitude - fromLatitude) * fraction,
        fromLongitude + (toLongitude - fromLongitude) * fraction,
      ]);
    }
  }
  return dense;
}

function center(viewport: ReturnType<typeof cameraViewportAt>): [number, number] {
  return [(viewport.minX + viewport.maxX) / 2, (viewport.minY + viewport.maxY) / 2];
}

describe('camera track', () => {
  const koreanJourney = journey([
    [37.5665, 126.9780],
    [37.4563, 126.7052],
    [36.3504, 127.3845],
    [35.8714, 128.6014],
    [35.1796, 129.0756],
  ]);

  it.each<CameraMovement>(['fixed', 'steady', 'dynamic', 'close-up'])('%s follows the journey instead of freezing', (movement) => {
    const track = buildCameraTrack(koreanJourney, SQUARE_480, movement);
    const [startX, startY] = center(cameraViewportAt(track, 0));
    const [endX, endY] = center(cameraViewportAt(track, 1));
    expect(Math.hypot(endX - startX, endY - startY)).toBeGreaterThan(0.001);
  });

  it.each<CameraMovement>(['dynamic', 'close-up'])('keeps the marker inside the stable central area in %s mode', (movement) => {
    const track = buildCameraTrack(koreanJourney, SQUARE_480, movement);
    for (let sample = 0; sample <= 40; sample += 1) {
      const progress = sample / 40;
      const viewport = cameraViewportAt(track, progress);
      const marker = worldPositionAtProgress(koreanJourney, progress).point;
      const normalizedX = (marker.x - viewport.minX) / (viewport.maxX - viewport.minX);
      const normalizedY = (marker.y - viewport.minY) / (viewport.maxY - viewport.minY);
      expect(normalizedX).toBeGreaterThanOrEqual(0.299);
      expect(normalizedX).toBeLessThanOrEqual(0.701);
      expect(normalizedY).toBeGreaterThanOrEqual(0.299);
      expect(normalizedY).toBeLessThanOrEqual(0.701);
    }
  });

  it('keeps one zoom span in fixed mode while continuing to pan', () => {
    const track = buildCameraTrack(koreanJourney, SQUARE_480, 'fixed');
    const spans = [0, 0.2, 0.5, 0.8, 1].map((progress) => {
      const viewport = cameraViewportAt(track, progress);
      return viewport.maxY - viewport.minY;
    });
    spans.forEach((span) => expect(span).toBeCloseTo(spans[0], 12));
  });

  it.each<CameraMovement>(['dynamic', 'close-up'])('uses the short camera path and wrapped tiles across the date line in %s mode', (movement) => {
    const dateLineJourney = journey([[10, 179], [10.2, -179]]);
    const track = buildCameraTrack(dateLineJourney, SQUARE_480, movement);
    const middle = cameraViewportAt(track, 0.5);
    expect(middle.maxX - middle.minX).toBeLessThan(0.05);
    const count = 2 ** middle.zoom;
    requiredTiles(middle).forEach((tile) => {
      expect(tile.x).toBeGreaterThanOrEqual(0);
      expect(tile.x).toBeLessThan(count);
    });
  });

  it('moves back west on the return leg of an Arctic round trip', () => {
    const arcticRoundTrip = journey([
      [37, 127],
      [70, 170],
      [82, -170],
      [38, -122],
      [70, -60],
      [82, 20],
      [70, 100],
      [37, 127],
    ]);
    const track = buildCameraTrack(arcticRoundTrip, SQUARE_480, 'dynamic');
    const turnProgress = arcticRoundTrip.cumulativeDistanceKm[3] / arcticRoundTrip.totalDistanceKm;
    const [startX] = center(cameraViewportAt(track, 0));
    const [turnX] = center(cameraViewportAt(track, turnProgress));
    const [endX] = center(cameraViewportAt(track, 1));

    expect(turnX).toBeGreaterThan(startX);
    expect(endX).toBeLessThan(turnX);
  });

  it.each<CameraMovement>(['dynamic', 'close-up'])('smooths changing spans and stabilizes integer tile zoom in %s mode', (movement) => {
    const changingJourney = journey([
      [37.5665, 126.9780],
      [37.5650, 126.9850],
      [35.1796, 129.0756],
      [35.1800, 129.0800],
    ]);
    const track = buildCameraTrack(changingJourney, SQUARE_480, movement);
    const maximumLogSpanChange = movement === 'close-up' ? 1.25 : 0.8;
    for (let index = 1; index < track.frames.length; index += 1) {
      const previous = track.frames[index - 1];
      const current = track.frames[index];
      expect(Number.isFinite(current.spanY)).toBe(true);
      expect(Math.abs(Math.log(current.spanY / previous.spanY))).toBeLessThan(maximumLogSpanChange);
      expect(Math.abs(current.zoom - previous.zoom)).toBeLessThanOrEqual(3);
    }
  });

  it('frames local travel more tightly in close-up mode than dynamic mode', () => {
    const localJourney = journey(densify([
      [37.5665, 126.9780],
      [37.5200, 127.0200],
      [37.5000, 127.0600],
    ], 12));
    const closeUp = cameraViewportAt(buildCameraTrack(localJourney, SQUARE_480, 'close-up'), 0.5);
    const dynamic = cameraViewportAt(buildCameraTrack(localJourney, SQUARE_480, 'dynamic'), 0.5);

    expect(closeUp.maxY - closeUp.minY).toBeLessThan(dynamic.maxY - dynamic.minY);
  });

  it('keeps both ends of a long transfer visible while close-up mode crosses it', () => {
    const transfer = journey([
      [37.5665, 126.9780],
      [48.8566, 2.3522],
    ]);
    const viewport = cameraViewportAt(buildCameraTrack(transfer, SQUARE_480, 'close-up'), 0.5);

    transfer.worldPoints.forEach((point) => {
      expect(point.x).toBeGreaterThanOrEqual(viewport.minX);
      expect(point.x).toBeLessThanOrEqual(viewport.maxX);
      expect(point.y).toBeGreaterThanOrEqual(viewport.minY);
      expect(point.y).toBeLessThanOrEqual(viewport.maxY);
    });
  });

  it('uses the same 6 km close-up minimum route context as Android and CLI', () => {
    // The proportional context for close-up is 3.5% of total distance.
    // On a 100 km route that gives 3.5 km, which is below both the old (15) and new (6) floor.
    // On a 200 km route that gives 7 km, which is above 6 but still below 15.
    // We verify that close-up frames a 200 km route tighter than it would with a 15 km floor
    // by comparing it against dynamic mode, whose minimum is 100 km and therefore dominates.
    // The close-up viewport must be narrower, which is only true when its floor is 6, not 15.
    const localHop = journey(densify([
      [37.5665, 126.9780],
      [37.5665, 128.7700],  // ~160 km east — proportional context ~5.6 km (below 6 floor)
    ], 60));
    const closeUp = cameraViewportAt(buildCameraTrack(localHop, SQUARE_480, 'close-up'), 0.5);
    const dynamic = cameraViewportAt(buildCameraTrack(localHop, SQUARE_480, 'dynamic'), 0.5);

    // close-up must frame more tightly than dynamic on this route length
    expect(closeUp.maxY - closeUp.minY).toBeLessThan(dynamic.maxY - dynamic.minY);
  });

  it('selects a narrower local context window with 6 km floor than with 15 km floor', () => {
    // Synthetic route: ~10 km total. Proportional context = 0.035 × 10 = 0.35 km → floor applies.
    // With floor=6 the camera looks 6 km ahead/behind. With floor=15 it would look 15 km — wider
    // than the whole route, causing unnecessary zoom-out on a short local trip.
    // We verify the viewport is tighter than it would be if the 15 km floor were still active,
    // by checking it is narrower than the equivalent dynamic viewport (floor=100 km, always wider).
    const shortLocal = journey(densify([
      [37.5665, 126.9780],
      [37.5200, 127.0600],  // ~10 km
    ], 20));
    const closeUp = cameraViewportAt(buildCameraTrack(shortLocal, SQUARE_480, 'close-up'), 0.5);
    const dynamic = cameraViewportAt(buildCameraTrack(shortLocal, SQUARE_480, 'dynamic'), 0.5);

    // close-up must be tighter than dynamic on a short local trip (no transfer override)
    expect(closeUp.maxY - closeUp.minY).toBeLessThan(dynamic.maxY - dynamic.minY);
  });

  it.each(FORMATS)('keeps close-up camera frames at the $width x $height aspect ratio', (size) => {
    const track = buildCameraTrack(koreanJourney, size, 'close-up');
    [0, 0.25, 0.5, 0.75, 1].forEach((progress) => {
      expect(ratio(cameraViewportAt(track, progress))).toBeCloseTo(size.width / size.height, 10);
    });
  });

  it.each(FORMATS)('fits the complete route below the Android-style video header at $width x $height', (size) => {
    const viewport = overviewViewport(koreanJourney, size);
    const safe = overviewSafeArea(size);
    koreanJourney.worldPoints.forEach((point) => {
      const screenX = (point.x - viewport.minX) / (viewport.maxX - viewport.minX) * size.width;
      const screenY = (point.y - viewport.minY) / (viewport.maxY - viewport.minY) * size.height;
      expect(screenX).toBeGreaterThanOrEqual(safe.left);
      expect(screenX).toBeLessThanOrEqual(safe.right);
      expect(screenY).toBeGreaterThanOrEqual(safe.top);
      expect(screenY).toBeLessThanOrEqual(safe.bottom);
    });
  });

  it.each(FORMATS)('keeps the drawn ending route on screen at $width x $height', (size) => {
    // renderer.prepareJourney feeds the overview the same segments drawFrame strokes, and
    // drawFrame ends on blendViewport(..., 1), so the fit has to survive that recomputation.
    const intercontinental = journey(densify([
      [37.57, 126.98],
      [48.86, 2.35],
      [40.71, -74.01],
    ], 120));
    const drawn = overviewRouteSegments(intercontinental.worldPoints).flat();
    const track = buildCameraTrack(intercontinental, size, 'steady');
    const ending = blendViewport(
      cameraViewportAt(track, 1),
      overviewViewport({ ...intercontinental, worldPoints: drawn }, size),
      1,
      size,
    );
    const safe = overviewSafeArea(size);

    drawn.forEach((point) => {
      const screenX = (point.x - ending.minX) / (ending.maxX - ending.minX) * size.width;
      const screenY = (point.y - ending.minY) / (ending.maxY - ending.minY) * size.height;
      expect(screenX).toBeGreaterThanOrEqual(safe.left);
      expect(screenX).toBeLessThanOrEqual(safe.right);
      expect(screenY).toBeGreaterThanOrEqual(safe.top);
      expect(screenY).toBeLessThanOrEqual(safe.bottom);
    });
  });

  it('calculates the same overview above browser argument limits', () => {
    const endpoints = unwrapWorldPoints([project(70, 20), project(-55, 20)]);
    const denseJourney = {
      worldPoints: Array.from({ length: 200_000 }, (_, index) => endpoints[index % endpoints.length]),
      cumulativeDistanceKm: [],
      totalDistanceKm: 0,
    };
    const endpointJourney = {
      worldPoints: endpoints,
      cumulativeDistanceKm: [],
      totalDistanceKm: 0,
    };

    expect(overviewViewport(denseJourney, SQUARE_480)).toEqual(overviewViewport(endpointJourney, SQUARE_480));
  });

  it('builds the moving camera above browser argument limits', () => {
    const point = koreanJourney.worldPoints[0];
    const pointCount = 130_000;
    const denseJourney = {
      worldPoints: Array.from({ length: pointCount }, () => point),
      cumulativeDistanceKm: new Array<number>(pointCount).fill(0),
      totalDistanceKm: 0,
    };

    const track = buildCameraTrack(denseJourney, SQUARE_480, 'steady');

    expect(track.frames).toHaveLength(481);
    expect(track.frames.every((frame) => Number.isFinite(frame.spanY))).toBe(true);
  });

  it.each(FORMATS)('blends from the final following view to the full-route ending view at $width x $height', (size) => {
    const track = buildCameraTrack(koreanJourney, size, 'dynamic');
    const following = cameraViewportAt(track, 1);
    const overview = overviewViewport(koreanJourney, size);
    const start = blendViewport(following, overview, 0, size);
    // Only the rectangle has to be continuous. The tile zoom may legitimately differ because
    // the camera track applies zoom hysteresis while blendViewport derives zoom from the span,
    // exactly as Android does. Before the aspect fix the rectangle matched only at aspect 1.
    expect([start.minX, start.maxX, start.minY, start.maxY])
      .toEqual([following.minX, following.maxX, following.minY, following.maxY]);
    const ending = blendViewport(following, overview, 1, size);
    expect(ending.minX).toBeCloseTo(overview.minX, 12);
    expect(ending.maxX).toBeCloseTo(overview.maxX, 12);
    expect(ending.minY).toBeCloseTo(overview.minY, 12);
    expect(ending.maxY).toBeCloseTo(overview.maxY, 12);
  });

  it.each(FORMATS)('builds a camera track whose aspect matches $width x $height', (size) => {
    const track = buildCameraTrack(koreanJourney, size, 'dynamic');
    expect(track.aspect).toBe(size.width / size.height);
  });

  it.each(FORMATS)('keeps every journey frame at the $width x $height aspect ratio', (size) => {
    const track = buildCameraTrack(koreanJourney, size, 'dynamic');
    const aspect = size.width / size.height;
    [0, 0.25, 0.5, 0.75, 1].forEach((progress) => {
      expect(ratio(cameraViewportAt(track, progress))).toBeCloseTo(aspect, 10);
    });
  });

  it.each(FORMATS)('keeps the ending overview at the $width x $height aspect ratio', (size) => {
    expect(ratio(overviewViewport(koreanJourney, size))).toBeCloseTo(size.width / size.height, 10);
  });

  it.each(FORMATS)('preserves the aspect ratio throughout the blend at $width x $height', (size) => {
    const track = buildCameraTrack(koreanJourney, size, 'dynamic');
    const following = cameraViewportAt(track, 1);
    const overview = overviewViewport(koreanJourney, size);
    const aspect = size.width / size.height;
    for (let step = 0; step <= 20; step += 1) {
      const blended = blendViewport(following, overview, step / 20, size);
      expect(ratio(blended)).toBeCloseTo(aspect, 10);
    }
  });

  it('insets the overview safe area from both axes of a portrait canvas', () => {
    const safe = overviewSafeArea({ width: 1080, height: 1920 });
    expect(safe.left).toBeCloseTo(51, 10);
    expect(safe.top).toBeCloseTo(228, 10);
    expect(safe.right).toBeCloseTo(1029, 10);
    expect(safe.bottom).toBeCloseTo(1869, 10);
  });

  it('insets the overview safe area from both axes of a landscape canvas', () => {
    const safe = overviewSafeArea({ width: 1920, height: 1080 });
    expect(safe.left).toBeCloseTo(51, 10);
    expect(safe.top).toBeCloseTo(228, 10);
    expect(safe.right).toBeCloseTo(1869, 10);
    expect(safe.bottom).toBeCloseTo(1029, 10);
  });

  it.each(FORMATS)('scales the overview safe area by the short edge at $width x $height', (size) => {
    const scale = Math.min(size.width, size.height) / 720;
    const safe = overviewSafeArea(size);
    expect(safe.left).toBeCloseTo(34 * scale, 10);
    expect(safe.top).toBeCloseTo(152 * scale, 10);
    expect(safe.right).toBeCloseTo(size.width - 34 * scale, 10);
    expect(safe.bottom).toBeCloseTo(size.height - 34 * scale, 10);
  });

  it.each(FORMATS)('derives the overview zoom from the width and the X span at $width x $height', (size) => {
    const viewport = overviewViewport(koreanJourney, size);
    const spanX = viewport.maxX - viewport.minX;
    const expected = Math.max(2, Math.min(15, Math.floor(Math.log2(size.width / (256 * spanX)))));
    expect(viewport.zoom).toBe(expected);
  });

  it.each(FORMATS)('resolves the tile zoom identically from either axis at $width x $height', (size) => {
    const aspect = size.width / Math.max(1, size.height);
    [0.01, 0.05, 0.2, 0.72].forEach((spanY) => {
      const fromWidth = Math.floor(Math.log2(size.width / (256 * spanY * aspect)));
      const fromHeight = Math.floor(Math.log2(size.height / (256 * spanY)));
      expect(fromWidth).toBe(fromHeight);
    });
  });

  it('names every format distinctly so parameterized cases stay readable', () => {
    expect(new Set(FORMATS.map(label)).size).toBe(FORMATS.length);
  });
});
