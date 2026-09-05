import { describe, expect, it } from 'vitest';
import { preferAndroid, mayAutoplay } from './device';
import { FLOW_STRINGS } from './flow-i18n';
import { LOCALES } from './language';

describe('landing priorities', () => {
  it.each([
    ['Mozilla/5.0 (Linux; Android 13; Pixel 6)', 5, true],
    ['Mozilla/5.0 (iPhone; CPU iPhone OS 18_0)', 5, false],
    ['Mozilla/5.0 (iPad; CPU OS 18_0)', 5, false],
    ['Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15)', 5, false],
    ['Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 0, false],
    ['', 0, false],
  ])('orders options for %s', (agent, touch, android) => expect(preferAndroid(agent, touch)).toBe(android));
  it('requires manual playback for data saving or reduced motion', () => {
    expect(mayAutoplay(false, false)).toBe(true);
    expect(mayAutoplay(true, false)).toBe(false);
    expect(mayAutoplay(false, true)).toBe(false);
  });
  it('provides every new label in all nine languages', () => {
    const keys = Object.keys(FLOW_STRINGS.en).sort();
    for (const locale of LOCALES) {
      expect(Object.keys(FLOW_STRINGS[locale]).sort()).toEqual(keys);
      for (const text of Object.values(FLOW_STRINGS[locale])) expect(text.length).toBeGreaterThan(0);
    }
  });
});
