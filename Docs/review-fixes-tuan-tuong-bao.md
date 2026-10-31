# Task 3.2 (Tuấn), Task 3.3 (Tường) và 3.4 (Bảo) — xử lý review TU-R1…TU-R3, TW-R1…TW-R5 và BA-R1…BA-R5


## 1. Tuấn — task 3.2

| Mã | Đã sửa | File chính |
|---|---|---|
| TU-R1 | Resolver không còn chọn ngẫu nhiên khi có hai giá trị **cùng mức phạm vi và cùng ngày hiệu lực**: trả `issues` dạng `CONFLICT: <tham số> …`, nhân viên không sẵn sàng phân lịch, tham số đó không bị báo nhầm là "chưa cấu hình". | `api/rules/EffectiveRuleResolver.java` |
| TU-R1 | `effectiveForScheduling` (nguồn của engine Tường) không còn âm thầm dùng quy tắc công ty khi không tìm thấy hồ sơ nhân viên: trả issue `SCOPE_CONTEXT_MISSING` và `readyForScheduling = false`. Context branch/employee_type/group vẫn đọc từ bảng `users` như baseline. | `api/rules/RuleService.java` |
| TU-R2 | `SchedulePlanGate` là dependency **bắt buộc** của `PeriodService` (không còn `Optional`): thiếu bean thì ứng dụng không khởi động, không có chế độ vắng bean để cho lưu trữ kỳ. Lưu trữ khóa dòng kỳ trước khi hỏi gate; module lịch cũng khóa dòng kỳ trước khi tạo/công bố phương án. | `api/periods/PeriodService.java`, `SchedulePlanGate.java` |
| TU-R3 | Giao diện (`js/workspace.js`): danh sách kỳ có cột hạn đăng ký theo múi giờ của kỳ và nhãn "Tự khóa khi hết hạn / Chỉ khóa thủ công (autoLock tắt)"; màn hình đăng ký của nhân viên hiện hạn + cách khóa và lý do không sửa được; quy tắc hiệu lực của nhân viên hiện nguồn phạm vi, ngày hiệu lực, mã thay đổi và lỗi cấu hình; bản xem trước quy tắc hiện lý do, revision gốc, từng mục thay đổi, thay đổi theo nhân viên, kỳ liên quan và có nút "Bỏ bản xem trước". | `js/workspace.js` |

Màn hình nghiệm thu là `workspace.html` (trang `index.html` chuyển thẳng tới đây) và chỉ đọc/ghi qua API; token đăng nhập lưu trong `sessionStorage`, không lưu dữ liệu nghiệp vụ ở trình duyệt. Các file cũ `js/admin*.js`, `js/user.js` (dùng `localStorage`) chỉ còn được `demo.html` nạp, không thuộc luồng nghiệm thu.

## 2.1. Thay đổi hợp đồng ảnh hưởng module khác

| Mã | Thay đổi | Ảnh hưởng |
|---|---|---|
| TW-R3 | `POST/PUT /api/schedule/shifts` (cả bulk, emergency, clone, repeat) **bắt buộc `periodId`**; ngày ca phải nằm trong kỳ chưa lưu trữ. Ca đã có phân công hoặc nằm trong phương án không được chuyển sang kỳ khác. | UI tạo ca đã gửi `periodId` của kỳ đang chọn. Client khác phải gửi `periodId`. |
| TW-R3 | Ca `period_id = NULL` không còn được đoán kỳ theo ngày. Kiểm tra/sửa lịch chính thức, self-check và check API trả `409 SHIFT_PERIOD_UNASSIGNED`. Admin phân loại bằng cách sửa ca và chọn kỳ (có audit `shift_update`). | Bảo: đổi ca/nghỉ/ca trống trên ca chưa phân loại nhận 409 thay vì chạy theo kỳ đoán. |
| TW-R3 | `GET /api/schedule/calendar?periodId=` chỉ trả ca gắn đúng kỳ; ca chưa phân loại chỉ hiện khi không lọc kỳ. Mỗi ca có thêm trường `periodId` (null = chưa phân loại). Ngày mặc định của calendar theo `Clock` nghiệp vụ (Asia/Ho_Chi_Minh). | UI hiện nhãn "Chưa phân loại kỳ". |
| TW-R4, BA-R3 | `AssignmentMutationService.swap(firstAssignmentId, firstUserId, secondAssignmentId, secondUserId, actorId, reason)`: đổi ca nguyên tử, kiểm tra hai vị trí theo trạng thái cuối, khóa các kỳ liên quan theo thứ tự id, một bản ghi lịch sử `manual_swap` và tối đa **một** sự kiện `schedule.changed` (`eventId = schedule.changed:<firstAssignmentId>:v<version+1>`). `ShiftRequestService.approve` dùng hàm này thay cho chuỗi hủy×2/thêm×2 (không còn thông báo trung gian). | Bảo: xem `Docs/review-fixes-tuan-bao.md` (BA-R1) về việc lõi lịch không phát thêm thông báo khi được gọi từ module yêu cầu. |
| TW-R5 | `OfficialJdbcScheduleAdapter` chuyển lỗi lõi lịch sang `ApiException` đúng hợp đồng: `PlanException` giữ nguyên `code`/HTTP status/`details` (409 xung đột version, 422 `HARD_VIOLATIONS`/`EXCEPTION_REQUIRED`, 409 `SHIFT_PERIOD_UNASSIGNED`, 409 `ALREADY_ASSIGNED`); ca không tồn tại → 404; dữ liệu không hợp lệ → 422 `VALIDATION_FAILED`. | Các API `/api/requests`, `/api/leave`, `/api/open-shifts` không còn trả 500 khi lõi lịch từ chối. |
| TW-R5 | Lưu/chạy lại phương án và công bố khóa dòng kỳ (`UPDLOCK, HOLDLOCK`) **trước** khi đọc trạng thái kỳ. Lưu trữ kỳ cũng khóa dòng này rồi gọi `SchedulePlanGate`, nên không thể archive chen giữa lúc tạo plan. Chạy thử (dry-run) không khóa. | Tuấn: không cần đổi. |
| TW-R1 | Báo cáo giờ (`/api/schedule/reports/hours`): nhân viên thiếu tham số bắt buộc có `limitStatus = "missing_config"`, `maxHoursPerWeek`/`maxHoursPerPeriod`/`maxHoursFormula` = null (trước đây so với trần 0 và báo `over_limit` sai). `maxHoursPerWeek` đổi từ `double` sang `Double`. | Bình: xử lý giá trị null và trạng thái mới khi dùng `EmployeeHours`. |

## 2.2. Thay đổi nội bộ

- TW-R1: bỏ `AssignmentValidator.weeklyLimit` (code chết dùng công thức legacy `target × target_rate`); cập nhật Javadoc `TableRulesSource`, `EffectiveRulesSource`, `EffectiveRules` theo nguồn thật `RuleService.effectiveForScheduling`.
- TW-R5 UI (`js/workspace.js`): calendar Admin có thêm/thay/hủy phân công trên lịch chính thức (vi phạm thuộc nhóm ngoại lệ → xem trước → xác nhận → gửi kèm `override`); phương án nháp có nút "Chuyển ca" (`/assignments/move`). Lỗi API giữ `code`/`status` trên đối tượng Error.

## 2.3. Điểm còn cần nhóm chốt

1. **Trần giờ/kỳ khi hai kỳ chồng ngày** (Tuấn, Bình): `AssignmentValidator.checkHours` và engine đang cộng mọi phân công rơi vào khoảng ngày của kỳ, kể cả ca thuộc kỳ khác. Cần chốt tính riêng theo `period_id` hay chung theo ngày.
2. **`understaffed_policy`** vẫn đọc từ bảng `schedule_rules` (`PlanRepository.understaffedPolicy`); chưa có tham số tương ứng trong danh mục rule của Tuấn.
3. **Thông báo khi duyệt đổi/thay ca, nghỉ phép, ca trống**: đã xử lý theo phương án "module gọi giữ thông báo nghiệp vụ, lõi lịch không phát thêm" (xem `Docs/review-fixes-tuan-bao.md`, BA-R1). Nhóm cần xác nhận phương án này.
4. **Swap khi vi phạm thuộc nhóm ngoại lệ** (ví dụ chưa đăng ký rảnh): hiện bị chặn bằng `422 EXCEPTION_REQUIRED` vì luồng duyệt yêu cầu không có bước xem trước ngoại lệ.

## 2.4. Kiểm thử

- `mvn test`: toàn bộ unit test đạt (xem số liệu trong báo cáo bàn giao). Test mới: `AvailabilityAndRulesValidationTest` (unavailable trong ngày rảnh, ngày không slot, slot/khoảng không thể làm qua đêm, nghỉ đã duyệt, thiếu tham số bắt buộc, hợp đồng chặt hơn), swap theo trạng thái cuối/rollback/xung đột, ca chưa gắn kỳ, khóa kỳ khi tạo plan, báo cáo `missing_config`, tạo/sửa ca bắt buộc kỳ.
- **CHƯA XÁC MINH:** `IntegratedMocAIT`, `IssueFiveFlowIT` (cần `SM_RUN_SQL_TESTS=true` và DB SQL Server QA), race archive ↔ tạo plan trên SQL Server thật, giao diện trên trình duyệt (máy làm việc không có Node để chạy `tests/moc-a-browser.mjs`).

## 3. Bảo — task 3.4

| Mã | Đã sửa | File chính |
|---|---|---|
| BA-R1 | `InterimJdbcScheduleAdapter` chỉ còn là lớp nền đọc dữ liệu, không phải bean; `OfficialJdbcScheduleAdapter` là bean duy nhất của `AssignmentMutationService`/`AssignmentValidationService` (có test nạp context xác nhận). **Thông báo kép:** khi lịch đổi do duyệt đổi/thay ca, duyệt nghỉ, duyệt ca trống, module của Bảo đã phát sự kiện nghiệp vụ cho đúng người, nên lõi lịch được gọi với `notify = false` (vẫn ghi lịch sử, version, kiểm tra ràng buộc). Admin sửa trực tiếp lịch chính thức vẫn nhận sự kiện lịch như cũ. | `domain/schedule/*Adapter.java`, `schedule/official/OfficialScheduleService.java` |
| BA-R2 | Tạo yêu cầu mà không chụp được snapshot (ca/phân công gốc không còn) → 409 và rollback, không để yêu cầu thiếu snapshot. Khi người nhận đồng ý và khi Admin duyệt: khóa dòng kỳ của các ca liên quan theo thứ tự id rồi mới so snapshot (lịch không đổi chen giữa lúc so và lúc ghi). Không đọc được dữ liệu gốc → `409 SCHEDULE_CHANGED` (trước đây âm thầm cho qua); yêu cầu cũ có snapshot NULL → `409 SCHEDULE_SNAPSHOT_MISSING`, chỉ được từ chối/hủy. | `api/requests/ShiftRequestRepository.java`, `ShiftRequestService.java` |
| BA-R3 | Swap gọi `AssignmentMutationService.swap(...)` nguyên tử của lõi lịch (chi tiết trong tài liệu của Tường). | `ShiftRequestService.java` |
| BA-R4 | Code baseline đã khóa dòng `open_shifts` đầu tiên ở claim/withdraw/approve/reject/close; bổ sung test bảo đảm thứ tự này. Thứ tự khóa chung: `open_shifts` → kỳ (lõi lịch) → phân công. | `OpenShiftServiceTest.java` |
| BA-R5 | Màn hình thông báo: số chưa đọc/tổng, phân trang theo `total` (trước đây đoán theo số dòng), thời gian đổi từ UTC sang giờ Việt Nam, ẩn nút "Đã đọc" với thông báo đã đọc. Đánh dấu đọc ở API chỉ áp dụng cho người nhận (`recipient_id`). Nhắc hạn: xác nhận `periods.deadline` lưu UTC, chỉ nhắc kỳ `open`, chỉ người chưa gửi; mở lại kỳ có hạn mới nên nhắc theo hạn mới. Nghỉ phép không phân biệt loại nhân viên (Part-time vẫn gửi đơn); nghỉ đã duyệt chặn cứng trong validator (TW-R2). | `js/workspace.js`, `DeadlineReminderJob.java` |

## 4. Điểm còn cần nhóm chốt

1. **Thông báo một nguồn (BA-R1):** phương án đang áp dụng là "module gọi giữ thông báo nghiệp vụ, lõi lịch im lặng". Nếu nhóm muốn ngược lại (lõi lịch phát `schedule.changed`, module yêu cầu không phát), cần đổi cả `ShiftRequestService`, `LeaveService`, `OpenShiftService`.
2. **Cách giải quyết `CONFLICT` quy tắc (TU-R1):** dữ liệu trùng chỉ xuất hiện nếu DB có hai dòng active cùng tham số/phạm vi/ngày (luồng apply hiện chuyển bản cũ sang `superseded`). Cần quy trình Admin gỡ bản thừa bằng thao tác `remove` + đặt lại giá trị.
3. **Revision trong effective response (TU-R1):** response trả `source.changeId` và `effectiveFrom` cho từng giá trị; chưa trả số `rule_revision` toàn cục. Cần chốt có thêm trường này hay không.

## 5. Kiểm thử

- `mvn test`: toàn bộ unit test đạt. Test mới: xung đột cùng mức, ưu tiên user > group > employee_type > branch > company, hai chi nhánh khác giới hạn, context thiếu hồ sơ, gate bắt buộc và thứ tự khóa archive, snapshot thiếu/đổi/khóa trước khi so, lõi lịch không phát thông báo kép, thứ tự khóa Open Shift, bean duy nhất của adapter và gate.
- **CHƯA XÁC MINH:** race thật trên SQL Server (hai người nhận chỗ cuối, claim cạnh tranh close, duyệt yêu cầu cạnh tranh sửa lịch, archive cạnh tranh tạo phương án), rollback outbox trên DB thật, `IntegratedMocAIT`/`IssueFiveFlowIT` (cần `SM_RUN_SQL_TESTS=true`), giao diện trên trình duyệt (máy làm việc không có Node để chạy `tests/moc-a-browser.mjs`).
