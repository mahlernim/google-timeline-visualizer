import type { TileCache } from './tile-cache';
export interface GeoPoint {
  instant: Date;
  latitude: number;
  longitude: number;
  recordedDate?: string;
  timeZoneMissing?: boolean;
}

export interface MonthOption {
  key: string;
  label: string;
}

export interface WorldPoint {
  x: number;
  y: number;
}

export interface RenderSize {
  width: number;
  height: number;
}

export interface Viewport {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
  zoom: number;
}

export type CameraMovement = 'fixed' | 'steady' | 'dynamic' | 'close-up';

export interface CameraFrame {
  centerX: number;
  centerY: number;
  spanY: number;
  zoom: number;
}

export interface CameraTrack {
  frames: CameraFrame[];
  aspect: number;
}

export interface TimelineFrame {
  journeyProgress: number;
  outroProgress: number;
}

export interface PreparedJourney {
  points: GeoPoint[];
  worldPoints: WorldPoint[];
  overviewRouteSegments: WorldPoint[][];
  cumulativeDistanceKm: number[];
  totalDistanceKm: number;
  /** The format size this journey was prepared for: its tile zooms and aspect ratio follow it. */
  size: RenderSize;
  cameraTrack: CameraTrack;
  overviewViewport: Viewport;
  tiles: Map<string, HTMLImageElement>;
  tileCache?: TileCache<ImageBitmap>;
  /** Selected total duration. Preview and export use the same trail window. */
  durationSeconds?: number;
}
