# Kiểm thử

> **Mục đích:** mô tả các bộ kiểm thử hiện có (backend và frontend), cách chạy, phạm vi bao phủ, kịch bản kiểm thử thủ công và các hạn chế đã xác định từ mã nguồn.

---

## 1. Tổng quan

| Loại | Công cụ | Vị trí | Số kịch bản | Cách chạy |
|---|---|---|---|---|
| Unit/slice test backend | JUnit 5, Spring Boot Test (`@WebMvcTest`, MockMvc), Mockito, Spring Security Test, H2 | `src/test/java/**` | 25 phương thức `@Test` trong 5 lớp | `mvn test` |
| Smoke test frontend | JavaScript thuần chạy trong trình duyệt | `tests/browser-smoke.html`, `tests/browser-smoke.js` | 11 kịch bản | Mở trang qua HTTP và bấm **Chạy kiểm thử** |
| Kiểm tra cú pháp JS | `node --check` | `js/*.js`, `tests/*.js` | — | Lệnh PowerShell ở mục 4 |
| Kiểm thử thủ công | — | Mục 5 | 12 nhóm | Theo danh sách |

---

## 2. Kiểm thử backend

### 2.1. Cách chạy

```powershell
mvn test
```

- `maven-surefire-plugin` đặt `spring.profiles.active=test` → nạp `src/test/resources/application-test.properties`.
- Profile `test`: datasource H2 in-memory (`jdbc:h2:mem:testdb;MODE=MSSQLServer`), **tắt Flyway**, tắt nhập `.env`, `app.jwt.secret` dành cho test, `app.jwt.expiration-ms=3600000`.
- Không cần SQL Server hay tệp `.env`.
- Yêu cầu: JDK 21 và Apache Maven có trong `PATH` (kiểm tra bằng `java -version`, `mvn -v`).

### 2.2. Kết quả chạy gần nhất

**Môi trường:** Windows 11, Apache Maven 3.10.0, Microsoft OpenJDK 21.0.12.1, ngày 16/10/2026.

**Lệnh:** `mvn -B -ntp test`

| Lớp test | Tests run | Failures | Errors | Skipped | Thời gian |
|---|---|---|---|---|---|
| `AuthControllerTest` | 7 | 0 | 0 | 0 | 9,959 s |
| `AuthServiceTest` | 6 | 0 | 0 | 0 | 1,308 s |
| `HealthControllerTest` | 1 | 0 | 0 | 0 | 1,007 s |
| `LevelControllerTest` | 9 | 0 | 0 | 0 | 1,206 s |
| `BcryptSeedVerificationTest` | 2 | 0 | 0 | 0 | 0,229 s |
| **Tổng** | **25** | **0** | **0** | **0** | — |

```
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Ghi chú: log có cảnh báo *"Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK…"*. Đây là cảnh báo về cơ chế nạp agent của Mockito trên các JDK tương lai, không làm test thất bại.

### 2.3. Danh sách test

#### `AuthServiceTest` – unit test thuần, không có Spring context (6 test)

| Test | Kiểm tra |
|---|---|
| `login_withValidCredentials_returnsJwt` | Đúng thông tin → trả token có 3 phần `header.payload.signature` |
| `login_withWrongPassword_returnsEmpty` | Sai mật khẩu → rỗng |
| `login_withInactiveUser_returnsEmpty` | User `inactive` → rỗng |
| `login_withUnknownUsername_returnsEmpty` | User không tồn tại → rỗng |
| `login_wrongPassword_andInactiveUser_returnSameEmptyOptional` | Hai kiểu lỗi trả cùng kết quả (chống dò tài khoản) |
| `login_withV3SeedHashAndDocumentedDevPassword_succeeds` | Hash trong migration V3/V4 khớp mật khẩu dev `Admin@123` |

#### `AuthControllerTest` – `@WebMvcTest(AuthController.class)` + `SecurityConfig`, `AuthService` được mock (7 test)

| Test | Kiểm tra |
|---|---|
| `login_isPublic_noTokenRequired` | `POST /api/auth/login` không cần token → 200 |
| `login_withValidCredentials_returns200WithToken` | 200, `$.token`, `$.tokenType = "Bearer"` |
| `login_withWrongPassword_returns401WithGenericError` | 401, `$.error = "Invalid credentials"` |
| `login_withInactiveUser_returns401WithGenericError` | 401, cùng thông báo |
| `login_withUnknownUsername_returns401WithGenericError` | 401, cùng thông báo |
| `logout_isPublic_noTokenRequired` | `POST /api/auth/logout` → 200 |
| `logout_alwaysReturns200WithClientSideMessage` | `$.message = "Logged out. Please discard your token."` |

#### `HealthControllerTest` (1 test)

| Test | Kiểm tra |
|---|---|
| `healthEndpoint_isPublic_noTokenRequired` | `GET /api/health` → 200, `status = UP`, `service = schedule-manager-api` |

#### `LevelControllerTest` – profile `sqlserver` + `test`, `LevelRepository` được mock (9 test)

| Test | Kiểm tra |
|---|---|
| `getLevels_withoutToken_returns401` | Không token → 401 |
| `getLevels_withValidJwt_returns200` | JWT role `user` → 200, 4 phần tử |
| `getLevels_withAdminJwt_returns200` | JWT role `admin` → 200, 4 phần tử |
| `getLevels_withMalformedToken_returns401` | Token sai định dạng → 401 |
| `getLevels_withTamperedSignature_returns401` | Sửa ký tự cuối chữ ký → 401 |
| `getLevels_withExpiredToken_returns401` | Token thời hạn 0 ms → 401 |
| `getLevels_returnsListWithCorrectFields` | Đúng `id`, `name`, `description`, `color`, `order` |
| `getLevels_isSortedByOrder` | `order` lần lượt 1, 2, 3, 4 |
| `getLevels_whenEmpty_returnsEmptyArray` | Repository rỗng → `[]` |

#### `BcryptSeedVerificationTest` (2 test)

| Test | Kiểm tra |
|---|---|
| `v3_adminHash_matchesDocumentedDevPassword` | Hash seed khớp `Admin@123` |
| `v3_adminHash_doesNotMatchWrongPassword` | Hash seed không khớp `WrongPassword` |

Khi đổi hash trong migration, phải cập nhật hằng `V3_ADMIN_HASH` trong test này (chú thích trong mã).

### 2.4. Phạm vi chưa được kiểm thử tự động ở backend

- `UserRepository`, `LevelRepository` với CSDL thật (repository được mock; Flyway tắt trong test).
- `DatabaseHealthController` (`/api/health/database`).
- Chạy migration Flyway trên SQL Server.

---

## 3. Smoke test frontend

### 3.1. Cách chạy

1. Tại thư mục gốc, chạy `py -m http.server 8000` (hoặc `python -m http.server 8000`).
2. Dùng một **browser profile riêng cho kiểm thử**, mở `http://localhost:8000/tests/browser-smoke.html`.
3. Bấm **Chạy kiểm thử**; trang liệt kê từng kịch bản "Đạt: …" hoặc "Lỗi: … — <lý do>" và tổng kết "Hoàn tất: X đạt, Y lỗi."

### 3.2. Cơ chế

- Trước khi chạy: sao lưu toàn bộ khóa `localStorage` có tiền tố `pls_`, xóa chúng để ứng dụng khởi tạo dữ liệu demo sạch.
- Ứng dụng được nạp trong `<iframe id="app-under-test">` (`../index.html?smoke=<timestamp>`); test chờ `App`, `State` sẵn sàng và màn hình tải đã ẩn.
- Sau khi chạy (kể cả khi lỗi): xóa các khóa `pls_` do test tạo, khôi phục dữ liệu đã sao lưu và tải lại iframe.
- Không đóng/tải lại tab runner khi đang chạy để quá trình khôi phục dữ liệu không bị gián đoạn.

### 3.3. Danh sách kịch bản

| # | Kịch bản | Nội dung kiểm tra chính |
|---|---|---|
| 1 | Đăng nhập và báo lỗi biểu mẫu | Submit rỗng → `aria-invalid="true"` và `aria-describedby="login-username-err"`; đăng nhập nhanh Admin; route mặc định `dashboard` |
| 2 | Route Admin, bộ lọc và lỗi form có liên kết trợ năng | Nút thu gọn sidebar cập nhật `aria-expanded`/`aria-label`; ô tìm nhân viên có tên trợ năng; lỗi form nhân viên gắn với input; nền `aria-hidden` khi mở modal; mọi control có nhãn |
| 3 | Nhãn và liên kết lỗi trên form cấu hình Admin | Form Level, Kỳ đăng ký, Ca, Phân công thủ công, Quy tắc: có nhãn, lỗi gắn đúng input |
| 4 | Focus trap, Esc và khôi phục focus của modal | `Shift+Tab` giữ focus trong modal; `Esc` đóng; focus về `#main-content` |
| 5 | Hộp xác nhận đóng bằng Esc và trả focus | Focus ban đầu ở nút Hủy; `Esc` đóng; focus trả lại |
| 6 | Responsive 320/375/768/1024/1440 px | Không tràn ngang; bảng rộng nhận focus bàn phím ở 320 px |
| 7 | Menu mobile và menu tài khoản dùng được bằng bàn phím | Mở/đóng bằng click và `Esc`, focus đúng; hủy hộp xác nhận đăng xuất trả focus về nút |
| 8 | Chuyển vai trò và kiểm tra route | `App.switchDemoUser()` giữa Admin và nhân viên; sidebar đổi đúng bộ route; route mặc định `overview` |
| 9 | Ngày trên lịch và thẻ ca dùng được bằng bàn phím | Ô ngày (`.cal-day`) và thẻ ca tuần (`.week-shift-card`) là `<button>`; lịch tuần Admin và nhân viên có `tabindex="0"`, nhãn "Lịch tuần có thể cuộn ngang", không tràn trang ở 320 px |
| 10 | Form input có label liên kết và `aria-describedby` lỗi | Các input Mã NV, Họ tên, Tên đăng nhập, Level có `label[for]`; submit rỗng → `uf-code` có `aria-invalid="true"` và trỏ tới `uf-code-err` |
| 11 | Nhân viên khai báo thời gian rảnh và gửi đăng ký | Form đổi mật khẩu báo lỗi đúng; `nv003` thêm khung giờ 08:00–16:00 vào ngày đầu kỳ đang mở, gửi đăng ký → trạng thái `submitted` |

Smoke test kiểm tra theo **độ rộng viewport**, không mô phỏng trực tiếp mức zoom của trình duyệt.

### 3.4. Kết quả chạy gần nhất

**Môi trường:** Google Chrome trên Windows 11, máy chủ `py -m http.server 8000`, ngày 17/10/2026; `window.devicePixelRatio = 0,833` (trình duyệt/màn hình không ở tỷ lệ 100%).

**Tổng kết trên trang runner:** `Hoàn tất: 10 đạt, 1 lỗi.`

| # | Kịch bản | Kết quả |
|---|---|---|
| 1 | Đăng nhập và báo lỗi biểu mẫu | Đạt |
| 2 | Route Admin, bộ lọc và lỗi form có liên kết trợ năng | Đạt |
| 3 | Nhãn và liên kết lỗi trên form cấu hình Admin | Đạt |
| 4 | Focus trap, Esc và khôi phục focus của modal | Đạt |
| 5 | Hộp xác nhận đóng bằng Esc và trả focus | Đạt |
| 6 | Responsive 320, 375, 768, 1024 và 1440 px; bảng cuộn được bằng bàn phím | **Lỗi** – "Viewport 320px không được áp dụng." |
| 7 | Menu mobile và menu tài khoản dùng được bằng bàn phím | Đạt |
| 8 | Chuyển vai trò qua role switcher và kiểm tra route | Đạt |
| 9 | Cal-day và week-shift-card dùng được bằng bàn phím | Đạt |
| 10 | Form input có label liên kết và aria-describedby lỗi | Đạt |
| 11 | Nhân viên khai báo thời gian rảnh và gửi đăng ký | Đạt |

**Phân tích lỗi kịch bản 6** (đo trực tiếp trên trang runner):

- Test đặt `frame.style.width = (width + 1) + 'px'` rồi yêu cầu `iframe.contentWindow.innerWidth === width`.
- `css/styles.css` áp `box-sizing: border-box` cho mọi phần tử, và iframe có viền `1px` mỗi bên, nên bề rộng nội dung = bề rộng đặt − 2 × viền. Ở tỷ lệ 100% kết quả là `321 − 2 = 319 ≠ 320`.
- Số đo thực tế: đặt 321 px → `innerWidth = 319`; 376 → 373; 769 → 766; 1025 → 1022; 1441 → 1438 (viền hiển thị 1,2 px do tỷ lệ 0,833).
- Khi đo lại ở cả 5 độ rộng, `document.documentElement.scrollWidth` **không vượt** `innerWidth` (không tràn ngang).

Kết luận: lỗi nằm ở **điều kiện kiểm tra độ rộng chính xác của test**, không phải giao diện bị tràn. Lần đo lại này chưa kiểm được ý "bảng nhận focus bằng bàn phím ở 320 px", vì sau khi runner khôi phục dữ liệu, ứng dụng trong iframe đang ở màn hình đăng nhập (không có bảng).

### 3.5. Phạm vi chưa được smoke test bao phủ

Thuật toán phân lịch (`Scheduler.run`), công bố lịch, phân công thủ công, các phép tính giờ mục tiêu/giờ đã phân, quy tắc thay thế Level.

---

## 4. Kiểm tra cú pháp JavaScript

Cần Node.js. Mở PowerShell tại thư mục gốc:

```powershell
Get-ChildItem js, tests -Filter *.js | ForEach-Object {
  node --check $_.FullName
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

Lệnh chỉ kiểm tra cú pháp, không chạy mã (các tệp phụ thuộc DOM của trình duyệt).

---

## 5. Kịch bản kiểm thử thủ công

Chạy frontend theo [setup.md](./setup.md). Nên bắt đầu với dữ liệu demo sạch (xóa khóa `pls_*` rồi tải lại trang).

| # | Nhóm | Bước thực hiện | Kết quả mong đợi | Liên quan |
|---|---|---|---|---|
| 1 | Đăng nhập | Bấm nút demo Admin; đăng xuất; đăng nhập `nv001/user123` | Vào đúng màn hình theo vai trò; đăng xuất có hộp xác nhận | FR-01 |
| 2 | Đăng nhập lỗi | Nhập sai mật khẩu; đăng nhập `nv009/user123` | "Tên đăng nhập hoặc mật khẩu không đúng."; "Tài khoản đã bị khóa. Vui lòng liên hệ Admin." | FR-01 |
| 3 | Phiên | Đăng nhập rồi tải lại trang | Vẫn đăng nhập, về route mặc định của vai trò | — |
| 4 | Nhân sự | Thêm nhân viên trùng mã/tên đăng nhập; khóa rồi mở khóa một nhân viên | Báo "Mã nhân viên đã tồn tại." / "Tên đăng nhập đã tồn tại."; trạng thái đổi; sau khi tải lại trang, Lịch sử có bản ghi `user_status` | FR-02 |
| 5 | Level | Thêm Level trùng tên; thử xóa Level đang có nhân viên | Báo "Tên Level đã tồn tại."; nút Xóa bị vô hiệu | FR-02 |
| 6 | Kỳ đăng ký | Tạo kỳ có hạn chót trước thời điểm mở; mở rồi khóa kỳ | Báo "Hạn chót phải sau thời điểm mở đăng ký."; trạng thái đổi đúng | FR-03 |
| 7 | Đăng ký rảnh | Đăng nhập `nv003`, chọn "Cả ngày" một ngày, "Chọn giờ" với hai khung giờ một ngày khác, gửi đăng ký | Ô ngày hiển thị khung giờ; sau khi gửi trạng thái "Đã gửi đăng ký" | FR-03 |
| 8 | Ca làm việc | Tạo ca chế độ Level với tổng Level ≠ tổng nhu cầu | Báo "Tổng Level (x) phải bằng tổng nhu cầu (y)." | FR-05, FR-06 |
| 9 | Phân lịch thử | Phân lịch tự động → chọn kỳ → **Chạy thử** | Hiện thống kê, ca thiếu, nhân viên chưa đủ giờ, lý do loại, nhật ký; không có phân công mới trong Quản lý ca | FR-10 → FR-14 |
| 10 | Áp dụng | Bấm **Áp dụng kết quả** | Toast "Đã áp dụng N phân công."; ca liên quan chuyển "Nháp"; sau khi tải lại trang, Lịch sử có `schedule_run` | FR-10, FR-18 |
| 11 | Phân công thủ công | Chi tiết ca → Phân công thủ công: chọn nhân viên đã có ca trùng giờ; chọn nhân viên không đăng ký rảnh | Lỗi "Xung đột lịch…" chặn phân công; trường hợp không rảnh vẫn phân công kèm cảnh báo `[Admin override]` | FR-15 |
| 12 | Công bố | Duyệt & công bố: chọn một ca thiếu người, bấm **Công bố tất cả**; sau đó **Công bố dù thiếu người** | Lần 1 báo lỗi; lần 2 công bố thành công; nhân viên thấy ca trong "Lịch làm việc của tôi" | FR-16, FR-17 |

Bổ sung về giao diện: kiểm tra `Esc` đóng lớp trên cùng (hộp xác nhận → modal → menu), tối đa 5 toast cùng lúc, điều hướng chỉ bằng bàn phím, hiển thị ở zoom 200%.

---

## 6. Hạn chế đã xác định khi đọc mã nguồn

Các điểm dưới đây nên được đưa vào kế hoạch kiểm thử/sửa lỗi. Mỗi mục ghi rõ vị trí mã.

| # | Mô tả | Vị trí |
|---|---|---|
| 1 | Trong một lần chạy phân lịch, kiểm tra trùng ca/giới hạn giờ chỉ dựa trên phân công đã lưu, chưa tính các phân công vừa tạo ở ca trước trong cùng lần chạy | `SafetyCheck.validateAssignment()` đọc `State.get.assignments()`; `Scheduler.run()` chỉ lưu khi kết thúc |
| 2 | Số "Đã phân" của ca thiếu chỉ đếm phân công mới; chạy lại cho ca đã đủ người có thể báo thiếu | `Scheduler.run()` – `totalFilled = filled.length` |
| 3 | `maxHoursPerPeriod`, `schedulingPriority`, `understaffedPolicy` không được sử dụng | `js/validation.js`, `js/scheduler.js` |
| 4 | Sửa thông tin nhân viên luôn đặt `status = 'active'` (tài khoản đang khóa sẽ được mở lại khi lưu form) | `Admin.submitUserForm()` |
| 5 | Ô chọn kỳ trên Tổng quan Admin không đổi dữ liệu hiển thị (`switchDashPeriod` bỏ qua tham số) | `Admin.switchDashPeriod()` |
| 6 | Form kỳ đăng ký hiển thị `openAt`/`deadline` bằng `toISOString()` (giờ UTC) nhưng khi lưu lại hiểu là giờ địa phương → giờ có thể bị lệch sau mỗi lần sửa | `AdminPeriod.openForm()`, `submitPeriodForm()` |
| 7 | Lọc lịch sử: khi kết quả lọc rỗng, danh sách cũ không được xóa | `AdminHistory.filterHist()` |
| 8 | Sau khi gửi đăng ký, nhân viên vẫn sửa được khung giờ khi kỳ còn mở (trạng thái giữ `submitted`), dù hộp xác nhận ghi "không thể chỉnh sửa trừ khi Admin mở lại" | `UserView._updateFreeTimeEntry()`, `renderRegister()` |
| 9 | Không kiểm tra giờ bắt đầu < giờ kết thúc khi nhân viên nhập khung giờ rảnh | `UserView.saveTimeSlot()` |
| 10 | Mục "Thông tin cá nhân" trong menu tài khoản của Admin chuyển về Tổng quan vì `ADMIN_ROUTES` không có route `profile` | `js/app.js`, `partials/app-shell.html` |
| 11 | Dữ liệu phân công mẫu không đi qua `SafetyCheck` | `_pickAssignees()` trong `js/storage.js` |
| 12 | Quy tắc thay thế Level không kiểm tra Level bên trái cao hơn Level bên phải | `AdminRules.updateSubRule()`, `save()` |
| 13 | Bản ghi lịch sử mới không hiển thị trên Tổng quan/Lịch sử cho đến khi tải lại trang, vì `logHistory()` ghi vào `localStorage` còn giao diện đọc `State.get.history()` (chỉ nạp khi `State.load()`) | `logHistory()` trong `js/storage.js`; `State.load()` chỉ được gọi trong `App.init()` và `App.resetDemo()` |
| 14 | Khi kỳ đã khóa nhưng bật "Cho phép sửa sau hạn", nhân viên sửa được khung giờ nhưng bấm **Gửi đăng ký** sẽ nhận "Kỳ đăng ký chưa mở hoặc đã khóa." | `UserView.submitRegistration()` chỉ tìm kỳ `open` |
