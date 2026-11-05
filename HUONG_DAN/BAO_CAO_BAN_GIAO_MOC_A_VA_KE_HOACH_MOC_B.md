# Báo cáo bàn giao mốc A và đề xuất triển khai mốc B

Ngày: 04/11/2026. Người nhận: senior/phụ trách tích hợp. Chuẩn đối chiếu: `task_v1.1.md`, giữ nguyên nội dung tài liệu task.

> Cập nhật 05/11/2026: đã làm rõ luồng chỉ Admin cấp tài khoản, bổ sung xác nhận mật khẩu mới và chặn tự đăng ký ở lớp bảo mật. Đã cấp 5 tài khoản test theo yêu cầu và xác minh đăng nhập/đổi mật khẩu trên frontend. Lượt kiểm thử cho cập nhật này đạt 349 test (`*Test,IntegratedMocAIT`) và browser; đây là phạm vi chạy riêng, không thay con số 355 của lượt nghiệm thu mốc A trước đó. Xem [tài khoản và kiểm tra chức năng](TAI_KHOAN_NHAN_VIEN_TEST.md), `KET_QUA_TAI_KHOAN_NHAN_VIEN.json`; manifest cũ vẫn nhận diện bản nghiệm thu ngày 04/11, không đại diện toàn bộ bản cập nhật 05/11.

> Cập nhật cách chạy: hai `.bat` FE/BE đã được thay bằng **`CHAY_HE_THONG.bat`**, một tiến trình Spring Boot phục vụ giao diện và API cùng cổng 8080, ghi trực tiếp vào `ScheduleManager`. Launcher kiểm tra trạng thái ứng dụng/database trước khi mở browser, dùng lại build hợp lệ, chống chạy trùng bằng mutex và hỗ trợ `--stop`. Đã kiểm tra dừng/khởi động lại, đăng nhập 5 nhân viên, sửa hồ sơ từ UI rồi xác nhận trực tiếp bằng truy vấn SQL và đọc lại từ tài khoản khác. Dữ liệu dùng trong kiểm tra được khôi phục sau đó; không tạo lại database QA. Bằng chứng: `KET_QUA_KHOI_DONG_CHUNG.json`. Node proxy chỉ còn là công cụ kiểm thử phụ, không cần để sử dụng hệ thống.

## 1. Kết quả em bàn giao

Em đã đưa bản tích hợp `4455742` vào nhánh làm việc hiện tại, tiếp nhận phần auth/hồ sơ của nhánh Anh và các sửa local đã có, sau đó sửa các lỗi phát hiện bằng SQL Server/HTTP/browser thật. Người dùng đã yêu cầu commit và đưa bản hiện tại lên `main` với mức hoàn thành tự đánh giá. Chốt **93% theo 13/14 AC đã nghiệm thu**, giữ bước Windows Scale ở trạng thái chờ. SHA nền `4455742` không đại diện toàn bộ bản hoàn thiện: dùng manifest SHA-256 để nhận diện đúng mã nguồn/test đã chạy. Commit/push không thay thế nghiệm thu 100%.

**Phần tự động đã có kết quả đạt:** 355 Java test, gồm unit/controller/wiring và hai bộ SQL Server integration, không failure/error/skip; 20 nhóm kịch bản HTTP; browser Admin/Employee ở sáu viewport, DPR/zoom, dữ liệu sau refresh/logout/login và trình duyệt độc lập; frontend proxy; diễn tập backup/restore SQL Server.

**Chưa đề nghị ký nghiệm thu mốc A 100%:** máy kiểm thử đang có Windows system DPI 96, tương ứng 100%. AC-09 còn yêu cầu thử Windows Display Scale thật khác 100%. DPR 1.25 và CSS zoom 125% đã được thử nhưng không được ghi thay cho kết quả OS scale. Em đã yêu cầu người dùng chuyển cấu hình và xác nhận để chạy tiếp. Cập nhật 05/11/2026: theo yêu cầu mới của người dùng, đã xóa các file review sau khi ghi trạng thái và việc còn thiếu tại mục 3.6 của `task_v1.1.md`; vẫn giữ tài liệu task và hồ sơ bàn giao, không thay đổi kết luận AC-09.

Những con số trên cần đọc cùng `KET_QUA_KIEM_THU.json` và log của lượt cuối. Không dùng log ngày 31/10 hoặc log chạy trước bản sửa để chứng nhận bản bàn giao hiện tại.

## 2. Các chức năng đã có trong bản hiện tại

| Nhóm task | Chức năng có thể sử dụng | Điểm kiểm soát chính |
|---|---|---|
| A-01…06 — nền tảng | Login JWT thật, tài khoản hiện tại, đổi mật khẩu, quản lý hồ sơ/liên hệ/ngày vào làm, chi nhánh/bộ phận/nhóm/loại nhân viên, quản lý trực tiếp, Level, năng lực, khóa/lưu trữ, tìm kiếm/phân trang, tra nhật ký | Không trả password hash qua API; actor từ JWT; Admin quản trị toàn công ty, Employee chỉ dữ liệu cá nhân; kiểm tra DB khi dùng token; hồ sơ lưu trữ không xóa lịch sử |
| TU-01…04 — kỳ và đăng ký | CRUD kỳ, đối tượng/ngày áp dụng, mở/khóa/mở lại/lưu trữ; availability nhiều khoảng, rảnh/không thể làm, nháp/gửi; tiến độ đăng ký | Clock server và giờ Việt Nam; autoLock bật/tắt; form cũ gửi sau hạn bị chặn; mở lại cần quyền/xác nhận/lý do/hạn mới; kỳ có plan chưa hoàn tất không được archive |
| TU-05…07 — quy tắc | Cấu hình theo công ty/chi nhánh/employeeType/nhóm/cá nhân; fixed/percent, preview, xác nhận, lịch sử, nguồn hiệu lực và checklist | Thiếu tham số không dùng số giả; xung đột cùng mức bị báo; không tự sửa lịch đã công bố khi thay rule; giữ giới hạn hợp đồng bắt buộc |
| TW-01…04 — ca và phân lịch | Ca gắn kỳ, ca đêm/nghỉ, lặp/bulk/clone, yêu cầu Level/năng lực, validator chung, engine backend, dry-run, lưu/chạy lại/so sánh nháp và diễn giải thiếu người | Kiểm tra trùng, unavailable/nghỉ, active/địa điểm, chứng chỉ, giờ ngày/tuần/kỳ, nghỉ tối thiểu/ngày liên tiếp; tính cả assignment vừa sinh; không lấp thiếu người bằng vi phạm hard constraint |
| TW-05…07 — vòng đời lịch | Lịch ngày/tuần/tháng và bộ lọc; nháp → chờ duyệt → duyệt → công bố; phiên bản; sửa phân công chính thức; preview/xác nhận ngoại lệ; xem/xác nhận lịch cá nhân; API số liệu chuẩn | Employee không thấy nháp; kiểm tra version/khóa kỳ trong giao dịch; hard constraints không được override; thay lịch có audit và sự kiện |
| BA-01…06 — phát sinh | Đổi chéo/làm thay, đồng nghiệp phản hồi rồi quản lý duyệt; Open Shift/claim/duyệt; nghỉ phép/vắng; notification in-app, đọc/chưa đọc/phân trang và nhắc hạn | Snapshot lịch từ lúc gửi; duyệt kiểm tra lại; idempotency; chỉ một request nhận chỗ cuối; outbox cùng transaction, retry và chống trùng |
| BI-01…02 — công | Check-in/out theo ca chính thức, không có lượt mở trùng; đề nghị bổ sung/sửa; duyệt/từ chối, duyệt giờ công, khóa kỳ và điều chỉnh sau khóa | Tách bản gốc/đề xuất, version và audit; không tự duyệt; khóa/lọc đúng `period_id` khi kỳ chồng ngày; điều chỉnh sau khóa đi qua duyệt có lý do |
| BI-03…07 — tổng hợp và UI | Dashboard kỳ/hạn/active/ca/lịch/xác nhận/công/yêu cầu; báo cáo giờ lịch/giờ thực tế/giờ duyệt, thiếu người, CSV/XLSX; workspace qua API; responsive | Cùng nguồn giờ lịch; nguồn published/plan có nhãn; 808/740 = +68h, 109%; mẫu số 0/thiếu hiển thị chưa đủ dữ liệu; export kiểm tra quyền |

Giao diện thật là `workspace.html`, `js/workspace.js`, `attendance.html`, `js/attendance-portal.js`. Các màn hình demo cũ dùng localStorage không phải luồng vận hành hay bằng chứng nghiệm thu.

## 3. Những thay đổi bổ sung trong đợt này

1. **Tích hợp nền tảng:** thêm `/api/me`, đổi mật khẩu backend và nút đổi mật khẩu; bổ sung hồ sơ liên hệ/tổ chức, bộ phận, quản lý trực tiếp, lưu trữ có lý do, tìm kiếm/phân trang và CRUD Level. Giữ JWT dùng chung giữa workspace và chấm công. Không thay workspace bằng JS cũ từ nhánh khác.
2. **Dữ liệu hồ sơ:** migration V18 thêm `departments`, `users.department_id/manager_id/archived_at/version` và ràng buộc. Không sửa migration V1–V17 đã phát hành. Chặn vòng lặp quản lý; giữ hồ sơ lịch sử; chặn lưu trữ khi còn phân công tương lai.
3. **Audit:** thêm API/ UI tra lịch sử chung chỉ Admin; ghi hồ sơ trước/sau và đổi mật khẩu mà không ghi password/hash. Nhật ký chấm công vẫn giữ bảng/API chuyên biệt với quyền riêng.
4. **Rules:** đưa `understaffed_policy` vào danh mục rule có preview/confirm, ngày hiệu lực và xung đột; công bố không đọc lại seed legacy làm chính sách hiệu lực.
5. **Chấm công:** sửa các truy vấn còn dùng khoảng ngày sang `period_id`; kỳ khác chồng ngày không khóa/nhận nhầm công. Khóa kỳ và thao tác công cùng khóa parent period trong transaction. Duyệt công thông thường bị chặn sau khóa; đề nghị điều chỉnh có kiểm soát vẫn hoạt động.
6. **Dashboard/quyền:** số liệu đếm theo cùng employee filter; số người chưa xác nhận đếm distinct user; hiện thêm thông tin kỳ/hạn, active, ca hôm nay, nháp và các loại yêu cầu chờ. Danh sách kỳ báo cáo và API truy cập trực tiếp không lộ kỳ nháp/không áp dụng cho Employee.
7. **Đồng thời:** bổ sung HTTP race archive ↔ tạo plan, duyệt request ↔ sửa lịch, hai người tranh chỗ cuối. Lỗi tranh khóa SQL Server trả `409 RETRY_REQUIRED` thay vì `500`, giao dịch thất bại rollback để người dùng tải lại/thử lại.
8. **Giao diện:** sửa helper tạo textarea gán nhầm thuộc tính chỉ đọc, khiến form có ô nhiều dòng không mở được. Kiểm tra modal bằng bàn phím, shared JWT của iframe và dữ liệu bền vững qua browser độc lập.
9. **Chạy hệ thống:** một `CHAY_HE_THONG.bat` trong `HUONG_DAN`, Spring Boot phục vụ cả UI/API trên localhost:8080 và lưu SQL Server thật. Không cần Node để chạy; có chờ readiness, cache build, kiểm tra cổng, chống khởi động trùng và dừng phiên do launcher quản lý. Node proxy trước đây vẫn được giữ cho bài kiểm thử riêng.

## 4. Quyết định nghiệp vụ và hợp đồng giữ cho mốc B

- Scope rule: **user > group > employee_type > branch > company**; chọn ngày hiệu lực gần nhất không sau ngày áp dụng. Hai giá trị cùng mức/ngày gây xung đột thì dừng và yêu cầu sửa cấu hình.
- `target_hours` và trần fixed/percent là hai đại lượng riêng. Hợp đồng/hard constraints chặt hơn phải thắng. Rule thiếu không được tự seed 120%, 125% hay một giá trị tùy ý.
- Ca có `period_id` tường minh. Dữ liệu legacy NULL phải được Admin phân loại; không đoán kỳ khi nhiều kỳ chồng ngày.
- **Trần giờ kỳ là ràng buộc an toàn theo cửa sổ ngày:** cộng mọi công việc thực tế/phân công hiệu lực rơi vào khoảng ngày của kỳ, kể cả ca thuộc kỳ khác. Không được tạo kỳ chồng ngày để vượt trần. Giờ ngày/tuần/nghỉ cũng tính công việc liên quan ngoài kỳ. Báo cáo một kỳ vẫn lọc đúng ca gắn `period_id` và chia giờ qua biên theo ngữ cảnh đã chọn; không đồng nhất mọi tổng lọc với tải dùng để kiểm tra hard constraint.
- Các thời điểm lưu UTC; ngày/giờ ca và mốc nhập hiển thị theo `Asia/Ho_Chi_Minh`. Không dùng đồng hồ trình duyệt để quyết định hết hạn.
- Pending request không sửa lịch. Snapshot source/target assignment/shift được kiểm tra lúc phản hồi/duyệt. Duyệt lặp không tạo lại mutation.
- Module gọi duyệt đổi/nghỉ/nhận ca phát notification nghiệp vụ; lõi lịch ghi version/history nhưng không phát thêm notification trùng. Sửa lịch trực tiếp phát sự kiện lịch. Outbox và mutation cùng transaction.
- Swap/request gặp vi phạm thuộc nhóm ngoại lệ vẫn bị chặn nếu chưa có quy trình preview/xác nhận phù hợp; không âm thầm bỏ qua validation để duyệt.
- Admin V1 có quyền toàn công ty; Employee chỉ nguồn dữ liệu cá nhân theo chính sách. Các role Owner/HR/Scheduler/Payroll/Learning/Department Manager và scope quản lý chi nhánh là phần cần thiết kế kỹ ở mốc B.
- Logout hiện xóa JWT phía client. Khóa/lưu trữ/đổi role được kiểm tra lại từ DB trên API production; chưa có cơ chế thu hồi từng JWT sau logout/đổi mật khẩu.

## 5. Ma trận nghiệm thu và bằng chứng

| AC | Bằng chứng tự động hiện có | Kết luận |
|---|---|---|
| AC-01 | Engine/validator/rules unit tests; SQL test giới hạn kỳ chồng ngày; HTTP 60 × 125% = 75; case fixed/missing/contract trong suite | Đạt phần đã tự động kiểm tra |
| AC-02 | Engine tests ứng viên hết giờ/không đủ chuẩn; ca thiếu vẫn giữ thiếu, không sinh phân công hard violation | Đạt |
| AC-03 | `dashboardExportsAndCanonicalHoursAgreeForThe808Over740Fixture`: SQL Server thật, dashboard/table/canonical/CSV/XLSX, mẫu số 0 | Đạt |
| AC-04 | `IssueFiveFlowIT` và availability tests: save/create/update/submit; autoLock bật/tắt; kiểm tra server trước khi job chạy | Đạt |
| AC-05 | `IssueFiveFlowIT.ac04_ac05_afterDeadline_apiRejectsEvenBeforeAutoLockJobRuns`: mở form trước hạn, giả lập Clock gửi sau hạn | Đạt |
| AC-06 | `IssueFiveFlowIT.ac06_reopen_requiresPermissionConfirmReason_andKeepsRegistrations`, period unit tests và audit DB | Đạt |
| AC-07 | Canonical schedule report; SQL fixture 808/740; cùng employee filter trên cards/counters; HTTP đối chiếu nguồn giờ | Đạt |
| AC-08 | SQL swap/snapshot/rollback; HTTP peer/approve/approve lặp và race sửa lịch; lịch/version/event/audit | Đạt |
| AC-09 | Admin 13 màn hình + modal, Employee 7 màn hình, chấm công, CSS 320/360/390/768/1024/1280; DPR 1.25, zoom 125%, Escape | **Còn Windows Display Scale thật khác 100%** |
| AC-10 | HTTP có dữ liệu hồ sơ/kỳ/availability/rules/lịch/request/leave/open shift/công; browser so snapshot sau refresh/logout/login và Chromium process độc lập | Đạt |
| AC-11 | API trực tiếp, userId/branchId giả, report/export kỳ riêng tư, profile/audit trái quyền, token tài khoản khóa/lưu trữ | Đạt |
| AC-12 | SQL/HTTP audit trước/sau/reason; publish/swap/period/công; outbox rollback/retry/dedup; API audit không lộ hash | Đạt |
| AC-13 | Resolver precedence/conflict/missing, preview-confirm/history; staffing policy theo ngày; không sửa lịch công bố qua apply rules | Đạt |
| AC-14 | Fixed/percent suy ra trong backend; fixture report tự tính +68h/109%, mẫu số 0 không chia sai | Đạt |

Lượt Java cuối chạy `*Test,IntegratedMocAIT,IssueFiveFlowIT` với SQL Server thật trên hai database QA khác nhau. `IssueFiveFlowIT` cần database rỗng vì các ca kiểm thử có thứ tự, nên script tạo mới mỗi lượt. H2 chỉ được dùng cho unit/controller; không dùng H2 làm bằng chứng migration SQL Server.

File evidence cục bộ:

- `.local-test/moc-a-current-tests.log`, `build/surefire-reports/`: kết quả Java/JUnit.
- `.local-test/http-evidence.json`, `moc-a-http-current.log`: các nhóm HTTP.
- `.local-test/browser-evidence.json`, `moc-a-browser-current.log`, `screenshots/`: viewport, form, session, persistence.
- `.local-test/frontend-host-evidence.json`, `frontend-host.log`: proxy và chặn file riêng tư.
- `.local-test/backup-restore-evidence.json`: COPY_ONLY/CHECKSUM, VERIFYONLY, restore mới, CHECKDB và row count.
- `.local-test/moc-a-run-manifest.json`: base SHA, hash JAR và từng file nguồn/test, nhận diện bản chưa commit.

Không đưa `.env`, `browser-fixture.json`, cấu hình DB QA hay backup vào báo cáo/Git vì chúng có thông tin môi trường và tài khoản kiểm thử.

## 6. Ưu điểm và khuyết điểm em ghi nhận

**Ưu điểm:** nghiệp vụ đi qua backend và SQL Server; validator dùng chung cho lịch và yêu cầu phát sinh; có ràng buộc hard/soft rõ; nhiều mutation đã có version/transaction/khóa, audit/outbox; thay rules có preview/confirm; báo cáo tách lịch/công thực tế/công duyệt; có test thật tái hiện tranh slot/lock và test phục hồi DB; một file chạy chung giúp bàn giao trên Windows dễ hơn.

**Khuyết điểm/giới hạn cần senior theo dõi:**

- Chưa đóng bước Windows Scale thật nên chưa ký đủ AC-09. Review đã được dọn ngày 05/11/2026 theo yêu cầu; việc còn lại tiếp tục theo dõi trong `task_v1.1.md`.
- Engine hiện là heuristic/greedy, không có cam kết tối ưu toàn cục hay chứng minh chi phí tối thiểu. Cần fixture thực tế và benchmark trước SC-01/SC-02.
- Quản lý quyền V1 còn đơn giản Admin/Employee; chưa đủ ma trận role/scope cho các module chứa dữ liệu lương, hợp đồng và đánh giá.
- JWT stateless chưa có deny-list/session registry; chính sách thu hồi phiên sau đổi mật khẩu cần thiết kế nếu senior yêu cầu ở mốc B.
- Workspace JS còn nhiều hàm/biểu thức dồn trong một file; nên tách API client, form components và từng màn hình khi mở rộng, giữ các test hiện có để tránh hồi quy.
- UI chấm công vẫn là iframe. Đã test shared JWT và responsive, nhưng khi tách nhiều frontend cần cân nhắc thay bằng module cùng shell để giảm hai luồng session/error.
- Outbox có retry/trạng thái failed/dead; chưa có dashboard vận hành và thao tác retry có quyền/audit cho người vận hành. Mốc B cần bổ sung trước khi mở thêm email/kênh ngoài.
- Các `.bat`/frontend host hiện phục vụ chạy local. Hạ tầng production như HTTPS, secret manager, lịch backup ngoài máy và giám sát cần triển khai riêng; không có kết quả load test hoặc diễn tập HA trong phạm vi bàn giao này.
- Chưa có Payroll/LMS/HR mở rộng chạy thật. Các màn hình demo có tên tương tự không được coi là module mốc B đã hoàn thành.

## 7. Đề xuất làm mốc B theo thứ tự phụ thuộc

Em đề xuất senior chốt xong AC-09 rồi tạo baseline nghiệm thu, commit mã nguồn và evidence tóm tắt. Không force-push các nhánh thành viên. Migration mới tiếp theo là **V19**; mỗi người nhận version trước khi tạo migration, không sửa V1–V18 đã áp dụng.

| Đợt | Task/chủ trì | Việc cần chốt trước khi code | Đầu ra và nghiệm thu đề xuất |
|---|---|---|---|
| B0 — hợp đồng chung | Anh + cả đội | Ma trận Owner/HR/quản lý/Scheduler/Attendance/Payroll/Learning; scope chi nhánh/bộ phận; chính sách tài liệu nhạy cảm; thời gian/version/event/API; phân phối migration | ERD + API/DTO + quyền + fixture + chủ sở hữu nguồn dữ liệu, test truy cập ngang/dọc; không phát triển các nguồn lương/hợp đồng song song |
| B1 — HR nền | **Anh: HR-01, HR-02** | Hồ sơ/tài liệu theo quyền, loại hợp đồng/phụ lục, ngày hiệu lực, điều chuyển, lịch sử chức vụ/lương và import có validation | Migration tăng thêm; import preview/confirm và báo lỗi từng dòng; lịch sử không bị ghi đè; export/tài liệu không vượt quyền; event hợp đồng/chứng chỉ hết hạn |
| B1 — năng lực | **Tuấn: LS-03** | Mức thành thạo, người xác nhận, ngày hiệu lực, điều kiện ca/vị trí và chính sách hết hạn | Ma trận kỹ năng có audit; validator lịch dùng một nguồn năng lực; kết quả học chưa xác nhận không tự đổi điều kiện phân ca |
| B1 — nghỉ nâng cao | **Bảo: CO-01** | Phép hưởng/đã dùng/còn lại, loại nhân viên/hợp đồng, duyệt nhiều bước, tài liệu, điều chỉnh số dư | Sổ phép có transaction/version; duyệt song song không trừ hai lần; kiểm tra Part-time; event và báo cáo cùng số dư nguồn |
| B2 — payroll | **Bình: PA-01, PA-02** | Đơn giá/hiệu lực, phụ cấp/thưởng/khấu trừ, kỳ lương, công đã duyệt, phép, quy tắc làm tròn; senior chốt công thức bằng fixture | Tính thử/đối soát/duyệt/khóa/điều chỉnh; phiếu lương cá nhân; chỉ dùng giờ công đã duyệt; audit và quyền riêng; phúc lợi/thưởng dùng một sổ nguồn |
| B2 — LMS | **Tuấn: LS-01, LS-02** | Khóa/bài/kiểm tra, gán bắt buộc/tự chọn, tiến độ/điểm/điểm danh, chứng chỉ, học lại | Luồng gán → học → đánh giá → xác nhận chứng chỉ; quyền học viên/giảng viên/quản lý; event nhắc học/hết hạn; không chờ LMS mới dùng chứng chỉ cơ bản của V1 |
| B2 — tối ưu lịch | **Tường: SC-01, SC-02** | Objective/weights, đơn giá từ Payroll theo hiệu lực, định biên theo vị trí/địa điểm, dữ liệu nhu cầu/availability/năng lực | So sánh baseline/thuật toán mới trên fixture; hard constraints giữ nguyên; có thời gian chạy/cost/coverage và giải thích; không tối ưu bằng vi phạm quy định |
| B3 — vòng đời nhân sự | **Anh: HR-03, HR-04** | Tuyển dụng/offer/onboarding, checklist/tài khoản/thiết bị, offboarding/ngày nghỉ/bàn giao | Chuyển ứng viên thành nhân viên đúng một lần; onboarding gán LMS; offboarding khóa tài khoản, xử lý ca tương lai/công/lương và giữ hồ sơ lịch sử |
| B3 — giao tiếp/đánh giá | **Bảo: CO-02, PE-01** | Kênh email/cấu hình, template/đối tượng; kỳ đánh giá/KPI/người đánh giá/cách phản hồi; phân biệt đánh giá và thưởng tài chính | Outbox đa kênh có retry/dedup/trạng thái; lịch sử đọc; KPI có quyền và audit; không mặc định đánh giá Part-time thấp do ít giờ; thưởng chuyển Payroll qua event chuẩn |
| B4 — tổng hợp | **Bình: AN-01, AN-02** | Định nghĩa KPI, nguồn dữ liệu, filter/timezone, role/scope, chu kỳ cập nhật và export | Dashboard theo role, tuyển dụng/đào tạo/chi phí/nhân sự/hiệu suất; đối chiếu số liệu với từng module; mẫu số thiếu không hiển thị số giả; export đúng quyền |

Không nên giao Bình viết lại toàn bộ logic các module để làm báo cáo. Mỗi chủ module cung cấp định nghĩa, API/read model và event chuẩn; Bình tổng hợp/hiển thị từ nguồn đó.

Checklist cho **mỗi task B**: yêu cầu chi tiết được senior chốt; migration/schema và backfill; API/DTO/lỗi/quyền; UI loading/empty/error/success; transaction/version/audit/event; unit + SQL Server + API negative + browser; fixture nghiệp vụ được duyệt; hướng dẫn vận hành; evidence cùng bản mã. Công thức lương/quyền lợi chưa được chốt thì chỉ preview, không tự kích hoạt.

## 8. Việc cần senior chốt ở điểm bàn giao

1. Thực hiện và ghi nhận AC-09 tại Windows Display Scale 125%/150%, tách biệt DPR/zoom, rồi mới quyết định nghiệm thu mốc A 100%.
2. Các file review đã được xóa theo yêu cầu cập nhật ngày 05/11/2026; tiếp tục giữ `task_v1.1.md`, báo cáo này, hướng dẫn chạy và evidence. Việc dọn review không thay thế nghiệm thu AC-09.
3. Review các quyết định ở mục 4, đặc biệt cửa sổ trần giờ khi kỳ chồng ngày, policy thiếu người, quyền Admin/Employee và JWT. Các quyết định này đã có kiểm thử tương ứng, cần duy trì khi mở rộng.
4. Tạo baseline đã commit và nhận lại quyền sở hữu task B theo bảng; chốt chính sách tài chính/tài liệu/quyền trước khi triển khai. Không coi màn hình demo là tính năng B đã có.
