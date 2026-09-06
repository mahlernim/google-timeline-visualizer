export const SHARE_URL = 'https://ahn-lab.org/google-timeline-visualizer/';

export type ShareResult = 'shared' | 'copied' | 'manual' | 'cancelled';

export async function shareApp(text: string): Promise<ShareResult> {
  const data = { title: 'Timeline Visualizer', text, url: SHARE_URL };

  if (navigator.share) {
    try {
      await navigator.share(data);
      return 'shared';
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return 'cancelled';
      return 'manual';
    }
  }

  try {
    await navigator.clipboard.writeText(SHARE_URL);
    return 'copied';
  } catch {
    return 'manual';
  }
}
