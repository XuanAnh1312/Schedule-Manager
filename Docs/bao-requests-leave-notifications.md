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

- Số ngày phép được hưởng / đã dùng / còn lại (CO-01).
- Email và kênh gửi khác (CO-02).
- KPI (PE-01).
- Duyệt theo quản lý trực tiếp: hiện chỉ Admin duyệt, chờ phân quyền theo phạm vi của Anh.
- Chọn người tự động theo quy tắc cho ca trống.
