# Yêu cầu và phạm vi chức năng

## Mục tiêu

Hệ thống hỗ trợ quản lý đăng ký thời gian rảnh, ca làm việc và phân công nhân sự theo kỳ. Ứng dụng hiện là bản demo chạy trên trình duyệt; dữ liệu được lưu cục bộ trên thiết bị.

## Vai trò

### Quản trị viên

- Xem tổng quan và tình hình phân lịch.
- Quản lý nhân sự, trạng thái tài khoản và cấp độ (Level).
- Tạo, mở, khóa và cấu hình các kỳ đăng ký.
- Xem lịch, tạo và chỉnh sửa ca làm việc.
- Cấu hình quy tắc phân lịch.
- Chạy phân lịch tự động, xem kết quả và các trường hợp thiếu nhân sự.
- Phân công thủ công, duyệt và công bố ca.
- Xem lịch sử thay đổi và báo cáo.

### Nhân viên

- Xem tổng quan cá nhân và lịch làm việc của mình.
- Khai báo thời gian rảnh trong kỳ đang mở; lưu bản nháp hoặc gửi đăng ký.
- Xem và cập nhật thông tin cá nhân; đổi mật khẩu.

## Quy tắc nghiệp vụ thể hiện trong ứng dụng

- Các kỳ đăng ký có thời gian áp dụng, thời điểm mở, hạn chót, ngày trong tuần áp dụng, ngày ngoại lệ và trạng thái.
- Ca làm việc có thời gian bắt đầu/kết thúc, số nhân sự cần thiết, trạng thái và một trong các chế độ phân công theo Level, theo nhân viên hoặc kết hợp.
- Quy tắc phân lịch có các giới hạn giờ theo ngày, tuần và kỳ; thời gian nghỉ tối thiểu giữa các ca; tỷ lệ giờ mục tiêu theo Level; quy tắc thay thế Level; thứ tự ưu tiên và cách xử lý ca thiếu người.
- Hệ thống kiểm tra an toàn phân công, bao gồm thời gian rảnh và xung đột/ràng buộc lịch được triển khai trong bộ kiểm tra hiện tại.
- Kết quả phân lịch tự động có thể chạy thử (dry run) trước khi áp dụng. Thuật toán là heuristic của bản demo, không đảm bảo tối ưu toàn cục.
- Không công bố ca thiếu người nếu chưa có xác nhận cho phép tiếp tục.
- Lịch sử thay đổi được lưu tối đa 500 mục.

## Yêu cầu phi chức năng

- Giao diện tiếng Việt, có bố cục responsive cho màn hình nhỏ.
- Ứng dụng chạy như SPA trong trình duyệt hiện đại có hỗ trợ JavaScript và `localStorage`.
- Dữ liệu và phiên đăng nhập hiện được lưu theo trình duyệt/thiết bị; chưa có đồng bộ người dùng qua mạng.

## Ngoài phạm vi đã xác minh

- Backend, API từ xa, cơ sở dữ liệu máy chủ và phân quyền được cưỡng chế phía máy chủ.
- Triển khai lên môi trường production.
- Cơ chế sao lưu/khôi phục dữ liệu ngoài thao tác khôi phục dữ liệu demo.
- Bộ kiểm thử tự động và ngưỡng hiệu năng chính thức.
