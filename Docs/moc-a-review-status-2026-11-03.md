# Kết quả tiếp nối review mốc A — 03/11/2026

## Kết luận

**Chưa đủ bằng chứng nghiệm thu mốc A đạt 100%. Không thực hiện bước xóa review, merge main và push.** Giữ `task_v1.1.md` và các review hiện có.

Đã fetch origin thành công trong lần review này. Bốn nhánh feature vẫn ở các SHA của lần review trước: Tuấn `4a2d030`, Tường `1bfdf2d`, Bảo `0db82b8`, Bình `fda7328`. Phần sửa tiếp theo nằm trên `origin/tuong/integration/moc-a-completion@4455742`, không nằm ở đầu bốn nhánh feature. Baseline tích hợp là `integration/moc-a-completion@814da0a`.

## Bằng chứng kiểm tra được

- Snapshot review riêng: Tường 158 test đạt; Bình 28 test đạt; Tuấn và Bảo có BUILD FAILURE. Đây là kết quả cũ ngày 31/10, không phải test mới trên bản sửa tích hợp.
- `.local-test/final-tests.log`: 292 test đạt, không lỗi/skip; kết thúc lúc 15:31 ngày 31/10/2026. Log không gắn SHA nên không dùng để chứng nhận một đầu nhánh cụ thể.
- `.local-test/http-test.log`: 11 kịch bản PASS, gồm JWT, đăng ký, rules, lịch, swap, nghỉ, tranh slot cuối, báo cáo, quyền và outbox. Đây là evidence cũ; chưa tái chạy trong lần review này.
- `.local-test/browser-test.log`: timeout 15 giây khi chờ `iframe` → `#app-view` tại màn hình chấm công. Không có kết quả hoàn tất toàn bộ browser suite.
- Commit sửa `4455742` có thời điểm 20:50 ngày 31/10, sau các log trên. `Docs/review-fixes-tuan-tuong-bao.md` của commit này ghi SQL Server integration/race và browser là CHƯA XÁC MINH.
- `Docs/issue8-ac-evidence.md` trên bản sửa vẫn là biểu mẫu CHƯA XÁC MINH và còn ánh xạ AC sai: AC-03 phải là tổng giờ; AC-07 là số liệu cùng context; AC-09 là responsive.

## Trạng thái AC-01 đến AC-14

| AC | Kết luận có thể đưa ra hiện tại |
|---|---|
| AC-01, AC-02 | Có code/test thiết kế và HTTP cũ cho rules/engine; chưa chứng nhận toàn bộ percent/fixed/missing-config/thiếu người trên bản sửa mới. |
| AC-03, AC-07, AC-14 | HTTP cũ có đối chiếu tổng giờ; code tích hợp đã gọi nguồn schedule report. Cần chạy lại fixture 808/740, mẫu số 0/null, context và export trên cùng SHA. |
| AC-04, AC-05, AC-06 | Có code/test và HTTP cũ về khóa kỳ; cần kiểm tra lại đủ create/update/draft/submit, form cũ, autoLock tắt, reopen/quyền/audit trên bản sửa. |
| AC-08 | HTTP cũ có swap và duyệt lặp. Bản sửa thay mutation/snapshot/lock nên phải tái kiểm tra SQL Server, rollback và cạnh tranh sửa lịch. |
| AC-09 | Chưa đạt điều kiện chứng nhận: browser suite gần nhất dừng ở chấm công, chưa hoàn tất đủ màn hình/viewport/scale. |
| AC-10 | HTTP cũ chứng minh một số luồng lưu API; chưa có browser suite hoàn tất cho refresh/login/trình duyệt khác của toàn bộ dữ liệu bắt buộc. |
| AC-11 | HTTP cũ có negative quyền; cần gắn SHA và kiểm tra đủ userId/branchId/token tài khoản khóa trên bản tích hợp. |
| AC-12 | Có HTTP cũ về notification/outbox; bản sửa đổi audit/event/mutation cần kiểm tra lại before/after/reason, rollback, retry và dedup trên DB thật. |
| AC-13 | Bản sửa có resolver conflict/scope và UI preview; cần evidence tích hợp mới cho validation/confirm/history và không đổi lịch đã công bố. |

Không coi các dòng chưa chứng nhận là tính năng chắc chắn bị lỗi. Chúng là khoảng trống evidence; AC-09 có một lần chạy thất bại cụ thể, chưa xác định nguyên nhân bằng lần chạy mới.

## Việc còn lại trước quyết định merge

1. Chuẩn bị checkout kiểm thử cô lập của `4455742`, giữ nguyên workspace và fixture hiện có. Chạy Maven cùng SQL Server integration tests trên đúng SHA; lưu SHA, môi trường và kết quả.
2. Chạy lại HTTP và browser với backend build từ cùng SHA. Điều tra timeout chấm công nếu còn tái hiện; hoàn tất sáu viewport 320/360/390/768/1024/1280 và cấu hình scale khác 100%.
3. Kiểm tra race/rollback/outbox mới và các case AC còn thiếu; cập nhật ma trận evidence đúng định nghĩa trong `task_v1.1.md`.
4. Đóng các điểm chưa chốt trong tài liệu sửa, đặc biệt giờ/kỳ chồng ngày và `understaffed_policy`; ghi quyết định và test tương ứng.
5. Chỉ khi đủ AC và không còn lỗi chặn mới xóa các file review thuộc phạm vi yêu cầu, giữ tài liệu task, merge main và push. Không xóa log/fixture cục bộ chỉ vì chúng chưa được Git theo dõi.

Lần tiếp nối này thực hiện fetch, đối chiếu Git, code/tài liệu và log sẵn có; chưa chạy lại Maven, SQL Server hoặc browser. Không thay đổi nhánh, không commit, không merge và không push.
