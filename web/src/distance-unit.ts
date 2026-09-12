export type DistanceUnit = 'kilometers' | 'miles';
export type DistanceUnitPreference = 'automatic' | DistanceUnit;

export const DISTANCE_UNIT_STORAGE_KEY = 'timeline-visualizer.distance-unit';

const MILE_REGIONS = new Set(['GB', 'LR', 'MM', 'US']);

export function isDistanceUnitPreference(value: string): value is DistanceUnitPreference {
  return value === 'automatic' || value === 'kilometers' || value === 'miles';
}

function localeRegion(localeTag: string): string | undefined {
  try {
    return new Intl.Locale(localeTag).maximize().region;
  } catch {
    return undefined;
  }
}

export function automaticDistanceUnit(preferredLocales: readonly string[]): DistanceUnit {
  for (const locale of preferredLocales) {
    const region = localeRegion(locale);
    if (region !== undefined) return MILE_REGIONS.has(region.toUpperCase()) ? 'miles' : 'kilometers';
  }
  return 'kilometers';
}

export function resolveDistanceUnit(
  preference: DistanceUnitPreference,
  preferredLocales: readonly string[],
): DistanceUnit {
  return preference === 'automatic' ? automaticDistanceUnit(preferredLocales) : preference;
}

export function convertDistanceFromKilometers(kilometers: number, unit: DistanceUnit): number {
  return unit === 'miles' ? kilometers * 0.621371192237334 : kilometers;
}

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function readDistanceUnitPreference(): DistanceUnitPreference {
  try {
    const stored = storage()?.getItem(DISTANCE_UNIT_STORAGE_KEY);
    if (stored !== null && stored !== undefined && isDistanceUnitPreference(stored)) return stored;
  } catch {
    // A blocked storage API has the same behavior as an unset preference.
  }
  return 'kilometers';
}

export function writeDistanceUnitPreference(preference: DistanceUnitPreference): void {
  try {
    storage()?.setItem(DISTANCE_UNIT_STORAGE_KEY, preference);
  } catch {
    // Private browsing can reject writes. The preference still applies to this page session.
  }
}
