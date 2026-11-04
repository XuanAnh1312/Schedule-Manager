# Bằng chứng chấm công và báo cáo — cập nhật 04/11/2026

Ma trận AC đầy đủ và kết quả của bản tích hợp: [Báo cáo bàn giao mốc A/mốc B](../HUONG_DAN/BAO_CAO_BAN_GIAO_MOC_A_VA_KE_HOACH_MOC_B.md). Chuẩn ánh xạ là task_v1.1.md, thay cho bảng đối chiếu Issue #8 cũ.

| Yêu cầu | Bằng chứng hiện tại |
|---|---|
| BI-01: check-in/out, trùng lượt, thời gian/nghỉ/ca đêm | Unit AttendanceMathTest, SQL IntegratedMocAIT, HTTP ca đang diễn ra/check-in trùng/check-out thật |
| BI-02: đề nghị sửa, version, duyệt, khóa kỳ | SQL stale request, kỳ chồng ngày, khóa chặn approve thường và cho điều chỉnh có kiểm soát; HTTP request/review/lock |
| AC-03: tổng giờ | SQL fixture 808/740: +68h, 109%; mẫu số 0; CSV/XLSX; cùng nguồn giờ lịch |
| AC-07: context số liệu | SQL dashboard/canonical/employee filter, HTTP đối chiếu tổng giờ; published/plan có nhãn riêng |
| AC-09: responsive | Browser sáu viewport và DPR/zoom đã đạt; Windows Display Scale thật khác 100% còn chờ xác nhận |
| AC-10: backend chuẩn | Snapshot có công thật không rỗng giữ nguyên qua refresh/logout/login/trình duyệt độc lập |
| AC-11/12: quyền và audit | API userId/branchId giả, kỳ nháp/không áp dụng, profile/audit trái quyền; audit trước/sau/lý do và outbox rollback/retry |
| AC-14: tự tính | Rules fixed/percent qua backend; báo cáo tự tính tỷ lệ/chênh lệch, không nhập trực tiếp số suy ra |

Log/JSON/JUnit/ảnh và manifest ở .local-test; kết quả tóm tắt không chứa credentials ở HUONG_DAN/KET_QUA_KIEM_THU.json. Không đánh dấu mốc A 100% trước khi đóng bước Windows Scale thật.
