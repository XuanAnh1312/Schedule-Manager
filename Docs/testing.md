# Kiểm thử

## Tình trạng hiện tại

Chưa tìm thấy thư mục test, test runner hoặc lệnh test được khai báo trong manifest dự án. `pom.xml` là cấu hình Maven cho lớp Java mẫu; nó không kiểm thử luồng SPA.

## Kiểm tra cú pháp JavaScript

Với Node.js đã cài đặt, mở PowerShell tại thư mục gốc:

```powershell
Get-ChildItem js -Filter *.js | ForEach-Object {
  node --check $_.FullName
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

## Kiểm tra thủ công đề xuất

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
10. Kiểm tra responsive ở màn hình desktop và mobile.

## Lưu ý kiểm thử dữ liệu

Ứng dụng ghi dữ liệu vào `localStorage` của origin. Dùng profile/trình duyệt riêng hoặc sao lưu dữ liệu cần thiết trước khi thử các thao tác thay đổi hay khôi phục demo.
