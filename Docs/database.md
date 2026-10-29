# Cấu trúc dữ liệu

## Trạng thái hiện tại

Backend dùng SQL Server và Flyway quản lý schema. Các migration hiện có:

- `V1`: `levels`, `users`.
- `V2`–`V4`: seed Level và tài khoản Admin demo, sửa hash mật khẩu seed.
- `V5`: các bảng nghiệp vụ phân lịch và cấu hình quy tắc mặc định.
- `V6`: dành cho module yêu cầu/nghỉ phép/thông báo (PR #10). Phải merge trước `V7`.
- `V7`: vòng đời kỳ đăng ký, loại khoảng giờ đăng ký và quy tắc có phạm vi/phiên bản (issue #5). Chi tiết ở mục [Kỳ đăng ký và quy tắc (V7)](#kỳ-đăng-ký-và-quy-tắc-v7).

Flyway lưu trạng thái migration trong `flyway_schema_history`. Không sửa migration đã áp dụng; thay đổi schema mới phải tạo migration phiên bản kế tiếp.

## Các bảng nghiệp vụ

| Bảng | Nội dung |
|---|---|
| `levels` | Cấp độ nhân viên và thứ tự hiển thị. |
| `users` | Tài khoản, mã nhân viên, role, trạng thái, Level và `password_hash`. |
| `periods` | Kỳ đăng ký, ngày áp dụng, thời điểm mở/hạn chót (UTC), tự khóa, cho phép mở lại, trạng thái `draft`/`open`/`locked`/`archived` và `version` (V7). |
| `period_applicable_users` | Danh sách nhân viên được áp dụng cho kỳ; không có dòng nghĩa là áp dụng cho tất cả. |
| `period_weekdays` | Các thứ trong tuần được đăng ký. Quy ước `0=Chủ nhật` đến `6=Thứ bảy`. |
| `period_dates` | Ngày miễn đăng ký hoặc ngày đóng trong kỳ. |
| `availability_registrations` | Bản đăng ký rảnh của một nhân viên trong một kỳ; mỗi cặp user/kỳ là duy nhất. |
| `availability_entries` | Ngày có khai báo trong bản đăng ký. |
| `availability_slots` | Các khoảng giờ của từng ngày; `slot_type` = `available` (rảnh) hoặc `unavailable` (không thể làm) (V7). |
| `shifts` | Ca làm: ngày, giờ, chế độ phân công, nhu cầu và trạng thái. Ca qua ngày được biểu diễn bằng giờ kết thúc nhỏ hơn giờ bắt đầu. |
| `shift_level_requirements` | Số người yêu cầu theo Level cho từng ca. |
| `shift_user_preferences` | Nhân viên bắt buộc hoặc ưu tiên cho ca. |
| `assignments` | Phân công nhân viên vào ca, người/nguồn phân công, trạng thái và lý do. |
| `schedule_rules` | Giới hạn giờ, thời gian nghỉ, ưu tiên phân lịch và chính sách thiếu người. V5 tạo cấu hình mặc định `rules_main`. Từ V7, dòng này chỉ là **mẫu đề xuất**; giá trị có hiệu lực nằm ở `rule_settings`. Cột cũ giữ nguyên vì module khác còn đọc. |
| `schedule_level_targets` | Tỷ lệ giờ mục tiêu theo Level. |
| `level_substitutions` | Các quan hệ Level cao có thể thay Level thấp. Bảng runtime dùng chung; từ V7 được ghi lại khi Admin áp dụng tham số `level_substitutions`. |
| `history` | Lịch sử thao tác, tác nhân và snapshot JSON before/after. |
| `rule_changes` | Mỗi lần thay đổi quy tắc: lý do, danh sách thay đổi, bản xem trước, người tạo/áp dụng, trạng thái `previewed`/`applied`/`cancelled` (V7). |
| `rule_settings` | Giá trị quy tắc theo tham số, phạm vi và ngày hiệu lực; bản cũ chuyển `superseded`/`removed`, không xóa (V7). |
| `rule_revision` | Bộ đếm một dòng để chống áp dụng bản xem trước đã cũ (V7). |

Các bảng chính có primary key, foreign key, unique/check constraint và index để bảo vệ toàn vẹn dữ liệu. Snapshot lịch sử dùng JSON; dữ liệu nghiệp vụ còn lại được chuẩn hóa thành bảng quan hệ.

## Kỳ đăng ký và quy tắc (V7)

### Trạng thái kỳ

`V7` đổi `ck_periods_status` thành `draft | open | locked | archived` và chuyển mọi dòng `not_open` cũ về `draft`. Bộ trạng thái này đã chốt với người phụ trách lịch ngày 29/10/2026.

| Giá trị | Nhãn hiển thị | Nhân viên thấy | Nhân viên đăng ký |
|---|---|---|---|
| `draft` | Nháp | Không | Không |
| `open` | Sắp mở (trước `open_at`, tính ra, không lưu) / Đang mở đăng ký | Có | Chỉ trong cửa sổ đăng ký |
| `locked` | Đã đóng đăng ký | Có, chỉ đọc | Không |
| `archived` | Đã lưu trữ | Có, chỉ đọc | Không |

- "Đang xếp lịch" và "Đã công bố" thuộc vòng đời phương án lịch, **không** lưu ở `periods`. Màn hình ghép hai trạng thái, ví dụ "Đã đóng đăng ký · Lịch: Đã công bố".
- Chuyển trạng thái hợp lệ: `draft → open → locked → archived`, mở lại `locked → open`. Chỉ xóa cứng kỳ `draft` chưa có đăng ký.
- `allow_edit_after_deadline` (V5) vẫn còn cột nhưng backend không dùng: kỳ tự khóa thì không có ngoại lệ sửa sau hạn.
- `periods.version` tăng sau mỗi lần sửa hoặc chuyển trạng thái; API từ chối thao tác dựa trên version cũ.

### Quy ước thời gian

- Cột thời điểm (`open_at`, `deadline`, `created_at`, `submitted_at`…) lưu **UTC**, giống mặc định `SYSUTCDATETIME()`.
- API nhận và trả giờ theo múi giờ nghiệp vụ `Asia/Ho_Chi_Minh`. Ví dụ hạn `05/10/2026 23:59` được lưu là `2026-10-05 16:59` UTC.
- Hạn chót được quyết định bằng giờ server, không dùng giờ trình duyệt.

### Quy tắc có phạm vi và phiên bản

- Mỗi giá trị trong `rule_settings` gồm tham số (`param_key`), phạm vi (`scope_type`, `scope_id`), giá trị dạng chuỗi và `effective_from`. Danh mục tham số nằm trong code (`RuleParameter`) và được trả qua `GET /api/rules/catalog`.
- Phạm vi: `company` (`scope_id` NULL), `branch`, `employee_type`, `group`, `user`. Hiện chỉ cấu hình được `company` và `user`, vì chưa có danh mục chi nhánh/loại nhân viên/nhóm.
- Khi nhiều giá trị cùng áp dụng, phạm vi cụ thể hơn thắng: `user > group > employee_type > branch > company`. Cùng phạm vi thì `effective_from` gần nhất mà không sau ngày đang xét sẽ thắng. **Thứ tự này là đề xuất, cần cả đội xác nhận.**
- Chỉ mục duy nhất có lọc `uq_rule_settings_active` bảo đảm mỗi tham số/phạm vi/ngày hiệu lực chỉ có một giá trị `active`.
- V7 **không seed** giá trị quy tắc nào. Tham số bắt buộc chưa cấu hình sẽ hiện trong checklist; hệ thống không tự gán số.
- Áp dụng quy tắc chỉ ghi `rule_changes`, `rule_settings`, `rule_revision`, `level_substitutions` và `history`. Không sửa `periods`, `shifts` hay `assignments`.

## Bảo mật và seed

- Mật khẩu trong `users` phải là BCrypt hash; không lưu mật khẩu dạng rõ.
- Tài khoản Admin V3/V4 là tài khoản seed cho môi trường demo. Phải đổi mật khẩu trước production.
- `DB_PASSWORD` và `JWT_SECRET` lấy từ biến môi trường hoặc `.env` cục bộ; không commit `.env`.
- Tài khoản runtime nên dùng quyền đọc/ghi; chỉ cấp quyền DDL tạm thời khi chạy migration.

## Dữ liệu chưa được chuyển

Flyway tạo schema và seed Level/Admin/default schedule rules. V5 **chưa chuyển các kỳ, nhân viên thường, đăng ký rảnh, ca, phân công và lịch sử từ `localStorage`**; các bảng nghiệp vụ này sẽ rỗng đến khi có API/import seed riêng. Dữ liệu hiện có trong browser không tự đồng bộ sang SQL Server.

Từ V7, kỳ đăng ký, đăng ký thời gian rảnh và quy tắc đã có API ghi vào SQL Server (xem `Docs/api.md`). Tuy vậy, SPA **chưa gọi các API này**: màn hình hiện tại vẫn đọc/ghi `localStorage` cho tới khi nối đăng nhập thật và API client chung.
