import { describe, expect, it } from 'vitest';
import {
  availableMonths,
  localDateKey,
  parseCoordinate,
  parseRawSignalsJson,
  parseTimelineJson,
  pointDateKey,
  processRawSignals,
  selectDateRange,
  selectRange,
  TimelineParseError,
} from './timeline';

describe('parseCoordinate', () => {
  it('supports iOS, geo, object, and E7 coordinate forms', () => {
    expect(parseCoordinate('37.5°, 127.0°')).toEqual([37.5, 127]);
    expect(parseCoordinate('geo:37.5,127?z=12')).toEqual([37.5, 127]);
    expect(parseCoordinate({ latLng: '37.5,127' })).toEqual([37.5, 127]);
    expect(parseCoordinate('375000000,1270000000')).toEqual([37.5, 127]);
  });

  it('rejects invalid coordinates', () => {
    expect(parseCoordinate('91,127')).toBeNull();
    expect(parseCoordinate('not a coordinate')).toBeNull();
  });
});

describe('raw location data', () => {
  it('parses position records and keeps the most accurate duplicate', () => {
    const points = parseRawSignalsJson({
      rawSignals: [
        { position: { LatLng: '37.1,127.1', timestamp: '2026-02-01T00:00:00Z', accuracyMeters: 20 } },
        { position: { latLng: '37.1,127.1', timestamp: '2026-02-01T00:00:00Z', accuracyMeters: '10' } },
        { wifiScan: { timestamp: '2026-02-01T00:01:00Z' } },
      ],
    });

    expect(points).toHaveLength(1);
    expect(points[0].accuracyMeters).toBe(10);
  });

  it('filters poor accuracy, stationary jitter, and a short impossible spike', () => {
    const points = parseRawSignalsJson({
      rawSignals: [
        { position: { LatLng: '37,127', timestamp: '2026-02-01T00:00:00Z', accuracyMeters: 10 } },
        { position: { LatLng: '38,128', timestamp: '2026-02-01T00:01:00Z', accuracyMeters: 10 } },
        { position: { LatLng: '37.0001,127.0001', timestamp: '2026-02-01T00:02:00Z', accuracyMeters: 10 } },
        { position: { LatLng: '37.01,127.01', timestamp: '2026-02-01T00:04:00Z', accuracyMeters: 150 } },
        { position: { LatLng: '37.1,127.1', timestamp: '2026-02-01T01:00:00Z', accuracyMeters: 10 } },
      ],
    });
    const result = processRawSignals(points, 100);

    expect(result.points).toHaveLength(2);
    expect(result.rejectedCount).toBe(3);
    expect(result.discontinuityCount).toBe(1);
  });
});

describe('parseTimelineJson', () => {
  const directExport = [
    {
      startTime: '2025-01-10T00:00:00Z',
      endTime: '2025-01-10T01:00:00Z',
      activity: { start: 'geo:37.5,127', end: 'geo:35.1,129' },
    },
    {
      startTime: '2025-03-10T00:00:00Z',
      timelinePath: [{ point: '33.5°, 126.5°', time: '2025-03-10T00:00:00Z' }],
    },
  ];

  it('supports direct-array and semanticSegments roots', () => {
    expect(parseTimelineJson(directExport)).toHaveLength(3);
    expect(parseTimelineJson({ semanticSegments: directExport })).toHaveLength(3);
  });

  it('suppresses standalone path points covered by semantic segments', () => {
    const points = parseTimelineJson([
      {
        startTime: '2026-05-11T08:00:00Z',
        endTime: '2026-05-11T22:00:00Z',
        activity: { start: '10,10', end: '20,20' },
      },
      {
        startTime: '2026-05-12T08:00:00Z',
        endTime: '2026-05-12T22:00:00Z',
        activity: { start: '20,20', end: '10,10' },
      },
      {
        startTime: '2026-05-11T08:00:00Z',
        endTime: '2026-05-12T23:00:00Z',
        timelinePath: [
          { point: '20,20', time: '2026-05-11T13:00:00Z' },
          { point: '10,10', time: '2026-05-11T17:00:00Z' },
          { point: '10,10', time: '2026-05-12T13:00:00Z' },
          { point: '20,20', time: '2026-05-12T17:00:00Z' },
          { point: '30,30', time: '2026-05-12T22:30:00Z' },
        ],
      },
    ]);

    expect(points.map((point) => [point.instant.toISOString(), point.latitude])).toEqual([
      ['2026-05-11T08:00:00.000Z', 10],
      ['2026-05-11T22:00:00.000Z', 20],
      ['2026-05-12T08:00:00.000Z', 20],
      ['2026-05-12T22:00:00.000Z', 10],
      ['2026-05-12T22:30:00.000Z', 30],
    ]);
  });

  it('keeps path detail inside the same semantic segment', () => {
    const points = parseTimelineJson([{
      startTime: '2026-01-01T00:00:00Z',
      endTime: '2026-01-01T02:00:00Z',
      activity: { start: '10,10', end: '20,20' },
      timelinePath: [{ point: '15,15', time: '2026-01-01T01:00:00Z' }],
    }]);

    expect(points.map((point) => point.latitude)).toEqual([10, 15, 20]);
  });

  it('parses string and numeric offsets from a segment start', () => {
    const points = parseTimelineJson([{
      startTime: '2026-01-01T00:00:00Z',
      endTime: '2026-01-01T02:00:00Z',
      timelinePath: [
        { point: '37.0,127.0', durationMinutesOffsetFromStartTime: '15' },
        { point: '37.1,127.1', durationMinutesOffsetFromStartTime: 60 },
      ],
    }]);
    expect(points.map((point) => point.instant.toISOString())).toEqual([
      '2026-01-01T00:15:00.000Z',
      '2026-01-01T01:00:00.000Z',
    ]);
  });

  it('prefers an absolute path time and ignores invalid offsets', () => {
    const points = parseTimelineJson([{
      startTime: '2026-01-01T00:00:00Z',
      endTime: '2026-01-01T01:00:00Z',
      timelinePath: [
        { point: '37.0,127.0', time: '2026-01-01T00:30:00Z', durationMinutesOffsetFromStartTime: '5' },
        { point: '37.1,127.1', durationMinutesOffsetFromStartTime: '-1' },
        { point: '37.2,127.2', durationMinutesOffsetFromStartTime: 'unknown' },
        { point: '37.3,127.3', durationMinutesOffsetFromStartTime: '120' },
      ],
      visit: { topCandidate: { placeLocation: '37.4,127.4' } },
    }]);
    expect(points).toHaveLength(2);
    expect(points[0].instant.toISOString()).toBe('2026-01-01T00:00:00.000Z');
    expect(points[1].instant.toISOString()).toBe('2026-01-01T00:30:00.000Z');
  });

  it('sorts, deduplicates, lists months, and selects a month range', () => {
    const duplicate = { ...directExport[0] };
    const points = parseTimelineJson([directExport[1], directExport[0], duplicate]);
    expect(points).toHaveLength(3);
    expect(points[0].instant.toISOString()).toBe('2025-01-10T00:00:00.000Z');
    expect(availableMonths(points).map((month) => month.key)).toEqual(['2025-01', '2025-03']);
    expect(selectRange(points, '2025-03', '2025-03')).toHaveLength(1);
  });

  it('preserves export order when timezone-less wall times cross the date line', () => {
    const points = parseTimelineJson([
      {
        startTime: '2026-01-02T10:00:00',
        visit: { topCandidate: { placeLocation: '10,179' } },
      },
      {
        startTime: '2026-01-01T15:00:00',
        visit: { topCandidate: { placeLocation: '10,-179' } },
      },
    ]);

    expect(points.map((point) => point.longitude)).toEqual([179, -179]);
    expect(points.every((point) => point.timeZoneMissing)).toBe(true);
    expect(points.map(pointDateKey)).toEqual(['2026-01-02', '2026-01-01']);
    expect(selectDateRange(points, '2026-01-01', '2026-01-01')).toHaveLength(1);
  });

  it('keeps a timezone-less activity start before its path and end', () => {
    const points = parseTimelineJson([{
      startTime: '2026-01-02T10:00:00',
      endTime: '2026-01-01T16:00:00',
      activity: { start: '10,179', end: '10,-178' },
      timelinePath: [{ point: '10,-179', durationMinutesOffsetFromStartTime: 120 }],
    }]);

    expect(points.map((point) => point.longitude)).toEqual([179, -179, -178]);
  });

  it('normalizes a reverse-ordered timezone-less segment array', () => {
    const points = parseTimelineJson([
      {
        startTime: '2026-01-03T10:00:00',
        visit: { topCandidate: { placeLocation: '35,129' } },
      },
      {
        startTime: '2026-01-02T10:00:00',
        visit: { topCandidate: { placeLocation: '37,127' } },
      },
      {
        startTime: '2026-01-01T10:00:00',
        visit: { topCandidate: { placeLocation: '33,126' } },
      },
    ]);

    expect(points.map(pointDateKey)).toEqual([
      '2026-01-01',
      '2026-01-02',
      '2026-01-03',
    ]);
  });

  it('does not let one timezone-less segment preserve an otherwise reverse export', () => {
    const points = parseTimelineJson([
      {
        startTime: '2026-03-10T10:00:00Z',
        visit: { topCandidate: { placeLocation: '35,129' } },
      },
      {
        startTime: '2026-02-10T10:00:00',
        visit: { topCandidate: { placeLocation: '37,127' } },
      },
      {
        startTime: '2026-01-10T10:00:00Z',
        visit: { topCandidate: { placeLocation: '33,126' } },
      },
    ]);

    expect(points.map(pointDateKey)).toEqual([
      '2026-01-10',
      '2026-02-10',
      '2026-03-10',
    ]);
  });

  it('rejects unsupported or empty exports', () => {
    expect(() => parseTimelineJson({ locations: [] })).toThrow(TimelineParseError);
    expect(() => parseTimelineJson([])).toThrow('no usable location points');
  });

  it('classifies unsupported export formats', () => {
    const reason = (value: unknown) => {
      try {
        parseTimelineJson(value);
        throw new Error('Expected parsing to fail');
      } catch (error) {
        expect(error).toBeInstanceOf(TimelineParseError);
        return (error as TimelineParseError).reason;
      }
    };
    expect(reason({ timelineObjects: [] })).toBe('legacy-format');
    expect(reason({ locations: [] })).toBe('legacy-format');
    expect(reason({ rawSignals: [] })).toBe('raw-signals-only');
    expect(reason({ semanticSegments: [] })).toBe('no-usable-locations');
  });

  it('selects an inclusive exact local-date range', () => {
    const points = parseTimelineJson(directExport);
    expect(localDateKey(points[0].instant)).toMatch(/^2025-01-10$/);
    expect(selectDateRange(points, '2025-03-10', '2025-03-10')).toHaveLength(1);
  });
});
