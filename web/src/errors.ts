import type { TextKey } from './i18n';

/**
 * An error whose text the user will see, carrying the catalog key rather than the message.
 *
 * renderer.ts and video.ts stay locale-free: they throw AppError with a stable code and an
 * English message, and main.ts turns the code into text with the active catalog. The English
 * message is what reaches the console and any caller that does not know about codes.
 */
export class AppError extends Error {
  constructor(public readonly code: TextKey, message: string) {
    super(message);
    this.name = 'AppError';
  }
}

/** Never include provider response text or credential-bearing URLs. */
export class VectorMapError extends Error {
  constructor() {
    super('Vector map unavailable');
    this.name = 'VectorMapError';
  }
}
