# Hướng dẫn sử dụng

> **Mục đích:** hướng dẫn thao tác trên giao diện web (frontend SPA) cho hai vai trò **Quản trị viên (Admin)** và **Nhân viên (User)**.
>
> **Phạm vi:** mô tả đúng các màn hình, nút bấm, thông báo có trong `partials/*.html` và `js/*.js`. Dữ liệu được lưu trong trình duyệt đang dùng (xem [setup.md](./setup.md)).

---

## 1. Bắt đầu

### 1.1. Đăng nhập

1. Chạy ứng dụng qua HTTP theo [setup.md](./setup.md) và mở địa chỉ trang.
2. Nhập **Tên đăng nhập**, **Mật khẩu** rồi bấm **Đăng nhập**; biểu tượng con mắt cạnh ô mật khẩu dùng để hiện/ẩn mật khẩu.
3. Hoặc bấm một trong 4 nút **Tài khoản demo**: `admin / admin123`, `nv001 / user123`, `nv002 / user123`, `nv003 / user123`.

| Thông báo | Ý nghĩa |
|---|---|
| Vui lòng nhập tên đăng nhập. / Vui lòng nhập mật khẩu. | Bỏ trống ô tương ứng |
| Tên đăng nhập hoặc mật khẩu không đúng. | Sai thông tin |
| Tài khoản đã bị khóa. Vui lòng liên hệ Admin. | Tài khoản ở trạng thái Tạm khóa |

Sau khi đăng nhập, Admin vào **Tổng quan** (Admin), nhân viên vào **Tổng quan** (cá nhân). Phiên được ghi nhớ khi tải lại trang.

### 1.2. Bố cục giao diện

| Vùng | Chức năng |
|---|---|
| **Sidebar** (trái) | Danh sách màn hình theo vai trò; nút ☰ ở đầu sidebar để thu gọn/mở rộng; cuối sidebar hiện tên và vai trò/Level |
| **Topbar** (trên) | Breadcrumb `Admin › <màn hình>` hoặc `Nhân viên › <màn hình>`; khối tên – ảnh đại diện mở menu tài khoản |
| **Menu tài khoản** | **Thông tin cá nhân**, **Đăng xuất** (có hộp xác nhận "Xác nhận đăng xuất?") |
| **Vùng nội dung** | Màn hình hiện tại |

Trên màn hình có chiều rộng ≤ 768 px: bấm nút ☰ trên topbar để mở sidebar; chọn một mục hoặc chạm vùng tối bên ngoài để đóng.

### 1.3. Quy ước chung

- **Hộp thoại (modal)** đóng bằng nút ×, bấm ra ngoài hoặc phím `Esc`.
- **Hộp xác nhận** xuất hiện trước các thao tác quan trọng (khóa, xóa, công bố…); `Esc` hoặc **Hủy** để bỏ qua.
- **Thông báo nhanh (toast)** góc màn hình, tự ẩn sau khoảng 4 giây.
- Ô nhập lỗi được viền đỏ, thông báo lỗi hiển thị ngay dưới ô.
- Bảng hoặc lịch rộng có thể cuộn ngang; khi đó vùng cuộn nhận focus bằng phím `Tab`.

### 1.4. Nhãn trạng thái

| Nhóm | Nhãn hiển thị | Giá trị |
|---|---|---|
| Kỳ đăng ký | Nháp · Chưa mở đăng ký · Đang mở đăng ký · Đã khóa | `draft` · `not_open` · `open` · `locked` |
| Đăng ký rảnh | Chưa đăng ký · Nháp · Đã gửi đăng ký | (không có bản ghi) · `draft` · `submitted` |
| Ca làm việc | Chưa phân lịch · Nháp · Đã công bố | `not_scheduled` · `draft` · `published` |
| Nhân sự trong ca | Đã đủ người · Thiếu nhân sự | Số người đã phân ≥ / < tổng nhu cầu |
| Tài khoản | Đang hoạt động · Tạm khóa | `active` · `inactive` |

---

## 2. Hướng dẫn cho Quản trị viên

### 2.1. Quy trình tổng thể

```
Quản lý nhân sự & Level → Cấu hình kỳ đăng ký → Mở kỳ
→ (Nhân viên đăng ký thời gian rảnh) → Khóa kỳ
→ Quản lý ca → Quy tắc phân lịch
→ Phân lịch tự động (Chạy thử → Áp dụng)
→ Bổ sung thủ công (nếu thiếu) → Duyệt & công bố
→ Lịch sử & báo cáo
```

### 2.2. Tổng quan

- Tiêu đề phụ hiển thị **kỳ hiện tại** = kỳ đang mở đăng ký, nếu không có thì kỳ đầu tiên trong danh sách. (Ô chọn kỳ ở góc phải hiện chưa thay đổi dữ liệu hiển thị.)
- Khối **Cần chú ý**: kỳ sẽ đóng trong < 48 giờ, số nhân viên chưa đăng ký, số ca thiếu nhân sự.
- 6 thẻ thống kê: Nhân viên hoạt động, Tổng ca trong kỳ, Ca đã đủ người, Ca thiếu nhân sự, NV chưa đăng ký, Ca đã công bố. (Đủ/thiếu người được tính trên các ca **đã công bố**.)
- **Tiến độ phân lịch**: tổng giờ đã phân / tổng giờ mục tiêu và % hoàn thành.
- **Trạng thái kỳ đăng ký**, **Hoạt động gần đây** (6 bản ghi lịch sử mới nhất, nút **Xem tất cả**). Thao tác vừa thực hiện chỉ xuất hiện ở đây sau khi tải lại trang.
- Nút **Chạy phân lịch** chuyển đến màn hình Phân lịch tự động.

### 2.3. Quản lý nhân sự

**Danh sách** (không hiển thị tài khoản Admin): Mã NV, Họ tên, Tài khoản, Level, Trạng thái, Tỷ lệ (%), Giờ MT (mục tiêu), Giờ đã phân, Còn thiếu (✓ nếu đã đủ). Tìm kiếm theo tên hoặc mã; lọc theo Level và trạng thái.

**Thêm/Sửa nhân viên** (nút **Thêm nhân viên** hoặc **Sửa** trên từng dòng):

| Trường | Quy tắc |
|---|---|
| Mã nhân viên * | Bắt buộc, ≤ 20 ký tự, không trùng |
| Họ và tên * | Bắt buộc, ≤ 100 ký tự |
| Tên đăng nhập * | Chữ thường, số, gạch dưới, 3–30 ký tự, không trùng; **không sửa được** sau khi tạo |
| Mật khẩu * / Mật khẩu mới | Tối thiểu 6 ký tự; khi sửa, để trống nếu không đổi |
| Level * | Bắt buộc |
| Tỷ lệ giờ mục tiêu (%) | 0–200, mặc định 100. Giờ mục tiêu = Giờ chuẩn × Tỷ lệ / 100 |
| Số điện thoại, Email, Ghi chú | Không bắt buộc |

Lưu ý: lưu form sửa sẽ đặt tài khoản về trạng thái **Đang hoạt động**.

**Thao tác khác trên từng dòng**

- **Chi tiết**: thông tin, giờ mục tiêu, giờ đã phân, 10 ca gần nhất; nút **Chỉnh sửa**.
- **Khóa / Mở khóa**: đổi trạng thái tài khoản sau khi xác nhận; tài khoản bị khóa không đăng nhập được và không được đưa vào phân lịch.

**Quản lý Level** (nút **Quản lý Level**): bảng Thứ tự, Tên Level, Mô tả, Màu, Số NV; thêm Level mới (Thứ tự ≥ 1, Tên * ≤ 50 ký tự, không trùng, Mô tả, Màu); **Sửa**; **Xóa** chỉ khả dụng khi Level không còn nhân viên.

### 2.4. Cấu hình kỳ đăng ký

Mỗi kỳ hiển thị dạng thẻ: tên, khoảng ngày, chu kỳ, trạng thái, thời điểm mở, hạn chót, số đã đăng ký/tổng nhân viên, số chưa đăng ký, tự động khóa, số ngày được miễn.

**Nút trên thẻ kỳ**

| Nút | Điều kiện hiển thị | Kết quả |
|---|---|---|
| **Mở đăng ký** | Kỳ ở trạng thái Nháp hoặc Chưa mở | Trạng thái → Đang mở đăng ký |
| **Khóa đăng ký** | Kỳ đang mở | Trạng thái → Đã khóa |
| **Mở lại** | Kỳ đã khóa và bật "Cho phép Admin mở lại" | Trạng thái → Đang mở đăng ký |
| **Chỉnh sửa** | Luôn có | Mở form kỳ |
| **Sao chép** | Luôn có | Mở form tạo kỳ mới, điền sẵn tên "… (Bản sao)", ngày bắt đầu, ngày kết thúc |
| **Xem đăng ký** | Luôn có | Danh sách nhân viên đang hoạt động với trạng thái đăng ký và ngày gửi |

**Form kỳ đăng ký** (nút **Tạo kỳ đăng ký**)

| Nhóm | Trường |
|---|---|
| Thông tin cơ bản | Tên kỳ * (không trùng), Ngày bắt đầu *, Ngày kết thúc * (không trước ngày bắt đầu), Chu kỳ: Theo tuần / Theo tháng / Theo năm |
| Thời gian đăng ký | Thời điểm mở đăng ký, Hạn chót đăng ký (phải sau thời điểm mở); công tắc **Tự động khóa khi hết hạn**, **Cho phép sửa sau hạn**, **Cho phép Admin mở lại** |
| Ngày cần đăng ký | Chọn các thứ CN, T2 … T7 (mặc định T2–T7) |
| Ngày được miễn đăng ký | Chọn ngày → **Thêm ngày miễn**; bấm × để bỏ |

Kỳ mới tạo có trạng thái **Chưa mở đăng ký**. Việc khóa kỳ được thực hiện bằng nút **Khóa đăng ký**; công tắc "Tự động khóa khi hết hạn" hiện chỉ được lưu và hiển thị.

### 2.5. Lịch làm việc

- Chế độ **Tháng / Tuần / Ngày**; nút ‹ › để lùi/tiến, **Hôm nay** để về ngày hiện tại.
- **Tháng**: mỗi ô ngày hiển thị tối đa 3 ca (và "+n ca"); bấm vào ngày để chuyển sang chế độ Ngày.
- **Tuần**: mỗi ca là một thẻ (tên, giờ, số người/nhu cầu) – bấm để xem chi tiết; nút **+ Ca** dưới mỗi ngày để thêm ca cho ngày đó.
- **Ngày**: danh sách ca kèm trạng thái, giờ, thời lượng, nhân sự, chế độ; nút **Sửa**, **Chi tiết**.
- Màu thẻ ca: ca đã công bố đủ người / ca đã công bố thiếu người / ca chưa công bố (nháp).
- Nút **Thêm ca** ở đầu trang.

### 2.6. Quản lý ca

**Danh sách ca**: Ngày, Tên ca, Giờ, Thời lượng, Chế độ, Nhân sự (đã phân/nhu cầu – đỏ nếu thiếu), Trạng thái. Lọc theo ngày và trạng thái (Chưa phân lịch, Nháp, Đã công bố); **Xóa lọc**.

**Thao tác trên từng ca**: **Sửa**, **Chi tiết**, **Sao chép** (tạo ca mới cùng ngày, điền sẵn tên "… (Bản sao)", giờ, nhu cầu, chế độ), **Xóa** (chỉ với ca chưa công bố; xóa cả phân công của ca).

**Form ca làm việc**

| Trường | Quy tắc |
|---|---|
| Tên ca *, Ngày làm * | Bắt buộc |
| Giờ bắt đầu *, Giờ kết thúc * | Định dạng giờ:phút; giờ kết thúc ≤ giờ bắt đầu được hiểu là **ca qua đêm** (ví dụ 22:00–06:00) |
| Tổng nhu cầu nhân sự * | Số nguyên dương |
| Ghi chú | Không bắt buộc |
| Chế độ cấu hình * | **A – Theo Level**, **B – Theo User cụ thể**, **C – Kết hợp** |

| Chế độ | Cách cấu hình | Điều kiện hợp lệ |
|---|---|---|
| A – Theo Level | **+ Thêm Level**: chọn Level và số lượng | Ít nhất 1 dòng; mỗi dòng có Level, số lượng > 0; **tổng các Level = tổng nhu cầu** |
| B – Theo User cụ thể | Chọn nhân viên + loại (Bắt buộc / Ưu tiên) → **+ Thêm**; có thể đổi loại (Bắt buộc, Ưu tiên, Thay thế được) hoặc **Xóa** | Ít nhất 1 nhân viên; số bắt buộc ≤ tổng nhu cầu; không trùng giữa bắt buộc và ưu tiên |
| C – Kết hợp | Cả hai phần trên | Số bắt buộc + tổng vị trí Level ≤ tổng nhu cầu; không trùng giữa bắt buộc và ưu tiên |

Khối **Tóm tắt cấu hình nhân sự** cập nhật theo dữ liệu đang nhập. Khi lưu ca vào ngày đã có ca công bố, ứng dụng nhắc "Lưu ý: ngày … đã có n ca đã công bố." (không chặn).

**Chi tiết ca**: thông tin ca, yêu cầu Level, danh sách nhân viên được phân (Họ tên, Level, Phân bởi: Tự động/Admin, nút **Xóa** phân công), nút **Phân công thủ công**, **Công bố ca này** (nếu chưa công bố), **Chỉnh sửa**.

**Phân công thủ công**

1. Chọn **Nhân viên** *, **Kỳ đăng ký** (dùng để đối chiếu thời gian rảnh), nhập **Lý do**.
2. Bấm **Phân công**. Hệ thống kiểm tra:

| Kiểm tra | Kết quả nếu vi phạm |
|---|---|
| Trùng giờ với ca khác của nhân viên | **Chặn**, hiển thị "Xung đột lịch: …" |
| Vượt giờ tối đa/ngày hoặc /tuần | **Chặn**, hiển thị "Vượt giới hạn giờ/ngày …" hoặc "/tuần …" |
| Nhân viên đã có trong ca | **Chặn**, "Nhân viên đã được phân vào ca này." |
| Không có/không khớp đăng ký rảnh | Cho phép, cảnh báo "[Admin override] …" |
| Nghỉ giữa hai ca không đủ | Cho phép, cảnh báo "Thời gian nghỉ giữa ca không đủ …" |

### 2.7. Quy tắc phân lịch

| Nhóm | Trường | Giới hạn | Mặc định |
|---|---|---|---|
| Giờ làm & Mục tiêu | Giờ chuẩn mỗi kỳ | 1–744 | 80 |
| | Giờ tối đa mỗi ngày | 1–24 | 12 |
| | Giờ tối đa mỗi tuần | 1–168 | 48 |
| | Nghỉ tối thiểu giữa 2 ca (giờ) | 0–24 | 8 |
| Tỷ lệ giờ mục tiêu theo Level (%) | Một ô cho mỗi Level | 0–200 | 75 / 100 / 100 / 100 |
| Quy tắc thay thế Level | "Level X **có thể thay** Level Y"; **+ Thêm quy tắc**, **Xóa** | Cần ít nhất 2 Level | 6 quy tắc (Level cao thay Level thấp) |
| Thứ tự ưu tiên & Xử lý | Thứ tự ưu tiên phân lịch; Xử lý ca thiếu nhân sự | — | "Ưu tiên người thiếu nhiều giờ nhất"; "Cảnh báo, cho phép tiếp tục" |

Bấm **Lưu cấu hình** để lưu (có ghi lịch sử kèm giá trị trước/sau). Ghi chú trên màn hình: *"Cấu hình riêng của từng nhân viên sẽ được ưu tiên hơn."* Theo mã nguồn hiện tại, thuật toán luôn ưu tiên người thiếu nhiều giờ nhất và chức năng công bố luôn hỏi xác nhận khi có ca thiếu, không phụ thuộc hai ô chọn ở nhóm cuối.

### 2.8. Phân lịch tự động

1. Chọn **Kỳ phân lịch** (mặc định: kỳ đang mở hoặc đã khóa).
2. Khối kiểm tra dữ liệu hiển thị "Dữ liệu hợp lệ, sẵn sàng phân lịch." hoặc danh sách **Lưu ý** (chưa có ca, n nhân viên chưa đăng ký thời gian rảnh, chưa cấu hình quy tắc, ca chưa cấu hình chế độ). Bấm **Kiểm tra dữ liệu đầu vào** để kiểm tra lại sau khi đổi kỳ.
3. Bấm **Chạy thử (Xem nháp)**: thuật toán chạy **không ghi dữ liệu**. Kết quả gồm:
   - Thẻ: Tổng ca, Phân công, Ca thiếu người, NV chưa đủ giờ.
   - **Ca thiếu nhân sự**: Ca, Ngày, Yêu cầu, Đã phân, Thiếu.
   - **Nhân viên chưa đủ giờ mục tiêu**: Giờ MT, Giờ đã phân, Còn thiếu.
   - **Lý do ứng viên bị loại** (20 mục đầu).
   - **Nhật ký thuật toán** từng bước.
4. Bấm **Áp dụng kết quả** (khả dụng sau khi chạy thử thành công): thuật toán chạy lại và **ghi** phân công; các ca đã xét chuyển trạng thái **Nháp**; toast "Đã áp dụng N phân công."

Nguyên tắc của thuật toán (tóm tắt):

- Chỉ phân các ca **chưa công bố** trong khoảng ngày của kỳ và chỉ dùng nhân viên **đang hoạt động**.
- Nhân viên phải **gửi** đăng ký rảnh, có khung giờ **bao trọn** ca, không trùng ca đã lưu, không vượt giờ tối đa ngày/tuần.
- Vị trí theo Level nhận đúng Level hoặc Level được phép thay thế.
- Ca có ít ứng viên hơn được xử lý trước; giữa các ứng viên hợp lệ, ưu tiên người **còn thiếu nhiều giờ** so với mục tiêu.

Chi tiết: [architecture.md – mục 5](./architecture.md#5-thuật-toán-phân-lịch-tự-động).

### 2.9. Duyệt và công bố

1. Chọn kỳ ở góc phải (mặc định: kỳ đang mở hoặc đã khóa).
2. Cảnh báo "n ca trong lịch nháp đang thiếu nhân sự…" nếu có.
3. Thẻ thống kê: **Ca nháp chờ duyệt** (gồm ca Nháp và Chưa phân lịch), **Ca đã công bố**.
4. Bảng **Ca chờ công bố**: ô chọn từng ca (ca đủ người được chọn sẵn), ô chọn tất cả ở tiêu đề; cột Nhân sự và Trạng thái nhân sự (Đã đủ người/Thiếu nhân sự); nút **Xem**, **Công bố**.

| Nút | Hành vi |
|---|---|
| **Công bố tất cả** | Công bố **các ca đang được chọn**; nếu trong đó có ca thiếu người → báo lỗi, không công bố |
| **Công bố dù thiếu người** | Công bố các ca đang được chọn kể cả ca thiếu người |
| **Công bố** (từng dòng) | Ca đủ người → công bố ngay; ca thiếu → hỏi "Ca chưa đủ nhân sự … Vẫn muốn công bố?" |

Ca đã công bố được liệt kê ở bảng **Ca đã công bố** (20 ca đầu) và xuất hiện trong lịch của nhân viên được phân. Ca đã công bố không bị thuật toán tự động phân lại và không xóa được từ Quản lý ca.

### 2.10. Lịch sử và báo cáo

**Tab Lịch sử hoạt động**: dòng thời gian gồm tiêu đề, người thực hiện, thời điểm, loại sự kiện, chi tiết, lý do. Tìm kiếm theo tiêu đề/chi tiết; lọc theo loại: Nhân sự, Kỳ đăng ký, Phân lịch, Công bố lịch, Phân công thủ công, Quy tắc. Ứng dụng giữ tối đa 500 bản ghi mới nhất. Bản ghi của các thao tác trong phiên hiện tại chỉ hiển thị sau khi tải lại trang (F5).

**Tab Báo cáo**: báo cáo của kỳ đang mở hoặc đã khóa (nếu không có thì kỳ đầu tiên). Mỗi nhân viên đang hoạt động: Level, Giờ mục tiêu, Giờ đã phân (trong kỳ), Tỷ lệ (thanh tiến độ, `%` làm tròn), Số ca, Đánh giá; danh sách sắp theo tỷ lệ tăng dần. Cột Đánh giá dùng nhãn: ≥ 100% "Đã đủ người", 60–99% "Đang phân lịch", < 60% "Thiếu nhân sự".

---

## 3. Hướng dẫn cho Nhân viên

### 3.1. Tổng quan cá nhân

- Lời chào, Level, ngày hiện tại.
- Thẻ: **Giờ đã phân** (trong kỳ hiện tại) và mục tiêu, **Tỷ lệ hoàn thành**, **Tổng ca được phân**, **Đăng ký kỳ hiện tại**.
- **Kỳ đăng ký hiện tại** (kỳ đang mở, nếu không có thì kỳ đã khóa, sau đó kỳ đầu tiên): tên, trạng thái, hạn đăng ký, trạng thái đăng ký của bạn; nút **Đăng ký ngay** / **Tiếp tục đăng ký** khi kỳ đang mở và bạn chưa gửi.
- **Giờ làm trong kỳ**: đã phân / mục tiêu, "✓ Đã đạt mục tiêu" hoặc "Còn thiếu …h".
- **Ca làm việc sắp tới**: 5 ca đã công bố gần nhất từ hôm nay; nút **Xem tất cả**.

### 3.2. Đăng ký thời gian rảnh

1. Mở **Đăng ký thời gian rảnh**. Màn hình dùng kỳ **đang mở**; nếu không có thì kỳ **đã khóa**; nếu không có kỳ nào, hiển thị "Chưa có kỳ đăng ký nào đang mở".
2. Chọn tuần bằng các nút **Tuần 1 (dd/mm)**, **Tuần 2**, …
3. Mỗi ô ngày có một trong các trạng thái:

| Ô ngày | Ý nghĩa | Thao tác |
|---|---|---|
| Ngoài kỳ | Ngày không thuộc kỳ | — |
| Ngày được miễn | Admin đã miễn đăng ký | — |
| Không cần đăng ký | Thứ không nằm trong "Ngày cần đăng ký" | — |
| Chưa đăng ký | Ngày cần khai báo, chưa có khung giờ | **Cả ngày** (00:00–23:59), **Chọn giờ** |
| Có khung giờ (nền xanh) | Đã khai báo | **Sửa giờ**, **Xóa** |

4. **Chọn giờ / Sửa giờ**: nhập Giờ bắt đầu, Giờ kết thúc cho từng khung; **+ Thêm khung giờ** để thêm nhiều khung trong ngày; **Lưu**.
5. Mọi thay đổi được lưu ngay; bản đăng ký chưa gửi có trạng thái **Nháp**. Nút **Lưu nháp** hiển thị xác nhận "Đã lưu bản nháp thành công."
6. Bấm **Gửi đăng ký** → xác nhận. Điều kiện: kỳ đang mở và đã có ít nhất một ngày được khai báo. Sau khi gửi, trạng thái là **Đã gửi đăng ký**.

Lưu ý quan trọng:

- Chỉ bản đăng ký **đã gửi** mới được thuật toán sử dụng.
- Để được xếp vào một ca, khung giờ rảnh của bạn phải **bao trọn** giờ của ca đó.
- Khi kỳ **đã khóa** và Admin không bật "Cho phép sửa sau hạn", màn hình hiện "Kỳ đăng ký đã khóa. Bạn không thể chỉnh sửa thêm." và ẩn các nút thao tác.
- Khi kỳ đã khóa nhưng Admin bật "Cho phép sửa sau hạn", bạn vẫn sửa được khung giờ, nhưng **Gửi đăng ký** sẽ báo "Kỳ đăng ký chưa mở hoặc đã khóa."

### 3.3. Lịch làm việc của tôi

- Tiêu đề phụ: tổng số ca được phân và giờ đã phân / giờ mục tiêu (con số này tính cả ca đã phân nhưng chưa công bố).
- Chế độ **Tuần** (mặc định) và **Tháng**; nút ‹ ›, **Hôm nay**.
- Chỉ hiển thị các ca **đã công bố** mà bạn được phân: tên ca, giờ, thời lượng.

### 3.4. Thông tin cá nhân

- Xem: Mã nhân viên, Họ và tên, Tài khoản, Level, Tỷ lệ giờ mục tiêu, Điện thoại, Email, Ngày tham gia, Ghi chú, trạng thái. Nhân viên không tự sửa được các thông tin này (do Admin cập nhật).
- **Đổi mật khẩu**: nhập Mật khẩu hiện tại, Mật khẩu mới (≥ 6 ký tự), Xác nhận mật khẩu mới.

| Thông báo | Ý nghĩa |
|---|---|
| Mật khẩu hiện tại không đúng. | Sai mật khẩu đang dùng |
| Mật khẩu phải có ít nhất 6 ký tự. | Mật khẩu mới quá ngắn |
| Xác nhận mật khẩu không khớp. | Hai ô mật khẩu mới khác nhau |
| Đã đổi mật khẩu thành công. | Hoàn tất |

---

## 4. Câu hỏi thường gặp

| Câu hỏi | Trả lời |
|---|---|
| Vì sao tôi không được xếp ca dù đã đăng ký? | Bản đăng ký cần ở trạng thái **Đã gửi**; khung giờ phải bao trọn giờ ca; có thể đã đủ người, trùng ca khác, hoặc vượt giờ tối đa ngày/tuần. Admin xem lý do ở mục "Lý do ứng viên bị loại". |
| Vì sao tôi không thấy ca trong "Lịch làm việc của tôi"? | Chỉ ca **đã công bố** mới hiển thị. |
| Dữ liệu có dùng chung giữa các máy không? | Không. Bản hiện tại lưu dữ liệu trong trình duyệt của từng máy. |
| Làm sao khôi phục dữ liệu mẫu? | Xóa các khóa `pls_*` trong Local Storage của trình duyệt rồi tải lại trang (xem [setup.md – mục 2.5](./setup.md#25-lưu-ý-dữ-liệu-frontend)). Thao tác này xóa toàn bộ dữ liệu hiện có và không thể hoàn tác. |
| Mục "Thông tin cá nhân" của Admin dẫn đi đâu? | Bộ route của Admin không có màn hình hồ sơ, nên mục này đưa về **Tổng quan**. |
