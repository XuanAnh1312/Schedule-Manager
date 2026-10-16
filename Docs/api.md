# Tài liệu API backend

> **Mục đích:** đặc tả các endpoint REST hiện có trong backend Spring Boot.
>
> **Nguồn:** `src/main/java/org/dacnpm/schedulemanager/**`, `src/main/resources/application*.properties`, `api.http`, các lớp test trong `src/test/java/**`.

---

## 1. Thông tin chung

| Mục | Giá trị |
|---|---|
| Base URL (phát triển) | `http://localhost:8081` |
| Định dạng dữ liệu | JSON (`Content-Type: application/json`) |
| Xác thực | JWT trong header `Authorization: Bearer <token>` |
| Phiên | Không trạng thái (`SessionCreationPolicy.STATELESS`), không dùng cookie, CSRF tắt |
| Profile | `sqlserver` (mặc định trong `application.properties`) |

> Frontend SPA hiện **chưa sử dụng** các API này; dữ liệu của frontend nằm trong `localStorage`.

### 1.1. Danh sách endpoint

| # | Phương thức | Đường dẫn | Xác thực | Profile cần có | Mô tả |
|---|---|---|---|---|---|
| 1 | POST | `/api/auth/login` | Công khai | `sqlserver` (cho `AuthService`) | Đăng nhập, nhận JWT |
| 2 | POST | `/api/auth/logout` | Công khai | — | Xác nhận đăng xuất phía client |
| 3 | GET | `/api/health` | Công khai | — | Kiểm tra dịch vụ đang chạy |
| 4 | GET | `/api/health/database` | Công khai | `sqlserver` | Kiểm tra kết nối SQL Server |
| 5 | GET | `/api/levels` | **JWT** | `sqlserver` | Danh sách Level |

Theo cấu hình `SecurityConfig`, mọi đường dẫn không nằm trong danh sách công khai (kể cả các đường dẫn không tồn tại) đều đi qua quy tắc `anyRequest().authenticated()`: thiếu JWT hợp lệ sẽ nhận `401`. (Hành vi `401` đã được kiểm chứng bằng test cho `/api/levels`; với các đường dẫn khác đây là suy ra từ cấu hình.)

### 1.2. Định dạng lỗi chung

| Tình huống | HTTP | Thân phản hồi | Nơi sinh |
|---|---|---|---|
| Thiếu/sai/hết hạn JWT khi gọi endpoint cần xác thực | 401 | `{"error":"Unauthorized"}` | `authenticationEntryPoint` trong `SecurityConfig` |
| Đăng nhập thất bại | 401 | `{"error":"Invalid credentials"}` | `AuthController.login` |
| Không kết nối được CSDL | 503 | `{"status":"DOWN","database":"unavailable"}` | `DatabaseHealthController` |

Phản hồi lỗi không chứa stack trace hay thông tin bí mật (chú thích trong `JwtAuthenticationFilter`).

---

## 2. Xác thực

### 2.1. `POST /api/auth/login`

Đăng nhập bằng tên đăng nhập và mật khẩu; trả về JWT.

**Request**

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "Admin@123"
}
```

| Trường | Kiểu | Bắt buộc | Mô tả |
|---|---|---|---|
| `username` | string | Có | Tên đăng nhập (`users.username`) |
| `password` | string | Có | Mật khẩu dạng rõ, được so với `users.password_hash` bằng BCrypt |

**Xử lý (`AuthService.login`)**

1. `UserRepository.findByUsername(username)` – `SELECT id, username, password_hash, role, status FROM users WHERE username = ?`.
2. Loại nếu `status` khác `'active'`.
3. Loại nếu `passwordEncoder.matches(password, password_hash)` sai.
4. Phát hành JWT bằng `JwtUtil.issue(id, username, role)`.

**Response 200 – thành công**

```json
{
  "token": "eyJhbGciOi...",
  "tokenType": "Bearer"
}
```

| Trường | Kiểu | Mô tả |
|---|---|---|
| `token` | string | JWT đã ký |
| `tokenType` | string | Luôn là `"Bearer"` |

**Response 401 – thất bại**

```json
{ "error": "Invalid credentials" }
```

Cùng một phản hồi cho cả ba trường hợp: tên đăng nhập không tồn tại, sai mật khẩu, tài khoản `inactive` (chống dò tài khoản – kiểm chứng bởi `AuthControllerTest` và `AuthServiceTest`).

**Tài khoản phát triển (seed V3/V4):** `admin` / `Admin@123`.

### 2.2. `POST /api/auth/logout`

API không lưu phiên nên không có gì để hủy ở máy chủ; endpoint chỉ phản hồi để client chủ động xóa token. **Token không bị thu hồi** và vẫn hợp lệ đến khi hết hạn.

**Request**

```http
POST /api/auth/logout
```

**Response 200**

```json
{ "message": "Logged out. Please discard your token." }
```

### 2.3. Cấu trúc JWT

Phát hành bởi `JwtUtil.issue()`, ký bằng khóa HMAC tạo từ biến `JWT_SECRET` (`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`).

| Claim | Giá trị |
|---|---|
| `sub` | `users.id` (ví dụ `usr_admin_001`) |
| `username` | `users.username` |
| `role` | `users.role` (`admin` hoặc `user`) |
| `iat` | Thời điểm phát hành |
| `exp` | `iat + JWT_EXPIRATION_MS` (mặc định `86400000` ms = 24 giờ) |

Yêu cầu về khóa: `JWT_SECRET` tối thiểu **32 byte** (256 bit) khi mã hóa UTF-8 – với ký tự ASCII là 32 ký tự (theo `.env.example` và chú thích trong `application-test.properties`). Đã kiểm chứng với JJWT 0.13.0: khóa 31 byte bị `Keys.hmacShaKeyFor` từ chối bằng `WeakKeyException`, khóa 32 byte được chấp nhận; khi đó backend không khởi động được vì bean `JwtUtil` không tạo được.

### 2.4. Xác minh token (`JwtAuthenticationFilter`)

| Trường hợp header `Authorization` | Kết quả |
|---|---|
| Không có header | Tiếp tục như người dùng ẩn danh |
| Có tiền tố `Bearer ` và token hợp lệ | Đặt `Authentication` với principal = `sub`, authority = `ROLE_` + `role` viết hoa (ví dụ `ROLE_ADMIN`, `ROLE_USER`) |
| Token sai định dạng, sai chữ ký, hết hạn | Không đặt `Authentication` (chỉ ghi log mức debug) |

Sau đó `SecurityFilterChain` quyết định cho phép hay trả `401`. Hiện chưa có quy tắc phân quyền theo `ROLE_*`; mọi người dùng đã xác thực có quyền như nhau.

---

## 3. Kiểm tra sức khỏe

### 3.1. `GET /api/health`

Kiểm tra tiến trình API đang chạy; không truy cập CSDL. Không phụ thuộc profile.

**Response 200**

```json
{ "status": "UP", "service": "schedule-manager-api" }
```

### 3.2. `GET /api/health/database`

Chạy truy vấn `SELECT 1` qua `JdbcTemplate`. Chỉ tồn tại với profile `sqlserver`.

**Response 200 – kết nối được**

```json
{ "status": "UP", "database": "connected" }
```

**Response 503 – không kết nối được**

```json
{ "status": "DOWN", "database": "unavailable" }
```

Chi tiết lỗi chỉ ghi vào log máy chủ (mức `WARN`: "SQL Server health check failed.").

---

## 4. Level

### 4.1. `GET /api/levels`

Trả về toàn bộ Level, sắp xếp tăng dần theo `sort_order`.

**Request**

```http
GET /api/levels
Authorization: Bearer <token>
```

**Truy vấn**

```sql
SELECT id, name, description, color, sort_order
FROM levels
ORDER BY sort_order
```

**Response 200**

```json
[
  { "id": "lvl1", "name": "Level 1", "description": "Nhân viên mới",        "color": "#7c3aed", "order": 1 },
  { "id": "lvl2", "name": "Level 2", "description": "Nhân viên chính thức", "color": "#be185d", "order": 2 },
  { "id": "lvl3", "name": "Level 3", "description": "Nhân viên kỳ cựu",     "color": "#c2410c", "order": 3 },
  { "id": "lvl4", "name": "Level 4", "description": "Trưởng ca",            "color": "#065f46", "order": 4 }
]
```

| Trường | Kiểu | Cột nguồn |
|---|---|---|
| `id` | string | `levels.id` |
| `name` | string | `levels.name` |
| `description` | string \| null | `levels.description` |
| `color` | string | `levels.color` |
| `order` | number | `levels.sort_order` |

Khi bảng rỗng: `200` với `[]`.

**Response 401** – không có token, token sai định dạng, sai chữ ký hoặc hết hạn:

```json
{ "error": "Unauthorized" }
```

---

## 5. Ví dụ sử dụng

### 5.1. Tệp `api.http`

Thư mục gốc có tệp `api.http` (dùng với tiện ích REST Client của VS Code hoặc trình HTTP client của IntelliJ) gồm hai request: đăng nhập và lấy danh sách Level. Token trong tệp là giá trị mẫu đã phát hành trước đó; cần thay bằng token nhận được từ bước đăng nhập (token cũ sẽ hết hạn sau `JWT_EXPIRATION_MS`).

### 5.2. PowerShell

```powershell
$login = Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/auth/login" `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"Admin@123"}'

Invoke-RestMethod -Uri "http://localhost:8081/api/levels" `
  -Headers @{ Authorization = "Bearer $($login.token)" }
```

### 5.3. curl

```bash
curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@123"}'

curl -s http://localhost:8081/api/levels \
  -H "Authorization: Bearer <token>"

curl -s http://localhost:8081/api/health
curl -s http://localhost:8081/api/health/database
```

---

## 6. Ma trận kiểm thử hợp đồng API

Các hành vi trên được kiểm chứng tự động (chi tiết: [testing.md](./testing.md)).

| Endpoint | Kịch bản | Kỳ vọng | Lớp test |
|---|---|---|---|
| `POST /api/auth/login` | Không cần token | 200 | `AuthControllerTest` |
| | Đúng thông tin | 200, `token`, `tokenType = Bearer` | `AuthControllerTest` |
| | Sai mật khẩu / user không tồn tại / user inactive | 401, `Invalid credentials` | `AuthControllerTest` |
| `POST /api/auth/logout` | Không cần token | 200, thông báo hủy token | `AuthControllerTest` |
| `GET /api/health` | Không cần token | 200, `status = UP`, `service = schedule-manager-api` | `HealthControllerTest` |
| `GET /api/levels` | Không token / token sai định dạng / sai chữ ký / hết hạn | 401 | `LevelControllerTest` |
| | JWT hợp lệ (user hoặc admin) | 200, 4 phần tử đúng trường, sắp theo `order` | `LevelControllerTest` |
| | Bảng rỗng | 200, `[]` | `LevelControllerTest` |

---

## 7. Giới hạn hiện tại

- Chưa có API cho nhân sự, kỳ đăng ký, thời gian rảnh, ca, phân công, quy tắc, phân lịch và lịch sử, dù lược đồ CSDL (V5) đã có các bảng tương ứng.
- Chưa phân quyền theo vai trò ở mức endpoint.
- Đăng xuất không thu hồi JWT (chú thích trong `AuthService` đề cập hướng mở rộng bằng danh sách chặn theo `jti` nếu cần).
- Không có cấu hình CORS trong `SecurityConfig`.
- Dự án có phụ thuộc `spring-boot-starter-actuator`; các đường dẫn `/actuator/**` không thuộc danh sách công khai nên cũng yêu cầu JWT.
