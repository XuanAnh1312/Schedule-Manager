# Bàn giao phần việc của Bảo (BA-01…06, BAO-01…06)

Cập nhật: 15/11/2026 · Người phụ trách: Bảo (Hoifbao)
Tài liệu chi tiết module: `Docs/bao-requests-leave-notifications.md` (được cập nhật trong từng PR BAO-xx).

## 1. Tóm tắt

| Mốc | Nội dung | Nhánh | Trạng thái |
|---|---|---|---|
| Mốc A – BA-01…06 | Đổi/nhận/thay ca, ca trống, nghỉ phép, thông báo, outbox | `feature/bao-requests-leave-notifications` (`0db82b8`) | Đã nằm trong `HRM_v1.1` và `main` |
| BAO-01 | Vận hành outbox: xem + gửi lại sự kiện lỗi, có audit | `feature/bao-01-outbox-ops` (`75d78d4`) | Đã push, PR #19 chờ merge |
| BAO-02 | Duyệt đổi/thay ca có ngoại lệ + ca trống tự chọn người | `feature/bao-02-exception-approve-auto-claim` | PR xếp chồng, base = BAO-01 |
| BAO-03 | Sổ phép (CO-01 phần 1) | `feature/bao-03-leave-balance` | PR xếp chồng, base = BAO-02 |
| BAO-04 | Duyệt nhiều bước theo quản lý + báo cáo vắng (CO-01 phần 2) | `feature/bao-04-leave-approval-flow` | PR xếp chồng, base = BAO-03 |
| BAO-05 | Giao tiếp nội bộ + email (CO-02) | `feature/bao-05-internal-comms` | PR xếp chồng, base = BAO-04 |
| BAO-06 | Hiệu suất và KPI (PE-01) | `feature/bao-06-performance-kpi` | PR xếp chồng, base = BAO-05 |

Mỗi nhánh BAO-0x tạo nối tiếp từ nhánh task trước, mỗi task 2 commit (`feat` rồi `test`).

## 2. Thứ tự merge (nhờ Anh)

1. Merge PR #19 (BAO-01) vào `HRM_v1.1`.
2. Merge lần lượt BAO-02 → BAO-03 → BAO-04 → BAO-05 → BAO-06.
3. Sau khi merge một PR và xóa nhánh của nó, GitHub tự đổi base của PR kế tiếp. Nếu không tự đổi: bấm **Edit** cạnh tiêu đề PR và chọn base `HRM_v1.1`.

## 3. Migration Flyway

| Task | File | Nội dung chính |
|---|---|---|
| BAO-02 | `V19__open_shift_selection_mode.sql` | `open_shifts.selection_mode`, `auto_processed_at`; `open_shift_claims.auto_rank`, `auto_note` |
| BAO-03 | `V20__leave_balances.sql` | `leave_policies`, `leave_balances`, `leave_balance_ledger`; seed chính sách 12 ngày/năm |
| BAO-04 | `V21__approval_flows.sql` | `approval_flows`, `approval_steps`; seed `admin_only` (giữ hành vi cũ) |
| BAO-05 | `V22__internal_comms_email.sql` | `announcement_templates`, `announcements`, `email_deliveries`, `notification_preferences` |
| BAO-06 | `V23__performance_kpi.sql` | `kpi_definitions`, `review_cycles`, `reviews`, `review_items`, `achievements` |

- Các nhánh khác trên GitHub hiện đều dừng ở V18 (kiểm tra ngày 15/11), nên chưa có trùng số. Nhánh nào của nhóm cần migration mới thì bắt đầu từ V24.
- Không sửa V1–V18.

## 4. Nội dung từng task

### BAO-02 – Duyệt có ngoại lệ + ca trống tự chọn
- Vi phạm nhóm ngoại lệ (`NOT_AVAILABLE_TIME`, `UNAVAILABLE`) không chặn lúc gửi/đồng ý; người duyệt xem trước qua `GET /api/requests/{id}/approval-preview` rồi quyết định.
- `POST /approve` nhận `exceptionReason` + `confirmException` + `previewTokens`. Thiếu ngoại lệ → 422 `EXCEPTION_REQUIRED`; vi phạm ràng buộc cứng → 422 `VALIDATION_FAILED`; token cũ → 409 `STALE_PREVIEW`.
- Ca trống `selectionMode = manual | auto`; hết hạn thì job mỗi phút (hoặc `POST /api/open-shifts/{id}/auto-select`) chọn người theo: Level đúng → tỉ lệ giờ đã phân/giờ mục tiêu thấp hơn → đăng ký sớm hơn. Có khóa dòng, không chạy 2 lần, không vượt số slot.

### BAO-03 – Sổ phép
- Gửi đơn → giữ chỗ; duyệt → trừ; từ chối/hủy → trả; Admin hủy đơn đã duyệt (`POST /api/leave/{id}/revoke`) → hoàn phép. Điều chỉnh tay có lý do + audit.
- Số dư = tổng sổ cái, DB chặn số dư âm; 2 Admin duyệt cùng lúc chỉ trừ 1 lần. Không đủ phép → 422 `INSUFFICIENT_BALANCE`; thiếu chính sách → 422 `POLICY_MISSING`.
- API: `/api/leave/balances/me`, `/api/leave/balances`, `/balances/{id}/ledger`, `/balances/adjust`, `/api/leave/policies`.

### BAO-04 – Duyệt nhiều bước + báo cáo vắng
- Quy trình theo đối tượng: `admin_only | manager | manager_then_admin`; người duyệt = `users.manager_id`, không có quản lý → chuyển Admin. Lịch và số dư phép chỉ đổi ở bước cuối. Áp dụng cho cả đơn nghỉ và đổi/thay ca.
- Báo cáo vắng `GET /api/leave/absences/summary?from&to&groupBy=employee|department|leaveType` và bản `.csv`. Admin xem toàn công ty, quản lý xem cấp dưới, nhân viên 403. **Bình có thể dùng API này cho Dashboard.**
- UI: menu "Hộp duyệt", nút "Các bước", màn hình cấu hình quy trình.

### BAO-05 – Thông báo chung + email
- Thông báo theo phạm vi company/branch/department/group/users, mẫu tin, lịch sử đọc kèm trạng thái email từng người.
- Email đi qua hàng đợi `email_deliveries`, chỉ xếp sau khi sự kiện outbox đã commit; không gửi trùng; lỗi SMTP thì thử lại rồi chuyển `dead`; Admin gửi lại trong màn hình "Sự kiện thông báo" (BAO-01).
- Mặc định tắt (`MAIL_ENABLED=false`). Demo: `MAIL_TRANSPORT=log`, hoặc `smtp` + Mailpit (SMTP 1025, giao diện 8025). Cấu hình trong `.env` (mẫu `.env.example`), không commit mật khẩu. Không thêm thư viện mới vào `pom.xml`.
- Đã định tuyến sẵn `lms.course_reminder`, `hr.contract_expiring`, `hr.certificate_expiring` để **Tuấn/Anh** phát sự kiện khi có module LMS/HR.

### BAO-06 – Hiệu suất và KPI
- KPI `manual | hours_vs_schedule | attendance_rate | absence_rate`, chuẩn hóa theo lịch từng người (Part-time làm đủ lịch vẫn đạt 100%).
- Kỳ đánh giá: tự đánh giá → quản lý chấm + kế hoạch cải thiện → nhân viên phản hồi → hoàn tất. Nhân viên chỉ xem phiếu của mình.
- Thành tích và đề xuất thưởng/thăng chức/đào tạo. Module này không tính tiền: nó phát sự kiện `performance.bonus_proposed` (**Payroll – Bình**) và `performance.training_proposed` (**LMS – Tuấn**), mỗi sự kiện chỉ nhận 1 lần.
- UI: menu "Hiệu suất & KPI".

## 5. File dùng chung có sửa (nhờ review)

| File | Task | Người nên review |
|---|---|---|
| `domain/schedule/Violation.java` (thêm cờ overridable, giữ constructor cũ) | BAO-02 | Tường |
| `AssignmentMutationService.java` (thêm phương thức default) | BAO-02 | Tường |
| `OfficialJdbcScheduleAdapter.java`, `schedule/official/OfficialScheduleService.java` (previewSwap, swap có override; swap cũ không đổi hành vi) | BAO-02 | Tường |
| `domain/events/OutboxDispatcher.java`, `EventTypes` | BAO-05 | Anh |
| `application.properties`, `.env.example` | BAO-05 | Anh |

## 6. Kiểm thử

| Sau task | Test mới | Tổng `mvn test` |
|---|---|---|
| BAO-02 | ShiftRequestExceptionApprovalTest (10), OpenShiftAutoSelectTest (8), OfficialScheduleSwapExceptionTest (4) | 359 |
| BAO-03 | LeaveBalanceServiceTest (14) | 373 |
| BAO-04 | ApprovalFlowServiceTest (10), LeaveApprovalStepsTest (9), ShiftRequestApprovalStepsTest (3) | 395 |
| BAO-05 | SmtpEmailTransportTest (4), EmailChannelTest (7), AnnouncementServiceTest (5) | 411 |
| BAO-06 | PerformanceServiceTest (12) | 423 |

Log `mvn test` của lần push được đính kèm trong từng PR.

**Chưa kiểm chứng:** migration V19–V23 chưa chạy trên SQL Server thật và chưa có SQL Server IT cho BAO-02…06. Cần chạy `HUONG_DAN\CHAY_HE_THONG.bat` trên một DB sạch để Flyway áp V19–V23 trước khi demo.

## 7. Tồn đọng và giả định cần nhóm chốt

| Vấn đề | Mức | Người xử lý |
|---|---|---|
| Migration V19–V23 chưa chạy trên SQL Server thật | Cao | Bảo (cần 1 máy có SQL Server chạy giúp nếu máy Bảo không chạy được) |
| Chưa có SQL Server IT cho BAO-02…06 | Trung bình | Bảo, sau khi nhóm chốt quy tắc |
| `/api/admin/**` chưa nằm trong `hasRole("ADMIN")` ở `SecurityConfig` (service đã chặn bằng `requireAdmin()`) | Thấp | Anh |
| Nhắc khóa học / hợp đồng / chứng chỉ hết hạn: đã định tuyến email nhưng chưa có nơi phát sự kiện | – | Tuấn, Anh |

**Quy tắc nghiệp vụ đang là giả định, chưa duyệt:** 12 ngày phép/năm (BLLĐ 2019 Điều 113), đếm theo ngày lịch, không chuyển phép sang năm sau, không chia phép theo ngày vào làm; thứ tự ưu tiên chọn người cho ca trống; thang KPI 1–5, trọng số, chu kỳ đánh giá, ai được xem kết quả; cách Payroll/LMS nhận sự kiện. Các giá trị này nằm trong cấu hình/seed, đổi được mà không sửa code.

## 8. Việc tiếp theo

| # | Việc | Ai |
|---|---|---|
| 1 | Review và merge PR BAO-01 → BAO-06 theo thứ tự ở mục 2 | Anh (Tường review file dùng chung) |
| 2 | Chạy hệ thống trên SQL Server sạch để kiểm tra V19–V23, báo lỗi nếu có | Bảo |
| 3 | Bình xem API báo cáo vắng (BAO-04) cho Dashboard | Bình |
| 4 | Chốt các giả định nghiệp vụ ở mục 7 | Cả nhóm, trước demo |
| 5 | Gom các nhánh về 1 nhánh chốt và chạy lại `mvn test` trước khi làm slide cuối kỳ | Anh, cả nhóm |
