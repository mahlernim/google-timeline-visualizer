import { describe, expect, it } from 'vitest';

import { LatestPreparation } from './preparation-cache';

describe('LatestPreparation', () => {
  it('rejects a result that finishes after settings changed', () => {
    const cache = new LatestPreparation<string>();
    const stale = cache.token('old-selection');

    cache.invalidate();

    expect(cache.commit(stale, 'old journey')).toBe(false);
    expect(cache.cached(cache.token('old-selection'))).toBeNull();
  });

  it('returns only the journey for the current exact signature', () => {
    const cache = new LatestPreparation<string>();
    const current = cache.token('selection:format:camera:duration');

    expect(cache.commit(current, 'prepared journey')).toBe(true);
    expect(cache.cached(current)).toBe('prepared journey');
    expect(cache.cached(cache.token('selection:other-format'))).toBeNull();
  });
});
