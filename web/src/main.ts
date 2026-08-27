import './style.css';
import { frameAtElapsedSeconds, totalDurationSeconds } from './animation';
import { AppError } from './errors';
import { cumulativeDistances } from './geo';
import {
  isDistanceUnitPreference,
  readDistanceUnitPreference,
  resolveDistanceUnit,
  writeDistanceUnitPreference,
} from './distance-unit';
import {
  activeLocale,
  createI18n,
  formattingLocale,
  isLanguagePreference,
  readLanguagePreference,
  writeLanguagePreference,
} from './i18n';
import { applyStrings, syncDocumentLang } from './i18n-dom';
import { filterLocationOutliers } from './outlier';
import { formatRawDateRange } from './raw-range';
import { drawFrame, prepareJourney, previewCanvasSize } from './renderer';
import { selectTimelineModePoints } from './selection';
import {
  availableMonths,
  localDateKey,
  parseRawSignalsJson,
  parseTimelineJson,
  pointDateKey,
  processRawSignals,
  TimelineParseError,
} from './timeline';
import type { I18n, LanguagePreference, TextKey } from './i18n';
import type { DistanceUnit, DistanceUnitPreference } from './distance-unit';
import type { LocationFilterMode } from './outlier';
import type { OverlayText } from './renderer';
import type { RawSignalPoint, RawSignalProcessingResult, TimelineParseReason } from './timeline';
import type {
  CameraMovement,
  GeoPoint,
  MonthOption,
  PreparedJourney,
  RenderSize,
  TimelineFrame,
} from './types';
import type { VideoFormat, VideoFormatSupport, VideoFrameRate } from './video';
import {
  ALL_VIDEO_FORMATS,
  createJourneyMp4,
  hasVideoEncoder,
  probeVideoFormats,
  resolveVideoFormat,
  VIDEO_FRAME_RATES,
  VIDEO_FORMATS,
  videoFormatAtFrameRate,
  videoFormatByKey,
  videoFormatSupportKey,
} from './video';

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) throw new Error(`Missing element #${id}`);
  return found as T;
}

const fileInput = element<HTMLInputElement>('timeline-file');
const sampleButton = element<HTMLButtonElement>('sample-button');
const fileStatus = element<HTMLParagraphElement>('file-status');
const compatibilityStatus = element<HTMLParagraphElement>('compatibility-status');
const languageSelect = element<HTMLSelectElement>('app-language');
const languageWarning = element<HTMLParagraphElement>('language-warning');
const distanceUnitSelect = element<HTMLSelectElement>('distance-unit');
const settingsCard = element<HTMLElement>('settings-card');
const exactDateToggle = element<HTMLInputElement>('exact-date-toggle');
const periodControls = element<HTMLElement>('period-controls');
const rawSignalsRow = element<HTMLElement>('raw-signals-row');
const rawSignalsToggle = element<HTMLInputElement>('raw-signals-toggle');
const rawSignalsDescription = element<HTMLElement>('raw-signals-description');
const rawDateRange = element<HTMLParagraphElement>('raw-date-range');
const rawAccuracyField = element<HTMLElement>('raw-accuracy-field');
const rawAccuracyLimit = element<HTMLInputElement>('raw-accuracy-limit');
const locationFilterField = element<HTMLElement>('location-filter-field');
const locationFilterSelect = element<HTMLSelectElement>('location-filter');
const monthRangeFields = element<HTMLElement>('month-range-fields');
const exactDateFields = element<HTMLElement>('exact-date-fields');
const startSelect = element<HTMLSelectElement>('start-month');
const endSelect = element<HTMLSelectElement>('end-month');
const startDateInput = element<HTMLInputElement>('start-date');
const endDateInput = element<HTMLInputElement>('end-date');
const titleInput = element<HTMLInputElement>('video-title');
const durationSelect = element<HTMLSelectElement>('duration');
const cameraMovementSelect = element<HTMLSelectElement>('camera-movement');
const formatSelect = element<HTMLSelectElement>('video-format');
const frameRateSelect = element<HTMLSelectElement>('frame-rate');
const formatWarning = element<HTMLParagraphElement>('format-warning');
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

function browserLanguages(): readonly string[] {
  return navigator.languages ?? [navigator.language];
}

/** Resolves the catalog and the Intl formatting tag together, so the two can never drift. */
function buildI18n(preference: LanguagePreference): I18n {
  const tags = browserLanguages();
  const locale = activeLocale(preference, tags);
  return createI18n(locale, formattingLocale(preference, tags, locale));
}

let languagePreference: LanguagePreference = readLanguagePreference();
let i18n: I18n = buildI18n(languagePreference);
let distanceUnitPreference: DistanceUnitPreference = readDistanceUnitPreference();

function currentDistanceUnit(): DistanceUnit {
  return resolveDistanceUnit(distanceUnitPreference, browserLanguages());
}

/** Where the loaded points came from. The sample has no filename, so it carries a catalog key. */
interface TimelineSource {
  readonly sample: boolean;
  readonly name: string;
}

/**
 * Every message the app shows is kept as the state it was derived from, never as the finished
 * string, so a language switch can re-render it instead of leaving stale text behind. The
 * `text` variant carries a message that was never in the catalog, such as the message of an
 * unrecognised Error.
 */
type MessageState =
  | { readonly kind: 'key'; readonly key: TextKey }
  | { readonly kind: 'text'; readonly text: string };

type FileStatusState =
  | { readonly kind: 'key'; readonly key: TextKey }
  | { readonly kind: 'reading'; readonly name: string }
  | {
    readonly kind: 'loaded';
    readonly source: TimelineSource;
    readonly count: number;
    readonly firstMonthKey: string;
    readonly lastMonthKey: string;
    readonly rawFallback: boolean;
    readonly timezoneMissing: boolean;
  };

type ProgressState =
  | { readonly kind: 'key'; readonly key: TextKey }
  | { readonly kind: 'preparing'; readonly completed: number; readonly total: number }
  | { readonly kind: 'creating'; readonly fraction: number }
  | { readonly kind: 'ready'; readonly bytes: number };

const PARSE_ERROR_KEYS: Readonly<Record<TimelineParseReason, TextKey>> = {
  'malformed-json': 'errorMalformedJson',
  'legacy-format': 'errorLegacyFormat',
  'raw-signals-only': 'errorRawSignalsOnly',
  'unsupported-format': 'errorUnsupportedFormat',
  'no-usable-locations': 'errorNoUsableLocations',
};

let allPoints: GeoPoint[] = [];
let semanticPoints: GeoPoint[] = [];
let filteredPoints: GeoPoint[] = [];
let rawSignalPoints: RawSignalPoint[] = [];
let rawSignalProcessing: RawSignalProcessingResult | null = null;
let pendingRawOnlyImport: { data: unknown; source: TimelineSource } | null = null;
let months: MonthOption[] = [];
let prepared: PreparedJourney | null = null;
let selectedSignature = '';
let resultUrl: string | null = null;
let resultFile: File | null = null;
let previewAnimation = 0;
let hasEncoder = false;
let formatSupport: VideoFormatSupport | null = null;
let compatibilityChecked = false;
let isExporting = false;
let isPreparing = false;
let exportController: AbortController | null = null;
let lastPreviewFrame: TimelineFrame | null = null;
let previewSizeDirty = false;
let resizeTimer = 0;
let pixelRatioQuery: MediaQueryList | null = null;
let errorState: MessageState | null = null;
let settingsErrorKey: TextKey | null = null;
let compatibilityKey: TextKey = 'compatibilityChecking';
let fileStatusState: FileStatusState = { kind: 'key', key: 'fileStatusEmpty' };
let progressState: ProgressState = { kind: 'key', key: 'progressReady' };

/** Dragging a desktop window fires resize continuously, and every applied size clears the bitmap. */
const PREVIEW_RESIZE_DEBOUNCE_MS = 150;

function messageText(state: MessageState): string {
  return state.kind === 'key' ? i18n.t(state.key) : state.text;
}

/**
 * Errors that carry a catalog code are translated; a TimelineParseError is mapped by its
 * reason so timeline.ts can keep its English developer message; anything else falls back to
 * the Error message, which is what the app showed before the catalog existed.
 */
function describeError(error: unknown, fallback: TextKey): MessageState {
  if (error instanceof TimelineParseError) return { kind: 'key', key: PARSE_ERROR_KEYS[error.reason] };
  if (error instanceof AppError) return { kind: 'key', key: error.code };
  if (error instanceof Error) return { kind: 'text', text: error.message };
  return { kind: 'key', key: fallback };
}

function renderErrorMessage(): void {
  errorMessage.textContent = errorState === null ? '' : messageText(errorState);
  errorMessage.classList.toggle('hidden', errorState === null);
}

function setError(state: MessageState | null): void {
  errorState = state;
  renderErrorMessage();
}

function renderSettingsError(): void {
  settingsError.textContent = settingsErrorKey === null ? '' : i18n.t(settingsErrorKey);
  settingsError.classList.toggle('hidden', settingsErrorKey === null);
}

function setSettingsError(key: TextKey | null): void {
  settingsErrorKey = key;
  renderSettingsError();
}

function renderCompatibilityStatus(): void {
  compatibilityStatus.textContent = i18n.t(compatibilityKey);
}

function monthLabel(key: string): string {
  return months.find((month) => month.key === key)?.label ?? key;
}

function sourceLabel(source: TimelineSource): string {
  return source.sample ? i18n.t('sampleSourceName') : source.name;
}

function renderFileStatus(): void {
  const state = fileStatusState;
  if (state.kind === 'key') {
    fileStatus.textContent = i18n.t(state.key);
    return;
  }
  if (state.kind === 'reading') {
    fileStatus.textContent = i18n.t('fileStatusReading', { name: state.name });
    return;
  }
  // Whole clauses joined by the catalog separator, never suffix fragments: a language that
  // orders these differently can reorder the clauses, which a ' · ' suffix cannot express.
  fileStatus.textContent = i18n.join(
    i18n.t('fileStatusLoaded', {
      count: state.count,
      source: sourceLabel(state.source),
      firstMonth: monthLabel(state.firstMonthKey),
      lastMonth: monthLabel(state.lastMonthKey),
    }),
    state.rawFallback ? i18n.t('fileStatusRawFallback') : '',
    state.timezoneMissing ? i18n.t('fileStatusTimezoneMissing') : '',
  );
}

function setFileStatus(state: FileStatusState): void {
  fileStatusState = state;
  renderFileStatus();
}

function renderProgressLabel(): void {
  const state = progressState;
  switch (state.kind) {
    case 'key':
      progressLabel.textContent = i18n.t(state.key);
      return;
    case 'preparing':
      progressLabel.textContent = i18n.t('progressPreparingMapCount', {
        completed: state.completed,
        total: state.total,
      });
      return;
    case 'creating':
      progressLabel.textContent = i18n.t('progressCreatingPercent', {
        percent: i18n.formatPercent(state.fraction),
      });
      return;
    case 'ready':
      progressLabel.textContent = i18n.t('progressVideoReady', {
        size: i18n.formatNumber(state.bytes / 1_000_000, {
          minimumFractionDigits: 1,
          maximumFractionDigits: 1,
        }),
      });
  }
}

function setProgress(state: ProgressState): void {
  progressState = state;
  renderProgressLabel();
}

function populateMonths(select: HTMLSelectElement, options: MonthOption[]): void {
  select.replaceChildren(...options.map(({ key, label }) => new Option(label, key)));
}

function rebuildRawSignalProcessing(): boolean {
  const trimmed = rawAccuracyLimit.value.trim();
  const limit = trimmed === '' ? null : Number(trimmed);
  if (limit !== null && (!Number.isFinite(limit) || limit < 0)) {
    rawSignalProcessing = null;
    setSettingsError('errorAccuracyLimit');
    rawAccuracyLimit.focus();
    return false;
  }
  rawSignalProcessing = processRawSignals(rawSignalPoints, limit);
  return true;
}

function currentFilterMode(): LocationFilterMode {
  return locationFilterSelect.value === 'off' ? 'off' : 'conservative';
}

function rebuildFilteredPoints(): void {
  filteredPoints = filterLocationOutliers(semanticPoints, currentFilterMode()).points;
}

function selectSemanticRange(source: GeoPoint[]): GeoPoint[] {
  return selectTimelineModePoints(false, [], source, {
    exactDates: exactDateToggle.checked,
    startMonth: startSelect.value,
    endMonth: endSelect.value,
    startDate: startDateInput.value,
    endDate: endDateInput.value,
  });
}

function currentPoints(): GeoPoint[] {
  if (rawSignalsToggle.checked) {
    if (!rebuildRawSignalProcessing()) return [];
    return selectTimelineModePoints(true, rawSignalProcessing?.points ?? [], filteredPoints, {
      exactDates: exactDateToggle.checked,
      startMonth: startSelect.value,
      endMonth: endSelect.value,
      startDate: startDateInput.value,
      endDate: endDateInput.value,
    });
  }
  return selectSemanticRange(filteredPoints);
}

function formatInputDate(value: string): string {
  const [year, month, day] = value.split('-').map(Number);
  return i18n.formatMediumDate(new Date(year, month - 1, day));
}

function currentPeriodLabel(): string {
  if (rawSignalsToggle.checked) return i18n.t('periodRawLocationData');
  if (exactDateToggle.checked) {
    const start = formatInputDate(startDateInput.value);
    const end = formatInputDate(endDateInput.value);
    return startDateInput.value === endDateInput.value ? start : i18n.t('periodRange', { start, end });
  }
  const start = monthLabel(startSelect.value);
  const end = monthLabel(endSelect.value);
  return startSelect.value === endSelect.value ? start : i18n.t('periodRange', { start, end });
}

/** The renderer holds no copy, so the title fallback is resolved here against the catalog. */
function overlayText(): OverlayText {
  const unit = currentDistanceUnit();
  const exportI18n = i18n;
  return {
    title: titleInput.value.trim() || i18n.t('defaultVideoTitle'),
    periodLabel: currentPeriodLabel(),
    separator: i18n.strings.listSeparator,
    formatDistance: (kilometers) => exportI18n.formatDistance(kilometers, unit),
  };
}

function baseFormat(): VideoFormat {
  return videoFormatByKey(formatSelect.value) ?? VIDEO_FORMATS[0];
}

function selectedFrameRate(format = baseFormat()): VideoFrameRate {
  if (frameRateSelect.value === 'recommended') return format.frameRate as VideoFrameRate;
  const value = Number(frameRateSelect.value);
  return VIDEO_FRAME_RATES.includes(value as VideoFrameRate) ? value as VideoFrameRate : 24;
}

function currentFormat(): VideoFormat {
  const format = baseFormat();
  return videoFormatAtFrameRate(format, selectedFrameRate(format));
}

function renderFrameRateOptions(): void {
  const recommended = frameRateSelect.querySelector<HTMLOptionElement>('option[value="recommended"]');
  if (recommended) {
    recommended.textContent = i18n.t('frameRateRecommended', { fps: baseFormat().frameRate });
  }
  VIDEO_FRAME_RATES.forEach((fps) => {
    const option = frameRateSelect.querySelector<HTMLOptionElement>(`option[value="${fps}"]`);
    if (option) option.textContent = i18n.t('frameRateValue', { fps });
  });
}

function stopPreview(): void {
  cancelAnimationFrame(previewAnimation);
  previewAnimation = 0; // rAF ids are positive, so 0 is a safe idle sentinel
  previewSizeDirty = false;
}

/** Assigning canvas.width clears the bitmap, so every caller must stop the preview loop first. */
function setCanvasSize(size: RenderSize): boolean {
  if (canvas.width === size.width && canvas.height === size.height) return false;
  canvas.width = size.width;
  canvas.height = size.height;
  return true;
}

/** The CSS box follows the selected format whatever the backing store holds. */
function applyPreviewAspect(): void {
  const format = currentFormat();
  canvas.style.setProperty('--preview-aspect', String(format.width / format.height));
}

/**
 * The canvas is laid out by min(100%, --preview-max-height * --preview-aspect), so its own
 * border box is the only correct measurement: the card is much wider than a portrait preview.
 * getBoundingClientRect flushes layout, so the value is final in the same task the card is
 * shown. A hidden card measures 0, which previewCanvasSize turns into the exact format size.
 */
function applyPreviewCanvasSize(): boolean {
  const format = currentFormat();
  return setCanvasSize(previewCanvasSize(
    { width: format.width, height: format.height },
    canvas.getBoundingClientRect().width,
    window.devicePixelRatio,
  ));
}

/**
 * Idempotent. Restores the exact format size, which createJourneyMp4 requires and CanvasSource
 * captures. Called at startup, on a format change, and immediately before every export.
 */
function applyVideoFormat(): void {
  const format = currentFormat();
  applyPreviewAspect();
  if (setCanvasSize({ width: format.width, height: format.height })) {
    setProgress({ kind: 'key', key: 'progressReady' });
    progress.classList.add('hidden');
    progress.value = 0;
  }
}

function onViewportChange(): void {
  if (isExporting) return;
  window.clearTimeout(resizeTimer);
  resizeTimer = window.setTimeout(applyPreviewResize, PREVIEW_RESIZE_DEBOUNCE_MS);
}

/**
 * The preview follows the display, so a window resize, a browser zoom or a move to another
 * screen has to be re-measured. Exports are exempt: createJourneyMp4 awaits the encoder between
 * drawing a frame and submitting it, and clearing the bitmap in that window would submit a
 * blank frame, while the encoder cannot change frame size mid sequence anyway.
 */
function applyPreviewResize(): void {
  resizeTimer = 0;
  if (isExporting || isPreparing) return; // preparing re-measures after its own await
  if (previewCard.classList.contains('hidden')) return;
  if (previewAnimation !== 0) {
    previewSizeDirty = true; // the next tick resizes and redraws in one rAF callback
    return;
  }
  if (!prepared || !lastPreviewFrame) return;
  if (!applyPreviewCanvasSize()) return;
  drawFrame(canvas, prepared, lastPreviewFrame, overlayText());
}

/** devicePixelRatio changes silently when the window moves to another monitor. */
function watchPixelRatio(): void {
  pixelRatioQuery?.removeEventListener('change', onPixelRatioChange);
  pixelRatioQuery = window.matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`);
  pixelRatioQuery.addEventListener('change', onPixelRatioChange, { once: true });
}

function onPixelRatioChange(): void {
  watchPixelRatio(); // re-arm against the new ratio
  onViewportChange();
}

function isFormatSupported(format: VideoFormat): boolean {
  return formatSupport !== null && resolveVideoFormat(format, formatSupport) !== null;
}

/**
 * The select is disabled while the map is prepared or a video is encoded, so the reason has
 * to be visible text rather than a title attribute, which VoiceOver skips on disabled controls.
 */
function updateFormatWarning(format: VideoFormat, supported: boolean): void {
  const locked = isExporting || isPreparing;
  const unsupported = !locked && formatSupport !== null && !supported;
  let message: string | null = null;
  if (isExporting) message = i18n.t('warnFormatLockedExporting');
  else if (isPreparing) message = i18n.t('warnFormatLockedPreparing');
  else if (unsupported) {
    message = i18n.t('errorFormatUnsupported', {
      width: format.width,
      height: format.height,
      fps: format.frameRate,
    });
  }
  formatWarning.textContent = message ?? '';
  formatWarning.classList.toggle('hidden', message === null);
  formatWarning.classList.toggle('error', unsupported);
  formatSelect.setAttribute('aria-invalid', unsupported ? 'true' : 'false');
  frameRateSelect.setAttribute('aria-invalid', unsupported ? 'true' : 'false');
}

/**
 * Switching language while the map is being prepared or a video is being encoded would relabel
 * a run already in progress, and the overlay of that video was frozen when it started, so the
 * two would disagree. Same reasoning as the format select: the reason is visible text rather
 * than a title attribute, which VoiceOver skips on a disabled control.
 */
function updateLanguageAvailability(): void {
  languageSelect.disabled = isExporting || isPreparing;
  let message: string | null = null;
  if (isExporting) message = i18n.t('languageLockedExporting');
  else if (isPreparing) message = i18n.t('languageLockedPreparing');
  languageWarning.textContent = message ?? '';
  languageWarning.classList.toggle('hidden', message === null);
}

// The format is baked into the prepared journey: camera aspect, per-frame tile zoom,
// the overview safe area and the downloaded tiles all depend on it, so it has to be part
// of the cache key rather than of a single call path that a later change could drop.
// Since drawFrame checks only the aspect ratio, this is the sole guarantee that an export
// receives a journey prepared at the format size. Dropping the format from the key, or adding
// the preview size to it, would silently encode a video from too low a tile zoom.
// The language is deliberately absent: it changes no pixel of the map, and including it would
// throw away every downloaded tile and re-request them from CARTO on every switch.
function currentRangeSignature(): string {
  const format = `:format:${currentFormat().key}`;
  if (rawSignalsToggle.checked) return `raw:${rawAccuracyLimit.value.trim()}${format}`;
  const filter = `:filter:${currentFilterMode()}`;
  return exactDateToggle.checked
    ? `dates:${startDateInput.value}:${endDateInput.value}${filter}${format}`
    : `months:${startSelect.value}:${endSelect.value}${filter}${format}`;
}

function selectedDistanceKm(points: GeoPoint[]): number {
  return cumulativeDistances(points).at(-1) ?? 0;
}

function refreshActionAvailability(points = currentPoints()): void {
  const hasJourney = points.length >= 2 && selectedDistanceKm(points) > 0;
  const format = currentFormat();
  const formatSupported = isFormatSupported(format);
  // Preview never depends on encoder support: an unencodable format is still previewable.
  previewButton.disabled = isExporting || isPreparing || !hasJourney;
  createButton.disabled = isExporting || isPreparing || !hasJourney || !formatSupported;
  formatSelect.disabled = isExporting || isPreparing;
  frameRateSelect.disabled = isExporting || isPreparing;
  if (!compatibilityChecked) {
    createButton.title = i18n.t('hintCheckingSupport');
  } else if (!hasEncoder) {
    createButton.title = i18n.t('hintNoEncoder');
  } else if (!formatSupported) {
    createButton.title = i18n.t('hintFormatUnsupported', {
      width: format.width,
      height: format.height,
      fps: format.frameRate,
    });
  } else if (!hasJourney) {
    createButton.title = i18n.t('hintSelectWiderPeriod');
  } else {
    createButton.removeAttribute('title');
  }
  updateFormatWarning(format, formatSupported);
  updateLanguageAvailability();
}

/**
 * Rebuilds every derived line of the settings card from the current state. Kept apart from
 * updateSelection because a language switch has to redraw this text without discarding the
 * prepared journey and the map tiles that came with it.
 */
function renderSelection(): void {
  const points = currentPoints();
  const showRawRange = rawSignalsToggle.checked && rawSignalProcessing !== null;
  rawDateRange.textContent = showRawRange ? formatRawDateRange(points, i18n) : '';
  rawDateRange.classList.toggle('hidden', !showRawRange);
  const distanceKm = selectedDistanceKm(points);
  const outliersIgnored = rawSignalsToggle.checked
    ? 0
    : Math.max(0, selectSemanticRange(semanticPoints).length - points.length);
  const outlierNote = outliersIgnored > 0
    ? i18n.t('summaryOutliersIgnored', { count: outliersIgnored })
    : '';
  if (points.length === 0) {
    selectionSummary.textContent = i18n.join(i18n.t('summaryNoLocations'), outlierNote);
  } else if (points.length === 1) {
    selectionSummary.textContent = i18n.join(i18n.t('summaryOneLocation'), outlierNote);
  } else if (distanceKm <= 0) {
    selectionSummary.textContent = i18n.join(
      i18n.t('summaryNoMovement', { count: points.length }),
      outlierNote,
    );
  } else {
    // Two whole messages rather than an 'About ' / 'Estimated ' prefix glued to a number: the
    // hedge moves or inflects in several of the nine locales and cannot survive as a fragment.
    const rejected = rawSignalsToggle.checked && rawSignalProcessing?.rejectedCount
      ? i18n.t('summaryRawRejected', { count: rawSignalProcessing.rejectedCount })
      : '';
    selectionSummary.textContent = i18n.join(
      i18n.t(rawSignalsToggle.checked ? 'summaryDistanceEstimated' : 'summaryDistanceAbout', {
        count: points.length,
        distance: i18n.formatDistance(distanceKm, currentDistanceUnit()),
      }),
      rejected,
      outlierNote,
    );
  }
  refreshActionAvailability(points);
}

function updateSelection(): void {
  stopPreview();
  setSettingsError(null);
  if (!rawSignalsToggle.checked && exactDateToggle.checked) {
    if (startDateInput.value > endDateInput.value) endDateInput.value = startDateInput.value;
  } else if (!rawSignalsToggle.checked && startSelect.value > endSelect.value) {
    endSelect.value = startSelect.value;
  }
  prepared = null;
  lastPreviewFrame = null;
  selectedSignature = '';
  renderSelection();
}

async function getPreparedJourney(signal?: AbortSignal): Promise<PreparedJourney> {
  const cameraMovement = cameraMovementSelect.value as CameraMovement;
  const durationSeconds = Number(durationSelect.value);
  const format = currentFormat();
  const signature = `${currentRangeSignature()}:camera:${cameraMovement}:duration:${durationSeconds}`;
  if (prepared && signature === selectedSignature) return prepared;
  if (signal?.aborted) throw new DOMException('Video creation was cancelled.', 'AbortError');
  setProgress({ kind: 'key', key: 'progressPreparingMap' });
  const nextJourney = await prepareJourney(
    currentPoints(),
    { width: format.width, height: format.height },
    cameraMovement,
    durationSeconds,
    signal,
    (completed, total) => {
      setProgress({ kind: 'preparing', completed, total });
    },
  );
  if (signal?.aborted) throw new DOMException('Video creation was cancelled.', 'AbortError');
  prepared = nextJourney;
  selectedSignature = signature;
  return nextJourney;
}

function requireMapConsent(): boolean {
  if (mapConsent.checked) return true;
  setSettingsError('errorMapConsent');
  mapConsent.focus();
  return false;
}

/**
 * Re-applies the whole catalog to the document and repaints every message that was derived
 * rather than authored in the HTML. applyStrings resets those nodes to their catalog defaults,
 * so the state-backed lines have to be rendered after it, not before.
 */
function renderLocalizedText(): void {
  applyStrings(document, i18n);
  syncDocumentLang(i18n);
  renderDistanceUnitOptions();
  renderFrameRateOptions();
  renderCompatibilityStatus();
  renderFileStatus();
  renderProgressLabel();
  renderErrorMessage();
  renderSettingsError();
  updateLanguageAvailability();
}

function renderDistanceUnitOptions(): void {
  const automaticOption = distanceUnitSelect.querySelector<HTMLOptionElement>('option[value="automatic"]');
  if (!automaticOption) throw new Error('Missing automatic distance unit option');
  const resolvedKey = currentDistanceUnit() === 'miles' ? 'distanceUnitMiles' : 'distanceUnitKilometers';
  automaticOption.textContent = i18n.t('distanceUnitAutomaticResolved', {
    automatic: i18n.t('distanceUnitAutomatic'),
    resolved: i18n.t(resolvedKey),
  });
}

function onDistanceUnitChange(): void {
  const value = distanceUnitSelect.value;
  if (!isDistanceUnitPreference(value)) return;
  distanceUnitPreference = value;
  writeDistanceUnitPreference(distanceUnitPreference);
  stopPreview();
  if (!settingsCard.classList.contains('hidden')) renderSelection();
  if (prepared && lastPreviewFrame && !previewCard.classList.contains('hidden')) {
    drawFrame(canvas, prepared, lastPreviewFrame, overlayText());
  }
}

/**
 * Re-renders in place rather than reloading. A reload would lose the picked File, which cannot
 * be restored without a second trip through the Files app, revoke the object URL of a finished
 * but not yet downloaded MP4, and throw away the prepared journey along with every map tile it
 * downloaded, which means another round of requests to CARTO.
 */
function onLanguageChange(): void {
  const value = languageSelect.value;
  if (!isLanguagePreference(value)) return;
  languagePreference = value;
  writeLanguagePreference(languagePreference); // a failed write never blocks the switch
  i18n = buildI18n(languagePreference);
  renderLocalizedText();
  // A running preview would draw consecutive frames in two languages. The last frame is
  // repainted once in the new language instead; restarting the animation was never asked for.
  stopPreview();
  if (months.length > 0) {
    // Month keys are locale independent 'YYYY-MM', so restoring the selection is exact.
    const start = startSelect.value;
    const end = endSelect.value;
    months = availableMonths(allPoints, i18n.formatLocale);
    populateMonths(startSelect, months);
    populateMonths(endSelect, months);
    startSelect.value = start;
    endSelect.value = end;
    renderFileStatus(); // the month labels inside it have just changed
  }
  if (!settingsCard.classList.contains('hidden')) renderSelection();
  if (prepared && lastPreviewFrame && !previewCard.classList.contains('hidden')) {
    drawFrame(canvas, prepared, lastPreviewFrame, overlayText());
  }
}

function parseTimelineText(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new TimelineParseError('malformed-json', 'This is not a valid or complete JSON file.');
  }
}

function applyTimeline(data: unknown, source: TimelineSource, useRawOnly = false): void {
  rawSignalPoints = parseRawSignalsJson(data);
  rawSignalProcessing = processRawSignals(rawSignalPoints, Number(rawAccuracyLimit.value));
  semanticPoints = useRawOnly ? [] : parseTimelineJson(data);
  rebuildFilteredPoints();
  allPoints = useRawOnly ? rawSignalProcessing.points : semanticPoints;
  if (allPoints.length === 0) {
    throw new TimelineParseError('no-usable-locations', 'This Timeline export contains no usable location points.');
  }
  months = availableMonths(allPoints, i18n.formatLocale);
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
  locationFilterField.classList.toggle('hidden', useRawOnly);
  periodControls.classList.toggle('hidden', useRawOnly);
  monthRangeFields.classList.remove('hidden');
  exactDateFields.classList.add('hidden');
  mapConsent.checked = false;
  settingsCard.classList.remove('hidden');
  previewCard.classList.add('hidden');
  setFileStatus({
    kind: 'loaded',
    source,
    count: allPoints.length,
    firstMonthKey: months[0].key,
    lastMonthKey: months.at(-1)?.key ?? months[0].key,
    rawFallback: useRawOnly,
    timezoneMissing: allPoints.some((point) => point.timeZoneMissing),
  });
  updateSelection();
}

async function loadTimeline(file: File): Promise<void> {
  setError(null);
  setSettingsError(null);
  setFileStatus({ kind: 'reading', name: file.name });
  const source: TimelineSource = { sample: false, name: file.name };
  const data = parseTimelineText(await file.text());
  try {
    applyTimeline(data, source);
  } catch (error) {
    const rawPoints = parseRawSignalsJson(data);
    if (error instanceof TimelineParseError && error.reason === 'raw-signals-only' && rawPoints.length > 0) {
      pendingRawOnlyImport = { data, source };
      setFileStatus({ kind: 'key', key: 'fileStatusRawOnly' });
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
    setFileStatus({ kind: 'key', key: 'fileStatusLoadFailed' });
    setError(describeError(error, 'errorFileUnreadable'));
    previewCard.classList.remove('hidden');
  }
});

sampleButton.addEventListener('click', async () => {
  setError(null);
  setSettingsError(null);
  setFileStatus({ kind: 'key', key: 'fileStatusLoadingSample' });
  try {
    const response = await fetch(`${import.meta.env.BASE_URL}sample-timeline.json`);
    if (!response.ok) {
      throw new AppError('errorSampleUnavailable', 'The fictional sample could not be loaded.');
    }
    applyTimeline(parseTimelineText(await response.text()), { sample: true, name: '' });
  } catch (error) {
    settingsCard.classList.add('hidden');
    setFileStatus({ kind: 'key', key: 'fileStatusSampleFailed' });
    setError(describeError(error, 'errorSampleUnavailable'));
    previewCard.classList.remove('hidden');
  }
});

startSelect.addEventListener('change', updateSelection);
endSelect.addEventListener('change', updateSelection);
startDateInput.addEventListener('change', updateSelection);
endDateInput.addEventListener('change', updateSelection);
durationSelect.addEventListener('change', updateSelection);
cameraMovementSelect.addEventListener('change', updateSelection);
languageSelect.addEventListener('change', onLanguageChange);
distanceUnitSelect.addEventListener('change', onDistanceUnitChange);
formatSelect.addEventListener('change', () => {
  stopPreview();
  renderFrameRateOptions();
  applyVideoFormat();
  updateSelection();
});
frameRateSelect.addEventListener('change', () => {
  stopPreview();
  renderSelection();
});
exactDateToggle.addEventListener('change', () => {
  monthRangeFields.classList.toggle('hidden', exactDateToggle.checked);
  exactDateFields.classList.toggle('hidden', !exactDateToggle.checked);
  updateSelection();
});
rawSignalsToggle.addEventListener('change', () => {
  periodControls.classList.toggle('hidden', rawSignalsToggle.checked);
  rawSignalsDescription.classList.toggle('hidden', !rawSignalsToggle.checked);
  rawAccuracyField.classList.toggle('hidden', !rawSignalsToggle.checked);
  locationFilterField.classList.toggle('hidden', rawSignalsToggle.checked);
  updateSelection();
});
rawAccuracyLimit.addEventListener('input', updateSelection);
locationFilterSelect.addEventListener('change', () => {
  rebuildFilteredPoints();
  updateSelection();
});
mapConsent.addEventListener('change', () => {
  if (mapConsent.checked) setSettingsError(null);
});

openGoogleMapsButton.addEventListener('click', () => {
  window.open('https://www.google.com/maps', '_blank', 'noopener');
  pendingRawOnlyImport = null;
  rawOnlyDialog.close();
  settingsCard.classList.add('hidden');
  setFileStatus({ kind: 'key', key: 'fileStatusExportAgain' });
});

continueRawDataButton.addEventListener('click', () => {
  const pending = pendingRawOnlyImport;
  if (!pending) return;
  pendingRawOnlyImport = null;
  rawOnlyDialog.close();
  try {
    applyTimeline(pending.data, pending.source, true);
  } catch (error) {
    settingsCard.classList.add('hidden');
    setFileStatus({ kind: 'key', key: 'fileStatusLoadFailed' });
    setError(describeError(error, 'errorFileUnreadable'));
    previewCard.classList.remove('hidden');
  }
});

rawOnlyDialog.addEventListener('cancel', () => {
  pendingRawOnlyImport = null;
  settingsCard.classList.add('hidden');
  setFileStatus({ kind: 'key', key: 'fileStatusRawImportCancelled' });
});

previewButton.addEventListener('click', async () => {
  if (!requireMapConsent()) return;
  stopPreview();
  // Only the CSS box, not the backing store: the preview size is measured after the await,
  // so bouncing the canvas back to the format size here would clear the bitmap for nothing.
  applyPreviewAspect();
  setError(null);
  resultActions.classList.add('hidden');
  resultVideo.classList.add('hidden');
  previewCard.classList.remove('hidden');
  previewCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
  isPreparing = true;
  refreshActionAvailability();
  try {
    const journey = await getPreparedJourney();
    // Measured here because the card is laid out, nothing has been drawn yet, and preparing
    // the map takes long enough that the device may have been rotated in the meantime.
    applyPreviewCanvasSize();
    previewSizeDirty = false;
    const started = performance.now();
    const previewJourneyDuration = Math.min(8, Number(durationSelect.value));
    const previewDuration = totalDurationSeconds(previewJourneyDuration);
    const tick = (now: number): void => {
      if (previewSizeDirty) {
        // Clearing and redrawing inside one rAF callback is never composited in between.
        previewSizeDirty = false;
        applyPreviewCanvasSize();
      }
      const elapsedSeconds = Math.min(previewDuration, (now - started) / 1000);
      const fraction = elapsedSeconds / previewDuration;
      const animationFrame = frameAtElapsedSeconds(elapsedSeconds, previewJourneyDuration);
      lastPreviewFrame = animationFrame;
      drawFrame(canvas, journey, animationFrame, overlayText());
      setProgress({
        kind: 'key',
        key: fraction < 1 ? 'progressPreviewing' : 'progressPreviewComplete',
      });
      previewAnimation = fraction < 1 ? requestAnimationFrame(tick) : 0;
    };
    previewAnimation = requestAnimationFrame(tick);
  } catch (error) {
    setError(describeError(error, 'errorPreviewFailed'));
  } finally {
    isPreparing = false;
    refreshActionAvailability();
  }
});

cancelButton.addEventListener('click', () => {
  cancelButton.disabled = true;
  setProgress({ kind: 'key', key: 'progressCancelling' });
  exportController?.abort();
});

createButton.addEventListener('click', async () => {
  if (!requireMapConsent()) return;
  const format = formatSupport === null
    ? null
    : resolveVideoFormat(currentFormat(), formatSupport);
  if (!format) {
    const unsupported = currentFormat();
    setError({
      kind: 'text',
      text: i18n.t('errorFormatUnsupported', {
        width: unsupported.width,
        height: unsupported.height,
        fps: unsupported.frameRate,
      }),
    });
    return;
  }
  // Both before the first await: a queued tick would otherwise draw over the restored size,
  // and CanvasSource captures whatever size the canvas has when the export starts.
  stopPreview();
  applyVideoFormat();
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
    setProgress({ kind: 'key', key: 'progressCreating' });
    const blob = await createJourneyMp4(canvas, journey, {
      durationSeconds: Number(durationSelect.value),
      // Frozen here, so the whole video carries one language even if the select were unlocked.
      overlay: overlayText(),
      format,
      signal: exportController.signal,
      onProgress: (fraction) => {
        progress.value = fraction;
        setProgress({ kind: 'creating', fraction });
      },
    });
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    resultUrl = URL.createObjectURL(blob);
    resultFile = new File([blob], 'timeline-journey.mp4', { type: 'video/mp4' });
    downloadLink.href = resultUrl;
    resultVideo.src = resultUrl;
    resultVideo.style.setProperty('--preview-aspect', String(format.width / format.height));
    resultVideo.classList.remove('hidden');
    resultActions.classList.remove('hidden');
    setProgress({ kind: 'ready', bytes: blob.size });
    const shareData = { files: [resultFile] };
    const canShare = typeof navigator.share === 'function'
      && (typeof navigator.canShare !== 'function' || navigator.canShare(shareData));
    shareButton.hidden = !canShare;
  } catch (error) {
    if (exportController.signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
      setProgress({ kind: 'key', key: 'progressCancelled' });
      progress.value = 0;
    } else {
      setError(describeError(error, 'errorExportFailed'));
      setProgress({ kind: 'key', key: 'progressFailed' });
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
    await navigator.share({ files: [resultFile], title: overlayText().title });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return;
    setError({ kind: 'key', key: 'errorShareUnavailable' });
  }
});

function applyFormatSupport(support: VideoFormatSupport): void {
  compatibilityChecked = true;
  formatSupport = support;
  const usable = ALL_VIDEO_FORMATS.filter((format) => support.get(videoFormatSupportKey(format)) != null).length;
  if (usable === ALL_VIDEO_FORMATS.length) {
    compatibilityKey = 'compatibilityFull';
  } else if (usable > 0) {
    compatibilityKey = 'compatibilityPartial';
  } else {
    compatibilityKey = 'compatibilityPreviewOnly';
  }
  renderCompatibilityStatus();
  refreshActionAvailability();
}

// Before anything else touches the DOM: the HTML ships the English source text so a failed
// script still renders a usable page, and this replaces it with the active catalog.
languageSelect.value = languagePreference;
distanceUnitSelect.value = distanceUnitPreference;
renderLocalizedText();
// Safari restores form control values on reload and on bfcache restore without firing
// change, so the canvas has to be synced to the selected format before anything is drawn.
applyVideoFormat();
// Single page, no unmount: the resize listener lives as long as the document, and the pixel
// ratio query re-arms itself so at most one is ever registered.
window.addEventListener('resize', onViewportChange);
watchPixelRatio();
hasEncoder = hasVideoEncoder();
void probeVideoFormats().then(applyFormatSupport);

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register(`${import.meta.env.BASE_URL}service-worker.js`);
  });
}
