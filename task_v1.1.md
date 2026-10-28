# Phân công phát triển hệ thống HRM — task v1.1

Ngày đối chiếu: 28/10/2026. Thành viên: **Anh, Tuấn, Tường, Bảo, Bình**.

Nguồn yêu cầu: [Ban_chuc_nang_HRM_V1_1.md](./Ban_chuc_nang_HRM_V1_1.md), đối chiếu trực tiếp mã Java, JavaScript, migration và bộ kiểm thử hiện có.

## 1. Phạm vi và kết quả kiểm tra dự án

**Mục tiêu phân công:** cả 5 người phát triển song song theo luồng nghiệp vụ, mỗi người làm database, API, giao diện, kiểm thử và tài liệu của phần được giao. Không chia một người chỉ làm backend và một người chỉ làm frontend, vì sẽ tạo điểm chờ cho toàn đội.

Tài liệu chức năng mang phiên bản **1.1**, nhưng phạm vi phát hành cốt lõi trong tài liệu vẫn gọi là **V1.0**. File này dùng hai mốc:

- **Mốc A — hoàn tất V1 vận hành:** toàn bộ yêu cầu bắt buộc ở mục 5, 7, 8, 9 và các sửa lỗi mục 2 của tài liệu nguồn. Đưa Open Shift vào kế hoạch sau các ràng buộc cốt lõi.
- **Mốc B — hoàn tất bản đồ HRM:** toàn bộ module mở rộng ở mục 4 và 6. Vẫn giao người cụ thể để không bỏ sót tính năng; chỉ triển khai sau khi mốc A ổn định và quy tắc chi tiết của từng module được chốt. Không coi Payroll/LMS đầy đủ là điều kiện nghiệm thu V1.

### Hiện trạng xác minh từ mã nguồn

| Khu vực | Đã có | Phần còn thiếu/phải sửa |
|---|---|---|
| Nền tảng | Maven, Java 21, Spring Boot 3.5.6, JDBC, SQL Server, Flyway, Spring Security, JWT | Hoàn thiện API nghiệp vụ, quyền theo thao tác và phạm vi dữ liệu; cấu hình chạy frontend/API thống nhất |
| Xác thực | `api/auth`, BCrypt, JWT; kiểm tra active khi đăng nhập | `js/app.js::_doLogin` vẫn so username/password từ State; chưa nối đăng nhập thật. Logout API hiện chỉ xác nhận bỏ token phía client, không thu hồi JWT |
| Nhân viên/Level | `users`, `levels` ở migration V1; API đọc Level; giao diện quản lý demo | CRUD nhân viên/Level đầy đủ; employeeType, tổ chức, quản lý trực tiếp, kỹ năng/chứng chỉ và quyền theo phạm vi |
| Kỳ/thời gian rảnh | V5 có periods, availability và bảng con; UI ở `admin2.js`, `user.js` | Chưa có controller/service/repository nghiệp vụ tương ứng; chưa thực thi hạn chót ở backend; trạng thái kỳ DB mới có draft/not_open/open/locked |
| Ca/phân công | V5 có shifts, requirements, assignments; `scheduler.js` chạy thuật toán tại trình duyệt | Chuyển nghiệp vụ lên server; phiên bản/phương án lịch, duyệt/công bố, giao dịch và chống cập nhật đồng thời |
| Ràng buộc | `validation.js` có kiểm tra giờ ngày/tuần, trùng ca, Level và nghỉ giữa ca | Chưa kiểm tra trần giờ theo kỳ trong `checkHoursLimit`; nghỉ giữa ca mới là warning. Bộ kiểm tra đọc State nên chưa bao quát đầy đủ phân công vừa sinh trong một lần chạy |
| Quy tắc | V5 có schedule_rules, level targets/substitutions; UI cấu hình demo | Có seed tham số cố định, chưa có xác nhận kích hoạt; thiếu quy tắc theo phạm vi, fixed/percent, xem trước tác động và lịch sử phiên bản |
| Dữ liệu/lịch sử | `storage.js`, `state.js` dùng localStorage; V5 có bảng history | Bảng có sẵn không có nghĩa nghiệp vụ đã chạy trên DB; frontend vẫn đọc/ghi dữ liệu local. Log demo bị giới hạn 500 bản ghi, không dùng làm audit chuẩn |
| Yêu cầu/chấm công/nghỉ/thông báo | Chưa có implementation chuyên biệt trong cây mã đã kiểm tra | Xây mới luồng, bảng, API, giao diện và tích hợp |
| Kiểm thử | Java test cho auth, Level, health, BCrypt; browser smoke dùng iframe và dữ liệu localStorage | Chưa có test nghiệp vụ backend/E2E thật. Smoke hiện dùng 320/375/768/1024/1440, thiếu 360/390/1280 theo yêu cầu |
| Tài liệu | Có `Docs/*` và đặc tả hệ thống hiện tại | Một số mô tả trong `Docs/testing.md` chưa phản ánh backend hiện có; cập nhật theo kết quả thực tế |

**Giới hạn đánh giá:** đây là rà soát tĩnh mã nguồn và cấu trúc kiểm thử; chưa chạy ứng dụng, SQL Server hay test suite trong lần lập kế hoạch này. Không kết luận các tính năng hiện có đã đạt nghiệm thu.

## 2. Tổng quan phân công

| Thành viên | Trách nhiệm chính mốc A | Trách nhiệm mở rộng mốc B |
|---|---|---|
| **Anh** | Nền tảng, đăng nhập thật, phân quyền, hồ sơ nhân viên/tổ chức, hạ tầng audit và API client chung | Hồ sơ HR nâng cao, hợp đồng, tuyển dụng, onboarding/offboarding, quản trị hệ thống |
| **Tuấn** | Kỳ đăng ký, thời gian rảnh, cấu hình quy tắc và tính quy tắc có hiệu lực | LMS, kỹ năng, chứng chỉ và liên kết năng lực với lịch |
| **Tường** | Ca làm, thuật toán backend, kiểm tra phân công dùng chung, lịch nháp/duyệt/công bố/phiên bản | Tối ưu lịch, chi phí dự kiến, định biên và dự báo thiếu người |
| **Bảo** | Đổi/nhận/thay ca, nghỉ phép, thông báo và sự kiện nghiệp vụ | Giao tiếp nội bộ, email/kênh mở rộng, hiệu suất/KPI |
| **Bình** | Chấm công/sửa công, Dashboard, báo cáo; điều phối kiểm thử tích hợp và responsive | Payroll, phúc lợi/thưởng, HR Analytics và các báo cáo đa phân hệ |

Mỗi thành viên chịu trách nhiệm sửa lỗi và test cho module mình; Bình điều phối bộ kiểm thử chung, không nhận toàn bộ công việc QA của đội. Độ lớn công việc không bằng số module: bộ lập lịch và nền tảng được phân ít module hơn vì có nhiều ràng buộc xuyên hệ thống.

## 3. Công việc chi tiết mốc A

### 3.1. Anh — nền tảng, tài khoản và hồ sơ nhân viên

- **A-01 — Chốt hợp đồng tích hợp:** cùng 4 thành viên thống nhất schema/ERD, ID, trạng thái, API/DTO, phân trang, bộ lọc, lỗi API, múi giờ và version chống ghi đè. Lập ma trận quyền Admin/Employee và các quyền quản lý cần dùng; để mở rộng Owner/HR/Scheduler/Attendance/Payroll/Learning/Department Manager.
- **A-02 — Xác thực thật:** nối UI login/logout với API; cung cấp thông tin tài khoản hiện tại, xử lý token hết hạn/401/403; đổi mật khẩu qua backend, bỏ password rõ và chuyển tài khoản demo khỏi luồng thật. Khóa tài khoản phải ngăn tiếp tục sử dụng API dù token cũ còn hạn; định nghĩa rõ cơ chế vô hiệu hóa phiên và chính sách logout.
- **A-03 — Phân quyền:** kiểm tra cả role/permission và quyền truy cập đối tượng, chi nhánh, nhân viên. Employee chỉ đọc/sửa tài nguyên của mình theo chính sách; không tin userId/actor do client gửi.
- **A-04 — Nhân viên/tổ chức:** tạo, xem, sửa, khóa/lưu trữ; mã duy nhất; liên hệ, ngày vào làm, trạng thái; employeeType tách Level, chức vụ và role; bộ phận, địa điểm/chi nhánh, nhóm, quản lý trực tiếp. Tìm kiếm, lọc, phân trang. Cung cấp danh mục Level, vị trí, tổ chức cho các module khác.
- **A-05 — Hạ tầng chung:** API client, cấu hình base URL/CORS hoặc cùng origin, xử lý lỗi thống nhất; State chỉ làm cache dữ liệu API, có cơ chế tải lại/hủy cache sau mutation. Tách seed demo khỏi chạy thật; xây service audit và API tra cứu theo quyền với actor, thời gian, before/after, reason, entity/version.
- **A-06 — Vận hành:** cấu hình môi trường không đưa bí mật vào repo; hướng dẫn setup, migration, backup/restore và kiểm tra phục hồi; health/log lỗi tích hợp. Tổng hợp quy trình tích hợp, không tự viết thay migration của mọi người.

**Kế thừa:** `security/`, `api/auth/`, `domain/user/`, `api/levels/`, phần auth/shell của `js/app.js`, phần nhân viên/Level trong `js/admin.js`, `js/storage.js`, `js/state.js`.

**Đầu ra/nghiệm thu:** đăng nhập thật và đổi mật khẩu hoạt động; tài khoản khóa và truy cập trái phép bị chặn ở API; hồ sơ giữ nguyên trên trình duyệt khác; không còn đăng nhập bằng mật khẩu demo trong dữ liệu client; audit API không lộ dữ liệu ngoài quyền. Phụ trách chính AC-10, AC-11 và hạ tầng AC-12.

### 3.2. Tuấn — kỳ, đăng ký và quy tắc

- **TU-01 — Kỳ đăng ký:** CRUD và chuyển trạng thái hợp lệ; ngày mở/hạn, autoLock, đối tượng áp dụng, ngày yêu cầu/miễn/đóng. Thống nhất với Tường trạng thái kỳ và trạng thái phương án lịch, không gộp hai vòng đời làm một.
- **TU-02 — Hạn chót backend:** dùng Clock có thể giả lập và múi giờ nghiệp vụ `Asia/Ho_Chi_Minh`; lưu thời điểm theo quy ước chung. Tự khóa qua tác vụ nền và kiểm tra lại trong mọi API đọc/ghi đăng ký liên quan. Kiểm tra cả create/update/save draft/submit trong giao dịch, kể cả form mở trước hạn. autoLock=false chỉ đóng thủ công và phải có nhãn rõ.
- **TU-03 — Mở lại kỳ:** quyền riêng, xác nhận, lý do, audit, chính sách hạn mới rõ ràng; giữ đăng ký cũ. Không để cờ allowEditAfterDeadline cũ vô hiệu hóa quy tắc autoLock.
- **TU-04 — Thời gian rảnh:** lịch tuần/ngày, nhiều khoảng giờ, rảnh/không thể làm, lưu nháp/gửi/sửa theo chính sách; validate khoảng giờ, ngày trong kỳ và đối tượng áp dụng. Admin xem đã/chưa gửi và tổng hợp khả năng đáp ứng.
- **TU-05 — Cài đặt quy tắc:** mục tiêu giờ; trần fixed hoặc percent; ngưỡng cảnh báo; nghỉ giữa ca; ngày liên tiếp; điều kiện/Level thay thế; ưu tiên và trọng số; hạn đăng ký; nhắc hạn; duyệt/thông báo; cách hiển thị/làm tròn báo cáo. UI giải thích đơn vị, giá trị và phạm vi tác động.
- **TU-06 — Quy tắc có hiệu lực:** cấu hình công ty/chi nhánh/employeeType/nhóm/cá nhân; cùng đội chốt thứ tự ưu tiên và xử lý trường hợp trùng phạm vi. Trả về giá trị hiệu lực và nguồn áp dụng; ưu tiên ràng buộc bắt buộc, hợp đồng/chứng chỉ trước ưu tiên thuật toán. Thiếu cấu hình phải trả checklist, không tự gán 120%, 125% hoặc số nghiệp vụ tùy ý.
- **TU-07 — Áp dụng cấu hình:** preview tác động, xác nhận, version/ngày hiệu lực, lịch sử before/after và lý do; không tự sửa lịch công bố/kỳ khóa. Các seed cũ chỉ được dùng như mẫu đề xuất sau xác nhận Admin.

**Kế thừa:** phần kỳ/tổng hợp đăng ký trong `js/admin2.js`, phần đăng ký trong `js/user.js`, phần Rules trong `js/admin3.js`, các bảng period/availability/rules của V5.

**Đầu ra/nghiệm thu:** API chặn đúng hạn kể cả request đến muộn; thử autoLock bật/tắt và mở lại; cấu hình fixed/percent hiển thị kết quả tính tự động; có test xung đột phạm vi/thiếu cấu hình/giữ nguyên lịch công bố. Phụ trách AC-04, AC-05, AC-06, AC-13; phối hợp AC-01, AC-14.

### 3.3. Tường — ca và toàn bộ vòng đời lịch

- **TW-01 — Ca:** CRUD, sao chép/lặp/tạo hàng loạt; ca đêm, giờ nghỉ, địa điểm, min/max, Level/chức vụ/kỹ năng/chứng chỉ; yêu cầu người bắt buộc và người ưu tiên phải qua kiểm tra điều kiện. Hủy/bổ sung ca khẩn cấp có lý do/lịch sử.
- **TW-02 — Dịch vụ kiểm tra phân công chung:** dùng cho tự động, thủ công, công bố, đổi ca, nhận ca và nghỉ phép tác động lịch. Kiểm tra trùng giờ, không thể làm, active/địa điểm, Level thay thế đúng chiều, kỹ năng/chứng chỉ còn hiệu lực, giờ ngày/tuần/kỳ, nghỉ tối thiểu, ngày liên tiếp và giới hạn hợp đồng/chính sách bắt buộc.
- **TW-03 — Thuật toán backend:** tách hard constraints và soft preferences; đọc quy tắc hiệu lực từ Tuấn; kiểm tra trước từng lần chốt phân công, tính cả phân công đã lưu và vừa sinh. Trừ thời gian nghỉ theo chính sách; xử lý ca qua ngày/tuần/kỳ; loại phân công hủy khỏi tổng giờ; không đếm trùng lịch được thay thế khi chạy lại phương án.
- **TW-04 — Phương án:** dry-run không sửa lịch chính thức; lưu nháp, chạy lại và so sánh; thống kê đủ/thiếu, lý do loại ứng viên và vì sao chọn. Không bù người bằng cách vượt trần hoặc vi phạm nghỉ. Thiếu thông số bắt buộc phải báo đúng nhân viên/thông số.
- **TW-05 — Lịch và công bố:** lịch ngày/tuần/tháng, lọc kỳ/chi nhánh/nhóm/nhân viên/Level/trạng thái; chỉnh thủ công hoặc kéo thả với cùng validation. Luồng nháp → chờ duyệt → duyệt → công bố, phiên bản và lịch sử. Employee chỉ thấy lịch chính thức của mình; lưu đã xem/xác nhận lịch.
- **TW-06 — Ngoại lệ và đồng thời:** quyền riêng + preview vi phạm + xác nhận + lý do + audit cho ngoại lệ được phép; không override ràng buộc bắt buộc. Optimistic locking/giao dịch tránh hai người sửa cùng phiên bản; công bố và thay phân công phát sự kiện cho Bảo sau khi commit.
- **TW-07 — Dữ liệu báo cáo:** API truy vấn phiên bản/phương án và tổng giờ/phân công có ngữ cảnh chuẩn cho Bình; tách trạng thái workflow với độ phủ full/understaffed.

**Kế thừa:** `js/scheduler.js`, `js/validation.js` phần SafetyCheck, phần ca/lịch trong `js/admin2.js`, phần AutoSchedule/Publish trong `js/admin3.js`, lịch cá nhân trong `js/user.js`; bảng shifts/assignments và yêu cầu ca của V5.

**Đầu ra/nghiệm thu:** test 60 giờ × 125% = 75 giờ và không vượt; tất cả ứng viên chạm trần thì giữ thiếu người; test nghỉ tối thiểu phải chặn, ca đêm và các phân công cùng lần chạy; chạy lại không nhân đôi giờ; nháp không lộ thành lịch chính thức. Phụ trách AC-01, AC-02; phối hợp AC-08, AC-12, AC-14.

### 3.4. Bảo — yêu cầu phát sinh, nghỉ phép và thông báo

- **BA-01 — Đổi/thay ca:** tạo yêu cầu từ ca hợp lệ, người nhận chấp thuận, người có quyền duyệt/từ chối/hủy; lưu ca gốc, người gửi/nhận/duyệt, thời điểm và lý do. Trong lúc chờ, lịch chính thức không đổi.
- **BA-02 — Duyệt an toàn:** gọi dịch vụ validation của Tường tại thời điểm duyệt, không chỉ lúc gửi. Kiểm tra phiên bản lịch đã thay đổi; cập nhật lịch/phiên bản/audit/sự kiện trong giao dịch phù hợp; xác định duy nhất người chịu trách nhiệm cuối. Yêu cầu duyệt lặp phải không tạo thay đổi lặp.
- **BA-03 — Open Shift:** Admin công bố nhu cầu/điều kiện/hạn nhận; nhân viên đăng ký; chọn/duyệt theo quy tắc; kiểm tra còn chỗ trong giao dịch để hai yêu cầu không cùng chiếm chỗ cuối. Làm sau BA-01/02 và ràng buộc cốt lõi.
- **BA-04 — Nghỉ phép cơ bản:** loại nghỉ, quy trình/quyền duyệt; gửi đơn/lý do/tài liệu theo chính sách; duyệt/từ chối/hủy; cảnh báo độ phủ, cập nhật ảnh hưởng lên lịch sau duyệt qua dịch vụ Tường. Ghi nhận vắng đột xuất, không mặc định Part-time mất quyền nghỉ.
- **BA-05 — Thông báo in-app:** danh sách, nội dung/loại/thời gian, đọc/chưa đọc, đánh dấu đọc, liên kết nghiệp vụ và phân trang; đúng người nhận, đúng phạm vi. Hạn đăng ký cấu hình được, nhắc người chưa gửi; lịch công bố/thay đổi, được phân ca và yêu cầu cần xử lý.
- **BA-06 — Phát sự kiện tin cậy:** thống nhất eventId/entity/version/recipient; chống gửi trùng, retry sau lỗi, không phát thông báo cho giao dịch rollback. Có thể dùng outbox cùng DB để tránh mất sự kiện sau commit; theo dõi trạng thái gửi và lỗi.

**Khu vực sở hữu:** module mới `requests`, `leave`, `notifications`, `open-shifts`; giao diện mới cho Employee/Manager, icon thông báo trên shell qua điểm mở rộng do Anh cung cấp.

**Đầu ra/nghiệm thu:** chờ duyệt không đổi lịch; duyệt kiểm tra lại xung đột/trần giờ/chứng chỉ; duyệt hai lần không đổi hai lần; nghỉ được duyệt tạo cảnh báo thiếu người; sự kiện gửi đúng người và không trùng. Phụ trách AC-08; phối hợp AC-12.

### 3.5. Bình — chấm công, báo cáo và kiểm thử tích hợp

- **BI-01 — Chấm công cơ bản:** check-in/out thật, gắn nhân viên/ca, kiểm tra thời gian hợp lệ, không tạo lượt đang mở trùng; giờ nghỉ và ca đêm. Nhân viên xem công cá nhân, quản lý xem trong phạm vi được cấp quyền.
- **BI-02 — Sửa/duyệt công:** yêu cầu bổ sung hoặc sửa, quản lý duyệt/từ chối và lý do; giữ bản gốc, before/after, trạng thái duyệt. Khóa kỳ công và quy trình điều chỉnh có kiểm soát; theo dõi muộn/vắng/thiếu check-out, tăng ca/ca đêm theo chính sách đã cấu hình.
- **BI-03 — Dashboard và thống kê chung:** API tổng hợp theo kỳ, phạm vi tổ chức, bộ lọc, trạng thái và phiên bản/phương án. Nhân viên active, kỳ/hạn, ca hôm nay/đã công bố/thiếu người/chưa xác nhận, nháp đang chọn, người đang làm/vắng/chưa check-in, yêu cầu chờ duyệt. Nhãn và timestamp cập nhật rõ ràng.
- **BI-04 — Báo cáo:** phân bổ giờ, gần/vượt giới hạn, ca thiếu và lý do; lịch dự kiến so với giờ thực tế/giờ công đã duyệt, đơn chờ xử lý. Dùng cùng định nghĩa/filter cho thẻ, bảng và biểu đồ; xuất CSV/Excel theo quyền, không lộ dữ liệu ngoài phạm vi.
- **BI-05 — Công thức:** tổng đã xếp, mục tiêu, chênh lệch, tỷ lệ = đã xếp / mục tiêu × 100. Với 808/740 hiển thị vượt 68 giờ và khoảng 109% khi làm tròn số nguyên. Mẫu số 0/thiếu dữ liệu hiển thị “Chưa đủ dữ liệu”. Không dùng giờ lịch làm giờ công thực tế.
- **BI-06 — Responsive/E2E:** cập nhật smoke theo CSS viewport 320, 360, 390, 768, 1024, 1280; thử một cấu hình Windows scale khác 100% và deviceScaleFactor riêng nếu tự động hóa. Bao phủ login, navigation, bảng, form đăng ký, lịch, modal; không tràn trang/nút che/modal vượt khung. Duy trì keyboard/focus hiện có.
- **BI-07 — Điều phối kiểm thử:** fixture dùng chung, test API trực tiếp, E2E Admin/Employee, refresh/đăng nhập lại/trình duyệt khác, API lỗi/loading/empty/403, đồng thời khi duyệt/đổi ca/check-in. Cập nhật `Docs/testing.md` và bảng bằng chứng AC; mỗi người tự sửa lỗi module mình.

**Kế thừa:** Dashboard trong `js/admin.js`, tổng quan Employee trong `js/user.js`, thống kê kết quả từ `js/admin3.js`, `tests/browser-smoke.*`, `css/styles.css`; tạo module attendance/reports và test E2E phù hợp.

**Đầu ra/nghiệm thu:** công thực tế bền vững và có phê duyệt/lịch sử; Dashboard và phân lịch bằng nhau khi cùng ngữ cảnh; báo cáo không gộp nháp với công bố; đủ viewport và bằng chứng scale. Phụ trách AC-03, AC-07, AC-09; phối hợp AC-10, AC-14.

## 4. Cách triển khai song song và giảm xung đột

### 4.1. Điểm tích hợp phải chốt trước khi làm sâu

Các API dưới đây là **đề xuất cần triển khai**, không phải API đang tồn tại. Anh tổng hợp hợp đồng, người sở hữu module quyết định chi tiết cùng bên sử dụng.

| Hợp đồng | Chủ sở hữu | Bên dùng | Nội dung tối thiểu |
|---|---|---|---|
| Auth/API client/audit | Anh | Tất cả | current user, permissions, request errors, audit actor lấy server; lỗi 401/403/409/validation thống nhất |
| `/api/employees`, danh mục tổ chức | Anh | Tuấn/Tường/Bảo/Bình | employeeType, trạng thái, Level, địa điểm, quản lý, kỹ năng/chứng chỉ theo schema thống nhất |
| `/api/periods`, `/api/availability` | Tuấn | Tường/Bảo/Bình | kỳ/trạng thái/hạn, đăng ký đã gửi và khoảng không thể làm; API chuyển trạng thái |
| `/api/rules`, effective rules | Tuấn | Tường/Bảo/Bình | giá trị, phạm vi, version, nguồn áp dụng, giới hạn suy ra, missing configuration; preview/confirm |
| `/api/shifts`, `/api/schedule-plans` | Tường | Bảo/Bình | ca, phương án, version, assignments, coverage/reasons; run/approve/publish và lịch cá nhân |
| Assignment validation/update service | Tường | Bảo | validation dùng lại nội bộ backend; sửa lịch qua service có giao dịch, không cập nhật bảng assignment tùy tiện |
| `/api/requests`, `/api/leave`, `/api/open-shifts` | Bảo | Bình/Tường | trạng thái, quyền duyệt, tác động lịch; bộ đếm pending theo phạm vi |
| Event contract + notifications | Bảo | Tất cả | eventId/type/entity/version/recipient/time, dedup, sau commit, trạng thái gửi |
| `/api/attendance`, `/api/reports` | Bình | Dashboard/Payroll sau này | planned/actual/approved tách biệt, context/filter/version, tổng giờ và quy tắc làm tròn |

- Thống nhất UTC/offset cho thời điểm và `Asia/Ho_Chi_Minh` khi diễn giải ngày/giờ nghiệp vụ; không dùng múi giờ máy người dùng để quyết định hết hạn.
- Chốt thứ tự ưu tiên scope quy tắc, thời điểm áp dụng, cách chia giờ ca đêm qua ngày/tuần/kỳ, trạng thái nghỉ/đổi ca và giới hạn override; ghi vào tài liệu hợp đồng trước khi test tích hợp.
- Tường và Bảo thống nhất mutation lịch sau đổi ca/nghỉ được duyệt; Tuấn không sửa workflow lịch bằng cách trực tiếp đổi status kỳ.

### 4.2. Quyền sở hữu file và migration

- Anh giữ phần auth, shell, bootstrap, API client và nền tảng State; cung cấp đăng ký route/menu/module để mọi người gắn màn hình riêng.
- Tách các khối hiện có trong `admin.js`, `admin2.js`, `admin3.js`, `user.js` sang module nghiệp vụ trước khi nhiều người cùng sửa. Ví dụ `employees`, `periods`, `rules`, `scheduling`, `requests`, `attendance`, `reports`, `notifications`. Đường dẫn mới là đề xuất, không mô tả cấu trúc đã có.
- Tuấn sở hữu kỳ/availability/rules; Tường sở hữu shifts/plans/assignments; Bảo sở hữu request/leave/notification; Bình sở hữu attendance/report/dashboard. Mọi thay đổi shell/State chung gửi cho Anh tích hợp; CSS chung phối hợp với Bình, mỗi module ưu tiên class riêng.
- Không sửa các migration V1–V5 đã áp dụng. Thêm migration mới; mỗi đợt merge Anh cấp số kế tiếp duy nhất, theo thứ tự phụ thuộc FK. Không tự lấy cùng một số Flyway giữa hai nhánh.
- Mỗi người viết repository/service/controller, DTO, validation, UI và test module mình. Các migration module khác chỉ tham chiếu schema chung đã chốt; tránh để Anh thành người viết toàn bộ DB/API.
- UI có thể làm song song bằng mock đúng hợp đồng; thuật toán dùng fixture/repository stub; báo cáo dùng fixture có nhãn nháp/công bố. Mock phải thay bằng API thật trước nghiệm thu.

### 4.3. Nhịp bàn giao

| Đợt | Anh | Tuấn | Tường | Bảo | Bình | Điều kiện qua đợt |
|---|---|---|---|---|---|---|
| 0 — hợp đồng/nền khung | Auth/client/audit contract, điều phối tách file | Kỳ/rules DTO và clock | Plan/assignment DTO, validator contract | Request/event DTO | Metric/filter DTO, fixture và viewport | ERD, quyền, trạng thái, API và chủ sở hữu file được thống nhất; mọi người có mock để bắt đầu |
| 1 — dữ liệu thật | Login, employee/org, quyền/audit | Kỳ/availability/hạn backend, rules cơ bản | Shift CRUD, validator, thuật toán fixture | Request/leave/notification CRUD và UI | Attendance CRUD, report fixture, responsive | Module tự đọc/ghi DB và có test; thay mock theo API sẵn có |
| 2 — vận hành lịch | Khóa tài khoản, scope quyền, bỏ dữ liệu local | Rules có hiệu lực, preview/xác nhận, nhắc hạn | Nháp/duyệt/công bố/phiên bản, giới hạn đầy đủ | Duyệt đổi/thay ca, nghỉ tác động lịch, notification thật | Dashboard/report API thật, duyệt/sửa/khóa công | Luồng Employee đăng ký → Admin xếp/duyệt/công bố → đổi/nghỉ → chấm công chạy xuyên hệ thống |
| 3 — nghiệm thu V1 | Quyền/dữ liệu bền vững/triển khai | Deadline/rules edge cases | Ràng buộc/đồng thời/phiên bản | Open Shift, dedup/retry, duyệt đồng thời | AC/E2E/responsive và báo cáo lỗi | AC-01…14 đạt, không còn dữ liệu nghiệp vụ chuẩn ở localStorage, Open Shift được tích hợp theo kế hoạch |
| 4 — HRM mở rộng | HR/hợp đồng/tuyển dụng | Learning & Skills | Lịch nâng cao/dự báo | Performance/giao tiếp | Payroll/phúc lợi/Analytics | Nghiệm thu theo từng module mốc B sau khi quy tắc chi tiết đã chốt |

Đây là thứ tự theo phụ thuộc, chưa phải cam kết ngày hoàn thành. Sau đợt 0, mỗi người ước lượng task theo năng lực/thời gian thực tế; cân lại các hạng mục độc lập nếu tải lệch, không chuyển người giữa chừng ở phần dùng chung đang tích hợp.

## 5. Mốc B — phân công toàn bộ module HRM mở rộng

Mỗi nhóm dưới đây bao gồm schema, API, quyền, UI, audit, báo cáo nguồn dữ liệu và kiểm thử. Không suy diễn công thức lương/quyền lợi từ demo; quy tắc doanh nghiệp/hợp đồng áp dụng phải được chốt trước khi kích hoạt tính toán.

| ID | Chủ trì | Công việc cần hoàn tất | Phụ thuộc/đầu ra tích hợp |
|---|---|---|---|
| HR-01 | Anh | Hồ sơ đầy đủ: liên hệ khẩn cấp, tài liệu đính kèm theo quyền, import Excel/CSV, export, cập nhật hàng loạt, lịch sử chức vụ/lương/bộ phận/hợp đồng, điều chuyển và lưu trữ | Employee core; dùng dữ liệu lịch sử lương của Bình, không tạo nguồn lương thứ hai |
| HR-02 | Anh | Cơ cấu công ty/chi nhánh/địa điểm/bộ phận/nhóm; đa địa điểm, mô tả vị trí, phạm vi quản lý; hồ sơ hợp đồng/phụ lục/ngày hiệu lực/gia hạn/chấm dứt và tài liệu nhạy cảm | Tường bổ sung định biên; Tuấn cung cấp yêu cầu kỹ năng; Bảo nhận sự kiện hết hạn |
| HR-03 | Anh | Tuyển dụng: đề xuất/vị trí tuyển, ứng viên/CV, phỏng vấn/kết quả/offer/ngày nhận; chuyển ứng viên sang nhân viên; onboarding/checklist, nội quy, tài khoản, thiết bị, mentor/thử việc | Tuấn nhận yêu cầu gán khóa hội nhập; Bình nhận số liệu time-to-hire |
| HR-04 | Anh | Offboarding: ngày nghỉ, bàn giao, khóa tài khoản/lưu hồ sơ; phân quyền mở rộng; quản trị cấu hình, audit, backup/restore, giám sát lỗi tích hợp | Bình chốt công/lương, Tường xử lý phân công tương lai; không xóa hồ sơ có lịch sử |
| LS-01 | Tuấn | LMS: khóa/tài liệu/video/bài học/kiểm tra; gán bắt buộc/tự chọn theo nhân viên/vị trí/Level/bộ phận; lộ trình và tiến độ | Nền hồ sơ/quyền của Anh; Bảo gửi nhắc |
| LS-02 | Tuấn | Đào tạo: buổi học/điểm danh/đánh giá, điểm thi, quá hạn, chi phí, học lại; chứng chỉ cấp/hết hạn; báo cáo đào tạo | Bình hiển thị Dashboard/Analytics từ dữ liệu được Tuấn cung cấp |
| LS-03 | Tuấn | Ma trận kỹ năng/mức thành thạo, quản lý đánh giá, yêu cầu theo vị trí/ca/địa điểm; tìm người đủ chuẩn, phát hiện thiếu kỹ năng/gợi ý học | Chứng chỉ cơ bản phục vụ V1 không chờ LMS; kết quả học chỉ đổi điều kiện phân ca sau bước xác nhận phù hợp và tích hợp validator Tường |
| SC-01 | Tường | So sánh phương án nâng cao, cân bằng số ca/giờ và ưu tiên có trọng số; tối ưu chi phí theo dữ liệu đơn giá hiệu lực, không đánh đổi hard constraints | Tuấn cung cấp rules; Bình cung cấp chính sách chi phí/đơn giá |
| SC-02 | Tường | Định biên bộ phận/vị trí/địa điểm, dự báo nguy cơ thiếu người từ nhu cầu/availability/năng lực, vận hành ca khẩn cấp và vắng đột xuất nâng cao | Tổ chức Anh, Skills Tuấn, nghỉ Bảo, attendance Bình |
| CO-01 | Bảo | Nghỉ nâng cao: phép hưởng/đã dùng/còn lại, chính sách theo loại nhân viên/hợp đồng, tài liệu và báo cáo vắng mặt; quy trình duyệt theo cấu hình | Dữ liệu công/hợp đồng, không mặc định loại trừ Part-time |
| CO-02 | Bảo | Giao tiếp nội bộ: gửi công ty/chi nhánh/nhóm, mẫu, lịch sử đọc; nhắc chấm công, khóa học, hợp đồng/chứng chỉ; email và kênh cấu hình, retry/trạng thái gửi/dedup | Event từ tất cả module; triển khai email sau cấu hình dịch vụ và theo dõi lỗi |
| PE-01 | Bảo | KPI theo công việc/bộ phận, mục tiêu/đánh giá định kỳ/phản hồi, chất lượng/độ tin cậy, kế hoạch cải thiện, lịch sử thành tích/khen thưởng/đề xuất thăng cấp/đào tạo | Không xếp Part-time thấp chỉ do ít giờ; Bình quản lý khoản thưởng tài chính, Tuấn nhận đề xuất học |
| PA-01 | Bình | Đơn giá giờ/ngày/tháng, theo vị trí/Level/ngày hiệu lực; phụ cấp/thưởng/khấu trừ; kỳ lương, tính thử/đối soát/duyệt/khóa/điều chỉnh có audit, xuất thanh toán; phiếu lương cá nhân | Chỉ dùng giờ công đã duyệt; hợp đồng Anh, phép Bảo; có dữ liệu kiểm thử công thức trước kích hoạt |
| PA-02 | Bình | Phúc lợi: chính sách thưởng/phụ cấp/chương trình ghi nhận, thành tích/lý do, điều kiện quyền lợi, ngân sách và báo cáo sử dụng | Dùng một nguồn khoản thưởng với Payroll; nhận thành tích từ Bảo |
| AN-01 | Bình | Dashboard theo Owner/HR/quản lý chi nhánh/Scheduler; chi phí tuần/tháng, hợp đồng/chứng chỉ hết hạn, khóa bắt buộc chưa xong, tỷ lệ nghỉ việc và xu hướng nhân sự | Chỉ hiển thị chỉ số khi có dữ liệu nguồn; phân quyền theo vai trò/phạm vi |
| AN-02 | Bình | Báo cáo nhân sự/tuyển dụng, lịch, công, chi phí, đào tạo, hiệu suất, quản trị; bộ lọc thời gian/employeeType/chi nhánh/bộ phận/Level/trạng thái, export theo quyền và phân tích dự báo | Chủ module cung cấp API/định nghĩa nguồn; Bình sở hữu tổng hợp/hiển thị, không sao chép logic nghiệp vụ |

QR/kiosk/xác minh vị trí thuộc mở rộng tùy nhu cầu của module chấm công, giao **Bình** nếu được đưa vào phạm vi chi tiết; không mặc định là yêu cầu V1.

### Đối chiếu đủ 16 module

| Module tài liệu nguồn | Người chịu trách nhiệm | Nơi thực hiện |
|---|---|---|
| 1. Dashboard | Bình | BI-03/04, AN-01 |
| 2. Hồ sơ nhân viên | Anh | A-04, HR-01 |
| 3. Cơ cấu tổ chức | Anh; Tường phụ trách định biên | A-04, HR-02, SC-02 |
| 4. Lịch làm việc | Tường; Tuấn đăng ký/rules, Bảo phát sinh | TU-01…07, TW-01…07, BA-01…03, SC-01/02 |
| 5. Chấm công | Bình | BI-01/02 và mở rộng thiết bị khi cần |
| 6. Nghỉ phép | Bảo | BA-04, CO-01 |
| 7. Payroll | Bình | PA-01 |
| 8. LMS | Tuấn | LS-01/02 |
| 9. Skills | Tuấn | LS-03; Tường dùng kiểm tra ca |
| 10. Performance | Bảo | PE-01 |
| 11. Tuyển dụng/onboarding | Anh | HR-03 |
| 12. Hợp đồng | Anh | HR-02 |
| 13. Phúc lợi/thưởng | Bình | PA-02 |
| 14. Giao tiếp/thông báo | Bảo | BA-05/06, CO-02 |
| 15. Báo cáo/phân tích | Bình | BI-03…05, AN-01/02; từng chủ module cung cấp dữ liệu |
| 16. Phân quyền/cấu hình/kiểm soát | Anh; Tuấn sở hữu quy tắc | A-01…06, TU-05…07, HR-04 |

## 6. Ma trận nghiệm thu V1 và người chịu trách nhiệm

| AC nguồn | Chủ trì | Phối hợp | Bằng chứng cần có |
|---|---|---|---|
| AC-01 trần giờ | Tường | Tuấn | Test mục tiêu 60, percent 125 → trần 75; tự động không vượt; thêm case fixed và thiếu cấu hình |
| AC-02 thiếu người | Tường | Bình | Tất cả ứng viên hết giờ → ca thiếu và có lý do, không tạo phân công vi phạm |
| AC-03 tổng giờ | Bình | Tường/Tuấn | Cùng fixture 808/740 → chênh 68, 109% theo làm tròn; test mẫu số 0 |
| AC-04 hạn chót | Tuấn | Anh | API create/update/submit bị từ chối sau hạn nếu autoLock bật; autoLock tắt theo thao tác thủ công |
| AC-05 form cũ | Tuấn | Bình | Form mở trước hạn, submit sau hạn bị từ chối bằng thời gian server |
| AC-06 mở lại | Tuấn | Anh | API sai quyền bị chặn; đúng quyền phải có xác nhận/lý do, audit và chính sách hạn rõ |
| AC-07 số liệu | Bình | Tường | Dashboard và phương án cùng context cho cùng kết quả; khác status ghi nhãn rõ |
| AC-08 đổi ca | Bảo | Tường | Pending giữ nguyên; approve cập nhật lịch/version/audit/thông báo; test duyệt lặp và xung đột mới |
| AC-09 responsive | Bình | Cả đội | Đủ 6 CSS viewport, các màn hình bắt buộc và một cấu hình scale khác 100%; lưu kết quả |
| AC-10 backend chuẩn | Anh | Cả đội | Tạo/sửa từng loại dữ liệu → refresh, logout/login, trình duyệt khác đọc lại đúng từ API |
| AC-11 phân quyền | Anh | Cả đội | Gọi API trực tiếp, sửa userId/branchId, token cũ của tài khoản khóa đều không vượt quyền |
| AC-12 audit | Anh | Tường/Tuấn/Bảo/Bình | Công bố/sửa phân công/đổi ca/mở kỳ/duyệt công có actor, timestamp, before/after và reason khi cần |
| AC-13 cấu hình | Tuấn | Anh/Tường | Scope/quyền/validation/preview/confirm/history; thiếu cấu hình chặn; không sửa ngầm lịch đã công bố |
| AC-14 tự tính | Tuấn | Bình/Tường | Giới hạn fixed/percent và tỷ lệ báo cáo tự tính, Admin không nhập kết quả suy ra |

## 7. Điều kiện hoàn thành và bàn giao

- Mỗi task có migration tăng thêm nếu cần, API/DTO và quyền được ghi lại, giao diện xử lý loading/empty/error/success, test ràng buộc quan trọng và tài liệu sử dụng.
- Chạy Java test phù hợp bằng Maven; kiểm tra migration trên SQL Server thật vì test H2 không chứng minh tương thích toàn bộ cú pháp/ràng buộc SQL Server. Chạy browser/E2E với backend thật, không chỉ bộ demo localStorage.
- Mutation quan trọng có giao dịch, kiểm tra dữ liệu cũ/version, audit và sự kiện đúng thời điểm; không xóa cứng hồ sơ lịch sử cần lưu.
- Kiểm tra seed hiện tại và chính sách chuyển dữ liệu; không tự động nhập mật khẩu rõ hoặc tin dữ liệu localStorage là dữ liệu chuẩn. Nếu cần chuyển dữ liệu demo, dùng quy trình import có validation/xác nhận riêng.
- Anh phụ trách tích hợp nền tảng và hướng dẫn triển khai; từng chủ module tự tích hợp phần mình; Bình tổng hợp kết quả kiểm thử và lỗi còn mở. Không đánh dấu hoàn tất task chỉ vì có màn hình hoặc có bảng DB.
- **Mốc A hoàn tất** khi các chức năng V1 được giao chạy xuyên hệ thống, AC-01…14 có bằng chứng đạt, dữ liệu bền vững và không còn lỗi chặn vận hành. **Mốc B hoàn tất** khi các hạng mục HR/LS/SC/CO/PE/PA/AN đã được chốt chi tiết, triển khai và nghiệm thu theo module; không báo hoàn tất toàn bộ HRM chỉ dựa vào việc đạt V1.
