import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  automaticDistanceUnit,
  convertDistanceFromKilometers,
  isDistanceUnitPreference,
  resolveDistanceUnit,
  readDistanceUnitPreference,
} from './distance-unit';

describe('distance units', () => {
  it.each(['en-US', 'en-GB', 'my-MM', 'en-LR'])('uses miles for %s', (locale) => {
    expect(automaticDistanceUnit([locale])).toBe('miles');
  });

  it.each(['ko-KR', 'ja-JP', 'de-DE', 'fr-CA'])('uses kilometers for %s', (locale) => {
    expect(automaticDistanceUnit([locale])).toBe('kilometers');
  });

  it('maximizes a language-only locale and skips invalid locale tags', () => {
    expect(automaticDistanceUnit(['not_a_locale', 'en'])).toBe('miles');
    expect(automaticDistanceUnit(['not_a_locale'])).toBe('kilometers');
  });

  it('keeps explicit preferences independent of the browser locale', () => {
    expect(resolveDistanceUnit('kilometers', ['en-US'])).toBe('kilometers');
    expect(resolveDistanceUnit('miles', ['ko-KR'])).toBe('miles');
  });

  it('recognizes only supported stored preferences', () => {
    expect(isDistanceUnitPreference('automatic')).toBe(true);
    expect(isDistanceUnitPreference('kilometers')).toBe(true);
    expect(isDistanceUnitPreference('miles')).toBe(true);
    expect(isDistanceUnitPreference('mi')).toBe(false);
  });

  it('converts display values without changing the kilometer source value', () => {
    expect(convertDistanceFromKilometers(10, 'kilometers')).toBe(10);
    expect(convertDistanceFromKilometers(10, 'miles')).toBeCloseTo(6.21371192237334, 12);
  });
});


describe('stored distance preference', () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each([null, 'invalid'])('defaults to kilometers in the US when stored value is %s', (stored) => {
    vi.stubGlobal('window', { localStorage: { getItem: () => stored } });
    expect(resolveDistanceUnit(readDistanceUnitPreference(), ['en-US'])).toBe('kilometers');
  });

  it.each(['automatic', 'kilometers', 'miles'])('preserves saved %s', (stored) => {
    vi.stubGlobal('window', { localStorage: { getItem: () => stored } });
    expect(readDistanceUnitPreference()).toBe(stored);
  });

  it('defaults to kilometers when storage access is blocked', () => {
    vi.stubGlobal('window', { get localStorage() { throw new Error('blocked'); } });
    expect(readDistanceUnitPreference()).toBe('kilometers');
  });
});
