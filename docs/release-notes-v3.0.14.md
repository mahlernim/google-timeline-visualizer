# Timeline Visualizer 3.0.14

[Join Google Play open testing](https://play.google.com/apps/testing/dev.mahlernim.timelinevisualizer)
to install the Android app and receive updates through Google Play. Sign in with
the Google account used on your phone, join the test, then follow the download
link. Open testing needs no invitation or Google Group membership. Availability
depends on Google's review and your country or device. If the test is not
available yet, check the enrollment page again later. The APK remains available
under **Assets** below.

- Fixed a crash when entering zero as a custom frame rate on Android.
- Export cancellation now takes effect while retrying unavailable encoder input buffers, including when finishing a video.
- Fixed CLI map tile requests across the International Date Line and beyond map latitude bounds.
- CLI custom dimensions now accept 4K landscape and portrait sizes. The resolved short edge must be 480 through 2160 pixels, the long edge must not exceed 3840 pixels, and both dimensions must be even.
- New CLI 480p landscape videos use 852x480 instead of 854x480, and portrait videos use 480x852 instead of 480x854, matching current Android and web presets. Existing exported videos are unchanged.

## 한국어

- Android에서 사용자 지정 프레임 속도에 0을 입력하면 앱이 종료되던 문제를 수정했습니다.
- 인코더 입력 버퍼를 기다리는 중에도 동영상 생성 취소가 반영되도록 개선했습니다.
- CLI에서 날짜 변경선과 지도 위도 범위를 벗어나는 지도 타일 요청을 수정했습니다.
- CLI에서 4K 가로 및 세로 크기를 직접 지정할 수 있습니다.
- CLI의 새 480p 출력 크기를 Android 및 웹과 동일한 가로 852x480, 세로 480x852로 맞췄습니다. 기존 동영상은 변경되지 않습니다.

## 日本語

- Androidでカスタムフレームレートに0を入力するとクラッシュする問題を修正しました。
- エンコーダーの入力バッファを待機している間も動画作成のキャンセルが反映されるようになりました。
- CLIで日付変更線や地図の緯度範囲を超えるタイルのリクエストを修正しました。
- CLIで4Kの横向きと縦向きのサイズを直接指定できるようになりました。
- CLIの新しい480p動画を横向き852x480、縦向き480x852に統一しました。既存の動画は変更されません。
