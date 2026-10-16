# Cài đặt và chạy dự án

## Yêu cầu

- Trình duyệt hiện đại có bật JavaScript và hỗ trợ `localStorage`.
- Một máy chủ HTTP tĩnh cục bộ. Bootstrap tải các partial bằng `fetch`, vì vậy mở trực tiếp qua `file://` sẽ không hoạt động đúng.
- Python 3 hoặc tiện ích tương đương để chạy máy chủ tĩnh (có thể dùng Live Server trong VS Code).
- Node.js chỉ cần nếu muốn chạy kiểm tra cú pháp JavaScript từ dòng lệnh.

## Chạy bằng Python

Mở PowerShell tại thư mục gốc dự án:

```powershell
py -m http.server 8000
```

Nếu lệnh `py` không khả dụng nhưng đã cài Python:

```powershell
python -m http.server 8000
```

Mở `http://localhost:8000/` trong trình duyệt. Dừng máy chủ bằng `Ctrl+C`.

## Chạy bằng VS Code

Mở thư mục dự án trong VS Code, khởi động tiện ích Live Server tại `index.html`, sau đó mở URL HTTP do tiện ích cung cấp.

## Tài khoản demo

| Vai trò | Tên đăng nhập | Mật khẩu |
|---|---|---|
| Quản trị viên | `admin` | `admin123` |
| Nhân viên | `nv001` | `user123` |
| Nhân viên | `nv002` | `user123` |
| Nhân viên | `nv003` | `user123` |

Đây là thông tin xác thực demo được lưu trong mã nguồn, không sử dụng trong môi trường production.

## Maven/Java

`pom.xml` và `src/main/java/org/dacnpm/schedulemanager/ManageSchedule.java` hiện tạo thành một chương trình Java mẫu độc lập; điểm vào này không khởi chạy ứng dụng web. Phiên bản Java được khai báo trong Maven là 26. Không cần Maven để chạy SPA theo hướng dẫn HTTP ở trên.

## Lưu ý dữ liệu

Dữ liệu được lưu trong `localStorage` của origin đang dùng. Dùng cùng trình duyệt và origin để tiếp tục phiên dữ liệu. Tính năng khôi phục dữ liệu demo sẽ thay thế dữ liệu ứng dụng hiện tại trên trình duyệt.
