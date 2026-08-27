import { de } from './locales/de';
import { en } from './locales/en';
import { es } from './locales/es';
import { fr } from './locales/fr';
import { ja } from './locales/ja';
import { ko } from './locales/ko';
import { ptBR } from './locales/pt-BR';
import { zhCN } from './locales/zh-CN';
import { zhTW } from './locales/zh-TW';
import { convertDistanceFromKilometers } from './distance-unit';
import type { DistanceUnit } from './distance-unit';

export type LocaleTag =
  | 'en'
  | 'ko'
  | 'ja'
  | 'zh-CN'
  | 'zh-TW'
  | 'es'
  | 'fr'
  | 'de'
  | 'pt-BR';

/**
 * The order of AppLanguage.supportedTags on Android, which is the order of
 * app/src/main/res/xml/locales_config.xml. The language menu follows it too, so the two
 * platforms offer the same list in the same order.
 */
export const LOCALES: readonly LocaleTag[] = [
  'en',
  'ko',
  'ja',
  'zh-CN',
  'zh-TW',
  'es',
  'fr',
  'de',
  'pt-BR',
];

/**
 * Every language is labelled in its own language, verbatim from the language_name_* resources
 * in app/src/main/res/values/strings.xml. Those are translatable="false" on Android and are
 * deliberately kept out of the catalogs here for the same reason: a ko catalog would translate
 * 'Espanol' into Korean, and nine catalogs would each repeat the same nine values.
 */
export const LANGUAGE_NAMES: Readonly<Record<LocaleTag, string>> = {
  en: 'English',
  ko: '한국어',
  ja: '日本語',
  'zh-CN': '简体中文',
  'zh-TW': '繁體中文',
  es: 'Español',
  fr: 'Français',
  de: 'Deutsch',
  'pt-BR': 'Português (Brasil)',
};

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
  cameraMovementLabel: string;
  cameraFixed: string;
  cameraSteady: string;
  cameraDynamic: string;
  cameraCloseUp: string;
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
 * All nine catalogs are statically imported so they land in the entry chunk.
 *
 * They must never become dynamic `import()`. web/public/service-worker.js precaches only './'
 * and its fetch handler falls back to the cache for same-origin GETs, so no hashed chunk is
 * ever stored: a lazily loaded catalog would be a second network request issued after the
 * first paint, and offline it would leave a rendered page with no strings in it. Static
 * imports also keep applyStrings synchronous, so a non-English user never sees a frame of
 * English, and a language switch can never fail with an unrecoverable chunk load error.
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
};

interface ParsedTag {
  readonly language: string;
  readonly script: string | null;
  readonly region: string | null;
}

/**
 * Intl.Locale throws on a malformed tag and carries far more than the three subtags that
 * matter here, so the tags are parsed by hand: the result is fully determined and testable
 * under the node test environment.
 */
function parseTag(tag: string): ParsedTag | null {
  const parts = tag.trim().replace(/_/g, '-').split('-').filter((part) => part.length > 0);
  const language = parts[0];
  if (language === undefined || !/^[a-z]{2,3}$/i.test(language)) return null;
  let index = 1;
  let script: string | null = null;
  const scriptPart = parts[index];
  if (scriptPart !== undefined && /^[a-z]{4}$/i.test(scriptPart)) {
    script = scriptPart[0].toUpperCase() + scriptPart.slice(1).toLowerCase();
    index += 1;
  }
  let region: string | null = null;
  const regionPart = parts[index];
  if (regionPart !== undefined && /^([a-z]{2}|\d{3})$/i.test(regionPart)) {
    region = regionPart.toUpperCase();
  }
  return { language: language.toLowerCase(), script, region };
}

const SCRIPT_LOCALES: Readonly<Record<string, LocaleTag | undefined>> = {
  'zh-Hant': 'zh-TW',
  'zh-Hans': 'zh-CN',
};

const REGION_LOCALES: Readonly<Record<string, LocaleTag | undefined>> = {
  'zh-HK': 'zh-TW',
  'zh-MO': 'zh-TW',
  'zh-SG': 'zh-CN',
  'pt-PT': 'pt-BR',
};

/** CLDR likely subtags resolve a bare 'zh' to zh-Hans-CN, and a bare 'pt' to pt-BR. */
const LANGUAGE_LOCALES: Readonly<Record<string, LocaleTag | undefined>> = {
  en: 'en',
  ko: 'ko',
  ja: 'ja',
  es: 'es',
  fr: 'fr',
  de: 'de',
  pt: 'pt-BR',
  zh: 'zh-CN',
};

function isLocaleTag(value: string): value is LocaleTag {
  return (LOCALES as readonly string[]).includes(value);
}

function matchTag(parsed: ParsedTag): LocaleTag | null {
  // a. exact: the normalized language-region, or the bare language, is one of the nine.
  const exact = parsed.region === null ? parsed.language : `${parsed.language}-${parsed.region}`;
  if (isLocaleTag(exact)) return exact;
  // b. language plus script, which outranks the region.
  if (parsed.script !== null) {
    const byScript = SCRIPT_LOCALES[`${parsed.language}-${parsed.script}`];
    if (byScript) return byScript;
  }
  // c. language plus a region that maps onto one of the nine.
  if (parsed.region !== null) {
    const byRegion = REGION_LOCALES[`${parsed.language}-${parsed.region}`];
    if (byRegion) return byRegion;
  }
  // d. the bare language.
  return LANGUAGE_LOCALES[parsed.language] ?? null;
}

/**
 * Walks the preferred tags in order and applies rules a to d to each one before moving on, so
 * ['zh-HK', 'en'] resolves to zh-TW rather than en. Falls back to 'en' when nothing matches.
 */
export function resolveLocale(preferred: readonly string[]): LocaleTag {
  for (const tag of preferred) {
    const parsed = parseTag(tag);
    if (parsed === null) continue;
    const matched = matchTag(parsed);
    if (matched !== null) return matched;
  }
  return 'en';
}

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

export type LanguagePreference = 'system' | LocaleTag;

export const LANGUAGE_STORAGE_KEY = 'timeline-visualizer.language';

/**
 * iOS Safari throws SecurityError when cookies are blocked, on the property access itself and
 * not only on getItem, so the try must wrap `window.localStorage` rather than the call.
 */
function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function readLanguagePreference(): LanguagePreference {
  try {
    const stored = storage()?.getItem(LANGUAGE_STORAGE_KEY);
    if (stored === 'system') return 'system';
    if (stored !== null && stored !== undefined && isLocaleTag(stored)) return stored;
  } catch {
    // A storage that exists but refuses to be read is the same as no storage at all.
  }
  return 'system';
}

/** A write that fails never blocks the switch: the session still changes language. */
export function writeLanguagePreference(preference: LanguagePreference): void {
  try {
    storage()?.setItem(LANGUAGE_STORAGE_KEY, preference);
  } catch {
    // Private windows raise QuotaExceededError; the preference is simply not remembered.
  }
}

export function isLanguagePreference(value: string): value is LanguagePreference {
  return value === 'system' || isLocaleTag(value);
}

export function activeLocale(
  preference: LanguagePreference,
  preferred: readonly string[],
): LocaleTag {
  return preference === 'system' ? resolveLocale(preferred) : preference;
}

function isUsableByIntl(tag: string): boolean {
  try {
    new Intl.DateTimeFormat(tag);
    new Intl.NumberFormat(tag);
    return true;
  } catch {
    return false;
  }
}

/**
 * The catalog follows the language, but number and date formatting follows the region. An
 * en-GB reader wants the English catalog and `1 Jan 2026`, not `Jan 1, 2026`, so the first
 * preferred tag that resolves to the active catalog is kept whole and handed to Intl.
 * An explicit language choice formats in that language alone: someone who picks Japanese
 * while browsing with en-GB is asking for Japanese, not for Japanese text on British dates.
 */
export function formattingLocale(
  preference: LanguagePreference,
  preferred: readonly string[],
  resolved: LocaleTag,
): string {
  if (preference !== 'system') return resolved;
  for (const tag of preferred) {
    const parsed = parseTag(tag);
    if (parsed === null || matchTag(parsed) !== resolved) continue;
    const canonical = [parsed.language, parsed.script, parsed.region]
      .filter((part): part is string => part !== null)
      .join('-');
    return isUsableByIntl(canonical) ? canonical : resolved;
  }
  return resolved;
}
