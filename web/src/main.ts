import './style.css';
import { frameAtElapsedSeconds, totalDurationSeconds } from './animation';
import { cumulativeDistances } from './geo';
import { drawFrame, prepareJourney } from './renderer';
import {
  availableMonths,
  localDateKey,
  parseRawSignalsJson,
  parseTimelineJson,
  pointDateKey,
  processRawSignals,
  selectDateRange,
  selectRange,
  TimelineParseError,
} from './timeline';
import type { RawSignalPoint, RawSignalProcessingResult } from './timeline';
import type { CameraMovement, GeoPoint, MonthOption, PreparedJourney } from './types';
import { canCreateMp4, createJourneyMp4 } from './video';

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) throw new Error(`Missing element #${id}`);
  return found as T;
}

const fileInput = element<HTMLInputElement>('timeline-file');
const sampleButton = element<HTMLButtonElement>('sample-button');
const fileStatus = element<HTMLParagraphElement>('file-status');
const compatibilityStatus = element<HTMLParagraphElement>('compatibility-status');
const settingsCard = element<HTMLElement>('settings-card');
const exactDateToggle = element<HTMLInputElement>('exact-date-toggle');
const periodControls = element<HTMLElement>('period-controls');
const rawSignalsRow = element<HTMLElement>('raw-signals-row');
const rawSignalsToggle = element<HTMLInputElement>('raw-signals-toggle');
const rawSignalsDescription = element<HTMLElement>('raw-signals-description');
const rawAccuracyField = element<HTMLElement>('raw-accuracy-field');
const rawAccuracyLimit = element<HTMLInputElement>('raw-accuracy-limit');
const monthRangeFields = element<HTMLElement>('month-range-fields');
const exactDateFields = element<HTMLElement>('exact-date-fields');
const startSelect = element<HTMLSelectElement>('start-month');
const endSelect = element<HTMLSelectElement>('end-month');
const startDateInput = element<HTMLInputElement>('start-date');
const endDateInput = element<HTMLInputElement>('end-date');
const titleInput = element<HTMLInputElement>('video-title');
const durationSelect = element<HTMLSelectElement>('duration');
const cameraMovementSelect = element<HTMLSelectElement>('camera-movement');
const selectionSummary = element<HTMLParagraphElement>('selection-summary');
const mapConsent = element<HTMLInputElement>('map-consent');
const settingsError = element<HTMLParagraphElement>('settings-error');
const previewCard = element<HTMLElement>('preview-card');
const canvas = element<HTMLCanvasElement>('journey-canvas');
const previewButton = element<HTMLButtonElement>('preview-button');
const createButton = element<HTMLButtonElement>('create-button');
const cancelButton = element<HTMLButtonElement>('cancel-button');
const progress = element<HTMLProgressElement>('export-progress');
const progressLabel = element<HTMLSpanElement>('progress-label');
const errorMessage = element<HTMLParagraphElement>('error-message');
const resultVideo = element<HTMLVideoElement>('result-video');
const resultActions = element<HTMLElement>('result-actions');
const shareButton = element<HTMLButtonElement>('share-button');
const downloadLink = element<HTMLAnchorElement>('download-link');
const rawOnlyDialog = element<HTMLDialogElement>('raw-only-dialog');
const openGoogleMapsButton = element<HTMLButtonElement>('open-google-maps');
const continueRawDataButton = element<HTMLButtonElement>('continue-raw-data');

if (import.meta.env.VITE_PREVIEW === 'true') {
  element<HTMLElement>('preview-banner').classList.remove('hidden');
}

let allPoints: GeoPoint[] = [];
let semanticPoints: GeoPoint[] = [];
let rawSignalPoints: RawSignalPoint[] = [];
let rawSignalProcessing: RawSignalProcessingResult | null = null;
let pendingRawOnlyImport: { data: unknown; sourceName: string } | null = null;
let months: MonthOption[] = [];
let prepared: PreparedJourney | null = null;
let selectedSignature = '';
let resultUrl: string | null = null;
let resultFile: File | null = null;
let previewAnimation = 0;
let encodingSupported = false;
let compatibilityChecked = false;
let isExporting = false;
let isPreparing = false;
let exportController: AbortController | null = null;

function setError(message: string | null): void {
  errorMessage.textContent = message ?? '';
  errorMessage.classList.toggle('hidden', !message);
}

function setSettingsError(message: string | null): void {
  settingsError.textContent = message ?? '';
  settingsError.classList.toggle('hidden', !message);
}

function populateMonths(select: HTMLSelectElement, options: MonthOption[]): void {
  select.replaceChildren(...options.map(({ key, label }) => new Option(label, key)));
}

function rebuildRawSignalProcessing(): boolean {
  const trimmed = rawAccuracyLimit.value.trim();
  const limit = trimmed === '' ? null : Number(trimmed);
  if (limit !== null && (!Number.isFinite(limit) || limit < 0)) {
    setSettingsError('Enter a non-negative accuracy limit, or leave it blank.');
    rawAccuracyLimit.focus();
    return false;
  }
  rawSignalProcessing = processRawSignals(rawSignalPoints, limit);
  return true;
}

function currentPoints(): GeoPoint[] {
  if (rawSignalsToggle.checked) {
    return rebuildRawSignalProcessing() ? rawSignalProcessing?.points ?? [] : [];
  }
  if (exactDateToggle.checked) {
    return selectDateRange(semanticPoints, startDateInput.value, endDateInput.value);
  }
  return selectRange(semanticPoints, startSelect.value, endSelect.value);
}

function formatInputDate(value: string): string {
  const [year, month, day] = value.split('-').map(Number);
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(year, month - 1, day));
}

function currentPeriodLabel(): string {
  if (rawSignalsToggle.checked) return 'Raw location data';
  if (exactDateToggle.checked) {
    const start = formatInputDate(startDateInput.value);
    const end = formatInputDate(endDateInput.value);
    return startDateInput.value === endDateInput.value ? start : `${start} – ${end}`;
  }
  const start = months.find((month) => month.key === startSelect.value)?.label ?? startSelect.value;
  const end = months.find((month) => month.key === endSelect.value)?.label ?? endSelect.value;
  return startSelect.value === endSelect.value ? start : `${start} – ${end}`;
}

function currentRangeSignature(): string {
  if (rawSignalsToggle.checked) return `raw:${rawAccuracyLimit.value.trim()}`;
  return exactDateToggle.checked
    ? `dates:${startDateInput.value}:${endDateInput.value}`
    : `months:${startSelect.value}:${endSelect.value}`;
}

function selectedDistanceKm(points: GeoPoint[]): number {
  return cumulativeDistances(points).at(-1) ?? 0;
}

function refreshActionAvailability(points = currentPoints()): void {
  const hasJourney = points.length >= 2 && selectedDistanceKm(points) > 0;
  previewButton.disabled = isExporting || isPreparing || !hasJourney;
  createButton.disabled = isExporting || isPreparing || !hasJourney || !encodingSupported;
  if (!compatibilityChecked) {
    createButton.title = 'Checking browser video support.';
  } else if (!encodingSupported) {
    createButton.title = 'MP4 creation requires Safari 16.4 or newer with H.264 encoding support.';
  } else if (!hasJourney) {
    createButton.title = 'Select a period containing at least two different locations.';
  } else {
    createButton.removeAttribute('title');
  }
}

function updateSelection(): void {
  cancelAnimationFrame(previewAnimation);
  setSettingsError(null);
  if (!rawSignalsToggle.checked && exactDateToggle.checked) {
    if (startDateInput.value > endDateInput.value) endDateInput.value = startDateInput.value;
  } else if (!rawSignalsToggle.checked && startSelect.value > endSelect.value) {
    endSelect.value = startSelect.value;
  }

  const points = currentPoints();
  const distanceKm = selectedDistanceKm(points);
  if (points.length === 0) {
    selectionSummary.textContent = 'No locations in this period';
  } else if (points.length === 1) {
    selectionSummary.textContent = '1 location point · Choose a wider period';
  } else if (distanceKm <= 0) {
    selectionSummary.textContent = `${points.length.toLocaleString()} location points · No movement`;
  } else {
    const estimate = rawSignalsToggle.checked ? 'Estimated ' : 'About ';
    const ignored = rawSignalsToggle.checked && rawSignalProcessing?.rejectedCount
      ? ` · ${rawSignalProcessing.rejectedCount.toLocaleString()} noisy or inaccurate points ignored`
      : '';
    selectionSummary.textContent = `${points.length.toLocaleString()} location points · ${estimate}${Math.round(distanceKm).toLocaleString()} km${ignored}`;
  }
  prepared = null;
  selectedSignature = '';
  refreshActionAvailability(points);
}

async function getPreparedJourney(signal?: AbortSignal): Promise<PreparedJourney> {
  const cameraMovement = cameraMovementSelect.value as CameraMovement;
  const durationSeconds = Number(durationSelect.value);
  const signature = `${currentRangeSignature()}:camera:${cameraMovement}:duration:${durationSeconds}`;
  if (prepared && signature === selectedSignature) return prepared;
  if (signal?.aborted) throw new DOMException('Video creation was cancelled.', 'AbortError');
  progressLabel.textContent = 'Preparing map';
  const nextJourney = await prepareJourney(
    currentPoints(),
    canvas.width,
    cameraMovement,
    durationSeconds,
    signal,
    (completed, total) => {
      progressLabel.textContent = `Preparing map ${completed}/${total}`;
    },
  );
  if (signal?.aborted) throw new DOMException('Video creation was cancelled.', 'AbortError');
  prepared = nextJourney;
  selectedSignature = signature;
  return nextJourney;
}

function requireMapConsent(): boolean {
  if (mapConsent.checked) return true;
  setSettingsError('Confirm the map privacy notice before requesting map images from CARTO.');
  mapConsent.focus();
  return false;
}

function parseTimelineText(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new TimelineParseError('malformed-json', 'This is not a valid or complete JSON file.');
  }
}

function applyTimeline(data: unknown, sourceName: string, useRawOnly = false): void {
  rawSignalPoints = parseRawSignalsJson(data);
  rawSignalProcessing = processRawSignals(rawSignalPoints, Number(rawAccuracyLimit.value));
  semanticPoints = useRawOnly ? [] : parseTimelineJson(data);
  allPoints = useRawOnly ? rawSignalProcessing.points : semanticPoints;
  if (allPoints.length === 0) {
    throw new TimelineParseError('no-usable-locations', 'This Timeline export contains no usable location points.');
  }
  months = availableMonths(allPoints);
  populateMonths(startSelect, months);
  populateMonths(endSelect, months);
  startSelect.value = months[0].key;
  endSelect.value = months.at(-1)?.key ?? months[0].key;
  const dateKeys = allPoints.map(pointDateKey).sort();
  const firstDate = dateKeys[0] ?? localDateKey(allPoints[0].instant);
  const lastDate = dateKeys.at(-1) ?? firstDate;
  startDateInput.min = firstDate;
  startDateInput.max = lastDate;
  endDateInput.min = firstDate;
  endDateInput.max = lastDate;
  startDateInput.value = firstDate;
  endDateInput.value = lastDate;
  exactDateToggle.checked = false;
  rawSignalsToggle.checked = useRawOnly;
  rawSignalsRow.classList.toggle('hidden', useRawOnly || rawSignalPoints.length === 0);
  rawSignalsDescription.classList.toggle('hidden', !useRawOnly);
  rawAccuracyField.classList.toggle('hidden', !useRawOnly);
  periodControls.classList.toggle('hidden', useRawOnly);
  monthRangeFields.classList.remove('hidden');
  exactDateFields.classList.add('hidden');
  mapConsent.checked = false;
  settingsCard.classList.remove('hidden');
  previewCard.classList.add('hidden');
  const timezoneNote = allPoints.some((point) => point.timeZoneMissing)
    ? ' · Timezone missing, preserving exported route order'
    : '';
  const sourceNote = useRawOnly ? ' · Raw location fallback' : '';
  fileStatus.textContent = `${sourceName} · ${allPoints.length.toLocaleString()} valid points from ${months[0].label} to ${months.at(-1)?.label}${sourceNote}${timezoneNote}`;
  updateSelection();
}

async function loadTimeline(file: File): Promise<void> {
  setError(null);
  setSettingsError(null);
  fileStatus.textContent = `Reading ${file.name}…`;
  const data = parseTimelineText(await file.text());
  try {
    applyTimeline(data, file.name);
  } catch (error) {
    const rawPoints = parseRawSignalsJson(data);
    if (error instanceof TimelineParseError && error.reason === 'raw-signals-only' && rawPoints.length > 0) {
      pendingRawOnlyImport = { data, sourceName: file.name };
      fileStatus.textContent = 'Only raw location data found';
      rawOnlyDialog.showModal();
      return;
    }
    throw error;
  }
}

async function requestWakeLock(): Promise<WakeLockSentinel | null> {
  try {
    return await navigator.wakeLock.request('screen');
  } catch {
    return null;
  }
}

fileInput.addEventListener('change', async () => {
  const file = fileInput.files?.[0];
  if (!file) return;
  try {
    await loadTimeline(file);
  } catch (error) {
    settingsCard.classList.add('hidden');
    fileStatus.textContent = 'Timeline could not be loaded';
    setError(error instanceof Error ? error.message : 'The selected file could not be read.');
    previewCard.classList.remove('hidden');
  }
});

sampleButton.addEventListener('click', async () => {
  setError(null);
  setSettingsError(null);
  fileStatus.textContent = 'Loading fictional sample…';
  try {
    const response = await fetch(`${import.meta.env.BASE_URL}sample-timeline.json`);
    if (!response.ok) throw new Error('The fictional sample could not be loaded.');
    applyTimeline(parseTimelineText(await response.text()), 'Fictional sample');
  } catch (error) {
    settingsCard.classList.add('hidden');
    fileStatus.textContent = 'Sample could not be loaded';
    setError(error instanceof Error ? error.message : 'The fictional sample could not be loaded.');
    previewCard.classList.remove('hidden');
  }
});

startSelect.addEventListener('change', updateSelection);
endSelect.addEventListener('change', updateSelection);
startDateInput.addEventListener('change', updateSelection);
endDateInput.addEventListener('change', updateSelection);
durationSelect.addEventListener('change', updateSelection);
cameraMovementSelect.addEventListener('change', updateSelection);
exactDateToggle.addEventListener('change', () => {
  monthRangeFields.classList.toggle('hidden', exactDateToggle.checked);
  exactDateFields.classList.toggle('hidden', !exactDateToggle.checked);
  updateSelection();
});
rawSignalsToggle.addEventListener('change', () => {
  periodControls.classList.toggle('hidden', rawSignalsToggle.checked);
  rawSignalsDescription.classList.toggle('hidden', !rawSignalsToggle.checked);
  rawAccuracyField.classList.toggle('hidden', !rawSignalsToggle.checked);
  updateSelection();
});
rawAccuracyLimit.addEventListener('input', updateSelection);
mapConsent.addEventListener('change', () => {
  if (mapConsent.checked) setSettingsError(null);
});

openGoogleMapsButton.addEventListener('click', () => {
  window.open('https://www.google.com/maps', '_blank', 'noopener');
  pendingRawOnlyImport = null;
  rawOnlyDialog.close();
  settingsCard.classList.add('hidden');
  fileStatus.textContent = 'Export your Timeline again after visits and trips appear, then load the new file here.';
});

continueRawDataButton.addEventListener('click', () => {
  const pending = pendingRawOnlyImport;
  if (!pending) return;
  pendingRawOnlyImport = null;
  rawOnlyDialog.close();
  try {
    applyTimeline(pending.data, pending.sourceName, true);
  } catch (error) {
    settingsCard.classList.add('hidden');
    fileStatus.textContent = 'Timeline could not be loaded';
    setError(error instanceof Error ? error.message : 'The selected file could not be read.');
    previewCard.classList.remove('hidden');
  }
});

rawOnlyDialog.addEventListener('cancel', () => {
  pendingRawOnlyImport = null;
  settingsCard.classList.add('hidden');
  fileStatus.textContent = 'Raw location import cancelled';
});

previewButton.addEventListener('click', async () => {
  if (!requireMapConsent()) return;
  cancelAnimationFrame(previewAnimation);
  setError(null);
  resultActions.classList.add('hidden');
  resultVideo.classList.add('hidden');
  previewCard.classList.remove('hidden');
  previewCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
  isPreparing = true;
  refreshActionAvailability();
  try {
    const journey = await getPreparedJourney();
    const started = performance.now();
    const previewJourneyDuration = Math.min(8, Number(durationSelect.value));
    const previewDuration = totalDurationSeconds(previewJourneyDuration);
    const tick = (now: number): void => {
      const elapsedSeconds = Math.min(previewDuration, (now - started) / 1000);
      const fraction = elapsedSeconds / previewDuration;
      drawFrame(
        canvas,
        journey,
        frameAtElapsedSeconds(elapsedSeconds, previewJourneyDuration),
        titleInput.value.trim(),
        currentPeriodLabel(),
      );
      progressLabel.textContent = fraction < 1 ? 'Previewing' : 'Preview complete';
      if (fraction < 1) previewAnimation = requestAnimationFrame(tick);
    };
    previewAnimation = requestAnimationFrame(tick);
  } catch (error) {
    setError(error instanceof Error ? error.message : 'Preview failed.');
  } finally {
    isPreparing = false;
    refreshActionAvailability();
  }
});

cancelButton.addEventListener('click', () => {
  cancelButton.disabled = true;
  progressLabel.textContent = 'Cancelling…';
  exportController?.abort();
});

createButton.addEventListener('click', async () => {
  if (!requireMapConsent()) return;
  cancelAnimationFrame(previewAnimation);
  setError(null);
  resultActions.classList.add('hidden');
  resultVideo.classList.add('hidden');
  previewCard.classList.remove('hidden');
  progress.classList.remove('hidden');
  cancelButton.classList.remove('hidden');
  cancelButton.disabled = false;
  progress.value = 0;
  isExporting = true;
  refreshActionAvailability();
  previewCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
  exportController = new AbortController();
  const wakeLock = await requestWakeLock();
  try {
    const journey = await getPreparedJourney(exportController.signal);
    progressLabel.textContent = 'Creating MP4';
    const blob = await createJourneyMp4(canvas, journey, {
      durationSeconds: Number(durationSelect.value),
      title: titleInput.value.trim() || 'My Journey',
      periodLabel: currentPeriodLabel(),
      signal: exportController.signal,
      onProgress: (fraction) => {
        progress.value = fraction;
        progressLabel.textContent = `Creating MP4 ${Math.round(fraction * 100)}%`;
      },
    });
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    resultUrl = URL.createObjectURL(blob);
    resultFile = new File([blob], 'timeline-journey.mp4', { type: 'video/mp4' });
    downloadLink.href = resultUrl;
    resultVideo.src = resultUrl;
    resultVideo.classList.remove('hidden');
    resultActions.classList.remove('hidden');
    progressLabel.textContent = `Video ready · ${(blob.size / 1_000_000).toFixed(1)} MB`;
    const shareData = { files: [resultFile] };
    const canShare = typeof navigator.share === 'function'
      && (typeof navigator.canShare !== 'function' || navigator.canShare(shareData));
    shareButton.hidden = !canShare;
  } catch (error) {
    if (exportController.signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
      progressLabel.textContent = 'Video creation cancelled';
      progress.value = 0;
    } else {
      setError(error instanceof Error ? error.message : 'Video creation failed.');
      progressLabel.textContent = 'Could not create video';
    }
  } finally {
    await wakeLock?.release().catch(() => undefined);
    exportController = null;
    isExporting = false;
    cancelButton.classList.add('hidden');
    refreshActionAvailability();
  }
});

shareButton.addEventListener('click', async () => {
  if (!resultFile || typeof navigator.share !== 'function') return;
  try {
    await navigator.share({ files: [resultFile], title: titleInput.value.trim() || 'My Journey' });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return;
    setError('The iPhone share sheet could not be opened. Use Download MP4 instead.');
  }
});

void canCreateMp4(canvas.width, canvas.height).then((supported) => {
  compatibilityChecked = true;
  encodingSupported = supported;
  compatibilityStatus.textContent = supported
    ? 'This browser can create H.264 MP4 video.'
    : 'Preview only. MP4 creation requires Safari 16.4 or newer with H.264 support.';
  refreshActionAvailability();
});

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register(`${import.meta.env.BASE_URL}service-worker.js`);
  });
}
