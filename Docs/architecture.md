# Kiến trúc

## Tổng quan

Ứng dụng hiện là SPA thuần HTML, CSS và JavaScript. Không có framework frontend hoặc API backend được sử dụng trong luồng chạy hiện tại. Dữ liệu nghiệp vụ được lưu trong `localStorage`.

## Cấu trúc chính

| Đường dẫn | Trách nhiệm |
|---|---|
| `index.html` | Trang HTML gốc; khai báo CSS và các mount point cho partial. |
| `partials/login.html` | Màn hình đăng nhập và tài khoản demo. |
| `partials/app-shell.html` | Sidebar, topbar và vùng nội dung SPA. |
| `partials/dialogs.html` | Modal, toast, hộp xác nhận và overlay điều hướng mobile. |
| `css/styles.css` | Kiểu dáng và responsive layout. |
| `js/bootstrap.js` | Tải partial, nạp lần lượt các module JavaScript và phát sự kiện `app:ready`. |
| `js/storage.js` | Khóa `localStorage`, thao tác lưu trữ, dữ liệu demo và ghi lịch sử. |
| `js/state.js` | Cache trạng thái trong bộ nhớ, getter/mutation và các phép tính dùng chung. |
| `js/validation.js` | Kiểm tra dữ liệu biểu mẫu và các ràng buộc an toàn. |
| `js/scheduler.js` | Thuật toán phân lịch, kiểm tra ứng viên, áp dụng phân công và công bố lịch. |
| `js/admin.js`, `js/admin2.js`, `js/admin3.js` | Màn hình và thao tác nghiệp vụ dành cho Admin. |
| `js/user.js` | Màn hình và thao tác nghiệp vụ dành cho nhân viên. |
| `js/app.js` | Đăng nhập, điều hướng SPA, modal, toast, xác nhận và khởi tạo ứng dụng. |
| `src/main/java/.../ManageSchedule.java` | Mã Java mẫu hiện chỉ in `Hello World!`; không thuộc luồng chạy SPA. |

## Luồng khởi động

1. Trình duyệt tải `index.html`, CSS và `js/bootstrap.js`.
2. `bootstrap.js` tải các tệp HTML trong `partials/` bằng `fetch`.
3. Bootstrap nạp các module JavaScript theo thứ tự đã khai báo để các biến/hàm toàn cục sẵn sàng.
4. Bootstrap phát sự kiện `app:ready`; `App.init()` khởi tạo dữ liệu demo nếu cần, nạp `State` và khôi phục phiên đăng nhập hợp lệ.
5. `App.navigate()` dựng màn hình theo vai trò và route hiện tại.

Do partial được tải bằng `fetch`, cần mở ứng dụng qua HTTP server cục bộ hoặc máy chủ web; mở trực tiếp `index.html` bằng `file://` không phải cách chạy được hỗ trợ.

## Quản lý trạng thái và dữ liệu

- `Storage` là lớp truy cập `localStorage`.
- `State` nạp dữ liệu vào cache bộ nhớ; các màn hình nghiệp vụ sử dụng `State` thay vì đọc trực tiếp storage.
- Các thay đổi được ghi qua mutation của `State`; bộ dữ liệu demo được khởi tạo theo `DATA_VERSION`.
- Dữ liệu nằm riêng trong profile trình duyệt hiện tại; xóa dữ liệu trình duyệt có thể làm mất dữ liệu ứng dụng.

## Ghi chú bảo mật/phạm vi

Đây là ứng dụng demo phía trình duyệt, không phải hệ thống xác thực production. Không dùng dữ liệu cá nhân hoặc mật khẩu thật; việc kiểm soát ở client không thay thế xác thực và phân quyền ở backend.
