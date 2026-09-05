import { JSONParser, TokenType } from '@streamparser/json';
import { filterLocationOutliers } from './outlier';
import {
  parseTimelineSegment, reconcileTimelineSegments, parseRawSignalsJson,
  pointDateKey, TimelineParseError,
} from './timeline';
import type { ParsedTimelineSegment, TimeInterval } from './timeline';
import { RawSignalStream, RawBatch, compareRaw } from './raw-stream';
import type { IndexedRawPoint } from './raw-stream';
import type { GeoPoint } from './types';
import { ImportError, MAX_SELECTED_POINTS } from './import-types';
import type { TimelineScan, RangeRequest, ImportResult } from './import-types';

const CHUNK_BYTES = 64 * 1024;
// Bounds unusually large single records and strings as well as the selected route.
const MAX_RECORD_BYTES = 16 * 1024 * 1024;
type RecordKind = 'semantic' | 'raw';

async function records(
  file: Blob,
  receive: (kind: RecordKind, value: unknown) => void,
  signal?: AbortSignal,
  progress?: (fraction: number) => void,
): Promise<void> {
  let parser: JSONParser | undefined;
  let rootArray = false;
  let depth = 0;
  let rootKey = '';
  let rootExpectsKey = false;
  let offset = 0;
  let lastRecordOffset = 0;
  let supported = false;
  let legacy = false;
  try {
    for (let position = 0; position < file.size; position += CHUNK_BYTES) {
      signal?.throwIfAborted();
      const chunk = new Uint8Array(await file.slice(position, position + CHUNK_BYTES).arrayBuffer());
      if (!parser) {
        const first = chunk.find((byte) => ![9, 10, 13, 32].includes(byte));
        if (first === undefined) { progress?.(Math.min(1, (position + chunk.length) / file.size)); continue; }
        rootArray = first === 91;
        if (!rootArray && first !== 123) throw new ImportError('unsupported-format');
        supported = rootArray;
        parser = new JSONParser({
          paths: rootArray ? ['$.*'] : ['$.semanticSegments.*', '$.rawSignals.*'],
          keepStack: false,
          stringBufferSize: 64 * 1024,
        });
        parser.onToken = ({ token, value, offset: at }) => {
          offset = at;
          if (!rootArray && depth === 1) {
            if (token === TokenType.STRING && rootExpectsKey) { rootKey = String(value); rootExpectsKey = false; }
            if (token === TokenType.COMMA) rootExpectsKey = true;
            if (token === TokenType.COLON && ['locations', 'timelineObjects'].includes(rootKey)) {
              legacy = true;
            }
            if (token === TokenType.LEFT_BRACKET && ['semanticSegments', 'rawSignals'].includes(rootKey)) supported = true;
          }
          if (token === TokenType.LEFT_BRACE || token === TokenType.LEFT_BRACKET) {
            depth += 1;
            if (depth === 1 && !rootArray) rootExpectsKey = true;
          }
          if (token === TokenType.RIGHT_BRACE || token === TokenType.RIGHT_BRACKET) depth -= 1;
        };
        parser.onValue = ({ value, stack }) => {
          lastRecordOffset = offset;
          const kind = rootArray || stack.at(-1)?.key === 'semanticSegments' ? 'semantic' : 'raw';
          receive(kind, value);
        };
      }
      if (position - lastRecordOffset > MAX_RECORD_BYTES) throw new ImportError('rangeTooLarge');
      parser.write(chunk);
      progress?.(Math.min(1, (position + chunk.length) / file.size));
    }
    signal?.throwIfAborted();
    if (!parser) throw new ImportError('malformed-json');
    if (!parser.isEnded) parser.end();
    if (!supported) throw new ImportError(legacy ? 'legacy-format' : 'unsupported-format');
  } catch (error) {
    if (error instanceof ImportError || error instanceof TimelineParseError
      || (error instanceof DOMException && error.name === 'AbortError')) throw error;
    throw new ImportError('malformed-json');
  }
}

export async function scanTimeline(
  file: Blob, signal?: AbortSignal, progress?: (fraction: number) => void,
): Promise<TimelineScan> {
  const dates = { first: '', last: '' };
  const months = new Set<string>();
  const rawMonths = new Set<string>();
  const rawDates = { first: '', last: '' };
  let hasSemantic = false;
  let hasRaw = false;
  let timezoneMissing = false;
  await records(file, (kind, record) => {
    const points = kind === 'semantic'
      ? parseTimelineSegment(record)?.points ?? []
      : parseRawSignalsJson({ rawSignals: [record] });
    if (kind === 'semantic' && points.length) hasSemantic = true;
    if (kind === 'raw' && points.length) hasRaw = true;
    for (const point of points) {
      const date = pointDateKey(point);
      const target = kind === 'semantic' ? dates : rawDates;
      if (!target.first || date < target.first) target.first = date;
      if (date > target.last) target.last = date;
      (kind === 'semantic' ? months : rawMonths).add(date.slice(0, 7));
      timezoneMissing ||= point.timeZoneMissing === true;
    }
  }, signal, progress);
  if (!dates.first && !rawDates.first) throw new ImportError('no-usable-locations');
  return { months: [...(hasSemantic ? months : rawMonths)].sort(), firstDate: dates.first || rawDates.first, lastDate: dates.last || rawDates.last, rawMonths: [...rawMonths].sort(), rawFirstDate: rawDates.first, rawLastDate: rawDates.last, hasSemantic, hasRaw, timezoneMissing };
}

function adjacentDate(value: string, days: number): string {
  const date = new Date(value + 'T12:00:00Z');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** Carries the full-file ordering signal without retaining full-file coordinates. */
class Direction {
  private first: number | undefined;
  private last: number | undefined;
  private ascending = 0;
  private descending = 0;
  add(anchor: Date | null): void {
    if (!anchor) return;
    const next = anchor.getTime();
    if (this.first === undefined) this.first = next;
    if (this.last !== undefined && Math.abs(next - this.last) >= 36 * 3600_000) {
      if (next > this.last) this.ascending += 1; else this.descending += 1;
    }
    this.last = next;
  }
  reversed(): boolean {
    const delta = (this.last ?? 0) - (this.first ?? 0);
    return this.descending + (delta <= -36 * 3600_000 ? 2 : 0)
      > this.ascending + (delta >= 36 * 3600_000 ? 2 : 0);
  }
}

export async function extractTimeline(
  file: Blob, range: RangeRequest, signal?: AbortSignal, progress?: (fraction: number) => void,
): Promise<ImportResult> {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(range.start) || !/^\d{4}-\d{2}-\d{2}$/.test(range.end) || range.start > range.end) {
    throw new ImportError('invalidDates');
  }
  if (range.raw) return extractRaw(file, range, signal, progress);
  const startContext = adjacentDate(range.start, -1);
  const endContext = adjacentDate(range.end, 1);
  const inRange = (point: GeoPoint): boolean => {
    const date = pointDateKey(point);
    return date >= range.start && date <= range.end;
  };
  const inContext = (point: GeoPoint): boolean => {
    const date = pointDateKey(point);
    return date >= startContext && date <= endContext;
  };
  const segments: ParsedTimelineSegment[] = [];
  const intervals: TimeInterval[] = [];
  const direction = new Direction();
  let preserveRecordedOrder = false;
  const contextStartMillis = Date.parse(startContext + 'T00:00:00Z') - 86400_000;
  const contextEndMillis = Date.parse(endContext + 'T23:59:59Z') + 86400_000;
  let retained = 0;
  let selected = 0;
  const retain = (points: GeoPoint[]): void => {
    retained += points.length;
    selected += points.filter(inRange).length;
    if (selected > MAX_SELECTED_POINTS || retained > MAX_SELECTED_POINTS * 2) throw new ImportError('rangeTooLarge');
  };
  await records(file, (kind, record) => {
    if (kind !== 'semantic') return;
    const segment = parseTimelineSegment(record);
    if (!segment) return;
    direction.add(segment.anchor);
    preserveRecordedOrder ||= segment.points.some((point) => point.timeZoneMissing === true);
    // Coverage must also include visits whose endpoints lie outside the selected dates.
    if (segment.interval && segment.interval.end >= contextStartMillis
      && segment.interval.start <= contextEndMillis) {
      intervals.push(segment.interval);
      if (intervals.length > MAX_SELECTED_POINTS) throw new ImportError('rangeTooLarge');
    }
    segment.points = segment.points.filter(inContext);
    if (segment.points.length) { retain(segment.points); segments.push(segment); }
  }, signal, progress);
  signal?.throwIfAborted();
  if (!segments.length) return { points: [], rejected: 0 };
  let normalized: GeoPoint[];
  try { normalized = reconcileTimelineSegments(segments, intervals, direction.reversed(), preserveRecordedOrder); }
  catch (error) {
    if (error instanceof TimelineParseError && error.reason === 'no-usable-locations') return { points: [], rejected: 0 };
    throw error;
  }
  const filtered = filterLocationOutliers(normalized, range.filter).points.filter(inRange);
  return { points: filtered, rejected: normalized.filter(inRange).length - filtered.length };
}

async function extractRaw(file: Blob, range: RangeRequest, signal?: AbortSignal, progress?: (fraction: number) => void): Promise<ImportResult> {
  let points: GeoPoint[] = [];
  let selected = 0;
  let previousTime = -Infinity;
  let uniqueSelected = 0;
  let ordered = true;
  const inRange = (point: GeoPoint) => { const date = pointDateKey(point); return date >= range.start && date <= range.end; };
  const emit = (point: GeoPoint): void => { if (inRange(point)) points.push(point); };
  const unique = (point: GeoPoint): void => { if (inRange(point)) uniqueSelected += 1; };
  let processor = new RawSignalStream(range.accuracy, emit, unique);
  // Carry spike and stabilization state from the complete ordered stream, not just one
  // neighboring day. Stationary clusters can otherwise shift their anchors indefinitely.
  await records(file, (kind, value) => {
    if (kind !== 'raw') return;
    for (const point of parseRawSignalsJson({ rawSignals: [value] })) {
      if (inRange(point) && ++selected > MAX_SELECTED_POINTS) throw new ImportError('rangeTooLarge');
      if (point.instant.getTime() < previousTime) ordered = false;
      previousTime = point.instant.getTime();
      if (ordered) processor.push(point);
    }
  }, signal, progress);
  if (!ordered) {
    points = [];
    uniqueSelected = 0;
    processor = new RawSignalStream(range.accuracy, emit, unique);
    let cursor: IndexedRawPoint | null = null;
    // Unordered exports need extra passes rather than an unbounded in-memory sort.
    // Stable record indices preserve duplicate/tied-timestamp behavior across batches.
    while (true) {
      const batch = new RawBatch();
      let index = 0;
      let eligible = 0;
      await records(file, (kind, value) => {
        if (kind !== 'raw') return;
        for (const point of parseRawSignalsJson({ rawSignals: [value] })) {
          const item = { point, index: index++ };
          if (!cursor || compareRaw(item, cursor) > 0) { batch.push(item); eligible += 1; }
        }
      }, signal, progress);
      const sorted = batch.sorted();
      for (const item of sorted) processor.push(item.point);
      if (sorted.length === eligible) break;
      cursor = sorted.at(-1)!;
    }
  }
  processor.finish();
  return { points, rejected: Math.max(0, uniqueSelected - points.length) };
}
