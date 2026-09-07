import type { CameraFrame, CameraMovement, CameraTrack, RenderSize, Viewport, WorldPoint } from './types';
import { worldBounds } from './geo';
import { OVERLAY_BOTTOM, overlayScale } from './overlay';

interface CameraMovementProfile {
  contextFraction: number;
  minimumContextKm: number;
  maximumContextKm: number;
  padding: number;
  minimumViewportSpan: number;
  zoomOutAlpha: number;
  zoomInAlpha: number;
  legAware: boolean;
  fixedZoom: boolean;
}

interface CameraJourney {
  worldPoints: WorldPoint[];
  cumulativeDistanceKm: number[];
  totalDistanceKm: number;
}

interface WorldPosition {
  point: WorldPoint;
  distanceKm: number;
  fromIndex: number;
  toIndex: number;
}

interface JourneyLeg {
  startKm: number;
  endKm: number;
  isTransfer: boolean;
}

export const CLOSE_UP_MINIMUM_CONTEXT_KM = 6;

const MOVEMENT_PROFILES: Record<CameraMovement, CameraMovementProfile> = {
  fixed: {
    contextFraction: 0.10,
    minimumContextKm: 25,
    maximumContextKm: 350,
    padding: 2.6,
    minimumViewportSpan: 0.00060,
    zoomOutAlpha: 0,
    zoomInAlpha: 0,
    legAware: false,
    fixedZoom: true,
  },
  steady: {
    contextFraction: 1,
    minimumContextKm: 650,
    maximumContextKm: 650,
    padding: 2.8,
    minimumViewportSpan: 0.00060,
    zoomOutAlpha: 0.14,
    zoomInAlpha: 0.035,
    legAware: false,
    fixedZoom: false,
  },
  dynamic: {
    contextFraction: 0.10,
    minimumContextKm: 100,
    maximumContextKm: 350,
    padding: 2.2,
    minimumViewportSpan: 0.00045,
    zoomOutAlpha: 0.24,
    zoomInAlpha: 0.06,
    legAware: true,
    fixedZoom: false,
  },
  'close-up': {
    contextFraction: 0.035,
    minimumContextKm: CLOSE_UP_MINIMUM_CONTEXT_KM,
    maximumContextKm: 120,
    padding: 1.7,
    minimumViewportSpan: 0.00030,
    zoomOutAlpha: 0.30,
    zoomInAlpha: 0.075,
    legAware: true,
    fixedZoom: false,
  },
};

const CAMERA_TRACK_SAMPLES = 480;
// Limit abrupt Close-up expansion to one zoom level per track sample. This
// delays full transfer framing slightly without slowing ordinary zoom changes.
const CLOSE_UP_MAX_LOG_ZOOM_OUT = Math.LN2;
const CAMERA_DEAD_ZONE_HALF = 0.20;
const FIXED_ZOOM_PERCENTILE = 0.80;
const TILE_ZOOM_HYSTERESIS = 0.15;
const MIN_TILE_ZOOM = 2;
const MAX_TILE_ZOOM = 15;
const MAX_VIEWPORT_SPAN = 0.72;
const MAX_OVERVIEW_VIEWPORT_SPAN = 1.25;
const MIN_OVERVIEW_VIEWPORT_SPAN = 0.00045;
const OVERVIEW_PADDING = 1.22;
const OVERVIEW_SIDE_INSET = 34;
const OVERVIEW_HEADER_GAP = 20;
const OVERVIEW_BOTTOM_INSET = 34;
const TRANSFER_PADDING = 2.8;

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}

function lowerBound(values: number[], target: number): number {
  let low = 0;
  let high = values.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (values[middle] < target) low = middle + 1;
    else high = middle;
  }
  return low;
}

function upperBound(values: number[], target: number): number {
  let low = 0;
  let high = values.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (values[middle] <= target) low = middle + 1;
    else high = middle;
  }
  return low;
}

function median(sorted: number[]): number {
  if (sorted.length === 0) return 0;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function transferThreshold(cumulativeDistanceKm: number[]): number {
  const ordinary = cumulativeDistanceKm
    .slice(1)
    .map((distance, index) => distance - cumulativeDistanceKm[index])
    .filter((distance) => distance > 0 && distance < 120)
    .sort((a, b) => a - b);
  if (ordinary.length === 0) return 120;
  const typical = median(ordinary);
  const deviation = median(ordinary.map((distance) => Math.abs(distance - typical)).sort((a, b) => a - b));
  return clamp(Math.max(typical * 3, typical + deviation * 6), 60, 120);
}

function buildLegs(journey: CameraJourney): JourneyLeg[] {
  if (journey.worldPoints.length < 2 || journey.totalDistanceKm <= 0) return [];
  const threshold = transferThreshold(journey.cumulativeDistanceKm);
  const legs: JourneyLeg[] = [];
  let localStartKm = 0;
  for (let index = 1; index < journey.cumulativeDistanceKm.length; index += 1) {
    const startKm = journey.cumulativeDistanceKm[index - 1];
    const endKm = journey.cumulativeDistanceKm[index];
    if (endKm - startKm < Math.max(1, threshold)) continue;
    if (startKm > localStartKm) legs.push({ startKm: localStartKm, endKm: startKm, isTransfer: false });
    legs.push({ startKm, endKm, isTransfer: true });
    localStartKm = endKm;
  }
  if (journey.totalDistanceKm > localStartKm) {
    legs.push({ startKm: localStartKm, endKm: journey.totalDistanceKm, isTransfer: false });
  }
  return legs;
}

function legAt(legs: JourneyLeg[], distanceKm: number, totalDistanceKm: number): JourneyLeg {
  if (legs.length === 0) return { startKm: 0, endKm: totalDistanceKm, isTransfer: false };
  const target = clamp(distanceKm, 0, totalDistanceKm);
  let selected = legs[0];
  for (const leg of legs) {
    if (leg.startKm > target) break;
    selected = leg;
  }
  return selected;
}

export function worldPositionAtDistance(journey: CameraJourney, distanceKm: number): WorldPosition {
  if (journey.worldPoints.length === 0) {
    return { point: { x: 0.5, y: 0.5 }, distanceKm: 0, fromIndex: 0, toIndex: 0 };
  }
  if (journey.worldPoints.length === 1 || journey.totalDistanceKm <= 0) {
    return { point: journey.worldPoints[0], distanceKm: 0, fromIndex: 0, toIndex: 0 };
  }
  const target = clamp(distanceKm, 0, journey.totalDistanceKm);
  const to = clamp(lowerBound(journey.cumulativeDistanceKm, target), 1, journey.worldPoints.length - 1);
  const from = to - 1;
  const segmentDistance = journey.cumulativeDistanceKm[to] - journey.cumulativeDistanceKm[from];
  const fraction = segmentDistance <= 0 ? 0 : clamp(
    (target - journey.cumulativeDistanceKm[from]) / segmentDistance,
    0,
    1,
  );
  return {
    point: {
      x: journey.worldPoints[from].x + (journey.worldPoints[to].x - journey.worldPoints[from].x) * fraction,
      y: journey.worldPoints[from].y + (journey.worldPoints[to].y - journey.worldPoints[from].y) * fraction,
    },
    distanceKm: target,
    fromIndex: from,
    toIndex: to,
  };
}

export function worldPositionAtProgress(journey: CameraJourney, progress: number): WorldPosition {
  return worldPositionAtDistance(journey, journey.totalDistanceKm * clamp(progress, 0, 1));
}

function unwrapNear(value: number, reference: number): number {
  let result = value;
  while (result - reference > 0.5) result -= 1;
  while (result - reference < -0.5) result += 1;
  return result;
}

function clampCenterY(centerY: number, spanY: number): number {
  const half = spanY / 2;
  return half >= 0.5 ? 0.5 : clamp(centerY, half, 1 - half);
}

export function aspectOf(size: RenderSize): number {
  return size.width / Math.max(1, size.height);
}

function tileZoom(size: RenderSize, spanY: number): number {
  const aspect = aspectOf(size);
  return clamp(
    Math.floor(Math.log2(Math.max(1, size.width) / (256 * spanY * aspect))),
    MIN_TILE_ZOOM,
    MAX_TILE_ZOOM,
  );
}

export interface OverviewSafeArea {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export function overviewSafeArea(size: RenderSize): OverviewSafeArea {
  const scale = overlayScale(size);
  return {
    left: OVERVIEW_SIDE_INSET * scale,
    top: (OVERLAY_BOTTOM + OVERVIEW_HEADER_GAP) * scale,
    right: size.width - OVERVIEW_SIDE_INSET * scale,
    bottom: size.height - OVERVIEW_BOTTOM_INSET * scale,
  };
}

export function overviewViewport(journey: CameraJourney, size: RenderSize): Viewport {
  const { minX, maxX, minY, maxY } = worldBounds(journey.worldPoints);
  const contentCenterX = (minX + maxX) / 2;
  const contentCenterY = (minY + maxY) / 2;
  const contentSpanX = Math.max(maxX - minX, MIN_OVERVIEW_VIEWPORT_SPAN);
  const contentSpanY = Math.max(maxY - minY, MIN_OVERVIEW_VIEWPORT_SPAN);
  const safe = overviewSafeArea(size);
  const safeWidth = Math.max(1, safe.right - safe.left);
  const safeHeight = Math.max(1, safe.bottom - safe.top);
  const worldPerPixel = Math.max(contentSpanX / safeWidth, contentSpanY / safeHeight) * OVERVIEW_PADDING;
  const spanX = Math.max(worldPerPixel * size.width, MIN_OVERVIEW_VIEWPORT_SPAN);
  const spanY = clamp(
    worldPerPixel * size.height,
    MIN_OVERVIEW_VIEWPORT_SPAN,
    MAX_OVERVIEW_VIEWPORT_SPAN,
  );
  const viewportMinX = contentCenterX - ((safe.left + safe.right) / 2) * worldPerPixel;
  let viewportMinY = contentCenterY - ((safe.top + safe.bottom) / 2) * worldPerPixel;
  if (spanY <= 1) viewportMinY = clamp(viewportMinY, 0, 1 - spanY);
  return {
    minX: viewportMinX,
    maxX: viewportMinX + spanX,
    minY: viewportMinY,
    maxY: viewportMinY + spanY,
    zoom: clamp(
      Math.floor(Math.log2(Math.max(1, size.width) / (256 * spanX))),
      MIN_TILE_ZOOM,
      MAX_TILE_ZOOM,
    ),
  };
}

export function blendViewport(
  from: Viewport,
  to: Viewport,
  fraction: number,
  size: RenderSize,
): Viewport {
  const amount = clamp(fraction, 0, 1);
  const aspect = aspectOf(size);
  const fromCenterX = (from.minX + from.maxX) / 2;
  const toCenterX = unwrapNear((to.minX + to.maxX) / 2, fromCenterX);
  const centerX = fromCenterX + (toCenterX - fromCenterX) * amount;
  const centerY = (from.minY + from.maxY) / 2
    + ((to.minY + to.maxY) / 2 - (from.minY + from.maxY) / 2) * amount;
  const fromSpanY = Math.max(from.maxY - from.minY, MIN_OVERVIEW_VIEWPORT_SPAN);
  const toSpanY = Math.max(to.maxY - to.minY, MIN_OVERVIEW_VIEWPORT_SPAN);
  const spanY = clamp(
    Math.exp(Math.log(fromSpanY) + (Math.log(toSpanY) - Math.log(fromSpanY)) * amount),
    MIN_OVERVIEW_VIEWPORT_SPAN,
    MAX_OVERVIEW_VIEWPORT_SPAN,
  );
  const spanX = spanY * aspect;
  const adjustedCenterY = clampCenterY(centerY, spanY);
  return {
    minX: centerX - spanX / 2,
    maxX: centerX + spanX / 2,
    minY: adjustedCenterY - spanY / 2,
    maxY: adjustedCenterY + spanY / 2,
    zoom: clamp(
      Math.floor(Math.log2(Math.max(1, size.width) / (256 * spanX))),
      MIN_TILE_ZOOM,
      MAX_TILE_ZOOM,
    ),
  };
}

function stabilizedTileZoom(previous: number, continuous: number): number {
  let zoom = previous;
  while (zoom < MAX_TILE_ZOOM && continuous >= zoom + 1 + TILE_ZOOM_HYSTERESIS) zoom += 1;
  while (zoom > MIN_TILE_ZOOM && continuous < zoom - TILE_ZOOM_HYSTERESIS) zoom -= 1;
  return clamp(zoom, MIN_TILE_ZOOM, MAX_TILE_ZOOM);
}

function rawViewport(
  journey: CameraJourney,
  progress: number,
  size: RenderSize,
  movement: CameraMovementProfile,
  legs: JourneyLeg[],
): Viewport {
  const current = worldPositionAtProgress(journey, progress);
  const proportionalContextKm = clamp(
    journey.totalDistanceKm * movement.contextFraction,
    movement.minimumContextKm,
    movement.maximumContextKm,
  );
  const leg = movement.legAware ? legAt(legs, current.distanceKm, journey.totalDistanceKm) : null;
  const contextKm = leg?.isTransfer ? leg.endKm - leg.startKm : proportionalContextKm;
  const padding = leg?.isTransfer ? TRANSFER_PADDING : movement.padding;
  const rangeStartKm = leg?.startKm ?? 0;
  const lookaheadLimitKm = leg?.isTransfer ? leg.endKm : journey.totalDistanceKm;
  const tailDistance = Math.max(rangeStartKm, current.distanceKm - contextKm);
  const lookaheadDistance = Math.min(lookaheadLimitKm, current.distanceKm + contextKm);
  const focus = [worldPositionAtDistance(journey, tailDistance).point];
  const startIndex = lowerBound(journey.cumulativeDistanceKm, tailDistance);
  const endIndex = upperBound(journey.cumulativeDistanceKm, lookaheadDistance);
  for (let index = startIndex; index < endIndex; index += 1) {
    if (index >= 0 && index < journey.worldPoints.length) focus.push(journey.worldPoints[index]);
  }
  focus.push(current.point, worldPositionAtDistance(journey, lookaheadDistance).point);

  const centerX = current.point.x;
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const point of focus) {
    const x = unwrapNear(point.x, centerX);
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (point.y < minY) minY = point.y;
    if (point.y > maxY) maxY = point.y;
  }
  const contentSpanX = Math.max(0.00015, maxX - minX);
  const contentSpanY = Math.max(0.00015, maxY - minY);
  const aspect = aspectOf(size);
  const spanY = clamp(
    Math.max(contentSpanY * padding, contentSpanX * padding / aspect),
    movement.minimumViewportSpan,
    MAX_VIEWPORT_SPAN,
  );
  const adjustedCenterY = clampCenterY(current.point.y, spanY);
  return {
    minX: centerX - spanY * aspect / 2,
    maxX: centerX + spanY * aspect / 2,
    minY: adjustedCenterY - spanY / 2,
    maxY: adjustedCenterY + spanY / 2,
    zoom: tileZoom(size, spanY),
  };
}

function frameToViewport(frame: CameraFrame, aspect: number): Viewport {
  const halfY = frame.spanY / 2;
  const halfX = frame.spanY * aspect / 2;
  return {
    minX: frame.centerX - halfX,
    maxX: frame.centerX + halfX,
    minY: frame.centerY - halfY,
    maxY: frame.centerY + halfY,
    zoom: frame.zoom,
  };
}

export function buildCameraTrack(
  journey: CameraJourney,
  size: RenderSize,
  cameraMovement: CameraMovement,
): CameraTrack {
  const aspect = aspectOf(size);
  const movement = MOVEMENT_PROFILES[cameraMovement];
  const legs = buildLegs(journey);
  const rawSamples = Array.from({ length: CAMERA_TRACK_SAMPLES + 1 }, (_, sample) => {
    const progress = sample / CAMERA_TRACK_SAMPLES;
    return {
      viewport: rawViewport(journey, progress, size, movement, legs),
      marker: worldPositionAtProgress(journey, progress).point,
    };
  });
  const fixedSpanY = movement.fixedZoom
    ? rawSamples
      .map((sample) => sample.viewport.maxY - sample.viewport.minY)
      .sort((a, b) => a - b)[Math.floor(CAMERA_TRACK_SAMPLES * FIXED_ZOOM_PERCENTILE)]
    : null;
  const frames: CameraFrame[] = [];
  for (const sample of rawSamples) {
    const raw = sample.viewport;
    const rawCenterX = (raw.minX + raw.maxX) / 2;
    const rawCenterY = (raw.minY + raw.maxY) / 2;
    const rawSpanY = clamp(
      fixedSpanY ?? raw.maxY - raw.minY,
      movement.minimumViewportSpan,
      MAX_VIEWPORT_SPAN,
    );
    const previous = frames.at(-1);
    if (!previous) {
      frames.push({
        centerX: rawCenterX,
        centerY: clampCenterY(rawCenterY, rawSpanY),
        spanY: rawSpanY,
        zoom: tileZoom(size, rawSpanY),
      });
      continue;
    }
    const zoomAlpha = rawSpanY > previous.spanY ? movement.zoomOutAlpha : movement.zoomInAlpha;
    const logSpanChange = (Math.log(rawSpanY) - Math.log(previous.spanY)) * zoomAlpha;
    const limitedLogSpanChange = cameraMovement === 'close-up'
      ? Math.min(logSpanChange, CLOSE_UP_MAX_LOG_ZOOM_OUT)
      : logSpanChange;
    const spanY = movement.fixedZoom
      ? rawSpanY
      : clamp(Math.exp(Math.log(previous.spanY) + limitedLogSpanChange),
        movement.minimumViewportSpan, MAX_VIEWPORT_SPAN);
    const spanX = spanY * aspect;
    const markerX = unwrapNear(sample.marker.x, previous.centerX);
    let centerX = previous.centerX;
    let centerY = previous.centerY;
    const deadHalfX = spanX * CAMERA_DEAD_ZONE_HALF;
    const deadHalfY = spanY * CAMERA_DEAD_ZONE_HALF;
    if (markerX < centerX - deadHalfX) centerX = markerX + deadHalfX;
    else if (markerX > centerX + deadHalfX) centerX = markerX - deadHalfX;
    if (sample.marker.y < centerY - deadHalfY) centerY = sample.marker.y + deadHalfY;
    else if (sample.marker.y > centerY + deadHalfY) centerY = sample.marker.y - deadHalfY;
    centerY = clampCenterY(centerY, spanY);
    const continuousZoom = Math.log2(Math.max(1, size.width) / (256 * spanX));
    frames.push({
      centerX,
      centerY,
      spanY,
      zoom: stabilizedTileZoom(previous.zoom, continuousZoom),
    });
  }
  return { frames, aspect };
}

export function cameraViewportAt(track: CameraTrack, progress: number): Viewport {
  if (track.frames.length === 1) return frameToViewport(track.frames[0], track.aspect);
  const position = clamp(progress, 0, 1) * (track.frames.length - 1);
  const fromIndex = clamp(Math.floor(position), 0, track.frames.length - 1);
  const toIndex = Math.min(fromIndex + 1, track.frames.length - 1);
  const fraction = position - fromIndex;
  const from = track.frames[fromIndex];
  const to = track.frames[toIndex];
  return frameToViewport({
    centerX: from.centerX + (to.centerX - from.centerX) * fraction,
    centerY: from.centerY + (to.centerY - from.centerY) * fraction,
    spanY: Math.exp(Math.log(from.spanY) + (Math.log(to.spanY) - Math.log(from.spanY)) * fraction),
    zoom: fraction < 0.5 ? from.zoom : to.zoom,
  }, track.aspect);
}
