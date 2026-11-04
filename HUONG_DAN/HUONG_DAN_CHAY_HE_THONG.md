# Hướng dẫn chạy Schedule Manager trên Windows

Hai file `.bat` là script khởi chạy hai phần của dự án, không phải hai bộ mã nguồn độc lập. Giữ thư mục `HUONG_DAN` trong thư mục dự án, cạnh `pom.xml`.

## 1. Chuẩn bị

- JDK 21 trở lên và Maven trong `PATH`. Kiểm tra bằng `java -version` và `mvn -version`; Maven phải dùng JDK 21 trở lên.
- SQL Server đang chạy, bật TCP/IP, biết đúng cổng và có database dành cho hệ thống. Môi trường kiểm thử lần bàn giao dùng SQL Server 2022.
- Node.js 20 trở lên để chạy frontend riêng. Frontend không cần `npm install`; Playwright chỉ cần khi chạy kiểm thử.
- Lần build Maven đầu tiên cần mạng để tải dependencies.

Mở PowerShell tại thư mục gốc:

```powershell
Copy-Item .env.example .env
```

Mở `.env` bằng trình soạn thảo và điền thông tin của máy bạn:

```properties
DB_HOST=localhost
DB_PORT=1433
DB_NAME=ScheduleManager
DB_USERNAME=ten_tai_khoan_sql
DB_PASSWORD=mat_khau_sql_cua_ban
DB_TRUST_SERVER_CERTIFICATE=true
JWT_SECRET=chuoi_ngau_nhien_it_nhat_32_ky_tu
JWT_EXPIRATION_MS=86400000
```

Có thể tạo JWT secret bằng PowerShell: `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))` trên PowerShell 7, hoặc dùng trình quản lý mật khẩu tạo chuỗi ngẫu nhiên. Không đưa `.env`, database backup hoặc fixture chứa mật khẩu vào Git. Tài khoản DB cần quyền tạo/sửa schema khi Flyway chạy; khi triển khai thật nên tách tài khoản migration và tài khoản runtime.

## 2. Chạy backend

Nhấp đúp `CHAY_BACKEND.bat`, hoặc chạy:

```powershell
.\HUONG_DAN\CHAY_BACKEND.bat
```

Script build JAR vào `target`, sau đó chạy API ở **http://localhost:8081**. Script dừng nếu thiếu Java/Maven/`.env` hoặc build thất bại. Bước build trong script bỏ qua test để khởi động; quy trình kiểm thử riêng ở mục 6 mới là bằng chứng chất lượng.

Nếu đã có đủ Maven cache, dùng `.\HUONG_DAN\CHAY_BACKEND.bat --offline` để build không cần mạng.

Chờ backend khởi động xong rồi mở **http://localhost:8081/api/health**; kết quả phải có `status: UP`. Endpoint `/api/health/database` kiểm tra kết nối SQL Server. Flyway tự áp dụng migration V1–V18 theo thứ tự, không tự xóa dữ liệu.

**Database cũ:** nếu đã chạy migration riêng của từng nhánh có V6/V7 khác nhau, cần backup và đối chiếu `flyway_schema_history` trước. Không sửa checksum, xóa history hay dùng `repair` để bỏ qua lỗi. V18 bổ sung tổ chức/hồ sơ; không thay nội dung migration đã chạy trước đó. Hướng dẫn tích hợp tại `Docs/integration-baseline-moc-a.md`.

## 3. Chạy frontend riêng

Giữ cửa sổ backend đang chạy, mở cửa sổ thứ hai:

```powershell
.\HUONG_DAN\CHAY_FRONTEND.bat
```

Mở **http://localhost:8080**. Frontend host chỉ phục vụ những HTML/CSS/JS công khai được cho phép; `/api/*` chuyển tiếp tới backend 8081. Nhờ vậy đăng nhập, chấm công trong iframe và tải báo cáo dùng cùng origin; không cần sửa base URL hay bật CORS rộng.

Cũng có thể mở trực tiếp **http://localhost:8081** vì backend đã đóng gói giao diện. Cách này dùng cùng tính năng và cùng database. Chọn một địa chỉ cho phiên làm việc: JWT lưu trong `sessionStorage` của địa chỉ/tab đang dùng, nên đổi cổng hoặc mở tab mới có thể cần đăng nhập lại.

Không chạy `file://workspace.html`, không dùng `demo.html` để nghiệm thu, và không phục vụ toàn bộ repository bằng HTTP server thông thường. `demo.html` và các file giao diện cũ là bản tham khảo dùng dữ liệu local.

## 4. Đăng nhập và thao tác ban đầu

Database mới có tài khoản seed `admin`, mật khẩu ban đầu `Admin@123` từ migration V4. Đây là tài khoản khởi tạo cho môi trường phát triển. Sau khi đăng nhập, chọn **Đổi mật khẩu** và đặt mật khẩu riêng trước khi cho người khác dùng. Mật khẩu mới tối thiểu 8 ký tự, tối đa 72 byte UTF-8 do BCrypt.

Thứ tự cấu hình và vận hành:

1. **Nhân viên & danh mục:** tạo chi nhánh, bộ phận, nhóm, loại nhân viên, Level; tạo nhân viên, liên hệ, ngày vào làm và quản lý trực tiếp. Gán địa điểm/chức vụ/kỹ năng/chứng chỉ cùng hạn dùng. Loại nhân viên, Level, chức vụ và role là các khái niệm riêng.
2. **Quy tắc:** thiết lập mục tiêu giờ, mode fixed/percent, trần ngày/tuần, nghỉ tối thiểu, ngày liên tiếp và các tham số bắt buộc. Xem trước rồi xác nhận. Giá trị thiếu phải được xử lý trong checklist; không có cấu hình trần nghiệp vụ tự kích hoạt từ dữ liệu demo.
3. **Kỳ làm việc:** tạo kỳ, chọn đối tượng/ngày cần đăng ký, hạn và autoLock; mở đăng ký. AutoLock tắt nghĩa là Admin đóng thủ công. Thời gian nhập là giờ Việt Nam; thời điểm lưu trong DB là UTC.
4. Nhân viên đăng nhập tài khoản của mình, **Đăng ký thời gian**, lưu nháp hoặc gửi. Sau hạn, server chặn ghi dù form đã mở từ trước. Mở lại kỳ cần xác nhận, lý do và hạn mới theo chính sách.
5. Admin tạo **Ca làm** gắn kỳ, chạy thử/lưu **Phương án lịch**, xem thiếu người/vi phạm, gửi duyệt, duyệt và công bố. Kỳ phải khóa đăng ký trước khi lưu/công bố. Nhân viên chỉ thấy lịch chính thức của mình và có thể xác nhận đã xem lịch.
6. **Đổi/thay ca, Nghỉ phép, Ca trống:** dùng luồng gửi/chấp thuận/duyệt. Yêu cầu chờ duyệt giữ nguyên lịch; lúc duyệt server kiểm tra lại điều kiện và phiên bản.
7. **Chấm công:** nhân viên check-in/out ca đã công bố được giao cho mình. Quản trị viên duyệt công/đề nghị sửa và khóa kỳ sau đối soát. Sau khóa, sửa công phải đi qua đề nghị và duyệt có lý do.
8. **Báo cáo:** chọn đúng kỳ và ngữ cảnh lịch công bố/phương án. Giờ lịch, giờ làm thực tế và giờ công đã duyệt được tách riêng. Có CSV/XLSX; nhật ký chung nằm ở **Nhật ký hệ thống**, nhật ký công ở màn hình chấm công.

Logout xóa JWT phía client. JWT có hạn dùng; bản hiện tại chưa có deny-list thu hồi từng token. Mỗi API production kiểm tra lại trạng thái/quyền tài khoản trong DB, nên khóa/lưu trữ tài khoản sẽ chặn token cũ. Đổi mật khẩu không tự thu hồi mọi JWT đã phát trước đó.

## 5. Đổi cổng, dừng và xử lý lỗi

```powershell
$env:BACKEND_PORT='8091'
.\HUONG_DAN\CHAY_BACKEND.bat
# Ở cửa sổ frontend:
$env:FRONTEND_PORT='8090'
$env:BACKEND_URL='http://127.0.0.1:8091'
.\HUONG_DAN\CHAY_FRONTEND.bat
```

Frontend host chỉ nhận backend HTTP trên máy cục bộ và chỉ lắng nghe loopback. Đây là cấu hình phát triển; triển khai truy cập từ xa cần reverse proxy HTTPS, cấu hình mạng và quản lý secret phù hợp.

Nhấn **Ctrl+C** ở từng cửa sổ để dừng; dữ liệu vẫn ở SQL Server. Nếu cổng bận, kiểm tra tiến trình đang dùng cổng trước khi đổi cổng hoặc dừng tiến trình đó.

| Hiện tượng | Cách xử lý |
|---|---|
| Maven không chạy hoặc báo release 21 | Kiểm tra `JAVA_HOME`, `PATH`, `mvn -version` và phiên bản JDK |
| Build không tải được thư viện | Kiểm tra mạng/proxy Maven; `mvn -o` chỉ dùng được khi đã có đủ cache |
| Login lỗi hoặc backend không khởi động | Kiểm tra `.env`, SQL Server/TCP/cổng, database, quyền DB và độ dài JWT secret; xem log cửa sổ backend |
| Frontend trả 502 | Backend chưa chạy/chưa sẵn sàng hoặc `BACKEND_URL` sai |
| 401 | Phiên hết hạn, tài khoản bị khóa hoặc quyền đã đổi; đăng nhập lại bằng tài khoản còn hiệu lực |
| 403 | Tài khoản không có quyền thao tác; không sửa userId/branchId để vượt quyền |
| 409 / RETRY_REQUIRED | Dữ liệu hoặc khóa đã thay đổi; tải lại, đối chiếu trạng thái rồi thử lại |
| Checklist thiếu cấu hình | Điền quy tắc bắt buộc đúng phạm vi/ngày hiệu lực và xác nhận áp dụng |
| Ca chưa phân loại kỳ | Admin gắn kỳ đúng cho ca trước khi sửa lịch/chấm công |

## 6. Chạy kiểm thử

```powershell
mvn test
npm ci
npm run browser:install
.\tests\verify-moc-a.ps1
# Nếu toàn bộ Maven dependencies đã có:
.\tests\verify-moc-a.ps1 -Offline
```

Script tích hợp cần `sqlcmd` và tài khoản Windows có quyền tạo database QA trên `.\SQLEXPRESS`; tài khoản SQL trong `.env` được cấp quyền trên các database QA riêng. Cổng 8082 phải trống. Script tạo database mới với tiền tố `ScheduleManager_MocA_Test_`, chạy Maven gồm `IntegratedMocAIT` và `IssueFiveFlowIT`, khởi động backend QA, chạy HTTP rồi Playwright và dừng đúng backend do script tạo. Không chạy suite này vào database vận hành.

Log, JSON evidence, manifest SHA-256 và ảnh nằm trong `.local-test`; thư mục này không được commit. Database QA được giữ lại để điều tra, không tự xóa. Playwright có 6 CSS viewport 320/360/390/768/1024/1280, DPR 1.25 và zoom 125%. **Chúng không thay thế bước thử Windows Display Scale thật khác 100%.** Để chốt AC-09, đặt Windows Scale 125% hoặc 150%, chạy lại kiểm tra giao diện và ghi cấu hình thực tế trong báo cáo.

## 7. Backup và phục hồi

Với database vận hành, senior/DBA cần lập lịch backup đầy đủ, giữ bản sao ngoài máy chủ, kiểm soát quyền đọc backup và diễn tập phục hồi. Không ghi đè database đang dùng để thử phục hồi.

Script dưới chỉ nhận tên database QA đúng tiền tố, tạo bản backup `COPY_ONLY` có checksum, `RESTORE VERIFYONLY`, khôi phục vào database QA mới, chạy `DBCC CHECKDB` và đối chiếu số bản ghi các bảng chính:

```powershell
.\tests\verify-backup-restore.ps1 -SourceDatabase ScheduleManager_MocA_Test_TEN_DATABASE_QA
```

Chạy khi các bài test đã xong, không có luồng đang ghi vào database nguồn để đối chiếu số bản ghi ổn định. SQL Server service account cần quyền ghi thư mục `.local-test/backups`. Kết quả ở `.local-test/backup-restore-evidence.json`; không đưa `.bak`, `.mdf`, `.ldf` lên Git.

Tham khảo trạng thái nghiệm thu và kế hoạch mốc B trong `BAO_CAO_BAN_GIAO_MOC_A_VA_KE_HOACH_MOC_B.md` cùng thư mục này. Giữ nguyên `task_v1.1.md` làm chuẩn yêu cầu.
