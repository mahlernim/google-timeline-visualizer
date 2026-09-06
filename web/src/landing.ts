import './landing.css';
import { activeLocale, readLanguagePreference, writeLanguagePreference, isLanguagePreference } from './language';
import { populateLanguageSelect } from './language-select';
import { applyFlowStrings, flowText } from './flow-i18n';
import { preferAndroid, mayAutoplay } from './device';

const language = document.getElementById('landing-language') as HTMLSelectElement;
const video = document.getElementById('demo-video') as HTMLVideoElement;
const toggle = document.getElementById('demo-toggle') as HTMLButtonElement;
const android = document.getElementById('android-options')!;
const web = document.getElementById('web-option')!;
const choices = document.getElementById('choices')!;
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
const connection = (navigator as Navigator & { connection?: EventTarget & { saveData?: boolean } }).connection;
let preference = readLanguagePreference();
let locale = activeLocale(preference, navigator.languages ?? [navigator.language]);
let visible = false;
let manuallyPaused = false;
let autoplayBlocked = false;
let manuallyStarted = false;
function text(): void {
  locale = activeLocale(preference, navigator.languages ?? [navigator.language]);
  document.documentElement.lang = locale;
  applyFlowStrings(document, locale);
  language.setAttribute('aria-label', flowText(locale, 'language'));
  populateLanguageSelect(language, preference, navigator.languages ?? [navigator.language]);
  toggle.textContent = flowText(locale, video.paused ? 'playDemo' : 'pauseDemo');
}
text();
const androidFirst = preferAndroid(navigator.userAgent, navigator.maxTouchPoints);
document.documentElement.dataset.platform = androidFirst ? 'android' : 'web';
if (androidFirst) choices.replaceChildren(android, web); else choices.replaceChildren(web, android);
function attachVideo(): void {
  if (!video.getAttribute('src')) video.src = import.meta.env.BASE_URL + 'demo-mahlerlab.mp4';
}
function syncPlayback(): void {
  const allowed = mayAutoplay(reduced.matches, connection?.saveData === true);
  if (document.hidden || !visible || (!allowed && !manuallyStarted) || manuallyPaused || autoplayBlocked) {
    video.pause();
    return;
  }
  attachVideo();
  void video.play().catch(() => { autoplayBlocked = true; text(); });
}
const observer = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; syncPlayback(); }, { threshold: 0.2 });
requestAnimationFrame(() => observer.observe(video));
toggle.addEventListener('click', () => {
  if (!video.paused) { manuallyPaused = true; video.pause(); return; }
  manuallyPaused = false;
  autoplayBlocked = false;
  manuallyStarted = true;
  attachVideo();
  void video.play().catch(() => { autoplayBlocked = true; text(); });
});
video.addEventListener('play', text);
video.addEventListener('pause', text);
video.addEventListener('error', () => { autoplayBlocked = true; text(); });
document.addEventListener('visibilitychange', syncPlayback);
reduced.addEventListener('change', () => { manuallyStarted = false; syncPlayback(); });
connection?.addEventListener('change', () => { manuallyStarted = false; syncPlayback(); });
language.addEventListener('change', () => {
  if (!isLanguagePreference(language.value)) return;
  preference = language.value;
  writeLanguagePreference(preference);
  text();
});
window.addEventListener('languagechange', text);
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register(import.meta.env.BASE_URL + 'service-worker.js?v=' + import.meta.env.VITE_SW_VERSION,
      { updateViaCache: 'none' }).catch(() => undefined);
  });
}
