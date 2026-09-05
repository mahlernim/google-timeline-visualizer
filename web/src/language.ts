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
