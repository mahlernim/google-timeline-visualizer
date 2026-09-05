import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { scanTimeline, extractTimeline } from './stream-import';
import { parseTimelineJson, parseRawSignalsJson, processRawSignals, selectDateRange } from './timeline';
import { filterLocationOutliers } from './outlier';
import type { RangeRequest } from './import-types';

const all: RangeRequest = { start: '2000-01-01', end: '2099-12-31', raw: false, accuracy: 100, filter: 'conservative' };
const blob = (data: unknown) => new Blob([JSON.stringify(data)]);
const activity = (day: string, from = 'geo:37.5,127', to = 'geo:35.1,129') => ({
  startTime: day + 'T08:00:00Z', endTime: day + 'T12:00:00Z', activity: { start: from, end: to },
});
describe('streamed Timeline import', () => {
  it('matches the canonical parser on the shared fixture and fictional sample', async () => {
    for (const path of ['../../test-fixtures/platform-parity-sample.json', '../public/sample-timeline.json']) {
      const data = JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
      const result = await extractTimeline(blob(data), all);
      expect(result.points).toEqual(filterLocationOutliers(parseTimelineJson(data)).points);
      const file = blob(data);
      const scan = await scanTimeline(file);
      expect(await extractTimeline(file, all, undefined, undefined, scan.index)).toEqual(result);
    }
  });
  it('keeps only the selected range while preserving cross-boundary semantic coverage', async () => {
    const data = [activity('2026-01-01'), {
      startTime: '2026-01-31T00:00:00Z', endTime: '2026-03-01T00:00:00Z',
      activity: { start: 'geo:37,127', end: 'geo:35,129' },
    }, {
      startTime: '2026-02-05T08:00:00Z', endTime: '2026-02-05T12:00:00Z',
      timelinePath: [{ time: '2026-02-05T09:00:00Z', point: 'geo:40,120' }],
    }, activity('2026-02-06')];
    const range = { ...all, start: '2026-02-01', end: '2026-02-28' };
    expect((await extractTimeline(blob({ semanticSegments: data }), range)).points)
      .toEqual(selectDateRange(filterLocationOutliers(parseTimelineJson(data)).points, range.start, range.end));
  });
  it('preserves reverse exports, missing timezones, and duplicate paths', async () => {
    const data = [activity('2026-04-10'), activity('2026-04-05'), activity('2026-03-01')]
      .map((segment) => ({ ...segment, startTime: segment.startTime.slice(0, -1), endTime: segment.endTime.slice(0, -1) }));
    expect((await extractTimeline(blob(data), all)).points).toEqual(filterLocationOutliers(parseTimelineJson(data)).points);
    const scan = await scanTimeline(blob(data));
    expect(scan.months).toEqual(['2026-03', '2026-04']);
    expect(scan.timezoneMissing).toBe(true);
  });
  it('scans semantic and raw dates separately without enabling raw processing', async () => {
    const data = { semanticSegments: [activity('2026-01-01')], rawSignals: [
      { position: { timestamp: '2026-07-01T00:00:00Z', LatLng: 'geo:37,127', accuracyMeters: 10 } },
      { position: { timestamp: '2026-07-01T01:00:00Z', LatLng: 'geo:35,129', accuracyMeters: 10 } },
    ] };
    const scan = await scanTimeline(blob(data));
    expect(scan.months).toEqual(['2026-01']);
    expect(scan.rawMonths).toEqual(['2026-07']);
    expect(scan.hasRaw).toBe(true);
    const result = await extractTimeline(blob(data), { ...all, raw: true });
    expect(result.points).toEqual(processRawSignals(parseRawSignalsJson(data), 100).points);
  });
  it('does not retain previous records or read the file as one string', async () => {
    const text = JSON.stringify({ semanticSegments: Array.from({ length: 15_000 }, () => activity('2026-01-01')) });
    const source = new Blob([text]);
    source.text = () => { throw new Error('Whole-file reads are forbidden'); };
    source.arrayBuffer = () => { throw new Error('Whole-file reads are forbidden'); };
    const seen: number[] = [];
    const scan = await scanTimeline(source, undefined, (fraction) => seen.push(fraction));
    expect(scan.months).toEqual(['2026-01']);
    expect(seen.length).toBeGreaterThan(10);
    expect(seen.at(-1)).toBe(1);
  });
  it('preserves raw stabilization anchors that start before the date context', async () => {
    const data = { rawSignals: Array.from({ length: 3 * 24 * 60 }, (_, minute) => ({ position: {
      timestamp: new Date(Date.UTC(2026, 0, 1) + minute * 60_000).toISOString(),
      LatLng: 'geo:37,127', accuracyMeters: 100,
    } })) };
    const range = { ...all, raw: true, start: '2026-01-03', end: '2026-01-03' };
    expect((await extractTimeline(blob(data), range)).points)
      .toEqual(selectDateRange(processRawSignals(parseRawSignalsJson(data), 100).points, range.start, range.end));
  });
  it('preserves shuffled raw data, duplicate accuracy, and spikes across bounded sort batches', async () => {
    const ordered = Array.from({ length: 20_003 }, (_, minute) => ({ position: {
      timestamp: new Date(Date.UTC(2026, 0, 1) + minute * 60_000).toISOString(),
      LatLng: minute % 89 === 0 ? 'geo:51,0' : 'geo:37,127', accuracyMeters: minute % 29 === 0 ? 200 : 100,
    } }));
    // Put a better duplicate at the opposite end of a nonchronological source.
    const data = { rawSignals: [...ordered.slice(10_000).reverse(), ...ordered.slice(0, 10_000),
      { position: { ...ordered[10_000].position, accuracyMeters: 5 } }] };
    const range = { ...all, raw: true, start: '2026-01-10', end: '2026-01-13' };
    const parsed = parseRawSignalsJson(data);
    const expected = selectDateRange(processRawSignals(parsed, 100).points, range.start, range.end);
    const actual = await extractTimeline(blob(data), range);
    expect(actual.points).toEqual(expected);
    expect(actual.rejected).toBe(selectDateRange(parsed, range.start, range.end).length - expected.length);
  });
  it('refuses oversized selections without returning truncated output', async () => {
    const data = Array.from({ length: 50_001 }, () => activity('2026-01-01'));
    await expect(extractTimeline(blob(data), all)).rejects.toMatchObject({ code: 'rangeTooLarge' });
  });
  it('cancels during the first scan instead of reading the remainder', async () => {
    const controller = new AbortController();
    const source = blob(Array.from({ length: 10_000 }, () => activity('2026-01-01')));
    let progress = 0;
    await expect(scanTimeline(source, controller.signal, () => { progress += 1; controller.abort(); }))
      .rejects.toMatchObject({ name: 'AbortError' });
    expect(progress).toBe(1);
  });
  it.each(['[{"broken":', '{"semanticSegments": [}', ''])('reports malformed input %s', async (text) => {
    await expect(scanTimeline(new Blob([text]))).rejects.toMatchObject({ code: 'malformed-json' });
  });
  it('rejects old Takeout and unsupported structures', async () => {
    await expect(scanTimeline(blob({ locations: [] }))).rejects.toMatchObject({ code: 'legacy-format' });
    await expect(scanTimeline(blob({ unrelated: [] }))).rejects.toMatchObject({ code: 'unsupported-format' });
  });
  it('seeks indexed byte ranges across UTF-8, leading whitespace, reversed dates, and covering visits', async () => {
    const data = [activity('2026-04-10'), {
      startTime: '2026-01-01T00:00:00', endTime: '2026-04-01T00:00:00',
      visit: { topCandidate: { placeLocation: 'geo:37,127' } },
    }, activity('2026-02-01'), activity('2026-01-01')];
    const filler = '\uD83C\uDF0F 서울 café '.repeat(30_000);
    for (const wrapper of [data, { semanticSegments: data, rawSignals: [] }]) {
      // The ignored record spans chunks and tests UTF-8 byte offsets rather than character offsets.
      const expanded = Array.isArray(wrapper) ? [{ note: filler }, ...wrapper]
        : { note: filler, ...wrapper };
      const file = new Blob([' '.repeat(70_000), JSON.stringify(expanded)]);
      const scan = await scanTimeline(file);
      const range = { ...all, start: '2026-02-01', end: '2026-02-28' };
      expect(await extractTimeline(file, range, undefined, undefined, scan.index))
        .toEqual(await extractTimeline(file, range));
    }
  });
  it('reads substantially fewer bytes on a selected-month pass without changing its output', async () => {
    const data = Array.from({ length: 6000 }, (_, i) => ({
      ...activity(new Date(Date.UTC(2020, 0, 1 + i)).toISOString().slice(0, 10)),
      note: 'ignored metadata'.repeat(60),
    }));
    const file = blob({ rawSignals: [], semanticSegments: data });
    const scan = await scanTimeline(file);
    const range = { ...all, start: '2026-01-01', end: '2026-01-31' };
    const expected = await extractTimeline(file, range);
    const slices = vi.spyOn(file, 'slice');
    expect(await extractTimeline(file, range, undefined, undefined, scan.index)).toEqual(expected);
    const bytesRead = slices.mock.calls.reduce((total, [start, end]) => total + Number(end) - Number(start), 0);
    expect(bytesRead).toBeLessThan(file.size / 4);
    const controller = new AbortController();
    await expect(extractTimeline(file, all, controller.signal, () => controller.abort(), scan.index))
      .rejects.toMatchObject({ name: 'AbortError' });
  });
  it('keeps separate repeated semantic arrays separate in the index', async () => {
    const file = new Blob(['{"semanticSegments":', JSON.stringify([activity('2026-01-01')]),
      ',"rawSignals":[],"semanticSegments":', JSON.stringify([activity('2026-02-01')]), '}']);
    const scan = await scanTimeline(file);
    expect(scan.index?.blocks).toHaveLength(2);
    expect(await extractTimeline(file, all, undefined, undefined, scan.index))
      .toEqual(await extractTimeline(file, all));
  });
  it('reads a covering semantic block even when its recorded endpoints are outside the selection', async () => {
    const cover = { startTime: '2026-01-01T00:00:00Z', endTime: '2026-04-01T00:00:00Z',
      activity: { start: 'geo:37,127', end: 'geo:35,129' }, note: 'x'.repeat(270_000) };
    const path = { startTime: '2026-02-05T00:00:00Z', endTime: '2026-02-06T00:00:00Z',
      timelinePath: [{ time: '2026-02-05T12:00:00Z', point: 'geo:40,120' }] };
    const file = blob([cover, path, activity('2026-02-10')]);
    const scan = await scanTimeline(file);
    expect(scan.index?.blocks.length).toBeGreaterThan(1);
    const range = { ...all, start: '2026-02-01', end: '2026-02-28' };
    expect(await extractTimeline(file, range, undefined, undefined, scan.index))
      .toEqual(await extractTimeline(file, range));
  });
});
