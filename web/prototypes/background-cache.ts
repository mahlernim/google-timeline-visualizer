import type { RenderSize, Viewport } from '../src/types';

export const OVERSCAN = 1.5;
export const MAX_SCALE_CHANGE = 0.02;

export function expandedViewport(view: Viewport): Viewport {
  const dx = (view.maxX - view.minX) * (OVERSCAN - 1) / 2;
  const dy = (view.maxY - view.minY) * (OVERSCAN - 1) / 2;
  return { ...view, minX: view.minX - dx, maxX: view.maxX + dx, minY: view.minY - dy, maxY: view.maxY + dy };
}

/** No longitude wrapping here. Camera tracks may cross the date line continuously. */
export function reusableBackground(cached: Viewport, view: Viewport): boolean {
  const scale = (cached.maxX - cached.minX) / OVERSCAN / (view.maxX - view.minX);
  return Math.abs(scale - 1) <= MAX_SCALE_CHANGE
    && view.minX >= cached.minX && view.maxX <= cached.maxX
    && view.minY >= cached.minY && view.maxY <= cached.maxY;
}

export function cropRectangle(cached: Viewport, view: Viewport, size: RenderSize) {
  return {
    x: (view.minX - cached.minX) / (cached.maxX - cached.minX) * size.width,
    y: (view.minY - cached.minY) / (cached.maxY - cached.minY) * size.height,
    width: (view.maxX - view.minX) / (cached.maxX - cached.minX) * size.width,
    height: (view.maxY - view.minY) / (cached.maxY - cached.minY) * size.height,
  };
}
