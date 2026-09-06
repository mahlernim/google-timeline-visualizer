import { afterEach, describe, expect, it, vi } from 'vitest';
import { SHARE_URL, shareApp } from './share-app';

const originalShare = Object.getOwnPropertyDescriptor(navigator, 'share');
const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');

function setNavigatorProperty(name: 'share' | 'clipboard', value: unknown): void {
  Object.defineProperty(navigator, name, { configurable: true, value });
}

afterEach(() => {
  if (originalShare) Object.defineProperty(navigator, 'share', originalShare);
  else Reflect.deleteProperty(navigator, 'share');
  if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard);
  else Reflect.deleteProperty(navigator, 'clipboard');
});

describe('shareApp', () => {
  it('uses native sharing with canonical data', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    setNavigatorProperty('share', share);

    await expect(shareApp('Hello')).resolves.toBe('shared');
    expect(share).toHaveBeenCalledWith({ title: 'Timeline Visualizer', text: 'Hello', url: SHARE_URL });
  });

  it('keeps a cancelled native share quiet', async () => {
    setNavigatorProperty('share', vi.fn().mockRejectedValue(new DOMException('', 'AbortError')));

    await expect(shareApp('Hello')).resolves.toBe('cancelled');
  });

  it('copies the actual canonical URL without native sharing', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setNavigatorProperty('share', undefined);
    setNavigatorProperty('clipboard', { writeText });

    await expect(shareApp('Hello')).resolves.toBe('copied');
    expect(writeText).toHaveBeenCalledExactlyOnceWith(SHARE_URL);
  });

  it('uses the manual-copy path when clipboard access fails', async () => {
    setNavigatorProperty('share', undefined);
    setNavigatorProperty('clipboard', { writeText: vi.fn().mockRejectedValue(new Error()) });

    await expect(shareApp('Hello')).resolves.toBe('manual');
  });
});
