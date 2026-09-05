import './style.css';
import './workflow.css';
import { createI18n, activeLocale, formattingLocale, readLanguagePreference, writeLanguagePreference, isLanguagePreference } from './i18n';
import type { I18n, TextKey } from './i18n';
import { applyStrings, syncDocumentLang } from './i18n-dom';
import { applyFlowStrings, flowText } from './flow-i18n';
import type { FlowKey } from './flow-i18n';
import { readDistanceUnitPreference, writeDistanceUnitPreference, resolveDistanceUnit, isDistanceUnitPreference } from './distance-unit';
import { buildVideoFormat, createFormatProbe, estimatedOutputBytes, MAX_OUTPUT_BYTES } from './video-format';
import type { VideoAspectRatio, ResolvedVideoFormat } from './video-format';
import { ImportError } from './import-types';
import type { TimelineScan, RangeRequest } from './import-types';
import { AppError } from './errors';
import { cumulativeDistances } from './geo';
import { frameAtElapsedSeconds } from './animation';
import type { CameraMovement, GeoPoint, PreparedJourney } from './types';
import type { OverlayText } from './renderer';

function el<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error('Missing element #' + id);
  return node as T;
}
const form = el<HTMLFormElement>('settings-form');
const language = el<HTMLSelectElement>('app-language');
const unitSelect = el<HTMLSelectElement>('distance-unit');
const sourceInput = el<HTMLInputElement>('timeline-file');
const sampleButton = el<HTMLButtonElement>('sample-button');
const startMonth = el<HTMLSelectElement>('start-month');
const endMonth = el<HTMLSelectElement>('end-month');
const startDate = el<HTMLInputElement>('start-date');
const endDate = el<HTMLInputElement>('end-date');
const exact = el<HTMLInputElement>('exact-date-toggle');
const advanced = el<HTMLInputElement>('advanced-toggle');
const raw = el<HTMLInputElement>('raw-signals-toggle');
const accuracy = el<HTMLInputElement>('raw-accuracy-limit');
const filter = el<HTMLSelectElement>('location-filter');
const camera = el<HTMLSelectElement>('camera-movement');
const aspect = el<HTMLSelectElement>('aspect-ratio');
const resolution = el<HTMLInputElement>('resolution');
const frameRate = el<HTMLInputElement>('frame-rate');
const duration = el<HTMLInputElement>('duration');
const simpleDuration = el<HTMLSelectElement>('duration-simple');
const title = el<HTMLInputElement>('video-title');
const consent = el<HTMLInputElement>('map-consent');
const canvas = el<HTMLCanvasElement>('journey-canvas');
const previewButton = el<HTMLButtonElement>('preview-button');
const createButton = el<HTMLButtonElement>('create-button');
const back = el<HTMLButtonElement>('back-button');
const next = el<HTMLButtonElement>('next-button');
const cancel = el<HTMLButtonElement>('cancel-button');
const progress = el<HTMLProgressElement>('export-progress');
const resultVideo = el<HTMLVideoElement>('result-video');
const resultActions = el('result-actions');
const download = el<HTMLAnchorElement>('download-link');
const share = el<HTMLButtonElement>('share-button');
const rawDialog = el<HTMLDialogElement>('raw-only-dialog');
let languagePreference = readLanguagePreference();
let unitPreference = readDistanceUnitPreference();
const languages = (): readonly string[] => navigator.languages ?? [navigator.language];
function makeI18n(): I18n {
  const locale = activeLocale(languagePreference, languages());
  return createI18n(locale, formattingLocale(languagePreference, languages(), locale));
}
let i18n = makeI18n();
const f = (key: FlowKey): string => flowText(i18n.locale, key);
const distanceUnit = () => resolveDistanceUnit(unitPreference, languages());
let step = 0;
let scan: TimelineScan | null = null;
let file: Blob | null = null;
let fileName = '';
let isSample = false;
let points: GeoPoint[] = [];
let rejected = 0;
let distance = 0;
let busy = false;
let generation = 0;
let controller: AbortController | null = null;
let renderer: typeof import('./renderer') | null = null;
let encoder: typeof import('./video') | null = null;
let prepared: PreparedJourney | null = null;
let supportedFormat: ResolvedVideoFormat | null = null;
let resultUrl: string | null = null;
let resultFile: File | null = null;
let statusText: () => string = () => '';
let errorText: (() => string) | null = null;
const probe = createFormatProbe();

function currentDuration(): number { return Number(advanced.checked ? duration.value : simpleDuration.value); }
function currentFormat() {
  return buildVideoFormat(aspect.value as VideoAspectRatio, advanced.checked ? Number(resolution.value) : 480,
    advanced.checked ? Number(frameRate.value) : 15);
}
function currentCamera(): CameraMovement { return advanced.checked ? camera.value as CameraMovement : 'steady'; }
function range(): RangeRequest {
  const lastDay = new Date(Number(endMonth.value.slice(0, 4)), Number(endMonth.value.slice(5, 7)), 0).getDate();
  return {
    start: exact.checked ? startDate.value : startMonth.value + '-01',
    end: exact.checked ? endDate.value : endMonth.value + '-' + String(lastDay).padStart(2, '0'),
    raw: raw.checked,
    accuracy: accuracy.value.trim() === '' ? null : Number(accuracy.value),
    filter: advanced.checked && filter.value === 'off' ? 'off' : 'conservative',
  };
}
function monthLabel(value: string): string {
  return i18n.formatMonth(new Date(Number(value.slice(0, 4)), Number(value.slice(5, 7)) - 1, 1));
}
function period(): string {
  const selected = range();
  const label = (value: string) => i18n.formatMediumDate(new Date(value + 'T12:00:00'));
  return exact.checked
    ? selected.start === selected.end ? label(selected.start) : i18n.t('periodRange', { start: label(selected.start), end: label(selected.end) })
    : startMonth.value === endMonth.value ? monthLabel(startMonth.value)
      : i18n.t('periodRange', { start: monthLabel(startMonth.value), end: monthLabel(endMonth.value) });
}
function overlay(): OverlayText {
  const locale = i18n;
  const unit = distanceUnit();
  return {
    title: title.value.trim() || i18n.t('defaultVideoTitle'),
    periodLabel: period(),
    separator: i18n.strings.listSeparator,
    formatDistance: (km) => locale.formatDistance(km, unit),
  };
}
function releasePreview(): void {
  renderer?.releaseJourney(prepared);
  prepared = null;
  canvas.width = 1;
  canvas.height = 1;
  canvas.hidden = true;
}
function releaseResult(): void {
  resultVideo.pause();
  resultVideo.removeAttribute('src');
  resultVideo.load();
  resultVideo.hidden = true;
  resultActions.hidden = true;
  download.removeAttribute('href');
  if (resultUrl) URL.revokeObjectURL(resultUrl);
  resultUrl = null;
  resultFile = null;
}
function renderStatus(): void {
  el('progress-label').textContent = statusText();
  el('error-message').hidden = errorText === null;
  el('error-message').textContent = errorText?.() ?? '';
}
function fail(error: unknown): void {
  const code = error instanceof ImportError ? error.code : error instanceof Error ? error.message : '';
  const parseKeys: Record<string, TextKey> = {
    'malformed-json': 'errorMalformedJson', 'legacy-format': 'errorLegacyFormat',
    'unsupported-format': 'errorUnsupportedFormat', 'no-usable-locations': 'errorNoUsableLocations',
  };
  if (['rangeTooLarge', 'invalidDates', 'importFailed', 'outputTooLarge'].includes(code)) {
    errorText = () => f(code as FlowKey);
  } else if (parseKeys[code]) errorText = () => i18n.t(parseKeys[code]);
  else if (error instanceof AppError) errorText = () => i18n.t(error.code);
  else errorText = () => i18n.t('errorPreviewFailed');
  renderStatus();
}
function refresh(): void {
  document.querySelectorAll<HTMLElement>('[data-panel]').forEach((panel) => { panel.hidden = Number(panel.dataset.panel) !== step; });
  document.querySelectorAll<HTMLButtonElement>('[data-step]').forEach((button) => {
    const target = Number(button.dataset.step);
    button.disabled = busy || target > step;
    if (target === step) button.setAttribute('aria-current', 'step'); else button.removeAttribute('aria-current');
  });
  form.querySelectorAll<HTMLInputElement | HTMLButtonElement | HTMLSelectElement>('input, button, select').forEach((control) => {
    control.disabled = busy && control !== cancel && control !== back;
  });
  language.disabled = busy;
  unitSelect.disabled = busy;
  back.hidden = step === 0;
  next.hidden = step === 0 || step === 3;
  cancel.hidden = !busy;
  progress.hidden = !busy;
  createButton.disabled = busy || supportedFormat === null;
  previewButton.disabled = busy || points.length < 2;
  share.hidden = !resultFile || typeof navigator.share !== 'function'
    || (typeof navigator.canShare === 'function' && !navigator.canShare({ files: [resultFile] }));
  el('advanced-fields').hidden = !advanced.checked;
  el('mode-summary').textContent = advanced.checked ? f('advanced') : f('simpleHint');
  el('simple-duration-field').hidden = advanced.checked;
  el('month-range-fields').classList.toggle('hidden', exact.checked);
  el('exact-date-fields').classList.toggle('hidden', !exact.checked);
  el('raw-signals-row').classList.toggle('hidden', !scan?.hasRaw);
  el('raw-accuracy-field').classList.toggle('hidden', !raw.checked);
  el('raw-signals-description').classList.toggle('hidden', !raw.checked);
  el('location-filter-field').classList.toggle('hidden', raw.checked);
  renderStatus();
}
function populateDates(reset = false): void {
  if (!scan) return;
  const oldStart = startMonth.value;
  const oldEnd = endMonth.value;
  const months = raw.checked ? scan.rawMonths : scan.months;
  for (const select of [startMonth, endMonth]) select.replaceChildren(...months.map((key) => new Option(monthLabel(key), key)));
  startMonth.value = !reset && months.includes(oldStart) ? oldStart : months.at(-1) ?? '';
  endMonth.value = !reset && months.includes(oldEnd) ? oldEnd : months.at(-1) ?? '';
  if (reset) {
    startDate.value = [startMonth.value + '-01', raw.checked ? scan.rawFirstDate : scan.firstDate].sort().at(-1)!;
    const last = raw.checked ? scan.rawLastDate : scan.lastDate;
    endDate.value = last;
  }
  for (const input of [startDate, endDate]) {
    input.min = raw.checked ? scan.rawFirstDate : scan.firstDate;
    input.max = raw.checked ? scan.rawLastDate : scan.lastDate;
  }
}
function localize(): void {
  i18n = makeI18n();
  syncDocumentLang(i18n);
  applyStrings(document, i18n);
  applyFlowStrings(document, i18n.locale);
  populateDates();
  const resolved = distanceUnit();
  unitSelect.options[0].textContent = i18n.t('distanceUnitAutomaticResolved', { automatic: i18n.t('distanceUnitAutomatic'), resolved: i18n.t(resolved === 'miles' ? 'distanceUnitMiles' : 'distanceUnitKilometers') });
  if (scan) el('file-status').textContent = [isSample ? i18n.t('sampleSourceName') : fileName, scan.firstDate, scan.lastDate].join(' · ');
  if (points.length) el('selection-summary').textContent = i18n.join(
    i18n.t(raw.checked ? 'summaryDistanceEstimated' : 'summaryDistanceAbout', {
      count: points.length, distance: i18n.formatDistance(distance, distanceUnit()),
    }),
    rejected > 0 ? i18n.t('summaryOutliersIgnored', { count: rejected }) : '',
    scan?.timezoneMissing ? i18n.t('fileStatusTimezoneMissing') : '',
  );
  refresh();
}
function stop(): void {
  generation += 1;
  controller?.abort();
  if (!busy) controller = null;
  if (step === 0) { file = null; scan = null; sourceInput.value = ''; }
  releasePreview();
  statusText = () => f('paused');
  refresh();
}
async function job(label: FlowKey, work: (signal: AbortSignal, current: () => boolean) => Promise<void>): Promise<void> {
  controller?.abort();
  const id = ++generation;
  const ownController = new AbortController();
  controller = ownController;
  busy = true;
  errorText = null;
  progress.value = 0;
  statusText = () => f(label);
  refresh();
  const current = () => id === generation && !ownController.signal.aborted;
  try { await work(ownController.signal, current); }
  catch (error) { if (current()) { ownController.abort(); releasePreview(); statusText = () => ''; fail(error); } }
  finally {
    if (controller === ownController) { busy = false; controller = null; refresh(); }
  }
}
function showStep(target: number): void {
  if (target < step) {
    stop();
    releaseResult();
    supportedFormat = null;
    if (target <= 1) { points = []; distance = 0; }
    if (target === 0) { file = null; scan = null; sourceInput.value = ''; fileName = ''; }
  }
  step = target;
  statusText = () => '';
  errorText = null;
  refresh();
}
async function load(fileToRead: Blob, name: string, sample = false): Promise<void> {
  stop();
  releaseResult();
  scan = null;
  points = [];
  file = fileToRead;
  fileName = name;
  isSample = sample;
  step = 0;
  await job('importScanning', async (signal, current) => {
    const { runImport } = await import('./import-client');
    signal.throwIfAborted();
    const message = await runImport({ file: fileToRead }, signal, (fraction) => { progress.value = fraction; });
    if (!current() || message.kind !== 'scan') return;
    scan = message.scan;
    raw.checked = !scan.hasSemantic && scan.hasRaw;
    if (raw.checked) advanced.checked = true;
    exact.checked = false;
    populateDates(true);
    el('file-status').textContent = [sample ? i18n.t('sampleSourceName') : name, scan.firstDate, scan.lastDate].join(' · ');
    if (raw.checked) {
      rawDialog.showModal();
    } else { step = 1; }
    statusText = () => '';
  });
}
function validSettings(): boolean {
  const fields: Array<HTMLInputElement | HTMLSelectElement> = [title, aspect];
  if (exact.checked) fields.push(startDate, endDate);
  if (advanced.checked) fields.push(duration, resolution, frameRate, accuracy);
  for (const field of fields) if (!field.reportValidity()) return false;
  const selected = range();
  if (!selected.start || !selected.end || selected.start > selected.end) { fail(new ImportError('invalidDates')); return false; }
  return true;
}
function requireConsent(): boolean {
  if (consent.checked) return true;
  errorText = () => i18n.t('errorMapConsent');
  renderStatus();
  consent.focus();
  return false;
}
next.addEventListener('click', async () => {
  if (step === 1 && file && validSettings()) {
    await job('extractReading', async (signal, current) => {
      const { runImport } = await import('./import-client');
      signal.throwIfAborted();
      const message = await runImport({ file: file!, range: range() }, signal, (fraction) => { progress.value = fraction; });
      if (!current() || message.kind !== 'range') return;
      points = message.result.points;
      rejected = message.result.rejected;
      distance = cumulativeDistances(points).at(-1) ?? 0;
      if (points.length < 2 || distance <= 0) throw new AppError('errorTooFewPoints', 'Select a wider period.');
      step = 2;
      statusText = () => '';
      localize();
    });
  } else if (step === 2 && requireConsent()) {
    releasePreview();
    step = 3;
    supportedFormat = null;
    await job('working', async (signal, current) => {
      const format = currentFormat();
      el('compatibility-status').textContent = '';
      el('export-settings').textContent = format.width + ' × ' + format.height + ' · ' + format.frameRate + ' fps · '
        + i18n.t('durationSeconds', { count: currentDuration() });
      if (estimatedOutputBytes(format, currentDuration()) > MAX_OUTPUT_BYTES) throw new Error('outputTooLarge');
      encoder = await import('./video');
      signal.throwIfAborted();
      const codec = await probe(format);
      if (!current()) return;
      supportedFormat = codec ? { ...format, codec } : null;
      el('compatibility-status').textContent = codec ? i18n.t('compatibilityFull')
        : i18n.t('errorFormatUnsupported', { width: format.width, height: format.height, fps: format.frameRate });
      statusText = () => '';
    });
  }
});
previewButton.addEventListener('click', async () => {
  if (!requireConsent()) return;
  releasePreview();
  await job('working', async (signal, current) => {
    renderer = await import('./renderer');
    signal.throwIfAborted();
    const format = currentFormat();
    // Size and preparation both use preview pixels. Export settings never allocate here.
    const size = renderer.previewCanvasSize(format, 480, 1);
    canvas.width = size.width;
    canvas.height = size.height;
    canvas.style.setProperty('--preview-aspect', String(format.width / format.height));
    canvas.hidden = false;
    prepared = await renderer.prepareJourney(points, size, currentCamera(), 8, signal);
    const text = overlay();
    const frames = 8 * 15;
    for (let frame = 0; frame <= frames; frame += 1) {
      signal.throwIfAborted();
      const started = performance.now();
      await renderer.drawJourneyFrame(canvas, prepared, frameAtElapsedSeconds(frame / 15, 6.5), text, signal);
      if (!current()) return;
      progress.value = frame / frames;
      // Waiting only for the remaining frame time keeps map loading from creating a burst.
      await new Promise<void>((resolve) => window.setTimeout(resolve, Math.max(0, 1000 / 15 - (performance.now() - started))));
    }
    statusText = () => i18n.t('progressPreviewComplete');
  });
});
createButton.addEventListener('click', async () => {
  if (!supportedFormat || !encoder || !requireConsent()) return;
  const format = supportedFormat;
  const videoEncoder = encoder;
  releaseResult();
  releasePreview();
  await job('working', async (signal, current) => {
    renderer = await import('./renderer');
    signal.throwIfAborted();
    const exportCanvas = document.createElement('canvas');
    exportCanvas.width = format.width;
    exportCanvas.height = format.height;
    let journey: PreparedJourney | null = null;
    let wakeLock: WakeLockSentinel | null = null;
    try {
      wakeLock = await navigator.wakeLock?.request('screen').catch(() => null) ?? null;
      signal.throwIfAborted();
      journey = await renderer.prepareJourney(points, format, currentCamera(), currentDuration(), signal);
      const blob = await videoEncoder.createJourneyMp4(exportCanvas, journey, {
        format, durationSeconds: currentDuration(), overlay: overlay(), signal,
        onProgress: (fraction) => {
          if (!current()) return;
          progress.value = fraction;
          statusText = () => i18n.t('progressCreatingPercent', { percent: i18n.formatPercent(fraction) });
          renderStatus();
        },
      });
      if (!current()) return;
      resultUrl = URL.createObjectURL(blob);
      resultFile = new File([blob], 'timeline-journey.mp4', { type: 'video/mp4' });
      download.href = resultUrl;
      resultVideo.src = resultUrl;
      resultVideo.style.setProperty('--preview-aspect', String(format.width / format.height));
      resultVideo.hidden = false;
      resultActions.hidden = false;
      statusText = () => i18n.t('progressVideoReady', { size: i18n.formatNumber(blob.size / 1_000_000, { maximumFractionDigits: 1 }) });
    } finally {
      renderer.releaseJourney(journey);
      exportCanvas.width = 1;
      exportCanvas.height = 1;
      await wakeLock?.release().catch(() => undefined);
    }
  });
});
sourceInput.addEventListener('change', () => { const selected = sourceInput.files?.[0]; if (selected) void load(selected, selected.name); });
sampleButton.addEventListener('click', async () => {
  sampleButton.disabled = true;
  try {
    const response = await fetch(import.meta.env.BASE_URL + 'sample-timeline.json');
    if (!response.ok) throw new ImportError('importFailed');
    await load(await response.blob(), '', true);
  } catch (error) { fail(error); }
  finally { sampleButton.disabled = false; }
});
el('continue-raw-data').addEventListener('click', () => { rawDialog.close(); step = 1; refresh(); });
el('open-google-maps').addEventListener('click', () => { window.open('https://maps.google.com/', '_blank', 'noopener,noreferrer'); });
rawDialog.addEventListener('cancel', () => { stop(); scan = null; file = null; });
cancel.addEventListener('click', stop);
back.addEventListener('click', () => showStep(Math.max(0, step - 1)));
document.querySelectorAll<HTMLButtonElement>('[data-step]').forEach((button) => {
  button.addEventListener('click', () => { const target = Number(button.dataset.step); if (target < step) showStep(target); });
});
form.addEventListener('submit', (event) => event.preventDefault());
form.addEventListener('input', () => {
  if (step !== 1) return;
  points = [];
  supportedFormat = null;
  releasePreview();
  releaseResult();
  errorText = null;
});
advanced.addEventListener('change', () => {
  if (!advanced.checked) raw.checked = !scan?.hasSemantic && !!scan?.hasRaw;
  populateDates(true);
  refresh();
});
raw.addEventListener('change', () => { populateDates(true); refresh(); });
exact.addEventListener('change', refresh);
language.addEventListener('change', () => {
  if (!isLanguagePreference(language.value)) return;
  languagePreference = language.value;
  writeLanguagePreference(languagePreference);
  localize();
});
unitSelect.addEventListener('change', () => {
  if (!isDistanceUnitPreference(unitSelect.value)) return;
  unitPreference = unitSelect.value;
  writeDistanceUnitPreference(unitPreference);
  localize();
});
share.addEventListener('click', async () => {
  if (!resultFile || typeof navigator.share !== 'function') return;
  try { await navigator.share({ files: [resultFile], title: title.value }); }
  catch (error) { if (!(error instanceof DOMException && error.name === 'AbortError')) { errorText = () => i18n.t('errorShareUnavailable'); renderStatus(); } }
});
document.addEventListener('visibilitychange', () => { if (document.hidden && step === 2 && busy) stop(); });
window.addEventListener('pagehide', () => { stop(); releaseResult(); file = null; points = []; scan = null; });
window.addEventListener('pageshow', (event) => { if (event.persisted) { step = 0; form.reset(); advanced.checked = false; localize(); } });
// Browsers may restore form values, including 4K settings. A fresh session always starts light.
form.reset();
advanced.checked = false;
language.value = languagePreference;
unitSelect.value = unitPreference;
localize();
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register(import.meta.env.BASE_URL + 'service-worker.js?v=' + import.meta.env.VITE_SW_VERSION,
      { updateViaCache: 'none' }).catch(() => undefined);
  });
}
