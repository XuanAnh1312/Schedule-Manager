# Issue #8 · BI-01 đến BI-07 — chấm công, báo cáo, kiểm thử

**Nhánh nguồn được khảo sát:** `docs/task-v1.1` của `XuanAnh1312/Schedule-Manager`. Gói này là **patch bổ sung**, không thay V1–V5, không xóa hoặc chuyển dữ liệu demo và **không tạo commit trên nhánh gốc**.

## 1. Phạm vi và giới hạn tích hợp

- Backend SQL Server là nguồn chân lý của chấm công thực tế (không ghi giờ công vào `localStorage`).
- Frontend cũ (`index.html`, `js/storage.js`, `js/state.js`, các view phân lịch) **vẫn là demo localStorage**. Tạo trang riêng `attendance.html` để giảm xung đột và cung cấp đường vào qua sidebar Admin/User. Trang này dùng **JWT của backend**; tài khoản `admin/admin123` trên demo frontend **không thể dùng** để truy cập trang công; đăng nhập backend admin dev là `admin / Admin@123` từ V4 (chỉ cho thử nghiệm local).
- **Phải có period, users, shifts, assignments trong SQL** thì portal mới có ca để check-in và kỳ để báo cáo. Migration V5 chỉ tạo bảng/cấu hình, không tự nhập bản ghi demo trình duyệt vào SQL. Dùng fixture QA ở mục 5 cho kiểm thử; dự án thực tế cần API đồng bộ quản lý kỳ/ca/nhân viên từ nhóm phụ trách phần đó.
- Schema hiện tại **không có `organization_id` hoặc `manager_id`**. Phân quyền tạm thời: admin xem toàn bộ SQL Server; nhân viên chỉ xem và sửa/yêu cầu trên tài khoản của họ. **Không thể tuyên bố đã đáp ứng phân quyền nhiều phòng ban/cấp quản lý** trước khi nhóm schema bổ sung quan hệ tổ chức.
- Dashboard/báo cáo từ API dùng cùng truy vấn chuẩn của SQL, chỉ lấy assignments có ca `published`. Dashboard demo trong `js/admin.js` tiếp tục phản ánh dữ liệu riêng `localStorage`; không so sánh số giữa chúng khi hai nguồn chưa đồng bộ.
- Trạng thái `completed` (check-out xong) tính vào **giờ thực tế**, chỉ trạng thái `approved` mới tính vào **giờ công đã duyệt**. Không lấy giờ đã phân để giả làm giờ chấm công thực tế.

## 2. File mới

| File | Vai trò |
|---|---|
| `src/main/resources/db/migration/V6__attendance_reports.sql` | Bảng records, requests, locks, audit, policy; unique filtered index chống trùng |
| `src/main/java/org/dacnpm/schedulemanager/api/attendance/AttendanceMath.java` | Hàm giờ thực tế, muộn, tăng ca, ca đêm |
| `.../api/attendance/AttendanceService.java` | Transaction check-in/out, xin sửa/bổ sung, duyệt, khóa kỳ, kiểm tra actor |
| `.../api/attendance/AttendanceController.java` | REST endpoint dành cho JWT |
| `.../api/attendance/AttendanceErrorAdvice.java` | Lỗi API JSON có HTTP status |
| `.../api/report/ReportService.java` | Một nguồn tính giờ cho dashboard, bảng và export |
| `.../api/report/ReportController.java` | API tổng hợp và CSV/XLSX |
| `.../security/AttendanceCorsConfig.java` | Chỉ cho phép origin local:8000 hoặc local:5500 |
| `attendance.html`, `css/attendance.css`, `js/attendance-portal.js` | Portal chấm công responsive, tách khỏi demo |
| `js/attendance-entry.js` | Link dẫn từ sidebar demo sang portal SQL |
| `tests/attendance-api.ps1` | Smoke API an toàn, read-only theo mặc định |
| `tests/attendance-fixture.sql` | Dữ liệu QA giả lập trên máy local |
| `tests/attendance-responsive.html` | Kiểm tra màn hình 320/360/390/768/1024/1280 |
| `src/test/java/.../ReportServiceTest.java` | Kiểm thử công thức và giờ qua đêm |

**File gốc chỉ sửa tối thiểu:** `js/bootstrap.js` (nạp entry), `js/app.js` (2 menu), `SecurityConfig.java` (bật cấu hình CORS), `tests/browser-smoke.js` (6 viewport) và `Docs/testing.md` (liên kết suite mới). Script `tools/apply_issue8.py` kiểm tra anchor và sao lưu trước khi sửa. Không chỉnh `js/admin.js`, `js/user.js`, `js/admin3.js`, `js/storage.js`, `js/state.js`, `pom.xml` hay V1–V5.

## 3. Cách ghép patch và chạy trên Windows

1. Mở VS Code trong `D:\Web_lap_lich\Schedule-Manager`. **Sao lưu công việc chưa commit** và bảo đảm `git status` không có các thay đổi chưa kiểm soát.
2. Tại terminal repository:

```powershell
git fetch origin
git switch docs/task-v1.1
git pull --ff-only origin docs/task-v1.1
git switch -c feature/issue-8-attendance-reports
```

3. Giải nén file ZIP vào nơi riêng, ví dụ `D:\Web_lap_lich\issue8_patch` (trong đó có thư mục `schedule_issue8_patch`). Chạy:

```powershell
py D:\Web_lap_lich\issue8_patch\schedule_issue8_patch\tools\apply_issue8.py D:\Web_lap_lich\Schedule-Manager
```

   Nếu `py` không nhận: thay bằng `python`. Script từ chối sửa nhánh gốc khi nhận diện `.git`. Nếu mã nhóm đã thay các anchor hoặc có migration V6 của người khác, script **dừng thay vì ghi đè**. Không gỡ file cũ hoặc sửa thủ công migration đã chạy.
4. Kiểm tra `git diff --check`, `git diff` và `git status`.
5. SQL Server Express còn chạy trên `localhost:1433`, `.env` đặt tại root và chứa `DB_PASSWORD`, `JWT_SECRET` từ máy bạn. Không commit `.env`.
6. Terminal 1:

```powershell
mvn test
mvn spring-boot:run
```

   Backend chạy `8081`, Flyway phải ghi thành công **V6**; nếu có lỗi SQL trước đó, sao lưu database trước khi xử lý.
7. Terminal 2:

```powershell
python -m http.server 8000 --bind 127.0.0.1
```

   Mở `http://127.0.0.1:8000/attendance.html`; hoặc đăng nhập demo ở `/`, vào mục **Chấm công SQL & Báo cáo** trong sidebar. Login SQL admin dev `admin / Admin@123`, không dùng password SQL login hoặc `admin123` demo.

## 4. REST API và các quy tắc

| Method | Path | Quyền | Kết quả |
|---|---|---|---|
| POST | `/api/auth/login` | Public | JWT token của backend |
| GET | `/api/attendance/me` | JWT | Thông tin actor từ SQL |
| GET | `/api/attendance/shifts` | JWT | Ca đã phân cho chính actor và `published` |
| POST | `/api/attendance/check-in` | NV | `{ "shiftId": "..." }`, server time |
| POST | `/api/attendance/{id}/check-out` | NV sở hữu | `{ "breakMinutes": 30 }` |
| GET | `/api/attendance/records?periodId=...` | NV mình/Admin | Công cá nhân + muộn, tăng ca, đêm |
| POST | `/api/attendance/requests` | NV | Đề xuất giờ mới + lý do; không sửa công ngay |
| GET | `/api/attendance/requests?status=pending` | NV mình/Admin | Hàng đợi xử lý |
| POST | `/api/attendance/requests/{id}/review` | Admin khác requester | `{ "approve": true, "reason": "..." }` |
| GET | `/api/attendance/pending-approval` | Admin | Công hoàn tất chưa duyệt |
| POST | `/api/attendance/{id}/approve` | Admin không phải owner | Duyệt công check-out có lý do |
| POST | `/api/attendance/periods/{id}/lock` | Admin | Khóa kỳ, ngăn sửa/check-in/out trực tiếp |
| GET | `/api/attendance/audit` | NV mình/Admin | Bản trước/sau, actor, lý do, timestamp |
| GET | `/api/reports/periods` | JWT | Các kỳ hiện có ở SQL |
| GET | `/api/reports/dashboard?periodId=...` | NV mình/Admin | KPI dùng cùng nguồn giờ SQL |
| GET | `/api/reports/users?periodId=...` | NV mình/Admin | Bảng giờ theo nhân viên |
| GET | `/api/reports/attendance-status?periodId=...` | NV mình/Admin | Đi muộn, vắng, thiếu checkout, tăng ca |
| GET | `/api/reports/shortages?periodId=...` | Admin | Ca công bố chưa đủ nhân viên |
| GET | `/api/reports/export?periodId=...&format=csv` | NV mình/Admin | CSV UTF-8 BOM |
| GET | `/api/reports/export?periodId=...&format=xlsx` | NV mình/Admin | Excel .xlsx |

**Quy tắc:** check-in chỉ trong [bắt đầu ca − 30 phút, kết thúc ca), phải có `assignment` confirmed + `shift` published; mỗi NV tối đa 1 lượt đang mở, 1 bản ghi cho một ca. Check-out server time không quá 24h, nghỉ không được âm hoặc >= thời lượng; điều chỉnh được duyệt atomic và có `before_json`/`after_json`. Từ chối không sửa bản công gốc. Admin không thể tự duyệt chính mình. Khóa kỳ ngăn check-in/out mới, nhưng điều chỉnh cần phê duyệt vẫn có thể xử lý (có audit).

**Ca đêm:** ca có end <= start đi qua ngày hôm sau; timezone công khai `Asia/Ho_Chi_Minh`. `nightMinutesBeforeBreak` là số phút giao với 22:00–06:00, **chưa trừ các khoảng nghỉ không xác định vị trí**; không khẳng định đó là số giờ đêm được trả lương. Tăng ca tính phần giờ thực tế (sau tổng phút nghỉ) vượt tổng thời lượng ca được xếp. Chính sách trả lương/tăng ca riêng chưa có bảng hoặc phê duyệt payroll.

**Công thức thống nhất:**

- `target = standard_hours × target_rate / 100`.
- `scheduled = tổng thời lượng assignment confirmed trên shifts published`; không gộp draft.
- `actual = tổng (out − in − break) của attendance completed/approved`.
- `approved = chỉ giờ công status approved`.
- `difference = scheduled − target`.
- `percentage = ROUND(scheduled / target × 100)`; target <= 0 thì null và nhãn **Chưa đủ dữ liệu**.
- 808/740 -> 109%; chênh +68 giờ.

## 5. Kiểm thử API với fixture (CHỈ TRÊN DATABASE QA LOCAL)

**Không áp dụng fixture lên production:** fixture tạo 2 nhân viên thử nghiệm dùng chung mật khẩu demo `Admin@123` và một ca được phân hôm nay. Thực hiện trong SSMS trên máy local database `ScheduleManager` sau khi V6 thành công:

1. Mở `tests/attendance-fixture.sql`, chạy bằng `Execute` trong SSMS.
2. Kiểm tra backend JWT login `smqa001 / Admin@123` và `smqa002 / Admin@123` (dev QA only).
3. Chạy read-only smoke:

```powershell
powershell -ExecutionPolicy Bypass -File tests\attendance-api.ps1 -Username admin -Password 'Admin@123'
```

4. Chạy các kịch bản có thay đổi dữ liệu (trên QA clone):
   - Employee1 check-in ca `qa_shift_today`; lặp POST ngay lập tức => `409`.
   - Employee2 check-in song song; hệ thống cho phép vì hai user khác nhau, nhưng không tạo 2 lượt cho *cùng user*.
   - Employee1 check-out sau khi thời gian thực tế > 0; thử checkout lần 2 => `409`.
   - Employee1 gửi sửa giờ hợp lệ hoặc bổ sung nếu chưa có attendance; admin xem `pending` và duyệt; có `before_json/after_json` trong lịch sử, status `approved`.
   - Nếu request đã duyệt, thử duyệt lần 2 hoặc 2 phiên admin xử lý đồng thời => một thành công và một `409`.
   - Employee2 gọi GET records userId của Employee1 => `403`; admin có quyền xem.
   - Employee2 gọi review hoặc lock => `403`; không token => `401`.
   - Admin duyệt trực tiếp completed punch khác owner, `approvedHours` tăng, `actualHours` không bị tính thành `scheduledHours`.
   - Admin khóa `qa_attendance_period`; employee check-in/out trực tiếp => `409`, còn yêu cầu sửa chờ duyệt vẫn được phép.
   - Gọi dashboard, userHours và CSV/XLSX với cùng `periodId`, xác nhận cùng số giờ; không có ca draft trong `scheduledHours`.
   - Thử API lỗi, cơ sở dữ liệu rỗng, API mất kết nối, đăng nhập lại, load trang mới và profile trình duyệt khác.

**Lưu ý kiểm thử:** Dữ liệu check-in/out dùng **đồng hồ server**, không thể làm cho 3 giờ trôi ngay. Đối với kiểm thử E2E toàn bộ chu trình, dùng SQL Server QA riêng hoặc test thời gian có kiểm soát/mocks; không thay đổi đồng hồ hệ điều hành máy thật.

## 6. Responsive, keyboard, scale và E2E

- Mở `http://127.0.0.1:8000/tests/attendance-responsive.html` → chạy kiểm tra viewport 320,360,390,768,1024,1280.
- Mở thêm `tests/browser-smoke.html` có sẵn để chạy lại regression của SPA (login/navigation/registration/calendar/modal). Phần này chưa sửa trực tiếp vì thuộc module khác.
- Test thủ công Windows Settings > System > Display > Scale = 125% hoặc 150% (tùy máy), browser zoom 100% và 200%; ghi bằng chứng ảnh ở `Docs/issue8-ac-evidence.md`.
- Tab qua login/form/button, nhập sai, xác nhận focus ring rõ, kiểm tra bảng cuộn ngang **trong** vùng bảng, không tràn trang.
- Kiểm tra màn nhỏ modal/hàng đợi và text dài. Modal gốc không thay đổi, cần chạy smoke gốc.

## 7. Kiểm soát xung đột / ghép nhóm

Nếu nhóm đã có V6, **không chỉnh V6 đã chạy trong database**; cần rebase/fetch và phối hợp đổi tên migration tiếp theo với nội dung merge đúng. Không dùng `git push --force`. Trong giai đoạn giao diện còn demo `localStorage`, mọi KPI về nhân sự/kỳ/ca trong portal SQL sẽ không bằng dashboard demo trừ khi dữ liệu backend được nạp tương ứng. Đó là giới hạn liên module, không phải lỗi công thức.

## 8. Push nhánh mới / Pull Request

Tại repository sau khi `mvn test` + API smoke + regression hoàn thành:

```powershell
git status
git diff --check
git add js/app.js js/bootstrap.js js/attendance-entry.js js/attendance-portal.js css/attendance.css attendance.html src/main/java src/main/resources/db/migration/V6__attendance_reports.sql src/test/java tests/attendance-* Docs/issue8-*
git status
git diff --cached --stat
git commit -m "feat(issue-8): attendance approval reports and QA evidence"
git push -u origin feature/issue-8-attendance-reports
```

Đảm bảo không xuất hiện `.env`, `.issue8-backups` hoặc QA test data chứa thông tin thật trong `git status --short`. Tạo PR trên GitHub với base `docs/task-v1.1` hoặc base chung do nhóm thống nhất; mô tả scope, migration V6, test và những chỗ cần hợp nhất dữ liệu lịch thực tế.
