/** iPads with desktop identification and unknown devices stay on the web-first path. */
export function preferAndroid(userAgent: string, maxTouchPoints = 0): boolean {
  if (/iPad|iPhone|iPod/i.test(userAgent) || (/Macintosh/i.test(userAgent) && maxTouchPoints > 1)) return false;
  return /Android/i.test(userAgent);
}
export function mayAutoplay(reducedMotion: boolean, saveData: boolean): boolean {
  return !reducedMotion && !saveData;
}
