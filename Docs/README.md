# Mục lục tài liệu

Trang này giúp tìm đúng tài liệu cần đọc. Các file vẫn nằm nguyên chỗ cũ; việc sắp xếp lại thư mục sẽ làm sau khi nhóm thống nhất.

**Người mới đọc theo thứ tự:** [requirements.md](requirements.md) → [use-cases.md](use-cases.md) → [architecture.md](architecture.md) → [flows.md](flows.md) → [database.md](database.md) → [setup.md](setup.md).

Tài liệu chung ngoài thư mục này: [rules.md](../rules.md) (quy tắc kỹ thuật của nhóm) · [task_v1.1.md](../task_v1.1.md) (phân công, mốc A/B) · [Ban_chuc_nang_HRM_V1_1.md](../Ban_chuc_nang_HRM_V1_1.md) (phạm vi chức năng, AC-01…14) · [HUONG_DAN/](../HUONG_DAN/) (báo cáo bàn giao, hướng dẫn chạy).

## 1. Tổng quan — hệ thống làm gì

| File | Nội dung |
|---|---|
| [requirements.md](requirements.md) | Yêu cầu và phạm vi chức năng |
| [use-cases.md](use-cases.md) | Tác nhân và 7 lược đồ use case |

## 2. Thiết kế — hệ thống được xây ra sao

| File | Nội dung |
|---|---|
| [architecture.md](architecture.md) | Kiến trúc, công nghệ, module, bảo mật, chức năng đáng chú ý, lược đồ lớp thiết kế |
| [flows.md](flows.md) | Lưu đồ hoạt động, sơ đồ tuần tự, sơ đồ trạng thái |
| [database.md](database.md) | Migration V1–V18, mô hình miền, ERD |
| [api.md](api.md) | Danh sách API |
| [diagrams/](diagrams/) | 32 sơ đồ `.drawio.png` (mở bằng draw.io để sửa) |

Quy ước tên sơ đồ: `0x` kiến trúc · `1x` use case · `2x` hoạt động · `3x` tuần tự · `4x` trạng thái · `5x` lớp · `6x` ERD.

## 3. Vận hành — chạy, kiểm thử, triển khai, sử dụng

| File | Nội dung |
|---|---|
| [setup.md](setup.md) | Cài đặt và chạy môi trường phát triển |
| [testing.md](testing.md) | Cách kiểm thử |
| [deployment.md](deployment.md) | Triển khai (đang trống) |
| [user-guide.md](user-guide.md) | Hướng dẫn sử dụng |

## 4. Tài liệu module

| File | Module |
|---|---|
| [bao-requests-leave-notifications.md](bao-requests-leave-notifications.md) | Đổi ca, nghỉ phép, ca trống, thông báo |
| [issue8-attendance.md](issue8-attendance.md) | Chấm công và báo cáo |

## 5. Hồ sơ mốc A — bản ghi tại một thời điểm, không cập nhật theo code

| File | Nội dung |
|---|---|
| [moc-a-completion.md](moc-a-completion.md) | Trỏ tới báo cáo bàn giao mốc A |
| [integration-baseline-moc-a.md](integration-baseline-moc-a.md) | Bản tích hợp mốc A |
| [moc-a-review-status-2026-11-03.md](moc-a-review-status-2026-11-03.md) | Kết quả review ngày 03/11/2026 |
| [review-task-3.2-3.5.md](review-task-3.2-3.5.md) | Review task 3.2–3.5 |
| [review-fixes-tuan-tuong-bao.md](review-fixes-tuan-tuong-bao.md) | Review các bản sửa |
| [issue8-ac-evidence.md](issue8-ac-evidence.md) | Bằng chứng nghiệm thu issue 8 |

## 6. Lưu trữ — tài liệu cũ, chỉ để tham khảo

| File | Ghi chú |
|---|---|
| [current-system-specification.md](current-system-specification.md) | Đặc tả hệ thống trước mốc A (SPA `localStorage`, V1–V5) |
| [legacy-migrations/](legacy-migrations/) | Migration cũ, không chạy |

## Giữ tài liệu đúng với code

- PR nào đổi API, schema hoặc luồng nghiệp vụ thì sửa tài liệu và sơ đồ liên quan trong cùng PR (theo `rules.md`).
- Tài liệu ở mục 1–4 phải luôn đúng với code; tài liệu ở mục 5–6 giữ nguyên như bản ghi.
- Khi đóng mỗi mốc, rà lại mục 1–4 và cập nhật dòng "Đối chiếu với commit …" ở đầu file.
