# Baseline tích hợp task 3.2–3.5 (mốc A)

**Baseline code đã push:** `integration/moc-a-completion` — commit `6a0df73870baee7ab4ad9a16df151d9debae4c2b` (`fix v1.1 lần 1 khoảng 50%`). Tài liệu này chốt hợp đồng để các nhánh thành viên tiếp tục sửa mà không tự tạo schema/API cạnh tranh. Commit tài liệu baseline này là phần bổ sung cho commit code trên.

## 1. Flyway: version duy nhất và thứ tự phụ thuộc

Không giữ lại V6/V7 trùng số của các nhánh gốc. Bộ migration tích hợp duy nhất là:

| Version | File tích hợp | Chủ sở hữu nội dung | Phụ thuộc/ghi chú |
|---|---|---|---|
| V1–V5 | Các migration nền hiện có | Nền tảng | Giữ nguyên, không sửa migration đã áp dụng. V5 tạo users, periods, shifts/assignments, availability và rules nền. |
| V6 | `V6__schedule_rules_targets_and_plans.sql` | Tường | Sau V5; schedule rules/targets và schedule plans. |
| V7 | `V7__period_lifecycle_availability_rules.sql` | Tuấn | Sau V5–V6; lifecycle kỳ, slot type, rule scope/version. Không seed rule nghiệp vụ tự kích hoạt. |
| V8 | `V8__shift_management.sql` | Tường | Danh mục và yêu cầu ca; dùng users/levels nền. |
| V9 | `V9__blocked_dates_and_contract_limit.sql` | Tường | Ngày không thể làm và giới hạn hợp đồng. |
| V10 | `V10__shift_break_and_version.sql` | Tường | Nghỉ giữa ca và version ca. |
| V11 | `V11__schedule_plan_statuses.sql` | Tường | Trạng thái phương án và index kỳ. |
| V12 | `V12__schedule_workflow_and_exceptions.sql` | Tường | Workflow, quyền, ngoại lệ và xác nhận lịch. |
| V13 | `V13__requests_leave_notifications.sql` | Bảo | Request/leave/open shift, domain event outbox, notification; phụ thuộc users, shifts, assignments, plans. |
| V14 | `V14__attendance_reports.sql` | Bình | Attendance, adjustment, khóa công/audit; phụ thuộc users, shifts, periods. |
| V15 | `V15__integration_scopes_and_attendance_version.sql` | Nền tảng + Tuấn/Bình | Danh mục branch/type/group và `base_record_version`; phụ thuộc V13–V14 cùng schema tổ chức hiện có. |
| V16 | `V16__bind_shifts_to_periods.sql` | Tường + nền tảng | Thêm `shifts.period_id`, backfill chỉ khi xác định duy nhất, index; phụ thuộc V6/V11 và shifts. |
| V17 | `V17__request_schedule_snapshots.sql` | Bảo | Version snapshot ca/phân công trong shift request; phụ thuộc V13 và version từ V10. |

V15–V17 đã có trong baseline code `6a0df73`; thứ tự chạy theo số version. Migration V16 có thể để `period_id` NULL cho dữ liệu không xác định duy nhất; không được tự chọn kỳ đầu tiên, kỳ nhỏ nhất hoặc kỳ gần nhất. Version mới tiếp theo phải là **V18**, do Anh điều phối và thông báo trước khi tạo migration. Mỗi nhánh chỉ thêm migration mới sau khi nhận version; không đổi nội dung migration đã phát hành hoặc đã chạy trên DB dùng chung.

**Dữ liệu cũ/DB đã chạy nhánh riêng:** DB mới và DB V1–V5 sạch chạy chuỗi tích hợp V1–V17 theo thứ tự. Nếu DB từng chạy V6/V7 theo nhánh riêng, dừng trước khi chạy chuỗi tích hợp; đối chiếu `flyway_schema_history`, schema và dữ liệu để viết kế hoạch chuyển đổi riêng. Không sửa checksum, xóa history, rename migration đã chạy hoặc xóa DB để ép Flyway qua.

## 2. Hợp đồng scope và effective rules

`RuleScopeType` và `rule_settings.scope_type` dùng đúng các giá trị: `company`, `branch`, `employee_type`, `group`, `user`. `company` có `scope_id = NULL`; bốn loại còn lại bắt buộc scope ID hợp lệ. Branch tra theo danh mục location/branch chuẩn của dự án; employee type và group dùng `employee_types`/`work_groups` từ V15. Không nhận `branchId`, `employeeTypeId`, `groupId` hay `userId` áp dụng từ payload tính lịch làm danh tính đáng tin cậy: context được dựng từ employee do backend truy vấn.

Resolver nhận `ScopeContext(userId, branchId, employeeTypeId, groupId, ...)` và ngày nghiệp vụ. Chỉ xét setting `active` có `effective_from <= ngày áp dụng`; với mỗi parameter chọn scope cụ thể nhất theo thứ tự **user > group > employee_type > branch > company**. Nếu không có cấu hình cho parameter bắt buộc thì trả missing/checklist và không lập/công bố lịch bằng giá trị mặc định suy đoán. Nếu có hai setting cùng parameter, cùng scope cụ thể và cùng effective date gây xung đột thì trả lỗi cấu hình; không chọn ngẫu nhiên theo thứ tự DB.

Effective response giữ giá trị, nguồn scope, ngày áp dụng, revision/version, derived target/max và checklist missing/issues. Preview dùng cùng resolver/context như effective API và engine; apply cần expected base revision, quyền Admin, reason, xác nhận và audit before/after. Trần cuối cùng không vượt giới hạn hợp đồng hay hard constraint. `target_rate` (mục tiêu cá nhân) không được tự đồng nhất với `max_hours`/tỷ lệ trần; không tự seed hoặc bật 60/75 giờ. Engine Tường tiêu thụ effective rules qua adapter, không tự đọc một bộ schedule_rules khác.

## 3. `period_id` của ca và backfill

`shifts.period_id` là định danh kỳ nghiệp vụ; ngày ca chỉ là thuộc tính ngày, không đủ nhận diện kỳ vì hai kỳ có thể chồng ngày. API tạo/sửa ca nhận period ID tường minh, kiểm tra kỳ tồn tại, ngày ca nằm trong kỳ theo quy tắc đã thống nhất và quyền người gọi. Calendar, plan, coverage, request, attendance/report lọc bằng cùng period ID. Các kiểm tra giờ tuần/nghỉ/ngày liên tiếp vẫn nạp assignment lân cận ngoài kỳ khi chúng ảnh hưởng hard constraint.

V16 backfill theo thứ tự: (1) dùng kỳ từ plan khi một shift chỉ liên hệ đúng một `schedule_plans.period_id`; (2) nếu chưa có thì gán khi `shift_date` nằm trong đúng một period; (3) nếu không tìm được hoặc có nhiều kỳ phù hợp, giữ NULL để Admin phân loại. Không chuyển các dòng NULL thành một kỳ mặc định, không xóa ca/assignment. Trước khi Admin gán kỳ, API/report phải hiển thị trạng thái chưa phân loại và loại khỏi số liệu yêu cầu period cụ thể; mutation cần kỳ phải từ chối ca NULL. Sau xử lý thủ công ghi audit. Tuấn/Tường/Bình không tạo backfill khác trong module riêng.

## 4. Version snapshot cho request đổi/thay ca và sửa công

Shift request chụp `source_assignment_version`, `source_shift_version`, và nếu có ca đích thì `target_assignment_version`, `target_shift_version` khi gửi request. Kiểm tra snapshot ở bước peer accept và manager approve; nếu bất kỳ version nào đổi, trả conflict `SCHEDULE_CHANGED` và yêu cầu hủy/tạo request mới. Version request riêng chỉ chống cập nhật đè request, không thay snapshot lịch. Snapshot cũ NULL không đủ cơ sở để approve; được reject/cancel, còn muốn approve thì gửi lại request mới. So sánh và mutation nằm trong cùng transaction với khóa/version check.

Attendance adjustment lưu `base_record_version` tại lúc gửi. Khi duyệt, khóa request/record, xác nhận record vẫn tồn tại và version bằng snapshot; stale trả 409, không ghi đè công mới. Yêu cầu bổ sung khi chưa có record giữ snapshot NULL nhưng lúc duyệt phải kiểm tra chưa có record mở/trùng, khóa kỳ chưa bật và ràng buộc ca/người vẫn đúng. Audit bản gốc/proposed, quyết định, actor/reason cùng transaction.

## 5. Event và transactional outbox

Mutation nghiệp vụ và insert `domain_events` phải cùng transaction SQL. Giao dịch rollback thì không có event; dispatcher chỉ xử lý event đã commit, có retry/backoff và trạng thái lỗi. `eventId` tất định, duy nhất, gắn event type + entity ID + entity version; publish lặp không tạo event/notification trùng. Payload thống nhất: eventId, eventType, entityType, entityId, entityVersion, actorId lấy từ server, recipient IDs/roles, occurredAt UTC, title/body/linkRoute. Không tin actor/recipient tùy tiện từ client; producer quyết định đúng người nhận theo nghiệp vụ.

Tường phát schedule event qua `SpringScheduleEventSink` sang `DomainEventPublisher` của Bảo; Bảo sở hữu bảng outbox, dispatcher và notification delivery. Không giữ listener log cũ như cơ chế giao nhận thứ hai, không phát notification trước commit. Lỗi xử lý một recipient phải retry idempotent; unique `(event_id, recipient_id)` ngăn duplicate. Event sinh từ swap chỉ phản ánh trạng thái cuối đã commit, không gửi trung gian sau khi mới hủy một assignment.

## 6. Tên bean report

Attendance report của Bình dùng bean `attendanceReportService`; schedule report của Tường giữ bean mặc định `reportService` (hoặc được inject bằng qualifier rõ ràng). Controller đặt tên bean tường minh nếu component scan có thể trùng `reportController`; không bật bean overriding. Khi Bình gọi schedule report phải truyền rõ period, variant/context, plan/version và filter; không tự tính lại planned hours bằng truy vấn rule legacy. Startup test profile SQL Server phải xác nhận cả hai controller/service được tạo.

## 7. Trạng thái plan chặn archive kỳ

`SchedulePlanGate.unfinishedPlans(periodId)` chặn archive nếu kỳ còn plan trạng thái `draft`, `pending_approval` hoặc `approved`. Chỉ cho archive khi không còn plan ở ba trạng thái này. `published`, `rejected`, `cancelled`, `archived` được xem là trạng thái kết thúc đối với điều kiện này. Trạng thái `approved` vẫn chặn vì chưa kết thúc publish lifecycle. Implementation SQL Server là `PeriodPlanGate` → `PlanRepository.unfinishedPlans`; cấu hình production phải nạp bean này, không dùng chế độ Optional vắng bean để âm thầm cho archive.

Archive kiểm tra gate trong luồng cập nhật kỳ và trả danh sách plan/status đang chặn; lỗi cạnh tranh giữa archive và mutation plan phải được bảo vệ bằng transaction/locking phù hợp. Không chuyển status plan từ API kỳ.

## 8. Các sửa tích hợp local đã được nhận vào baseline code

Danh sách dưới đây đã có trong commit đã push `6a0df73`, nên không giao chủ module làm lại nguyên trạng. Hãy review chúng như code tích hợp hiện hữu và chỉ sửa phần còn thiếu/regression:

| Nội dung đã nhận | File/điểm nhận diện trong baseline code |
|---|---|
| Hợp nhất migration: Bảo V13, Bình V14; bỏ hai migration V6 trùng và migration seed 60/75 khỏi chuỗi active | `src/main/resources/db/migration/V13…V17`; `Docs/legacy-migrations/` |
| Scope context từ DB và validate scope ID branch/type/group | `api/rules/RuleRepository.scopeContext/scopeExists`, `RuleService.contextOf`, `V15` |
| Kỳ chặn archive khi có plan chưa kết thúc | `schedule/plan/PeriodPlanGate`, `PlanRepository.unfinishedPlans` |
| Ca có period_id và backfill chỉ khi duy nhất; request lưu snapshot version | `V16`, `V17`, `ShiftRequestRepository.captureScheduleSnapshot/scheduleUnchanged` |
| Attendance adjustment base version | `V15`, `AttendanceService` |
| Adapter schedule gọi core chính thức; swap bỏ hai assignment cũ trước khi thêm hai assignment cuối trong cùng transaction | `OfficialJdbcScheduleAdapter`, `ShiftRequestService` |
| Open Shift khóa dòng cha trong claim/withdraw/approve/reject/close | `OpenShiftRepository.lock`, `OpenShiftService` |
| Schedule event nối sang domain event outbox | `SpringScheduleEventSink`, `DomainEventPublisher`, `OutboxDispatcher` |
| Tránh bean service report trùng tên mặc định; report Bình gọi nguồn planned hours của schedule và period_id | `api/report/ReportService` bean `attendanceReportService`, `schedule/report/ReportService` |
| Slot available/unavailable và nghỉ đã duyệt đi vào availability/validator; lọc schedule theo kỳ và nạp assignment lân cận | `UserAvailability`, `ScheduleDataRepository`, `AssignmentValidator`, `V16` |
| PeriodPlanGate/Workspace, test SQL integration/browser harness và cập nhật testing docs | commit `6a0df73`; các test/evidence vẫn cần chạy theo quy trình QA |

“Đã nhận” ở đây nghĩa là code đã có trong baseline commit, không đồng nghĩa mọi AC đã đạt. Các migration V15–V17 đã được đưa vào baseline nhưng vẫn cần SQL Server QA trên DB mới và DB baseline; các test unit không thay thế race/rollback, role/API hoặc browser evidence. Nếu nhóm sửa một điểm trong bảng, tạo commit follow-up trên nhánh sở hữu module và dẫn lại mã review.

## 9. Quy tắc làm việc từ baseline này

Mọi nhánh thành viên lấy `integration/moc-a-completion` commit `6a0df73` làm baseline (merge/rebase theo lịch sử nhánh của nhóm; không force push). Push sửa tiếp vào đúng nhánh feature đã ghi trong `Docs/review-task-3.2-3.5.md`. Version tiếp theo V18 phải hỏi Anh trước khi thêm migration. Nếu phát hiện bất đồng hợp đồng, mở commit sửa tài liệu này trước hoặc cùng commit code; không tự đổi schema giữa chừng.
