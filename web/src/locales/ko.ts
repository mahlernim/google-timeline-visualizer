import type { Strings } from '../i18n';

/**
 * Korean (ko) catalog. Translated; every value is real Korean text.
 *
 * Terminology follows app/src/main/res/values-ko/strings.xml so the Android app and the web app
 * never call the same thing two different names. 'Timeline Visualizer' stays untranslated as the
 * product name, and MP4, H.264, JSON, Timeline.json, GPS, CARTO, OpenStreetMap and Safari stay in
 * English the way this platform shows them.
 *
 * Plural categories for this locale, from Intl.PluralRules: other. Do not
 * add or remove a category; the test derives the expected set from ICU and will reject either.
 *
 * Keep every placeholder exactly as it appears in the English source, in every plural form,
 * and keep the typography: U+2019, U+00B7 in ' · ', U+2013, U+00D7 and U+2026. 'Personal
 * content', 'Export Timeline data', 'Add to Home Screen' and 'Files app' must use the official
 * localized Google Maps and iOS labels rather than a literal translation.
 */
export const ko: Strings = {
  appName: 'Timeline Visualizer',
  appShortName: '타임라인',
  appDescription: 'Google 지도 타임라인 내보내기 파일로 움직이는 여행 동영상을 기기에서만 만드세요.',
  previewBanner: 'iPhone 미리보기 빌드 · 민감하지 않은 샘플 데이터로 먼저 테스트하세요',
  headerTitle: '동영상 만들기',

  fileCardTitle: '타임라인 파일',
  fileCardIntro: '내보낸 Timeline.json 파일을 선택하세요. 파일은 이 기기에 남으며, 지도 제공업체에는 지도 이미지만 요청합니다.',
  exportHelpSummary: 'iPhone에서 타임라인 내보내는 방법',
  exportHelpStep1: 'Google 지도를 열고 프로필 사진을 탭하세요.',
  exportHelpStep2: '설정을 연 다음 개인 콘텐츠로 이동하세요.',
  exportHelpStep3: '타임라인 데이터 내보내기를 선택하세요.',
  exportHelpStep4: 'Timeline.json을 파일 앱에 저장한 뒤 여기로 돌아오세요.',
  addToHomeScreenHint: 'iPhone에 이 앱을 남겨 두려면 Safari의 공유 메뉴에서 홈 화면에 추가를 선택하세요.',
  chooseFileButton: 'Timeline.json 선택',
  sampleButton: '가상 샘플 여행 체험하기',
  fileStatusEmpty: '불러온 타임라인 없음',
  compatibilityChecking: '동영상 지원 확인 중…',
  compatibilityFull: '이 브라우저에서 H.264 MP4 동영상을 만들 수 있습니다.',
  compatibilityPartial: '이 브라우저에서 H.264 MP4 동영상을 만들 수 있습니다. 일부 동영상 형식은 사용할 수 없습니다.',
  compatibilityPreviewOnly: '미리보기만 가능합니다. MP4를 만들려면 H.264를 지원하는 Safari 16.4 이상이 필요합니다.',

  languageLabel: '언어',
  languageSystemDefault: '시스템 기본값',
  languageLockedExporting: '동영상을 만드는 중에는 언어를 변경할 수 없습니다.',
  languageLockedPreparing: '지도를 준비하는 중에는 언어를 변경할 수 없습니다.',
  distanceUnitLabel: '거리 단위',
  distanceUnitAutomatic: '자동',
  distanceUnitKilometers: '킬로미터',
  distanceUnitMiles: '마일',
  distanceUnitAutomaticResolved: '{automatic} · {resolved}',

  settingsTitle: '이동 경로 만들기',
  rawSignalsToggle: '원시 위치 데이터 사용',
  rawSignalsDescription: '최근의 처리되지 않은 위치 정보를 사용합니다. 노이즈를 줄이지만 경로와 이동 거리가 여전히 부정확할 수 있습니다.',
  rawRangeEmpty: '이 정확도 한도에 맞는 원시 위치 정보가 없습니다.',
  rawRangeOnePoint: '{date}의 원시 위치 정보 1개',
  rawRangeOneDay: '{date}의 원시 위치 정보 {count}개',
  rawRangeMultipleDays: '{start}부터 {end}까지 원시 위치 정보 {count}개',
  accuracyLimitLabel: '정확도 한도(미터)',
  accuracyLimitHelp: '정확도가 낮은 위치는 제외됩니다. 사용 가능한 모든 위치를 포함하려면 비워 두세요.',
  locationFilterLabel: 'GPS 이상치 필터링',
  locationFilterConservative: '보수적',
  locationFilterOff: '끄기',
  locationFilterHelp: '보수적 필터링은 짧고 불가능한 왕복 이동만 무시합니다. 타임라인 파일은 변경되지 않습니다.',
  exactDatesToggle: '정확한 날짜 선택',
  fromLabel: '시작',
  toLabel: '종료',
  startDateLabel: '시작일',
  endDateLabel: '종료일',
  videoTitleLabel: '동영상 제목',
  defaultVideoTitle: '나의 여행',
  durationLabel: '재생 시간',
  durationSeconds: {
    other: '{count}초',
  },
  cameraMovementLabel: '카메라 움직임',
  cameraFixed: '고정 줌',
  cameraSteady: '안정적 따라가기',
  cameraDynamic: '역동적 따라가기',
  cameraCloseUp: '근접',
  videoFormatLabel: '동영상 형식',
  formatSquare480: '정사각형 · 480p',
  formatSquare720: '정사각형 · 720p',
  formatSquare1080: '정사각형 · 1080p',
  formatPortrait: '세로 · 1080×1920',
  formatLandscape: '가로 · 1920×1080',
  videoFormatHelp: '형식이 클수록 만드는 데 시간이 더 걸리고 파일 크기도 커집니다.',
  frameRateLabel: '프레임 속도',
  frameRateRecommended: '권장 · {fps} fps',
  frameRateValue: '{fps} fps',
  frameRateHelp: '프레임 속도가 높을수록 더 부드럽지만 만드는 시간이 길어지고 파일도 커집니다.',
  privacyNoticeTitle: '지도를 불러오기 전에',
  privacyNoticeBody: '타임라인 파일은 업로드되지 않습니다. CARTO에는 선택한 이동 경로의 지도 영역에 해당하는 타일 좌표가 IP 주소 같은 일반적인 네트워크 정보와 함께 전송됩니다. 이 정보로 타임라인에 포함된 장소가 CARTO에 알려질 수 있습니다.',
  mapConsentLabel: '이해했으며 지도를 불러오겠습니다',
  privacyPolicyLink: '웹 앱 개인정보 처리방침 읽기',
  previewButton: '미리보기',
  createButton: 'MP4 만들기',

  previewTitle: '미리보기',
  progressReady: '준비 완료',
  progressPreparingMap: '지도 준비 중',
  progressPreparingMapCount: '지도 준비 중 {completed}/{total}',
  progressPreviewing: '미리보기 재생 중',
  progressPreviewComplete: '미리보기 완료',
  progressCreating: 'MP4 만드는 중',
  progressCreatingPercent: 'MP4 만드는 중 {percent}',
  progressCancelling: '취소하는 중…',
  progressCancelled: '동영상 만들기를 취소했습니다',
  progressFailed: '동영상을 만들지 못했습니다',
  progressVideoReady: '동영상 준비 완료 · {size} MB',
  cancelButton: '동영상 만들기 취소',
  shareButton: '동영상 공유',
  downloadButton: 'MP4 다운로드',

  footerNoAccount: '계정도, 위치 권한도, 타임라인 업로드도 필요하지 않습니다.',
  footerMapAttribution: '지도 데이터 © OpenStreetMap 기여자 및 © CARTO.',
  footerThirdPartyNotices: '타사 고지 사항',

  rawOnlyDialogTitle: '원시 위치 데이터만 발견됨',
  rawOnlyDialogBody1: '이 파일에는 처리된 방문 및 이동 기록 없이 원시 위치 정보만 포함되어 있습니다. 계속할 수 있지만 경로가 부정확하거나 일부 누락될 수 있으며, 이동 거리는 추정값으로 표시됩니다.',
  rawOnlyDialogBody2: '더 정확한 결과를 얻으려면 Google 지도에서 타임라인을 확인하거나 복원하고, 방문 및 이동 기록이 표시되는지 확인하세요. 그런 다음 타임라인을 다시 내보낸 후 이 앱에서 불러오세요.',
  openGoogleMapsButton: 'Google 지도 열기',
  continueRawDataButton: '원시 데이터로 계속',

  listSeparator: ' · ',
  summaryNoLocations: '이 기간에는 위치 정보가 없습니다',
  summaryOneLocation: '위치 1개 · 기간을 넓혀 주세요',
  summaryNoMovement: {
    other: '위치 {count}개 · 이동 없음',
  },
  summaryDistanceAbout: {
    other: '위치 {count}개 · 약 {distance}',
  },
  summaryDistanceEstimated: {
    other: '위치 {count}개 · 추정 거리 {distance}',
  },
  summaryOutliersIgnored: {
    other: '의심스러운 위치 {count}개를 무시함',
  },
  summaryRawRejected: {
    other: '노이즈가 많거나 부정확한 위치 {count}개를 무시함',
  },

  fileStatusLoaded: {
    other: '{source} · {firstMonth}부터 {lastMonth}까지 유효한 위치 {count}개',
  },
  fileStatusRawFallback: '원시 위치 데이터로 대체됨',
  fileStatusTimezoneMissing: '시간대 정보 없음, 내보낸 경로 순서 유지',
  fileStatusReading: '{name} 읽는 중…',
  fileStatusLoadingSample: '가상 샘플 불러오는 중…',
  sampleSourceName: '가상 샘플',
  fileStatusLoadFailed: '타임라인을 불러오지 못했습니다',
  fileStatusSampleFailed: '샘플을 불러오지 못했습니다',
  fileStatusRawOnly: '원시 위치 데이터만 발견됨',
  fileStatusExportAgain: '방문 및 이동 기록이 표시된 후 타임라인을 다시 내보낸 뒤 새 파일을 여기에서 불러오세요.',
  fileStatusRawImportCancelled: '원시 위치 데이터 가져오기를 취소했습니다',

  periodRawLocationData: '원시 위치 데이터',
  periodRange: '{start} – {end}',

  errorAccuracyLimit: '0 이상의 정확도 한도를 입력하거나 비워 두세요.',
  errorMapConsent: 'CARTO에 지도 이미지를 요청하기 전에 지도 개인정보 안내를 확인해 주세요.',
  errorMalformedJson: '올바르거나 완전한 JSON 파일이 아닙니다.',
  errorLegacyFormat: '이 파일은 이전 Google 테이크아웃 형식입니다. 휴대전화에서 타임라인 데이터를 내보내 주세요.',
  errorRawSignalsOnly: '이 내보내기 파일에는 원시 신호만 있고 재구성된 타임라인 이동 경로가 없습니다.',
  errorUnsupportedFormat: 'Timeline JSON은 배열이거나 semanticSegments를 포함해야 합니다.',
  errorNoUsableLocations: '이 타임라인 내보내기 파일에는 사용할 수 있는 위치 정보가 없습니다.',
  errorFileUnreadable: '선택한 파일을 읽을 수 없습니다.',
  errorSampleUnavailable: '가상 샘플을 불러오지 못했습니다.',
  errorPreviewFailed: '미리보기에 실패했습니다.',
  errorExportFailed: '동영상 만들기에 실패했습니다.',
  errorShareUnavailable: 'iPhone 공유 시트를 열 수 없습니다. 대신 MP4 다운로드를 사용하세요.',
  errorTooFewPoints: '위치 정보가 두 개 이상 포함된 기간을 선택하세요.',
  errorNoEncoder: '이 브라우저에서는 MP4 동영상을 만들 수 없습니다. Safari 16.4 이상을 사용하세요.',
  errorFormatUnsupported: '이 브라우저에서는 {width}×{height}, {fps} fps 동영상을 만들 수 없습니다. 다른 형식이나 프레임 속도를 선택하세요.',
  errorEncoderOutput: '동영상 인코더가 MP4 파일을 만들지 못했습니다.',
  errorEncoderInvalid: '동영상 인코더가 잘못된 MP4 파일을 만들었습니다.',
  errorCanvasUnavailable: 'Canvas 렌더링을 사용할 수 없습니다.',
  errorCanvasSize: '미리보기가 선택한 동영상 형식 크기를 사용하고 있지 않습니다.',
  errorAspectRatio: '준비된 이동 경로가 Canvas 화면 비율과 맞지 않습니다.',

  hintCheckingSupport: '브라우저의 동영상 지원을 확인하는 중입니다.',
  hintNoEncoder: 'MP4를 만들려면 H.264 인코딩을 지원하는 Safari 16.4 이상이 필요합니다.',
  hintFormatUnsupported: '이 브라우저에서는 {width}×{height} 동영상을 만들 수 없습니다. 다른 형식을 선택하세요.',
  hintSelectWiderPeriod: '서로 다른 위치가 두 곳 이상 포함된 기간을 선택하세요.',
  warnFormatLockedExporting: '동영상을 만드는 중에는 동영상 형식을 변경할 수 없습니다.',
  warnFormatLockedPreparing: '지도를 준비하는 중에는 동영상 형식을 변경할 수 없습니다.',
};
