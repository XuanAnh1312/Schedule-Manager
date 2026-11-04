# Schedule Manager

Quản lý kỳ đăng ký, thời gian rảnh, quy tắc, phân ca, duyệt/công bố lịch, đổi/nhận ca, nghỉ phép, thông báo, chấm công và báo cáo.

## Chạy ứng dụng

1. Chuẩn bị Java 21+, Maven và SQL Server; tạo `.env` theo `.env.example` với thông tin môi trường của bạn.
2. Chạy `mvn spring-boot:run` từ thư mục dự án.
3. Mở **http://localhost:8081/**. Giao diện và API dùng cùng origin; không cần chạy HTTP server riêng.

Trên Windows có thể dùng [CHAY_BACKEND.bat](HUONG_DAN/CHAY_BACKEND.bat) và [CHAY_FRONTEND.bat](HUONG_DAN/CHAY_FRONTEND.bat). Xem [hướng dẫn chạy chi tiết](HUONG_DAN/HUONG_DAN_CHAY_HE_THONG.md) và [báo cáo bàn giao/kế hoạch mốc B](HUONG_DAN/BAO_CAO_BAN_GIAO_MOC_A_VA_KE_HOACH_MOC_B.md).

Giao diện chính là `workspace.html`, đọc/ghi dữ liệu nghiệp vụ bằng API và chỉ lưu JWT của phiên trong sessionStorage. `demo.html` giữ giao diện localStorage cũ để tham khảo, không phải nguồn dữ liệu của ứng dụng chính.

## Kiểm thử và tích hợp

- Unit/controller test: `mvn test`.
- Hướng dẫn SQL Server, HTTP/E2E, migration và kết quả mốc A: [Docs/moc-a-completion.md](Docs/moc-a-completion.md).
- Playwright chỉ dùng cho kiểm thử: `npm ci`, `npm run browser:install`.

Các nhánh thành viên từng có migration trùng số. Trước khi nâng cấp database đã chạy migration của một nhánh riêng, đối chiếu lịch sử theo hướng dẫn tích hợp; không bỏ qua lỗi checksum bằng `repair`.
