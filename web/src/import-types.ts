import type { GeoPoint } from './types';
import type { LocationFilterMode } from './outlier';

export interface TimelineScan {
  months: string[];
  firstDate: string;
  lastDate: string;
  rawMonths: string[];
  rawFirstDate: string;
  rawLastDate: string;
  hasSemantic: boolean;
  hasRaw: boolean;
  timezoneMissing: boolean;
}
export interface RangeRequest {
  start: string;
  end: string;
  raw: boolean;
  accuracy: number | null;
  filter: LocationFilterMode;
}
export interface ImportResult {
  points: GeoPoint[];
  rejected: number;
}
export type ImportRequest = { file: Blob; range?: RangeRequest };
export type ImportResponse =
  | { kind: 'progress'; fraction: number }
  | { kind: 'scan'; scan: TimelineScan }
  | { kind: 'range'; result: ImportResult }
  | { kind: 'error'; code: string };
export const MAX_SELECTED_POINTS = 100_000;
export class ImportError extends Error {
  constructor(public readonly code: string) { super(code); }
}
