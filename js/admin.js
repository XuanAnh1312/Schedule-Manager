/**
 * admin.js — Toàn bộ màn hình Admin (9 màn hình)
 * Phần 1: Dashboard + Quản lý nhân sự
 */

const Admin = (() => {

/* ═══════════════════════════════════════════════════════════
   5.1 TỔNG QUAN (Dashboard)
═══════════════════════════════════════════════════════════ */
function renderDashboard() {
  const periods   = State.get.periods();
  const activePeriod = periods.find(p => p.status === 'open') || periods[0];
  const stats = activePeriod ? State.get.dashboardStats(activePeriod.id) : null;
  const allUsers = State.get.activeUsers();
  const shifts   = State.get.shifts();
  const asgns    = State.get.assignments();
  const rules    = State.get.rules();

  // Cảnh báo
  const warnings = _buildWarnings(activePeriod, stats);

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Tổng quan</h1>
    <p class="page-subtitle">Kỳ hiện tại: ${activePeriod ? escHtml(activePeriod.name) : 'Chưa có kỳ nào'}</p>
  </div>
  <div class="page-actions">
    <select class="form-input" id="dash-period-select" aria-label="Chọn kỳ hiển thị trên tổng quan"
            onchange="Admin.switchDashPeriod(this.value)" style="width:220px">
      ${periods.map(p => `<option value="${p.id}" ${activePeriod && p.id === activePeriod.id ? 'selected' : ''}>${escHtml(p.name)}</option>`).join('')}
    </select>
    <button class="btn btn-primary" onclick="App.navigate('auto-schedule')">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>
      Chạy phân lịch
    </button>
  </div>
</div>

${warnings.length > 0 ? `
<div class="alert alert-warning mb-4">
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
  <div class="alert-content">
    <div class="alert-title">Cần chú ý (${warnings.length})</div>
    <ul style="margin:4px 0 0 16px;font-size:0.857rem">
      ${warnings.map(w => `<li>${escHtml(w)}</li>`).join('')}
    </ul>
  </div>
</div>` : ''}

${stats ? `
<div class="stat-grid mb-4">
  ${_statCard('Nhân viên hoạt động', stats.totalActiveUsers, '#dbeafe', '#1e40af', iconUsers())}
  ${_statCard('Tổng ca trong kỳ', stats.totalShifts, '#f0fdf4', '#16a34a', iconCalendar())}
  ${_statCard('Ca đã đủ người', stats.fullShifts, '#f0fdf4', '#16a34a', iconCheck())}
  ${_statCard('Ca thiếu nhân sự', stats.understaffedShifts, '#fef2f2', '#dc2626', iconAlert())}
  ${_statCard('NV chưa đăng ký', stats.unregisteredCount, '#fffbeb', '#d97706', iconUser())}
  ${_statCard('Ca đã công bố', stats.publishedShifts, '#ecfeff', '#0891b2', iconPublish())}
</div>

<div style="display:grid;grid-template-columns:1fr 1fr;gap:16px" class="mb-4">
  <div class="card">
    <div class="card-header"><span class="card-title">Tiến độ phân lịch</span></div>
    <div class="card-body">
      <div style="display:flex;justify-content:space-between;margin-bottom:6px">
        <span class="text-sm text-secondary">Giờ đã phân / Giờ mục tiêu</span>
        <span class="text-sm font-semibold">${stats.totalAssignedHours}h / ${stats.totalTargetHours}h</span>
      </div>
      <div class="progress-bar-wrap">
        <div class="progress-bar ${stats.progressPercent >= 80 ? 'success' : stats.progressPercent >= 50 ? '' : 'warning'}"
             style="width:${Math.min(stats.progressPercent,100)}%"></div>
      </div>
      <div class="text-sm text-muted mt-1">${stats.progressPercent}% hoàn thành</div>
    </div>
  </div>
  <div class="card">
    <div class="card-header"><span class="card-title">Trạng thái kỳ đăng ký</span></div>
    <div class="card-body">
      <div class="detail-list">
        <div class="detail-item"><span class="detail-label">Kỳ hiện tại</span><span class="detail-value">${escHtml(activePeriod.name)}</span></div>
        <div class="detail-item"><span class="detail-label">Trạng thái</span><span class="detail-value">${renderBadge(activePeriod.status)}</span></div>
        <div class="detail-item"><span class="detail-label">Ngày áp dụng</span><span class="detail-value">${formatDate(activePeriod.startDate)} – ${formatDate(activePeriod.endDate)}</span></div>
        <div class="detail-item"><span class="detail-label">Hạn đăng ký</span><span class="detail-value">${formatDateTime(activePeriod.deadline)}</span></div>
      </div>
    </div>
  </div>
</div>` : `<div class="empty-state"><div class="empty-state-title">Chưa có dữ liệu</div><div class="empty-state-desc">Tạo kỳ đăng ký để bắt đầu.</div></div>`}

<div class="card">
  <div class="card-header">
    <span class="card-title">Hoạt động gần đây</span>
    <button class="btn btn-secondary btn-sm" onclick="App.navigate('history')">Xem tất cả</button>
  </div>
  <div class="card-body" style="padding:16px">
    ${_renderRecentHistory()}
  </div>
</div>`;
}

function _buildWarnings(period, stats) {
  const w = [];
  if (!period) return ['Chưa có kỳ đăng ký nào.'];
  if (period.status === 'open' && period.deadline) {
    const diff = Math.ceil((new Date(period.deadline) - new Date()) / 3600000);
    if (diff > 0 && diff < 48) w.push(`Kỳ đăng ký "${period.name}" sẽ đóng sau ${diff} giờ.`);
  }
  if (stats) {
    if (stats.unregisteredCount > 0) w.push(`${stats.unregisteredCount} nhân viên chưa đăng ký thời gian rảnh.`);
    if (stats.understaffedShifts > 0) w.push(`${stats.understaffedShifts} ca thiếu nhân sự.`);
  }
  return w;
}

function _statCard(label, value, bgColor, textColor, icon) {
  return `<div class="stat-card">
    <div class="stat-card-header">
      <span class="stat-label">${label}</span>
      <div class="stat-icon" style="background:${bgColor};color:${textColor}">${icon}</div>
    </div>
    <div class="stat-value">${value ?? '—'}</div>
  </div>`;
}

function _renderRecentHistory() {
  const hist = State.get.history().slice(0, 6);
  if (!hist.length) return '<p class="text-muted text-sm">Chưa có hoạt động nào.</p>';
  return `<div class="timeline">${hist.map(h => `
    <div class="timeline-item">
      <div class="timeline-dot"></div>
      <div class="timeline-content">
        <div class="timeline-title">${escHtml(h.title)}</div>
        <div class="timeline-meta">${escHtml(h.actor)} · ${formatDateTime(h.timestamp)}</div>
        ${h.detail ? `<div class="timeline-detail">${escHtml(h.detail)}</div>` : ''}
      </div>
    </div>`).join('')}</div>`;
}

function switchDashPeriod(periodId) {
  App.navigate('dashboard');
}

/* ═══════════════════════════════════════════════════════════
   5.2 QUẢN LÝ NHÂN SỰ
═══════════════════════════════════════════════════════════ */
let _userSearch = '', _userLevelFilter = '', _userStatusFilter = '';

function renderUsers() {
  const levels  = State.get.levels();
  const users   = _getFilteredUsers();

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Quản lý nhân sự</h1>
    <p class="page-subtitle">${users.length} nhân viên</p>
  </div>
  <div class="page-actions">
    <button class="btn btn-secondary" onclick="Admin.openLevelManager()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>
      Quản lý Level
    </button>
    <button class="btn btn-primary" onclick="Admin.openUserForm()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      Thêm nhân viên
    </button>
  </div>
</div>

<div class="card">
  <div class="table-toolbar">
    <div class="search-input-wrap">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
      <input type="text" aria-label="Tìm nhân viên theo tên hoặc mã" placeholder="Tìm tên hoặc mã..." value="${escHtml(_userSearch)}"
             oninput="Admin.filterUsers('search', this.value)" />
    </div>
    <select class="form-input filter-select" aria-label="Lọc nhân viên theo Level" onchange="Admin.filterUsers('level', this.value)">
      <option value="">Tất cả Level</option>
      ${levels.map(l => `<option value="${l.id}" ${_userLevelFilter === l.id ? 'selected' : ''}>${escHtml(l.name)}</option>`).join('')}
    </select>
    <select class="form-input filter-select" aria-label="Lọc nhân viên theo trạng thái" onchange="Admin.filterUsers('status', this.value)">
      <option value="">Tất cả trạng thái</option>
      <option value="active"   ${_userStatusFilter === 'active'   ? 'selected' : ''}>Đang hoạt động</option>
      <option value="inactive" ${_userStatusFilter === 'inactive' ? 'selected' : ''}>Tạm khóa</option>
    </select>
  </div>
  <div class="table-container">
    <table class="data-table">
      <thead>
        <tr>
          <th>Mã NV</th><th>Họ tên</th><th>Tài khoản</th><th>Level</th>
          <th>Trạng thái</th><th>Tỷ lệ (%)</th><th>Giờ MT</th><th>Giờ đã phân</th><th>Còn thiếu</th>
          <th class="actions-cell">Thao tác</th>
        </tr>
      </thead>
      <tbody>
        ${users.length ? users.map(u => _renderUserRow(u, levels)).join('') : `
        <tr><td colspan="10" class="table-empty">
          <div class="empty-state-icon">${iconUsers()}</div>
          <div>Không tìm thấy nhân viên nào.</div>
        </td></tr>`}
      </tbody>
    </table>
  </div>
</div>`;
}

function _renderUserRow(u, levels) {
  const level = levels.find(l => l.id === u.levelId);
  const targetH  = State.get.targetHoursForUser(u.id);
  const assignedH = State.get.assignedHoursByUser(u.id, null, null);
  const diff = targetH - assignedH;
  return `<tr>
    <td><span class="font-medium">${escHtml(u.code)}</span></td>
    <td>${escHtml(u.fullName)}</td>
    <td><code style="font-size:0.786rem">${escHtml(u.username)}</code></td>
    <td>${level ? `<span class="badge" style="background:${level.color}22;color:${level.color}">${escHtml(level.name)}</span>` : '—'}</td>
    <td>${renderBadge(u.status)}</td>
    <td>${u.targetRate}%</td>
    <td>${targetH}h</td>
    <td>${assignedH.toFixed(1)}h</td>
    <td class="${diff > 0 ? 'text-warning' : 'text-success'} font-medium">${diff > 0 ? diff.toFixed(1)+'h' : '✓'}</td>
    <td class="actions-cell">
      <button class="btn btn-secondary btn-sm" onclick="Admin.openUserForm('${u.id}')">Sửa</button>
      <button class="btn btn-ghost btn-sm" onclick="Admin.viewUserDetail('${u.id}')">Chi tiết</button>
      <button class="btn btn-${u.status === 'active' ? 'warning' : 'success'} btn-sm"
              onclick="Admin.toggleUserStatus('${u.id}')">
        ${u.status === 'active' ? 'Khóa' : 'Mở khóa'}
      </button>
    </td>
  </tr>`;
}

function _getFilteredUsers() {
  return State.get.users().filter(u => {
    if (u.role === 'admin') return false;
    if (_userSearch) {
      const q = _userSearch.toLowerCase();
      if (!u.fullName.toLowerCase().includes(q) && !u.code.toLowerCase().includes(q)) return false;
    }
    if (_userLevelFilter && u.levelId !== _userLevelFilter) return false;
    if (_userStatusFilter && u.status !== _userStatusFilter) return false;
    return true;
  });
}

function filterUsers(type, val) {
  if (type === 'search') _userSearch = val;
  if (type === 'level')  _userLevelFilter = val;
  if (type === 'status') _userStatusFilter = val;
  document.getElementById('main-content').innerHTML = renderUsers();
}

function openUserForm(userId = null) {
  const user = userId ? State.get.userById(userId) : null;
  const levels = State.get.levels().filter(l => true);
  const isNew  = !user;
  const title  = isNew ? 'Thêm nhân viên' : 'Chỉnh sửa nhân viên';

  const body = `
<form id="user-form" novalidate>
  <div class="form-row-2">
    <div class="form-group">
      <label class="form-label" for="uf-code">Mã nhân viên <span class="required" aria-hidden="true">*</span></label>
      <input type="text" id="uf-code" name="code" class="form-input" value="${escHtml(user?.code||'')}" placeholder="NV001"
             aria-required="true" aria-describedby="uf-code-err">
      <span class="field-error" id="uf-code-err" data-err="code" aria-live="polite"></span>
    </div>
    <div class="form-group">
      <label class="form-label" for="uf-fullName">Họ và tên <span class="required" aria-hidden="true">*</span></label>
      <input type="text" id="uf-fullName" name="fullName" class="form-input" value="${escHtml(user?.fullName||'')}" placeholder="Nguyễn Văn A"
             aria-required="true" aria-describedby="uf-fullName-err">
      <span class="field-error" id="uf-fullName-err" data-err="fullName" aria-live="polite"></span>
    </div>
  </div>
  <div class="form-row-2">
    <div class="form-group">
      <label class="form-label" for="uf-username">Tên đăng nhập <span class="required" aria-hidden="true">*</span></label>
      <input type="text" id="uf-username" name="username" class="form-input" value="${escHtml(user?.username||'')}" placeholder="nvana"
             ${!isNew ? 'disabled' : ''} aria-required="true" aria-describedby="uf-username-err">
      <span class="field-error" id="uf-username-err" data-err="username" aria-live="polite"></span>
    </div>
    <div class="form-group">
      <label class="form-label" for="uf-password">${isNew ? 'Mật khẩu' : 'Mật khẩu mới'} ${isNew ? '<span class="required" aria-hidden="true">*</span>' : ''}</label>
      <input type="password" id="uf-password" name="password" class="form-input" placeholder="${isNew ? 'Nhập mật khẩu' : 'Để trống nếu không đổi'}"
             ${isNew ? 'aria-required="true"' : ''} aria-describedby="uf-password-err">
      <span class="field-error" id="uf-password-err" data-err="password" aria-live="polite"></span>
    </div>
  </div>
  <div class="form-row-2">
    <div class="form-group">
      <label class="form-label" for="uf-levelId">Level <span class="required" aria-hidden="true">*</span></label>
      <select id="uf-levelId" name="levelId" class="form-input" aria-required="true" aria-describedby="uf-levelId-err">
        <option value="">-- Chọn Level --</option>
        ${levels.map(l => `<option value="${l.id}" ${user?.levelId === l.id ? 'selected' : ''}>${escHtml(l.name)}</option>`).join('')}
      </select>
      <span class="field-error" id="uf-levelId-err" data-err="levelId" aria-live="polite"></span>
    </div>
    <div class="form-group">
      <label class="form-label" for="uf-targetRate">Tỷ lệ giờ mục tiêu (%)</label>
      <input type="number" id="uf-targetRate" name="targetRate" class="form-input" value="${user?.targetRate ?? 100}" min="0" max="200"
             aria-describedby="uf-targetRate-hint uf-targetRate-err">
      <span class="field-error" id="uf-targetRate-err" data-err="targetRate" aria-live="polite"></span>
      <span class="form-hint" id="uf-targetRate-hint">Giờ mục tiêu = Giờ chuẩn × Tỷ lệ / 100</span>
    </div>
  </div>
  <div class="form-row-2">
    <div class="form-group">
      <label class="form-label" for="uf-phone">Số điện thoại</label>
      <input type="text" id="uf-phone" name="phone" class="form-input" value="${escHtml(user?.phone||'')}" placeholder="0901234567">
    </div>
    <div class="form-group">
      <label class="form-label" for="uf-email">Email</label>
      <input type="email" id="uf-email" name="email" class="form-input" value="${escHtml(user?.email||'')}" placeholder="email@company.vn">
    </div>
  </div>
  <div class="form-group">
    <label class="form-label" for="uf-note">Ghi chú</label>
    <textarea id="uf-note" name="note" class="form-input" rows="2">${escHtml(user?.note||'')}</textarea>
  </div>
  <input type="hidden" name="role" value="user">
  <input type="hidden" name="id" value="${user?.id||''}">
</form>`;

  App.openModal(title, body, [
    { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
    { label: isNew ? 'Thêm nhân viên' : 'Lưu thay đổi', cls: 'btn-primary', action: () => submitUserForm(isNew) },
  ]);
}

function submitUserForm(isNew) {
  const form = document.getElementById('user-form');
  if (!form) return;
  clearFormErrors(form);

  const data = {
    id:         form.querySelector('[name="id"]').value || null,
    code:       form.querySelector('[name="code"]').value.trim(),
    fullName:   form.querySelector('[name="fullName"]').value.trim(),
    username:   form.querySelector('[name="username"]').value.trim(),
    password:   form.querySelector('[name="password"]').value,
    levelId:    form.querySelector('[name="levelId"]').value,
    targetRate: parseFloat(form.querySelector('[name="targetRate"]').value),
    phone:      form.querySelector('[name="phone"]').value.trim(),
    email:      form.querySelector('[name="email"]').value.trim(),
    note:       form.querySelector('[name="note"]').value.trim(),
    role:       'user',
    status:     'active',
  };

  const result = Validate.userForm(data, isNew);
  if (!result.valid) { showFieldErrors(form, result.errors); return; }

  if (UniqueCheck.userCode(data.code, data.id))    { showFieldErrors(form, { code: 'Mã nhân viên đã tồn tại.' }); return; }
  if (isNew && UniqueCheck.username(data.username)) { showFieldErrors(form, { username: 'Tên đăng nhập đã tồn tại.' }); return; }

  const existing = data.id ? State.get.userById(data.id) : null;
  if (!isNew && !data.password) data.password = existing?.password || '';

  State.mut.saveUser(data);
  logHistory('user_save', isNew ? 'Thêm nhân viên' : 'Cập nhật nhân viên',
    `${data.code} - ${data.fullName}`, State.get.currentUser()?.id || 'admin');

  App.closeModal();
  App.toast(isNew ? 'Đã thêm nhân viên.' : 'Đã cập nhật nhân viên.', 'success');
  App.navigate('users');
}

function toggleUserStatus(userId) {
  const user = State.get.userById(userId);
  if (!user) return;
  const action = user.status === 'active' ? 'khóa' : 'mở khóa';
  App.confirm(`Xác nhận ${action} tài khoản "${user.fullName}"?`, '', () => {
    const newStatus = user.status === 'active' ? 'inactive' : 'active';
    State.mut.saveUser({ ...user, status: newStatus });
    logHistory('user_status', `${action.charAt(0).toUpperCase()+action.slice(1)} tài khoản`,
      `${user.code} - ${user.fullName}`, State.get.currentUser()?.id || 'admin');
    App.toast(`Đã ${action} tài khoản.`, 'success');
    App.navigate('users');
  }, { type: user.status === 'active' ? 'warn' : 'info' });
}

function viewUserDetail(userId) {
  const user   = State.get.userById(userId);
  if (!user) return;
  const level  = State.get.levelById(user.levelId);
  const targetH   = State.get.targetHoursForUser(userId);
  const assignedH = State.get.assignedHoursByUser(userId, null, null);
  const userAsgns = State.get.assignmentsByUser(userId);
  const shifts    = State.get.shifts();
  const myShifts  = userAsgns.map(a => shifts.find(s => s.id === a.shiftId)).filter(Boolean)
                             .sort((a,b) => a.date.localeCompare(b.date)).slice(0,10);

  const body = `
<div class="detail-list mb-4">
  <div class="detail-item"><span class="detail-label">Mã nhân viên</span><span class="detail-value font-medium">${escHtml(user.code)}</span></div>
  <div class="detail-item"><span class="detail-label">Họ tên</span><span class="detail-value">${escHtml(user.fullName)}</span></div>
  <div class="detail-item"><span class="detail-label">Tài khoản</span><span class="detail-value"><code>${escHtml(user.username)}</code></span></div>
  <div class="detail-item"><span class="detail-label">Level</span><span class="detail-value">${level ? `<span class="badge" style="background:${level.color}22;color:${level.color}">${escHtml(level.name)}</span>` : '—'}</span></div>
  <div class="detail-item"><span class="detail-label">Trạng thái</span><span class="detail-value">${renderBadge(user.status)}</span></div>
  <div class="detail-item"><span class="detail-label">Giờ mục tiêu</span><span class="detail-value">${targetH}h (tỷ lệ ${user.targetRate}%)</span></div>
  <div class="detail-item"><span class="detail-label">Giờ đã phân</span><span class="detail-value">${assignedH.toFixed(1)}h</span></div>
  <div class="detail-item"><span class="detail-label">Điện thoại</span><span class="detail-value">${escHtml(user.phone||'—')}</span></div>
  <div class="detail-item"><span class="detail-label">Email</span><span class="detail-value">${escHtml(user.email||'—')}</span></div>
  ${user.note ? `<div class="detail-item"><span class="detail-label">Ghi chú</span><span class="detail-value text-secondary">${escHtml(user.note)}</span></div>` : ''}
</div>
<div class="form-section-title">Ca làm việc gần đây (${userAsgns.length} tổng)</div>
${myShifts.length ? `<table class="data-table"><thead><tr><th>Ngày</th><th>Ca</th><th>Giờ</th><th>Thời lượng</th></tr></thead><tbody>
  ${myShifts.map(s => `<tr><td>${formatDate(s.date)}</td><td>${escHtml(s.name)}</td><td>${s.startTime}–${s.endTime}</td><td>${getShiftDurationLabel(s.startTime,s.endTime)}</td></tr>`).join('')}
</tbody></table>` : '<p class="text-muted text-sm">Chưa có ca nào được phân.</p>'}`;

  App.openModal(`Chi tiết: ${user.fullName}`, body, [
    { label: 'Đóng', cls: 'btn-secondary', action: () => App.closeModal() },
    { label: 'Chỉnh sửa', cls: 'btn-primary', action: () => { App.closeModal(); openUserForm(userId); } },
  ], 'modal-lg');
}

/* ── Level Manager ─────────────────────────────────────────── */
function openLevelManager() {
  const levels = State.get.levels();
  const body = `
<div id="level-list-wrap">
  ${_renderLevelList(levels)}
</div>
<hr class="divider">
<div id="level-form-wrap">
  <div class="form-section-title">Thêm Level mới</div>
  ${_renderLevelFormInline()}
</div>`;
  App.openModal('Quản lý Level', body, [
    { label: 'Đóng', cls: 'btn-secondary', action: () => App.closeModal() },
  ], 'modal-lg');
}

function _renderLevelList(levels) {
  if (!levels.length) return '<p class="text-muted text-sm">Chưa có Level nào.</p>';
  return `<table class="data-table"><thead><tr><th>Thứ tự</th><th>Tên Level</th><th>Mô tả</th><th>Màu</th><th>Số NV</th><th>Thao tác</th></tr></thead><tbody>
  ${levels.map(l => {
    const count = State.get.users().filter(u => u.levelId === l.id).length;
    return `<tr>
      <td>${l.order}</td>
      <td><span class="badge" style="background:${l.color}22;color:${l.color}">${escHtml(l.name)}</span></td>
      <td class="text-secondary">${escHtml(l.description||'')}</td>
      <td><span style="display:inline-block;width:20px;height:20px;border-radius:4px;background:${l.color}"></span></td>
      <td>${count}</td>
      <td class="actions-cell">
        <button class="btn btn-secondary btn-sm" onclick="Admin.editLevelInline('${l.id}')">Sửa</button>
        <button class="btn btn-danger btn-sm" onclick="Admin.deleteLevel('${l.id}')" ${count > 0 ? 'disabled title="Còn nhân viên đang dùng"' : ''}>Xóa</button>
      </td>
    </tr>`;
  }).join('')}
  </tbody></table>`;
}

function _renderLevelFormInline(level = null) {
  const isEdit = !!level;
  return `<form id="level-inline-form" novalidate class="level-inline-form">
    <div class="form-group"><label class="form-label" for="level-order">Thứ tự</label>
      <input type="number" id="level-order" name="order" class="form-input" value="${level?.order ?? (State.get.levels().length + 1)}" min="1">
      <span class="field-error" id="level-order-err" data-err="order" aria-live="polite"></span>
    </div>
    <div class="form-group"><label class="form-label" for="level-name">Tên Level <span class="required" aria-hidden="true">*</span></label>
      <input type="text" id="level-name" name="name" class="form-input" value="${escHtml(level?.name||'')}" placeholder="Level 5" aria-required="true" aria-describedby="level-name-err">
      <span class="field-error" id="level-name-err" data-err="name" aria-live="polite"></span>
    </div>
    <div class="form-group"><label class="form-label" for="level-description">Mô tả</label>
      <input type="text" id="level-description" name="description" class="form-input" value="${escHtml(level?.description||'')}" placeholder="Mô tả ngắn">
    </div>
    <div class="form-group"><label class="form-label" for="level-color">Màu</label>
      <input type="color" id="level-color" name="color" class="form-input level-color-input" value="${level?.color||'#3b82f6'}">
    </div>
    <input type="hidden" name="id" value="${level?.id||''}">
    <div style="padding-bottom:2px">
      <button type="button" class="btn btn-primary" onclick="Admin.submitLevelForm()">${isEdit ? 'Cập nhật' : 'Thêm'}</button>
      ${isEdit ? `<button type="button" class="btn btn-ghost" onclick="Admin.cancelLevelEdit()">Hủy</button>` : ''}
    </div>
  </form>`;
}

function submitLevelForm() {
  const form = document.getElementById('level-inline-form');
  if (!form) return;
  clearFormErrors(form);
  const data = {
    id:          form.querySelector('[name="id"]').value || null,
    name:        form.querySelector('[name="name"]').value.trim(),
    description: form.querySelector('[name="description"]').value.trim(),
    color:       form.querySelector('[name="color"]').value,
    order:       parseInt(form.querySelector('[name="order"]').value),
  };
  const result = Validate.levelForm(data);
  if (!result.valid) { showFieldErrors(form, result.errors); return; }
  if (UniqueCheck.levelName(data.name, data.id)) { showFieldErrors(form, { name: 'Tên Level đã tồn tại.' }); return; }

  State.mut.saveLevel(data);
  logHistory('level_save', data.id ? 'Cập nhật Level' : 'Thêm Level', data.name, State.get.currentUser()?.id || 'admin');
  App.toast(data.id ? 'Đã cập nhật Level.' : 'Đã thêm Level.', 'success');
  // Refresh modal content
  const levels = State.get.levels();
  document.getElementById('level-list-wrap').innerHTML = _renderLevelList(levels);
  document.getElementById('level-form-wrap').innerHTML = `<div class="form-section-title">Thêm Level mới</div>${_renderLevelFormInline()}`;
}

function editLevelInline(levelId) {
  const level = State.get.levelById(levelId);
  if (!level) return;
  document.getElementById('level-form-wrap').innerHTML = `<div class="form-section-title">Chỉnh sửa Level</div>${_renderLevelFormInline(level)}`;
}

function cancelLevelEdit() {
  document.getElementById('level-form-wrap').innerHTML = `<div class="form-section-title">Thêm Level mới</div>${_renderLevelFormInline()}`;
}

function deleteLevel(levelId) {
  const level = State.get.levelById(levelId);
  if (!level) return;
  App.confirm(`Xóa Level "${level.name}"?`, 'Thao tác không thể hoàn tác.', () => {
    State.mut.deleteLevel(levelId);
    App.toast('Đã xóa Level.', 'success');
    const levels = State.get.levels();
    document.getElementById('level-list-wrap').innerHTML = _renderLevelList(levels);
  }, { type: 'danger' });
}

/* ── Icon helpers ──────────────────────────────────────────── */
function iconUsers()   { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>`; }
function iconCalendar(){ return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>`; }
function iconCheck()   { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>`; }
function iconAlert()   { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`; }
function iconUser()    { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>`; }
function iconPublish() { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>`; }

  return {
    renderDashboard, switchDashPeriod,
    renderUsers, filterUsers, openUserForm, submitUserForm,
    toggleUserStatus, viewUserDetail,
    openLevelManager, submitLevelForm, editLevelInline, cancelLevelEdit, deleteLevel,
  };
})();
