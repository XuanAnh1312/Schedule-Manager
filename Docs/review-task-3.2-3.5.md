# Nhận xét và yêu cầu sửa task 3.2–3.5 trước khi merge

Ngày đối chiếu: 31/10/2026. Phạm vi: mốc A trong `task_v1.1.md`, không giao thêm Payroll/LMS hoặc toàn bộ mốc B.

## 1. Cơ sở đánh giá và cách đọc

Đã chạy `git fetch origin` và đối chiếu bốn đầu nhánh remote sau:

| Mục | Thành viên | Nhánh phải push phần sửa | SHA được review |
|---|---|---|---|
| 3.2 | Tuấn | `feature/task-5-periods` | `4a2d030` |
| 3.3 | Tường | `feature/tuong-case-cycle` | `1bfdf2d` |
| 3.4 | Bảo | `feature/bao-requests-leave-notifications` | `0db82b8` |
| 3.5 | Bình | `feature/cham-cong-bao-cao` | `fda7328` |

Các đường dẫn code bên dưới là đường dẫn tương đối trong nhánh thành viên tương ứng; tiền tố Java chung là `src/main/java/org/dacnpm/schedulemanager/`. Bản snapshot có sẵn trong `.review-moc-a/{tuan,tuong,bao,binh}` được dùng để đọc đối chiếu với code tích hợp và diff local.

**Phân biệt ba trạng thái:**

- **Lỗi/gap ở nhánh gốc:** có căn cứ trong mã nguồn được review; phải sửa hoặc chứng minh bằng test rằng đã xử lý.
- **Có bản sửa local tham khảo:** workspace đang ở `integration/moc-a-completion`, HEAD `63af23c`, có nhiều thay đổi tracked và untracked chưa commit. Những thay đổi này chưa phải kết quả đã push của thành viên. Không yêu cầu viết lại máy móc; chủ module cần nhận đúng phần, review và kiểm thử trước khi push.
- **Chưa đủ bằng chứng nghiệm thu:** chưa khẳng định tính năng sai, nhưng chưa được đánh dấu hoàn tất. Báo cáo này là review code và bằng chứng có sẵn; không chạy lại SQL Server/browser suite trong lượt review này.

Không chấm phần trăm hoàn thành vì số lượng file/test không chứng minh mức đạt nghiệp vụ. Đánh giá chung: đã có nền tảng module đáng kể, nhưng chưa đủ điều kiện xác nhận 100% hoặc merge thẳng bốn nhánh vào main để phát hành.

Mức ưu tiên: **P0** chặn tích hợp/khởi động; **P1** sai hoặc có nguy cơ sai dữ liệu/nghiệp vụ; **P2** hoàn thiện UI, hợp đồng và bằng chứng.

## 2. Các việc chung phải chốt trước

### C-01 — P0: trùng version Flyway giữa các nhánh

**Căn cứ:** Tường có `V6__schedule_rules_targets_and_plans.sql`, Bảo có `V6__requests_leave_notifications.sql`, Bình có `V6__attendance_reports.sql`. Tuấn có `V7__period_lifecycle_availability_rules.sql`, Tường có `V7__set_weekly_ceiling_75.sql`. Khi gộp nguyên trạng, Flyway có nhiều migration cùng version.

**Yêu cầu:** Anh điều phối số migration duy nhất; chủ module tự kiểm tra FK và dữ liệu backfill. Bản local đang đề xuất giữ Tường V6, Tuấn V7, Tường V8–V12, Bảo V13, Bình V14 và tích hợp V15–V17; đây là phương án tham khảo, cần công bố baseline đã commit trước khi mọi người dùng. Không tự đổi checksum/version trong `flyway_schema_history`, không xóa DB đang dùng để che lỗi. Nếu DB riêng đã chạy migration cũ, ghi rõ hướng chuyển đổi riêng; nếu đã chạy ở môi trường chung, không sửa migration đó tại chỗ.

**Nghiệm thu:** khởi động trên SQL Server DB mới và nâng cấp DB baseline V1–V5; lưu Flyway output, danh sách migration và kết quả kiểm tra dữ liệu. DB đã chạy migration nhánh riêng cần có phương án được review, không dùng chung hướng dẫn nâng cấp V1–V5 một cách mặc định.

### C-02 — P0: hai module report có cùng tên bean mặc định

**Căn cứ:** `api/report/ReportService.java` của Bình và `schedule/report/ReportService.java` của Tường đều dùng tên lớp `ReportService`; controller tương ứng cũng cùng tên `ReportController`. Khi component scan gộp các module, phải xử lý tên bean trùng.

**Yêu cầu:** Bình phối hợp Tường đặt tên bean rõ ràng hoặc đổi tên lớp; kiểm tra cả controller và service, không chỉ sửa một lớp. Bản local có đặt `attendanceReportService` và tên controller riêng để tham khảo.

**Nghiệm thu:** application context với profile `sqlserver` khởi động được, cả hai nhóm endpoint report gọi được, không bật bean overriding để bỏ qua xung đột.

### C-03 — quy tắc giao nhận code

Anh cung cấp một commit baseline tích hợp sạch, đã push, gồm hợp đồng/schema cần dùng; không lấy thư mục làm việc còn dirty làm baseline. Mỗi thành viên nhận phần sửa local đúng sở hữu module qua commit chọn lọc sau khi review, hoặc tự thực hiện trên nhánh mình. Không cherry-pick một commit lớn sửa cả bốn module sang mọi nhánh.

## 3. Gửi Tuấn — task 3.2 / TU-01…TU-07

**Nhận xét:** đã có service kỳ, kiểm tra cửa sổ đăng ký, Clock, availability, resolver và preview rules; đây là nền tảng tốt để hoàn thiện. Điểm chưa khép kín chủ yếu là scope thực tế, adapter với module lịch và UI thật.

### TU-R1 — P1: scope branch/employeeType/group chưa được đưa vào resolver

**Căn cứ:** `api/rules/RuleService.java`, hàm `contextOf` ở khoảng dòng 562 của nhánh gốc trả `new ScopeContext(userId, null, null, null, SCOPE_NOTE)`. Resolver có hỗ trợ scope nhưng context thực tế thiếu thông tin tổ chức.

**Hậu quả:** cấu hình riêng chi nhánh/loại nhân viên/nhóm có thể không được chọn khi tính effective rules, preview tác động hoặc phân lịch; UI có lựa chọn scope chưa chứng minh backend áp đúng.

**Sửa cụ thể:** đọc context tổ chức từ nguồn nhân viên chuẩn của Anh; validate scopeId tồn tại trước khi confirm; thống nhất thứ tự ưu tiên và cách báo xung đột cùng mức. Cùng một context phải dùng cho API xem effective, preview và `effectiveForScheduling`. Không lặng lẽ quay về rule công ty nếu có cấu hình xung đột/thiếu dữ liệu bắt buộc. Bản local có sửa `RuleRepository.scopeContext`, `scopeExists` và `RuleService.contextOf` để tham khảo; schema scope phải đi theo baseline chung.

**Test bắt buộc:** cùng ngày, hai nhân viên khác chi nhánh nhận giới hạn khác nhau; rule user thắng rule nhóm theo thứ tự đã chốt; scopeId không tồn tại bị từ chối; hai rule trùng mức trả lỗi có thể hiểu được; effective response ghi nguồn và version. Đối chiếu TU-06, AC-13.

### TU-R2 — P1: điểm chặn archive kỳ chưa nối với lịch

**Căn cứ:** `api/periods/SchedulePlanGate.java` quy định khi không có bean thì cho lưu trữ kỳ; `PeriodService` inject `Optional<SchedulePlanGate>`. Đây là điểm mở rộng có chủ ý, nhưng khi tích hợp phải có implementation thật.

**Sửa cụ thể:** Tuấn chốt hợp đồng; Tường cung cấp bean truy vấn plan `draft/pending_approval/approved`. Profile chạy SQL Server phải có adapter; nên có kiểm tra cấu hình/test để tránh mất bean mà hệ thống vẫn cho archive. Bản local có `schedule/plan/PeriodPlanGate.java`.

**Test:** mỗi trạng thái chưa hoàn tất đều chặn archive và trả danh sách plan liên quan; chỉ còn trạng thái kết thúc thì cho phép theo chính sách; archive cạnh tranh với tạo/chuyển trạng thái plan không để trạng thái cuối sai. Không sửa status plan trực tiếp trong module kỳ.

### TU-R3 — P1/P2: đóng vòng UI kỳ/availability/rules với backend

**Căn cứ:** giao diện kế thừa `js/admin2.js` vẫn đọc `State.get.periods()`, `freeTimeByPeriod()`…; sự tồn tại API mới không chứng minh các thao tác trên màn hình đã dùng API. Bản local có workspace API mới nhưng chưa commit/push.

**Sửa cụ thể:** xác định rõ màn hình thật được nghiệm thu, route/menu dẫn tới đâu; đọc/ghi kỳ, lưu nháp/gửi đăng ký và preview/confirm rules qua API. Hiển thị giờ server, múi giờ, nhãn autoLock=false; lỗi 409/hết hạn không được báo lưu thành công. Preview phải hiển thị thay đổi trước khi confirm, có lý do, nguồn/version/ngày hiệu lực. Không dùng dữ liệu localStorage làm nguồn nghiệp vụ chuẩn.

**Test:** mở form trước hạn, gửi sau hạn → backend từ chối; create/update/draft/submit đều thử tại đúng ranh giới hạn bằng Clock; autoLock=false không tự đóng theo nhánh xử lý autoLock=true; mở lại đúng quyền, xác nhận/lý do/hạn mới, giữ đăng ký cũ; refresh và trình duyệt mới đọc đúng DB. Đây là yêu cầu nghiệm thu, không phải kết luận tất cả case hiện đang lỗi.

**Đầu ra Tuấn phải push:** code scope + hợp đồng với Tường + UI module + test + tài liệu API/nguồn rule. Push `feature/task-5-periods`; không push thay vào nhánh của Tường.

## 4. Gửi Tường — task 3.3 / TW-01…TW-07

**Nhận xét:** đã có CRUD ca, validator, engine, plan workflow và report; bộ test nhánh gốc có log 158 test đạt. Tuy nhiên còn sai khác giữa dữ liệu Tuấn cung cấp và cách module lịch đọc, cộng với adapter/sự kiện chưa nối hoàn chỉnh.

### TW-R1 — P1: engine chưa đọc effective rules thật của Tuấn

**Căn cứ:** `schedule/rules/TableRulesSource.java` nhánh gốc gọi `EmployeeRules.derive(base, employee, period)` từ bộ rule chung. `EmployeeRules` còn diễn giải trần tuần từ target_rate. Migration `V7__set_weekly_ceiling_75.sql` tự UPDATE mục tiêu 60 và trần 75.

**Hậu quả:** API cấu hình một rule nhưng engine có thể áp rule khác; target_rate cá nhân và phần trăm trần là hai ý nghĩa cần tách. Case 60 × 125% = 75 phải là fixture/test, không phải thao tác tự kích hoạt chính sách thật qua migration.

**Sửa cụ thể:** implement `EffectiveRulesSource` đọc `RuleService.effectiveForScheduling(userId,date)` hoặc hợp đồng tương đương đã chốt; giữ giới hạn hợp đồng bắt buộc; truyền checklist thiếu cấu hình tới UI/engine. Loại seed tự kích hoạt 60/75 khỏi đường nâng cấp mới theo quy trình migration C-01; dữ liệu đã áp dụng cần hướng xử lý riêng. Bản local có sửa `TableRulesSource`, `EmployeeRules` và test để tham khảo.

**Test:** percent và fixed; thiếu target/giới hạn → checklist; rule theo nhân viên/ngày có hiệu lực; hợp đồng chặt hơn rule công ty vẫn chặn; mọi ứng viên chạm trần thì giữ thiếu người; thay cấu hình không âm thầm thay lịch đã công bố. AC-01/02/13/14.

### TW-R2 — P1: availability không phân biệt available/unavailable

**Căn cứ:** `schedule/plan/ScheduleDataRepository.java` nhánh gốc đọc slot không lấy `slot_type`; khi start/end null thì coi cả ngày rảnh. Trong khi Tuấn đã có slot loại không thể làm.

**Hậu quả:** khoảng bị cấm có thể bị dùng như khoảng rảnh; entry không có slot có thể thành rảnh cả ngày. Đây là lỗi chuyển đổi dữ liệu, sửa validator đơn thuần chưa đủ.

**Sửa cụ thể:** đọc slot_type, tách khoảng available và unavailable; unavailable và nghỉ đã duyệt phải chặn cứng theo chính sách. Chuẩn hóa ca/slot qua nửa đêm; không biến ngày rỗng thành cả ngày rảnh. Nạp cả dữ liệu biên cần thiết để kiểm tra ca qua ngày. Bản local có `UserAvailability.subtract`, `UNAVAILABLE_INTERVAL` và truy vấn leave để tham khảo.

**Test:** available 08–17 + unavailable 12–14 → không xếp ca chồng 12–14; ngày không slot không được coi tự do cả ngày; slot 22–02; nghỉ đã duyệt; thử cùng dữ liệu qua engine, thêm tay, publish và đổi/nhận ca. Ngoại lệ không được bỏ qua unavailable/nghỉ bắt buộc.

### TW-R3 — P1: phân lịch/lọc kỳ đang dựa quá nhiều vào ngày

**Căn cứ:** `ScheduleDataRepository` gốc chọn ca trong khoảng ngày; chưa có liên kết period_id rõ ràng cho ca trong mô hình gốc và chưa lọc employees theo `period_applicable_users` khi nạp dữ liệu.

**Sửa cụ thể:** thêm liên kết ca–kỳ và validate ca nằm trong kỳ; giới hạn tập người được xếp theo đối tượng áp dụng. Dữ liệu cũ chỉ tự backfill khi xác định duy nhất; ca không xác định phải được Admin xử lý. Lọc ca mục tiêu theo kỳ nhưng vẫn nạp assignment lân cận để kiểm tra tuần, nghỉ tối thiểu và ngày liên tiếp. Không bỏ assignment kỳ khác có tác động đến hard constraints. Bản local có migration V16 và các sửa `ShiftRequest`, repository/calendar/report.

**Test:** hai kỳ trùng ngày không lấy nhầm ca/người; ca qua ranh tuần/kỳ chia giờ đúng; chạy lại plan không nhân đôi giờ; phân công ngoài kỳ vẫn chặn vượt giờ/nghỉ; ca hủy không cộng giờ. Kiểm tra calendar và report cùng context.

### TW-R4 — P1: event lịch mới dừng ở log sau commit

**Căn cứ:** `schedule/events/SpringScheduleEventSink.java` gốc dùng Spring event; `AfterCommitLog` chỉ ghi log sau commit, chưa nối outbox của Bảo.

**Sửa cụ thể:** Tường sở hữu adapter chuyển ScheduleEvent thành DomainEvent; Bảo sở hữu outbox/delivery. Lưu outbox cùng transaction mutation; dispatcher gửi sau commit, eventId ổn định theo entity/version. Không thêm listener thứ hai gửi cùng một notification. Bản local đã có hướng nối `DomainEventPublisher`.

**Test:** publish/sửa assignment thành công → đúng người nhận; rollback → không có thông báo; lỗi dispatcher → retry; chạy lại dispatcher → không tạo bản sao; khởi động lại sau commit không mất sự kiện. AC-12 và BA-06.

### TW-R5 — P1/P2: hoàn tất mutation dùng chung, audit và UI lịch

**Yêu cầu:** cung cấp API nội bộ validation/mutation chính thức cho Bảo, có transaction/version và before/after audit. Review các bản local ở `OfficialScheduleService`, `PlanEditService`, `PlanWorkflowService`; không coi bản gốc đã đủ chỉ vì endpoint tồn tại. Nối UI tạo/bulk/lặp/ca khẩn cấp, chạy thử/so sánh, duyệt/công bố, sửa tay, lịch cá nhân/xác nhận; filter kỳ/chi nhánh/nhóm/Level/status phải tác động dữ liệu thật.

**Test nghiệm thu:** hai Admin sửa cùng version chỉ một bên thành công; employee gọi trực tiếp API không đọc nháp/người khác; publish kiểm tra lại hard constraints; override phải preview+xác nhận+lý do+quyền và không vượt hard constraints; audit có actor server, thời gian, before/after, reason. Phần chưa có log runtime phải ghi chưa xác minh.

**Đầu ra Tường phải push:** rules bridge, availability/leave, ca–kỳ, plan gate, mutation/event bridge, UI và test. Push `feature/tuong-case-cycle`.

## 5. Gửi Bảo — task 3.4 / BA-01…BA-06

**Nhận xét:** đã có request/leave/open-shifts/notification và outbox; các test module trong log cũ đạt. Điểm quan trọng nhất là bỏ adapter tạm và kiểm soát lịch thay đổi từ lúc gửi đến lúc duyệt.

### BA-R1 — P1: vẫn sử dụng adapter lịch tạm

**Căn cứ:** `domain/schedule/InterimJdbcScheduleAdapter.java` vẫn là `@Component`, tự validate và UPDATE/INSERT assignments. Comment của lớp cũng ghi cần thay khi Tường hoàn tất.

**Hậu quả:** có hai bộ validation/mutation có thể lệch nhau; luồng đổi ca/nghỉ/nhận ca không tự động hưởng toàn bộ hard constraints, version, audit và event của lõi lịch.

**Sửa cụ thể:** Bảo tích hợp implementation gọi service chính thức của Tường; chỉ còn một bean được chọn cho mỗi interface. Không chỉ thêm `@Primary` rồi giữ adapter tạm đang ghi dữ liệu song song. Bản local có `OfficialJdbcScheduleAdapter`, bỏ component ở adapter tạm. Chốt bên nào phát event nào để tránh thông báo kép.

**Test:** cùng một nhân viên/ca vi phạm giờ/nghỉ/chứng chỉ/unavailable phải bị chặn giống nhau khi thêm tay, đổi ca và nhận ca; lỗi ở mutation sau cùng rollback toàn bộ request, assignments, audit/outbox liên quan.

### BA-R2 — P1: cần snapshot version lịch tại thời điểm gửi

**Căn cứ:** `api/requests/ShiftRequestService.java` gốc có expectedVersion của yêu cầu và kiểm tra lại điều kiện khi duyệt, nhưng chưa lưu snapshot version ca/phân công gốc tại lúc tạo. Version request không thay thế version schedule.

**Sửa cụ thể:** lưu version của các ca/phân công liên quan khi gửi; kiểm tra lại khi peer chấp thuận và khi quản lý duyệt. Nếu lịch đổi thì trả 409 có mã lỗi rõ và hướng dẫn tạo lại. Bản local có `captureScheduleSnapshot`, `scheduleUnchanged`, migration V17. Request cũ thiếu snapshot phải có chính sách rõ, không mặc định cho duyệt khi không chứng minh được dữ liệu gốc.

**Test:** gửi request → Admin sửa giờ ca nhưng giữ ID → duyệt bị từ chối; đổi người được phân công rồi đổi trở lại cũng kiểm tra version; sửa một trong hai đầu swap bị phát hiện; reject/cancel request cũ vẫn xử lý được; approve lặp không đổi lịch lần hai.

### BA-R3 — P1: swap cần xét trạng thái cuối của cả hai phân công

**Căn cứ:** hàm approve trong nhánh gốc gọi `schedule.reassign` ca nguồn rồi mới reassign ca đích. Khi nối validator thật, bước đầu có thể còn thấy phân công cũ của người nhận và báo trùng/vượt giờ dù trạng thái cuối hợp lệ.

**Sửa cụ thể:** phối hợp Tường tạo mutation swap nguyên tử hoặc bỏ cả hai phân công cũ khỏi context trước khi validate/thêm hai phân công mới; tất cả trong một transaction. Bản local đang cancel hai assignment rồi add hai assignment để tham khảo; phải review tác động ID/audit/event và các bảng tham chiếu, không áp dụng máy móc.

**Test:** swap hợp lệ có ca cũ gây xung đột tạm phải thành công; swap làm trạng thái cuối vượt trần phải thất bại; nếu thêm ca thứ hai lỗi thì hai assignment gốc còn nguyên; pending không thay lịch; duyệt đồng thời chỉ có một kết quả có hiệu lực. AC-08.

### BA-R4 — P1: chuẩn hóa thứ tự khóa Open Shift

**Căn cứ:** các luồng claim/withdraw/approve/reject/close của `OpenShiftService` gốc chưa cùng lấy khóa cha trước khi thao tác. Không chỉ kiểm tra filledCount trên dữ liệu đọc thông thường.

**Sửa cụ thể:** chọn một thứ tự khóa chung, ví dụ dòng open_shift trước rồi claim rồi dữ liệu lịch; giữ khóa trong transaction và kiểm tra chỗ/điều kiện/hạn lần cuối trước mutation. Bản local có `OpenShiftRepository.lock` và lời gọi đầu các luồng. Cần kiểm thử SQL Server thật vì mock không tái hiện deadlock/isolation.

**Test:** hai request khác nhau nhận chỗ cuối cùng → đúng một assignment, filledCount đúng; claim cạnh tranh close; withdraw cạnh tranh approve; không còn slot âm/vượt và không trả 500 do deadlock chưa được xử lý. Kiểm tra rollback outbox khi bên thua bị từ chối.

### BA-R5 — P2: UI thông báo/nghỉ và nhắc hạn phải chạy xuyên module

**Yêu cầu nghiệm thu:** có danh sách phân trang, đọc/chưa đọc, đánh dấu đọc và link mở đúng nghiệp vụ; người khác không đánh dấu đọc notification của mình. Nhắc hạn chỉ gửi đúng người chưa submit, theo cấu hình và ngày/hạn của Tuấn, không spam sau khóa/mở lại. Nghỉ đã duyệt ảnh hưởng validator và coverage; vắng đột xuất được ghi nhận; Part-time không bị chặn nghỉ chỉ vì loại nhân viên. Giữ log retry/dedup và trạng thái lỗi đủ để vận hành.

**Đầu ra Bảo phải push:** adapter chính thức, snapshot lịch, swap nguyên tử, locking Open Shift, UI và integration test outbox/leave/deadline. Push `feature/bao-requests-leave-notifications`.

## 6. Gửi Bình — task 3.5 / BI-01…BI-07

**Nhận xét:** đã có attendance, adjustment, export và portal; log nhánh gốc có 28 test đạt. Tài liệu evidence còn ghi CHƯA XÁC MINH/CHƯA TÍCH HỢP, vì vậy không được dùng test công thức đơn lẻ để kết luận Dashboard, E2E và responsive đã đạt.

### BI-R1 — P1: report tự tính planned hours khác nguồn lịch

**Căn cứ:** `api/report/ReportService.java` khoảng dòng 68–92: đọc `standard_hours` từ `rules_main`, tính target qua target_rate; scheduled_minutes dùng DATEDIFF start/end chưa trừ break. Tường có module report riêng với context plan/version.

**Hậu quả:** ca 08–17 nghỉ 60 phút có thể báo 9 giờ thay vì 8; scope rules của Tuấn không được phản ánh; cùng kỳ nhưng Dashboard và report lịch có thể lệch.

**Sửa cụ thể:** dùng nguồn tổng giờ/context chung của Tường và effective target của Tuấn; tách published với draft/plan, actual với approved. Chốt chính sách chia nghỉ khi ca qua kỳ trước khi tính; không tự suy ra từ dữ liệu demo. Bản local đã inject schedule report vào report của Bình để tham khảo, chưa phải xác nhận đã hoàn tất mọi bộ lọc/UI.

**Test:** 808/740 → +68 và khoảng 109%; target=0/null → “Chưa đủ dữ liệu”; ca có break; ca qua ngày/tuần/kỳ; ca hủy; cùng context Dashboard/bảng/export/report lịch bằng nhau; đổi context phải có nhãn rõ. AC-03/07/14.

### BI-R2 — P1: lọc kỳ theo ngày có thể lẫn dữ liệu và gán sai giờ qua ranh giới

**Căn cứ:** nhiều truy vấn trong `api/report/ReportService.java` dùng `shift_date BETWEEN start/end` cho scheduled/actual/approved và bộ đếm; không đủ phân biệt hai kỳ trùng ngày. Actual tính toàn bộ một bản công theo ngày bắt đầu ca.

**Sửa cụ thể:** dùng quan hệ ca–kỳ đã thống nhất với Tường và quy ước context báo cáo; cắt phần thời gian giao với cửa sổ báo cáo theo Asia/Ho_Chi_Minh, lưu/chuyển thời điểm theo quy ước UTC chung. Kiểm tra cả bảng, thẻ, biểu đồ và CSV/XLSX. Không bỏ lịch sử chỉ vì nhân viên hiện đã inactive nếu báo cáo cần giữ công quá khứ. Bản local có `workedHours`, period_id và sửa counters để tham khảo; chính sách phân bổ qua kỳ vẫn phải được nhóm xác nhận.

**Test:** hai kỳ trùng ngày; ca 22–02 ở ngày cuối kỳ; giờ công chênh lịch dự kiến; nhân viên bị khóa sau khi có công; filter user/Level/branch/group/type tác động nhất quán; Employee sửa userId/export không xem được dữ liệu người khác.

### BI-R3 — P1: yêu cầu sửa công thiếu version bản gốc lúc gửi

**Căn cứ:** `api/attendance/AttendanceService.java` gốc có before_json/after_json nhưng chưa lưu base_record_version khi tạo adjustment request. Lấy version hiện tại lúc approve chỉ chống thay đổi ngay trong lần approve, chưa phát hiện thay đổi kể từ lúc gửi đơn.

**Sửa cụ thể:** lưu version attendance gốc tại lúc submit, so sánh trong transaction khi approve; stale → 409 và yêu cầu gửi lại, giữ công hiện tại. Bản local thêm `base_record_version` qua V15. Với đơn bổ sung chưa có record, phải xử lý trường hợp record đã xuất hiện trước lúc duyệt.

**Test:** A gửi sửa công v1, quản lý sửa/duyệt công lên v2, sau đó duyệt A → không ghi đè v2; hai người duyệt cùng đơn → một kết quả; khóa kỳ cạnh tranh approve/check-out → tuân thủ chính sách; audit lưu đủ bản trước/sau, actor và reason.

### BI-R4 — P1/P2: dashboard chưa đủ nhóm số liệu và scope thống nhất

**Căn cứ:** `pendingRequests` của nhánh gốc chỉ đếm attendance_adjustment_requests; chưa đại diện tổng yêu cầu đổi ca/nghỉ/công. Các bộ đếm kỳ dùng khoảng ngày; chỉ có cột số không chứng minh đã đủ BI-03.

**Sửa cụ thể:** tách pendingAttendance/pendingShift/pendingLeave và định nghĩa tổng; bổ sung/đối chiếu draft đang chọn, chưa xác nhận, active, kỳ/hạn, ca hôm nay/thiếu người và trạng thái chấm công. Xác định rõ đơn vị đếm “chưa xác nhận”: người, assignment hay phiên bản lịch, tránh đếm nhiều ca thành nhiều người. Tất cả chỉ số phải cùng filter/context và timestamp; số liệu thiếu phải có nhãn thay vì bịa 0.

**Test:** fixture có một request mỗi loại; đổi kỳ/branch/group thì mọi thẻ và bảng đổi tương ứng; xác nhận một phiên bản không tự xác nhận phiên bản mới; giải thích được cách tổng hợp số liệu từ các hàng chi tiết.

### BI-R5 — P2: evidence mapping và E2E chưa khép kín

**Căn cứ:** `Docs/issue8-ac-evidence.md` của nhánh Bình ánh xạ BI-01 sang AC-03, BI-02 sang AC-07, BI-05 sang AC-09 dưới nhãn “đối chiếu”. `task_v1.1.md` đã định nghĩa AC-03 là tổng giờ, AC-07 là số liệu cùng context, AC-09 là responsive. Bảng hiện còn CHƯA XÁC MINH.

**Sửa cụ thể:** sửa ánh xạ theo task; điền SHA, môi trường, lệnh chạy, ngày, kết quả và đường dẫn log/ảnh. Chạy browser với backend/SQL Server thật, không chỉ demo/iframe localStorage. Dùng chung phiên đăng nhập với shell, xử lý 401/403 rõ. Có thể tham khảo bộ `tests/moc-a-http.mjs` và `tests/moc-a-browser.mjs` local sau khi được commit baseline.

**Ma trận tối thiểu:** 320/360/390/768/1024/1280 CSS px; Admin và Employee; login/navigation/bảng/form đăng ký/calendar/modal/chấm công; loading/empty/error/403; refresh, logout/login và browser context khác; keyboard/focus; ít nhất một Windows scale khác 100% và ghi riêng deviceScaleFactor. DPR hoặc CSS zoom tự động không thay thế bằng chứng Windows scale thật. Ảnh chụp trang không đủ chứng minh nút/modals không bị che; cần assertion hoặc kiểm tra thao tác thực tế.

**Đầu ra Bình phải push:** bean name, report nguồn chung/context, version adjustment, Dashboard/UI, test và evidence AC chuẩn. Push `feature/cham-cong-bao-cao`.

## 7. Bằng chứng test đang có và giới hạn kết luận

Các log có sẵn trong `.review-moc-a/*/review-test.log` ghi ngày 31/10/2026; đây là kết quả từ lần chạy trước, không phải suite vừa chạy lại cho báo cáo này:

| Nhánh snapshot | Kết quả log |
|---|---|
| Tuấn | 88 test, 1 failure: `LevelControllerTest.getLevels_withTamperedSignature_returns401`, expected 401 nhưng nhận 200 |
| Tường | 158 test, 0 failure/error, BUILD SUCCESS |
| Bảo | 72 test, 1 failure: cùng test JWT nêu trên |
| Bình | 28 test, 0 failure/error, BUILD SUCCESS |

Failure JWT thuộc phần nền tảng cần Anh phối hợp điều tra; chưa đủ căn cứ quy lỗi cho Tuấn/Bảo hoặc kết luận cơ chế xác minh JWT có lỗ hổng từ assertion này. Cần kiểm tra cách tạo chữ ký giả trong test, tính ổn định khi chạy lặp và đường xác thực thực tế. Không bỏ test hoặc đổi expected thành 200 để làm xanh build.

Unit test đạt không chứng minh SQL Server migration/locking hoạt động, cũng không chứng minh browser đã dùng API. Mỗi thành viên phải chạy suite phù hợp trên commit cuối và gửi bằng chứng; Bình tổng hợp, không chịu sửa thay mọi module.

## 8. Hướng dẫn push và thứ tự merge cho cả nhóm

### 8.1. Mỗi thành viên thực hiện trên nhánh của mình

Đổi tên nhánh trong ví dụ theo bảng mục 1. Các lệnh dưới là hướng dẫn bàn giao, chưa được thực thi bởi lượt review này:

```powershell
git status --short
git fetch origin
git switch feature/task-5-periods
git pull --ff-only origin feature/task-5-periods
# Sau khi Anh cung cấp commit baseline đã push:
# git merge origin/<nhanh-baseline-da-chot>
# Sửa đúng module, xử lý conflict theo hợp đồng, chạy test và đọc git diff.
git add <cac-file-module-va-test-da-review>
git commit -m "fix: complete TU acceptance and integration"
git push origin feature/task-5-periods
```

Nếu working tree đang dirty thì không switch/merge bừa: lưu thay đổi có chủ đích trước. Nếu pull không fast-forward, kiểm tra lịch sử và giải quyết bình thường; không force-push để che khác biệt. Không dùng `git add .` khi có credential, fixture DB, log hoặc file module người khác. Bảo/Bình/Tường thay nhánh và commit message tương ứng. Không push thẳng `main`; không ép mọi người dùng nhánh integration local hiện chưa được publish.

### 8.2. Thứ tự tích hợp đề xuất

1. **Anh:** chốt/push baseline hợp đồng, migration và bean name; thống nhất schema scope/period_id/version/event. Có thể chọn lọc bản sửa local đã review vào baseline. Ghi rõ những sửa nào đã đưa vào để thành viên không giao lại cùng patch.
2. **Tuấn:** giao rules/context/kỳ/availability và test hợp đồng; merge vào nhánh tích hợp kiểm thử.
3. **Tường:** giao bridge effective rules, reader availability, ca–kỳ, plan gate, mutation/event adapter; kiểm thử với code Tuấn trước khi merge tiếp.
4. **Bảo:** giao adapter chính thức, snapshot/swap/locking/outbox, kiểm thử với lõi lịch đã hợp nhất.
5. **Bình:** giao report/Dashboard/adjustment/E2E trên nguồn dữ liệu đã thống nhất; tổng hợp AC-01…14 với từng chủ module.
6. **Anh:** kiểm tra migration SQL Server, khởi động thật, full test và luồng xuyên hệ thống rồi mới merge nhánh tích hợp vào main. Nếu lỗi tích hợp thuộc module nào thì trả đúng chủ module sửa và push lại nhánh ở bảng mục 1.

Các phần độc lập vẫn làm song song; thứ tự trên là thứ tự nhận hợp đồng và kiểm thử, không bắt cả nhóm chờ tuần tự mới bắt đầu viết code.

### 8.3. Mẫu bàn giao bắt buộc của từng thành viên

```text
Thành viên / mục task:
Nhánh đã push:
Commit SHA cuối:
Baseline đã tích hợp:
Các mã review đã xử lý (TU-R..., TW-R..., BA-R..., BI-R...):
File/migration thay đổi và ảnh hưởng dữ liệu cũ:
Lệnh test + số pass/fail/skip + môi trường:
Link/path evidence API, SQL Server, UI:
Phụ thuộc PR/commit khác:
Case chưa xác minh hoặc lỗi còn mở:
```

Chỉ đóng từng mã review khi có code và bằng chứng tương ứng. Mục cần phối hợp không được ghi “xong” chỉ vì bên mình đã tạo interface; phải có implementation được nạp trong cấu hình chạy tích hợp và test sử dụng thật.

## 9. Thứ tự hoàn thiện task và phần có thể làm song song

Thứ tự dưới đây phân biệt **thứ tự chốt hợp đồng/tích hợp** với **thứ tự bắt đầu làm**. Không cần đợi người trước hoàn thành toàn bộ module mới được bắt đầu; các phần song song dùng chung schema/API phải chốt contract trước, làm bằng mock/branch riêng trong lúc chờ implementation.

### Giai đoạn 0 — Anh chốt baseline chung (chặn merge, không chặn mọi công việc)

Anh cần công bố một commit baseline đã push cho cả nhóm, gồm: bảng migration version duy nhất và thứ tự phụ thuộc; hợp đồng scope/effective rules; period_id trên ca và cách xử lý dữ liệu không backfill duy nhất; version snapshot request/công; event/outbox; tên bean report; trạng thái plan nào chặn archive. Chỉ rõ phần nào trong các sửa local tích hợp đã được nhận vào baseline để chủ module tránh làm lại.

Trong lúc Anh chốt, cả bốn thành viên vẫn có thể sửa các lỗi thuần module, viết unit test và làm UI dựa trên contract hiện có. Chưa rebase/merge các migration cùng version vào nhánh tích hợp dùng để chạy Flyway.

### Giai đoạn 1 — Bốn luồng sửa độc lập, làm song song

**Tuấn trên `feature/task-5-periods`:** xử lý TU-R1 và TU-R3: context scope thật và đường UI/API; viết test resolver theo công ty/chi nhánh/nhóm/cá nhân, thiếu cấu hình, preview/confirm. Có thể làm độc lập với Tường bằng interface effective rules/mock đã chốt. TU-R2 cần Tường cung cấp trạng thái plan; Tuấn có thể hoàn thiện interface, còn integration test chờ Giai đoạn 2.

**Tường trên `feature/tuong-case-cycle`:** xử lý TW-R2/TW-R3: đọc available/unavailable và nghỉ đã duyệt; ràng buộc ca với period, đối tượng áp dụng, assignment lân cận. Có thể code song song Tuấn sau khi thống nhất schema slot_type, period_id và dữ liệu scope. Đồng thời viết test repository/validator bằng fixture. TW-R1 dùng contract từ Tuấn; có thể dựng adapter/mock trước, nhưng chỉ chốt sau khi effective rules contract được Anh/Tuấn công bố.

**Bảo trên `feature/bao-requests-leave-notifications`:** xử lý BA-R2 và phần UI/notification của BA-R5 song song với Tuấn/Tường; snapshot version cần migration baseline nhưng service/test có thể viết trên migration tạm theo contract. Chuẩn bị BA-R1 bằng cách thay adapter qua interface mutation đã thống nhất. BA-R3 swap và BA-R4 locking cần cùng làm với Tường về transaction, version, thứ tự khóa; có thể viết test trước nhưng không tự chọn semantics/schema riêng.

**Bình trên `feature/cham-cong-bao-cao`:** xử lý BI-R3 (version công), BI-R5 (sửa evidence và E2E harness), responsive UI độc lập với report nguồn. Chuẩn bị BI-R1/BI-R2 qua interface report/context và period_id đã chốt; có thể viết adapter/mock trước. Không tự định nghĩa lại công thức planned hours.

**Anh:** song song với các nhánh trên điều tra failure JWT, rà quyền và chuẩn bị SQL Server QA DB an toàn. Đây là việc nền tảng độc lập; báo kết quả để Tuấn/Bảo dùng chung, không để mỗi nhánh tự sửa security khác nhau.

### Giai đoạn 2 — Tuấn và Tường tích hợp quy tắc/lịch

Sau khi contract effective rules, slot_type, period_id và scope được chốt, Tuấn push API/source rules hoàn chỉnh; Tường nối `EffectiveRulesSource`, availability, plan gate và lọc ca theo kỳ. Hai người làm theo cặp ở phần bridge: Tuấn sở hữu ý nghĩa/nguồn rules và kỳ; Tường sở hữu cách engine tiêu thụ, validator và plan. Không sửa chéo file sở hữu nếu chưa thống nhất.

Đầu ra trước khi chuyển giai đoạn: tests fixed/percent/thiếu cấu hình, scope theo nhân viên; tests slot unavailable/nghỉ, ca đêm/qua tuần; archive bị chặn khi plan chưa kết thúc; SQL Server migration thống nhất. Khi các test này chạy trên cùng baseline, Bình có thể bắt đầu nối report theo dữ liệu thật.

### Giai đoạn 3 — Tường và Bảo tích hợp mutation lịch

Tường cung cấp và push validator/mutation service chính thức trước. Bảo thay adapter tạm bằng service này, sau đó hoàn thiện swap nguyên tử, snapshot/version, Open Shift locking và event outbox. Tường chịu trách nhiệm đúng một mutation/audit/event cho lịch; Bảo chịu trách nhiệm state request, quyền duyệt và thông báo. Hai bên cùng review thứ tự khóa và rollback.

Phần có thể chạy song song: Bảo làm snapshot/DTO/UI và test mock; Tường làm mutation/event adapter và test lõi. Phần phải chờ nhau: chọn API cuối, kiểm thử SQL Server race/rollback và xác nhận event recipient/dedup. Không merge BA-R1/R3 nếu test vẫn dùng InterimJdbcScheduleAdapter.

### Giai đoạn 4 — Bình tích hợp báo cáo và nghiệm thu giao diện

Khi ca–kỳ, report context và effective target đã có, Bình nối scheduled/actual/approved vào cùng context, sửa Dashboard và export. Bình có thể làm responsive, mapping AC và browser harness từ Giai đoạn 1; chỉ kết luận số liệu đúng sau khi chạy trên service thật của Tuấn/Tường/Bảo. Tường xác nhận planned hours; Tuấn xác nhận target/effective rules; Bảo xác nhận pending request categories. Bình không tự sửa nghiệp vụ nguồn của ba module kia.

### Giai đoạn 5 — tích hợp theo lát cắt và sửa lỗi hồi quy

Merge theo lát cắt có thể chạy được: (1) baseline+migrations; (2) kỳ/rules/ca/plan; (3) request/leave/open shift/outbox; (4) attendance/report/dashboard/UI. Sau mỗi lát cắt, Anh chạy SQL Server migration/startup và test tích hợp liên quan; chủ module sửa lỗi trên nhánh của mình, push commit mới rồi mới nhận lát cắt tiếp theo. Bình cập nhật bảng evidence từ output thật, không copy kết quả unit test thành bằng chứng E2E.

### Giai đoạn 6 — điều kiện chốt task 3.2–3.5

Chỉ báo hoàn tất khi từng mã TU-R/TW-R/BA-R/BI-R đã có commit trên đúng nhánh, migration sạch trên DB mới và DB baseline hỗ trợ, API/UI dùng chung nguồn dữ liệu, race/rollback được thử trên SQL Server, quyền nhân viên được gọi trực tiếp qua API, browser flow và viewport có bằng chứng, AC liên quan được đối chiếu. Mỗi thành viên push nhánh riêng trong mục 1; Anh merge theo thứ tự ở mục 8.2, không push hộ thành viên và không merge thẳng các nhánh gốc đồng thời trước khi xử lý migration/bean conflict.
