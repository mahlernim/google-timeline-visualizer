import { de } from './locales/de';
import { en } from './locales/en';
import { es } from './locales/es';
import { fr } from './locales/fr';
import { ja } from './locales/ja';
import { ko } from './locales/ko';
import { ptBR } from './locales/pt-BR';
import { id } from './locales/id';
import { vi } from './locales/vi';
import { zhCN } from './locales/zh-CN';
import { zhTW } from './locales/zh-TW';
import { convertDistanceFromKilometers } from './distance-unit';
import type { DistanceUnit } from './distance-unit';

import type { LocaleTag } from './language';
export * from './language';

export type PluralCategory = Intl.LDMLPluralRule;

/** 'other' is the one category every CLDR locale defines, so it is the only required form. */
export type PluralEntry =
  & { readonly other: string }
  & { readonly [C in Exclude<PluralCategory, 'other'>]?: string };

/**
 * Every user-visible string in the web app.
 *
 * Rules for catalog authors:
 * - Keys are camelCase and must match /^[a-z][A-Za-z0-9]*$/. `data-i18n-attr` separates pairs
 *   with ';' and attribute from key with ':', so a key may never contain either character.
 * - Placeholders are `{name}`. Every form of one plural entry must use exactly the same set of
 *   placeholders as the English `other` form, so `one` is '{count} location point', never
 *   'One location point'.
 * - Typography is load-bearing: U+2019 in Safari's, U+00B7 in every ' · ' separator, U+2013 in
 *   periodRange, U+00D7 in the format labels, U+2026 for every ellipsis. Do not let a
 *   translation tool substitute ASCII quotes, hyphens or three dots.
 * - Terminology locked to the platform: 'Personal content', 'Export Timeline data',
 *   'Add to Home Screen' and 'Files app' are official Google Maps and iOS labels. Use the
 *   official localized label for each platform, never a literal translation.
 * - Frozen tokens inside translated sentences: Timeline.json, semanticSegments, rawSignals,
 *   CARTO, OpenStreetMap, Google Maps, Safari, H.264, MP4, MB, km, mi, iPhone.
 */
export interface Strings {
  // --- app shell and document metadata ---
  appName: string;
  appShortName: string;
  appDescription: string;
  previewBanner: string;
  headerTitle: string;

  // --- Timeline file card ---
  fileCardTitle: string;
  fileCardIntro: string;
  exportHelpSummary: string;
  exportHelpStep1: string;
  exportHelpStep2: string;
  exportHelpStep3: string;
  exportHelpStep4: string;
  addToHomeScreenHint: string;
  chooseFileButton: string;
  sampleButton: string;
  fileStatusEmpty: string;
  compatibilityChecking: string;
  compatibilityFull: string;
  compatibilityPartial: string;
  compatibilityPreviewOnly: string;

  // --- language field ---
  languageLabel: string;
  languageSystemDefault: string;
  languageLockedExporting: string;
  languageLockedPreparing: string;
  distanceUnitLabel: string;
  distanceUnitAutomatic: string;
  distanceUnitKilometers: string;
  distanceUnitMiles: string;
  /** {automatic} {resolved} */
  distanceUnitAutomaticResolved: string;

  // --- settings card ---
  settingsTitle: string;
  rawSignalsToggle: string;
  rawSignalsDescription: string;
  rawRangeEmpty: string;
  /** {date} */
  rawRangeOnePoint: string;
  /** {count} {date} */
  rawRangeOneDay: string;
  /** {count} {start} {end} */
  rawRangeMultipleDays: string;
  accuracyLimitLabel: string;
  accuracyLimitHelp: string;
  locationFilterLabel: string;
  locationFilterConservative: string;
  locationFilterOff: string;
  locationFilterHelp: string;
  exactDatesToggle: string;
  fromLabel: string;
  toLabel: string;
  startDateLabel: string;
  endDateLabel: string;
  videoTitleLabel: string;
  defaultVideoTitle: string;
  durationLabel: string;
  /** {count} */
  durationSeconds: PluralEntry;
  useRecommendedDuration: PluralEntry;
  cameraMovementLabel: string;
  cameraFixed: string;
  cameraSteady: string;
  cameraDynamic: string;
  cameraCloseUp: string;
  aspectRatioLabel: string;
  aspectSquare: string;
  aspectPortrait: string;
  aspectLandscape: string;
  resolutionLabel: string;
  videoFormatLabel: string;
  formatSquare480: string;
  formatSquare720: string;
  formatSquare1080: string;
  formatPortrait: string;
  formatLandscape: string;
  videoFormatHelp: string;
  frameRateLabel: string;
  frameRateRecommended: string;
  frameRateValue: string;
  frameRateHelp: string;
  privacyNoticeTitle: string;
  privacyNoticeBody: string;
  mapConsentLabel: string;
  privacyPolicyLink: string;
  previewButton: string;
  createButton: string;

  // --- preview card ---
  previewTitle: string;
  progressReady: string;
  progressPreparingMap: string;
  /** {completed} {total} */
  progressPreparingMapCount: string;
  progressPreviewing: string;
  progressPreviewComplete: string;
  progressCreating: string;
  /** {percent} */
  progressCreatingPercent: string;
  progressCancelling: string;
  progressCancelled: string;
  progressFailed: string;
  /** {size} */
  progressVideoReady: string;
  cancelButton: string;
  shareButton: string;
  downloadButton: string;

  // --- footer ---
  footerNoAccount: string;
  footerMapAttribution: string;
  footerThirdPartyNotices: string;
  openTestingLink: string;

  // --- raw-only dialog ---
  rawOnlyDialogTitle: string;
  rawOnlyDialogBody1: string;
  rawOnlyDialogBody2: string;
  openGoogleMapsButton: string;
  continueRawDataButton: string;

  // --- selection summary ---
  /** Joins the clauses of the summary and file status lines. */
  listSeparator: string;
  summaryNoLocations: string;
  summaryOneLocation: string;
  /** {count} */
  summaryNoMovement: PluralEntry;
  /** {count} {distance} */
  summaryDistanceAbout: PluralEntry;
  /** {count} {distance} */
  summaryDistanceEstimated: PluralEntry;
  /** {count} */
  summaryOutliersIgnored: PluralEntry;
  /** {count} */
  summaryRawRejected: PluralEntry;

  // --- file status ---
  /** {source} {count} {firstMonth} {lastMonth} */
  fileStatusLoaded: PluralEntry;
  fileStatusRawFallback: string;
  fileStatusTimezoneMissing: string;
  /** {name} */
  fileStatusReading: string;
  fileStatusLoadingSample: string;
  sampleSourceName: string;
  fileStatusLoadFailed: string;
  fileStatusSampleFailed: string;
  fileStatusRawOnly: string;
  fileStatusExportAgain: string;
  fileStatusRawImportCancelled: string;

  // --- period label, drawn into the exported MP4 ---
  periodRawLocationData: string;
  /** {start} {end} */
  periodRange: string;

  // --- errors ---
  errorAccuracyLimit: string;
  errorMapConsent: string;
  errorMalformedJson: string;
  errorLegacyFormat: string;
  errorRawSignalsOnly: string;
  errorUnsupportedFormat: string;
  errorNoUsableLocations: string;
  errorFileUnreadable: string;
  errorSampleUnavailable: string;
  errorPreviewFailed: string;
  errorExportFailed: string;
  errorShareUnavailable: string;
  errorTooFewPoints: string;
  errorNoEncoder: string;
  /** {width} {height} */
  errorFormatUnsupported: string;
  errorEncoderOutput: string;
  errorEncoderInvalid: string;
  errorCanvasUnavailable: string;
  errorCanvasSize: string;
  errorAspectRatio: string;

  // --- button titles and inline warnings ---
  hintCheckingSupport: string;
  hintNoEncoder: string;
  /** {width} {height} */
  hintFormatUnsupported: string;
  hintSelectWiderPeriod: string;
  warnFormatLockedExporting: string;
  warnFormatLockedPreparing: string;
}

export type StringKey = keyof Strings;
export type PluralKey = { [K in StringKey]: Strings[K] extends PluralEntry ? K : never }[StringKey];
export type TextKey = Exclude<StringKey, PluralKey>;

/**
 * All catalogs are statically imported so they land in the entry chunk.
 *
 * They must never become dynamic `import()`. The generated service worker precaches the
 * complete static build, but keeping catalogs in the entry bundle also avoids a second
 * request before translated first paint. Static imports keep applyStrings synchronous, so a
 * non-English user never sees a frame of English, and a language switch cannot fail with an
 * unrecoverable catalog chunk load error.
 */
export const CATALOGS: Readonly<Record<LocaleTag, Strings>> = {
  en,
  ko,
  ja,
  'zh-CN': zhCN,
  'zh-TW': zhTW,
  es,
  fr,
  de,
  'pt-BR': ptBR,
  id,
  vi,
};

export type Params = Readonly<Record<string, string | number>>;

const PLACEHOLDER_PATTERN = /\{([a-zA-Z][a-zA-Z0-9_]*)\}/g;

/**
 * One pass over the template, so a substituted value is never re-scanned for placeholders.
 * A placeholder with no matching param is left in place rather than throwing: applyStrings
 * runs on the startup path, where an exception would blank the whole page, and a visible
 * {name} is easy to spot in QA.
 */
export function interpolate(template: string, params: Params): string {
  return template.replace(PLACEHOLDER_PATTERN, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match);
}

type TranslateArgs<K extends StringKey> =
  K extends PluralKey ? [params: Params & { readonly count: number }] : [params?: Params];

export interface I18n {
  readonly locale: LocaleTag;
  /** The tag handed to Intl. Carries the reader's region, so it can be narrower than `locale`. */
  readonly formatLocale: string;
  readonly strings: Strings;
  /** `count` is required by the type system for plural keys and optional for text keys. */
  t<K extends StringKey>(key: K, ...args: TranslateArgs<K>): string;
  formatNumber(value: number, options?: Intl.NumberFormatOptions): string;
  formatDistance(kilometers: number, unit: DistanceUnit): string;
  formatPercent(fraction: number): string;
  formatMonth(date: Date): string;
  formatMediumDate(date: Date): string;
  /** Joins clauses with the locale's list separator, skipping empty ones. */
  join(...parts: readonly string[]): string;
}

function isPluralEntry(value: string | PluralEntry): value is PluralEntry {
  return typeof value !== 'string';
}

/**
 * Picks one form of a plural entry. A category the catalog does not declare falls back to
 * `other`, which every CLDR locale defines, so a form can never render as undefined even if a
 * translation drops one. Shared with `t` so the test exercises the real path.
 */
export function pluralForm(entry: PluralEntry, category: PluralCategory): string {
  return entry[category] ?? entry.other;
}

/**
 * Every Intl instance is built once per locale. formatMediumDate reaches the preview loop
 * through the period label, which runs twice per animation frame, so constructing a formatter
 * per call was building 120 of them a second at 60fps.
 */
export function createI18n(locale: LocaleTag, formatLocale: string = locale): I18n {
  const strings = CATALOGS[locale];
  // Plural categories belong to the catalog language, never to the region: the forms a
  // translator wrote for `locale` are the only ones the entries declare.
  const plurals = new Intl.PluralRules(locale);
  const numberFormats = new Map<string, Intl.NumberFormat>();
  const numberFormat = (options?: Intl.NumberFormatOptions): Intl.NumberFormat => {
    const key = options === undefined ? '' : JSON.stringify(options);
    let format = numberFormats.get(key);
    if (!format) {
      format = new Intl.NumberFormat(formatLocale, options);
      numberFormats.set(key, format);
    }
    return format;
  };
  const distanceFormats: Readonly<Record<DistanceUnit, Intl.NumberFormat>> = {
    kilometers: new Intl.NumberFormat(formatLocale, {
      style: 'unit',
      unit: 'kilometer',
      maximumFractionDigits: 0,
    }),
    miles: new Intl.NumberFormat(formatLocale, {
      style: 'unit',
      unit: 'mile',
      maximumFractionDigits: 0,
    }),
  };
  const percentFormat = new Intl.NumberFormat(formatLocale, {
    style: 'percent',
    maximumFractionDigits: 0,
  });
  const monthFormat = new Intl.DateTimeFormat(formatLocale, { month: 'long', year: 'numeric' });
  const mediumDateFormat = new Intl.DateTimeFormat(formatLocale, { dateStyle: 'medium' });

  const formatNumber = (value: number, options?: Intl.NumberFormatOptions): string =>
    numberFormat(options).format(value);

  const translate = (key: StringKey, params: Params = {}): string => {
    const value = strings[key];
    if (!isPluralEntry(value)) return interpolate(value, params);
    const count = Number(params.count ?? 0);
    const template = pluralForm(value, plurals.select(count));
    // Only `count` is auto-formatted: it is always a quantity. Any other numeric param would
    // pick up group separators it must not have, such as a year rendering as '2,026'.
    return interpolate(template, { ...params, count: formatNumber(count) });
  };

  return {
    locale,
    formatLocale,
    strings,
    t: translate as I18n['t'],
    formatNumber,
    // Rounded before formatting so the digits retain the previous Math.round behavior.
    formatDistance: (kilometers, unit) => distanceFormats[unit].format(
      Math.round(convertDistanceFromKilometers(kilometers, unit)),
    ),
    // Rounded to whole percent first: Intl rounds the shortest decimal of the double while
    // Math.round rounds the double itself, and the two disagree on values such as 0.575.
    formatPercent: (fraction) => percentFormat.format(Math.round(fraction * 100) / 100),
    formatMonth: (date) => monthFormat.format(date),
    formatMediumDate: (date) => mediumDateFormat.format(date),
    join: (...parts) => parts.filter((part) => part !== '').join(strings.listSeparator),
  };
}
