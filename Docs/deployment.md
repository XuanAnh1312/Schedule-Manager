# Triển khai

> **Mục đích:** mô tả những gì mã nguồn hiện hỗ trợ để đóng gói và chạy hệ thống ngoài môi trường phát triển, cùng danh sách kiểm tra bảo mật trước khi triển khai.
>
> **Phạm vi:** repository **không có** Dockerfile, cấu hình CI/CD, script triển khai hay cấu hình máy chủ web. Tài liệu này chỉ dựa trên `pom.xml`, các tệp `application*.properties`, `.env.example`, `.gitignore` và chú thích trong mã nguồn.

---

## 1. Thành phần cần triển khai

| Thành phần | Sản phẩm triển khai | Yêu cầu chạy |
|---|---|---|
| Frontend SPA | Các tệp tĩnh: `index.html`, `partials/`, `css/`, `js/` | Bất kỳ máy chủ HTTP tĩnh nào |
| Backend API | File JAR do `spring-boot-maven-plugin` tạo | JRE/JDK 21, kết nối SQL Server, biến môi trường |
| Cơ sở dữ liệu | Database SQL Server | Flyway tự tạo lược đồ khi backend khởi động |

Hiện frontend và backend **không phụ thuộc nhau** khi chạy; frontend có thể triển khai riêng như một trang tĩnh.

---

## 2. Triển khai frontend

### 2.1. Tệp cần sao chép

```
index.html
partials/login.html
partials/app-shell.html
partials/dialogs.html
css/styles.css
js/bootstrap.js  js/storage.js  js/state.js  js/validation.js  js/scheduler.js
js/admin.js      js/admin2.js   js/admin3.js js/user.js        js/app.js
```

Giữ nguyên cấu trúc thư mục vì đường dẫn trong `index.html` (`data-include="partials/..."`) và `bootstrap.js` (`'js/storage.js'`, …) là đường dẫn tương đối. Thư mục `tests/` không cần cho môi trường chạy thật.

### 2.2. Yêu cầu máy chủ

- Phục vụ qua HTTP/HTTPS (không chạy qua `file://`).
- Trả đúng tệp `.html` cho các yêu cầu `fetch` tới `partials/*.html`.
- Không cần cấu hình rewrite URL: router không dùng đường dẫn hay hash.
- Trình duyệt người dùng cần truy cập được `fonts.googleapis.com` / `fonts.gstatic.com` để tải font Inter (nếu không, trình duyệt dùng font dự phòng).

### 2.3. Tính chất dữ liệu sau khi triển khai

- Mỗi trình duyệt/thiết bị có bộ dữ liệu `localStorage` riêng, **không chia sẻ** giữa người dùng.
- Lần mở đầu tiên trên mỗi trình duyệt, dữ liệu demo được khởi tạo tự động.
- Mật khẩu frontend lưu dạng rõ; bản frontend hiện tại chỉ phù hợp cho mục đích trình diễn.

---

## 3. Triển khai backend

### 3.1. Đóng gói

Tại thư mục gốc:

```powershell
mvn clean package
```

- Lệnh `package` chạy cả bộ test (Surefire tự bật profile `test`, dùng H2, không cần SQL Server). Nếu test lỗi, quá trình đóng gói dừng.
- Sản phẩm: `target/ManageSchedule-1.0-SNAPSHOT.jar` (theo `artifactId = ManageSchedule`, `version = 1.0-SNAPSHOT`); `spring-boot-maven-plugin` đóng gói lại thành JAR chạy được (kèm thư viện trong `BOOT-INF/`), bản gốc đổi tên thành `.jar.original`. Đã kiểm chứng ngày 17/10/2026 bằng `mvn package -DskipTests` (Maven 3.10.0, JDK 21): `BUILD SUCCESS`.

### 3.2. Chạy

```powershell
java -jar target/ManageSchedule-1.0-SNAPSHOT.jar
```

| Cấu hình | Giá trị/Nguồn |
|---|---|
| Profile | `sqlserver` (mặc định trong `application.properties`) |
| Cổng | `8081` (`server.port` trong `application-sqlserver.properties`) |
| Biến bắt buộc | `DB_PASSWORD`, `JWT_SECRET` |
| Biến tùy chọn | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_TRUST_SERVER_CERTIFICATE`, `JWT_EXPIRATION_MS` (xem [setup.md – mục 3.2](./setup.md#32-tạo-tệp-env)) |
| Tệp `.env` | Tùy chọn; được đọc từ **thư mục làm việc** của tiến trình (`optional:file:.env[.properties]`) |

Ví dụ đặt biến môi trường trong PowerShell trước khi chạy:

```powershell
$env:DB_HOST = "<máy chủ SQL>"
$env:DB_PASSWORD = "<mật khẩu>"
$env:JWT_SECRET = "<chuỗi ngẫu nhiên tối thiểu 32 ký tự>"
$env:DB_TRUST_SERVER_CERTIFICATE = "false"
java -jar target/ManageSchedule-1.0-SNAPSHOT.jar
```

### 3.3. Cơ sở dữ liệu và migration

- Khi khởi động, Flyway áp dụng các migration chưa chạy trong `db/migration` (`V1`–`V5`) và ghi lịch sử vào `flyway_schema_history`.
- Tài khoản dùng cho lần chạy migration cần quyền tạo bảng/index; sau đó tài khoản runtime chỉ cần quyền đọc/ghi.
- Không sửa nội dung migration đã áp dụng; thay đổi lược đồ bằng migration mới `V6__...`.
- Sao lưu database trước khi triển khai phiên bản có migration mới.

### 3.4. Kiểm tra sau triển khai

| Bước | Lệnh/Endpoint | Kết quả mong đợi |
|---|---|---|
| 1 | `GET /api/health` | `200 {"status":"UP","service":"schedule-manager-api"}` |
| 2 | `GET /api/health/database` | `200 {"status":"UP","database":"connected"}` |
| 3 | `POST /api/auth/login` với tài khoản quản trị | `200` có `token` |
| 4 | `GET /api/levels` với token | `200`, danh sách Level |
| 5 | `GET /api/levels` không token | `401 {"error":"Unauthorized"}` |

Hai endpoint health là công khai, có thể dùng cho giám sát.

---

## 4. Danh sách kiểm tra bảo mật trước khi triển khai

Các mục dưới đây trích từ chú thích và cấu hình trong mã nguồn:

| # | Hạng mục | Căn cứ |
|---|---|---|
| 1 | **Đổi mật khẩu tài khoản `admin`** (seed `Admin@123`) trước lần triển khai production đầu tiên | Chú thích trong `V3__seed_admin_user.sql`, `V4__fix_admin_password_hash.sql`, `BcryptSeedVerificationTest` |
| 2 | Dùng `JWT_SECRET` ngẫu nhiên, tối thiểu 32 byte (32 ký tự ASCII), khác giá trị dùng cho test | `.env.example`, `JwtUtil` (khóa HMAC từ secret) |
| 3 | Không commit `.env`; chỉ commit `.env.example` | `.gitignore` (`.env`, `.env.*`, `!.env.example`) |
| 4 | Đặt `DB_TRUST_SERVER_CERTIFICATE=false` khi máy chủ SQL có chứng chỉ hợp lệ (giá trị mặc định trong properties là `false`; `.env.example` dùng `true` cho máy cục bộ) | `application-sqlserver.properties` |
| 5 | Tách quyền: tài khoản runtime chỉ đọc/ghi; quyền DDL chỉ dùng khi chạy migration | Khuyến nghị trong tài liệu CSDL |
| 6 | Xem xét thời hạn token (`JWT_EXPIRATION_MS`, mặc định 24 giờ) vì đăng xuất không thu hồi token | `AuthService`, `AuthController.logout` |
| 7 | Không dùng frontend hiện tại với dữ liệu cá nhân/mật khẩu thật | Mật khẩu dạng rõ trong `localStorage` |

---

## 5. Giới hạn hiện tại liên quan đến triển khai

- Không có Dockerfile, docker-compose, pipeline CI/CD hay cấu hình reverse proxy trong repository.
- Backend không phục vụ tệp frontend; hai phần phải được phục vụ riêng.
- `SecurityConfig` không cấu hình CORS; nếu sau này frontend ở origin khác gọi API, cần bổ sung cấu hình này.
- Chưa có cấu hình HTTPS trong ứng dụng; nếu cần, phải đặt ở tầng máy chủ/proxy phía trước.
