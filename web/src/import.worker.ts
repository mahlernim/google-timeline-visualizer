import { scanTimeline, extractTimeline } from './stream-import';
import { ImportError } from './import-types';
import { TimelineParseError } from './timeline';
import type { ImportRequest, ImportResponse } from './import-types';

self.onmessage = async (event: MessageEvent<ImportRequest>): Promise<void> => {
  const send = (message: ImportResponse): void => self.postMessage(message);
  let lastProgress = 0;
  const progress = (fraction: number): void => {
    const now = performance.now();
    if (now - lastProgress > 100 || fraction === 1) {
      lastProgress = now;
      send({ kind: 'progress', fraction });
    }
  };
  try {
    const { file, range } = event.data;
    if (range) send({ kind: 'range', result: await extractTimeline(file, range, undefined, progress) });
    else send({ kind: 'scan', scan: await scanTimeline(file, undefined, progress) });
  } catch (error) {
    send({ kind: 'error', code: error instanceof ImportError ? error.code
      : error instanceof TimelineParseError ? error.reason : 'importFailed' });
  }
};
