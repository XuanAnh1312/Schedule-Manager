# Schedule Manager

Quản lý kỳ đăng ký, thời gian rảnh, quy tắc, phân ca, duyệt/công bố lịch, đổi/nhận ca, nghỉ phép, thông báo, chấm công và báo cáo.

## Chạy ứng dụng

1. Chuẩn bị Java 21+, Maven và SQL Server; tạo `.env` theo `.env.example` với thông tin môi trường của bạn.
2. Mở [CHAY_HE_THONG.bat](HUONG_DAN/CHAY_HE_THONG.bat). Một ứng dụng Spring Boot phục vụ cả giao diện lẫn API, chờ SQL Server sẵn sàng rồi tự mở trình duyệt.
3. Truy cập **http://localhost:8080/**. Tạo/sửa dữ liệu qua API và lưu trực tiếp vào SQL Server theo `.env`.

Chỉ cần một file `.bat`, không cần Node.js hoặc server frontend riêng để sử dụng. Giữ cửa sổ chạy, nhấn Ctrl+C để dừng. Xem [hướng dẫn chạy chi tiết](HUONG_DAN/HUONG_DAN_CHAY_HE_THONG.md) và [báo cáo bàn giao/kế hoạch mốc B](HUONG_DAN/BAO_CAO_BAN_GIAO_MOC_A_VA_KE_HOACH_MOC_B.md).

Giao diện chính là `workspace.html`, đọc/ghi dữ liệu nghiệp vụ bằng API và chỉ lưu JWT của phiên trong sessionStorage. `demo.html` giữ giao diện localStorage cũ để tham khảo, không phải nguồn dữ liệu của ứng dụng chính.

## Kiểm thử và tích hợp

- Unit/controller test: `mvn test`.
- Hướng dẫn SQL Server, HTTP/E2E, migration và kết quả mốc A: [Docs/moc-a-completion.md](Docs/moc-a-completion.md).
- Playwright chỉ dùng cho kiểm thử: `npm ci`, `npm run browser:install`.

Các nhánh thành viên từng có migration trùng số. Trước khi nâng cấp database đã chạy migration của một nhánh riêng, đối chiếu lịch sử theo hướng dẫn tích hợp; không bỏ qua lỗi checksum bằng `repair`.
