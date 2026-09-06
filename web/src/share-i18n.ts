import type { LocaleTag } from './language';
export type ShareKey = 'button' | 'text' | 'copied' | 'manual' | 'manualInputLabel';
export const SHARE_STRINGS: Record<LocaleTag, Record<ShareKey, string>> = {
  en: { button: 'Share app', text: 'The original Timeline Visualizer. Turn your Google Maps Timeline into a journey video, privately on your device.', copied: 'App link copied.', manual: 'Copy this app link:', manualInputLabel: 'App link' },
  ko: { button: '앱 공유', text: '오리지널 Timeline Visualizer. Google Maps 타임라인을 기기에서만 여행 영상으로 만드세요.', copied: '앱 링크를 복사했습니다.', manual: '이 앱 링크를 복사하세요:', manualInputLabel: '앱 링크' },
  ja: { button: 'アプリを共有', text: 'オリジナルのTimeline Visualizer。Google マップのタイムラインから、端末内だけで旅動画を作れます。', copied: 'アプリのリンクをコピーしました。', manual: 'このアプリのリンクをコピーしてください:', manualInputLabel: 'アプリのリンク' },
  'zh-CN': { button: '分享应用', text: '原版 Timeline Visualizer。在设备上私密地将 Google 地图时间轴制作成旅程视频。', copied: '已复制应用链接。', manual: '请复制此应用链接：', manualInputLabel: '应用链接' },
  'zh-TW': { button: '分享應用程式', text: '原版 Timeline Visualizer。在裝置上私密地將 Google 地圖時間軸製作成旅程影片。', copied: '已複製應用程式連結。', manual: '請複製此應用程式連結：', manualInputLabel: '應用程式連結' },
  es: { button: 'Compartir aplicación', text: 'El Timeline Visualizer original. Convierte tu cronología de Google Maps en un vídeo de viaje privado en tu dispositivo.', copied: 'Enlace de la aplicación copiado.', manual: 'Copia este enlace de la aplicación:', manualInputLabel: 'Enlace de la aplicación' },
  fr: { button: 'Partager l’application', text: 'Le Timeline Visualizer original. Transformez votre chronologie Google Maps en vidéo de voyage, en privé sur votre appareil.', copied: 'Lien de l’application copié.', manual: 'Copiez ce lien vers l’application :', manualInputLabel: 'Lien de l’application' },
  de: { button: 'App teilen', text: 'Der originale Timeline Visualizer. Erstelle aus deiner Google Maps-Zeitachse ein Reisevideo, privat auf deinem Gerät.', copied: 'App-Link kopiert.', manual: 'Diesen App-Link kopieren:', manualInputLabel: 'App-Link' },
  'pt-BR': { button: 'Compartilhar aplicativo', text: 'O Timeline Visualizer original. Transforme sua Linha do tempo do Google Maps em um vídeo de viagem, de forma privada no seu dispositivo.', copied: 'Link do aplicativo copiado.', manual: 'Copie este link do aplicativo:', manualInputLabel: 'Link do aplicativo' },
  id: { button: 'Bagikan aplikasi', text: 'Timeline Visualizer yang asli. Ubah Linimasa Google Maps menjadi video perjalanan secara pribadi di perangkat Anda.', copied: 'Tautan aplikasi disalin.', manual: 'Salin tautan aplikasi ini:', manualInputLabel: 'Tautan aplikasi' },
  vi: { button: 'Chia sẻ ứng dụng', text: 'Timeline Visualizer nguyên bản. Tạo video hành trình từ Dòng thời gian Google Maps một cách riêng tư trên thiết bị của bạn.', copied: 'Đã sao chép liên kết ứng dụng.', manual: 'Sao chép liên kết ứng dụng này:', manualInputLabel: 'Liên kết ứng dụng' },
};
