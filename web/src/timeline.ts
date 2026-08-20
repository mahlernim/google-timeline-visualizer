import type { GeoPoint, MonthOption } from './types';
import { haversineKm } from './geo';

export type TimelineParseReason =
  | 'malformed-json'
  | 'legacy-format'
  | 'raw-signals-only'
  | 'unsupported-format'
  | 'no-usable-locations';

export class TimelineParseError extends Error {
  constructor(
    public readonly reason: TimelineParseReason,
    message: string,
  ) {
    super(message);
    this.name = 'TimelineParseError';
  }
}

type JsonObject = Record<string, unknown>;

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function parseCoordinate(value: unknown): [number, number] | null {
  if (isObject(value)) {
    return parseCoordinate(value.latLng ?? value.point);
  }
  if (typeof value !== 'string' || value.trim() === '') return null;

  const cleaned = value
    .trim()
    .replace(/^geo:/, '')
    .split('?', 1)[0]
    .replaceAll('°', '')
    .replaceAll(' ', '');
  const parts = cleaned.split(',');
  if (parts.length < 2) return null;

  let latitude = Number(parts[0]);
  let longitude = Number(parts[1]);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  if (Math.abs(latitude) > 1_000_000 || Math.abs(longitude) > 1_000_000) {
    latitude /= 10_000_000;
    longitude /= 10_000_000;
  }
  if (latitude < -85.05112878 || latitude > 85.05112878 || longitude < -180 || longitude > 180) {
    return null;
  }
  return [latitude, longitude];
}

interface ParsedInstant {
  instant: Date;
  recordedDate?: string;
  timeZoneMissing: boolean;
}

interface ParsedTimelineSegment {
  anchor: Date | null;
  points: GeoPoint[];
  standalonePath: boolean;
}

interface TimeInterval {
  start: number;
  end: number;
}

const SEGMENT_DIRECTION_SIGNAL_MS = 36 * 60 * 60 * 1000;
const DEFAULT_RAW_ACCURACY_METERS = 100;

function parseInstant(value: unknown): ParsedInstant | null {
  if (typeof value !== 'string' || value.trim() === '') return null;
  const raw = value.trim();
  const timeZoneMissing = !/(?:z|[+-]\d{2}:?\d{2})$/i.test(raw);
  const instant = new Date(timeZoneMissing ? `${raw}Z` : raw);
  if (Number.isNaN(instant.getTime())) return null;
  return {
    instant,
    recordedDate: timeZoneMissing && /^\d{4}-\d{2}-\d{2}/.test(raw) ? raw.slice(0, 10) : undefined,
    timeZoneMissing,
  };
}

function parseOffsetMinutes(value: unknown): number | null {
  if (typeof value !== 'number' && typeof value !== 'string') return null;
  const parsed = typeof value === 'number' ? value : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) return null;
  return parsed;
}

function parseOffsetInstant(startValue: unknown, endValue: unknown, offsetValue: unknown): Date | null {
  const start = parseInstant(startValue);
  const offsetMinutes = parseOffsetMinutes(offsetValue);
  if (!start || offsetMinutes === null) return null;
  const timestamp = start.instant.getTime() + offsetMinutes * 60_000;
  if (!Number.isSafeInteger(timestamp)) return null;
  const instant = new Date(timestamp);
  if (Number.isNaN(instant.getTime())) return null;
  const end = parseInstant(endValue);
  if (
    end
    && !start.timeZoneMissing
    && !end.timeZoneMissing
    && instant.getTime() > end.instant.getTime() + 60_000
  ) return null;
  return instant;
}

function addPoint(output: GeoPoint[], time: unknown, coordinate: unknown): boolean {
  const parsedTime = parseInstant(time);
  const parsed = parseCoordinate(coordinate);
  if (!parsedTime || !parsed) return false;
  output.push({
    instant: parsedTime.instant,
    latitude: parsed[0],
    longitude: parsed[1],
    recordedDate: parsedTime.recordedDate,
    timeZoneMissing: parsedTime.timeZoneMissing,
  });
  return true;
}

function semanticInterval(startValue: unknown, endValue: unknown): TimeInterval | null {
  const start = parseInstant(startValue);
  const end = parseInstant(endValue);
  if (!start || !end || start.timeZoneMissing || end.timeZoneMissing) return null;
  const startMillis = start.instant.getTime();
  const endMillis = end.instant.getTime();
  return endMillis >= startMillis ? { start: startMillis, end: endMillis } : null;
}

function mergeIntervals(intervals: TimeInterval[]): TimeInterval[] {
  const sorted = [...intervals].sort((a, b) => a.start - b.start);
  const merged: TimeInterval[] = [];
  for (const interval of sorted) {
    const previous = merged.at(-1);
    if (!previous || interval.start > previous.end) {
      merged.push({ ...interval });
    } else if (interval.end > previous.end) {
      previous.end = interval.end;
    }
  }
  return merged;
}

function isCovered(point: GeoPoint, intervals: TimeInterval[]): boolean {
  if (point.timeZoneMissing) return false;
  const timestamp = point.instant.getTime();
  let low = 0;
  let high = intervals.length - 1;
  while (low <= high) {
    const middle = Math.floor((low + high) / 2);
    const interval = intervals[middle];
    if (timestamp < interval.start) high = middle - 1;
    else if (timestamp > interval.end) low = middle + 1;
    else return true;
  }
  return false;
}

function normalizeSegmentDirection(segments: ParsedTimelineSegment[]): ParsedTimelineSegment[] {
  const anchors = segments
    .map((segment) => segment.anchor?.getTime())
    .filter((timestamp): timestamp is number => timestamp !== undefined);
  let ascendingSignals = 0;
  let descendingSignals = 0;
  for (let index = 1; index < anchors.length; index += 1) {
    const delta = anchors[index] - anchors[index - 1];
    if (Math.abs(delta) < SEGMENT_DIRECTION_SIGNAL_MS) continue;
    if (delta > 0) ascendingSignals += 1;
    else descendingSignals += 1;
  }
  const endpointDelta = (anchors.at(-1) ?? 0) - (anchors[0] ?? 0);
  if (Math.abs(endpointDelta) >= SEGMENT_DIRECTION_SIGNAL_MS) {
    if (endpointDelta > 0) ascendingSignals += 2;
    else descendingSignals += 2;
  }
  return descendingSignals > ascendingSignals ? [...segments].reverse() : segments;
}

export function parseTimelineJson(data: unknown): GeoPoint[] {
  let segments: unknown[];
  if (Array.isArray(data)) {
    segments = data;
  } else if (isObject(data) && Array.isArray(data.semanticSegments)) {
    segments = data.semanticSegments;
  } else if (isObject(data) && ('timelineObjects' in data || 'locations' in data)) {
    throw new TimelineParseError(
      'legacy-format',
      'This is an older Google Takeout format. Export Timeline data from your phone instead.',
    );
  } else if (isObject(data) && 'rawSignals' in data) {
    throw new TimelineParseError(
      'raw-signals-only',
      'This export contains raw signals but no reconstructed Timeline journeys.',
    );
  } else {
    throw new TimelineParseError(
      'unsupported-format',
      'Timeline JSON must be an array or contain semanticSegments.',
    );
  }

  const parsedSegments: ParsedTimelineSegment[] = [];
  const semanticIntervals: TimeInterval[] = [];
  for (const rawSegment of segments) {
    if (!isObject(rawSegment)) continue;
    const startTime = rawSegment.startTime;
    const endTime = rawSegment.endTime;
    const segmentPoints: GeoPoint[] = [];
    let semanticPointAdded = false;
    const hasPath = Array.isArray(rawSegment.timelinePath);

    if (isObject(rawSegment.activity)) {
      semanticPointAdded = addPoint(segmentPoints, startTime, rawSegment.activity.start) || semanticPointAdded;
    }

    if (isObject(rawSegment.visit) && isObject(rawSegment.visit.topCandidate)) {
      semanticPointAdded = addPoint(segmentPoints, startTime, rawSegment.visit.topCandidate.placeLocation)
        || semanticPointAdded;
    }

    if (Array.isArray(rawSegment.timelinePath)) {
      for (const rawPathPoint of rawSegment.timelinePath) {
        if (!isObject(rawPathPoint)) continue;
        const absolute = parseInstant(rawPathPoint.time);
        const offsetInstant = parseOffsetInstant(startTime, endTime, rawPathPoint.durationMinutesOffsetFromStartTime);
        const coordinate = parseCoordinate(rawPathPoint.point);
        if ((absolute || offsetInstant) && coordinate) {
          const segmentStart = absolute ? null : parseInstant(startTime);
          segmentPoints.push({
            instant: absolute?.instant ?? offsetInstant!,
            latitude: coordinate[0],
            longitude: coordinate[1],
            recordedDate: absolute?.recordedDate
              ?? (segmentStart?.timeZoneMissing ? offsetInstant?.toISOString().slice(0, 10) : undefined),
            timeZoneMissing: absolute?.timeZoneMissing ?? segmentStart?.timeZoneMissing ?? false,
          });
        }
      }
    }

    if (isObject(rawSegment.activity)) {
      semanticPointAdded = addPoint(segmentPoints, endTime, rawSegment.activity.end) || semanticPointAdded;
    }
    if (segmentPoints.length > 0) {
      if (semanticPointAdded) {
        const interval = semanticInterval(startTime, endTime);
        if (interval) semanticIntervals.push(interval);
      }
      parsedSegments.push({
        anchor: parseInstant(startTime)?.instant ?? segmentPoints[0].instant,
        points: segmentPoints,
        standalonePath: hasPath && !semanticPointAdded,
      });
    }
  }

  const coverage = mergeIntervals(semanticIntervals);
  // Direct-array exports can append a second path-only history for periods already
  // represented by activities and visits. Same-segment path detail remains canonical.
  const points = normalizeSegmentDirection(parsedSegments).flatMap((segment) => (
    segment.standalonePath
      ? segment.points.filter((point) => !isCovered(point, coverage))
      : segment.points
  ));
  const unique = new Map<string, GeoPoint>();
  for (const point of points) {
    const key = `${point.instant.getTime()}:${point.latitude}:${point.longitude}`;
    unique.set(key, point);
  }
  const deduplicated = [...unique.values()];
  const normalized = deduplicated.some((point) => point.timeZoneMissing)
    ? deduplicated
    : deduplicated.sort((a, b) => a.instant.getTime() - b.instant.getTime());
  if (normalized.length === 0) {
    throw new TimelineParseError(
      'no-usable-locations',
      'This Timeline export contains no usable location points.',
    );
  }
  return normalized;
}

export interface RawSignalPoint extends GeoPoint {
  accuracyMeters: number;
}

export interface RawSignalProcessingResult {
  points: GeoPoint[];
  inputCount: number;
  rejectedCount: number;
  discontinuityCount: number;
}

export function parseRawSignalsJson(data: unknown): RawSignalPoint[] {
  if (!isObject(data) || !Array.isArray(data.rawSignals)) return [];
  const unique = new Map<string, RawSignalPoint>();
  for (const signal of data.rawSignals) {
    if (!isObject(signal) || !isObject(signal.position)) continue;
    const position = signal.position;
    const coordinate = parseCoordinate(position.LatLng ?? position.latLng);
    const parsedTime = parseInstant(position.timestamp);
    const accuracy = typeof position.accuracyMeters === 'string'
      ? Number(position.accuracyMeters)
      : position.accuracyMeters;
    if (!coordinate || !parsedTime || typeof accuracy !== 'number' || !Number.isFinite(accuracy) || accuracy < 0) {
      continue;
    }
    const point: RawSignalPoint = {
      instant: parsedTime.instant,
      latitude: coordinate[0],
      longitude: coordinate[1],
      recordedDate: parsedTime.recordedDate,
      timeZoneMissing: parsedTime.timeZoneMissing,
      accuracyMeters: accuracy,
    };
    const key = `${point.instant.getTime()}:${point.latitude}:${point.longitude}`;
    const previous = unique.get(key);
    if (!previous || point.accuracyMeters < previous.accuracyMeters) unique.set(key, point);
  }
  return [...unique.values()].sort((a, b) => a.instant.getTime() - b.instant.getTime());
}

export function processRawSignals(
  source: RawSignalPoint[],
  maximumAccuracyMeters: number | null = DEFAULT_RAW_ACCURACY_METERS,
): RawSignalProcessingResult {
  const accuracyFiltered = source.filter((point) => (
    maximumAccuracyMeters === null || point.accuracyMeters <= maximumAccuracyMeters
  ));
  const withoutSpikes: RawSignalPoint[] = [];
  if (accuracyFiltered.length > 0) withoutSpikes.push(accuracyFiltered[0]);
  for (let index = 1; index < accuracyFiltered.length - 1; index += 1) {
    const before = withoutSpikes.at(-1)!;
    const candidate = accuracyFiltered[index];
    const after = accuracyFiltered[index + 1];
    if (!isShortImpossibleRawSpike(before, candidate, after)) withoutSpikes.push(candidate);
  }
  if (accuracyFiltered.length > 1) withoutSpikes.push(accuracyFiltered.at(-1)!);

  const stabilized: RawSignalPoint[] = [];
  for (const candidate of withoutSpikes) {
    const previous = stabilized.at(-1);
    if (!previous) {
      stabilized.push(candidate);
      continue;
    }
    const elapsed = candidate.instant.getTime() - previous.instant.getTime();
    const uncertaintyKm = Math.max(0.025, (previous.accuracyMeters + candidate.accuracyMeters) / 1000);
    const overlapsWithinUncertainty = elapsed >= 0 && elapsed <= 10 * 60 * 1000
      && haversineKm(previous, candidate) <= uncertaintyKm;
    if (overlapsWithinUncertainty) {
      if (candidate.accuracyMeters < previous.accuracyMeters) stabilized[stabilized.length - 1] = candidate;
    } else {
      stabilized.push(candidate);
    }
  }
  const discontinuityCount = stabilized.slice(1).filter((point, index) => (
    point.instant.getTime() - stabilized[index].instant.getTime() > 30 * 60 * 1000
  )).length;
  return {
    points: stabilized,
    inputCount: source.length,
    rejectedCount: source.length - stabilized.length,
    discontinuityCount,
  };
}

function isShortImpossibleRawSpike(
  before: RawSignalPoint,
  candidate: RawSignalPoint,
  after: RawSignalPoint,
): boolean {
  const windowMs = after.instant.getTime() - before.instant.getTime();
  if (windowMs < 0 || windowMs > 20 * 60 * 1000) return false;
  const rejoinToleranceKm = Math.max(0.2, (before.accuracyMeters + after.accuracyMeters) * 2 / 1000);
  if (haversineKm(before, after) > rejoinToleranceKm) return false;
  const ingressKm = haversineKm(before, candidate);
  const egressKm = haversineKm(candidate, after);
  const minimumSpikeKm = Math.max(0.5, candidate.accuracyMeters * 5 / 1000);
  if (ingressKm < minimumSpikeKm || egressKm < minimumSpikeKm) return false;
  const ingressHours = (candidate.instant.getTime() - before.instant.getTime()) / 3_600_000;
  const egressHours = (after.instant.getTime() - candidate.instant.getTime()) / 3_600_000;
  if (ingressHours <= 0 || egressHours <= 0) return true;
  return ingressKm / ingressHours > 250 && egressKm / egressHours > 250;
}

export function monthKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

export function pointDateKey(point: GeoPoint): string {
  return point.recordedDate ?? localDateKey(point.instant);
}

function pointMonthKey(point: GeoPoint): string {
  return pointDateKey(point).slice(0, 7);
}

export function availableMonths(points: GeoPoint[]): MonthOption[] {
  const formatter = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' });
  const keys = [...new Set(points.map(pointMonthKey))].sort();
  return keys.map((key) => {
    const [year, month] = key.split('-').map(Number);
    return { key, label: formatter.format(new Date(year, month - 1, 1)) };
  });
}

export function selectRange(points: GeoPoint[], startMonth: string, endMonth: string): GeoPoint[] {
  return points.filter((point) => {
    const key = pointMonthKey(point);
    return key >= startMonth && key <= endMonth;
  });
}

export function localDateKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function selectDateRange(points: GeoPoint[], startDate: string, endDate: string): GeoPoint[] {
  return points.filter((point) => {
    const key = pointDateKey(point);
    return key >= startDate && key <= endDate;
  });
}
