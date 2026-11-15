# Module yêu cầu, nghỉ phép, ca trống và thông báo (Bảo — task v1.1)

Phạm vi: BA-01 … BA-06 trong `task_v1.1.md` mục 3.4, chủ trì **AC-08**, phối hợp **AC-12**.

## 1. Tóm tắt chức năng

| Mã | Chức năng | Backend | Giao diện demo |
|---|---|---|---|
| BA-01 | Đổi ca (swap) / nhờ làm thay (cover): gửi → người nhận đồng ý → Admin duyệt; hủy, từ chối | `/api/requests` | Nhân viên: **Đổi ca / Làm thay** · Admin: **Duyệt yêu cầu** |
| BA-02 | Duyệt an toàn: kiểm tra lại ràng buộc **lúc duyệt**; chống dữ liệu cũ (version); duyệt lặp không đổi lặp; lịch + trạng thái + audit + sự kiện trong 1 giao dịch | `ShiftRequestService.approve` | Nút Duyệt bị khóa khi có vi phạm |
| BA-03 | Ca trống: Admin mở cho ca đã công bố còn thiếu; nhân viên đăng ký; Admin chọn; **không vượt số chỗ**; đủ người thì tự từ chối đăng ký còn lại | `/api/open-shifts` | **Ca trống** (cả 2 vai trò) |
| BA-04 | Nghỉ phép cơ bản: loại nghỉ cấu hình, tài liệu bắt buộc theo loại, báo trước tối thiểu, xem ảnh hưởng lên ca, duyệt thì gỡ khỏi ca + cảnh báo thiếu người; vắng đột xuất + tỷ lệ vắng | `/api/leave` | Nhân viên: **Nghỉ phép** · Admin: tab Nghỉ phép / Vắng đột xuất |
| BA-05 | Thông báo in-app: danh sách, đọc/chưa đọc, đánh dấu, liên kết tới màn hình, phân trang; nhắc hạn đăng ký theo mốc Admin cấu hình | `/api/notifications` | Chuông trên topbar, trang **Thông báo** |
| BA-06 | Sự kiện tin cậy: outbox cùng DB, eventId tất định chống trùng, retry/backoff, trạng thái `dead`, không phát cho giao dịch rollback | `domain/events` | `HrStore.publish` (bản demo) |

## 2. Luồng đổi ca

```
Nhân viên A gửi ─► pending_peer ─(B đồng ý)─► pending_approval ─(Admin duyệt)─► approved
                     │ (B từ chối) ► peer_declined         │ (Admin từ chối) ► rejected
                     └─ A hủy ► cancelled ◄────────────────┘
```

- Chỉ ca **đã công bố** và **chưa bắt đầu** của chính người gửi mới được đổi.
- Kiểm tra cho người nhận (và cho người gửi khi đổi chéo): trùng giờ (kể cả ca qua đêm), nghỉ tối thiểu, giờ tối đa ngày/tuần/kỳ, Level bằng hoặc được phép thay thế.
- Mỗi ca chỉ có 1 yêu cầu đang mở (filtered unique index `ux_shift_requests_open_source`).
- Sau duyệt: `final_responsible_user_id` = người nhận; audit lưu before/after của phân công.

## 3. API

Tất cả cần JWT. `userId` luôn lấy từ token, không nhận từ body (AC-11). Lỗi trả về
`{"error": "...", "code": "...", "details": [...]}` với mã HTTP 400/403/404/409/422.

| Method | Đường dẫn | Ai | Ghi chú |
|---|---|---|---|
| POST | `/api/requests` | NV | `{type: swap\|cover, sourceAssignmentId, targetUserId, targetAssignmentId?, reason}` |
| GET | `/api/requests?scope=mine\|all&status=` | NV / Admin (all) | |
| GET | `/api/requests/{id}` | liên quan / Admin | |
| POST | `/api/requests/{id}/accept`, `/decline` | người nhận | `{expectedVersion, note}` |
| POST | `/api/requests/{id}/approve`, `/reject` | Admin | reject bắt buộc `note` |
| POST | `/api/requests/{id}/cancel` | người gửi | |
| GET | `/api/requests/cover-candidates?assignmentId=` | NV | ai đủ điều kiện làm thay + lý do |
| GET | `/api/requests/swap-options?targetUserId=` | NV | ca tương lai của đồng nghiệp |
| GET | `/api/pending-summary` | tất cả | bộ đếm việc chờ theo phạm vi |
| GET | `/api/leave/types` | tất cả | `?includeInactive=true` cho Admin |
| POST | `/api/leave` | NV | `{leaveTypeId, startDate, endDate, reason, documentUrl?}` |
| GET | `/api/leave?scope=mine\|all&status=` | NV / Admin | |
| GET | `/api/leave/{id}/impact` | chủ đơn / Admin | ca bị ảnh hưởng, độ phủ sau duyệt |
| POST | `/api/leave/{id}/approve`, `/reject`, `/cancel` | Admin / chủ đơn | |
| POST | `/api/leave/absences` | Admin | `{assignmentId, reason}` — ca đã diễn ra |
| GET | `/api/leave/absences/report?from=&to=` | Admin | số lượt vắng, tỷ lệ vắng |
| POST | `/api/open-shifts` | Admin | `{shiftId, slots?, requiredLevelId?, claimDeadline (giờ địa phương), note?}` |
| GET | `/api/open-shifts` | tất cả | NV chỉ thấy ca còn nhận được + ca đã đăng ký |
| POST | `/api/open-shifts/{id}/claims` | NV | |
| DELETE | `/api/open-shifts/{id}/claims/mine` | NV | rút đăng ký |
| POST | `/api/open-shifts/{id}/claims/{claimId}/approve`, `/reject` | Admin | |
| POST | `/api/open-shifts/{id}/close`, `/cancel` | Admin | |
| GET | `/api/notifications?unreadOnly=&page=&size=` | tất cả | size ≤ 100 |
| GET | `/api/notifications/unread-count` | tất cả | |
| POST | `/api/notifications/{id}/read`, `/read-all` | tất cả | chỉ thông báo của mình |
| GET/PUT | `/api/notifications/settings` | Admin | `{"deadlineReminderHours":[48,24]}`; `[]` = tắt |
| GET | `/api/admin/events?status=&type=&from=&to=&page=&size=` | Admin | BAO-01. `status`: `problem` (mặc định = failed + dead), `all` hoặc 1 trạng thái; `from/to` là ngày theo `app.timezone` |
| GET | `/api/admin/events/summary` | Admin | BAO-01. Số sự kiện theo trạng thái |
| POST | `/api/admin/events/{id}/retry` | Admin | BAO-01. `{reason, dispatchNow?=true}`; chỉ sự kiện `failed`/`dead` |
| POST | `/api/admin/events/retry` | Admin | BAO-01. `{status: problem\|dead\|failed, type?, reason}`; tối đa 200 sự kiện/lần |

## 4. Cơ sở dữ liệu — `V6__requests_leave_notifications.sql`

`shift_requests`, `open_shifts`, `open_shift_claims`, `leave_types` (seed 4 loại), `leave_requests`,
`absence_records`, `domain_events` (outbox), `notifications` (UNIQUE `event_id + recipient_id`),
`notification_settings` (mốc nhắc = NULL, tức chưa cấu hình).

Các ràng buộc quan trọng: `CHECK filled_count <= slots`, version cho duyệt lạc quan, CHECK trạng thái,
CHECK `ISJSON(recipients_json)`.

## 5. Hợp đồng với thành viên khác

**Tường — lịch.** Module này gọi hai interface trong `domain/schedule`:

- `AssignmentValidationService.validate(userId, shiftId, replacingUserId, ignoreAssignmentIds, requiredLevelId)`
- `AssignmentMutationService` gồm `findAssignment`, `findShift`, `findConfirmedAssignments`, `reassign`, `addAssignment`, `cancelAssignment`. Hàm sửa phải ném 409 khi dữ liệu khác giá trị mong đợi.

`InterimJdbcScheduleAdapter` là bản tạm để chạy được ngay. Khi Tường có service chính thức thì xóa lớp này. Bản tạm **chưa** có trần giờ theo mục tiêu × % (AC-01) và kiểm tra chứng chỉ.

**Tất cả — phát thông báo.** Gọi `DomainEventPublisher.publish(DomainEvent)` **bên trong** `@Transactional` của mình:

```java
events.publish(new DomainEvent(
    DomainEvent.idOf(EventTypes.SCHEDULE_PUBLISHED, planId, version),   // eventId tất định
    EventTypes.SCHEDULE_PUBLISHED, "schedule_plan", planId, version, actorId,
    affectedUserIds,                 // hoặc DomainEvent.ROLE_ADMIN
    "Lịch mới đã được công bố", "Lịch tuần 03/11 đã có.", "my-schedule"));
```

Ví dụ: Tường phát `schedule.published`, `schedule.changed`, `assignment.added` sau khi công bố hoặc sửa phân công.

**Anh — nền tảng.**

- Chuông và số việc chờ được gắn qua một dòng trong `App._buildTopbar`: `HrViews.mountShell(user)`.
- Có 7 route mới trong `ADMIN_ROUTES` / `USER_ROUTES`.
- `AuditTrail` ghi vào bảng `history`. Khi Anh có hạ tầng audit chung thì thay phần thân của lớp này.
- `ApiExceptionHandler` hiện chỉ áp dụng cho các package của module này.

**Tuấn — kỳ đăng ký.** Job nhắc hạn đọc `periods`, `period_applicable_users` và `availability_registrations`. Giả định `periods.deadline` lưu theo UTC, cần Tuấn xác nhận.

**Bình — báo cáo.** Dùng `GET /api/pending-summary` và `/api/leave/absences/report` cho Dashboard.

## 6. Cấu hình (`application.properties`)

`app.timezone`, `app.scheduling.enabled`, `app.events.poll-ms|batch-size|max-attempts`, `app.notifications.deadline-cron`.
Mốc nhắc hạn **không** đặt trong file cấu hình; Admin cấu hình qua API/màn hình (AC-13).

## 7. Kiểm thử

- Java (JUnit 5 + Mockito, không cần DB): `ShiftRequestServiceTest`, `LeaveServiceTest`, `OpenShiftServiceTest`,
  `NotificationServiceTest`, `DomainEventTest`, `InterimJdbcScheduleAdapterTest`. Chạy `mvn test`.
- Migration V6 cần chạy thử trên SQL Server thật (Flyway). H2 trong test không chạy migration.
- Demo trình duyệt: chạy `py -m http.server 8000`.
  1. Admin vào **Phân lịch tự động**, bấm **Áp dụng**, rồi vào **Duyệt & công bố** công bố các ca từ hôm nay trở đi.
  2. Đăng nhập nhân viên để thử đổi ca, nghỉ phép, ca trống.

## 8. Chưa làm (mốc B hoặc phụ thuộc người khác)

- ~~Số ngày phép được hưởng / đã dùng / còn lại (CO-01)~~ → đã làm ở BAO-03 (mục 11).
- Email và kênh gửi khác (CO-02).
- KPI (PE-01).
- ~~Duyệt theo quản lý trực tiếp~~ → đã làm ở BAO-04 (mục 12), theo `users.manager_id`; ma trận role/scope đầy đủ vẫn chờ B0 của Anh.
- ~~Chọn người tự động theo quy tắc cho ca trống~~ → đã làm ở BAO-02 (mục 10).

## 9. BAO-01 — Vận hành outbox (13/11/2026)

Mục tiêu: Admin xem được sự kiện thông báo gửi lỗi và gửi lại có kiểm soát. Đây là điều kiện trước khi mở email/kênh ngoài (CO-02).

**Code:** `api/events/` — `OutboxAdminController`, `OutboxAdminService`, `OutboxEventRepository`, `OutboxEventView`, `OutboxEventPage`, `OutboxEventFilter`. Giao diện: route `events` ("Sự kiện thông báo") trong `js/workspace.js`, chỉ Admin. **Không có migration mới** (dùng bảng `domain_events`, `notifications`, `history` sẵn có).

**Quy tắc:**

- Chỉ Admin (`CurrentUser.requireAdmin()`); actor lấy từ JWT.
- Chỉ gửi lại sự kiện `failed`/`dead`. `pending`/`processing`/`processed` → `409 EVENT_NOT_RETRYABLE`. Dòng sự kiện được khóa (`UPDLOCK, ROWLOCK`) trước khi kiểm tra; nếu trạng thái đổi chen giữa → `409 EVENT_CHANGED`.
- Bắt buộc lý do 5–500 ký tự (`400 REASON_REQUIRED`). Mỗi lần gửi lại ghi `history` (`outbox_event_retry` hoặc `outbox_event_retry_bulk`) với trạng thái/số lần thử/lỗi trước và sau.
- Gửi lại = đưa về `pending`, `attempts = 0`, thử ngay; giữ `last_error` cho tới khi gửi thành công. Đặt lại và audit nằm trong 1 giao dịch; `dispatchNow` chạy **sau commit**, nên lỗi khi gửi không làm mất audit, sự kiện quay về `failed` như luồng tự động.
- Không tạo thông báo trùng: người đã nhận ở lần thử trước không nhận thêm (UNIQUE `event_id + recipient_id`). Cột `notificationsCreated` trên màn hình để đối chiếu.
- Gửi lại hàng loạt chỉ đưa về hàng đợi (dispatcher định kỳ gửi), tối đa 200 sự kiện/lần, 1 bản audit liệt kê id.

**Kiểm thử:**

- Unit: `OutboxAdminServiceTest` (quyền, lọc/phân trang, đổi ngày nghiệp vụ sang UTC, lý do, 404/409, dispatch sau commit, rollback, bulk).
- SQL Server: `OutboxAdminIT` (cần `SM_RUN_SQL_TESTS=true`): sự kiện `dead` đã có 1 thông báo → gửi lại thành `processed`, vẫn 1 thông báo, có 1 audit; gửi lại lần 2 → 409.
- Browser: thêm route `events` vào `tests/moc-a-browser.mjs`; `tests/employee-accounts-browser.mjs` kiểm tra Employee không thấy menu và API trả 403.

**Đề xuất cho Anh (file dùng chung, chưa sửa):** thêm `"/api/admin/**"` vào dòng `hasRole("ADMIN")` trong `SecurityConfig` để chặn thêm ở lớp bảo mật. Hiện tại service đã chặn bằng `requireAdmin()` (Employee nhận 403).

## 10. BAO-02 — Duyệt đổi/thay ca có ngoại lệ + ca trống tự chọn người

**Migration:** `V19__open_shift_selection_mode.sql` — **số tạm, phải xin Anh xác nhận trước khi commit**. Thêm `open_shifts.selection_mode` (`manual` | `auto`, mặc định `manual`), `open_shifts.auto_processed_at`, `open_shift_claims.auto_rank`, `open_shift_claims.auto_note`.

### 10.1 Duyệt có ngoại lệ (đổi chéo và làm thay)

- Vi phạm chia 2 nhóm theo `ViolationCode.overridable()` của lõi lịch (Tường): **nhóm ngoại lệ** (`NOT_AVAILABLE_TIME`, `UNAVAILABLE`) và **ràng buộc cứng** (còn lại: trùng giờ, nghỉ tối thiểu, trần giờ, Level…).
- Gửi yêu cầu / đồng nghiệp đồng ý: chỉ ràng buộc cứng chặn; vi phạm nhóm ngoại lệ để người duyệt quyết định. Gợi ý người làm thay hiện nhãn "(cần ngoại lệ khi duyệt)".
- Duyệt không kèm ngoại lệ mà có vi phạm nhóm ngoại lệ → `422 EXCEPTION_REQUIRED`. Ràng buộc cứng → `422 VALIDATION_FAILED` kể cả khi gửi ngoại lệ.
- `GET /api/requests/{id}/approval-preview` (Admin có quyền `schedule.exception.approve`): trả từng vị trí (`source` = người nhận lấy ca gốc; `target` = người gửi lấy ca của người nhận, chỉ khi đổi chéo) với vi phạm và `previewToken`. Đổi chéo được xem trước **theo trạng thái cuối** (cả hai ca cũ đã gỡ), đúng ngữ cảnh lõi lịch dùng khi đổi.
- `POST /api/requests/{id}/approve` nhận thêm `exceptionReason`, `confirmException: true`, `previewTokens: {source, target}`. Lõi lịch kiểm tra lại vi phạm, token và quyền trong cùng giao dịch; dữ liệu đổi giữa lúc xem và lúc duyệt → `409 STALE_PREVIEW`. Ngoại lệ được ghi vào `schedule_exceptions` + `history`; audit của yêu cầu có loại `shift_request_approve_exception` và lý do ngoại lệ.
- **File dùng chung có sửa (cần Anh/Tường review):** `domain/schedule/Violation` (thêm cờ `overridable`, giữ constructor cũ), `AssignmentMutationService` (thêm phương thức `default`, không phá bản cài đặt cũ), `OfficialJdbcScheduleAdapter`, `OfficialScheduleService` (thêm `previewSwap`, `swap(...)` có override từng vị trí, `workload`; `swap` cũ gọi qua bản mới với override `null` nên hành vi cũ giữ nguyên).

### 10.2 Ca trống tự chọn người

- Tạo ca trống chọn `selectionMode = auto`. Hết hạn nhận → `OpenShiftAutoSelectJob` (mặc định mỗi phút, `app.open-shifts.auto-select-cron`) gọi `OpenShiftService.autoSelect`. Admin có thể chạy ngay: `POST /api/open-shifts/{id}/auto-select`.
- Thứ tự ưu tiên (giả định chờ nhóm chốt): (1) Level đúng yêu cầu trước Level cao hơn được phép thay; (2) tỷ lệ giờ đã phân trong kỳ / giờ mục tiêu kỳ thấp hơn (thiếu giờ mục tiêu → so số giờ); (3) đăng ký sớm hơn.
- Mỗi ứng viên được kiểm tra lại ràng buộc lúc chọn. **Tự động không duyệt ngoại lệ**: vi phạm nhóm ngoại lệ cũng bị loại. Mỗi đăng ký có `auto_rank` + `auto_note` giải thích.
- An toàn đồng thời: khóa dòng `open_shifts` trước, kiểm tra `auto_processed_at`, giữ chỗ bằng `UPDATE ... WHERE filled_count < slots`. Hai job song song chỉ một lượt xử lý; không vượt `slots`. Chưa đủ người → ca trống chuyển `closed` và gửi cảnh báo thiếu người cho Admin.
- Actor ghi lịch/audit là người tạo ca trống (hệ thống chọn thay mặt theo quy tắc đã công bố).

### 10.3 Kiểm thử

- `ShiftRequestExceptionApprovalTest` (10 test): ngoại lệ không lý do/không xác nhận bị chặn; ràng buộc cứng không override được; override đúng token cho từng vị trí; preview gộp hai vị trí; Employee không xem trước được.
- `OpenShiftAutoSelectTest` (8 test): thứ tự ưu tiên; không vượt chỗ; ứng viên vi phạm bị bỏ qua; không chạy lần hai; khóa dòng trước khi đọc; chưa hết hạn/chế độ manual bị từ chối.
- `OfficialScheduleSwapExceptionTest` (4 test, validator thật): xem trước đổi chéo theo trạng thái cuối, đổi có token ghi ngoại lệ, token cũ → 409, không có quyền → 403, tính giờ trong kỳ.
- SQL Server IT: chưa có — cần chạy migration V19 trên DB test của nhóm.

## 11. BAO-03 — Sổ phép (CO-01 phần 1)

**Migration:** `V20__leave_balances.sql` — **số tạm, xin Anh xác nhận**. Thêm `leave_types.tracks_balance` (bật cho `leave_annual`), `leave_requests.half_day`, `leave_requests.days_requested`, bảng `leave_policies`, `leave_balances`, `leave_balance_ledger`; seed chính sách chung 12 ngày/năm (BLLĐ 2019 Điều 113 — giả định chờ nhóm chốt).

**Code:** `api/leave/` — `LeaveBalanceService`, `LeaveBalanceRepository`, `LeaveBalanceController`, `LeaveBalanceView`, `LeavePolicyView`, `LeaveLedgerEntry`; `LeaveService` gọi sổ phép trong cùng giao dịch.

**Vòng đời số ngày** (chỉ loại nghỉ có `tracks_balance`): gửi đơn → `reserve` (đang chờ +) · duyệt → `consume` (đang chờ −, đã dùng +) · từ chối/hủy khi chờ → `release` · Admin hủy đơn đã duyệt (`POST /api/leave/{id}/revoke`, chỉ trước ngày nghỉ) → `restore`. Admin điều chỉnh → `adjust_up/adjust_down`. Cấp phép năm → `grant`.

**Quy tắc:**

- Số dư = tổng sổ cái (kiểm thử bằng cách tính lại từ sổ cái). CHECK `entitled + adjusted − used − pending ≥ 0` ở DB là lớp chặn cuối; mọi UPDATE số dư có điều kiện + khóa dòng.
- Hai Admin duyệt cùng lúc: đơn chỉ chuyển trạng thái được một lần (`decide` có điều kiện version) nên chỉ trừ một lần.
- Không đủ phép → `422 INSUFFICIENT_BALANCE` kèm "Năm X: cần A ngày, còn B ngày"; đơn bị rollback.
- Chính sách: loại nghỉ × loại nhân viên (`users.employee_type_id`) × ngày hiệu lực; không có chính sách riêng thì dùng chính sách chung; không có gì → `422 POLICY_MISSING` (không tự điền số ngày). **Không** mặc định Part-time mất phép. Chính sách không xóa, chỉ kết thúc hiệu lực; tạo chính sách chồng thời gian → `409 POLICY_OVERLAP`.
- Đơn vắt qua năm trừ vào số dư từng năm. Nửa ngày = 0,5, chỉ cho đơn một ngày.
- Đơn tạo trước V20 (chưa giữ chỗ) khi duyệt: giữ chỗ bổ sung rồi trừ, không đủ → 422.

**Giả định chờ nhóm chốt:** đếm ngày lịch (chưa trừ ngày nghỉ tuần/lễ); không chuyển phép sang năm sau; không chia phép theo ngày vào làm; nguồn loại nhân viên tạm dùng `users.employee_type_id`, khi Anh làm HR-02 (hợp đồng) có thể đổi nguồn.

**API:** `GET /api/leave/balances/me`, `GET /api/leave/balances?userId=&year=` (Admin), `GET /api/leave/balances/{id}/ledger`, `POST /api/leave/balances/adjust`, `GET|POST /api/leave/policies`, `POST /api/leave/policies/{id}/end`, `POST /api/leave/{id}/revoke`; `POST /api/leave` nhận thêm `halfDay`.

**Kiểm thử:** `LeaveBalanceServiceTest` (14 test, sổ phép trong bộ nhớ mô phỏng điều kiện SQL): chia ngày theo năm, nửa ngày, giữ chỗ/trừ/trả/hoàn khớp sổ cái, không đủ phép, duyệt hai lần chỉ trừ một lần, đơn cũ, chính sách Part-time/chung/thiếu, chồng chính sách, điều chỉnh (lý do, version, không âm, bội số 0,5), ẩn sổ cái người khác. SQL Server IT: chưa có.

## 12. BAO-04 — Duyệt nhiều bước theo quản lý + báo cáo vắng (CO-01 phần 2)

**Migration:** `V21__approval_flows.sql` — **số tạm, xin Anh xác nhận**. Bảng `approval_flows` (cấu hình) và `approval_steps` (các bước của từng đơn). Seed cả hai đối tượng ở chế độ `admin_only` → **hành vi cũ giữ nguyên** cho tới khi Admin đổi.

**Code:** package mới `api/approvals/` — `ApprovalFlowService`, `ApprovalRepository`, `ApprovalFlowController`, `ApprovalStep`, `ApprovalFlow`. `LeaveService` và `ShiftRequestService` gọi chung một dịch vụ.

**Quy trình** (theo đối tượng; đơn nghỉ có thể riêng theo loại nghỉ): `admin_only` · `manager` (1 bước) · `manager_then_admin` (2 bước).

- Bước quản lý chốt người duyệt = `users.manager_id` lúc đơn bắt đầu chờ duyệt (đơn nghỉ: lúc gửi; đổi ca: lúc đồng nghiệp đồng ý). Không có quản lý đang hoạt động → bước quản lý `skipped` kèm lý do, chuyển Admin.
- Quản lý chỉ duyệt/từ chối đơn có bước đang chờ đúng mình (`403` nếu không); người không liên quan xem đơn → `404`. Quản lý trực tiếp và người duyệt của một bước được xem đơn.
- **Lịch và số dư phép chỉ đổi ở bước cuối.** Bước giữa chỉ ghi bước + audit `leave_step_approve` / `shift_request_step_approve` và thông báo người duyệt kế tiếp (`leave.step_approved`, `shift_request.step_approved`).
- Admin (toàn quyền V1) duyệt ở bước nào cũng là duyệt cuối; các bước còn lại `skipped` "Admin duyệt thay". Ngoại lệ đổi ca (BAO-02) vẫn cần quyền `schedule.exception.approve`.
- Loại nghỉ yêu cầu tài liệu: kiểm tra lại ở mọi bước (`422 DOCUMENT_REQUIRED`).
- Chuyển trạng thái bước là UPDATE có điều kiện (version + status) → hai người duyệt cùng lúc: một bên `409 STALE_STEP`.

**Báo cáo vắng mặt:** `GET /api/leave/absences/summary?from=&to=&groupBy=employee|department|leaveType` và bản CSV `.../summary.csv` (UTF-8 có BOM, chặn công thức Excel). Admin: toàn công ty; quản lý: cấp dưới trực tiếp; nhân viên thường: `403`. Tỷ lệ vắng = vắng đột xuất / phân công confirmed; mẫu số 0 → `null` ("Chưa đủ dữ liệu"). API này để Bình dùng cho Dashboard.

**API khác:** `GET|PUT /api/approval-flows`, `GET /api/approval-flows/me`, `GET /api/leave/{id}/steps`, `GET /api/requests/{id}/steps`, `GET /api/leave?scope=team|approvals`, `GET /api/requests?scope=approvals`. Giao diện: route mới **Hộp duyệt** (Admin và nhân viên có cấp dưới).

**Kiểm thử:** `ApprovalFlowServiceTest` (10), `LeaveApprovalStepsTest` (9), `ShiftRequestApprovalStepsTest` (3): quản lý ngoài phạm vi không duyệt/không xem; lịch không đổi ở bước quản lý; Admin là bước cuối; không có quản lý → Admin; tài liệu kiểm tra mỗi bước; báo cáo theo phạm vi, theo bộ phận, mẫu số 0; CSV chống công thức. Đổi ở test cũ: `ShiftRequestServiceTest.approve_requiresAdmin` nay đọc yêu cầu trước khi xét quyền (quyền phụ thuộc bước), vẫn 403 và không đổi lịch.
