# Hướng dẫn chạy Schedule Manager trên Windows

Chỉ cần mở **`CHAY_HE_THONG.bat`**. Một ứng dụng Spring Boot phục vụ cả giao diện và API tại **http://localhost:8080**, dùng database SQL Server trong `.env`. Hai file chạy FE/BE cũ đã được thay thế. Giữ thư mục `HUONG_DAN` trong thư mục dự án, cạnh `pom.xml`.

## 1. Chuẩn bị

- JDK 21 trở lên và Maven trong `PATH`. Kiểm tra bằng `java -version` và `mvn -version`; Maven phải dùng JDK 21 trở lên.
- SQL Server đang chạy, bật TCP/IP, biết đúng cổng và có database dành cho hệ thống. Môi trường kiểm thử lần bàn giao dùng SQL Server 2022.
- Không cần Node.js/npm để sử dụng hệ thống. Node.js/Playwright chỉ cần khi chạy các công cụ nạp fixture và kiểm thử.
- Lần build Maven đầu tiên cần mạng để tải dependencies.

Nếu máy đã có `.env` và kết nối SQL Server được, **bỏ qua bước tạo file**, giữ nguyên thông tin của máy. Chỉ tạo file khi mới tải dự án và chưa có `.env`:

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
```

Mở `.env` bằng trình soạn thảo (`notepad .env`) để cấu hình nếu đây là file mới hoặc thông tin kết nối đang sai. Các giá trị dưới đây là mẫu, **không copy đè lên mật khẩu SQL/JWT đang dùng được**:

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

## 2. Chạy toàn bộ hệ thống

Nhấp đúp `CHAY_HE_THONG.bat`, hoặc chạy:

```powershell
.\HUONG_DAN\CHAY_HE_THONG.bat
```

Script kiểm tra `.env`, Java và cổng; đóng gói JAR vào `target` khi chưa có build hoặc mã nguồn thay đổi. Lần sau dùng lại JAR nếu fingerprint nguồn và hash JAR vẫn khớp. Maven ưu tiên cache offline, chỉ tải mạng khi thiếu dependency. Build lúc khởi động bỏ qua test; kiểm thử chất lượng chạy riêng ở mục 6.

Script đọc cấu hình hiện có, không ghi đè `.env`, không reset hoặc nạp lại database. Frontend gọi API cùng origin và mọi mutation nghiệp vụ được ghi vào SQL Server. Node proxy cũ không nằm trong luồng chạy chính.

Chỉ khi `/api/health` và `/api/health/database` đều `UP`, script mới báo **SAN SANG** và tự mở **http://localhost:8080**. Giữ cửa sổ đang chạy. Nếu mở lại `.bat` khi đúng phiên này còn hoạt động, script dùng lại phiên đó; không tạo thêm server. Cổng bị ứng dụng khác chiếm sẽ được báo lỗi, không tự tắt tiến trình khác. Nếu mã nguồn đã thay đổi, dừng phiên cũ bằng Ctrl+C rồi mở lại.

**Database cũ:** nếu đã chạy migration riêng của từng nhánh có V6/V7 khác nhau, cần backup và đối chiếu `flyway_schema_history` trước. Không sửa checksum, xóa history hay dùng `repair` để bỏ qua lỗi. V18 bổ sung tổ chức/hồ sơ; không thay nội dung migration đã chạy trước đó. Hướng dẫn tích hợp tại `Docs/integration-baseline-moc-a.md`.

## 3. Sử dụng và xem log

Một địa chỉ duy nhất cho cả hai vai trò:

```text
http://localhost:8080
```

Đăng nhập Admin để quản lý hoặc đăng nhập bằng tài khoản nhân viên được cấp. Khi tạo/sửa qua giao diện, dữ liệu đi qua backend, kiểm tra quyền và lưu vào database thật `ScheduleManager` theo cấu hình local hiện tại. Đăng xuất/đăng nhập lại hoặc trình duyệt khác vẫn đọc cùng dữ liệu; browser storage không phải nguồn nghiệp vụ.

Log nằm trong `.local-run/build.log`, `.local-run/application.log`, `.local-run/application-error.log`. Nếu lỗi, cửa sổ `.bat` giữ thông báo để đọc. JWT lưu trong `sessionStorage`, nên mở tab mới hoặc đổi cổng có thể cần đăng nhập lại.

Không chạy `file://workspace.html`, không dùng `demo.html` để nghiệm thu, và không phục vụ toàn bộ repository bằng HTTP server thông thường. `demo.html` và các file giao diện cũ là bản tham khảo dùng dữ liệu local.

## 4. Đăng nhập và thao tác ban đầu

Tài khoản nhân viên do Admin cấp khi thêm hồ sơ; không có tự đăng ký. Nhân viên được tự đổi mật khẩu sau khi đăng nhập. Năm tài khoản thử và cách nạp chủ động: [Tài khoản nhân viên test](TAI_KHOAN_NHAN_VIEN_TEST.md).

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
$env:APP_PORT='8090'
.\HUONG_DAN\CHAY_HE_THONG.bat
# Tùy chọn:
.\HUONG_DAN\CHAY_HE_THONG.bat --offline
.\HUONG_DAN\CHAY_HE_THONG.bat --rebuild
.\HUONG_DAN\CHAY_HE_THONG.bat --no-browser
.\HUONG_DAN\CHAY_HE_THONG.bat --stop
```

Ứng dụng lắng nghe loopback trên máy cục bộ. Cách chạy này cập nhật SQL Server thật và dùng quyền/API thật; triển khai truy cập từ xa cần cấu hình HTTPS, mạng và quản lý secret phù hợp.

Nhấn **Ctrl+C ở cửa sổ hệ thống**, hoặc chạy `CHAY_HE_THONG.bat --stop`, để dừng đúng tiến trình Java mà launcher đã tạo; dữ liệu đã lưu vẫn ở SQL Server. Lệnh stop kiểm tra PID/thời điểm tạo để không tắt nhầm tiến trình khác. Không cần chạy thêm FE/BE hay mở thêm cửa sổ dịch vụ. Khi sửa `.env`, dừng rồi chạy lại để ứng dụng nạp cấu hình mới; không cần build lại JAR nếu mã nguồn chưa đổi.

| Hiện tượng | Cách xử lý |
|---|---|
| Maven không chạy hoặc báo release 21 | Kiểm tra `JAVA_HOME`, `PATH`, `mvn -version` và phiên bản JDK |
| Build không tải được thư viện | Kiểm tra mạng/proxy Maven; `mvn -o` chỉ dùng được khi đã có đủ cache |
| Login lỗi hoặc backend không khởi động | Kiểm tra `.env`, SQL Server/TCP/cổng, database, quyền DB và độ dài JWT secret; xem log cửa sổ backend |
| Cổng bận | Dừng hai cửa sổ FE/BE cũ hoặc chọn `APP_PORT` khác; không chạy song song proxy cũ và ứng dụng chung trên cùng cổng |
| Khởi động thất bại | Đọc `.local-run/application.log` và `application-error.log`; kiểm tra mật khẩu SQL, quyền migration và trạng thái database |
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
