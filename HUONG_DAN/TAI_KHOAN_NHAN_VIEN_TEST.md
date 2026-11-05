# Cấp tài khoản nhân viên và dữ liệu test

Admin vào **Nhân viên & danh mục → Thêm nhân viên**, nhập mã/họ tên, **tài khoản đăng nhập do Admin cấp** và **mật khẩu ban đầu**. Khi lưu thành công, hồ sơ và tài khoản được tạo trong cùng giao dịch; mật khẩu chỉ lưu dưới dạng BCrypt hash. Tài khoản trùng bị từ chối, không tạo thêm hồ sơ lỗi.

Nhân viên đăng nhập bằng tài khoản được cấp tại **http://localhost:8080**, chọn **Đổi mật khẩu**, nhập mật khẩu hiện tại, mật khẩu mới và xác nhận mật khẩu mới. Nhân viên chỉ đổi mật khẩu của chính mình; không được tạo tài khoản hoặc tự đăng ký. Admin cũng không thể cấp role Admin thông qua form tạo nhân viên.

Đây là **tài khoản đăng nhập ứng dụng**, khác `DB_USERNAME`/`DB_PASSWORD` trong `.env` dùng để kết nối SQL Server.

## Năm tài khoản test đã cấp

| Nhân viên | Tài khoản | Mật khẩu ban đầu |
|---|---|---|
| Võ Duy Tường | tuong.vdt@hrm.com | Test@123 |
| Nguyễn Thanh Tuấn | tuan.ntt@hrm.com | Test@123 |
| Nguyễn Chí Bình | binh.ncb@hrm.com | Test@123 |
| Nguyễn Phạm Xuân Anh | anh.npxa@hrm.com | Test@123 |
| Đỗ Lê Hoài Bảo | bao.dlhb@hrm.com | Test@123 |

Các tài khoản có role Employee (`user`) và trạng thái active. Đã tạo trên database local `ScheduleManager` đang được `CHAY_HE_THONG.bat` sử dụng, để đăng nhập trực tiếp tại http://localhost:8080. Không seed tự động khi khởi động backend và không thêm chúng vào migration production.

## Hồ sơ giả lập đã bổ sung

Đã điền toàn bộ nhóm thông tin nghiệp vụ hiện có: họ tên, điện thoại mẫu, email, ngày vào làm, Level, chi nhánh, bộ phận, nhóm, loại nhân viên, quản lý trực tiếp, ghi chú; gán địa điểm làm việc, chức vụ, kỹ năng, chứng chỉ và ngày hết hạn. Thông số `target_rate` và `contract_max_hours_per_week` đã được điền trong DB. Các trường hệ thống như ID, ngày tạo/cập nhật do hệ thống quản lý; `archived_at` giữ NULL vì tài khoản đang active.

Đây là tên/thông tin giả lập để kiểm thử, không phải thông tin cá nhân thật. Mật khẩu và tên đăng nhập không thay đổi. Không tự kích hoạt cấu hình phân lịch hoặc tạo lịch/availability trong bước bổ sung hồ sơ.

| Nhân viên | Chi nhánh | Level | Loại nhân viên | Chức vụ | Quản lý | Mục tiêu / giới hạn tuần mẫu |
|---|---|---|---|---|---|---|
| Anh | TP. Hồ Chí Minh | 4 | Toàn thời gian | Trưởng nhóm vận hành | Admin | 100% / 40h |
| Tường | TP. Hồ Chí Minh | 3 | Toàn thời gian | Điều phối viên ca làm | Anh | 100% / 40h |
| Tuấn | TP. Hồ Chí Minh | 2 | Toàn thời gian | Nhân viên vận hành | Tường | 100% / 40h |
| Bình | Đà Nẵng | 2 | Bán thời gian | Nhân viên chăm sóc khách hàng | Anh | 60% / 24h |
| Bảo | Đà Nẵng | 1 | Bán thời gian | Nhân viên hỗ trợ vận hành | Anh | 50% / 20h |

Mỗi người có 2–3 kỹ năng, 2 chứng chỉ với hạn dùng mẫu 2027/2028. Anh và Tường được gán cả hai địa điểm; ba người còn lại gắn với chi nhánh chính. Bộ dữ liệu chi tiết nằm trong `DU_LIEU_HO_SO_NHAN_VIEN_TEST.json`.

Để nạp lại hồ sơ trên ứng dụng local đã có 5 tài khoản:

```powershell
node tests/seed-test-employee-profiles.mjs
sqlcmd -S ".\SQLEXPRESS" -E -C -b -f 65001 -i tests/seed-test-employee-contracts.sql
```

Script API chỉ cập nhật đúng 5 tài khoản, dùng lại danh mục trùng tên và kiểm tra đăng nhập sau cập nhật. Script SQL chỉ điền hai thông số mục tiêu/giới hạn tuần trong `ScheduleManager`, ghi audit, không sửa mật khẩu hoặc tạo database QA.

## Tạo lại trên database local/QA khác

Sau khi cấu hình backend tới đúng database local cần kiểm thử và chạy backend:

```powershell
node tests/seed-test-employees.mjs
```

Script dùng API Admin để cấp tài khoản. Mặc định là ứng dụng chung `http://127.0.0.1:8080` và Admin seed `admin` / `Admin@123`. Nếu đã đổi mật khẩu Admin, đặt `SM_SEED_ADMIN_PASSWORD` trong phiên PowerShell bằng mật khẩu hiện tại trước khi chạy; không chỉnh source hoặc gửi mật khẩu vào chat.

Có thể đặt `SM_SEED_URL` để chọn backend QA local, chẳng hạn `http://127.0.0.1:8082`. Script chỉ cho gọi địa chỉ loopback. Nếu tài khoản đã tồn tại, script kiểm tra đăng nhập với mật khẩu test và không tạo trùng hay tự đặt lại mật khẩu mà nhân viên đã đổi.

## Kết quả kiểm tra 05/11/2026

- 349 test unit/controller/SQL Server integration đạt, không failure/error/skip (`*Test,IntegratedMocAIT`). Các test mới kiểm tra cấp tài khoản chỉ bởi Admin, chống giả role, trùng tài khoản, đổi mật khẩu của chính mình và chặn tự đăng ký.
- Cả 5 tài khoản đã đăng nhập qua giao diện frontend; Employee không có quyền tạo tài khoản hoặc mở chức năng quản trị nhân viên.
- Đã thử đổi mật khẩu qua UI, chặn xác nhận không khớp, kiểm tra mật khẩu cũ/mới và khôi phục về `Test@123` sau test. Không còn tài khoản test nào bị giữ ở mật khẩu tạm của bài kiểm thử.
- Kết quả: `KET_QUA_TAI_KHOAN_NHAN_VIEN.json`. Bộ browser có thể chạy lại bằng `node tests/employee-accounts-browser.mjs` trên ứng dụng local với các tài khoản test này; bài kiểm thử sẽ tạm đổi rồi khôi phục mật khẩu của tài khoản Tường.
