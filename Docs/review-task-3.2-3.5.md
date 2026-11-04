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

Mục này là nội dung giao việc trực tiếp cho từng thành viên. Tất cả prompt đều lấy baseline đã push `integration/moc-a-completion@ae8f740` và code tích hợp `6a0df73` làm chuẩn. Mỗi người làm trên nhánh feature của mình. Có thể bắt đầu song song ngay; chỉ các phần ghi “phối hợp/chờ contract” mới cần đồng bộ trước khi tích hợp hoặc chốt semantics.

### 9.1. Việc Tuấn cần làm — task 3.2

**Nhánh:** `feature/task-5-periods`. **Mã review:** TU-R1, TU-R2, TU-R3.

**Việc cần hoàn thành:**

1. Đối chiếu code hiện tại với baseline; giữ các phần context scope, validate scope ID, migration V15 và PeriodPlanGate đã có trong `6a0df73`, chỉ bổ sung nếu phát hiện thiếu/lỗi.
2. Hoàn thiện resolver dùng employee context lấy từ backend, theo scope ưu tiên user > group > employee_type > branch > company; kiểm tra effective date, revision, xung đột cùng mức và missing required rule. Preview, effective API và nguồn mà Tường tiêu thụ phải dùng cùng resolver.
3. Phối hợp Anh/Tường kiểm tra PeriodPlanGate: archive phải bị chặn với `draft`, `pending_approval`, `approved`; chỉ archive khi không còn plan ở các trạng thái đó. Không đổi trạng thái plan trong API kỳ.
4. Kiểm tra UI/API kỳ, availability, rules: loading/empty/error/409; autoLock, submit sau hạn, mở lại có quyền/lý do; preview rồi confirm có reason/version/audit. Bỏ đường ghi localStorage làm nguồn nghiệp vụ chuẩn trong các màn hình nghiệm thu.
5. Gửi hợp đồng effective rules cho Tường và Bình: tên API/DTO, ý nghĩa `target_rate`, target/max riêng biệt, missing checklist, nguồn scope và version.
6. Chạy test unit/module; phối hợp chạy SQL Server migration và test deadline/scope trên baseline tích hợp. Gửi SHA, test output, API/UI evidence và lỗi còn mở.

**Làm song song:** có thể làm resolver, validate scope, UI kỳ/rules và unit tests cùng lúc Tường làm slot/period, Bảo làm snapshot, Bình làm responsive/evidence. Chỉ phần contract effective rules với Tường cần chốt chung trước khi engine bridge được kết luận; PeriodPlanGate integration test cần Tường có repository plan nhưng Tuấn vẫn làm unit/API trước được.

**Prompt dành cho AI Agent:**

```text
Bạn đang làm task 3.2 (Tuấn) trong repo ManageSchedule. Hãy hoàn thiện các mã TU-R1, TU-R2, TU-R3 theo Docs/review-task-3.2-3.5.md và hợp đồng chuẩn trong Docs/integration-baseline-moc-a.md. Baseline code là integration/moc-a-completion@6a0df73; tài liệu baseline đã push tại ae8f740. Làm trên branch feature/task-5-periods.

Trước tiên kiểm tra git status, branch, commit hiện tại và đọc hai tài liệu trên. Không ghi đè hoặc xóa thay đổi có sẵn. Không làm lại các phần đã có trong baseline; kiểm tra và chỉ sửa gap/regression. Không sửa migration V1-V17; migration mới phải xin Anh cấp version, hiện version kế tiếp V18. Không force-push, không commit file secret/fixture/log.

Phạm vi: (1) đảm bảo context scope được dựng từ employee backend và resolver ưu tiên user > group > employee_type > branch > company, áp đúng effective date/version, trả missing/issues thay vì tự gán mặc định; preview/effective API/scheduling source thống nhất; (2) xác nhận archive kỳ gọi SchedulePlanGate và chặn draft/pending_approval/approved; (3) rà UI/API kỳ, availability, rules với loading/empty/error, deadline/autolock/reopen, preview-confirm/audit và không dùng localStorage làm dữ liệu chuẩn. Chốt DTO/API/ý nghĩa target_hours, target_rate và max_hours với Tường/Bình; target và trần là hai đại lượng riêng.

Viết/sửa test có ý nghĩa cho từng scope, xung đột/missing config, ngày hiệu lực, archive gate và deadline server. Chạy test phù hợp, ghi đúng lệnh/môi trường/pass/fail; không tuyên bố SQL Server/E2E pass nếu chưa chạy thật. Rà git diff, commit thay đổi của riêng task này với message rõ, push origin feature/task-5-periods. Cuối cùng báo: mã TU-R đã xử lý, file/migration, SHA, test/evidence, dependency và lỗi còn mở. Nếu contract với Tường chưa rõ, nêu chính xác điểm cần chốt nhưng tiếp tục phần độc lập.
```

### 9.2. Việc Tường cần làm — task 3.3

**Nhánh:** `feature/tuong-case-cycle`. **Mã review:** TW-R1, TW-R2, TW-R3, TW-R4, TW-R5.

**Việc cần hoàn thành:**

1. Giữ migration V6–V12 tích hợp; không khôi phục V7 tự set 60/75. Kiểm tra `TableRulesSource` dùng effective rules của Tuấn; không tính một bộ rule legacy khác. Thiếu required config phải chặn lập/công bố và trả checklist.
2. Đọc `availability_slots.slot_type`; available là khoảng được phép, unavailable và nghỉ đã duyệt là hard block. Slot không có giờ không được tự biến thành available cả ngày. Xử lý ca/slot qua đêm, giờ nghỉ và ngày biên.
3. Giữ `shifts.period_id` từ V16; lọc ca/người theo đúng kỳ, xử lý NULL theo baseline; vẫn nạp assignment lân cận để kiểm tra giờ tuần/nghỉ/ngày liên tiếp. Không backfill thêm heuristic riêng.
4. Giữ `PeriodPlanGate` và status gate theo baseline. Kiểm tra đúng plan chưa kết thúc làm chặn archive.
5. Nối mutation/validation chính thức và schedule event. Event lịch qua `SpringScheduleEventSink` vào outbox Bảo; transaction rollback không tạo event, retry/dedup không gửi trùng. Đảm bảo Bảo có API nội bộ ổn định để gọi mutation.
6. Kiểm tra workflow plan/assignment: version optimistic lock, hard constraints khi publish/sửa tay, audit before/after/reason, phân biệt draft với published; hoàn thiện UI ca/lịch/plan dùng API thật.
7. Test fixed/percent/missing rules, unavailable/leave, ca đêm/ranh kỳ, stale version, rollback, archive gate và event. Phối hợp SQL Server test với Bảo.

**Làm song song:** slot reader/validator, period filtering, unit tests và UI có thể làm song song với Tuấn/Bảo/Bình theo schema baseline. Effective rules bridge cần contract Tuấn; mutation adapter/event/atomic swap cần làm cặp với Bảo. Bảo có thể chuẩn bị DTO/UI/mock trong khi Tường hoàn thiện service, nhưng không chốt tích hợp BA-R1/R3 trước khi test dùng mutation chính thức.

**Prompt dành cho AI Agent:**

```text
Bạn đang làm task 3.3 (Tường) trong repo ManageSchedule. Hoàn thiện TW-R1…TW-R5 theo Docs/review-task-3.2-3.5.md và Docs/integration-baseline-moc-a.md. Baseline code 6a0df73, baseline tài liệu ae8f740 trên integration/moc-a-completion. Làm trên feature/tuong-case-cycle.

Kiểm tra git status/branch trước khi sửa; bảo toàn thay đổi hiện có. Review code baseline trước, không làm lại phần đã nhận. Giữ V6–V12; không khôi phục V7 seed 60/75, không sửa V1–V17. Migration tiếp theo chỉ dùng sau khi Anh cấp V18. Không force-push hoặc commit fixture/secret.

Hoàn thiện: TableRulesSource tiêu thụ effective rules cùng resolver của Tuấn; availability phân biệt available/unavailable, nghỉ đã duyệt và khoảng qua đêm; validator chặn hard constraints; mọi ca gắn period_id, ca NULL được xử lý đúng baseline, assignment ngoài kỳ vẫn được tính khi ảnh hưởng hard constraints; PlanGate chặn archive với draft/pending_approval/approved; mutation chính thức có version/transaction/audit và cung cấp contract để Bảo gọi; schedule event ghi transactional outbox qua DomainEventPublisher với đúng entity/version/recipients. Rà UI/API plan, shifts, calendar, publish và quyền dữ liệu.

Phối hợp Tuấn chốt EffectiveRules DTO/semantics và Bảo chốt mutation/swap/event/locking; ghi rõ các điểm đã thống nhất. Viết test cho percent/fixed/missing config, availability/leave, qua đêm/ranh kỳ, stale version, rollback, archive gate, event retry/dedup. Chạy test; không nhận SQL Server/race pass nếu chỉ dùng mock. Rà diff, commit riêng, push origin feature/tuong-case-cycle. Báo mã TW-R, SHA, file, kết quả test, evidence, dependency và lỗi còn mở.
```

### 9.3. Việc Bảo cần làm — task 3.4

**Nhánh:** `feature/bao-requests-leave-notifications`. **Mã review:** BA-R1, BA-R2, BA-R3, BA-R4, BA-R5.

**Việc cần hoàn thành:**

1. Giữ V13 và V17 baseline; giữ snapshot source/target assignment+shift version lúc tạo request. Kiểm tra ở peer accept và manager approve; stale trả 409. Request lịch sử thiếu snapshot không được approve; có thể hủy/từ chối hoặc tạo request mới.
2. Dùng mutation/validator service chính thức của Tường, không để InterimJdbcScheduleAdapter còn là bean ghi lịch song song. Không phát duplicate audit/event nếu core mutation đã chịu trách nhiệm.
3. Làm swap nguyên tử theo trạng thái cuối: bỏ cả hai assignment khỏi context validation rồi áp hai assignment mới trong cùng transaction. Lỗi một bên phải rollback toàn bộ; giữ đúng audit/version/event.
4. Chuẩn hóa row lock Open Shift theo thứ tự đã chốt cho claim/withdraw/approve/reject/close; kiểm tra slot cuối và điều kiện lần cuối trong transaction. Chạy race test trên SQL Server.
5. Outbox phải ghi cùng transaction, đúng người nhận, idempotent, retry được; notification read chỉ bởi người nhận. Deadline reminder dùng config của Tuấn, không spam sau submit/lock.
6. Leave đã duyệt đi vào validator/coverage qua hợp đồng Tường; giữ quyền nghỉ cho Part-time. Hoàn thiện UI request/leave/open shift/notifications và các state loading/empty/error.

**Làm song song:** snapshot service/DTO/UI, V17-compatible tests và notification UI có thể làm độc lập. Tường đồng thời làm validator/mutation core. BA-R1 và BA-R3 chỉ tích hợp/chốt sau khi Tường cung cấp service và cùng review transaction/lock order; race/rollback/outbox acceptance phải chạy sau khi merge lát cắt của cả hai.

**Prompt dành cho AI Agent:**

```text
Bạn đang làm task 3.4 (Bảo) trong repo ManageSchedule. Hoàn thiện BA-R1…BA-R5 theo Docs/review-task-3.2-3.5.md và Docs/integration-baseline-moc-a.md. Baseline code 6a0df73, tài liệu ae8f740 trên integration/moc-a-completion. Làm trên feature/bao-requests-leave-notifications.

Kiểm tra branch/status trước khi sửa, bảo toàn thay đổi riêng. Review code baseline đã nhận để tránh làm lại. Giữ V13/V17; không sửa V1–V17, migration tiếp theo chỉ sau khi Anh cấp V18. Không force-push, không thêm credential/test data thật.

Hoàn thiện snapshot version ca/phân công lúc request được tạo và xác minh ở peer accept/approve; stale schedule trả 409, snapshot cũ NULL không được approve. Thay adapter tạm bằng service mutation/validation chính thức của Tường, đảm bảo một mutation/audit/event duy nhất. Thực hiện swap nguyên tử với validation trạng thái cuối và rollback toàn phần nếu một assignment lỗi. Khóa Open Shift theo thứ tự nhất quán trên mọi mutation, kiểm tra chỗ cuối trong transaction. Nối event qua transactional outbox, recipient đúng, dedup/retry, rollback không phát; hoàn thiện leave/request/notification UI, quyền đọc notification và deadline reminder theo config Tuấn.

Phối hợp trực tiếp Tường để thống nhất signature service, transaction boundary, thứ tự lock, trách nhiệm audit/event. Viết test stale snapshot, swap hợp lệ/không hợp lệ và rollback, approve cạnh tranh, slot cuối, outbox retry/dedup, leave ảnh hưởng validator. Chạy SQL Server race test nếu môi trường sẵn; ghi rõ nếu chưa chạy. Commit riêng và push origin feature/bao-requests-leave-notifications. Báo mã BA-R, SHA, test/evidence, dependency và lỗi còn mở.
```

### 9.4. Việc Bình cần làm — task 3.5

**Nhánh:** `feature/cham-cong-bao-cao`. **Mã review:** BI-R1, BI-R2, BI-R3, BI-R4, BI-R5.

**Việc cần hoàn thành:**

1. Giữ bean `attendanceReportService`; dùng schedule report/context của Tường làm nguồn planned hours và effective target của Tuấn. Không tự tính target từ `schedule_rules` legacy hoặc tính giờ planned khác nguồn engine.
2. Mọi số liệu theo period_id/context/variant/version đã chọn; chia giờ ca qua kỳ theo timezone Asia/Ho_Chi_Minh và quy ước UTC; published/draft, scheduled/actual/approved phải tách. Dashboard, table, chart và CSV/XLSX phải dùng cùng filter/định nghĩa.
3. Giữ V15 `base_record_version`; xử lý stale adjustment bằng conflict, không overwrite; bổ sung case request bổ sung khi record được tạo trước lúc duyệt.
4. Hoàn thiện Dashboard với pending attendance/shift/leave tách rõ, kỳ/hạn, ca hôm nay/thiếu người, draft và xác nhận lịch; định nghĩa đơn vị đếm và timestamp. Scope employee/branch/group/level/type phải được kiểm soát.
5. Sửa mapping evidence BI↔AC theo task; chạy HTTP/browser với backend thật khi có QA fixture, đủ viewport 320/360/390/768/1024/1280. Ghi rõ CSS viewport, Windows scale, zoom và deviceScaleFactor; có assertion overflow, modal, keyboard/focus, 401/403/error/loading/empty, refresh/session/browser khác.
6. Phối hợp Tường xác nhận scheduled source/context; Tuấn xác nhận target/effective rules; Bảo xác nhận pending categories. Bình tổng hợp evidence nhưng mỗi chủ module tự sửa lỗi nguồn của họ.

**Làm song song:** BI-R3, responsive/UI, sửa tài liệu evidence và dựng HTTP/browser harness có thể làm ngay. Report planned hours, lọc kỳ và Dashboard tổng hợp chỉ chốt sau khi Tuấn/Tường cung cấp contract/implementation tương ứng; có thể viết adapter/mock trước nhưng không báo số liệu chuẩn trước khi đối chiếu dữ liệu thật.

**Prompt dành cho AI Agent:**

```text
Bạn đang làm task 3.5 (Bình) trong repo ManageSchedule. Hoàn thiện BI-R1…BI-R5 theo Docs/review-task-3.2-3.5.md và Docs/integration-baseline-moc-a.md. Baseline code 6a0df73, tài liệu ae8f740 trên integration/moc-a-completion. Làm trên feature/cham-cong-bao-cao.

Kiểm tra git status/branch và giữ thay đổi có sẵn. Review phần baseline đã tích hợp; không làm lại. Giữ V14/V15, không sửa V1–V17; migration mới chỉ sau khi Anh cấp V18. Không force-push, không commit fixture nhạy cảm.

Dùng `attendanceReportService`; nối planned hours với schedule report/context của Tường và effective target của Tuấn. Giữ rõ published/draft, scheduled/actual/approved; dùng period_id, cắt ca qua ranh kỳ đúng timezone; cùng filter/định nghĩa cho cards/table/chart/CSV. Bổ sung kiểm tra target 0/missing, break, overnight, overlap period, cancelled shift và employee scope. Dùng base_record_version khi duyệt adjustment; stale trả 409, không ghi đè. Bổ sung pending attendance/shift/leave, định nghĩa rõ count và timestamp. Sửa mapping AC/evidence và browser harness backend thật với đủ viewport, role/error/loading/empty/reload/session, overflow/modal/keyboard/focus.

Phối hợp Tường xác nhận nguồn planned hours/period context, Tuấn xác nhận target/effective rules, Bảo xác nhận request categories. Chạy module tests; nếu SQL Server/browser thật chưa chạy thì ghi CHƯA XÁC MINH, không biến unit test thành E2E evidence. Commit riêng và push origin feature/cham-cong-bao-cao. Báo mã BI-R, SHA, lệnh/môi trường/kết quả test, evidence, dependency và lỗi còn mở.
```

### 9.5. Việc Anh cần làm và trình tự nhận kết quả

Baseline đã push tại `integration/moc-a-completion@ae8f740`; code sửa local tích hợp được nhận vào baseline là commit `6a0df73`, danh sách nằm trong `Docs/integration-baseline-moc-a.md`. Anh gửi prompt đúng người và yêu cầu mỗi người cập nhật branch từ baseline, nhưng tránh yêu cầu họ merge lẫn nhau cùng lúc.

**Có thể giao cả bốn prompt cùng lúc.** Tuấn làm rules/UI; Tường làm availability/period/UI; Bảo làm snapshot/notification/UI; Bình làm attendance version/responsive/evidence. Yêu cầu họ thông báo trước khi sửa contract chung. Sau đó nhận tích hợp theo thứ tự: (1) Tuấn + Tường kiểm thử effective rules, availability và plan gate; (2) Tường + Bảo kiểm thử mutation, swap, locking, event/outbox; (3) Bình nối report/dashboard với nguồn lịch và pending categories; (4) Anh kiểm tra migration/startup trên SQL Server, quyền API và luồng xuyên hệ thống, rồi mới merge nhánh tích hợp vào `main`.

Anh chịu trách nhiệm cấp V18 nếu thực sự cần migration mới; điều tra failure JWT chung nếu tái hiện; chuẩn bị DB QA cô lập; review conflict/schema và quyết định release. Không nhận bàn giao chỉ có “đã sửa”: cần branch + commit SHA, mã review, lệnh test và kết quả, bằng chứng môi trường thật nếu task yêu cầu, cùng danh sách lỗi chưa đóng.

### 9.6. Mẫu báo cáo thành viên gửi Anh

```text
Tên / task / mã review:
Nhánh và commit SHA đã push:
Baseline đã tích hợp:
Phần đã sửa và file chính:
Migration mới (nếu có) và version Anh đã cấp:
Lệnh test, môi trường, pass/fail/skip:
SQL Server/API/browser evidence:
Phần đã phối hợp với ai, contract nào đã chốt:
Phần chưa xác minh hoặc lỗi còn mở:
```

Chỉ đóng review khi code đã push đúng nhánh và bằng chứng khớp mức nghiệm thu. Unit test không thay SQL Server race/migration; screenshot không thay assertion/flow trình duyệt; interface chưa được bean/service thật sử dụng không được tính là tích hợp xong.
