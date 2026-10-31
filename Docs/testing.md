# Kiểm thử

## Bản tích hợp mốc A

Ứng dụng chính hiện dùng `workspace.html` và API cùng origin. Xem [hướng dẫn kiểm thử và kết quả tích hợp](moc-a-completion.md).

- `mvn test`: unit/controller và kiểm tra wiring. **294 tests PASS** (kết quả lần cuối 31/10/2026).
- `IntegratedMocAIT`: kiểm tra SQL Server thật, bật có chủ đích trên database test riêng.
- `tests/moc-a-http.mjs`: luồng API thật, đồng thời và thông báo.
- `tests/moc-a-browser.mjs`: Admin/Employee, form, phiên đăng nhập và responsive trên Chromium.

---

## Bằng chứng nghiệm thu AC-10, AC-11, AC-12 (Task 3.1 — Anh)

### AC-10: Backend chuẩn — dữ liệu bền vững từ API, không còn localStorage làm nguồn chính

| Tiêu chí | Cách kiểm tra | Kết quả / Bằng chứng |
|---|---|---|
| Đăng nhập gọi API thật | `POST /api/auth/login` → trả `{"token":"...","tokenType":"Bearer"}` | `app.js::_doLogin()` gọi `fetch('/api/auth/login')`, lưu token vào `sessionStorage('auth_jwt')` |
| Không so sánh password từ State | Không còn `State.get.users().find(u => u.password === pw)` | Đã xóa khỏi `_doLogin()`, thay bằng API call |
| Session khôi phục từ JWT | Tải lại trang → gọi `/api/me` xác nhận token còn hợp lệ | `app.js::init()` kiểm tra `sessionStorage('auth_jwt')` → `fetch('/api/me')` |
| Trình duyệt khác đọc đúng | Đăng nhập trên tab mới → gọi `/api/me` từ JWT đã lưu | Token lưu `sessionStorage` (không `localStorage`) — chỉ trong tab hiện tại |
| Tài khoản khóa bị chặn | `/api/me` với token của tài khoản `inactive` → 401 `ACCOUNT_LOCKED` | `AuthController.me()` kiểm tra `status == active` |
| `IdentityGuard` chặn token cũ | Token còn hạn nhưng account bị khóa trên DB → interceptor trả 401 | `IdentityGuard` query DB mỗi request `/api/**` |

**Unit test bằng chứng:**
```
AuthControllerTest#me_withLockedAccount_returns401          → PASS
AuthControllerTest#me_withValidToken_returnsUserProfile     → PASS
AuthControllerTest#me_withoutToken_returns401               → PASS
AuthControllerTest#me_doesNotExposePasswordHash             → PASS
```

---

### AC-11: Phân quyền API — actor từ JWT, không tin client-supplied userId

| Tiêu chí | Cách kiểm tra | Kết quả / Bằng chứng |
|---|---|---|
| Actor ID lấy từ JWT | `POST /api/auth/change-password` không có trường `userId` trong body | `AuthController.changePassword()` gọi `CurrentUser.require().id()` |
| Không thể đổi mật khẩu người khác | Body không chứa `userId`; actor chỉ từ JWT sub | Test `changePassword_doesNotAcceptUserIdFromBody` xác nhận service nhận đúng `usr_002` từ token |
| Employee chỉ đọc profile của mình | `GET /api/my/profile` → lấy từ JWT, không có query param `userId` | `WorkspaceDirectory.myProfile()` dùng `CurrentUser.require().id()` |
| Admin CRUD nhân viên | `POST/PUT /api/workspace/employees` yêu cầu role admin | `CurrentUser.require().requireAdmin()` throws 403 nếu không đủ quyền |
| Catalog endpoint chỉ cho admin | `GET /api/workspace/catalog` → 403 nếu role=user | `CurrentUser.require().requireAdmin()` trong `WorkspaceDirectory.catalog()` |
| Không lộ password_hash | `GET /api/me` không trả `passwordHash` hay `password_hash` | `UserRepository.findProfileById()` không select `password_hash` |

**Unit test bằng chứng:**
```
AuthControllerTest#changePassword_doesNotAcceptUserIdFromBody → PASS
AuthControllerTest#changePassword_withoutToken_returns401     → PASS
AuthControllerTest#changePassword_withWrongCurrentPassword_returns401 → PASS
AuthControllerTest#me_doesNotExposePasswordHash               → PASS
```

---

### AC-12: Audit trail — actor, timestamp, before/after, reason

| Tiêu chí | Cách kiểm tra | Bằng chứng |
|---|---|---|
| Tạo nhân viên có audit | `POST /api/workspace/employees` → insert vào `history` | `WorkspaceDirectory.create()` gọi `audit.record("employee_create", ...)` với `actorId` từ JWT |
| Cập nhật nhân viên có before/after | `PUT /api/workspace/employees/{id}` → `before_json` từ DB, `after_json` mới | `WorkspaceDirectory.update()` query `before` trước khi update, gọi `audit.record(...)` |
| AuditTrail ghi actor_name | Lookup `full_name` từ `users` theo `actor_id` | `AuditTrail.record()` query `SELECT full_name FROM users WHERE id = ?` |
| Đổi mật khẩu không ghi audit chi tiết | Không ghi hash vào audit log | `AuthService.changePassword()` không gọi `AuditTrail` (không lộ hash) |
| Các module khác (shift/leave/requests) | `AuditTrail` là shared bean; các module gọi cùng interface | `ShiftRequestService`, `LeaveService`, `OfficialScheduleService` đều inject `AuditTrail` |

**Code evidence:**
```java
// WorkspaceDirectory.create():
audit.record("employee_create", "Tạo nhân viên", id, actor.id(),
             null, Map.of("id", id, "fullName", in.fullName()), null);

// WorkspaceDirectory.update():
var before = jdbc.queryForList("SELECT ... FROM users WHERE id = ? AND role = 'user'", id);
audit.record("employee_update", "Cập nhật nhân viên", id, actor.id(),
             before.get(0), Map.of("fullName", ..., "status", ...), null);
```

---

## Tóm tắt kết quả mvn test (31/10/2026)

```
Tests run: 294, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Breakdown theo class liên quan đến Task 3.1:

| Test Class | Tests | Nội dung |
|---|---|---|
| `AuthControllerTest` | 15 | login/logout, change-password, /api/me — security contract |
| `AuthServiceTest` | 12 | login logic, changePassword — unit test không cần Spring context |

---

## Kiểm thử API thủ công (curl / api.http)

```http
# 1. Login
POST http://localhost:8081/api/auth/login
Content-Type: application/json

{"username": "admin", "password": "Admin@123"}

# 2. Dùng token từ bước 1
GET http://localhost:8081/api/me
Authorization: Bearer {{token}}

# 3. Đổi mật khẩu (actor từ JWT, không cần userId)
POST http://localhost:8081/api/auth/change-password
Authorization: Bearer {{token}}
Content-Type: application/json

{"currentPassword": "Admin@123", "newPassword": "NewPass@456"}

# 4. Xem danh sách nhân viên (admin only)
GET http://localhost:8081/api/workspace/employees
Authorization: Bearer {{admin_token}}

# 5. Xem chi tiết nhân viên
GET http://localhost:8081/api/workspace/employees/usr_xxx
Authorization: Bearer {{admin_token}}

# 6. Danh mục tổ chức
GET http://localhost:8081/api/workspace/catalog
Authorization: Bearer {{admin_token}}

# 7. Profile của chính mình (employee)
GET http://localhost:8081/api/my/profile
Authorization: Bearer {{user_token}}

# 8. Logout
POST http://localhost:8081/api/auth/logout
Authorization: Bearer {{token}}
```

---

## Kiểm thử trình duyệt tự động

Bộ smoke cũ tải `demo.html` (localStorage). Kết quả smoke không thay thế nghiệm thu API/SQL/E2E của bản tích hợp. Xem [moc-a-completion.md](moc-a-completion.md) để biết quy trình E2E với Chromium.

Suite kiểm tra đăng nhập và lỗi form; responsive ở 320/375/768/1024/1440 px; truy cập bảng/lịch bằng bàn phím; và luồng khai báo/gửi thời gian rảnh.

---

## Kiểm thử Issue #8: chấm công thực tế và báo cáo

Xem [hướng dẫn BI-01..BI-07](./issue8-attendance.md) và [ma trận bằng chứng AC](./issue8-ac-evidence.md). Suite API là `tests/attendance-api.ps1`, fixture local-only là `tests/attendance-fixture.sql`, responsive ở `tests/attendance-responsive.html`. Chạy `mvn test` và kiểm thử cả API 401/403/409, hai user cạnh tranh, login/logout/refresh, 320/360/390/768/1024/1280 px và Windows Scale 125%/150%.

---

## Kiểm tra cú pháp JavaScript

Với Node.js đã cài đặt, mở PowerShell tại thư mục gốc:

```powershell
Get-ChildItem js -Filter *.js | ForEach-Object {
  node --check $_.FullName
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

---

## Kiểm tra thủ công bổ sung

Chạy ứng dụng qua HTTP như mô tả trong [setup.md](./setup.md), rồi kiểm tra:

1. Trang đăng nhập gọi `POST /api/auth/login` — không so sánh password từ localStorage.
2. Đăng nhập sai hoặc tài khoản bị khóa trả 401 từ server; không vào được màn hình ứng dụng.
3. Tải lại trang khôi phục phiên từ JWT (`sessionStorage`); tài khoản bị khóa sau khi cấp token → `/api/me` trả 401.
4. Logout gọi `POST /api/auth/logout` và xóa `sessionStorage('auth_jwt')`.
5. Không còn nút chuyển tài khoản demo (role-tabs ẩn hoặc bị xóa).
6. `GET /api/me` không trả `passwordHash` hay `password_hash`.
7. `POST /api/auth/change-password` không chấp nhận `userId` từ body — luôn dùng JWT sub.
8. Employee không gọi được `GET /api/workspace/employees` (admin only) → 403.
9. `GET /api/my/profile` trả profile của đúng user đang đăng nhập.
10. Admin tạo/cập nhật nhân viên → bảng `history` ghi entry với `actor_id`, `before_json`, `after_json`.

## Lưu ý kiểm thử dữ liệu

Phiên đăng nhập lưu JWT trong `sessionStorage` của tab — không chia sẻ giữa các tab và xóa khi đóng trình duyệt. Dùng profile/trình duyệt riêng để kiểm thử các kịch bản cạnh tranh (hai user cùng đăng nhập).
