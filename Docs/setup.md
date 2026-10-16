# Cài đặt và chạy dự án

> **Mục đích:** hướng dẫn chuẩn bị môi trường và chạy **frontend SPA** và **backend Spring Boot** trên máy phát triển.
>
> **Nguồn:** `index.html`, `js/bootstrap.js`, `pom.xml`, `src/main/resources/*.properties`, `.env.example`, `.vscode/launch.json`, `.vscode/tasks.json`.

Hai phần chạy **độc lập**; có thể chỉ chạy frontend để dùng toàn bộ chức năng demo.

---

## 1. Yêu cầu môi trường

| Thành phần | Dùng cho | Ghi chú |
|---|---|---|
| Trình duyệt hiện đại có JavaScript và `localStorage` | Frontend | Giao diện tải font Inter từ Google Fonts (cần Internet để hiển thị đúng font) |
| Máy chủ HTTP tĩnh: Python 3 (`http.server`) hoặc Live Server (VS Code) | Frontend | Bắt buộc – `bootstrap.js` tải `partials/*.html` bằng `fetch`, không hoạt động qua `file://` |
| Node.js (tùy chọn) | Kiểm tra cú pháp JS | Xem [testing.md](./testing.md) |
| JDK 21 | Backend | `pom.xml`: `<java.version>21</java.version>` |
| Apache Maven | Backend | Repository không kèm Maven Wrapper (`mvnw`); cần cài Maven và có lệnh `mvn` trong `PATH` |
| Microsoft SQL Server | Backend | Mặc định `localhost:1433`, database `ScheduleManager` |

---

## 2. Chạy frontend

### 2.1. Bằng Python

Mở PowerShell tại thư mục gốc dự án (`Schedule-Manager`):

```powershell
py -m http.server 8000
```

Nếu lệnh `py` không khả dụng:

```powershell
python -m http.server 8000
```

Mở `http://localhost:8000/` trong trình duyệt. Dừng máy chủ bằng `Ctrl+C`.

### 2.2. Bằng VS Code Live Server

Mở thư mục dự án trong VS Code, chạy Live Server tại `index.html` và mở URL HTTP do tiện ích cung cấp.

### 2.3. Kết quả mong đợi

1. Màn hình "Đang tải ứng dụng..." xuất hiện trong lúc tải partial và script.
2. Lần đầu chạy trên một origin, ứng dụng tự khởi tạo dữ liệu demo vào `localStorage` (`pls_data_version = 1.0.0`).
3. Màn hình **Hệ thống phân lịch – Đăng nhập để tiếp tục** hiển thị.

Nếu mở trực tiếp tệp `index.html` (giao thức `file://`), ứng dụng hiển thị: *"Không thể tải ứng dụng khi mở trực tiếp tệp. Hãy chạy ứng dụng qua máy chủ HTTP rồi thử lại."*

### 2.4. Tài khoản demo của frontend

Màn hình đăng nhập có 4 nút đăng nhập nhanh tương ứng 4 tài khoản đầu trong bảng.

| Vai trò | Tên đăng nhập | Mật khẩu |
|---|---|---|
| Quản trị viên | `admin` | `admin123` |
| Nhân viên (Level 1) | `nv001` | `user123` |
| Nhân viên (Level 1) | `nv002` | `user123` |
| Nhân viên (Level 1) | `nv003` | `user123` |
| Nhân viên khác | `nv004` … `nv012` | `user123` |

`nv009` ở trạng thái **Tạm khóa**, đăng nhập sẽ báo "Tài khoản đã bị khóa. Vui lòng liên hệ Admin." Danh sách đầy đủ: [database.md – mục 2.3](./database.md#23-dữ-liệu-demo-seeddemodata).

### 2.5. Lưu ý dữ liệu frontend

- Dữ liệu nằm trong `localStorage` của **origin** đang dùng (ví dụ `http://localhost:8000`). Đổi cổng hoặc trình duyệt sẽ có bộ dữ liệu riêng.
- Muốn khởi tạo lại dữ liệu demo: xóa các khóa `pls_*` (DevTools → Application → Local Storage) rồi tải lại trang. Giao diện hiện không có nút khôi phục dữ liệu demo.

---

## 3. Chạy backend

### 3.1. Chuẩn bị SQL Server

1. Có một instance SQL Server nghe tại `DB_HOST:DB_PORT` (mặc định `localhost:1433`).
2. Tạo database có tên trùng `DB_NAME` (mặc định `ScheduleManager`).
3. Tạo login/user SQL có quyền trên database đó; lần chạy đầu cần quyền tạo bảng để Flyway thực thi migration `V1`–`V5`.

### 3.2. Tạo tệp `.env`

Sao chép `.env.example` thành `.env` tại thư mục gốc và điền giá trị thật:

```powershell
Copy-Item .env.example .env
```

| Biến | Giá trị mẫu (`.env.example`) | Mặc định trong properties | Ý nghĩa |
|---|---|---|---|
| `DB_HOST` | `localhost` | `localhost` | Máy chủ SQL Server |
| `DB_PORT` | `1433` | `1433` | Cổng |
| `DB_NAME` | `ScheduleManager` | `ScheduleManager` | Tên database |
| `DB_USERNAME` | `schedulemanagerproject` | `schedulemanagerproject` | Tài khoản SQL |
| `DB_PASSWORD` | `replace-with-your-new-local-password` | *(không có – bắt buộc)* | Mật khẩu SQL |
| `DB_TRUST_SERVER_CERTIFICATE` | `true` | `false` | Tin chứng chỉ máy chủ (kết nối luôn bật `encrypt=true`) |
| `JWT_SECRET` | `replace-with-at-least-32-random-characters` | *(không có – bắt buộc)* | Khóa ký JWT, tối thiểu 32 byte (32 ký tự ASCII) |
| `JWT_EXPIRATION_MS` | `86400000` | `86400000` | Thời hạn token (ms) |

Cách nạp: `application-sqlserver.properties` khai báo `spring.config.import=optional:file:.env[.properties]`, tức là đọc tệp `.env` **ở thư mục làm việc hiện tại** khi khởi động. Biến môi trường hệ điều hành cũng được dùng nếu đặt trực tiếp. `.env` đã nằm trong `.gitignore`; không commit tệp này.

### 3.3. Khởi chạy bằng Maven

Tại thư mục gốc dự án:

```powershell
mvn spring-boot:run
```

Profile `sqlserver` được bật mặc định bởi `application.properties` (`spring.profiles.active=sqlserver`). Khi khởi động, Flyway áp dụng các migration chưa chạy; API lắng nghe tại `http://localhost:8081`.

Kiểm tra nhanh:

```powershell
Invoke-RestMethod http://localhost:8081/api/health
Invoke-RestMethod http://localhost:8081/api/health/database
```

Kết quả mong đợi lần lượt: `status = UP, service = schedule-manager-api` và `status = UP, database = connected`.

### 3.4. Khởi chạy bằng VS Code

`.vscode/launch.json` có hai cấu hình (cần Extension Pack for Java):

| Cấu hình | `mainClass` | VM args | Ghi chú |
|---|---|---|---|
| **Spring Boot: Debug** | `org.dacnpm.schedulemanager.ManageSchedule` | `-Dspring.profiles.active=sqlserver` | Nạp biến từ `envFile: ${workspaceFolder}/.env` |
| **Spring Boot: Run (Maven)** | như trên | như trên | Chạy task `Java: Maven Compile` (`mvn -B -ntp compile`) trước |

`.vscode/tasks.json` còn có task **Spring Boot: Run** (`mvn spring-boot:run`, chạy nền, nhận biết khởi động xong khi log chứa `Started ManageSchedule`).

### 3.5. Tài khoản backend

| Tên đăng nhập | Mật khẩu | Nguồn |
|---|---|---|
| `admin` | `Admin@123` | Migration `V3`/`V4` (BCrypt cost 10) |

Tài khoản này **khác** tài khoản `admin/admin123` của frontend. Ví dụ gọi API: [api.md](./api.md).

---

## 4. Xử lý sự cố thường gặp

| Hiện tượng | Nguyên nhân theo mã nguồn | Cách xử lý |
|---|---|---|
| Frontend báo "Không thể tải ứng dụng khi mở trực tiếp tệp…" | Mở bằng `file://`, `fetch` partial thất bại | Chạy qua máy chủ HTTP (mục 2) |
| Frontend báo "Không thể tải đầy đủ ứng dụng…" kèm nút "Thử tải lại" | Một partial hoặc script trả lỗi HTTP | Kiểm tra máy chủ tĩnh được chạy tại **thư mục gốc dự án** |
| Đăng nhập frontend thành công nhưng dữ liệu "biến mất" | Đổi cổng/origin hoặc trình duyệt khác | Dùng lại đúng origin |
| Backend không khởi động, lỗi thiếu `DB_PASSWORD` hoặc `JWT_SECRET` | Hai biến này không có giá trị mặc định | Tạo `.env` ở thư mục làm việc hoặc đặt biến môi trường |
| Backend không khởi động, log có `WeakKeyException` | `JWT_SECRET` dưới 32 byte (256 bit) | Dùng chuỗi ngẫu nhiên ASCII ≥ 32 ký tự |
| Backend không khởi động, lỗi kết nối SQL Server/Flyway | Lúc khởi động Flyway cần kết nối CSDL để chạy migration | Kiểm tra SQL Server đang chạy, host, cổng, tên DB, tài khoản, chứng chỉ (`DB_TRUST_SERVER_CERTIFICATE`) |
| `/api/health/database` trả `503` | Backend đang chạy nhưng mất kết nối SQL Server (timeout kết nối Hikari 5 giây) | Kiểm tra như dòng trên |
| Gọi `/api/levels` nhận `401 {"error":"Unauthorized"}` | Thiếu hoặc token hết hạn | Đăng nhập lại để lấy token mới |
