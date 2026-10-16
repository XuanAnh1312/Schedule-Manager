# Yêu cầu phần mềm và mức độ đáp ứng

> **Mục đích:** tóm tắt các yêu cầu trong tài liệu *ĐẶC TẢ YÊU CẦU PHẦN MỀM – Hệ thống phân lịch làm việc tự động (phiên bản 1.0)* và đối chiếu từng yêu cầu với mã nguồn hiện có trong repository.
>
> **Nguồn đối chiếu:** `js/*.js`, `partials/*.html` (frontend) và `src/main/**` (backend). Mọi nhận định "đã có / một phần / chưa có" trong tài liệu này đều dựa trên mã nguồn, không dựa trên kế hoạch.

---

## 1. Tổng quan hệ thống

### 1.1. Mục đích (theo đặc tả)

Hỗ trợ Admin phân công lịch làm việc cho nhân sự dựa trên **thời gian rảnh**, **cấp độ năng lực (Level)**, **nhu cầu nhân sự của từng ca** và **tỷ lệ giờ làm mục tiêu**. Hệ thống tự động tìm phương án phân bổ, hạn chế trùng lịch và phân bổ giờ làm công bằng theo mục tiêu.

### 1.2. Mục tiêu (theo đặc tả)

1. Tự động hóa quá trình phân lịch làm việc.
2. Hạn chế trùng lịch và phân công sai cấp độ.
3. Phân bổ tổng số giờ làm trong tháng theo mục tiêu của từng nhân sự.
4. Đáp ứng nhu cầu nhân sự và cơ cấu Level của từng ca.
5. Hỗ trợ Admin xử lý những trường hợp không thể tự động phân lịch.

### 1.3. Hiện trạng triển khai

Mã nguồn gồm **hai phần chạy độc lập**:

| Thành phần | Công nghệ | Phạm vi chức năng hiện có | Lưu trữ |
|---|---|---|---|
| Frontend (SPA) | HTML, CSS, JavaScript thuần | Toàn bộ nghiệp vụ: đăng nhập, nhân sự, Level, kỳ đăng ký, thời gian rảnh, ca, quy tắc, phân lịch tự động, công bố, lịch sử, báo cáo | `localStorage` của trình duyệt |
| Backend (REST API) | Java 21, Spring Boot 3.5.6, Spring Security, JWT, JDBC, Flyway | Đăng nhập (JWT), đăng xuất, kiểm tra sức khỏe dịch vụ/CSDL, đọc danh sách Level | SQL Server |

Frontend **chưa gọi API backend** (trong `js/` chỉ có một lời gọi `fetch` để tải các tệp `partials/*.html` tại `js/bootstrap.js`). Hai phần dùng hai bộ tài khoản demo khác nhau (xem [setup.md](./setup.md)).

---

## 2. Đối tượng sử dụng

| Vai trò (đặc tả) | Giá trị `role` trong mã | Màn hình được truy cập (frontend) |
|---|---|---|
| **Admin** – quản lý hệ thống, cấu hình, phân lịch, phê duyệt | `admin` | 9 route trong `ADMIN_ROUTES` (`js/app.js`): Tổng quan, Quản lý nhân sự, Cấu hình đăng ký, Lịch làm việc, Quản lý ca, Quy tắc phân lịch, Phân lịch tự động, Duyệt & công bố, Lịch sử & báo cáo |
| **User** – nhân viên đăng ký thời gian rảnh và xem lịch | `user` | 4 route trong `USER_ROUTES` (`js/app.js`): Tổng quan, Đăng ký thời gian rảnh, Lịch làm việc của tôi, Thông tin cá nhân |

Ràng buộc vai trò trong CSDL: `CONSTRAINT ck_users_role CHECK (role IN ('admin', 'user'))` (`V1__create_levels_and_users.sql`).

---

## 3. Yêu cầu chức năng – bảng truy vết

Quy ước cột **Mức độ**: ✅ Đã có · ⚠️ Một phần · ❌ Chưa có.

### 3.1. Quản lý tài khoản

| Mã | Yêu cầu (tóm tắt đặc tả) | Mức độ | Hiện thực trong mã nguồn |
|---|---|---|---|
| FR-01 | Đăng nhập bằng tài khoản hợp lệ; xác định vai trò và cấp quyền; tài khoản không hợp lệ không được truy cập | ✅ | **Frontend:** `App._doLogin()` so khớp `username` + `password` với danh sách `pls_users`; báo "Tên đăng nhập hoặc mật khẩu không đúng." hoặc "Tài khoản đã bị khóa. Vui lòng liên hệ Admin." nếu `status !== 'active'`; điều hướng theo `role` (`admin` → `dashboard`, `user` → `overview`). **Backend:** `POST /api/auth/login` (`AuthService.login`) chỉ cấp JWT khi người dùng tồn tại, `status = 'active'` và mật khẩu khớp BCrypt; mọi trường hợp lỗi trả cùng thông báo `Invalid credentials`. |
| FR-02 | Admin xem danh sách, thêm, sửa, khóa, cập nhật nhân sự; thiết lập Level; mỗi User một Level hiện hành | ✅ | `Admin.renderUsers()` (danh sách, tìm theo tên/mã, lọc Level/trạng thái), `Admin.openUserForm()` / `submitUserForm()` (thêm/sửa), `Admin.toggleUserStatus()` (khóa/mở khóa), `Admin.viewUserDetail()`. Mỗi user có một trường `levelId`. Quản lý danh mục Level: `Admin.openLevelManager()` (thêm, sửa; chỉ xóa được Level không còn nhân viên). Giao diện không có chức năng xóa nhân viên. |

### 3.2. Đăng ký thời gian rảnh

| Mã | Yêu cầu | Mức độ | Hiện thực trong mã nguồn |
|---|---|---|---|
| FR-03 | User đăng ký ngày và khung giờ có thể làm trong tuần; cập nhật trước thời hạn chốt; lưu theo User, ngày, khoảng thời gian; có trạng thái đã lưu/chưa | ⚠️ | `UserView.renderRegister()` hiển thị kỳ đang mở theo **từng tuần** (nút "Tuần 1, Tuần 2…"); mỗi ngày có thể chọn "Cả ngày" (`00:00–23:59`), "Chọn giờ" (nhiều khung giờ) hoặc "Xóa". Dữ liệu lưu dạng `{ userId, periodId, status, entries: [{ date, slots: [{ start, end }] }] }`. Trạng thái: `draft` (Nháp), `submitted` (Đã gửi đăng ký), chưa có bản ghi = "Chưa đăng ký". **Hạn chế:** mã nguồn không so sánh thời điểm hiện tại với `deadline`; việc chặn chỉnh sửa chỉ dựa vào trạng thái kỳ (`locked` và không bật `allowEditAfterDeadline`). |
| FR-04 | Đối chiếu thời gian rảnh với ca; chỉ xem xét User đáp ứng thời gian; khung giờ không đăng ký không được tự động sử dụng | ✅ | `SafetyCheck.checkFreeTime()` (`js/validation.js`): yêu cầu bản đăng ký ở trạng thái `submitted`, có mục ngày trùng ngày ca, và **một khung giờ bao phủ trọn vẹn** giờ ca (hỗ trợ ca/khung giờ qua đêm). Thuật toán tự động luôn coi lỗi này là lỗi chặn. |

### 3.3. Quản lý ca làm việc

| Mã | Yêu cầu | Mức độ | Hiện thực trong mã nguồn |
|---|---|---|---|
| FR-05 | Tạo ca với ngày, giờ bắt đầu/kết thúc, tổng nhân sự, số lượng theo Level, trạng thái (chưa phân lịch, đang phân lịch, đã phân lịch, đã công bố); nhiều ca/ngày; điều chỉnh trước khi công bố | ⚠️ | `AdminShift.openForm()` / `submitShiftForm()`: tên ca, ngày, giờ bắt đầu/kết thúc (ca qua đêm khi giờ kết thúc ≤ giờ bắt đầu), tổng nhu cầu, ghi chú, chế độ cấu hình nhân sự **A – Theo Level**, **B – Theo User cụ thể**, **C – Kết hợp**. Có sao chép ca, xóa ca chưa công bố. Trạng thái thực sự được gán trong mã: `not_scheduled` (khi tạo), `draft` (sau khi áp dụng phân lịch), `published` (khi công bố). Nhãn `scheduling` ("Đang phân lịch"), `full`, `understaffed` có trong `STATUS_LABELS` nhưng không được gán làm trạng thái ca. |
| FR-06 | Xác định số lượng nhân sự cho từng ca và theo từng Level | ✅ | `Validate.shiftStaffConfig()`: chế độ Level yêu cầu ≥ 1 dòng Level, mỗi dòng có Level và số lượng > 0, **tổng các Level = tổng nhu cầu**; chế độ User yêu cầu ≥ 1 nhân viên, số bắt buộc ≤ tổng nhu cầu; chế độ Kết hợp yêu cầu (số bắt buộc + tổng vị trí Level) ≤ tổng nhu cầu; một nhân viên không được vừa bắt buộc vừa ưu tiên. |

### 3.4. Tỷ lệ giờ làm mục tiêu

| Mã | Yêu cầu | Mức độ | Hiện thực trong mã nguồn |
|---|---|---|---|
| FR-07 | Thiết lập tỷ lệ mục tiêu cho toàn bộ User thuộc một Level | ⚠️ | Màn hình **Quy tắc phân lịch** có ô tỷ lệ (%) cho từng Level (`rules.levelTargetRates`). Tuy nhiên `State.get.targetHoursForUser()` chỉ dùng tỷ lệ Level khi tỷ lệ riêng của nhân viên là giá trị "falsy" (`0`, rỗng, `undefined`); biểu mẫu nhân viên luôn có giá trị mặc định `100`, nên trên thực tế tỷ lệ Level chỉ được dùng khi tỷ lệ riêng bằng `0`. |
| FR-08 | Thiết lập tỷ lệ riêng cho một hoặc nhiều User; cấu hình cá nhân ưu tiên hơn Level | ⚠️ | Trường **Tỷ lệ giờ mục tiêu (%)** (0–200) trong form nhân viên; được ưu tiên hơn tỷ lệ Level. Chỉ thiết lập được **từng người**, chưa có thao tác chọn nhiều người cùng lúc. Không thể đặt mục tiêu 0% (giá trị 0 sẽ chuyển sang tỷ lệ Level hoặc 100%). |
| FR-09 | Admin thiết lập giờ chuẩn tương ứng 100%; `Giờ mục tiêu = Giờ chuẩn × Tỷ lệ / 100` | ✅ | `rules.standardHours` (mặc định 80, cho phép 1–744). Công thức: `Math.round(rules.standardHours * rate / 100)` – kết quả **làm tròn đến số nguyên giờ**. Giờ chuẩn là một giá trị chung cho mọi nhân viên. |

### 3.5. Phân lịch tự động

| Mã | Yêu cầu | Mức độ | Hiện thực trong mã nguồn |
|---|---|---|---|
| FR-10 | Chọn kỳ và tạo lịch tự động; tổng hợp nhân sự, Level, thời gian rảnh, ca, giờ mục tiêu, lịch đã phân, giới hạn | ✅ | `Scheduler.run(periodId, { dryRun })`: lấy nhân viên đang hoạt động, Level, các ca **chưa công bố** trong khoảng ngày của kỳ, giờ mục tiêu và giờ đã phân của từng người. `Scheduler.preRunCheck()` cảnh báo: chưa có ca, nhân viên chưa gửi đăng ký, chưa có quy tắc, ca chưa có chế độ hợp lệ. Chạy thử (`dryRun = true`) không ghi dữ liệu. |
| FR-11 | Chỉ xét User có thời gian rảnh phù hợp, không trùng ca, đúng Level hoặc Level cao được phép thay, không vượt giới hạn giờ | ⚠️ | `SafetyCheck.validateAssignment()` kiểm: trùng ca (lỗi), thời gian rảnh (lỗi), giới hạn giờ/ngày và giờ/tuần (lỗi), nghỉ tối thiểu giữa ca (**chỉ cảnh báo**). `SafetyCheck.checkLevelEligibility()` kiểm Level cho vị trí theo Level. **Hạn chế:** (1) `maxHoursPerPeriod` có trong dữ liệu nhưng không được kiểm tra; (2) bộ kiểm tra đọc phân công đã lưu trong `State`, các phân công mới tạo trong cùng một lần chạy chưa được đưa vào khi kiểm tra trùng ca/giới hạn giờ cho ca tiếp theo; (3) vị trí "bắt buộc"/"ưu tiên" chỉ định đích danh không kiểm Level. |
| FR-12 | Ưu tiên User còn thiếu nhiều giờ so với mục tiêu; người đã đạt mục tiêu bị giảm ưu tiên nhưng vẫn có thể được phân | ✅ | `_findBestCandidate()` sắp xếp ứng viên hợp lệ theo `(giờ mục tiêu − giờ đã phân)` giảm dần và chọn người đứng đầu; người đã vượt mục tiêu có hiệu số âm nên xếp sau nhưng vẫn được chọn nếu không còn ai khác. Tùy chọn `schedulingPriority` trên màn hình Quy tắc được lưu nhưng thuật toán không đọc giá trị này. |

### 3.6. Quy tắc Level

| Mã | Yêu cầu | Mức độ | Hiện thực trong mã nguồn |
|---|---|---|---|
| FR-13 | Level cao có thể đảm nhận vị trí Level thấp; Level thấp không tự động đảm nhận vị trí Level cao; tuân thủ cơ cấu Level | ✅ | Danh sách `rules.levelSubstitutions` (`{ higherLevelId, lowerLevelId }`); dữ liệu mặc định cho phép Level 4→3,2,1; Level 3→2,1; Level 2→1. `checkLevelEligibility()` chỉ chấp nhận Level trùng khớp hoặc có quy tắc thay thế tương ứng. **Lưu ý:** giao diện cho phép chọn bất kỳ cặp Level nào ở hai ô "có thể thay"; mã nguồn không kiểm tra Level bên trái phải cao hơn Level bên phải. |

### 3.7. Xử lý thiếu nhân sự

| Mã | Yêu cầu | Mức độ | Hiện thực trong mã nguồn |
|---|---|---|---|
| FR-14 | Đánh dấu ca thiếu, hiển thị số lượng thiếu, xác định Level còn thiếu, cung cấp danh sách ứng viên có thể cân nhắc | ⚠️ | Kết quả phân lịch có bảng **Ca thiếu nhân sự** (Ca, Ngày, Yêu cầu, Đã phân, Thiếu) và danh sách **Lý do ứng viên bị loại** (hiển thị tối đa 20 mục). Chưa xác định **Level** còn thiếu. Cột "Đã phân" chỉ đếm phân công mới của lần chạy hiện tại. |
| FR-15 | Admin tự chọn User bổ sung; hệ thống kiểm tra lại thời gian, Level, lịch hiện tại; cảnh báo và không tự động bỏ qua vi phạm; có thể chạy lại thuật toán | ⚠️ | `Scheduler.manualAssign(userId, shiftId, periodId, reason, adminOverride = true)` từ hộp **Phân công thủ công**: trùng ca và vượt giới hạn giờ → **chặn** kèm thông báo; không có thời gian rảnh → **cảnh báo** `[Admin override]` nhưng vẫn cho phép; nghỉ không đủ → cảnh báo. Mã nguồn **không kiểm tra Level** khi phân công thủ công. Admin có thể chạy lại thuật toán bất kỳ lúc nào. |

### 3.8. Duyệt và công bố lịch

| Mã | Yêu cầu | Mức độ | Hiện thực trong mã nguồn |
|---|---|---|---|
| FR-16 | Xem lịch đề xuất theo ngày, ca, nhân sự; xem tổng giờ đã phân so với mục tiêu từng User | ✅ | **Lịch làm việc** (tháng/tuần/ngày), **Duyệt & công bố** (ca chờ công bố, số người đã phân/yêu cầu), **Chi tiết ca** (danh sách nhân viên, nguồn phân công Tự động/Admin), **Báo cáo** (giờ mục tiêu, giờ đã phân, tỷ lệ % theo kỳ), bảng **Nhân viên chưa đủ giờ mục tiêu** trong kết quả phân lịch. |
| FR-17 | Admin xác nhận lịch sau khi kiểm tra ca thiếu; User xem lịch cá nhân theo tuần hoặc tháng | ✅ | `Scheduler.publishShifts(shiftIds, force)`: nếu có ca thiếu và `force = false` thì trả lỗi; giao diện hỏi xác nhận "Vẫn công bố". Nhân viên xem các ca **đã công bố** của mình ở chế độ Tuần/Tháng (`UserView.renderMySchedule()`). Tùy chọn `understaffedPolicy = 'block'` được lưu nhưng không được áp dụng khi công bố. |
| FR-18 | Lưu lịch đã phân, trạng thái công bố, lịch sử thay đổi | ✅ | Phân công lưu trong `pls_assignments` (`assignedBy`: `auto`/`admin`, `assignedAt`, `reason`); trạng thái ca `published`; nhật ký `logHistory()` lưu tối đa **500** bản ghi mới nhất; lưu `before` và `after` khi cập nhật quy tắc, `after` khi phân công thủ công, `before` khi xóa phân công của ca đã công bố. **Hạn chế:** bản ghi mới được ghi xuống `localStorage` nhưng màn hình Tổng quan và Lịch sử đọc từ bộ nhớ đệm `State` (chỉ nạp khi khởi động), nên chỉ hiển thị sau khi tải lại trang. |

---

## 4. Thuật toán phân lịch – đối chiếu mục 4 của đặc tả

| Bước (đặc tả) | Hiện thực trong `js/scheduler.js` |
|---|---|
| 1. Nhận kỳ, danh sách ca, nhu cầu | `run()` lấy kỳ, các ca chưa công bố trong kỳ |
| 2. Tính giờ mục tiêu từng User | `State.get.targetHoursForUser()` |
| 3. Xác định nhân sự đủ điều kiện cho từng vị trí | `_buildSlotPlan()` tạo danh sách vị trí; `_findBestCandidate()` lọc ứng viên |
| 4. Ưu tiên ca có ít ứng viên phù hợp trước | `_sortShiftsByConstraint()` sắp xếp ca tăng dần theo số người qua `validateAssignment()` (không tính Level) |
| 5. Ưu tiên người thiếu nhiều giờ | Sắp xếp theo hiệu số giờ mục tiêu − giờ đã phân |
| 6. Kiểm tra trùng lịch, giới hạn giờ, Level sau mỗi lần phân | Kiểm tra trước khi chọn từng ứng viên (xem hạn chế ở FR-11) |
| 7. Lặp đến khi đủ người hoặc hết phương án | Dừng khi số người của ca đạt `totalRequired` hoặc duyệt hết vị trí |
| 8. Tạo lịch nháp và danh sách cảnh báo | Kết quả trả về `assignments`, `understaffed`, `underTarget`, `rejectedCandidates`, `warnings`, `log` |
| 9. Admin bổ sung thủ công, điều chỉnh, công bố | Màn hình Chi tiết ca / Phân công thủ công / Duyệt & công bố |

Chi tiết thuật toán: xem [architecture.md – mục 5](./architecture.md#5-thuật-toán-phân-lịch-tự-động).

---

## 5. Yêu cầu phi chức năng

| Mã | Yêu cầu (đặc tả) | Mức độ | Hiện trạng theo mã nguồn |
|---|---|---|---|
| NFR-01 | Mật khẩu lưu an toàn; User chỉ truy cập chức năng của mình; chỉ Admin thay đổi cấu hình và công bố | ⚠️ | **Backend:** mật khẩu là BCrypt hash (`users.password_hash`), JWT không trạng thái. Hiện chưa có quy tắc phân quyền theo vai trò trên endpoint (mọi người dùng đã xác thực đều gọi được `GET /api/levels`). **Frontend:** mật khẩu lưu **dạng rõ** trong `localStorage` (`pls_users`); phân quyền chỉ ở phía client bằng cách hiển thị bộ route theo vai trò. |
| NFR-02 | Không tự động phân một User vào hai ca trùng giờ; tính giờ chính xác theo thời lượng ca; dùng chung quy tắc làm tròn | ⚠️ | Thời lượng ca: `calcShiftDuration()` (phút → giờ, cộng 24 giờ cho ca qua đêm). Làm tròn: `Math.round` cho giờ mục tiêu và mọi tỷ lệ %; giờ đã phân hiển thị `toFixed(1)`. Kiểm tra trùng ca so với phân công đã lưu; các phân công phát sinh trong cùng một lần chạy chưa được kiểm tra chéo (xem FR-11). |
| NFR-03 | Hiển thị giờ mục tiêu, giờ đã phân, số giờ còn thiếu; thông báo rõ ca không đủ; lịch sử thay đổi của Admin | ✅ | Bảng nhân sự (Giờ MT, Giờ đã phân, Còn thiếu), Tổng quan nhân viên, Báo cáo, kết quả phân lịch, cảnh báo trên Tổng quan Admin, màn hình Lịch sử (bản ghi mới hiển thị sau khi tải lại trang – xem FR-18). |
| NFR-04 | Mở rộng số User, ca, quy tắc mà không thiết kế lại lõi | — | Mã nguồn tách lớp lưu trữ (`Storage`), trạng thái (`State`), kiểm tra (`Validate`, `SafetyCheck`) và thuật toán (`Scheduler`); chú thích trong `js/storage.js` ghi rõ lớp lưu trữ được tách để "có thể thay bằng API sau này". Lược đồ SQL Server (V5) đã chuẩn hóa các thực thể nghiệp vụ. Chưa có số liệu đo hiệu năng. |

---

## 6. Dữ liệu chính – đối chiếu mục 6 của đặc tả

| Thực thể (đặc tả) | Frontend (`localStorage`) | Backend (SQL Server) |
|---|---|---|
| User | `pls_users` | `users` |
| Availability | `pls_free_time` (`entries[].slots[]`) | `availability_registrations`, `availability_entries`, `availability_slots` |
| Shift | `pls_shifts` | `shifts` |
| ShiftRequirement | `levelRequirements`, `mandatoryUsers`, `preferredUsers` trong mỗi ca | `shift_level_requirements`, `shift_user_preferences` |
| MonthlyTarget | `pls_schedule_rules.standardHours`, `levelTargetRates`; `users.targetRate` | `schedule_rules.standard_hours`, `schedule_level_targets`, `users.target_rate` |
| Assignment | `pls_assignments` | `assignments` |
| ScheduleHistory | `pls_history` | `history` |

Mã nguồn chưa có bảng/khóa lưu mục tiêu **theo từng kỳ**; giờ chuẩn và tỷ lệ là cấu hình dùng chung. Mô tả chi tiết trường dữ liệu: [database.md](./database.md).

---

## 7. Tiêu chí nghiệm thu – đối chiếu mục 7 của đặc tả

| # | Tiêu chí | Đáp ứng | Màn hình/chức năng kiểm chứng |
|---|---|---|---|
| 1 | User đăng ký thời gian rảnh và xem lịch cá nhân | ✅ | Đăng ký thời gian rảnh; Lịch làm việc của tôi |
| 2 | Admin tạo ca và thiết lập số lượng nhân sự theo Level | ✅ | Quản lý ca → Thêm ca (chế độ A hoặc C) |
| 3 | Admin đặt tỷ lệ mục tiêu theo Level hoặc theo cá nhân | ⚠️ | Quy tắc phân lịch (theo Level); form nhân viên (cá nhân) – xem FR-07, FR-08 |
| 4 | Quy đổi tỷ lệ thành giờ mục tiêu chính xác | ✅ | Công thức `Math.round(standardHours × rate / 100)` |
| 5 | Tự động phân lịch dựa trên thời gian rảnh, Level, số giờ còn thiếu | ✅ | Phân lịch tự động |
| 6 | Không tự động tạo lịch trùng cho cùng một User | ⚠️ | Có kiểm tra trùng so với phân công đã lưu; xem hạn chế tại FR-11 |
| 7 | Phát hiện ca thiếu nhân sự và cho phép bổ sung thủ công | ✅ | Kết quả phân lịch; Chi tiết ca → Phân công thủ công |
| 8 | Admin duyệt và công bố lịch | ✅ | Duyệt & công bố |
| 9 | Hiển thị tổng giờ đã phân, giờ mục tiêu, tỷ lệ hoàn thành của từng User | ✅ | Lịch sử & báo cáo → tab Báo cáo; Tổng quan nhân viên |

---

## 8. Các quy tắc cần xác nhận (mục 8 của đặc tả) và cách mã nguồn hiện xử lý

Đặc tả liệt kê các câu hỏi cần xác nhận trước khi triển khai. Bảng dưới chỉ mô tả **hành vi hiện tại của mã nguồn**, không thay thế quyết định nghiệp vụ.

| Câu hỏi | Hành vi hiện tại của mã nguồn |
|---|---|
| Giờ chuẩn 100% cố định cho mọi User hay theo Level? | Một giá trị `standardHours` chung cho tất cả nhân viên. |
| Tỷ lệ mục tiêu là mức ưu tiên hay mức tối thiểu bắt buộc? | Mức ưu tiên: chỉ dùng để sắp xếp ứng viên; không có ràng buộc buộc phải đạt. |
| Có cho phép phân vượt mục tiêu khi còn ca thiếu? | Có, miễn không vi phạm giới hạn giờ/ngày, giờ/tuần và các kiểm tra khác. |
| Có giới hạn giờ tối đa ngày/tuần? | Có: `maxHoursPerDay` (mặc định 12) và `maxHoursPerWeek` (mặc định 48), tuần tính từ Thứ Hai đến Chủ Nhật. |
| Admin bổ sung thủ công người không đăng ký rảnh có được ngoại lệ? | Có: lỗi thời gian rảnh chuyển thành cảnh báo `[Admin override]`; trùng ca và vượt giới hạn giờ vẫn bị chặn. |

---

## 9. Ngoài phạm vi của mã nguồn hiện tại

- Frontend gọi API backend; đồng bộ dữ liệu `localStorage` ↔ SQL Server.
- API nghiệp vụ cho kỳ, thời gian rảnh, ca, phân công, quy tắc, lịch sử (backend hiện chỉ có xác thực, health check và đọc Level).
- Phân quyền theo vai trò trên backend; thu hồi JWT khi đăng xuất.
- Tự động khóa kỳ theo hạn chót (cờ `autoLock` được lưu và hiển thị nhưng không có mã xử lý theo thời gian).
- Cấu hình triển khai (Dockerfile, CI/CD) – repository không có các tệp này.
