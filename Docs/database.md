# Cấu trúc dữ liệu

## Trạng thái hiện tại

Backend dùng SQL Server và Flyway quản lý schema. Các migration hiện có từ `V1` đến `V5`:

- `V1`: `levels`, `users`.
- `V2`–`V4`: seed Level và tài khoản Admin demo, sửa hash mật khẩu seed.
- `V5`: các bảng nghiệp vụ phân lịch và cấu hình quy tắc mặc định.

Flyway lưu trạng thái migration trong `flyway_schema_history`. Không sửa migration đã áp dụng; thay đổi schema mới phải tạo migration phiên bản kế tiếp.

## Các bảng nghiệp vụ

| Bảng | Nội dung |
|---|---|
| `levels` | Cấp độ nhân viên và thứ tự hiển thị. |
| `users` | Tài khoản, mã nhân viên, role, trạng thái, Level và `password_hash`. |
| `periods` | Kỳ đăng ký, ngày áp dụng, thời điểm mở/hạn chót và trạng thái. |
| `period_applicable_users` | Danh sách nhân viên được áp dụng cho kỳ; không có dòng nghĩa là áp dụng cho tất cả. |
| `period_weekdays` | Các thứ trong tuần được đăng ký. Quy ước `0=Chủ nhật` đến `6=Thứ bảy`. |
| `period_dates` | Ngày miễn đăng ký hoặc ngày đóng trong kỳ. |
| `availability_registrations` | Bản đăng ký rảnh của một nhân viên trong một kỳ; mỗi cặp user/kỳ là duy nhất. |
| `availability_entries` | Ngày có thể làm trong bản đăng ký. |
| `availability_slots` | Các khoảng giờ rảnh của từng ngày. |
| `shifts` | Ca làm: ngày, giờ, chế độ phân công, nhu cầu và trạng thái. Ca qua ngày được biểu diễn bằng giờ kết thúc nhỏ hơn giờ bắt đầu. |
| `shift_level_requirements` | Số người yêu cầu theo Level cho từng ca. |
| `shift_user_preferences` | Nhân viên bắt buộc hoặc ưu tiên cho ca. |
| `assignments` | Phân công nhân viên vào ca, người/nguồn phân công, trạng thái và lý do. |
| `schedule_rules` | Giới hạn giờ, thời gian nghỉ, ưu tiên phân lịch và chính sách thiếu người. V5 tạo cấu hình mặc định `rules_main`. |
| `schedule_level_targets` | Tỷ lệ giờ mục tiêu theo Level. |
| `level_substitutions` | Các quan hệ Level cao có thể thay Level thấp. |
| `history` | Lịch sử thao tác, tác nhân và snapshot JSON before/after. |

Các bảng chính có primary key, foreign key, unique/check constraint và index để bảo vệ toàn vẹn dữ liệu. Snapshot lịch sử dùng JSON; dữ liệu nghiệp vụ còn lại được chuẩn hóa thành bảng quan hệ.

## Bảo mật và seed

- Mật khẩu trong `users` phải là BCrypt hash; không lưu mật khẩu dạng rõ.
- Tài khoản Admin V3/V4 là tài khoản seed cho môi trường demo. Phải đổi mật khẩu trước production.
- `DB_PASSWORD` và `JWT_SECRET` lấy từ biến môi trường hoặc `.env` cục bộ; không commit `.env`.
- Tài khoản runtime nên dùng quyền đọc/ghi; chỉ cấp quyền DDL tạm thời khi chạy migration.

## Dữ liệu chưa được chuyển

Flyway tạo schema và seed Level/Admin/default schedule rules. V5 **chưa chuyển các kỳ, nhân viên thường, đăng ký rảnh, ca, phân công và lịch sử từ `localStorage`**; các bảng nghiệp vụ này sẽ rỗng đến khi có API/import seed riêng. Dữ liệu hiện có trong browser không tự đồng bộ sang SQL Server.
