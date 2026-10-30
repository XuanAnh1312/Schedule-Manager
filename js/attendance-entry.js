/* Small bridge only; does not change the existing demo login or localStorage behavior. */
const AttendanceEntry = {
  render() {
    return `<div class="page-header"><div class="page-header-left">
      <h1 class="page-title">Chấm công & báo cáo</h1>
      <p class="page-subtitle">Theo dõi thời gian làm việc, đề nghị điều chỉnh công và tra cứu báo cáo theo kỳ.</p>
    </div></div>
    <div class="card"><div class="card-body" style="padding:24px">
      <p class="mb-4">Ghi nhận giờ vào, giờ ra của ca làm việc; xem lịch sử chấm công và gửi đề nghị điều chỉnh khi cần. Người quản lý có thể xét duyệt, theo dõi tình hình nhân sự và xuất báo cáo.</p>
      <a class="btn btn-primary" href="attendance.html">Truy cập chấm công & báo cáo</a>
      <p class="text-muted mt-3">Sử dụng tài khoản chấm công được cấp để truy cập dữ liệu làm việc của bạn.</p>
    </div></div>`;
  }
};
