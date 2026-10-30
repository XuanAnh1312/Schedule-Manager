# Issue #8 – Bảng bằng chứng nghiệm thu (điền khi chạy trên máy người thực hiện)

> Chưa có kết quả kiểm thử trực tiếp trên SQL Server của người dùng trong môi trường tạo gói. Đánh dấu **CHƯA XÁC MINH**, không coi test thiết kế là test đã PASS.
>
> Issue #8 nêu AC-03, AC-07, AC-09 nhưng không cung cấp văn bản đầy đủ của từng AC; ánh xạ bên dưới theo phạm vi BI và cần đối chiếu với tài liệu AC của nhóm.

| Nhóm | Kịch bản | Cách chạy | Kết quả hiện tại | Bằng chứng cần lưu |
|---|---|---|---|---|
| BI-01 / AC-03 (đối chiếu) | JWT nhân viên, check-in ca published, không lặp open | QA fixture + API | CHƯA XÁC MINH | HTTP 200,409; bản ghi SQL |
| BI-01 | Check-out, nghỉ, ca qua ngày, late/night/overtime | API + unit test | CHƯA XÁC MINH môi trường | Screenshot JSON/JUnit |
| BI-02 / AC-07 (đối chiếu) | Request before/after, duyệt/từ chối, cạnh tranh duyệt | Hai tài khoản/2 terminal | CHƯA XÁC MINH | JSON + attendance_audit |
| BI-02 | Khóa kỳ, điều chỉnh có kiểm soát | SQL QA + API | CHƯA XÁC MINH | 409 + audit |
| BI-03 | Dashboard cùng định nghĩa bảng | GET dashboard/users | CHƯA XÁC MINH | So sánh theo periodId |
| BI-04 | CSV/XLSX đúng quyền, không lộ employee khác | GET export qua token 2 vai trò | CHƯA XÁC MINH | File thử + 403 |
| BI-05 / AC-09 (đối chiếu) | 808/740 = 109%, +68h; 0 giờ mục tiêu | `mvn test` | CHƯA XÁC MINH Maven | Test output |
| BI-06 | 320,360,390,768,1024,1280 CSS px | `tests/attendance-responsive.html` | CHƯA XÁC MINH browser | Kết quả runner + ảnh |
| BI-06 | Windows scale 125%, browser zoom 200%, keyboard | Thủ công | CHƯA XÁC MINH | OS scale + DevTools |
| BI-07 | SPA login/route/calendar/modal không hồi quy | `tests/browser-smoke.html` gốc | CHƯA XÁC MINH | Smoke log |
| BI-07 | API lỗi, 401/403, empty/loading, reload, 2 browser | Test thủ công | CHƯA XÁC MINH | HTTP response, video/ảnh |
| AC-10, AC-14 (phối hợp) | Đồng bộ periods/assignments thật với UI demo | Nhóm phụ trách API phân lịch | CHƯA TÍCH HỢP | PR + tài liệu API |

### Ghi nhận người kiểm thử

- Ngày chạy: ______
- Commit SHA: ______
- Backend/Java: ______
- SQL Server/Flyway: ______
- Browser/Windows scale/deviceScaleFactor: ______
- Kết quả `mvn test`: ______
- API fixture/negative/security: ______
- Bằng chứng đường dẫn: ______
- Phân tích lỗi còn mở: ______
