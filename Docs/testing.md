# Kiểm thử

## Bản tích hợp mốc A

Ứng dụng chính hiện dùng `workspace.html` và API cùng origin. Xem [hướng dẫn kiểm thử và kết quả tích hợp](moc-a-completion.md).

- `mvn test`: unit/controller và kiểm tra wiring.
- `IntegratedMocAIT`: kiểm tra SQL Server thật, bật có chủ đích trên database test riêng.
- `tests/moc-a-http.mjs`: luồng API thật, đồng thời và thông báo.
- `tests/moc-a-browser.mjs`: Admin/Employee, form, phiên đăng nhập và responsive trên Chromium.

Nội dung bên dưới mô tả bộ smoke của **demo cũ**, hiện tải `demo.html`. Kết quả demo không thay thế nghiệm thu API/SQL/E2E của bản tích hợp.

## Kiểm thử trình duyệt tự động

Ứng dụng có smoke-test trình duyệt không cần cài thêm thư viện tại `tests/browser-smoke.html`.

1. Mở PowerShell ở thư mục gốc và chạy `py -m http.server 8000` (hoặc `python -m http.server 8000`).
2. Dùng một browser profile dành riêng cho kiểm thử, mở `http://localhost:8000/tests/browser-smoke.html`.
3. Chọn **Chạy kiểm thử** và xem từng kết quả trên trang.

Suite kiểm tra đăng nhập và lỗi form; tên truy cập và liên kết mô tả lỗi ở form Admin/Nhân viên (nhân viên, Level, kỳ, ca, quy tắc, mật khẩu và khung giờ); trạng thái nút sidebar desktop; focus trap/khôi phục focus, modal và hộp xác nhận; menu mobile/tài khoản; responsive ở 320/375/768/1024/1440 px; truy cập bảng/lịch bằng bàn phím; và luồng khai báo/gửi thời gian rảnh.

Runner tạm thay dữ liệu `localStorage` có tiền tố `pls_` trong lúc chạy, sau đó khôi phục lại dữ liệu đã lưu và tải lại ứng dụng trong iframe. Dùng browser profile riêng để tránh ảnh hưởng phiên ứng dụng đang sử dụng; không đóng/reload tab runner giữa chừng.

## Tình trạng hiện tại

Chưa có test runner/lệnh test được tích hợp vào Maven hoặc package manager. `pom.xml` là cấu hình Maven cho lớp Java mẫu; nó không kiểm thử luồng SPA.

## Kiểm thử Issue #8: chấm công thực tế và báo cáo

Xem [hướng dẫn BI-01..BI-07](./issue8-attendance.md) và [ma trận bằng chứng AC](./issue8-ac-evidence.md). Suite API là `tests/attendance-api.ps1`, fixture local-only là `tests/attendance-fixture.sql`, responsive ở `tests/attendance-responsive.html`. Chạy `mvn test` và kiểm thử cả API 401/403/409, hai user cạnh tranh, login/logout/refresh, 320/360/390/768/1024/1280 px và Windows Scale 125%/150%.

## Kiểm tra cú pháp JavaScript

Với Node.js đã cài đặt, mở PowerShell tại thư mục gốc:

```powershell
Get-ChildItem js -Filter *.js | ForEach-Object {
  node --check $_.FullName
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

## Kiểm tra thủ công bổ sung

Chạy ứng dụng qua HTTP như mô tả trong [setup.md](./setup.md), rồi kiểm tra:

1. Trang đăng nhập hiện đầy đủ; đăng nhập nhanh bằng tài khoản Admin và nhân viên hoạt động.
2. Đăng nhập sai hoặc tài khoản bị khóa hiển thị thông báo phù hợp; không vào được màn hình ứng dụng.
3. Tải lại trang khôi phục đúng phiên còn hoạt động; đăng xuất xóa phiên và trở về đăng nhập.
4. Các route Admin và Nhân viên hiển thị đúng theo vai trò; chuyển route trên màn hình nhỏ đóng sidebar.
5. Admin tạo/cập nhật kỳ, nhân viên lưu bản nháp/gửi đăng ký và các trạng thái được phản ánh trong giao diện.
6. Chạy phân lịch ở chế độ thử không ghi phân công; áp dụng kết quả mới ghi dữ liệu và lịch sử.
7. Ràng buộc lịch ngăn phân công không hợp lệ; xử lý ca thiếu người và công bố theo xác nhận của giao diện.
8. Modal, hộp xác nhận và toast hoạt động; nhấn Esc đóng lớp trên cùng; số toast đang hiển thị không vượt quá 5.
9. Khôi phục dữ liệu demo yêu cầu xác nhận, nạp lại dữ liệu mẫu và giữ phiên chỉ khi tài khoản tương ứng còn hợp lệ.
10. Kiểm tra responsive ở màn hình desktop và mobile, kể cả zoom 200% và điều hướng chỉ bằng bàn phím. Smoke test kiểm tra kích thước viewport nhưng không mô phỏng trực tiếp mức zoom trình duyệt.

## Lưu ý kiểm thử dữ liệu

Ứng dụng ghi dữ liệu vào `localStorage` của origin. Dùng profile/trình duyệt riêng hoặc sao lưu dữ liệu cần thiết trước khi thử các thao tác thay đổi hay khôi phục demo.
