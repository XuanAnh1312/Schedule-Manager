/* Small bridge only; does not change the existing demo login or localStorage behavior. */
const AttendanceEntry = {
  render() {
    return `<div class="page-header"><div class="page-header-left">
      <h1 class="page-title">Chấm công thực tế & báo cáo SQL Server</h1>
      <p class="page-subtitle">Phân hệ riêng sử dụng API JWT và SQL Server, không dùng localStorage demo.</p>
    </div></div>
    <div class="card"><div class="card-body" style="padding:24px">
      <p class="mb-4">Mở cổng chấm công để check-in/out, gửi yêu cầu bổ sung và xem thống kê. Quản trị viên SQL có thể duyệt công, khóa kỳ và xuất CSV/Excel.</p>
      <a class="btn btn-primary" href="attendance.html">Mở chấm công & báo cáo</a>
      <p class="text-muted mt-3">Cần đăng nhập bằng tài khoản Backend (tài khoản demo SPA không được dùng để xác thực nghiệp vụ SQL).</p>
    </div></div>`;
  }
};
