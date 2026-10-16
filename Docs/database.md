# Cấu trúc dữ liệu

## Hiện trạng lưu trữ

Dự án chưa có cơ sở dữ liệu quan hệ hoặc máy chủ. Các bản ghi được tuần tự hóa JSON vào `localStorage`; khóa thực tế được khai báo trong `js/storage.js`. Mỗi trình duyệt/origin có bộ dữ liệu riêng, không có khóa ngoại được cơ sở dữ liệu cưỡng chế.

| Khóa `localStorage` | Thực thể | Quan hệ chính |
|---|---|---|
| `pls_users` | Người dùng/nhân viên | Có thể tham chiếu đến Level; được liên kết với đăng ký rảnh, phân công và lịch sử. |
| `pls_levels` | Level nhân viên | Được người dùng, nhu cầu ca và quy tắc thay thế tham chiếu. |
| `pls_periods` | Kỳ đăng ký | Có nhiều đăng ký thời gian rảnh; giới hạn ngày của ca được phân lịch. |
| `pls_free_time` | Đăng ký thời gian rảnh | Tham chiếu người dùng và kỳ; chứa trạng thái, danh sách ngày và slot giờ. |
| `pls_shifts` | Ca làm việc | Có nhiều phân công; chứa ngày/giờ, nhu cầu nhân sự và trạng thái công bố. |
| `pls_assignments` | Phân công | Tham chiếu ca và người dùng; lưu nguồn/người phân công, trạng thái và lý do. |
| `pls_schedule_rules` | Quy tắc phân lịch | Cấu hình giờ mục tiêu/giới hạn, thay thế Level, ưu tiên và xử lý thiếu người. |
| `pls_history` | Lịch sử | Lưu loại sự kiện, nội dung, tác nhân, thời điểm, before/after và lý do. |
| `pls_current_user` | Phiên người dùng hiện tại | Lưu thông tin phiên đăng nhập hiện tại trong trình duyệt. |
| `pls_data_version` | Phiên bản dữ liệu demo | Được dùng để xác định có cần seed lại dữ liệu mẫu hay không. |

## Các trường dữ liệu tiêu biểu

- **User:** `id`, `code`, `username`, `password`, `fullName`, `role`, `levelId`, `status`, `targetRate`, thông tin liên hệ và ghi chú.
- **Level:** `id`, `name`, `description`, `color`, `order`.
- **Period:** `id`, `name`, `cycle`, `startDate`, `endDate`, `openAt`, `deadline`, cờ khóa/sửa, nhân viên áp dụng, thứ trong tuần, ngày ngoại lệ/ngày đóng và `status`.
- **Free time:** `id`, `userId`, `periodId`, `status`, `entries` (ngày và các slot `start`/`end`), thời điểm gửi/cập nhật.
- **Shift:** `id`, `name`, `date`, `startTime`, `endTime`, `mode`, `levelRequirements`, `mandatoryUsers`, `preferredUsers`, `totalRequired`, `note`, `status`.
- **Assignment:** `id`, `shiftId`, `userId`, `assignedBy`, `assignedAt`, `reason`, `status`.
- **Rules:** `standardHours`, giới hạn giờ ngày/tuần/kỳ, `minRestBetweenShifts`, tỷ lệ mục tiêu theo Level, quan hệ thay thế Level, `schedulingPriority`, `understaffedPolicy`.
- **History:** `id`, `type`, `title`, `detail`, `actor`, `timestamp`, `before`, `after`, `reason`.

Trường có thể thay đổi theo luồng nghiệp vụ; danh sách trên là tóm tắt từ cấu trúc hiện dùng và dữ liệu seed, không phải schema được kiểm tra/cưỡng chế độc lập.

## Quan hệ logic

```text
Level 1 ─── * User
Period 1 ── * FreeTime * ── 1 User
Shift 1 ─── * Assignment * ── 1 User
ScheduleRules ── cấu hình phân lịch cho toàn ứng dụng
History ── actor tham chiếu logic tới User hoặc định danh hệ thống
```

## Vòng đời và phiên bản dữ liệu

- `DATA_VERSION` hiện được khai báo là `1.0.0`.
- Khi khởi tạo, dữ liệu demo được seed nếu phiên bản lưu không khớp; khi cùng phiên bản thì không ghi đè dữ liệu người dùng hiện có.
- Khôi phục demo xóa các khóa ứng dụng rồi tạo lại dữ liệu mẫu.
- Lịch sử được giới hạn tối đa 500 mục.

## Hạn chế

`localStorage` không phù hợp để chia sẻ dữ liệu giữa người dùng/thiết bị, không cung cấp giao dịch nhiều bản ghi, sao lưu máy chủ hay kiểm soát truy cập đáng tin cậy. Mật khẩu và dữ liệu hiện nằm ở phía trình duyệt; không sử dụng dữ liệu nhạy cảm trong bản demo.
