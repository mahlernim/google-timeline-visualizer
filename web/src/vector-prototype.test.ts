import { describe, expect, it } from 'vitest';
import { authenticatedCartoUrl, vectorCamera } from '../prototypes/vector-map';

describe('vector prototype projection and credential scope', () => {
  it('matches the existing normalized Mercator viewport with a 512px world at zoom zero', () => {
    const camera = vectorCamera({ minX: 0, maxX: 1, minY: 0, maxY: 1, zoom: 0 }, { width: 512, height: 512 });
    expect(camera).toEqual({ center: [0, 0], zoom: 0 });
  });
  it('preserves unwrapped longitude across the date line', () => {
    const camera = vectorCamera({ minX: .9, maxX: 1.1, minY: .4, maxY: .6, zoom: 2 }, {width:512,height:512});
    expect(camera.center).toEqual([180, 0]);
    expect(camera.zoom).toBeCloseTo(Math.log2(5));
  });
  it('authenticates every CARTO asset but never unrelated or insecure hosts', () => {
    expect(new URL(authenticatedCartoUrl('https://tiles.basemaps.cartocdn.com/fonts/test.pbf', 'test key')).searchParams.get('key')).toBe('test key');
    for (const url of ['https://example.com/style.json','https://basemaps.cartocdn.com.evil.test/a','http://basemaps.cartocdn.com/a']) {
      expect(authenticatedCartoUrl(url, 'test key')).toBe(url);
    }
  });
});
