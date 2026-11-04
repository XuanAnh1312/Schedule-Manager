# TÀI LIỆU CHỐT PHẠM VI CHỨC NĂNG
## Workforce Management–First HRM

**Phiên bản tài liệu:** 1.1  
**Định hướng sản phẩm:** Hệ thống quản lý nhân sự tập trung vào lập lịch và vận hành nhân viên, có khả năng mở rộng thành HRM đầy đủ.  
**Đối tượng sử dụng:** Owner/Admin, người quản lý, người xếp lịch và nhân viên Full-time/Part-time.  
**Nguyên tắc triển khai:** Hoàn thiện các nghiệp vụ cốt lõi và bảo đảm dữ liệu thống nhất trước khi mở rộng sang tất cả phân hệ HRM.

---

## 1. Mục tiêu và định hướng sản phẩm

Hệ thống giúp doanh nghiệp quản lý nhân viên và vận hành lịch làm việc trên cùng một nền tảng. Trọng tâm của phiên bản đầu là đăng ký thời gian rảnh, tạo ca, tự động và thủ công phân ca, kiểm soát giờ làm, công bố lịch, xử lý yêu cầu phát sinh, chấm công cơ bản và báo cáo vận hành.

Sản phẩm phải hỗ trợ cả **Full-time** và **Part-time**, nhưng không mặc định hai nhóm có cùng mục tiêu giờ, cách xếp lịch, chính sách công hoặc điều kiện hợp đồng. Các chính sách liên quan cần cấu hình được.

### 1.1. Nguyên tắc thiết kế

1. **Một nguồn dữ liệu chuẩn:** Dữ liệu nghiệp vụ được lưu và xử lý ở backend/database; các màn hình không tự giữ những bản dữ liệu nghiệp vụ độc lập trong `localStorage`.
2. **Điều kiện bắt buộc khác tiêu chí ưu tiên:** Ví dụ, không đủ chứng chỉ hoặc bị trùng ca là điều kiện chặn; mong muốn làm ca sáng chỉ là tiêu chí ưu tiên.
3. **Không âm thầm vượt giới hạn:** Khi không thể xếp đủ người mà vẫn tuân thủ các giới hạn, hệ thống phải báo thiếu nhân sự và giải thích nguyên nhân thay vì tự ý vượt giới hạn.
4. **Mọi thao tác quan trọng có dấu vết:** Ghi nhận người thao tác, thời gian, dữ liệu trước/sau và lý do khi cần.
5. **Phân biệt lịch dự kiến và giờ thực tế:** Lịch làm không được dùng thay cho dữ liệu chấm công đã được duyệt.
6. **Phân quyền ở backend:** Ẩn nút trên giao diện không được xem là cơ chế bảo mật đủ.
7. **Thiết kế mở rộng nhưng không làm quá phạm vi V1.0:** Các phân hệ Payroll, LMS và HRM nâng cao được chuẩn bị về mặt mô hình dữ liệu, nhưng chỉ triển khai khi phạm vi và quy tắc đã được chốt.

### 1.2. Phân biệt các khái niệm nhân sự

- **Loại hình làm việc (`employeeType`):** Full-time, Part-time, thực tập sinh, thời vụ hoặc loại tùy chỉnh.
- **Level:** Cấp độ chuyên môn, dùng trong quy tắc đủ điều kiện hoặc thay thế khi phân ca.
- **Chức vụ/vị trí công việc:** Công việc nhân viên đảm nhiệm, ví dụ nhân viên bán hàng hoặc quản lý ca.
- **Kỹ năng/chứng chỉ:** Năng lực cụ thể cần thiết cho một số ca hoặc vị trí.
- **Role/quyền truy cập:** Quyền sử dụng hệ thống, ví dụ Admin, Scheduler hoặc Employee.

Không gộp các khái niệm trên thành một trường dữ liệu duy nhất.

---

### 1.3. Admin tự cấu hình tham số nghiệp vụ

**Nguyên tắc chốt:** Không cố định các tỷ lệ hoặc ngưỡng nghiệp vụ như 120% giờ mục tiêu. Admin/Owner được quyền cấu hình các tham số vận hành trong màn hình **Cài đặt quy tắc**, theo phạm vi toàn công ty, chi nhánh, loại hình làm việc, nhóm nhân viên hoặc từng nhân viên khi cần.

Hệ thống cần có giao diện cấu hình dễ hiểu, ghi rõ ý nghĩa từng thông số, đơn vị, phạm vi áp dụng, giá trị hiện tại và ảnh hưởng khi thay đổi. Admin không cần tự tính tỷ lệ bằng tay: nhập giờ mục tiêu và chọn giới hạn; hệ thống tự tính giờ tối đa, mức chênh lệch và tỷ lệ trong báo cáo.

Các tham số cần cấu hình gồm:

- **Giờ làm:** mục tiêu giờ theo kỳ; giới hạn tối đa theo số giờ cụ thể hoặc theo phần trăm của mục tiêu; số giờ tối thiểu nếu doanh nghiệp cần; ngưỡng cảnh báo khi gần đạt giới hạn.
- **Điều kiện ca:** số người tối thiểu/tối đa; nhu cầu theo Level/chức vụ/kỹ năng; Level nào được phép thay thế Level khác; thời gian nghỉ tối thiểu giữa hai ca; số ngày làm liên tiếp tối đa; cách xử lý ca qua đêm.
- **Cách phân lịch:** tiêu chí ưu tiên và mức độ ưu tiên khi cân bằng giờ, cân bằng số ca, tôn trọng thời gian rảnh, hạn chế chi phí; phạm vi cho phép Admin sửa thủ công hoặc ghi đè ngoại lệ.
- **Đăng ký:** ngày giờ mở kỳ, hạn chót, múi giờ, tự động khóa hay khóa thủ công, điều kiện mở lại kỳ và mốc nhắc trước hạn.
- **Duyệt và thông báo:** loại yêu cầu cần duyệt, người/cấp duyệt, các sự kiện cần thông báo, kênh gửi và thời điểm nhắc.
- **Báo cáo:** cách làm tròn số hiển thị, đơn vị giờ, ngưỡng cảnh báo và nhãn phân biệt dữ liệu nháp với lịch đã công bố.

**Cách cấu hình giới hạn giờ tối đa:** Admin chọn một trong hai cách: (1) nhập số giờ tối đa cụ thể trong kỳ; hoặc (2) nhập tỷ lệ phần trăm so với giờ mục tiêu. Ví dụ chỉ để minh họa: mục tiêu 60 giờ, Admin chọn 125%, hệ thống tự tính giới hạn là 75 giờ. 125% không phải giá trị bắt buộc hoặc mặc định của sản phẩm.

Khi một thông số chưa được thiết lập, hệ thống phải chỉ rõ thông số còn thiếu và yêu cầu Admin hoàn tất thiết lập trước khi chạy chức năng liên quan; không tự chọn một con số nghiệp vụ tùy ý. Có thể cung cấp giá trị tham khảo, nhưng phải gắn nhãn **đề xuất** và yêu cầu Admin xác nhận trước khi áp dụng.

Giới hạn cấu hình của doanh nghiệp không được vô hiệu hóa các điều kiện an toàn hoặc quy định pháp luật áp dụng. Nếu cấu hình xung đột với hợp đồng, quy tắc bắt buộc hoặc chính sách an toàn, hệ thống phải chặn hoặc cảnh báo theo mức độ đã xác định; không cho phép một thiết lập thông thường âm thầm vượt qua các ràng buộc đó.

---

## 2. Các bất tiện hiện tại cần xử lý

Đây là các vấn đề đã được ghi nhận trong bản demo và phải được xử lý trước khi xem chức năng lập lịch là hoàn thiện.

### 2.1. Thuật toán xếp lịch vượt xa giờ mục tiêu

**Hiện trạng:** `js/scheduler.js` dùng giờ mục tiêu chủ yếu để xếp thứ tự ưu tiên, chưa có giới hạn trần. Ví dụ, nhân viên `nv001` (Trần Văn An) có mục tiêu 60 giờ nhưng được phân 136 giờ, tương đương 227%. Tổng giờ trong phương án của Admin hiển thị 808/740 giờ, tương đương khoảng 109.19% (hiển thị 109% nếu làm tròn đến số nguyên gần nhất).

**Yêu cầu chốt:**

- Bổ sung giới hạn giờ tối đa cho mỗi nhân viên theo kỳ.
- Admin tự chọn cách đặt giới hạn: nhập số giờ tối đa cụ thể hoặc nhập tỷ lệ phần trăm so với giờ mục tiêu. Không áp đặt sẵn mức 120% hay một tỷ lệ khác.
- Ví dụ minh họa cách hệ thống tính: nếu mục tiêu là 60 giờ và Admin cấu hình 125%, hệ thống tự tính mức tối đa là 75 giờ. Đây chỉ là ví dụ, không phải giá trị mặc định.
- Giờ mục tiêu và giới hạn tối đa phải là hai giá trị khác nhau. Giờ mục tiêu dùng để cân bằng phân bổ; giới hạn tối đa là ràng buộc của thuật toán.
- Nếu mục tiêu giờ hoặc giới hạn tối đa chưa được cấu hình, hệ thống phải hiển thị mục nào đang thiếu và yêu cầu Admin hoàn tất thiết lập trước khi tự động xếp lịch cho nhân viên đó. Chỉ được dùng chính sách dự phòng nếu Admin đã thiết lập và xác nhận chính sách đó.
- Thuật toán phải kiểm tra giới hạn **trước khi chốt mỗi lần phân công**, không chỉ sắp xếp nhân viên theo mức ưu tiên.
- Khi tất cả nhân viên phù hợp đều chạm giới hạn, ca phải được đánh dấu thiếu người và nêu nguyên nhân; không tự tăng giờ của nhân viên để lấp ca.
- Nếu doanh nghiệp cho phép Admin vượt giới hạn trong trường hợp ngoại lệ, phải có bước xác nhận riêng, quyền phù hợp, lý do và audit log. Việc ghi đè thủ công không được xảy ra âm thầm trong tự động xếp lịch.
- Báo cáo tổng cần thể hiện tổng giờ đã xếp, tổng giờ mục tiêu, mức chênh lệch và tỷ lệ phần trăm. Ví dụ `808/740 giờ · vượt 68 giờ · 109%`.

**Phân biệt hai loại giới hạn:**

- **Giới hạn giờ theo mục tiêu của hệ thống:** Chính sách do Admin cấu hình theo số giờ tối đa hoặc tỷ lệ phần trăm của mục tiêu; không cố định một tỷ lệ cho mọi doanh nghiệp.
- **Giới hạn theo hợp đồng/quy định áp dụng:** Ràng buộc riêng, có thể nghiêm ngặt hơn. Không được cho phép ghi đè bằng một tùy chọn thông thường nếu việc đó vi phạm chính sách hoặc quy định áp dụng.

### 2.2. Hạn chót đăng ký không được thực thi

**Hiện trạng:** Kỳ đăng ký tháng 10/2026 có hạn `05/10/2026 23:59`, nhưng ở thời điểm kiểm tra ngày 26/10/2026 vẫn hiển thị “Đang mở đăng ký”; nhân viên vẫn sửa và gửi đăng ký. Tùy chọn “Tự động khóa khi hết hạn” được lưu nhưng chưa có logic thực thi.

**Yêu cầu chốt:**

- Khi tạo kỳ, Admin cấu hình ngày giờ bắt đầu, hạn chót và trạng thái tự động khóa.
- Khi bật **Tự động khóa khi hết hạn**, backend tự chuyển kỳ sang trạng thái đã đóng khi thời điểm hiện tại vượt hạn chót.
- Dùng múi giờ thống nhất `Asia/Ho_Chi_Minh` cho nghiệp vụ và cách hiển thị thời gian.
- Sau hạn chót, backend từ chối cả thao tác tạo mới, chỉnh sửa và gửi đăng ký của nhân viên. Chỉ khóa nút trên giao diện là chưa đủ.
- Dữ liệu đăng ký đã gửi phải được giữ nguyên sau khi khóa.
- Giao diện nhân viên hiển thị trạng thái và hạn chót rõ ràng, ví dụ: “Đã đóng đăng ký — hạn chót 05/10/2026 23:59”.
- Nếu tắt tự động khóa, kỳ chỉ đóng theo thao tác thủ công của người có quyền; giao diện phải thể hiện đây là kỳ được quản lý thủ công.
- Admin có thể mở lại kỳ bằng thao tác riêng có quyền, xác nhận và lý do. Lịch sử phải ghi ai mở lại, lúc nào và vì sao.
- Cần xử lý trường hợp nhân viên mở form trước hạn nhưng gửi sau hạn: backend kiểm tra lại hạn tại thời điểm gửi và từ chối nếu kỳ đã đóng.

### 2.3. Số liệu giữa Dashboard và màn Phân lịch không thống nhất ngữ cảnh

**Hiện trạng:** Dashboard ghi “Tổng ca trong kỳ 52, Ca thiếu nhân sự 0”, trong khi màn Phân lịch tự động ghi “33 ca, 23 ca thiếu người”. Những con số này có thể đến từ trạng thái lịch khác nhau nhưng nhãn chưa giải thích rõ, khiến người dùng nghĩ hệ thống tính sai.

**Yêu cầu chốt:**

- Chuẩn hóa định nghĩa, nguồn dữ liệu, điều kiện lọc và cách tính số ca dùng chung giữa các màn hình.
- Dashboard nếu chỉ đếm lịch đã công bố phải ghi rõ **“Ca đã công bố”** và **“Ca thiếu người đã công bố”**.
- Màn Phân lịch tự động phải ghi rõ đây là phương án nào và trạng thái của nó, ví dụ **“33 ca trong phương án nháp”** và **“23 ca thiếu người trong phương án nháp”**.
- Nếu hai màn hình đang xem cùng kỳ, cùng phạm vi chi nhánh, cùng bộ lọc và cùng trạng thái dữ liệu, số liệu tương ứng phải giống nhau.
- Nếu số khác nhau do trạng thái nháp/đã công bố, phải thể hiện rõ sự khác biệt bằng nhãn, bộ lọc hoặc chú giải. Không hiển thị hai con số như thể chúng cùng đo một đại lượng.
- Khi dữ liệu được lọc, các thẻ số liệu, bảng và biểu đồ phải dùng cùng bộ lọc.

### 2.4. Kiểm thử responsive phụ thuộc tỷ lệ scale màn hình

**Hiện trạng:** Smoke test responsive có thể chạy sai trên máy đặt Windows Display Scale khác 100%.

**Yêu cầu chốt:**

- Kiểm thử responsive theo **CSS viewport size**, không lấy độ phân giải vật lý của màn hình làm chiều rộng giao diện.
- Chuẩn hóa kích thước viewport cho smoke test; kiểm thử ít nhất các mốc 320, 360, 390, 768, 1024 và 1280 CSS px.
- Nếu dùng công cụ tự động như Playwright, cấu hình `viewport` rõ ràng; kiểm thử `deviceScaleFactor` riêng khi cần, không dùng nó thay cho kích thước viewport.
- Có bài test tối thiểu cho đăng nhập, điều hướng, bảng dữ liệu, form đăng ký, lập lịch và modal trên màn hình hẹp.
- Đảm bảo không có tràn ngang ngoài ý muốn, nút bị che, modal vượt khung hoặc thao tác chính không thể thực hiện.
- Chạy smoke test trên ít nhất một cấu hình scale khác 100% để xác nhận lỗi không quay lại.

---

## 3. Danh sách chức năng đề xuất bổ sung

### 3.1. Thông báo hạn đăng ký và lịch đã công bố

- Thông báo trong ứng dụng khi kỳ đăng ký sắp hết hạn; mốc nhắc có thể cấu hình, ví dụ trước 24 hoặc 48 giờ.
- Nhắc nhân viên chưa gửi đăng ký.
- Gửi thông báo khi lịch được công bố, lịch bị thay đổi hoặc nhân viên được phân vào ca mới.
- Mỗi thông báo có thời gian, nội dung, loại thông báo và trạng thái đã đọc/chưa đọc.
- Tránh gửi trùng thông báo cho cùng một sự kiện.
- V1.0 ưu tiên thông báo trong ứng dụng. Email có thể bật khi cấu hình được dịch vụ gửi thư và xử lý lỗi gửi.

### 3.2. Xin đổi ca và nhờ người khác làm thay

- Nhân viên gửi yêu cầu đổi ca hoặc tìm người làm thay từ ca được phép.
- Người nhận lời thay ca phải đáp ứng các điều kiện bắt buộc: không trùng lịch, đủ Level/kỹ năng/chứng chỉ, không vượt giới hạn giờ và đáp ứng thời gian nghỉ tối thiểu.
- Yêu cầu chỉ có hiệu lực sau khi người có quyền duyệt chấp thuận.
- Trong thời gian chờ duyệt, lịch chính thức không tự thay đổi.
- Khi duyệt, cập nhật lịch và gửi thông báo cho các bên liên quan.
- Lưu người yêu cầu, người thay, ca gốc, thời điểm, người duyệt, quyết định và lý do.
- Sau khi đổi ca được duyệt, hệ thống phải xác định rõ người chịu trách nhiệm cuối cùng cho ca.

### 3.3. Ca trống để nhân viên đăng ký nhận

Có thể triển khai cùng hoặc sau chức năng đổi ca:

- Admin công bố ca trống (Open Shift) với số lượng cần tuyển, điều kiện và thời hạn nhận ca.
- Nhân viên đủ điều kiện đăng ký nhận ca.
- Nếu số người đăng ký vượt nhu cầu, Admin hoặc quy tắc đã cấu hình chọn người phù hợp; không tự xác nhận vượt số lượng yêu cầu.
- Khi ca được nhận và duyệt, cập nhật lịch chính thức, ghi lịch sử và gửi thông báo.

---

## 4. Danh mục chức năng HRM đầy đủ theo module

Danh mục sau là **bản đồ chức năng dài hạn**, không có nghĩa tất cả phải được phát triển trong V1.0.

### Module 1 — Dashboard điều hành doanh nghiệp

- Tổng số nhân viên theo loại hình và trạng thái làm việc.
- Nhân viên đang làm hôm nay; người vắng mặt, đi muộn hoặc chưa check-in.
- Ca hôm nay, ca thiếu người và ca chưa xác nhận.
- Tổng giờ theo lịch so với giờ thực tế.
- Chi phí lương dự kiến theo tuần/tháng khi phân hệ lương được triển khai.
- Hợp đồng/chứng chỉ sắp hết hạn và khóa học bắt buộc chưa hoàn thành.
- Yêu cầu chờ duyệt: nghỉ phép, đổi ca, sửa công, tăng ca.
- Biểu đồ nhân sự, tỷ lệ nghỉ việc và chi phí lao động khi có đủ dữ liệu.
- Dashboard theo vai trò: Owner, HR, quản lý chi nhánh và người xếp lịch.

### Module 2 — Quản lý hồ sơ nhân viên

- Tạo, xem, sửa, khóa và lưu trữ hồ sơ.
- Mã nhân viên duy nhất; họ tên, liên hệ và người liên hệ khẩn cấp.
- Loại hình làm việc; ngày vào làm; trạng thái thử việc, chính thức, nghỉ việc.
- Bộ phận, chi nhánh, chức vụ, quản lý trực tiếp, Level, kỹ năng và chứng chỉ.
- Lịch sử đổi chức vụ, lương, bộ phận và hợp đồng.
- Tài liệu đính kèm; nhập Excel/CSV; xuất dữ liệu theo quyền.
- Tìm kiếm, lọc, phân trang và cập nhật hàng loạt.

### Module 3 — Cơ cấu tổ chức và phân công quản lý

- Quản lý công ty, chi nhánh, địa điểm, bộ phận và nhóm.
- Tạo chức vụ, vị trí và mô tả nhiệm vụ.
- Gán nhân viên vào một hoặc nhiều địa điểm.
- Xác định quản lý và phạm vi nhân viên được quản lý.
- Quản lý định biên theo bộ phận, vị trí hoặc địa điểm.
- Cấu hình Level, kỹ năng và chứng chỉ cần thiết cho vị trí.
- Lưu lịch sử điều chuyển nhân viên.

### Module 4 — Quản lý lịch làm việc

**A. Cấu hình lịch và ca**
- Tạo ca theo ngày, giờ bắt đầu/kết thúc, giờ nghỉ và địa điểm.
- Hỗ trợ ca cố định, ca lặp, ca qua đêm và ca đặc biệt.
- Cấu hình nhân sự tối thiểu/tối đa và số lượng theo Level, chức vụ hoặc kỹ năng.
- Quy định Level cao được thay Level thấp trong trường hợp nào; không cho thay thế ngược chiều.
- Cấu hình thời gian nghỉ tối thiểu giữa ca, giờ tối đa và ngày làm liên tiếp.
- Tạo mẫu lịch tuần/tháng để dùng lại.

**B. Đăng ký thời gian rảnh**
- Nhân viên đăng ký thời gian có thể làm, không thể làm và ngày nghỉ mong muốn.
- Cấu hình số giờ tối thiểu/tối đa mong muốn.
- Thiết lập hạn chót và nhắc người chưa đăng ký.
- Admin xem tổng hợp khả năng đáp ứng theo ngày/ca.
- Tự động khóa sau hạn hoặc cho phép cập nhật có kiểm soát.

**C. Tự động xếp lịch**
- Chọn người đáp ứng điều kiện bắt buộc.
- Ưu tiên sự phù hợp của thời gian rảnh, Level, kỹ năng, chứng chỉ và các quy tắc liên quan.
- Cân bằng giờ và số ca, có xét mục tiêu giờ từng người/nhóm.
- Áp dụng giới hạn tối đa theo kỳ; tránh trùng ca, thiếu thời gian nghỉ và vượt các giới hạn đã cấu hình.
- Giảm chi phí nhân sự dự kiến nếu có dữ liệu và chính sách chi phí.
- Báo ca thiếu người, người không đủ điều kiện và nguyên nhân.
- Giải thích lý do chọn/không chọn nhân viên ở mức có thể kiểm tra.
- Cho chạy lại phương án mà không ghi đè lịch đã chốt nếu chưa được xác nhận.

**D. Chỉnh sửa và công bố lịch**
- Xem lịch ngày/tuần/tháng; lọc theo chi nhánh, nhóm, nhân viên và Level.
- Kéo thả hoặc chỉ định nhân viên thủ công, kèm cảnh báo ngay.
- Lưu nháp, so sánh phương án, duyệt và công bố lịch.
- Quản lý phiên bản và lịch sử chỉnh sửa.
- Thông báo khi lịch mới được công bố hoặc thay đổi.
- Theo dõi nhân viên đã xem/xác nhận lịch.

**E. Nghiệp vụ phát sinh**
- Ca trống (Open Shift), xin đổi ca và nhờ người khác làm thay.
- Hủy ca, bổ sung ca khẩn cấp và xử lý nhân viên vắng đột xuất.
- Lưu người nhận ca và người chịu trách nhiệm cuối cùng.
- Dự báo nguy cơ thiếu người dựa trên nhu cầu và dữ liệu sẵn có.

### Module 5 — Chấm công và đối soát giờ làm

- Check-in/check-out trên web hoặc ứng dụng.
- Ghi giờ vào/ra, giờ nghỉ, địa điểm và ca liên quan.
- Có thể mở rộng QR, kiosk hoặc xác minh vị trí nếu doanh nghiệp cần.
- Phát hiện quên chấm công, đi muộn, về sớm hoặc vắng không báo.
- Đối chiếu lịch dự kiến với giờ thực tế.
- Nhân viên gửi yêu cầu bổ sung/sửa công; quản lý xác nhận và người phụ trách xử lý.
- Theo dõi tăng ca, ca đêm và thời gian đặc biệt theo chính sách.
- Khóa kỳ công và lưu lịch sử chỉnh sửa trước/sau.

### Module 6 — Nghỉ phép và vắng mặt

- Cấu hình loại nghỉ và quy trình duyệt.
- Theo dõi số phép được hưởng, đã dùng và còn lại theo chính sách.
- Nhân viên gửi yêu cầu nghỉ, lý do và tài liệu nếu cần.
- Quản lý duyệt/từ chối và ghi lý do.
- Kiểm tra tác động lên độ phủ ca; cập nhật lịch sau khi nghỉ được duyệt.
- Ghi nhận vắng mặt đột xuất và báo cáo tỷ lệ nghỉ.
- Quy tắc cần căn cứ chính sách doanh nghiệp, điều kiện hợp đồng và quy định áp dụng; không mặc định Part-time không có quyền lợi nghỉ.

### Module 7 — Tiền lương và bảng lương

- Chính sách lương theo giờ/ngày/tháng; đơn giá theo vị trí hoặc Level và ngày hiệu lực.
- Quản lý phụ cấp, thưởng, khoản khấu trừ và quy tắc tính.
- Tạo kỳ lương, tính thử, kiểm tra chênh lệch, duyệt, khóa và xuất dữ liệu thanh toán.
- Nhân viên xem phiếu lương và lịch sử kỳ lương của chính mình.
- Dùng giờ công đã duyệt, không lấy trực tiếp giờ trong lịch dự kiến làm căn cứ cuối cùng.
- Không sửa âm thầm kỳ lương đã khóa; điều chỉnh phải qua quy trình riêng và có audit log.
- Tính lương thực tế phải được đối chiếu với hợp đồng, chính sách và quy định hiện hành trước khi triển khai.

### Module 8 — Learning & Development (LMS)

- Quản lý khóa học, tài liệu, video, bài học và bài kiểm tra.
- Gán khóa học bắt buộc/tự chọn theo nhân viên, vị trí, Level hoặc bộ phận.
- Theo dõi lộ trình học và trạng thái hoàn thành.
- Quản lý chứng chỉ, ngày cấp/hết hạn và yêu cầu học lại.
- Tổ chức buổi đào tạo, điểm danh và đánh giá sau đào tạo.
- Báo cáo tiến độ, kết quả, khóa học quá hạn và chi phí.
- Liên kết kết quả học tập với kỹ năng/điều kiện đủ tiêu chuẩn làm ca khi có bước xác nhận phù hợp.

### Module 9 — Kỹ năng và năng lực

- Danh mục kỹ năng, mức thành thạo và yêu cầu theo vị trí/ca/địa điểm.
- Đánh giá năng lực bởi quản lý.
- Theo dõi chứng chỉ và ngày hết hạn.
- Ma trận kỹ năng toàn đội ngũ; tìm người đủ điều kiện cho ca.
- Phát hiện khoảng trống kỹ năng và gợi ý đào tạo.

### Module 10 — Đánh giá hiệu suất

- Cấu hình KPI theo công việc/bộ phận.
- Đánh giá định kỳ, theo dõi mục tiêu và kết quả.
- Phản hồi giữa nhân viên và quản lý.
- Theo dõi chất lượng, kỹ năng, độ tin cậy và kế hoạch cải thiện.
- Lưu lịch sử khen thưởng, thành tích và đề xuất đào tạo/thăng cấp.
- Không đánh giá thấp nhân viên Part-time chỉ vì tổng giờ làm thấp hơn Full-time.

### Module 11 — Tuyển dụng và tiếp nhận nhân viên

- Tạo vị trí tuyển, theo dõi ứng viên, CV, phỏng vấn và kết quả.
- Quản lý đề xuất tuyển dụng, offer và ngày nhận việc.
- Chuyển ứng viên thành hồ sơ nhân viên.
- Checklist trước ngày đầu tiên, tài liệu nội quy, tài khoản và trang thiết bị.
- Gán khóa học hội nhập, người hướng dẫn và theo dõi thử việc.

### Module 12 — Hợp đồng và hồ sơ lao động

- Quản lý hợp đồng, phụ lục, loại hợp đồng, thời hạn và ngày hiệu lực.
- Theo dõi thay đổi lương, chức vụ, thời gian làm và gia hạn.
- Nhắc hợp đồng sắp hết hạn.
- Quản lý tài liệu ký kết và trạng thái hoàn tất hồ sơ.
- Giới hạn quyền xem tài liệu nhạy cảm; lưu lịch sử điều chỉnh/chấm dứt hợp đồng.

### Module 13 — Phúc lợi, thưởng và ghi nhận

- Cấu hình chính sách thưởng, phụ cấp và chương trình ghi nhận.
- Lưu thành tích và lý do thưởng.
- Theo dõi quyền lợi theo điều kiện áp dụng.
- Quản lý ngân sách phúc lợi và báo cáo sử dụng.

### Module 14 — Giao tiếp và thông báo nội bộ

- Gửi thông báo toàn công ty hoặc theo chi nhánh/nhóm.
- Thông báo lịch mới, đổi ca, yêu cầu duyệt và hạn đăng ký.
- Nhắc chấm công, đăng ký thời gian rảnh và hoàn thành khóa học.
- Thông báo hợp đồng/chứng chỉ sắp hết hạn đến đúng người có trách nhiệm.
- Đánh dấu đã đọc, lưu lịch sử và quản lý mẫu thông báo.
- Hỗ trợ các kênh gửi được cấu hình, ví dụ in-app trước, email sau.

### Module 15 — Báo cáo và phân tích

- **Nhân sự:** số nhân viên, tỷ lệ nghỉ việc và thời gian tuyển dụng khi có dữ liệu.
- **Lịch làm:** ca thiếu người, tỷ lệ lấp đầy, phân bổ giờ và chênh lệch mục tiêu.
- **Chấm công:** đi muộn, vắng mặt, giờ thực tế so với lịch.
- **Chi phí:** chi phí lao động theo bộ phận/chi nhánh khi phân hệ lương có dữ liệu.
- **Đào tạo:** tỷ lệ hoàn thành, điểm thi và chứng chỉ hết hạn.
- **Hiệu suất:** KPI và tiến độ mục tiêu.
- **Quản trị:** yêu cầu chờ duyệt, dữ liệu lỗi và lịch sử thay đổi.
- Bộ lọc thời gian, loại nhân viên, chi nhánh, bộ phận, Level và trạng thái; xuất Excel/CSV theo quyền.

### Module 16 — Phân quyền, cấu hình và kiểm soát hệ thống

- Quyền Owner/Super Admin, HR Manager, Scheduler, Attendance Manager, Payroll Officer, Learning Manager, Department Manager và Employee.
- V1.0 có thể bắt đầu với bộ quyền tối thiểu cần thiết, nhưng phải thiết kế để mở rộng.
- Cấu hình loại nhân viên, Level, chức vụ, bộ phận, ca, kỳ đăng ký, kỳ công, quy trình duyệt và toàn bộ tham số nghiệp vụ được phép điều chỉnh.
- Có màn hình thiết lập quy tắc xếp lịch cho mục tiêu/giới hạn giờ, điều kiện ca, thời gian nghỉ, tiêu chí ưu tiên, mốc nhắc và cách khóa kỳ; hỗ trợ phạm vi áp dụng toàn công ty, chi nhánh, loại nhân viên, nhóm hoặc cá nhân.
- Có xem trước tác động và xác nhận trước khi áp dụng thay đổi quy tắc; lưu lịch sử giá trị trước/sau, người sửa, thời điểm và lý do.
- Quản lý tài khoản, trạng thái khóa và chính sách bảo mật.
- Audit log cho thao tác quan trọng.
- Sao lưu, khôi phục và theo dõi lỗi tích hợp theo khả năng của hạ tầng.
- Quy trình nghỉ việc (Offboarding): ngày nghỉ chính thức, bàn giao, khóa tài khoản, chốt công/lương và lưu hồ sơ; có thể mở rộng ở giai đoạn sau.

---

## 5. Phạm vi chốt cho V1.0

**Mục tiêu V1.0:** Có thể vận hành lịch làm việc và dữ liệu nhân viên trên backend thật, thay vì chỉ chạy như một bản demo giao diện. Không triển khai đồng thời tất cả 16 module ở mức đầy đủ.

### 5.1. Nền tảng, tài khoản và phân quyền

- Đăng nhập/đăng xuất thật qua backend.
- Tài khoản Admin và nhân viên; trạng thái hoạt động/khóa.
- Kiểm tra quyền tại API/backend, không chỉ tại frontend.
- Phiên đăng nhập và xử lý hết hạn phiên rõ ràng.
- Audit log tối thiểu cho thay đổi lịch, công bố lịch, thay đổi kỳ, duyệt yêu cầu và thao tác quản trị quan trọng.

### 5.2. Hồ sơ nhân viên cơ bản

- Mã nhân viên duy nhất, tên, thông tin liên hệ, trạng thái làm việc.
- Full-time/Part-time, Level, chức vụ, bộ phận/địa điểm, ngày vào làm và quản lý trực tiếp.
- Mục tiêu giờ theo kỳ và giới hạn giờ tối đa do Admin cấu hình (theo số giờ hoặc tỷ lệ mục tiêu); thông số bắt buộc chưa thiết lập phải được cảnh báo rõ.
- Tìm kiếm, lọc và cập nhật hồ sơ theo quyền.
- Hợp đồng chi tiết, giấy tờ lao động và lịch sử lương đầy đủ để giai đoạn sau.

### 5.3. Lập lịch và đăng ký thời gian rảnh — chức năng trọng tâm

- Tạo và quản lý kỳ đăng ký; có hạn chót và trạng thái rõ ràng.
- Đăng ký thời gian rảnh/không thể làm của nhân viên.
- Tạo ca, yêu cầu số người, Level và điều kiện đủ tiêu chuẩn.
- Phân lịch tự động và thủ công.
- Bắt buộc kiểm tra trùng ca, điều kiện đủ tiêu chuẩn, nghỉ giữa ca và giới hạn giờ.
- Cân bằng giờ theo mục tiêu và các tiêu chí do Admin cấu hình; báo thiếu người thay vì âm thầm vượt giới hạn.
- Nháp, duyệt, công bố và lưu phiên bản lịch.
- Xem lịch cá nhân; thông báo khi công bố hoặc thay đổi.
- Xin đổi ca/nhờ người thay, có bước duyệt và lịch sử.
- Có thể bổ sung Open Shift trong cùng V1.0 nếu không ảnh hưởng tiến độ các ràng buộc cốt lõi.

### 5.4. Dữ liệu nghiệp vụ dùng chung trên backend

Các dữ liệu sau phải có API và được lưu ở database làm nguồn chuẩn: hồ sơ nhân viên, kỳ đăng ký, thời gian rảnh, ca, quy tắc xếp lịch, phân công, trạng thái công bố, phiên bản lịch, yêu cầu đổi ca và lịch sử liên quan.

Frontend không được tự coi dữ liệu lưu trong `localStorage` là dữ liệu chuẩn của hệ thống. Có thể dùng `localStorage` cho thiết lập giao diện không quan trọng, nhưng dữ liệu nghiệp vụ phải được tải/lưu qua API.

Theo mô tả hiện trạng, backend đã có đăng nhập JWT nhưng frontend chưa gọi API đăng nhập; dữ liệu demo và trạng thái phiên còn ở `localStorage`; nghiệp vụ xếp lịch chưa đồng bộ đầy đủ lên SQL Server. Đây là phần cần xử lý trước khi mở rộng số lượng phân hệ.

### 5.5. Chấm công và nghỉ phép cơ bản

- Ghi nhận giờ thực tế vào/ra.
- Nhân viên gửi yêu cầu sửa công; người có quyền duyệt hoặc từ chối.
- Tạo yêu cầu nghỉ cơ bản, có trạng thái và người duyệt.
- Kiểm tra ảnh hưởng của nghỉ được duyệt lên lịch và cảnh báo ca thiếu người.
- Chưa cần triển khai đầy đủ công thức lương trong V1.0.

### 5.6. Thông báo và báo cáo vận hành

- Thông báo trong ứng dụng cho hạn đăng ký, lịch được công bố/thay đổi và yêu cầu cần xử lý.
- Dashboard thống kê nhất quán: ca đã công bố, ca trong phương án đang chọn, ca thiếu người theo từng trạng thái, tổng giờ theo lịch và chênh lệch so với mục tiêu.
- Báo cáo phân bổ giờ, nhân viên vượt gần giới hạn, ca còn thiếu người và yêu cầu chờ duyệt.
- Các thẻ số liệu phải mô tả rõ trạng thái dữ liệu đang được đếm.

---

## 6. Định hướng V2.0 và các giai đoạn sau

Ưu tiên đưa các nghiệp vụ V1.0 vào backend và làm ổn định trước. Sau đó mở rộng theo thứ tự:

1. **Payroll:** chính sách lương, tính thử, đối soát, phê duyệt bảng lương và phiếu lương.
2. **Learning & Skills:** khóa học, bài kiểm tra, chứng chỉ, ma trận kỹ năng và liên kết điều kiện năng lực với phân ca.
3. **Hồ sơ HR nâng cao:** hợp đồng, giấy tờ lao động, onboarding/offboarding.
4. **Performance & Benefits:** KPI, phản hồi, khen thưởng, phụ cấp và phúc lợi.
5. **Recruitment & Analytics nâng cao:** tuyển dụng, dự báo nhu cầu nhân sự, phân tích chi phí và xu hướng nhân sự.
6. **Mở rộng thông báo:** email và các kênh khác sau khi hoàn thiện cấu hình dịch vụ, theo dõi gửi thành công/thất bại và chống gửi trùng.

---

## 7. Quy tắc nghiệp vụ phải được thống nhất

### 7.1. Quy tắc xếp lịch

**Điều kiện bắt buộc (hard constraints):**
- Nhân viên không được phân vào các ca bị trùng giờ.
- Nhân viên phải đáp ứng Level, kỹ năng hoặc chứng chỉ bắt buộc của ca.
- Phải đáp ứng thời gian nghỉ tối thiểu giữa hai ca và giới hạn giờ được cấu hình.
- Không vượt giới hạn giờ tối đa tự động phân.
- Không phân vào thời gian nhân viên đã khai báo không thể làm, trừ khi có quy trình ngoại lệ được phép và ghi nhận.

**Tiêu chí ưu tiên (soft preferences):**
- Đưa tổng giờ về gần mục tiêu.
- Cân bằng số ca và giờ giữa nhân viên phù hợp.
- Ưu tiên thời gian rảnh mong muốn.
- Hạn chế tăng ca/chi phí khi có chính sách và dữ liệu cần thiết.

Nếu không thể đáp ứng toàn bộ nhu cầu bằng những người thỏa mãn điều kiện bắt buộc, hệ thống phải giữ ca thiếu người và đưa ra lý do. Không hy sinh điều kiện bắt buộc chỉ để tăng tỷ lệ lấp đầy.

### 7.2. Quy tắc đăng ký kỳ

- Trạng thái tối thiểu: Nháp, Đang mở đăng ký, Đã đóng đăng ký, Đang xếp lịch, Đã công bố và Đã lưu trữ.
- Trạng thái được thay đổi bằng nghiệp vụ hợp lệ; không chỉ dựa vào nhãn frontend.
- Hạn chót được kiểm tra ở backend khi đọc/ghi các thao tác đăng ký liên quan.
- Mở lại kỳ, nếu được phép, phải có người thực hiện, lý do và thời điểm trong lịch sử.

### 7.3. Quy tắc công bố lịch và đổi ca

- Bản nháp không được hiển thị như lịch chính thức.
- Lịch đã công bố có phiên bản và lịch sử chỉnh sửa.
- Chỉ yêu cầu đổi ca được duyệt mới cập nhật phân công chính thức.
- Khi đổi lịch, thông báo cho các nhân viên bị ảnh hưởng.
- Nếu có sửa lịch thủ công vượt chính sách, cần cảnh báo/xác nhận và ghi audit log.

### 7.4. Quy tắc cấu hình tham số

- Admin/Owner được tự cấu hình các tham số nghiệp vụ được phép thay đổi ở màn hình Cài đặt quy tắc.
- Tham số có thể áp dụng toàn công ty hoặc ghi đè theo chi nhánh, loại hình làm việc, nhóm nhân viên hay từng nhân viên; hệ thống phải hiển thị quy tắc nào đang có hiệu lực nếu nhiều phạm vi cùng tồn tại.
- Phải xác định thứ tự ưu tiên khi quy tắc xung đột, ví dụ: ràng buộc pháp luật/chính sách an toàn bắt buộc → điều kiện hợp đồng/chứng chỉ → quy tắc doanh nghiệp → tiêu chí ưu tiên của thuật toán.
- Với giới hạn giờ, Admin có thể chọn giới hạn cố định bằng số giờ hoặc giới hạn theo phần trăm của mục tiêu. Nếu chọn phần trăm, hệ thống tự tính `giờ tối đa = giờ mục tiêu × tỷ lệ phần trăm / 100`.
- Hệ thống tự tính phần trăm báo cáo theo công thức `tỷ lệ giờ đã xếp = tổng giờ đã xếp / tổng giờ mục tiêu × 100%`, khi tổng giờ mục tiêu lớn hơn 0. Nếu mẫu số bằng 0 hoặc thiếu dữ liệu, hiển thị “Chưa đủ dữ liệu” thay vì tự hiển thị 0% hoặc một tỷ lệ gây hiểu nhầm.
- Khi thay đổi tham số, hiển thị phạm vi ảnh hưởng và yêu cầu xác nhận. Quy tắc mới không tự sửa các lịch đã công bố hoặc kỳ đã khóa, trừ khi người có quyền thực hiện quy trình cập nhật riêng.
- Các tham số bắt buộc chưa cấu hình phải được liệt kê thành checklist hoàn tất thiết lập; không chạy tự động với giá trị nghiệp vụ tự phát.
- Có thể cung cấp giá trị tham khảo theo mẫu, nhưng phải cho Admin xem, chỉnh sửa và xác nhận trước khi kích hoạt.
- Lưu lịch sử thay đổi cấu hình, bao gồm người sửa, thời gian, giá trị cũ/mới, phạm vi áp dụng và lý do.

### 7.5. Quy tắc dữ liệu và báo cáo

- Các màn hình sử dụng chung định nghĩa trạng thái, bộ lọc và cách tính.
- “Ca đã công bố” khác “ca trong phương án nháp”; không cộng/gộp hai nhóm mà không ghi nhãn.
- Tổng giờ dự kiến từ lịch khác tổng giờ làm thực tế từ chấm công.
- Bảng lương trong tương lai phải lấy dữ liệu từ giờ công đã duyệt.
- Không xóa cứng những bản ghi cần lưu lịch sử; ưu tiên trạng thái, phiên bản và quy trình điều chỉnh.

---

## 8. Tiêu chí nghiệm thu bắt buộc cho các vấn đề đã ghi nhận

| ID | Hạng mục | Tiêu chí nghiệm thu |
|---|---|---|
| AC-01 | Giới hạn giờ tự động | Khi Admin cấu hình mục tiêu 60 giờ và giới hạn 125%, hệ thống tự tính trần 75 giờ; thuật toán không tự phân vượt 75 giờ. Giá trị 125% chỉ là dữ liệu kiểm thử, không phải mặc định của sản phẩm. |
| AC-02 | Thiếu nhân sự | Khi tất cả nhân viên đủ điều kiện đều chạm giới hạn, ca được đánh dấu thiếu người và có lý do; không tự vượt trần. |
| AC-03 | Tổng giờ kỳ | Tổng giờ đã xếp, mục tiêu, chênh lệch và tỷ lệ được tính thống nhất; ví dụ 808/740 thể hiện rõ vượt 68 giờ, tương đương khoảng 109% nếu làm tròn theo số nguyên gần nhất. |
| AC-04 | Hạn chót | Sau hạn chót, API từ chối tạo/sửa/gửi đăng ký nếu tính năng tự động khóa được bật. |
| AC-05 | Form đã mở trước hạn | Nếu nhân viên mở form trước hạn nhưng gửi sau hạn, backend vẫn từ chối và hiển thị thông báo phù hợp. |
| AC-06 | Mở lại kỳ | Chỉ người có quyền mới mở lại được; phải có xác nhận, lý do và lịch sử thao tác. |
| AC-07 | Số liệu các màn hình | Cùng kỳ, cùng bộ lọc và cùng trạng thái phải cho cùng số liệu. Nếu khác trạng thái, nhãn phải nêu rõ nháp/đã công bố. |
| AC-08 | Đổi ca | Lịch chính thức không đổi trước khi yêu cầu được duyệt; sau duyệt, lịch, lịch sử và thông báo được cập nhật. |
| AC-09 | Responsive | Smoke test chạy trên viewport CSS đã định nghĩa và vượt qua ở các mốc 320, 360, 390, 768, 1024, 1280 px. |
| AC-10 | Backend là nguồn chuẩn | Làm mới trang hoặc đăng nhập từ thiết bị/trình duyệt khác vẫn đọc được dữ liệu nghiệp vụ đã lưu trên backend. |
| AC-11 | Phân quyền | API từ chối thao tác không được phép kể cả khi người dùng gọi trực tiếp API hoặc sửa giao diện phía client. |
| AC-12 | Audit log | Các thao tác quan trọng gồm công bố lịch, chỉnh phân công, đổi ca và mở lại kỳ có người thực hiện, thời gian và thông tin thay đổi. |
| AC-13 | Admin tự cấu hình | Admin có thể tạo/sửa quy tắc trong phạm vi được cấp quyền; hệ thống kiểm tra dữ liệu, hiển thị phạm vi áp dụng, xem trước tác động, yêu cầu xác nhận và lưu lịch sử. Quy tắc bắt buộc chưa cấu hình được báo rõ, không tự điền một giá trị nghiệp vụ bất kỳ. |
| AC-14 | Tự tính tỷ lệ | Khi Admin nhập giờ mục tiêu và chọn tỷ lệ giới hạn, hệ thống tự tính số giờ tối đa; khi có dữ liệu phân lịch, hệ thống tự tính tỷ lệ báo cáo. Admin không phải tự thực hiện phép tính bằng tay. |

**Lưu ý AC-03:** `808/740 = 109.19%`, nên hiển thị 109% nếu làm tròn đến số nguyên gần nhất; nếu giao diện hiện 108% thì cần kiểm tra công thức, dữ liệu đầu vào hoặc cách làm tròn. Đây là tỷ lệ hệ thống tự tính từ dữ liệu thực tế, không phải thông số Admin phải tự nhập. Admin có thể cấu hình cách làm tròn/hiển thị, nhưng không được thay đổi công thức tính tỷ lệ.

---

## 9. Báo cáo và màn hình cần có trong V1.0

1. **Dashboard Admin:** nhân viên đang hoạt động; kỳ đăng ký hiện tại và hạn chót; ca đã công bố; ca thiếu người đã công bố; phương án nháp hiện tại nếu có; tổng giờ theo lịch so với mục tiêu; yêu cầu đang chờ duyệt.
2. **Quản lý kỳ đăng ký:** ngày mở, hạn chót, trạng thái, tự động khóa, số nhân viên đã/chưa đăng ký và thao tác mở/đóng kỳ theo quyền.
3. **Phân lịch tự động:** thông tin phương án đang xem, tổng số ca trong phương án, số ca đủ người/thiếu người, tổng giờ theo nhân viên, giới hạn giờ và lý do ca chưa được lấp đầy.
4. **Lịch làm việc:** lịch nháp/đã công bố phân biệt rõ; lọc theo khoảng thời gian, nhân viên, chi nhánh và trạng thái.
5. **Yêu cầu phát sinh:** đổi ca, người thay ca, nghỉ phép và sửa công với trạng thái chờ duyệt/đã duyệt/từ chối.
6. **Thông báo:** danh sách, thời điểm, trạng thái đã đọc và liên kết đến nghiệp vụ liên quan.
7. **Cài đặt quy tắc:** cấu hình mục tiêu/giới hạn giờ, điều kiện ca, thời gian nghỉ, tiêu chí ưu tiên, hạn đăng ký, tự động khóa, mốc nhắc, quy trình duyệt và cách hiển thị báo cáo; có giải thích từng trường, kiểm tra hợp lệ, xem trước tác động và lịch sử thay đổi.

Mỗi màn hình phải hiển thị trạng thái dữ liệu và thời điểm cập nhật thích hợp; không dùng cùng một nhãn cho các chỉ số lấy từ phạm vi dữ liệu khác nhau.

---

## 10. Trình tự triển khai đề xuất

### Giai đoạn 1 — Hoàn thiện nền tảng dữ liệu

- Kết nối frontend với API đăng nhập thật.
- Chốt schema database và API cho nhân viên, kỳ, thời gian rảnh, ca, quy tắc, phân công và lịch sử.
- Chuyển dữ liệu nghiệp vụ khỏi `localStorage` sang backend/database.
- Làm phân quyền phía backend và audit log tối thiểu.

### Giai đoạn 2 — Khắc phục các lỗi lập lịch ưu tiên cao

- Thực thi hạn chót và tự động khóa ở backend.
- Bổ sung giới hạn giờ tối đa cho thuật toán.
- Chuẩn hóa công thức/nhãn thống kê Dashboard và màn Phân lịch.
- Cải thiện lý do ca thiếu người và cảnh báo vi phạm ràng buộc.

### Giai đoạn 3 — Hoàn thiện quy trình vận hành lịch

- Duyệt/công bố/phiên bản lịch.
- Thông báo trong ứng dụng.
- Xin đổi ca và duyệt người thay; Open Shift nếu đủ phạm vi.
- Kiểm thử luồng nhân viên và Admin từ đầu đến cuối.

### Giai đoạn 4 — Chấm công, nghỉ phép và báo cáo

- Ghi giờ thực tế, yêu cầu sửa công, duyệt ngoại lệ.
- Đơn nghỉ cơ bản và cảnh báo tác động lên độ phủ ca.
- Báo cáo lịch dự kiến so với công thực tế.

### Giai đoạn 5 — Kiểm thử và ổn định

- Smoke test responsive theo CSS viewport.
- Kiểm thử vai trò Admin/nhân viên, hạn chót, giới hạn giờ và đổi ca.
- Kiểm thử Admin thay đổi tham số, phạm vi áp dụng, quy tắc xung đột, giá trị chưa thiết lập và công thức hệ thống tự tính phần trăm.
- Kiểm thử dữ liệu bền vững sau refresh, đăng xuất/đăng nhập và dùng trình duyệt khác.
- Kiểm thử lỗi API, trạng thái tải, dữ liệu trống và quyền bị từ chối.

### Giai đoạn 6 — Mở rộng V2.0

Triển khai Payroll, LMS, Skills, hợp đồng nâng cao, tuyển dụng, KPI, phúc lợi và HR Analytics theo ưu tiên thực tế.

---

## 11. Kết luận chốt phạm vi

Dự án được định vị là **Workforce Management–First HRM**. V1.0 ưu tiên hoàn thiện quản lý nhân viên cơ bản, backend/database dùng chung, lập lịch có ràng buộc thật, hạn đăng ký có hiệu lực, duyệt/công bố lịch, thông báo, đổi ca, chấm công/nghỉ phép cơ bản, báo cáo vận hành và màn hình để Admin tự cấu hình các tham số nghiệp vụ. Hệ thống tự tính các tỷ lệ và kết quả suy ra từ thông số đầu vào, không bắt Admin tính bằng tay.

Bốn việc cần ưu tiên xử lý ngay trong bộ lập lịch là:

1. Cho Admin tự cấu hình mục tiêu/giới hạn giờ; hệ thống tự tính toán, kiểm tra và không âm thầm vượt trần.
2. Thực thi hạn đăng ký ở backend, không chỉ lưu công tắc hoặc khóa giao diện.
3. Thống nhất cách tính số ca và làm rõ nháp/đã công bố trên mọi màn hình.
4. Sửa và chuẩn hóa smoke test responsive theo CSS viewport, độc lập với Windows Display Scale.

Tiếp theo, bổ sung thông báo hạn đăng ký/lịch công bố và yêu cầu đổi ca có duyệt. Chỉ sau khi các nghiệp vụ cốt lõi chạy ổn định với dữ liệu backend mới mở rộng sang Payroll, LMS và những phân hệ HRM nâng cao.

Đây là phạm vi đề xuất chốt cho V1.0; các module còn lại là roadmap V2.0 trở đi, không phải yêu cầu bắt buộc phải hoàn tất trong đợt hiện tại.
