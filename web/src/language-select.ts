import { LANGUAGE_NAMES, LOCALES, resolveLocale } from './language';
import type { LanguagePreference } from './language';

/** The collapsed control names the language while preserving automatic selection. */
export function populateLanguageSelect(
  select: HTMLSelectElement, preference: LanguagePreference, preferred: readonly string[], systemLabel: string,
): void {
  const automatic = document.createElement('optgroup');
  automatic.label = systemLabel;
  automatic.append(new Option(LANGUAGE_NAMES[resolveLocale(preferred)], 'system'));
  select.replaceChildren(automatic, ...LOCALES.map((tag) => new Option(LANGUAGE_NAMES[tag], tag)));
  select.value = preference;
}
