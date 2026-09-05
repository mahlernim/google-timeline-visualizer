import { ImportError } from './import-types';
import type { ImportRequest, ImportResponse } from './import-types';

/** Each job owns a worker. Termination releases parser state on success, failure, or cancellation. */
export function runImport(
  request: ImportRequest, signal: AbortSignal, progress: (fraction: number) => void,
): Promise<Exclude<ImportResponse, { kind: 'progress' } | { kind: 'error' }>> {
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./import.worker.ts', import.meta.url), { type: 'module' });
    const cleanup = (): void => { worker.terminate(); signal.removeEventListener('abort', abort); };
    const abort = (): void => { cleanup(); reject(new DOMException('Cancelled', 'AbortError')); };
    signal.addEventListener('abort', abort, { once: true });
    worker.onerror = () => { cleanup(); reject(new ImportError('importFailed')); };
    worker.onmessage = (event: MessageEvent<ImportResponse>) => {
      if (signal.aborted) return;
      const message = event.data;
      if (message.kind === 'progress') { progress(message.fraction); return; }
      cleanup();
      if (message.kind === 'error') reject(new ImportError(message.code));
      else resolve(message);
    };
    worker.postMessage(request);
  });
}
