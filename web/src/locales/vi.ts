import type { Strings } from "../i18n";

/** Vietnamese catalog. Keep placeholders and punctuation aligned with en.ts. */
export const vi: Strings = {
  appName: "Timeline Visualizer",
  appShortName: "Dòng thời gian",
  appDescription:
    "Tạo video hoạt ảnh hành trình một cách riêng tư từ tệp xuất Dòng thời gian của Google Maps.",
  previewBanner:
    "Bản xem trước iPhone · Hãy thử trước bằng dữ liệu mẫu không nhạy cảm",
  headerTitle: "Tạo video",
  fileCardTitle: "Tệp Dòng thời gian",
  fileCardIntro:
    "Chọn tệp Dòng thời gian đã xuất. Tệp chỉ ở trên thiết bị này và nhà cung cấp bản đồ chỉ nhận yêu cầu ảnh bản đồ.",
  exportHelpSummary: "Cách xuất Dòng thời gian trên iPhone",
  exportHelpStep1: "Mở Google Maps rồi chạm vào ảnh hồ sơ.",
  exportHelpStep2:
    "Mở Cài đặt, rồi Vị trí và quyền riêng tư (trước đây là Nội dung cá nhân).",
  exportHelpStep3: "Chọn Xuất dữ liệu trên Dòng thời gian.",
  exportHelpStep4:
    "Chọn Lưu vào Tệp, chọn thư mục rồi lưu. Quay lại đây với location-history.json hoặc Timeline.json.",
  addToHomeScreenHint:
    "Để giữ ứng dụng này trên iPhone, hãy mở menu Chia sẻ của Safari và chọn Thêm vào Màn hình chính.",
  chooseFileButton: "Chọn tệp Dòng thời gian",
  sampleButton: "Thử hành trình mẫu hư cấu",
  fileStatusEmpty: "Chưa tải Dòng thời gian",
  compatibilityChecking: "Đang kiểm tra khả năng tạo video…",
  compatibilityFull: "Trình duyệt này có thể tạo video MP4 H.264.",
  compatibilityPartial:
    "Trình duyệt này có thể tạo video MP4 H.264. Một số định dạng video không khả dụng.",
  compatibilityPreviewOnly:
    "Chỉ xem trước. Để tạo MP4 cần Safari 16.4 trở lên có hỗ trợ H.264.",
  languageLabel: "Ngôn ngữ",
  languageSystemDefault: "Mặc định hệ thống",
  languageLockedExporting: "Không thể đổi ngôn ngữ khi đang tạo video.",
  languageLockedPreparing: "Không thể đổi ngôn ngữ khi đang chuẩn bị bản đồ.",
  distanceUnitLabel: "Đơn vị khoảng cách",
  distanceUnitAutomatic: "Tự động",
  distanceUnitKilometers: "Kilômét",
  distanceUnitMiles: "Dặm",
  distanceUnitAutomaticResolved: "{automatic} · {resolved}",
  settingsTitle: "Tạo hành trình",
  rawSignalsToggle: "Dùng dữ liệu vị trí thô",
  rawSignalsDescription:
    "Dùng các ước tính vị trí gần đây chưa xử lý. Nhiễu được giảm bớt, nhưng tuyến đường và khoảng cách vẫn có thể không chính xác.",
  rawRangeEmpty:
    "Không còn ước tính vị trí thô nào với giới hạn độ chính xác này.",
  rawRangeOnePoint: "1 ước tính vị trí thô vào {date}",
  rawRangeOneDay: "{count} ước tính vị trí thô vào {date}",
  rawRangeMultipleDays: "{count} ước tính vị trí thô từ {start} đến {end}",
  accuracyLimitLabel: "Giới hạn độ chính xác (mét)",
  accuracyLimitHelp:
    "Các vị trí kém chính xác hơn sẽ bị loại. Để trống để gồm mọi vị trí có thể dùng.",
  locationFilterLabel: "Lọc điểm bất thường GPS",
  locationFilterConservative: "Thận trọng",
  locationFilterOff: "Tắt",
  locationFilterHelp:
    "Chế độ thận trọng chỉ bỏ qua các chuyến đi khứ hồi ngắn không thể xảy ra. Tệp Dòng thời gian của bạn không bị thay đổi.",
  exactDatesToggle: "Chọn ngày chính xác",
  fromLabel: "Từ",
  toLabel: "Đến",
  startDateLabel: "Ngày bắt đầu",
  endDateLabel: "Ngày kết thúc",
  videoTitleLabel: "Tiêu đề video",
  defaultVideoTitle: "Hành trình của tôi",
  durationLabel: "Thời lượng",
  durationSeconds: { other: "{count} giây" },
  useRecommendedDuration: { other: "Dùng thời lượng đề xuất · {count} giây" },
  cameraMovementLabel: "Chuyển động máy quay",
  cameraFixed: "Thu phóng cố định",
  cameraSteady: "Theo dõi ổn định",
  cameraDynamic: "Theo dõi linh hoạt",
  cameraCloseUp: "Cận cảnh",
  aspectRatioLabel: "Tỷ lệ khung hình",
  aspectSquare: "Vuông",
  aspectPortrait: "Dọc",
  aspectLandscape: "Ngang",
  resolutionLabel: "Độ phân giải",
  videoFormatLabel: "Định dạng video",
  formatSquare480: "Vuông · 480p",
  formatSquare720: "Vuông · 720p",
  formatSquare1080: "Vuông · 1080p",
  formatPortrait: "Dọc · 1080×1920",
  formatLandscape: "Ngang · 1920×1080",
  videoFormatHelp:
    "Nhập cạnh ngắn theo pixel từ 480 đến 2160. Định dạng lớn hơn mất nhiều thời gian hơn và tạo tệp lớn hơn.",
  frameRateLabel: "Tốc độ khung hình",
  frameRateRecommended: "Đề xuất · {fps} fps",
  frameRateValue: "{fps} fps",
  frameRateHelp:
    "Tốc độ khung hình cao hơn mượt hơn nhưng mất nhiều thời gian hơn và tạo tệp lớn hơn.",
  privacyNoticeTitle: "Trước khi tải bản đồ",
  privacyNoticeBody:
    "Tệp Dòng thời gian của bạn không bao giờ được tải lên. CARTO nhận tọa độ ô bản đồ của khu vực trong hành trình đã chọn, cùng thông tin mạng thông thường như địa chỉ IP. Điều này có thể tiết lộ các địa điểm trong Dòng thời gian của bạn cho CARTO.",
  mapConsentLabel: "Tôi hiểu và muốn tải bản đồ",
  privacyPolicyLink: "Đọc chính sách quyền riêng tư của ứng dụng web",
  previewButton: "Xem trước",
  createButton: "Tạo MP4",
  previewTitle: "Xem trước",
  progressReady: "Sẵn sàng",
  progressPreparingMap: "Đang chuẩn bị bản đồ",
  progressPreparingMapCount: "Đang chuẩn bị bản đồ {completed}/{total}",
  progressPreviewing: "Đang xem trước",
  progressPreviewComplete: "Đã xem trước xong",
  progressCreating: "Đang tạo MP4",
  progressCreatingPercent: "Đang tạo MP4 {percent}",
  progressCancelling: "Đang hủy…",
  progressCancelled: "Đã hủy tạo video",
  progressFailed: "Không thể tạo video",
  progressVideoReady: "Video đã sẵn sàng · {size} MB",
  cancelButton: "Hủy tạo video",
  shareButton: "Chia sẻ video",
  downloadButton: "Tải MP4 xuống",
  footerNoAccount:
    "Không cần tài khoản ứng dụng, quyền vị trí hay tải Dòng thời gian lên.",
  footerMapAttribution:
    "Dữ liệu bản đồ © các cộng tác viên OpenStreetMap và © CARTO.",
  footerThirdPartyNotices: "Thông báo của bên thứ ba",
  openTestingLink: "Ứng dụng Android · Tham gia thử nghiệm mở trên Google Play",
  rawOnlyDialogTitle: "Chỉ tìm thấy dữ liệu vị trí thô",
  rawOnlyDialogBody1:
    "Tệp này có dữ liệu vị trí thô nhưng không có các lượt ghé thăm hoặc chuyến đi đã xử lý. Bạn có thể tiếp tục, nhưng tuyến đường có thể nhiễu hoặc không đầy đủ và khoảng cách chỉ là ước tính.",
  rawOnlyDialogBody2:
    "Để có kết quả tốt hơn, hãy mở Google Maps, xác nhận hoặc khôi phục Dòng thời gian và kiểm tra xem các lượt ghé thăm cùng chuyến đi có xuất hiện. Sau đó xuất lại Dòng thời gian và tải tệp mới vào ứng dụng này.",
  openGoogleMapsButton: "Mở Google Maps",
  continueRawDataButton: "Tiếp tục với dữ liệu thô",
  listSeparator: " · ",
  summaryNoLocations: "Không có vị trí trong giai đoạn này",
  summaryOneLocation: "1 điểm vị trí · Hãy chọn giai đoạn rộng hơn",
  summaryNoMovement: { other: "{count} điểm vị trí · Không có di chuyển" },
  summaryDistanceAbout: { other: "{count} điểm vị trí · Khoảng {distance}" },
  summaryDistanceEstimated: {
    other: "{count} điểm vị trí · Ước tính {distance}",
  },
  summaryOutliersIgnored: { other: "Đã bỏ qua {count} vị trí đáng ngờ" },
  summaryRawRejected: {
    other: "Đã bỏ qua {count} điểm nhiễu hoặc không chính xác",
  },
  fileStatusLoaded: {
    other: "{source} · {count} điểm hợp lệ từ {firstMonth} đến {lastMonth}",
  },
  fileStatusRawFallback: "Dùng dữ liệu vị trí thô thay thế",
  fileStatusTimezoneMissing:
    "Thiếu múi giờ, giữ nguyên thứ tự tuyến đường khi xuất",
  fileStatusReading: "Đang đọc {name}…",
  fileStatusLoadingSample: "Đang tải mẫu hư cấu…",
  sampleSourceName: "Mẫu hư cấu",
  fileStatusLoadFailed: "Không thể tải Dòng thời gian",
  fileStatusSampleFailed: "Không thể tải mẫu",
  fileStatusRawOnly: "Chỉ tìm thấy dữ liệu vị trí thô",
  fileStatusExportAgain:
    "Hãy xuất lại Dòng thời gian sau khi xuất hiện các lượt ghé thăm và chuyến đi, rồi tải tệp mới ở đây.",
  fileStatusRawImportCancelled: "Đã hủy nhập dữ liệu vị trí thô",
  periodRawLocationData: "Dữ liệu vị trí thô",
  periodRange: "{start} – {end}",
  errorAccuracyLimit: "Nhập giới hạn độ chính xác không âm hoặc để trống.",
  errorMapConsent:
    "Hãy xác nhận thông báo quyền riêng tư của bản đồ trước khi yêu cầu ảnh bản đồ từ CARTO.",
  errorMalformedJson: "Đây không phải tệp JSON hợp lệ hoặc đầy đủ.",
  errorLegacyFormat:
    "Đây là định dạng Google Takeout cũ. Hãy xuất dữ liệu trên Dòng thời gian từ điện thoại.",
  errorRawSignalsOnly:
    "Bản xuất này có tín hiệu thô nhưng không có hành trình Dòng thời gian đã được dựng lại.",
  errorUnsupportedFormat:
    "JSON Dòng thời gian phải là mảng hoặc chứa semanticSegments.",
  errorNoUsableLocations:
    "Bản xuất Dòng thời gian này không có điểm vị trí nào có thể dùng.",
  errorFileUnreadable: "Không thể đọc tệp đã chọn.",
  errorSampleUnavailable: "Không thể tải mẫu hư cấu.",
  errorPreviewFailed: "Xem trước thất bại.",
  errorExportFailed: "Tạo video thất bại.",
  errorShareUnavailable:
    "Không thể mở bảng chia sẻ của iPhone. Hãy dùng Tải MP4 xuống.",
  errorTooFewPoints: "Chọn giai đoạn có ít nhất hai điểm vị trí.",
  errorNoEncoder:
    "Trình duyệt này không thể tạo video MP4. Hãy dùng Safari 16.4 trở lên.",
  errorFormatUnsupported:
    "Trình duyệt này không thể tạo video {width}×{height} ở {fps} fps. Hãy chọn định dạng hoặc tốc độ khung hình khác.",
  errorEncoderOutput: "Bộ mã hóa video không tạo ra tệp MP4.",
  errorEncoderInvalid: "Bộ mã hóa video tạo ra tệp MP4 không hợp lệ.",
  errorCanvasUnavailable: "Không thể kết xuất Canvas.",
  errorCanvasSize:
    "Bản xem trước không dùng kích thước định dạng video đã chọn.",
  errorAspectRatio:
    "Hành trình đã chuẩn bị không khớp tỷ lệ khung hình Canvas.",
  hintCheckingSupport: "Đang kiểm tra hỗ trợ video của trình duyệt.",
  hintNoEncoder: "Để tạo MP4 cần Safari 16.4 trở lên có hỗ trợ mã hóa H.264.",
  hintFormatUnsupported:
    "Trình duyệt này không thể tạo video {width}×{height}. Hãy chọn định dạng khác.",
  hintSelectWiderPeriod: "Chọn giai đoạn có ít nhất hai vị trí khác nhau.",
  warnFormatLockedExporting:
    "Không thể đổi định dạng video khi đang tạo video.",
  warnFormatLockedPreparing:
    "Không thể đổi định dạng video khi đang chuẩn bị bản đồ.",
};
