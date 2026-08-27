import { easeInOutCubic, easeOutCubic } from './animation';
import {
  aspectOf,
  blendViewport,
  buildCameraTrack,
  cameraViewportAt,
  overviewViewport,
  worldPositionAtProgress,
} from './camera';
import { cumulativeDistances, overviewRouteSegments, unwrapJourneyPoints } from './geo';
import type {
  CameraMovement,
  GeoPoint,
  PreparedJourney,
  RenderSize,
  TimelineFrame,
  Viewport,
  WorldPoint,
} from './types';
import { AppError } from './errors';
import { overlayCard, overlayScale } from './overlay';

const TILE_TEMPLATE = 'https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png';
const CARTO_BASEMAP_API_KEY = import.meta.env.VITE_CARTO_BASEMAP_API_KEY?.trim() ?? '';

/**
 * The overlay text of one frame, already resolved by the caller.
 *
 * This module never sees a locale. main.ts formats the period label and falls back to the
 * translated default title, so the renderer holds no user copy and imports nothing from the
 * i18n layer. An export freezes one OverlayText for the whole video, so the text of a finished
 * MP4 can never be half in one language and half in another.
 */
export interface OverlayText {
  /** Already resolved and guaranteed non-empty by the caller. */
  readonly title: string;
  readonly periodLabel: string;
  readonly separator: string;
  readonly formatDistance: (kilometers: number) => string;
}

/**
 * The map attribution is a licensing artifact burned into the MP4, which outlives the app
 * locale, so it is developer-owned and deliberately not translatable. Android now follows the
 * same rule after one locale dropped '© CARTO' altogether. A viewer of an exported video has no
 * way to correct it afterwards, so renderer.test.ts pins both names against that class of loss.
 */
export const MAP_ATTRIBUTION = '© OpenStreetMap contributors © CARTO';

// Route and marker sizes are authored on the same 720 design grid as the overlay,
// so a single scale keeps every stroke proportional at any output size.
const TRAIL_WIDTH = 7.5;           // 5 px at 480
const RECENT_TRAIL_WIDTH = 12;     // 8 px at 480
const OVERVIEW_TRAIL_WIDTH = 5.25; // 3.5 px at 480
const HEAD_RADIUS = 15;            // 10 px at 480
const HEAD_RING_RADIUS = 24;       // 16 px at 480
const HEAD_RING_WIDTH = 7.5;       // 5 px at 480
const HEAD_SHADOW_BLUR = 15;       // 10 px at 480

function worldToCanvas(point: WorldPoint, viewport: Viewport, size: RenderSize): [number, number] {
  return [
    ((point.x - viewport.minX) / (viewport.maxX - viewport.minX)) * size.width,
    ((point.y - viewport.minY) / (viewport.maxY - viewport.minY)) * size.height,
  ];
}

interface TileCoordinate {
  zoom: number;
  x: number;
  y: number;
}

export function cartoTileUrl(
  tile: TileCoordinate,
  apiKey: string = CARTO_BASEMAP_API_KEY,
): string {
  const path = TILE_TEMPLATE.replace('{z}', String(tile.zoom))
    .replace('{x}', String(tile.x))
    .replace('{y}', String(tile.y));
  if (!apiKey) return path;
  const url = new URL(path);
  url.searchParams.set('key', apiKey);
  return url.toString();
}

function tileKey(tile: TileCoordinate): string {
  return `${tile.zoom}/${tile.x}/${tile.y}`;
}

function loadImage(url: string, signal?: AbortSignal): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.crossOrigin = 'anonymous';
    const cleanup = (): void => signal?.removeEventListener('abort', abort);
    const abort = (): void => {
      image.src = '';
      cleanup();
      reject(new DOMException('Video creation was cancelled.', 'AbortError'));
    };
    image.onload = () => {
      cleanup();
      resolve(image);
    };
    image.onerror = () => {
      cleanup();
      reject(new Error('Could not load a map tile.'));
    };
    if (signal?.aborted) {
      abort();
      return;
    }
    signal?.addEventListener('abort', abort, { once: true });
    image.src = url;
  });
}

export function requiredTiles(viewport: Viewport): TileCoordinate[] {
  const tileCount = 2 ** viewport.zoom;
  const minTileX = Math.floor(viewport.minX * tileCount);
  const maxTileX = Math.floor(viewport.maxX * tileCount);
  const minTileY = Math.max(0, Math.floor(viewport.minY * tileCount));
  const maxTileY = Math.min(tileCount - 1, Math.floor(viewport.maxY * tileCount));
  const tiles: TileCoordinate[] = [];
  for (let tileX = minTileX; tileX <= maxTileX; tileX += 1) {
    for (let tileY = minTileY; tileY <= maxTileY; tileY += 1) {
      tiles.push({
        zoom: viewport.zoom,
        x: ((tileX % tileCount) + tileCount) % tileCount,
        y: tileY,
      });
    }
  }
  return tiles;
}

function drawMapBackground(
  canvas: HTMLCanvasElement,
  viewport: Viewport,
  tiles: Map<string, HTMLImageElement>,
): void {
  const context = canvas.getContext('2d');
  if (!context) throw new AppError('errorCanvasUnavailable', 'Canvas rendering is unavailable.');
  const size: RenderSize = { width: canvas.width, height: canvas.height };
  context.fillStyle = '#f2edf0';
  context.fillRect(0, 0, size.width, size.height);

  const tileCount = 2 ** viewport.zoom;
  const minTileX = Math.floor(viewport.minX * tileCount);
  const maxTileX = Math.floor(viewport.maxX * tileCount);
  const minTileY = Math.max(0, Math.floor(viewport.minY * tileCount));
  const maxTileY = Math.min(tileCount - 1, Math.floor(viewport.maxY * tileCount));

  for (let tileX = minTileX; tileX <= maxTileX; tileX += 1) {
    for (let tileY = minTileY; tileY <= maxTileY; tileY += 1) {
      const wrappedX = ((tileX % tileCount) + tileCount) % tileCount;
      const image = tiles.get(tileKey({ zoom: viewport.zoom, x: wrappedX, y: tileY }));
      if (!image) continue;
      const worldX = tileX / tileCount;
      const worldY = tileY / tileCount;
      const [left, top] = worldToCanvas({ x: worldX, y: worldY }, viewport, size);
      const width = (1 / tileCount / (viewport.maxX - viewport.minX)) * size.width;
      const height = (1 / tileCount / (viewport.maxY - viewport.minY)) * size.height;
      context.drawImage(image, left, top, width, height);
    }
  }
}

async function loadRequiredTiles(
  coordinates: TileCoordinate[],
  signal?: AbortSignal,
  onProgress?: (completed: number, total: number) => void,
): Promise<Map<string, HTMLImageElement>> {
  const tiles = new Map<string, HTMLImageElement>();
  let nextIndex = 0;
  let completed = 0;
  const worker = async (): Promise<void> => {
    while (nextIndex < coordinates.length) {
      if (signal?.aborted) throw new DOMException('Video creation was cancelled.', 'AbortError');
      const coordinate = coordinates[nextIndex];
      nextIndex += 1;
      const url = cartoTileUrl(coordinate);
      try {
        tiles.set(tileKey(coordinate), await loadImage(url, signal));
      } catch (error) {
        if (signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')) throw error;
      }
      completed += 1;
      onProgress?.(completed, coordinates.length);
    }
  };
  await Promise.all(Array.from({ length: Math.min(6, coordinates.length) }, worker));
  return tiles;
}

export async function prepareJourney(
  points: GeoPoint[],
  size: RenderSize = { width: 480, height: 480 },
  cameraMovement: CameraMovement = 'steady',
  durationSeconds = 30,
  signal?: AbortSignal,
  onProgress?: (completed: number, total: number) => void,
): Promise<PreparedJourney> {
  if (points.length < 2) {
    throw new AppError('errorTooFewPoints', 'Select a period containing at least two location points.');
  }
  const worldPoints = unwrapJourneyPoints(points);
  const distances = cumulativeDistances(points);
  const journey = {
    points,
    worldPoints,
    cumulativeDistanceKm: distances,
    totalDistanceKm: distances.at(-1) ?? 0,
  };
  const cameraTrack = buildCameraTrack(journey, size, cameraMovement);
  const overviewSegments = overviewRouteSegments(worldPoints);
  const endingOverview = overviewViewport(
    { ...journey, worldPoints: overviewSegments.flat() },
    size,
  );
  const sampleCount = Math.max(
    20,
    Math.min(durationSeconds * 8, Math.max(durationSeconds * 2, Math.ceil(journey.totalDistanceKm / 250))),
  );
  const required = new Map<string, TileCoordinate>();
  for (let sample = 0; sample <= sampleCount; sample += 1) {
    for (const tile of requiredTiles(cameraViewportAt(cameraTrack, sample / sampleCount))) {
      required.set(tileKey(tile), tile);
    }
  }
  const journeyEnd = cameraViewportAt(cameraTrack, 1);
  for (let sample = 0; sample <= 12; sample += 1) {
    const ending = blendViewport(journeyEnd, endingOverview, easeOutCubic(sample / 12), size);
    for (const tile of requiredTiles(ending)) required.set(tileKey(tile), tile);
  }
  const tiles = await loadRequiredTiles([...required.values()], signal, onProgress);
  return {
    ...journey,
    overviewRouteSegments: overviewSegments,
    size,
    cameraTrack,
    overviewViewport: endingOverview,
    tiles,
  };
}

function pointAtProgress(journey: PreparedJourney, progress: number): { point: WorldPoint; completedIndex: number } {
  const position = worldPositionAtProgress(journey, progress);
  return { point: position.point, completedIndex: position.fromIndex };
}

function strokeRoute(
  context: CanvasRenderingContext2D,
  points: WorldPoint[],
  head: WorldPoint,
  viewport: Viewport,
  size: RenderSize,
): void {
  if (points.length === 0) return;
  context.beginPath();
  points.forEach((point, index) => {
    const [x, y] = worldToCanvas(point, viewport, size);
    if (index === 0) context.moveTo(x, y);
    else context.lineTo(x, y);
  });
  const [headX, headY] = worldToCanvas(head, viewport, size);
  context.lineTo(headX, headY);
  context.stroke();
}

/**
 * The preview canvas is the display's pixel size capped at the format size, so it is
 * proportionally smaller than the prepared size instead of equal to it. An integer backing
 * store cannot hit the format ratio exactly; the widest drift over every reachable device
 * width is 0.20 percent and the analytic bound is 0.33 percent, while the closest pair of
 * format ratios, 1 and 0.5625, is 44 percent apart.
 */
export const ASPECT_EPSILON = 0.01;

/**
 * The preview never draws a short edge below this many device pixels: below it the integer
 * backing store drifts too far from the format ratio to stay inside ASPECT_EPSILON.
 */
export const MIN_PREVIEW_SHORT_EDGE = 240;

/**
 * The backing store for the animated preview: the display's real pixel size, capped at the
 * format size so the preview is never sharper than the video it previews, and floored so the
 * short edge keeps at least MIN_PREVIEW_SHORT_EDGE device pixels. A width that cannot be
 * measured and a device pixel ratio that is not a positive finite number both degrade to the
 * exact format size, which is always correct and only ever costs pixels.
 */
export function previewCanvasSize(
  format: RenderSize,
  cssWidth: number,
  devicePixelRatio: number,
): RenderSize {
  const ratio = Number.isFinite(devicePixelRatio) && devicePixelRatio > 0 ? devicePixelRatio : 1;
  const deviceWidth = Number.isFinite(cssWidth) && cssWidth > 0 ? cssWidth * ratio : format.width;
  const minimumScale = Math.min(1, MIN_PREVIEW_SHORT_EDGE / Math.min(format.width, format.height));
  const scale = Math.min(1, Math.max(minimumScale, deviceWidth / format.width));
  return {
    width: Math.round(format.width * scale),
    height: Math.round(format.height * scale),
  };
}

export function drawFrame(
  canvas: HTMLCanvasElement,
  journey: PreparedJourney,
  frame: TimelineFrame,
  text: OverlayText,
): void {
  const context = canvas.getContext('2d');
  if (!context) throw new AppError('errorCanvasUnavailable', 'Canvas rendering is unavailable.');
  const size: RenderSize = { width: canvas.width, height: canvas.height };
  // The preview draws the prepared journey on a proportionally smaller canvas, which is the
  // same picture under a uniform scale, so only the ratio has to match. Absolute size is no
  // longer checked here: the export path is held to the format size by createJourneyMp4.
  const preparedAspect = aspectOf(journey.size);
  if (Math.abs(aspectOf(size) - preparedAspect) > preparedAspect * ASPECT_EPSILON) {
    throw new AppError('errorAspectRatio', 'The prepared journey does not match the canvas aspect ratio.');
  }
  const scale = overlayScale(size);
  context.clearRect(0, 0, size.width, size.height);
  const journeyViewport = cameraViewportAt(journey.cameraTrack, frame.journeyProgress);
  // The prepared size, never the canvas size: blendViewport derives the integer tile zoom from
  // the width it is given, and prepareJourney downloaded the outro tiles at the prepared zoom.
  // A smaller preview canvas would ask journey.tiles for keys that were never fetched, and the
  // whole map would fall back to the empty background for the length of the outro.
  const viewport = frame.outroProgress <= 0
    ? journeyViewport
    : blendViewport(
      journeyViewport,
      journey.overviewViewport,
      easeOutCubic(frame.outroProgress),
      journey.size,
    );
  drawMapBackground(canvas, viewport, journey.tiles);

  const current = pointAtProgress(journey, frame.journeyProgress);
  context.lineCap = 'round';
  context.lineJoin = 'round';
  const activeAlpha = 1 - easeOutCubic(frame.outroProgress);
  context.save();
  context.globalAlpha = activeAlpha;
  const traveled = journey.worldPoints.slice(0, current.completedIndex + 1);
  context.strokeStyle = 'rgba(233, 0, 100, 0.34)';
  context.lineWidth = TRAIL_WIDTH * scale;
  strokeRoute(context, traveled, current.point, viewport, size);

  const currentDistance = journey.totalDistanceKm * Math.max(0, Math.min(1, frame.journeyProgress));
  const recentStartDistance = Math.max(0, currentDistance - Math.max(80, journey.totalDistanceKm * 0.16));
  const recentStartIndex = Math.max(
    0,
    journey.cumulativeDistanceKm.findIndex((distance) => distance >= recentStartDistance),
  );
  context.strokeStyle = '#e90064';
  context.lineWidth = RECENT_TRAIL_WIDTH * scale;
  strokeRoute(
    context,
    journey.worldPoints.slice(recentStartIndex, current.completedIndex + 1),
    current.point,
    viewport,
    size,
  );
  const [headX, headY] = worldToCanvas(current.point, viewport, size);

  context.shadowColor = 'rgba(36, 25, 29, 0.35)';
  context.shadowBlur = HEAD_SHADOW_BLUR * scale;
  context.fillStyle = '#24191d';
  context.beginPath();
  context.arc(headX, headY, HEAD_RADIUS * scale, 0, Math.PI * 2);
  context.fill();
  context.shadowBlur = 0;
  context.strokeStyle = '#e90064';
  context.lineWidth = HEAD_RING_WIDTH * scale;
  context.beginPath();
  context.arc(headX, headY, HEAD_RING_RADIUS * scale, 0, Math.PI * 2);
  context.stroke();
  context.restore();

  if (frame.outroProgress > 0) {
    context.save();
    context.globalAlpha = (190 / 255) * easeInOutCubic(frame.outroProgress);
    context.strokeStyle = '#e90064';
    context.lineWidth = OVERVIEW_TRAIL_WIDTH * scale;
    for (const segment of journey.overviewRouteSegments) {
      strokeRoute(
        context,
        segment.slice(0, -1),
        segment.at(-1) ?? current.point,
        viewport,
        size,
      );
    }
    context.restore();
  }

  const card = overlayCard(size);
  context.fillStyle = 'rgba(255, 248, 250, 0.86)';
  context.beginPath();
  context.roundRect(card.left, card.top, card.width, card.bottom - card.top, 24 * scale);
  context.fill();
  context.textAlign = 'center';
  context.fillStyle = '#24191d';
  context.font = `700 ${34 * scale}px -apple-system, BlinkMacSystemFont, sans-serif`;
  context.fillText(text.title, card.centerX, 72 * scale, card.width - 36 * scale);
  context.fillStyle = '#5c4b52';
  context.font = `${20 * scale}px -apple-system, BlinkMacSystemFont, sans-serif`;
  const distanceLabel = text.formatDistance(currentDistance);
  context.fillText(
    `${text.periodLabel}${text.separator}${distanceLabel}`,
    card.centerX,
    108 * scale,
    card.width - 36 * scale,
  );

  context.textAlign = 'right';
  context.fillStyle = 'rgba(36, 25, 29, 0.78)';
  context.font = `${13 * scale}px -apple-system, BlinkMacSystemFont, sans-serif`;
  context.fillText(
    MAP_ATTRIBUTION,
    size.width - 12 * scale,
    size.height - 12 * scale,
  );
}
