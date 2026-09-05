import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  CATALOGS,
  createI18n,
  interpolate,
  LANGUAGE_NAMES,
  LOCALES,
  pluralForm,
  formattingLocale,
  resolveLocale,
} from './i18n';
import type { LocaleTag, Params, PluralEntry, StringKey, Strings } from './i18n';

const PLACEHOLDER = /\{([a-zA-Z][a-zA-Z0-9_]*)\}/g;

function names(value: string): string[] {
  return [...new Set([...value.matchAll(PLACEHOLDER)].map((match) => match[1]))].sort();
}

function isPlural(value: Strings[StringKey]): value is PluralEntry {
  return typeof value !== 'string';
}

function shape(catalog: Strings): Record<string, 'text' | 'plural'> {
  return Object.fromEntries(
    Object.entries(catalog).map(([key, value]) => [key, isPlural(value) ? 'plural' : 'text']),
  );
}

function forms(value: Strings[StringKey]): string[] {
  return isPlural(value) ? Object.values(value) : [value];
}

function categoriesOf(locale: LocaleTag): string[] {
  return [...new Intl.PluralRules(locale).resolvedOptions().pluralCategories].sort();
}

const keys = Object.keys(CATALOGS.en) as StringKey[];

describe('resolveLocale', () => {
  // Every row of the lookup table in the architecture spec, in the same order.
  const CASES: readonly (readonly [readonly string[], LocaleTag])[] = [
    [['en'], 'en'],
    [['en-US'], 'en'],
    [['en-GB'], 'en'],
    [['ko-KR'], 'ko'],
    [['ja'], 'ja'],
    [['zh-CN'], 'zh-CN'],
    [['zh-TW'], 'zh-TW'],
    [['zh-Hant'], 'zh-TW'],
    [['zh-Hans'], 'zh-CN'],
    [['zh-Hant-HK'], 'zh-TW'],
    [['zh-Hans-SG'], 'zh-CN'],
    [['zh-HK'], 'zh-TW'],
    [['zh-MO'], 'zh-TW'],
    [['zh-SG'], 'zh-CN'],
    [['zh'], 'zh-CN'],
    [['zh-XX'], 'zh-CN'],
    [['zh-Latn'], 'zh-CN'],
    [['ZH-hant-hk'], 'zh-TW'],
    [['pt'], 'pt-BR'],
    [['pt-PT'], 'pt-BR'],
    [['pt-BR'], 'pt-BR'],
    [['pt-br'], 'pt-BR'],
    [['pt_BR'], 'pt-BR'],
    [['es-419'], 'es'],
    [['es-MX'], 'es'],
    [['fr-CA'], 'fr'],
    [['de-AT'], 'de'],
    [['de-CH'], 'de'],
    [['en-US-u-ca-gregory'], 'en'],
    [['sv-SE'], 'en'],
    [['yue-Hant-HK'], 'en'],
    [['nb'], 'en'],
    [[], 'en'],
    [[''], 'en'],
    [['x-private', '123', '-'], 'en'],
    [['sv', 'fr-CA'], 'fr'],
    [['zh-HK', 'en'], 'zh-TW'],
    [['', 'ko'], 'ko'],
  ];

  it('resolves every tag in the lookup table', () => {
    for (const [input, expected] of CASES) {
      expect(resolveLocale(input), JSON.stringify(input)).toBe(expected);
    }
  });

  it('resolves every supported tag to itself', () => {
    for (const locale of LOCALES) {
      expect(resolveLocale([locale]), locale).toBe(locale);
    }
  });

  it('keeps looking after a malformed tag instead of giving up on the list', () => {
    expect(resolveLocale(['', '123', 'x-private', 'de-CH'])).toBe('de');
  });
});

describe('translate', () => {
  const en = createI18n('en');

  it('returns a key with no placeholders unchanged', () => {
    expect(en.t('progressReady')).toBe('Ready');
    expect(en.t('previewButton')).toBe('Preview');
  });

  it('substitutes every placeholder', () => {
    expect(en.t('periodRange', { start: 'March 2026', end: 'May 2026' }))
      .toBe('March 2026 – May 2026');
    expect(en.t('fileStatusReading', { name: 'Timeline.json' }))
      .toBe('Reading Timeline.json…');
  });

  it('substitutes a placeholder that appears more than once', () => {
    expect(interpolate('{a} and {a} and {b}', { a: 'x', b: 'y' })).toBe('x and x and y');
  });

  it('leaves a placeholder in place when the param is missing, and does not throw', () => {
    expect(() => en.t('periodRange', { start: 'March 2026' })).not.toThrow();
    expect(en.t('periodRange', { start: 'March 2026' })).toBe('March 2026 – {end}');
  });

  it('ignores a param that no placeholder names', () => {
    expect(en.t('progressReady', { unused: 'x' })).toBe('Ready');
  });

  it('never re-scans a substituted value', () => {
    // A single pass is the guarantee that a user filename which happens to contain a brace
    // cannot be interpolated a second time.
    expect(en.t('fileStatusReading', { name: '{name}' })).toBe('Reading {name}…');
  });

  it('renders a numeric param that is not count without group separators', () => {
    expect(en.t('errorFormatUnsupported', { width: 1080, height: 1920, fps: 60 }))
      .toBe('This browser cannot create 1080×1920 video at 60 fps. Choose another format or frame rate.');
    expect(en.t('hintFormatUnsupported', { width: 480, height: 480 }))
      .toBe('This browser cannot create 480×480 videos. Choose another format.');
  });
});

describe('plural selection', () => {
  // If this fails, node is a small-icu build and every locale-dependent failure below is an
  // environment problem rather than a catalog problem.
  it('runs on a full-icu node build', () => {
    expect(new Intl.NumberFormat('de').format(1000)).toBe('1.000');
  });

  function category(locale: LocaleTag, count: number): string {
    return new Intl.PluralRules(locale).select(count);
  }

  it('selects the English categories', () => {
    expect(category('en', 0)).toBe('other');
    expect(category('en', 1)).toBe('one');
    expect(category('en', 2)).toBe('other');
  });

  it('gives ko, ja and both Chinese locales a single form', () => {
    for (const locale of ['ko', 'ja', 'zh-CN', 'zh-TW'] as const) {
      for (const count of [0, 1, 2, 11]) {
        expect(category(locale, count), `${locale}/${count}`).toBe('other');
      }
    }
  });

  it('selects the German categories', () => {
    expect(category('de', 1)).toBe('one');
    expect(category('de', 0)).toBe('other');
  });

  it('selects the Spanish categories, including many at a million', () => {
    expect(category('es', 1)).toBe('one');
    expect(category('es', 2)).toBe('other');
    expect(category('es', 1_000_000)).toBe('many');
  });

  it('treats zero as singular in French', () => {
    expect(category('fr', 0)).toBe('one');
    expect(category('fr', 1)).toBe('one');
    expect(category('fr', 2)).toBe('other');
    expect(category('fr', 1_000_000)).toBe('many');
  });

  it('treats zero as singular in Brazilian Portuguese', () => {
    expect(category('pt-BR', 0)).toBe('one');
    expect(category('pt-BR', 2)).toBe('other');
    expect(category('pt-BR', 1_000_000)).toBe('many');
  });

  it('renders the English catalog forms the CLDR categories choose', () => {
    const en = createI18n('en');
    expect(en.t('summaryOutliersIgnored', { count: 1 })).toBe('1 suspicious location ignored');
    expect(en.t('summaryOutliersIgnored', { count: 4 })).toBe('4 suspicious locations ignored');
    expect(en.t('durationSeconds', { count: 10 })).toBe('10 seconds');
  });

  it('falls back to other for a category the entry does not declare', () => {
    expect(pluralForm({ other: 'only form' }, 'many')).toBe('only form');
    expect(pluralForm({ one: 'a', other: 'b' }, 'one')).toBe('a');
  });

  it('formats count for the locale it renders in', () => {
    expect(createI18n('de').t('summaryNoMovement', { count: 1_000_000 })).toContain('1.000.000');
    expect(createI18n('en').t('summaryNoMovement', { count: 1_000_000 })).toContain('1,000,000');
  });
});

describe('formattingLocale', () => {
  it('keeps the reader region when the browser language matches the catalog', () => {
    expect(formattingLocale('system', ['en-GB'], 'en')).toBe('en-GB');
    expect(formattingLocale('system', ['de-AT'], 'de')).toBe('de-AT');
    expect(formattingLocale('system', ['fr-CA'], 'fr')).toBe('fr-CA');
    expect(formattingLocale('system', ['es-MX'], 'es')).toBe('es-MX');
    expect(formattingLocale('system', ['pt-PT'], 'pt-BR')).toBe('pt-PT');
    expect(formattingLocale('system', ['zh-Hant-HK'], 'zh-TW')).toBe('zh-Hant-HK');
  });

  it('falls back to the catalog tag when no preferred tag resolves to it', () => {
    expect(formattingLocale('system', ['sv-SE'], 'en')).toBe('en');
    expect(formattingLocale('system', [], 'en')).toBe('en');
    expect(formattingLocale('system', ['not a tag'], 'en')).toBe('en');
  });

  it('skips preferred tags that resolve to a different catalog', () => {
    expect(formattingLocale('system', ['sv-SE', 'en-AU'], 'en')).toBe('en-AU');
    expect(formattingLocale('system', ['ko-KR', 'en-GB'], 'en')).toBe('en-GB');
  });

  it('ignores the browser region once a language is chosen explicitly', () => {
    expect(formattingLocale('ja', ['en-GB'], 'ja')).toBe('ja');
    expect(formattingLocale('en', ['en-GB'], 'en')).toBe('en');
  });

  it('formats British dates for a British reader of the English catalog', () => {
    const british = createI18n('en', formattingLocale('system', ['en-GB'], 'en'));
    const american = createI18n('en', formattingLocale('system', ['en-US'], 'en'));
    const date = new Date(Date.UTC(2026, 0, 5));

    expect(british.locale).toBe('en');
    expect(british.strings).toBe(american.strings);
    expect(british.formatMediumDate(date))
      .toBe(new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium' }).format(date));
    expect(british.formatMediumDate(date)).not.toBe(american.formatMediumDate(date));
  });

  it('selects plural forms from the catalog language, not the reader region', () => {
    const british = createI18n('en', 'en-GB');

    expect(british.t('summaryOutliersIgnored', { count: 1 }))
      .toBe(createI18n('en').t('summaryOutliersIgnored', { count: 1 }));
    expect(british.t('summaryOutliersIgnored', { count: 2 }))
      .toBe(createI18n('en').t('summaryOutliersIgnored', { count: 2 }));
  });
});

describe('formatting', () => {
  it('passes the locale to the number formatter', () => {
    expect(createI18n('de').formatNumber(1234567))
      .toBe(new Intl.NumberFormat('de').format(1234567));
    expect(createI18n('de').formatNumber(1e6)).not.toBe(createI18n('en').formatNumber(1e6));
  });

  it('passes the options through to the number formatter', () => {
    const options = { minimumFractionDigits: 1, maximumFractionDigits: 1 };
    expect(createI18n('fr').formatNumber(4.25, options))
      .toBe(new Intl.NumberFormat('fr', options).format(4.25));
  });

  it('formats a distance with the locale unit pattern', () => {
    const kilometers = { style: 'unit', unit: 'kilometer', maximumFractionDigits: 0 } as const;
    expect(createI18n('fr').formatDistance(12345, 'kilometers'))
      .toBe(new Intl.NumberFormat('fr', kilometers).format(12345));
    expect(createI18n('en').formatDistance(12345.4, 'kilometers')).toBe('12,345 km');
    expect(createI18n('en').formatDistance(12345.4, 'miles')).toBe('7,671 mi');
  });

  it('formats a percent with the locale spacing rule', () => {
    expect(createI18n('en').formatPercent(0.42)).toBe('42%');
    expect(createI18n('fr').formatPercent(0.42))
      .toBe(new Intl.NumberFormat('fr', { style: 'percent', maximumFractionDigits: 0 }).format(0.42));
  });

  it('rounds a percent the way the old Math.round did', () => {
    // 0.575 is below 57.5 as a double, so Math.round produced 57 while formatting the shortest
    // decimal would produce 58. The English text has to stay exactly what it was.
    expect(createI18n('en').formatPercent(0.575)).toBe('57%');
  });

  it('passes the locale to the date formatters', () => {
    const march = new Date(2026, 2, 14);
    expect(createI18n('en').formatMonth(march))
      .toBe(new Intl.DateTimeFormat('en', { month: 'long', year: 'numeric' }).format(march));
    expect(createI18n('ja').formatMediumDate(march))
      .toBe(new Intl.DateTimeFormat('ja', { dateStyle: 'medium' }).format(march));
    expect(createI18n('ja').formatMonth(march)).not.toBe(createI18n('en').formatMonth(march));
  });

  it('joins clauses with the catalog separator and drops the empty ones', () => {
    const en = createI18n('en');
    expect(en.join('a', '', 'b')).toBe('a · b');
    expect(en.join('a')).toBe('a');
    expect(en.join('a', '')).toBe('a');
  });
});

describe('catalog completeness', () => {
  it('holds exactly the supported locales', () => {
    expect(Object.keys(CATALOGS)).toEqual([...LOCALES]);
  });

  it('does not drift from the Android locale list', () => {
    // app/src/main/res/xml/locales_config.xml, in its own order.
    expect([...LOCALES]).toEqual(['en', 'ko', 'ja', 'zh-CN', 'zh-TW', 'es', 'fr', 'de', 'pt-BR']);
  });

  it('names every language in its own language', () => {
    expect(Object.keys(LANGUAGE_NAMES)).toEqual([...LOCALES]);
    for (const locale of LOCALES) {
      expect(LANGUAGE_NAMES[locale].trim(), locale).not.toBe('');
    }
  });

  it('gives every locale the same key set as English', () => {
    for (const locale of LOCALES) {
      expect(Object.keys(CATALOGS[locale]).sort(), locale).toEqual(keys.slice().sort());
    }
  });

  it('gives every locale the same text or plural shape as English', () => {
    const reference = shape(CATALOGS.en);
    for (const locale of LOCALES) {
      expect(shape(CATALOGS[locale]), locale).toEqual(reference);
    }
  });

  it('has no empty form anywhere', () => {
    const offenders: string[] = [];
    for (const locale of LOCALES) {
      for (const key of keys) {
        const value = CATALOGS[locale][key];
        if (isPlural(value)) {
          for (const [category, form] of Object.entries(value)) {
            if (form.trim() === '') offenders.push(`${locale}.${key}.${category}`);
          }
        } else if (value.trim() === '') {
          offenders.push(`${locale}.${key}`);
        }
      }
    }
    // listSeparator is whitespace around a middle dot, so trim() alone would flag it.
    expect(offenders).toEqual([]);
  });

  it('declares exactly the plural categories ICU defines for each locale', () => {
    const offenders: string[] = [];
    for (const locale of LOCALES) {
      const expected = categoriesOf(locale);
      for (const key of keys) {
        const value = CATALOGS[locale][key];
        if (!isPlural(value)) continue;
        const declared = Object.keys(value).sort();
        if (declared.join(',') !== expected.join(',')) {
          offenders.push(`${locale}.${key}: ${declared.join('/')} != ${expected.join('/')}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('keeps the English placeholder set in every locale and every plural form', () => {
    const offenders: string[] = [];
    for (const key of keys) {
      const source = CATALOGS.en[key];
      const reference = names(isPlural(source) ? source.other : source);
      for (const locale of LOCALES) {
        for (const form of forms(CATALOGS[locale][key])) {
          if (names(form).join(',') !== reference.join(',')) {
            offenders.push(`${locale}.${key}: ${names(form).join('/')} != ${reference.join('/')}`);
          }
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('uses key names that data-i18n-attr can always split', () => {
    for (const key of keys) {
      expect(key, key).toMatch(/^[a-z][A-Za-z0-9]*$/);
    }
  });

  it('covers the whole app', () => {
    expect(keys.length).toBeGreaterThan(100);
  });

  it('keeps the typography that a translation tool would flatten', () => {
    const en = CATALOGS.en;
    expect(en.addToHomeScreenHint).toContain('Safari’s');
    expect(en.listSeparator).toBe(' · ');
    expect(en.periodRange).toBe('{start} – {end}');
    expect(en.formatPortrait).toBe('Portrait · ' + '1080×1920');
    expect(en.errorFormatUnsupported).toContain('{width}×{height}');
    expect(en.compatibilityChecking.endsWith('…')).toBe(true);
    expect(en.progressCancelling.endsWith('…')).toBe(true);
    expect(en.fileStatusLoadingSample.endsWith('…')).toBe(true);
    expect(en.fileStatusReading.endsWith('…')).toBe(true);
    expect(en.footerMapAttribution).toBe('Map data © OpenStreetMap contributors and © CARTO.');
  });
});

describe('index.html i18n keys', () => {
  const html = readFileSync(new URL('../app/index.html', import.meta.url), 'utf8');
  const catalog: Record<string, string | PluralEntry> = { ...CATALOGS.en };

  interface Annotation {
    readonly key: string;
    readonly count: string | undefined;
  }

  function annotations(): Annotation[] {
    const found: Annotation[] = [];
    for (const tag of html.match(/<[a-zA-Z][^>]*>/g) ?? []) {
      const key = /\sdata-i18n="([^"]*)"/.exec(tag);
      if (!key) continue;
      const count = /\sdata-i18n-count="([^"]*)"/.exec(tag);
      found.push({ key: key[1], count: count?.[1] });
    }
    return found;
  }

  it('annotates the document with keys that exist', () => {
    const used = annotations();
    expect(used.length).toBeGreaterThan(50);
    for (const { key } of used) {
      expect(Object.prototype.hasOwnProperty.call(catalog, key), key).toBe(true);
    }
  });

  it('uses data-i18n-count on plural keys and only on plural keys', () => {
    for (const { key, count } of annotations()) {
      const value = catalog[key];
      expect(typeof value !== 'string', key).toBe(count !== undefined);
      if (count !== undefined) expect(Number.isInteger(Number(count)), key).toBe(true);
    }
  });

  it('references only keys that exist from data-i18n-attr', () => {
    for (const match of html.matchAll(/data-i18n-attr="([^"]*)"/g)) {
      for (const pair of match[1].split(';')) {
        const [attribute, key] = pair.split(':');
        expect(attribute, match[1]).toBeTruthy();
        expect(Object.prototype.hasOwnProperty.call(catalog, key), key).toBe(true);
        expect(typeof catalog[key], key).toBe('string');
      }
    }
  });

  it('renders exactly the attribute text the markup already carries', () => {
    // Same contract as the textContent pass below: the literal attribute is the English source
    // and the catalog is what replaces it, so the two drifting apart would change what an
    // English user sees between first paint and applyStrings.
    const translate = createI18n('en').t as (key: string, params?: Params) => string;
    let checked = 0;
    for (const tag of html.match(/<[a-zA-Z][^>]*>/g) ?? []) {
      const annotation = /\sdata-i18n-attr="([^"]*)"/.exec(tag);
      if (!annotation) continue;
      for (const pair of annotation[1].split(';')) {
        const [attribute, key] = pair.split(':');
        const literal = new RegExp(`\\s${attribute}="([^"]*)"`).exec(tag);
        expect(literal, pair).not.toBeNull();
        expect(literal?.[1], pair).toBe(translate(key));
        checked += 1;
      }
    }
    expect(checked).toBeGreaterThan(1);
  });

  it('fills the video title field from the key the empty-field fallback uses', () => {
    // main.ts renders `titleInput.value.trim() || t('defaultVideoTitle')` into the MP4, so the
    // prefilled value has to come from the same key. A hardcoded attribute here would prefill
    // English for every translated catalog, and clearing the field would then change the
    // overlay text rather than restore what the field had shown.
    const tag = /<input id="video-title"[^>]*>/.exec(html)?.[0];
    expect(tag).toBeDefined();
    expect(tag).toContain('data-i18n-attr="value:defaultVideoTitle"');
  });

  it('offers the supported locales in the language select, labelled in their own language', () => {
    const block = /<select id="app-language">([\s\S]*?)<\/select>/.exec(html);
    expect(block).not.toBeNull();
    const options = [...(block?.[1] ?? '').matchAll(/<option value="([^"]*)"[^>]*>([^<]*)<\/option>/g)];
    expect(options.map((option) => option[1])).toEqual(['system', ...LOCALES]);
    for (const option of options.slice(1)) {
      expect(option[2], option[1]).toBe(LANGUAGE_NAMES[option[1] as LocaleTag]);
    }
  });

  it('offers the three Android distance-unit preferences with Automatic selected', () => {
    const block = /<select id="distance-unit">([\s\S]*?)<\/select>/.exec(html);
    expect(block).not.toBeNull();
    const options = [...(block?.[1] ?? '').matchAll(/<option value="([^"]*)"([^>]*)>/g)];
    expect(options.map((option) => option[1])).toEqual(['automatic', 'kilometers', 'miles']);
    expect(options[0][2]).toContain('selected');
  });

  it('uses Android export bounds for duration, resolution, and frame rate', () => {
    expect(html).toContain('id="duration" type="number" min="10" max="300"');
    expect(html).toContain('id="resolution" type="number" min="480" max="2160"');
    expect(html).toContain('id="frame-rate" type="number" min="15" max="120"');
  });

  it('renders exactly the text the markup already carries', () => {
    // The HTML text is the English source and the catalog is what replaces it, so the two
    // drifting apart would change what an English user sees between first paint and
    // applyStrings, and would silently break the no-script fallback.
    // applyStrings resolves the key the same way, without the type parameter it cannot know
    // at runtime, so the loose signature here mirrors what the DOM pass actually does.
    const translate = createI18n('en').t as (key: string, params?: Params) => string;
    let checked = 0;
    for (const match of html.matchAll(/<([a-zA-Z]+)([^>]*\sdata-i18n="([^"]+)"[^>]*)>([^<]*)</g)) {
      const [, , attrs, key, text] = match;
      const count = /\sdata-i18n-count="([^"]*)"/.exec(attrs);
      const rendered = count === null ? translate(key) : translate(key, { count: Number(count[1]) });
      expect(rendered, key).toBe(text);
      checked += 1;
    }
    expect(checked).toBeGreaterThan(50);
  });

  it('renders exactly the meta description the markup already carries', () => {
    expect(/<meta name="description"[^>]*content="([^"]*)"/.exec(html)?.[1])
      .toBe(createI18n('en').t('appDescription'));
  });

  it('keeps the English source text in the markup as a no-script fallback', () => {
    // A failed module script must still render a usable page, so the HTML carries the English
    // text rather than empty nodes that applyStrings would fill in.
    expect(html).toContain('>Create video<');
    expect(html).toContain('>Timeline file<');
    expect(html).toContain('>No Timeline loaded<');
  });
});

/**
 * The English text the app composes at runtime, pinned against the literals the code used
 * before the catalog existed. An English speaker has to see no difference at all.
 */
describe('English rendering', () => {
  const en = createI18n('en');

  it('composes the selection summary', () => {
    expect(en.join(en.t('summaryNoLocations'), '')).toBe('No locations in this period');
    expect(en.join(en.t('summaryOneLocation'), en.t('summaryOutliersIgnored', { count: 1 })))
      .toBe('1 location point · Choose a wider period · 1 suspicious location ignored');
    expect(en.join(en.t('summaryNoMovement', { count: 1234 }), ''))
      .toBe('1,234 location points · No movement');
    expect(en.join(
      en.t('summaryDistanceAbout', { count: 1234, distance: en.formatDistance(12345.4, 'kilometers') }),
      '',
      en.t('summaryOutliersIgnored', { count: 2 }),
    )).toBe('1,234 location points · About 12,345 km · 2 suspicious locations ignored');
    expect(en.join(
      en.t('summaryDistanceEstimated', { count: 40, distance: en.formatDistance(3, 'kilometers') }),
      en.t('summaryRawRejected', { count: 2000 }),
    )).toBe('40 location points · Estimated 3 km · 2,000 noisy or inaccurate points ignored');
  });

  it('composes the file status line', () => {
    expect(en.join(
      en.t('fileStatusLoaded', {
        count: 123456,
        source: 'Timeline.json',
        firstMonth: 'January 2025',
        lastMonth: 'March 2026',
      }),
      en.t('fileStatusRawFallback'),
      en.t('fileStatusTimezoneMissing'),
    )).toBe('Timeline.json · 123,456 valid points from January 2025 to March 2026 · Raw location fallback · Timezone missing, preserving exported route order');
  });

  it('composes the progress labels', () => {
    expect(en.t('progressPreparingMapCount', { completed: 7, total: 12 })).toBe('Preparing map 7/12');
    expect(en.t('progressCreatingPercent', { percent: en.formatPercent(0.42) })).toBe('Creating MP4 42%');
    expect(en.t('progressVideoReady', {
      size: en.formatNumber(4_250_000 / 1_000_000, {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1,
      }),
    })).toBe('Video ready · 4.3 MB');
  });

  it('composes the period label', () => {
    expect(en.t('periodRange', { start: 'March 2026', end: 'May 2026' }))
      .toBe('March 2026 – May 2026');
    expect(en.formatMonth(new Date(2026, 2, 1))).toBe('March 2026');
    expect(en.formatMediumDate(new Date(2026, 2, 14))).toBe('Mar 14, 2026');
    expect(en.t('periodRawLocationData')).toBe('Raw location data');
  });

  it('takes the singular at a count of one, which the old string concatenation could not', () => {
    // The only place the English text deliberately changed: 'Estimated 1 noisy or inaccurate
    // points ignored' and '1 valid points from' were hardcoded plurals with no branch.
    expect(en.t('summaryRawRejected', { count: 1 })).toBe('1 noisy or inaccurate point ignored');
    expect(en.t('fileStatusLoaded', { count: 1, source: 's', firstMonth: 'a', lastMonth: 'b' }))
      .toBe('s · 1 valid point from a to b');
  });
});
