import { LANGUAGE_NAMES, LOCALES, resolveLocale } from './language';
import type { LanguagePreference } from './language';

/** The collapsed control names the language while preserving automatic selection. */
export function populateLanguageSelect(
  select: HTMLSelectElement, preference: LanguagePreference, preferred: readonly string[],
): void {
  select.replaceChildren(...LOCALES.map((tag) => new Option(LANGUAGE_NAMES[tag], tag)));
  select.value = preference === 'system' ? resolveLocale(preferred) : preference;
}
