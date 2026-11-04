# Quy tắc chung cho dự án HRM

Tài liệu áp dụng cho toàn bộ nhóm **Anh, Tuấn, Tường, Bảo, Bình**, xuyên suốt thiết kế, lập trình, kiểm thử, tích hợp và vận hành.

Phạm vi nghiệp vụ căn cứ [Ban_chuc_nang_HRM_V1_1.md](./Ban_chuc_nang_HRM_V1_1.md); trách nhiệm từng thành viên căn cứ [task_v1.1.md](./task_v1.1.md). Đây là quy tắc phải tuân thủ khi phát triển, không phải báo cáo tiến độ triển khai.

## 1. Nguyên tắc chung

1. Backend và database là nguồn dữ liệu nghiệp vụ chuẩn. Frontend chỉ hiển thị, thu thập input và cache dữ liệu phục vụ giao diện.
2. Mọi quyết định nghiệp vụ, kiểm tra quyền, giới hạn giờ, hạn đăng ký và phê duyệt phải được thực thi tại backend.
3. Dữ liệu của cùng một nghiệp vụ chỉ có một nguồn sở hữu; module khác truy vấn hoặc gọi service, không tự tạo bản dữ liệu cạnh tranh.
4. Phân biệt điều kiện bắt buộc với tiêu chí ưu tiên. Không vi phạm điều kiện bắt buộc để làm đầy lịch hoặc tạo báo cáo đẹp.
5. Không cố định tham số nghiệp vụ tùy ý. Admin cấu hình, hệ thống kiểm tra và tự tính kết quả suy ra.
6. Mọi thao tác quan trọng phải truy vết được người thực hiện, thời điểm, đối tượng, thay đổi và lý do khi cần.
7. Bảo toàn lịch sử; điều chỉnh dữ liệu đã duyệt/khóa/công bố phải đi qua quy trình riêng.
8. Hoàn thiện nghiệp vụ cốt lõi và tính nhất quán trước khi mở rộng HRM. Payroll, LMS và module nâng cao triển khai theo phạm vi đã chốt.
9. Thay đổi hợp đồng API, schema hoặc quy tắc dùng chung phải thông báo cho thành viên phụ thuộc và cập nhật tài liệu trong cùng đợt thay đổi.

## 2. Công nghệ và cấu trúc dự án

| Thành phần | Quy định |
|---|---|
| Backend | Java 21, Spring Boot; phiên bản dependency lấy từ `pom.xml` |
| Build | Maven; dùng cấu hình thống nhất trong repository |
| API | Spring Web, REST API, JSON, tiền tố `/api` |
| Database | Microsoft SQL Server |
| Truy cập dữ liệu | Spring JDBC, `JdbcTemplate`, connection pool HikariCP |
| Migration | Flyway; SQL trong `src/main/resources/db/migration/` |
| Bảo mật | Spring Security, JWT, BCrypt |
| Frontend | HTML, CSS, JavaScript thuần; tải partial qua HTTP; gọi API bằng client dùng chung |
| Kiểm thử | Spring Boot Test, Spring Security Test; test tích hợp SQL Server và smoke/E2E trình duyệt |
| Cấu hình | Spring profiles, biến môi trường và file cấu hình mẫu không chứa bí mật thật |

- Giữ stack thống nhất; không tự đổi framework, database hoặc cơ chế xác thực trong một task nghiệp vụ.
- Chỉ thêm dependency/dịch vụ khi giải quyết nhu cầu cụ thể; ghi rõ mục đích, cấu hình, ảnh hưởng bảo mật, vận hành và kiểm thử.
- Package Java gốc là `org.dacnpm.schedulemanager`; nhóm code theo module nghiệp vụ.
- Đặt tên có nghĩa; Java/JavaScript/JSON dùng `camelCase`, class Java dùng `PascalCase`, bảng/cột SQL dùng `snake_case`.
- Mã nguồn và tài liệu lưu UTF-8; dữ liệu tiếng Việt phải giữ đúng dấu.
- Không đặt logic nghiệp vụ trong file cấu hình, script seed hoặc CSS/HTML.

## 3. Kiến trúc backend

Luồng xử lý chuẩn:

```text
Request → Security → Controller/DTO → Service → Repository → SQL Server
                                        ↓
                              Audit và sự kiện nghiệp vụ
```

### 3.1. Controller và DTO

- Controller nhận input, lấy danh tính từ SecurityContext, gọi service và ánh xạ response/HTTP status.
- Dùng DTO riêng cho create/update/response khi quyền hoặc trường dữ liệu khác nhau; chỉ cho phép cập nhật trường được khai báo.
- Không bind toàn bộ bản ghi DB từ request rồi lưu trực tiếp; không cho client tự sửa role, actor, trạng thái duyệt hoặc trường hệ thống.
- Validate trường bắt buộc, kiểu, độ dài, miền giá trị, enum và định dạng. Service tiếp tục kiểm tra điều kiện nghiệp vụ.
- Không đưa thuật toán, SQL hoặc chuỗi cập nhật nhiều bảng vào controller.
- Không trả password/hash, token nội bộ, credential DB, stacktrace hoặc trường ngoài quyền.

### 3.2. Service

- Service sở hữu quy tắc nghiệp vụ, kiểm tra phạm vi quyền, chuyển trạng thái, transaction và điều phối audit/sự kiện.
- Dùng constructor injection; dependency có trách nhiệm rõ ràng; tránh phụ thuộc vòng giữa các module.
- Tách bộ kiểm tra phân công dùng chung để thuật toán, sửa thủ công, đổi ca và nhận ca sử dụng cùng quy tắc.
- Không gọi controller của module khác để tái sử dụng nghiệp vụ; gọi service/hợp đồng nội bộ đã thống nhất.
- Lỗi nghiệp vụ cần mã rõ ràng và ngữ cảnh để UI hướng dẫn người dùng xử lý.
- Không bắt Exception chung rồi trả thành công hoặc âm thầm bỏ qua lỗi.

### 3.3. Repository

- Repository chỉ phụ trách SQL, bind tham số, mapping và truy xuất dữ liệu.
- Dùng query có tham số; không nối input vào SQL. Cột sort và hướng sort lấy từ whitelist.
- Phân trang ở database; tránh tải toàn bộ dữ liệu rồi lọc/phân trang trong bộ nhớ.
- Tránh truy vấn lặp từng nhân viên/ca khi có thể batch; chỉ chọn cột cần thiết.
- Ràng buộc nghiệp vụ có tính duy nhất phải được bảo vệ ở DB khi thích hợp, đồng thời ánh xạ lỗi DB thành lỗi API dễ hiểu.

## 4. Database và migration

### 4.1. Thiết kế dữ liệu

- Có PK, FK, UNIQUE, CHECK và index phù hợp. Mã nhân viên và username phải duy nhất theo quy ước đã chốt.
- Dùng `NVARCHAR` cho nội dung tiếng Việt; kích thước trường theo dữ liệu thực tế, không dùng trường không giới hạn tùy tiện.
- Tách employeeType, Level, chức vụ, kỹ năng/chứng chỉ và role; không gộp vào một trường.
- Tách kỳ đăng ký, phương án lịch, phiên bản công bố, ca và phân công; định nghĩa quan hệ rõ ràng.
- Tách giờ dự kiến, giờ thực tế và giờ công đã duyệt. Không lưu nhiều trường suy ra mà không có cơ chế duy trì nhất quán.
- Thời lượng dùng đơn vị chuẩn thống nhất, ưu tiên số phút khi tính; giờ decimal và tiền dùng độ chính xác phù hợp. Java dùng `BigDecimal` cho tiền.
- Chính sách lương, hợp đồng, kỹ năng/chứng chỉ và quy tắc cần ngày hiệu lực hoặc version khi tác động kết quả theo thời gian.
- Dùng trạng thái/lưu trữ/phiên bản thay cho xóa cứng dữ liệu cần đối soát; không cascade xóa lịch sử chỉ vì khóa nhân viên.
- Tài liệu đính kèm phải có metadata, chủ sở hữu và quyền truy cập; tải file qua cơ chế kiểm tra quyền, không công khai đường dẫn nhạy cảm.

### 4.2. Flyway

- Mọi thay đổi schema và dữ liệu nền có migration `V<so>__<mo_ta>.sql`.
- Không sửa migration đã áp dụng ở môi trường dùng chung. Không dùng sửa DB thủ công làm cách triển khai chuẩn.
- Anh điều phối version khi tích hợp; từng thành viên viết migration của module mình, không để hai nhánh trùng số.
- Thêm cột bắt buộc phải tính dữ liệu cũ, backfill, giá trị chưa cấu hình và bước áp constraint.
- Không tự gán ngưỡng nghiệp vụ vào dữ liệu thật khi nâng cấp. Mẫu đề xuất phải cần Admin xác nhận.
- Kiểm tra cả tạo DB mới và nâng cấp từ version đang sử dụng trên SQL Server.
- Migration có rủi ro dữ liệu phải có backup và phương án phục hồi; đánh giá khóa bảng và thời gian chạy.
- Seed chỉ dùng dữ liệu phát triển/kiểm thử phù hợp; không chứa bí mật hoặc dữ liệu cá nhân thật.

## 5. Giao dịch, đồng thời và tính nhất quán

- Đặt transaction ở service cho nghiệp vụ cập nhật nhiều bản ghi/bảng.
- Kiểm tra lại quyền, trạng thái, version và điều kiện tại thời điểm thực thi; kết quả preview không thay thế kiểm tra khi commit.
- Dùng version/conditional update/locking phù hợp để tránh ghi đè; với JDBC phải kiểm tra số dòng cập nhật.
- Chống hai request cùng nhận chỗ cuối, duyệt cùng một yêu cầu, tạo check-in đang mở hoặc công bố từ phiên bản cũ.
- Dùng khóa duy nhất/idempotency key khi cần để retry không tăng giờ, tạo phân công, duyệt hoặc thanh toán hai lần.
- Audit bắt buộc phải lưu cùng giao dịch nghiệp vụ; nếu thất bại thì không coi quyết định đã hoàn thành.
- Sự kiện/thông báo chỉ được xử lý sau commit; không gửi cho dữ liệu đã rollback.
- Dùng cơ chế lưu sự kiện bền vững và retry khi cần đảm bảo không mất thông báo; retry phải có dedup.
- Không giữ transaction DB mở trong lúc chờ người dùng xác nhận hoặc chờ dịch vụ gửi email.

## 6. Hợp đồng API

### 6.1. Thiết kế endpoint

- API dưới `/api`, tài nguyên đặt tên nhất quán; GET dùng để đọc, POST để tạo/hành động nghiệp vụ, PUT/PATCH theo semantics đã chốt.
- Công bố lịch, duyệt đơn, mở lại kỳ và khóa công phải có hành động rõ ràng; không cho client tùy ý sửa status để bỏ qua quy trình.
- Trước tích hợp phải thống nhất method/path, request/response, trường required/nullable, enum, ngày/giờ, filter, pagination, quyền và lỗi.
- Không thay đổi hợp đồng gây vỡ client mà không cập nhật phía dùng và test liên quan.
- API danh sách trả metadata phân trang nhất quán; giới hạn page size, khoảng ngày và export để tránh truy vấn không kiểm soát.
- Filter và báo cáo phải mang đủ kỳ, phạm vi tổ chức, trạng thái và phiên bản/phương án khi liên quan.

### 6.2. HTTP status và lỗi

| Status | Quy tắc |
|---|---|
| 200 | Thao tác thành công có response |
| 201 | Tạo tài nguyên thành công |
| 204 | Thành công, không có response body |
| 400 | Input không hợp lệ |
| 401 | Chưa xác thực hoặc token không hợp lệ/hết hạn |
| 403 | Đã xác thực nhưng không có quyền |
| 404 | Tài nguyên không tìm thấy hoặc không được phép tiết lộ sự tồn tại |
| 409 | Xung đột trạng thái/version/cập nhật đồng thời |
| 500 | Lỗi hệ thống ngoài dự kiến; không lộ chi tiết nội bộ |
| 503 | Phụ thuộc cần thiết tạm không khả dụng |

- Lỗi chuẩn có `code`, `message`, `fieldErrors` khi phù hợp và `traceId` để tra log. Khi chuẩn hóa endpoint đang được dùng, sửa đồng bộ client và test.
- Mã lỗi ổn định, ví dụ `PERIOD_CLOSED`, `MISSING_RULE_CONFIGURATION`, `HOURS_LIMIT_EXCEEDED`, `VERSION_CONFLICT`.
- Không trả 200 cho nghiệp vụ thất bại. Không để frontend phải phân tích chuỗi lỗi để quyết định luồng.
- Thông báo lỗi phải đủ để người dùng sửa input, nhưng không tiết lộ SQL, credential hoặc dữ liệu ngoài quyền.

## 7. Xác thực và phân quyền

- Mật khẩu chỉ kiểm tra ở backend bằng BCrypt; không lưu/so mật khẩu rõ trong frontend hoặc localStorage.
- JWT truyền bằng `Authorization: Bearer <token>`; không đưa vào URL, log hoặc thông báo lỗi.
- Khóa ký JWT phải đủ mạnh, lấy từ môi trường; secret test và production tách biệt. Thời hạn token cấu hình được và được kiểm tra tại server.
- Logout phải xóa trạng thái/token phía client; nếu cần vô hiệu hóa token phía server phải thực hiện bằng cơ chế thu hồi/token version phù hợp, không chỉ trả thông báo logout thành công.
- Khóa tài khoản, nghỉ việc hoặc thay quyền phải được phản ánh khi truy cập API, kể cả người dùng còn token cũ.
- Kiểm tra role/permission và quyền trên đối tượng, nhân viên, chi nhánh, bộ phận; không chỉ kiểm tra đã đăng nhập.
- Principal, actor, thời điểm duyệt và quyền lấy từ server; ID do client gửi phải được kiểm tra quyền sở hữu/phạm vi.
- Employee chỉ xem/sửa dữ liệu cá nhân được cho phép; quản lý chỉ thao tác trong phạm vi được giao; quyền payroll/tài liệu nhạy cảm phải tách rõ.
- Hạn chế thử đăng nhập liên tục; không tiết lộ tài khoản có tồn tại hay không qua lỗi đăng nhập.
- Chốt cơ chế lưu/khôi phục phiên frontend; không lưu mật khẩu để tự đăng nhập lại. Token trong trình duyệt phải được bảo vệ tương ứng với mô hình đã chọn.
- Nếu dùng cookie xác thực, cấu hình HttpOnly/Secure/SameSite và CSRF phù hợp; cơ chế Bearer/cookie phải thống nhất với Spring Security.
- CORS chỉ cho origin cần thiết theo môi trường; cấu hình method/header và preflight đúng. Không dùng `no-cors` để né lỗi tích hợp.
- Production dùng HTTPS; không public endpoint quản trị, chi tiết health hoặc file nội bộ ngoài nhu cầu vận hành.

## 8. Thời gian, cấu hình và quy tắc nghiệp vụ

### 8.1. Thời gian

- Múi giờ nghiệp vụ thống nhất `Asia/Ho_Chi_Minh`; backend quyết định thời điểm, không tin đồng hồ client.
- Timestamp API dùng ISO-8601 có UTC/offset; ngày nghiệp vụ dùng `YYYY-MM-DD`. Giờ ca phải đi kèm ngày và quy ước múi giờ.
- Nếu dùng SQL Server `DATETIME2` lưu UTC, phải chuyển đổi rõ ràng vì kiểu này không mang offset.
- Dùng `Clock` hoặc abstraction thời gian để test deadline và ngày hiệu lực ổn định.
- Chốt ranh giới trước/tại/sau hạn, cách chia ca đêm qua ngày/tuần/kỳ và giờ nghỉ; tất cả module dùng cùng định nghĩa.

### 8.2. Quy tắc có hiệu lực

- Admin được cấu hình mục tiêu/trần giờ, điều kiện ca, nghỉ giữa ca, ngày liên tiếp, ưu tiên, hạn đăng ký, duyệt, nhắc và hiển thị báo cáo theo quyền.
- Phạm vi hỗ trợ công ty, chi nhánh, employeeType, nhóm, cá nhân; thứ tự ưu tiên và xử lý scope ngang cấp xung đột phải được chốt và có test.
- Trả rõ giá trị đang có hiệu lực, scope, nguồn và version; không để người dùng đoán quy tắc đang áp dụng.
- Ràng buộc bắt buộc/an toàn và hợp đồng/chứng chỉ không được bị cấu hình thông thường vô hiệu hóa.
- Thiếu tham số bắt buộc phải liệt kê và chặn chức năng liên quan; fallback chỉ dùng khi đã được cấu hình/xác nhận.
- Mẫu tham khảo phải gắn nhãn đề xuất. Không áp sẵn 120%, 125% hoặc ngưỡng tùy ý cho mọi doanh nghiệp.
- Thay quy tắc có validation, preview tác động, xác nhận, lịch sử before/after, người sửa và lý do; không tự sửa lịch công bố/kỳ khóa.

## 9. Quy tắc từng phân hệ

### 9.1. Nhân viên và tổ chức

- Quản lý mã nhân viên duy nhất, thông tin liên hệ, loại hình, trạng thái, ngày vào làm, Level, chức vụ, bộ phận/địa điểm và quản lý trực tiếp.
- Phân biệt khóa tài khoản với trạng thái hồ sơ làm việc; kiểm tra tác động đến ca tương lai khi nhân viên ngừng hoạt động.
- Lưu lịch sử điều chuyển, hợp đồng và dữ liệu nhạy cảm theo quyền; import/export/cập nhật hàng loạt phải validate từng dòng, báo lỗi và có audit.
- File tải lên kiểm tra kích thước, loại/nội dung cho phép; đặt tên lưu an toàn, ngăn ghi đè/path traversal và kiểm tra quyền khi tải xuống.

### 9.2. Kỳ đăng ký và thời gian rảnh

- Chuyển trạng thái theo quy trình hợp lệ; kỳ đăng ký và phương án lịch có vòng đời riêng.
- Tạo kỳ phải có phạm vi, ngày mở/hạn và chính sách autoLock rõ ràng.
- autoLock bật: tự khóa khi hết hạn và kiểm tra lại ở mọi thao tác đăng ký liên quan; tác vụ nền không thay thế kiểm tra request.
- Chặn tạo/sửa/lưu nháp/gửi khi kỳ đóng; form mở trước hạn nhưng submit sau hạn vẫn bị kiểm tra lại.
- autoLock tắt: đóng thủ công theo quyền, hiển thị rõ chế độ quản lý thủ công.
- Mở lại cần quyền, xác nhận, lý do, audit và chính sách hạn mới; dữ liệu đã gửi được giữ nguyên.
- Validate ngày thuộc kỳ, đối tượng áp dụng, khoảng giờ hợp lệ và phân biệt thời gian rảnh mong muốn với thời gian không thể làm.

### 9.3. Ca và thuật toán xếp lịch

- Ca có ngày, giờ bắt đầu/kết thúc, giờ nghỉ, địa điểm, nhu cầu min/max và điều kiện nhân sự; hỗ trợ ca đêm/lặp theo phạm vi.
- Tách mục tiêu giờ và trần giờ. Trần fixed hoặc `targetHours × percent / 100`; hệ thống tự tính.
- Kiểm tra trước từng lần chốt phân công: active/phạm vi địa điểm, trùng ca, thời gian không thể làm, Level/chức vụ/kỹ năng/chứng chỉ, nghỉ tối thiểu, giờ ngày/tuần/kỳ và số ngày liên tiếp.
- Level thay thế theo chiều/quy tắc được cấu hình; kỹ năng/chứng chỉ phải có hiệu lực tại thời điểm ca.
- Tính cả phân công đã lưu và vừa tạo trong lần chạy; xử lý ca đêm, nghỉ và giao kỳ. Loại phân công hủy/phiên bản thay thế khỏi tổng để không đếm trùng.
- Cân bằng mục tiêu, số ca, mong muốn và chi phí là tiêu chí mềm; chỉ áp dụng sau khi thỏa điều kiện bắt buộc.
- Không đủ ứng viên thì giữ ca thiếu, ghi số người thiếu và lý do; không tự vượt giới hạn để lấp ca.
- Dry-run không thay lịch chính thức; chạy lại không nhân đôi phân công hoặc ghi đè phương án đã chốt khi chưa có xác nhận hợp lệ.

### 9.4. Duyệt, công bố và sửa lịch

- Tách nháp/chờ duyệt/đã duyệt/đã công bố khỏi độ phủ đủ/thiếu người.
- Employee chỉ thấy lịch chính thức được phép; UI không trình bày bản nháp như lịch công bố.
- Công bố/sửa lịch có version và audit; kiểm tra lại điều kiện tại thời điểm áp dụng.
- Sửa thủ công và kéo thả phải gọi cùng service validation với tự động xếp lịch.
- Ngoại lệ cần quyền riêng, xác nhận, lý do và audit; chỉ được ghi đè chính sách được phép, không ghi đè điều kiện bắt buộc.
- Thông báo cho người bị ảnh hưởng khi công bố/thay đổi; lưu đã xem/xác nhận lịch khi nghiệp vụ yêu cầu.

### 9.5. Đổi ca, thay ca và Open Shift

- Yêu cầu có người gửi/nhận, ca gốc, trạng thái, thời điểm, người duyệt và lý do.
- Pending không làm thay đổi lịch chính thức. Người nhận phải đồng ý theo quy trình và thỏa điều kiện phân ca.
- Kiểm tra lại giờ, lịch, năng lực và version tại thời điểm duyệt; yêu cầu lỗi thời phải được xử lý rõ ràng.
- Sau duyệt, cập nhật phân công/version/audit và thông báo; xác định người chịu trách nhiệm cuối cùng cho ca.
- Open Shift có nhu cầu, điều kiện, hạn nhận; đăng ký không đồng nghĩa đã được nhận. Quy trình chọn/duyệt không được vượt số lượng cần tuyển.
- Chống duyệt lặp và cạnh tranh chỗ cuối bằng transaction/constraint phù hợp.

### 9.6. Chấm công và nghỉ phép

- Check-in/out gắn đúng nhân viên/ca; ngăn lượt đang mở trùng, thời gian đảo ngược và sửa dữ liệu người khác.
- Xử lý ca đêm, nghỉ giữa ca, thiếu check-out, đi muộn/về sớm và vắng theo chính sách được cấu hình.
- Sửa/bổ sung công phải có yêu cầu, người duyệt, quyết định/lý do và dữ liệu gốc; khóa kỳ và điều chỉnh sau khóa qua quy trình riêng.
- Nghỉ có loại, trạng thái, phạm vi thời gian, người duyệt và chính sách quyền lợi; không mặc định Part-time không có phép.
- Nghỉ được duyệt phải đánh giá/cập nhật tác động lên lịch và cảnh báo độ phủ, không âm thầm xóa trách nhiệm ca.

### 9.7. Thông báo và audit

- Thông báo có loại, thời gian, recipient, nội dung, liên kết nghiệp vụ và đọc/chưa đọc.
- Nhắc hạn/nhắc người chưa gửi theo cấu hình; dedup theo sự kiện/phiên bản/người nhận; retry có giới hạn và theo dõi lỗi.
- Người dùng chỉ đọc/đánh dấu thông báo thuộc quyền mình; không tin recipientId client gửi.
- Audit chứa actor server, timestamp, entity/id/version, hành động, before/after và lý do khi cần.
- Không lưu password/token trong audit; bảo vệ quyền đọc và tính toàn vẹn, không cắt lịch sử tùy tiện như dữ liệu demo.

### 9.8. Dashboard và báo cáo

- Cùng kỳ, phạm vi, filter, trạng thái và version phải ra cùng số liệu ở mọi màn hình; dùng định nghĩa/tính toán chung tại backend.
- Thẻ, bảng, biểu đồ và export dùng cùng bộ lọc. Ghi rõ nháp/công bố, giờ dự kiến/thực tế/đã duyệt và thời điểm cập nhật.
- Hiển thị tổng giờ đã xếp, mục tiêu, chênh lệch và tỷ lệ; `ratio = scheduledHours / targetHours × 100%` khi mẫu số hợp lệ.
- Mục tiêu 0 hoặc thiếu dữ liệu hiển thị “Chưa đủ dữ liệu”; không biến thành 0% gây hiểu nhầm.
- Ví dụ 808/740 = khoảng 109,19%, vượt 68 giờ; làm tròn số nguyên là 109%. Cấu hình làm tròn chỉ đổi hiển thị, không đổi công thức.
- Export tuân quyền và chống công thức nguy hiểm từ input khi xuất bảng tính/CSV; không lộ dữ liệu ngoài phạm vi.

### 9.9. Các phân hệ HRM mở rộng

- **Payroll:** đơn giá/quy tắc có ngày hiệu lực; tính từ công đã duyệt và chính sách/hợp đồng; có tính thử, đối soát, duyệt, khóa, điều chỉnh và phiếu lương cá nhân. Không sửa âm thầm kỳ đã khóa.
- **LMS/Skills:** theo dõi học, kết quả, chứng chỉ và hạn; chỉ cập nhật điều kiện đủ chuẩn phân ca qua quy trình xác nhận phù hợp.
- **Hợp đồng/onboarding/offboarding:** tài liệu phân quyền, lịch sử hiệu lực, nhắc hạn, bàn giao và thu hồi quyền; phối hợp chốt lịch/công/lương.
- **Performance/Benefits:** KPI công bằng theo loại hình và mục tiêu; nguồn thành tích/khoản thưởng thống nhất, tránh ghi nhận tài chính hai lần.
- **Recruitment/Analytics:** dữ liệu ứng viên bảo vệ theo quyền; chuyển sang nhân viên có mapping và lịch sử; chỉ công bố chỉ số khi đủ dữ liệu và định nghĩa.

## 10. Quy tắc frontend

### 10.1. Tổ chức và tích hợp

- Chạy qua HTTP; giữ thứ tự tải partial/script trong bootstrap và xử lý lỗi tải có thể thử lại.
- Module hóa theo nghiệp vụ; tránh sửa đồng thời các file lớn và khai báo global trùng tên. Shell, route, API client và State chung phải có người điều phối.
- API client tập trung base URL, header xác thực, parse lỗi, 401/403/409 và hủy request khi cần; không hardcode localhost từng màn hình.
- State là cache từ API; mutation cập nhật bằng response hoặc refetch. Không fallback im lặng sang dữ liệu demo khi server lỗi.
- Không lưu dữ liệu nghiệp vụ độc lập trong localStorage; chỉ dùng cho tùy chọn giao diện không quan trọng theo quy ước.
- Không nhúng credential DB/secret server vào bundle; frontend không truy cập database trực tiếp.

### 10.2. Hiển thị và tương tác

- Mọi màn hình có loading, empty, error và success; thông báo rõ hành động có thể làm tiếp.
- Chống double-submit và request cũ ghi đè kết quả mới; dữ liệu chưa lưu phải được cảnh báo khi rời trang khi cần.
- Validate input để phản hồi nhanh nhưng luôn xử lý lỗi server. Không tự đổi trạng thái thành công trước khi API xác nhận, trừ khi có cơ chế hoàn tác rõ ràng.
- Xử lý 401 bằng quy trình hết phiên; 403 bằng thông báo thiếu quyền; 409 bằng tải lại/hiển thị xung đột, không tự ghi đè.
- Ẩn/hiện nút theo quyền để hỗ trợ trải nghiệm; backend vẫn quyết định quyền cuối cùng.
- Render input bằng `textContent` hoặc escape đúng ngữ cảnh; không gán HTML chưa kiểm soát từ tên, ghi chú, lý do hoặc lỗi API.
- Hiển thị tiếng Việt thống nhất, đơn vị giờ/phút rõ ràng, ngày/giờ theo múi giờ nghiệp vụ; không dùng màu làm tín hiệu duy nhất.
- Hành động công bố, mở lại kỳ, override và thay cấu hình phải hiển thị tác động, xác nhận và lý do theo quy trình.

### 10.3. Responsive và khả năng truy cập

- Kiểm thử CSS viewport **320, 360, 390, 768, 1024, 1280 px**; không lấy độ phân giải vật lý thay viewport.
- Kiểm tra thêm một cấu hình Windows scale khác 100%; deviceScaleFactor kiểm tra riêng khi dùng tự động hóa.
- Không tràn ngang toàn trang ngoài ý muốn; bảng có vùng cuộn rõ, nút chính không bị che, modal nằm trong khung nhìn.
- Form có label và lỗi gắn đúng trường; dùng bàn phím được; dialog quản lý focus/Escape/khôi phục focus hợp lý.
- Đăng nhập, điều hướng, bảng, đăng ký, lịch và modal đều phải thao tác được trên màn hình hẹp.

## 11. Cấu hình, triển khai và vận hành

- Dùng profile/cấu hình môi trường tách dev/test/production; secret lấy từ môi trường hoặc hệ thống quản lý secret, không commit.
- `.env` theo cú pháp Java properties `KEY=value`; `.env.example` chỉ có mẫu an toàn. Không chia sẻ credential qua source hoặc ảnh chụp log.
- Cấu hình kết nối gồm `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_TRUST_SERVER_CERTIFICATE`; JWT gồm `JWT_SECRET`, `JWT_EXPIRATION_MS`.
- Backend dev dùng port 8081 theo cấu hình; frontend tĩnh có thể dùng 8000. Khi khác origin phải cấu hình CORS/proxy; nếu cùng origin phải phục vụ đúng tài nguyên tĩnh và quyền truy cập.
- Production dùng TLS và chứng chỉ phù hợp; không sao chép tùy chọn tin chứng chỉ dev một cách mặc định.
- Tài khoản ứng dụng chỉ có quyền DB cần thiết; quản lý quyền chạy migration theo môi trường triển khai.
- Log có mức độ và traceId, đủ tra lỗi nhưng không chứa password/token hoặc dữ liệu cá nhân không cần thiết.
- Theo dõi health, lỗi API, lỗi DB, tác vụ nhắc hạn và sự kiện thất bại; tác vụ nền phải có chính sách retry, chống chạy trùng khi có nhiều instance.
- Backup theo chính sách lưu giữ; kiểm tra phục hồi định kỳ. Thử migration/restore ở môi trường tách biệt trước khi áp dụng production.
- Lưu phiên bản triển khai và migration liên quan; có kế hoạch rollback ứng dụng tương thích dữ liệu hoặc phục hồi khi cần.

Lệnh phát triển từ thư mục gốc:

```powershell
mvn spring-boot:run
```

Chạy frontend tĩnh ở terminal khác khi dùng mô hình tách server:

```powershell
py -m http.server 8000
```

## 12. Kiểm thử

- Mỗi người viết và chạy test cho nghiệp vụ mình; Bình điều phối E2E/responsive và bằng chứng nghiệm thu chung.
- Test backend gồm service/ràng buộc, API/security và integration SQL Server; mock chỉ dùng ở lớp phù hợp, không thay nghiệm thu luồng thật.
- Test H2 không thay kiểm tra SQL Server. Profile test tắt Flyway thì phải kiểm tra migration riêng trên SQL Server test.
- Không chạy test thay đổi dữ liệu trên production; fixture độc lập, có thể lặp, không phụ thuộc thời gian hiện tại hoặc dữ liệu cá nhân thật.
- Test tối thiểu: input sai, quyền sai, trạng thái sai, ranh giới deadline, fixed/percent/thiếu quy tắc, ca đêm, trùng ca/nghỉ tối thiểu, phân công cùng lần chạy, cập nhật đồng thời, rollback, retry và dedup.
- E2E bao phủ Admin và Employee: login → đăng ký → phân lịch → duyệt/công bố → đổi/nhận ca hoặc nghỉ → chấm công/sửa công → báo cáo.
- Kiểm tra dữ liệu sau refresh, logout/login và trình duyệt khác; trạng thái loading/empty/lỗi mạng/401/403/409 phải xử lý đúng.
- Smoke theo đủ viewport và scale, giữ khả năng thao tác bàn phím; dùng profile browser test riêng nếu suite thay dữ liệu cục bộ.
- AC-01…AC-14 trong tài liệu phạm vi phải có kết quả và bằng chứng; không ghi “đã kiểm thử” khi chưa chạy.

Lệnh chạy test backend:

```powershell
mvn test
```

## 13. Phối hợp nhóm, Git và review

- Thực hiện theo chủ sở hữu module trong task_v1.1; người làm module chịu cả schema, API, UI, test và tài liệu.
- Chốt DTO/schema/trạng thái/scope/quyền và fixture sớm để làm song song; mock đúng hợp đồng phải được thay bằng API thật trước nghiệm thu.
- Không sửa file/module của người khác ngoài phạm vi mà không phối hợp; thay phần chung qua người điều phối để tránh xung đột.
- Mỗi task dùng nhánh riêng, commit rõ mục đích; tích hợp vào `main` qua Pull Request và quy tắc bảo vệ repository.
- Không force-push nhánh dùng chung hoặc xóa lịch sử của người khác. Giải quyết conflict phải hiểu logic hai phía và chạy lại kiểm tra liên quan.
- PR nêu vấn đề, hành vi sau sửa, API/schema bị ảnh hưởng, migration, cấu hình mới và kiểm thử thực tế; không đưa lịch sử trao đổi không cần thiết.
- Không commit `.env`, password/token, dữ liệu thật, log hoặc output build `target/`.
- Review tập trung tính đúng nghiệp vụ, quyền, tính toàn vẹn dữ liệu, tương thích API/migration, đồng thời và khả năng vận hành; không chỉ xem giao diện.

## 14. Điều kiện hoàn thành công việc

Một task chỉ hoàn thành khi:

1. Chức năng đáp ứng phạm vi và trạng thái nghiệp vụ đã chốt.
2. Database/migration, API, giao diện và quyền hoạt động xuyên hệ thống.
3. Backend thực thi đầy đủ validation, giao dịch, xử lý đồng thời và audit/sự kiện liên quan.
4. Dữ liệu bền vững, thống kê nhất quán; không có nguồn nghiệp vụ độc lập ở client.
5. Kiểm thử phù hợp đạt; nêu rõ phần chưa kiểm chứng nếu còn giới hạn môi trường.
6. Tài liệu API/cấu hình/hướng dẫn sử dụng cập nhật; PR được review theo quy trình.

Hoàn tất V1 khi luồng vận hành cốt lõi và AC-01…14 đạt. Hoàn tất toàn bộ HRM khi các module mở rộng đã được chốt chi tiết, triển khai và nghiệm thu theo phân công; không đồng nhất hai mốc này.
