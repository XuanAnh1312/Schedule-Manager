/**
 * admin2.js — Admin màn hình 3, 4, 5
 * 5.3 Cấu hình kỳ đăng ký
 * 5.4 Lịch làm việc (Calendar)
 * 5.5 Quản lý ca làm việc
 */

/* ═══════════════════════════════════════════════════════════
   5.3 CẤU HÌNH KỲ ĐĂNG KÝ
═══════════════════════════════════════════════════════════ */
const AdminPeriod = (() => {

function render() {
  const periods = State.get.periods().sort((a,b) => b.startDate.localeCompare(a.startDate));
  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Cấu hình kỳ đăng ký</h1>
    <p class="page-subtitle">${periods.length} kỳ đăng ký</p>
  </div>
  <div class="page-actions">
    <button class="btn btn-primary" onclick="AdminPeriod.openForm()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      Tạo kỳ đăng ký
    </button>
  </div>
</div>

<div style="display:flex;flex-direction:column;gap:16px">
  ${periods.length ? periods.map(p => _renderPeriodCard(p)).join('') : `
  <div class="card"><div class="card-body"><div class="empty-state">
    <div class="empty-state-title">Chưa có kỳ đăng ký</div>
    <div class="empty-state-desc">Tạo kỳ đăng ký đầu tiên để bắt đầu thu thập thời gian rảnh.</div>
    <button class="btn btn-primary" onclick="AdminPeriod.openForm()">Tạo kỳ đăng ký</button>
  </div></div></div>`}
</div>`;
}

function _renderPeriodCard(p) {
  const freeTimeList = State.get.freeTimeByPeriod(p.id);
  const submitted    = freeTimeList.filter(ft => ft.status === 'submitted').length;
  const unregistered = State.get.unregisteredUsersInPeriod(p.id).length;
  const totalActive  = State.get.activeUsers().length;

  const actionBtns = [];
  if (p.status === 'not_open' || p.status === 'draft') {
    actionBtns.push(`<button class="btn btn-success btn-sm" onclick="AdminPeriod.openPeriod('${p.id}')">Mở đăng ký</button>`);
  }
  if (p.status === 'open') {
    actionBtns.push(`<button class="btn btn-warning btn-sm" onclick="AdminPeriod.lockPeriod('${p.id}')">Khóa đăng ký</button>`);
  }
  if (p.status === 'locked' && p.allowReopenByAdmin) {
    actionBtns.push(`<button class="btn btn-secondary btn-sm" onclick="AdminPeriod.reopenPeriod('${p.id}')">Mở lại</button>`);
  }
  actionBtns.push(`<button class="btn btn-secondary btn-sm" onclick="AdminPeriod.openForm('${p.id}')">Chỉnh sửa</button>`);
  actionBtns.push(`<button class="btn btn-ghost btn-sm" onclick="AdminPeriod.copyPeriod('${p.id}')">Sao chép</button>`);
  actionBtns.push(`<button class="btn btn-ghost btn-sm" onclick="AdminPeriod.viewRegistrations('${p.id}')">Xem đăng ký</button>`);

  return `<div class="card">
    <div class="card-header">
      <div>
        <div class="card-title">${escHtml(p.name)}</div>
        <div class="card-subtitle">${formatDate(p.startDate)} – ${formatDate(p.endDate)} · Chu kỳ: ${_cycleName(p.cycle)}</div>
      </div>
      <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
        ${renderBadge(p.status)}
        ${actionBtns.join('')}
      </div>
    </div>
    <div class="card-body" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:12px">
      <div><span class="stat-label">Mở đăng ký</span><div class="font-medium text-sm">${formatDateTime(p.openAt)}</div></div>
      <div><span class="stat-label">Hạn chót</span><div class="font-medium text-sm">${formatDateTime(p.deadline)}</div></div>
      <div><span class="stat-label">Đã đăng ký</span><div class="font-medium text-sm">${submitted} / ${totalActive} nhân viên</div></div>
      <div><span class="stat-label">Chưa đăng ký</span><div class="font-medium text-sm ${unregistered > 0 ? 'text-danger' : 'text-success'}">${unregistered} nhân viên</div></div>
      <div><span class="stat-label">Tự động khóa</span><div class="font-medium text-sm">${p.autoLock ? 'Có' : 'Không'}</div></div>
      <div><span class="stat-label">Ngày được miễn</span><div class="font-medium text-sm">${p.exemptDates?.length || 0} ngày</div></div>
    </div>
  </div>`;
}

function _cycleName(cycle) {
  return { weekly: 'Theo tuần', monthly: 'Theo tháng', yearly: 'Theo năm' }[cycle] || cycle;
}

function openForm(periodId = null) {
  const period = periodId ? State.get.periodById(periodId) : null;
  const isNew  = !period;
  const title  = isNew ? 'Tạo kỳ đăng ký' : 'Chỉnh sửa kỳ đăng ký';
  const dowLabels = ['CN','T2','T3','T4','T5','T6','T7'];

  const body = `
<form id="period-form" novalidate>
  <div class="form-section">
    <div class="form-section-title">Thông tin cơ bản</div>
    <div class="form-group mb-4">
      <label class="form-label">Tên kỳ đăng ký <span class="required">*</span></label>
      <input type="text" name="name" class="form-input" value="${escHtml(period?.name||'')}" placeholder="Kỳ tháng 11/2026">
      <span class="field-error" data-err="name"></span>
    </div>
    <div class="form-row-2">
      <div class="form-group">
        <label class="form-label">Ngày bắt đầu <span class="required">*</span></label>
        <input type="date" name="startDate" class="form-input" value="${period?.startDate||''}">
        <span class="field-error" data-err="startDate"></span>
      </div>
      <div class="form-group">
        <label class="form-label">Ngày kết thúc <span class="required">*</span></label>
        <input type="date" name="endDate" class="form-input" value="${period?.endDate||''}">
        <span class="field-error" data-err="endDate"></span>
      </div>
    </div>
    <div class="form-group">
      <label class="form-label">Chu kỳ</label>
      <select name="cycle" class="form-input">
        <option value="weekly"  ${period?.cycle==='weekly'  ? 'selected':''}>Theo tuần</option>
        <option value="monthly" ${!period||period?.cycle==='monthly' ? 'selected':''}>Theo tháng</option>
        <option value="yearly"  ${period?.cycle==='yearly'  ? 'selected':''}>Theo năm</option>
      </select>
    </div>
  </div>
  <div class="form-section">
    <div class="form-section-title">Thời gian đăng ký</div>
    <div class="form-row-2">
      <div class="form-group">
        <label class="form-label">Thời điểm mở đăng ký</label>
        <input type="datetime-local" name="openAt" class="form-input"
               value="${period?.openAt ? new Date(period.openAt).toISOString().slice(0,16) : ''}">
        <span class="field-error" data-err="openAt"></span>
      </div>
      <div class="form-group">
        <label class="form-label">Hạn chót đăng ký</label>
        <input type="datetime-local" name="deadline" class="form-input"
               value="${period?.deadline ? new Date(period.deadline).toISOString().slice(0,16) : ''}">
        <span class="field-error" data-err="deadline"></span>
      </div>
    </div>
    <div style="display:flex;gap:24px;flex-wrap:wrap">
      <label class="toggle-switch">
        <input type="checkbox" name="autoLock" ${period?.autoLock !== false ? 'checked' : ''}>
        <span class="toggle-track"></span>
        <span class="toggle-label">Tự động khóa khi hết hạn</span>
      </label>
      <label class="toggle-switch">
        <input type="checkbox" name="allowEditAfterDeadline" ${period?.allowEditAfterDeadline ? 'checked' : ''}>
        <span class="toggle-track"></span>
        <span class="toggle-label">Cho phép sửa sau hạn</span>
      </label>
      <label class="toggle-switch">
        <input type="checkbox" name="allowReopenByAdmin" ${period?.allowReopenByAdmin !== false ? 'checked' : ''}>
        <span class="toggle-track"></span>
        <span class="toggle-label">Cho phép Admin mở lại</span>
      </label>
    </div>
  </div>
  <div class="form-section">
    <div class="form-section-title">Ngày cần đăng ký</div>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      ${dowLabels.map((label, i) => `
        <label class="form-check">
          <input type="checkbox" name="dow_${i}" ${(period?.requiredDaysOfWeek ?? [1,2,3,4,5,6]).includes(i) ? 'checked' : ''}>
          <span class="form-check-label">${label}</span>
        </label>`).join('')}
    </div>
    <span class="form-hint mt-1">Các thứ nhân viên cần đăng ký thời gian rảnh.</span>
  </div>
  <div class="form-section">
    <div class="form-section-title">Ngày được miễn đăng ký</div>
    <div id="exempt-dates-wrap">
      ${_renderExemptDates(period?.exemptDates||[])}
    </div>
    <div style="display:flex;gap:8px;margin-top:8px">
      <input type="date" id="new-exempt-date" class="form-input" style="width:auto">
      <button type="button" class="btn btn-secondary btn-sm" onclick="AdminPeriod.addExemptDate()">Thêm ngày miễn</button>
    </div>
    <span class="form-hint">Ngày nghỉ lễ, ngày cửa hàng đóng cửa, v.v.</span>
  </div>
  <input type="hidden" name="id" value="${period?.id||''}">
  <input type="hidden" name="exempt-dates-hidden" id="exempt-dates-hidden" value="${escHtml(JSON.stringify(period?.exemptDates||[]))}">
</form>`;

  App.openModal(title, body, [
    { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
    { label: isNew ? 'Tạo kỳ' : 'Lưu thay đổi', cls: 'btn-primary', action: () => submitPeriodForm(isNew) },
  ], 'modal-lg');
}

let _exemptDates = [];

function _renderExemptDates(dates) {
  _exemptDates = [...dates];
  if (!dates.length) return '<p class="text-muted text-sm">Chưa có ngày miễn.</p>';
  return dates.map(d => `
    <span class="user-tag" style="margin:2px">
      ${formatDate(d)}
      <button class="user-tag-remove" type="button" onclick="AdminPeriod.removeExemptDate('${d}')">×</button>
    </span>`).join('');
}

function addExemptDate() {
  const input = document.getElementById('new-exempt-date');
  const val   = input?.value;
  if (!val) return;
  if (!_exemptDates.includes(val)) {
    _exemptDates.push(val);
    _exemptDates.sort();
  }
  input.value = '';
  document.getElementById('exempt-dates-wrap').innerHTML = _renderExemptDates(_exemptDates);
  document.getElementById('exempt-dates-hidden').value = JSON.stringify(_exemptDates);
}

function removeExemptDate(dateStr) {
  _exemptDates = _exemptDates.filter(d => d !== dateStr);
  document.getElementById('exempt-dates-wrap').innerHTML = _renderExemptDates(_exemptDates);
  document.getElementById('exempt-dates-hidden').value = JSON.stringify(_exemptDates);
}

function submitPeriodForm(isNew) {
  const form = document.getElementById('period-form');
  if (!form) return;
  clearFormErrors(form);

  const dow = [0,1,2,3,4,5,6].filter(i => form.querySelector(`[name="dow_${i}"]`)?.checked);
  const data = {
    id:                   form.querySelector('[name="id"]').value || null,
    name:                 form.querySelector('[name="name"]').value.trim(),
    cycle:                form.querySelector('[name="cycle"]').value,
    startDate:            form.querySelector('[name="startDate"]').value,
    endDate:              form.querySelector('[name="endDate"]').value,
    openAt:               form.querySelector('[name="openAt"]').value ? new Date(form.querySelector('[name="openAt"]').value).toISOString() : null,
    deadline:             form.querySelector('[name="deadline"]').value ? new Date(form.querySelector('[name="deadline"]').value).toISOString() : null,
    autoLock:             form.querySelector('[name="autoLock"]').checked,
    allowEditAfterDeadline: form.querySelector('[name="allowEditAfterDeadline"]').checked,
    allowReopenByAdmin:   form.querySelector('[name="allowReopenByAdmin"]').checked,
    requiredDaysOfWeek:   dow,
    exemptDates:          JSON.parse(document.getElementById('exempt-dates-hidden').value || '[]'),
    closedDates:          [],
    applicableUserIds:    [],
    status:               isNew ? 'not_open' : (State.get.periodById(form.querySelector('[name="id"]').value)?.status || 'not_open'),
  };

  const result = Validate.periodForm(data);
  if (!result.valid) { showFieldErrors(form, result.errors); return; }
  if (UniqueCheck.periodName(data.name, data.id)) { showFieldErrors(form, { name: 'Tên kỳ đã tồn tại.' }); return; }

  State.mut.savePeriod(data);
  logHistory('period_save', isNew ? 'Tạo kỳ đăng ký' : 'Cập nhật kỳ đăng ký',
    data.name, State.get.currentUser()?.id || 'admin');

  App.closeModal();
  App.toast(isNew ? 'Đã tạo kỳ đăng ký.' : 'Đã cập nhật kỳ đăng ký.', 'success');
  App.navigate('periods');
}

function openPeriod(periodId) {
  const p = State.get.periodById(periodId);
  if (!p) return;
  App.confirm(`Mở đăng ký kỳ "${p.name}"?`, 'Nhân viên sẽ có thể đăng ký thời gian rảnh.', () => {
    State.mut.savePeriod({ ...p, status: 'open' });
    logHistory('period_open', 'Mở kỳ đăng ký', p.name, State.get.currentUser()?.id || 'admin');
    App.toast('Đã mở kỳ đăng ký.', 'success');
    App.navigate('periods');
  }, { type: 'info' });
}

function lockPeriod(periodId) {
  const p = State.get.periodById(periodId);
  if (!p) return;
  App.confirm(`Khóa kỳ đăng ký "${p.name}"?`, 'Nhân viên sẽ không thể sửa đăng ký nữa.', () => {
    State.mut.savePeriod({ ...p, status: 'locked' });
    logHistory('period_lock', 'Khóa kỳ đăng ký', p.name, State.get.currentUser()?.id || 'admin');
    App.toast('Đã khóa kỳ đăng ký.', 'success');
    App.navigate('periods');
  }, { type: 'warn' });
}

function reopenPeriod(periodId) {
  const p = State.get.periodById(periodId);
  if (!p) return;
  App.confirm(`Mở lại kỳ đăng ký "${p.name}"?`, '', () => {
    State.mut.savePeriod({ ...p, status: 'open' });
    logHistory('period_reopen', 'Mở lại kỳ đăng ký', p.name, State.get.currentUser()?.id || 'admin');
    App.toast('Đã mở lại kỳ đăng ký.', 'success');
    App.navigate('periods');
  });
}

function copyPeriod(periodId) {
  const p = State.get.periodById(periodId);
  if (!p) return;
  const copy = { ...p, id: null, name: p.name + ' (Bản sao)', status: 'not_open', createdAt: null, updatedAt: null };
  openFormWithData(copy);
}

function openFormWithData(data) {
  // Re-use openForm with prefilled data - just open the new form
  openForm(null);
  setTimeout(() => {
    const form = document.getElementById('period-form');
    if (!form) return;
    form.querySelector('[name="name"]').value = data.name;
    form.querySelector('[name="startDate"]').value = data.startDate || '';
    form.querySelector('[name="endDate"]').value   = data.endDate   || '';
  }, 50);
}

function viewRegistrations(periodId) {
  const p      = State.get.periodById(periodId);
  if (!p) return;
  const users  = State.get.activeUsers();
  const ftList = State.get.freeTimeByPeriod(periodId);

  const rows = users.map(u => {
    const ft = ftList.find(f => f.userId === u.id);
    const level = State.get.levelById(u.levelId);
    const status = ft ? ft.status : 'not_registered';
    return { u, ft, level, status };
  });

  const body = `
<div style="margin-bottom:12px">
  <span class="badge badge-submitted">Đã gửi: ${rows.filter(r=>r.status==='submitted').length}</span>
  <span class="badge badge-draft" style="margin-left:6px">Bản nháp: ${rows.filter(r=>r.status==='draft').length}</span>
  <span class="badge badge-not-registered" style="margin-left:6px">Chưa đăng ký: ${rows.filter(r=>r.status==='not_registered').length}</span>
</div>
<div class="table-container">
<table class="data-table"><thead><tr><th>Mã NV</th><th>Họ tên</th><th>Level</th><th>Trạng thái</th><th>Ngày gửi</th></tr></thead>
<tbody>
${rows.map(r => `<tr>
  <td>${escHtml(r.u.code)}</td>
  <td>${escHtml(r.u.fullName)}</td>
  <td>${r.level ? `<span class="badge" style="background:${r.level.color}22;color:${r.level.color}">${escHtml(r.level.name)}</span>` : '—'}</td>
  <td>${renderBadge(r.status)}</td>
  <td>${r.ft?.submittedAt ? formatDateTime(r.ft.submittedAt) : '—'}</td>
</tr>`).join('')}
</tbody></table></div>`;

  App.openModal(`Danh sách đăng ký — ${p.name}`, body, [
    { label: 'Đóng', cls: 'btn-secondary', action: () => App.closeModal() },
  ], 'modal-lg');
}

return {
  render, openForm, submitPeriodForm, addExemptDate, removeExemptDate,
  openPeriod, lockPeriod, reopenPeriod, copyPeriod, viewRegistrations,
};
})();

/* ═══════════════════════════════════════════════════════════
   5.4 LỊCH LÀM VIỆC (Calendar)
═══════════════════════════════════════════════════════════ */
const AdminCalendar = (() => {
let _view = 'month', _currentDate = new Date();

function render() {
  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Lịch làm việc</h1>
  </div>
  <div class="page-actions">
    <button class="btn btn-primary" onclick="AdminShift.openForm()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      Thêm ca
    </button>
  </div>
</div>
<div class="card">
  <div class="card-body">
    <div class="calendar-nav">
      <div style="display:flex;gap:8px;align-items:center">
        <button class="btn btn-secondary btn-sm" onclick="AdminCalendar.prev()">&#8249;</button>
        <span class="calendar-title" id="cal-title"></span>
        <button class="btn btn-secondary btn-sm" onclick="AdminCalendar.next()">&#8250;</button>
        <button class="btn btn-ghost btn-sm" onclick="AdminCalendar.goToday()">Hôm nay</button>
      </div>
      <div class="calendar-view-tabs">
        <button class="cal-view-btn ${_view==='month'?'active':''}" onclick="AdminCalendar.setView('month')">Tháng</button>
        <button class="cal-view-btn ${_view==='week' ?'active':''}" onclick="AdminCalendar.setView('week')">Tuần</button>
        <button class="cal-view-btn ${_view==='day'  ?'active':''}" onclick="AdminCalendar.setView('day')">Ngày</button>
      </div>
    </div>
    <div id="cal-body"></div>
  </div>
</div>`;
}

function init() {
  _renderView();
}

function _renderView() {
  const titleEl = document.getElementById('cal-title');
  const bodyEl  = document.getElementById('cal-body');
  if (!bodyEl) return;

  if (_view === 'month') {
    if (titleEl) titleEl.textContent = `Tháng ${_currentDate.getMonth()+1}/${_currentDate.getFullYear()}`;
    bodyEl.innerHTML = _renderMonth();
  } else if (_view === 'week') {
    const wStart = _weekStart(_currentDate);
    const wEnd   = new Date(wStart); wEnd.setDate(wEnd.getDate()+6);
    if (titleEl) titleEl.textContent = `Tuần: ${_fmtD(wStart)} – ${_fmtD(wEnd)}`;
    bodyEl.innerHTML = _renderWeek(wStart);
  } else {
    if (titleEl) titleEl.textContent = _fmtD(_currentDate);
    bodyEl.innerHTML = _renderDay(_fmtDate(_currentDate));
  }
}

function _fmtDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
}
function _fmtD(d) {
  return `${String(d.getDate()).padStart(2,'0')}/${String(d.getMonth()+1).padStart(2,'0')}/${d.getFullYear()}`;
}

function _renderMonth() {
  const y = _currentDate.getFullYear(), m = _currentDate.getMonth();
  const firstDay = new Date(y, m, 1);
  const lastDay  = new Date(y, m+1, 0);
  const startDOW = firstDay.getDay(); // 0=CN
  const todayStr = _fmtDate(new Date());

  const shifts   = State.get.shifts();
  const asgns    = State.get.assignments();

  let cells = '';
  // Header
  ['CN','T2','T3','T4','T5','T6','T7'].forEach(d => {
    cells += `<div class="cal-day-header">${d}</div>`;
  });
  // Empty cells before first day
  for (let i = 0; i < startDOW; i++) cells += `<div class="cal-day other-month"></div>`;
  // Days
  for (let d = 1; d <= lastDay.getDate(); d++) {
    const dateStr = `${y}-${String(m+1).padStart(2,'0')}-${String(d).padStart(2,'0')}`;
    const dayShifts = shifts.filter(s => s.date === dateStr).slice(0,3);
    const isToday   = dateStr === todayStr;
    cells += `<div class="cal-day ${isToday?'today':''}" onclick="AdminCalendar.selectDay('${dateStr}')">
      <div class="cal-day-num">${d}</div>
      <div class="cal-events">
        ${dayShifts.map(s => {
          const filled = asgns.filter(a => a.shiftId === s.id).length;
          const cls = s.status === 'published' ? (filled >= s.totalRequired ? 'full' : 'understaffed') : 'draft';
          return `<div class="cal-event ${cls}" title="${escHtml(s.name)}">${escHtml(s.name)}</div>`;
        }).join('')}
        ${shifts.filter(s => s.date === dateStr).length > 3 ? `<div class="cal-more">+${shifts.filter(s=>s.date===dateStr).length-3} ca</div>` : ''}
      </div>
    </div>`;
  }
  // Trailing cells
  const total = startDOW + lastDay.getDate();
  for (let i = total; i % 7 !== 0; i++) cells += `<div class="cal-day other-month"></div>`;

  return `<div class="calendar-grid">${cells}</div>`;
}

function _renderWeek(wStart) {
  const days = [];
  for (let i = 0; i < 7; i++) {
    const d = new Date(wStart); d.setDate(d.getDate()+i);
    days.push({ date: d, str: _fmtDate(d) });
  }
  const shifts = State.get.shifts();
  const asgns  = State.get.assignments();
  const dowNames = ['CN','T2','T3','T4','T5','T6','T7'];
  const todayStr = _fmtDate(new Date());

  let ths = '<th style="width:60px">Giờ</th>';
  days.forEach(d => {
    const isToday = d.str === todayStr;
    ths += `<th ${isToday?'style="background:#eff6ff"':''}>${dowNames[d.date.getDay()]} ${d.date.getDate()}/${d.date.getMonth()+1}</th>`;
  });

  let tds = '<td class="text-muted text-xs text-secondary" style="padding:8px;vertical-align:top">Ca</td>';
  days.forEach(d => {
    const dayShifts = shifts.filter(s => s.date === d.str);
    tds += `<td style="vertical-align:top">
      ${dayShifts.map(s => {
        const filled = asgns.filter(a => a.shiftId === s.id).length;
        const cls = s.status === 'published' ? (filled >= s.totalRequired ? 'full' : 'understaffed') : 'draft';
        return `<div class="week-shift-card cal-event ${cls}" onclick="AdminShift.viewDetail('${s.id}')" style="cursor:pointer">
          <strong>${escHtml(s.name)}</strong><br>
          <span style="font-size:0.714rem">${s.startTime}–${s.endTime}</span><br>
          <span style="font-size:0.714rem">${filled}/${s.totalRequired} người</span>
        </div>`;
      }).join('')}
      <button class="btn btn-ghost btn-xs" onclick="AdminShift.openForm(null,'${d.str}')" style="width:100%;margin-top:4px">+ Ca</button>
    </td>`;
  });

  return `<div class="week-view"><table class="week-table"><thead><tr>${ths}</tr></thead><tbody><tr>${tds}</tr></tbody></table></div>`;
}

function _renderDay(dateStr) {
  const shifts = State.get.shifts().filter(s => s.date === dateStr);
  const asgns  = State.get.assignments();
  if (!shifts.length) return `<div class="empty-state">
    <div class="empty-state-title">Không có ca nào ngày ${formatDate(dateStr)}</div>
    <button class="btn btn-primary" onclick="AdminShift.openForm(null,'${dateStr}')">Thêm ca cho ngày này</button>
  </div>`;
  return shifts.sort((a,b)=>a.startTime.localeCompare(b.startTime)).map(s => {
    const filled = asgns.filter(a => a.shiftId === s.id).length;
    return `<div class="shift-card">
      <div class="shift-card-header">
        <span class="shift-card-title">${escHtml(s.name)}</span>
        <div style="display:flex;gap:8px;align-items:center">
          ${renderBadge(s.status)}
          <button class="btn btn-secondary btn-sm" onclick="AdminShift.openForm('${s.id}')">Sửa</button>
          <button class="btn btn-ghost btn-sm" onclick="AdminShift.viewDetail('${s.id}')">Chi tiết</button>
        </div>
      </div>
      <div class="shift-card-body">
        <div class="shift-stat"><span class="shift-stat-label">Giờ</span><span class="shift-stat-val">${s.startTime}–${s.endTime}</span></div>
        <div class="shift-stat"><span class="shift-stat-label">Thời lượng</span><span class="shift-stat-val">${getShiftDurationLabel(s.startTime,s.endTime)}</span></div>
        <div class="shift-stat"><span class="shift-stat-label">Nhân sự</span><span class="shift-stat-val ${filled<s.totalRequired?'text-danger':'text-success'}">${filled}/${s.totalRequired}</span></div>
        <div class="shift-stat"><span class="shift-stat-label">Chế độ</span><span class="shift-stat-val">${{level:'Theo Level',user:'Theo User',combined:'Kết hợp'}[s.mode]||s.mode}</span></div>
      </div>
    </div>`;
  }).join('');
}

function _weekStart(d) {
  const r = new Date(d);
  const dow = r.getDay();
  r.setDate(r.getDate() - (dow === 0 ? 6 : dow - 1));
  return r;
}

function selectDay(dateStr) {
  _view = 'day';
  _currentDate = new Date(dateStr);
  _renderView();
}
function setView(v) { _view = v; _renderView(); }
function prev() {
  if (_view==='month') _currentDate.setMonth(_currentDate.getMonth()-1);
  else if (_view==='week') _currentDate.setDate(_currentDate.getDate()-7);
  else _currentDate.setDate(_currentDate.getDate()-1);
  _renderView();
}
function next() {
  if (_view==='month') _currentDate.setMonth(_currentDate.getMonth()+1);
  else if (_view==='week') _currentDate.setDate(_currentDate.getDate()+7);
  else _currentDate.setDate(_currentDate.getDate()+1);
  _renderView();
}
function goToday() { _currentDate = new Date(); _renderView(); }

return { render, init, prev, next, goToday, setView, selectDay };
})();

/* ═══════════════════════════════════════════════════════════
   5.5 QUẢN LÝ CA LÀM VIỆC
═══════════════════════════════════════════════════════════ */
const AdminShift = (() => {
let _shiftDateFilter = '', _shiftStatusFilter = '';

function render() {
  const shifts = _getFilteredShifts();
  const asgns  = State.get.assignments();
  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Quản lý ca làm việc</h1>
    <p class="page-subtitle">${shifts.length} ca</p>
  </div>
  <div class="page-actions">
    <button class="btn btn-primary" onclick="AdminShift.openForm()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      Thêm ca
    </button>
  </div>
</div>
<div class="card">
  <div class="table-toolbar">
    <input type="date" class="form-input" style="width:auto" value="${_shiftDateFilter}"
           onchange="AdminShift.filterShifts('date', this.value)" title="Lọc theo ngày">
    <select class="form-input filter-select" onchange="AdminShift.filterShifts('status', this.value)">
      <option value="">Tất cả trạng thái</option>
      <option value="not_scheduled" ${_shiftStatusFilter==='not_scheduled'?'selected':''}>Chưa phân lịch</option>
      <option value="draft"         ${_shiftStatusFilter==='draft'        ?'selected':''}>Nháp</option>
      <option value="published"     ${_shiftStatusFilter==='published'    ?'selected':''}>Đã công bố</option>
    </select>
    <button class="btn btn-ghost btn-sm" onclick="AdminShift.clearFilters()">Xóa lọc</button>
  </div>
  <div class="table-container">
    <table class="data-table">
      <thead><tr>
        <th>Ngày</th><th>Tên ca</th><th>Giờ</th><th>Thời lượng</th>
        <th>Chế độ</th><th>Nhân sự</th><th>Trạng thái</th>
        <th class="actions-cell">Thao tác</th>
      </tr></thead>
      <tbody>
      ${shifts.length ? shifts.map(s => {
        const filled = asgns.filter(a => a.shiftId === s.id).length;
        const modeLabel = {level:'Level',user:'User',combined:'Kết hợp'}[s.mode]||s.mode;
        const staffCls  = filled < s.totalRequired ? 'text-danger' : 'text-success';
        return `<tr>
          <td>${formatDate(s.date)}</td>
          <td class="font-medium">${escHtml(s.name)}</td>
          <td class="no-wrap">${s.startTime}–${s.endTime}</td>
          <td>${getShiftDurationLabel(s.startTime,s.endTime)}</td>
          <td><span class="badge badge-draft">${modeLabel}</span></td>
          <td class="${staffCls} font-medium">${filled}/${s.totalRequired}</td>
          <td>${renderBadge(s.status)}</td>
          <td class="actions-cell">
            <button class="btn btn-secondary btn-sm" onclick="AdminShift.openForm('${s.id}')">Sửa</button>
            <button class="btn btn-ghost btn-sm" onclick="AdminShift.viewDetail('${s.id}')">Chi tiết</button>
            <button class="btn btn-ghost btn-sm" onclick="AdminShift.copyShift('${s.id}')">Sao chép</button>
            ${s.status !== 'published' ? `<button class="btn btn-danger btn-sm" onclick="AdminShift.deleteShift('${s.id}')">Xóa</button>` : ''}
          </td>
        </tr>`;
      }).join('') : `<tr><td colspan="8" class="table-empty">Không có ca nào.</td></tr>`}
      </tbody>
    </table>
  </div>
</div>`;
}

function _getFilteredShifts() {
  return State.get.shifts().filter(s => {
    if (_shiftDateFilter   && s.date !== _shiftDateFilter)     return false;
    if (_shiftStatusFilter && s.status !== _shiftStatusFilter) return false;
    return true;
  }).sort((a,b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime));
}

function filterShifts(type, val) {
  if (type === 'date')   _shiftDateFilter   = val;
  if (type === 'status') _shiftStatusFilter = val;
  document.getElementById('main-content').innerHTML = render();
}
function clearFilters() { _shiftDateFilter = ''; _shiftStatusFilter = ''; document.getElementById('main-content').innerHTML = render(); }

function openForm(shiftId = null, prefillDate = null) {
  const shift  = shiftId ? State.get.shiftById(shiftId) : null;
  const isNew  = !shift;
  const levels = State.get.levels();
  const mode   = shift?.mode || 'level';

  const body = `
<form id="shift-form" novalidate>
  <div class="form-section">
    <div class="form-section-title">Thông tin ca</div>
    <div class="form-row-2">
      <div class="form-group">
        <label class="form-label">Tên ca <span class="required">*</span></label>
        <input type="text" name="name" class="form-input" value="${escHtml(shift?.name||'')}" placeholder="Ca sáng">
        <span class="field-error" data-err="name"></span>
      </div>
      <div class="form-group">
        <label class="form-label">Ngày làm <span class="required">*</span></label>
        <input type="date" name="date" class="form-input" value="${shift?.date || prefillDate || ''}">
        <span class="field-error" data-err="date"></span>
      </div>
    </div>
    <div class="form-row-3">
      <div class="form-group">
        <label class="form-label">Giờ bắt đầu <span class="required">*</span></label>
        <input type="time" name="startTime" class="form-input" value="${shift?.startTime||'07:00'}">
        <span class="field-error" data-err="startTime"></span>
      </div>
      <div class="form-group">
        <label class="form-label">Giờ kết thúc <span class="required">*</span></label>
        <input type="time" name="endTime" class="form-input" value="${shift?.endTime||'15:00'}">
        <span class="field-error" data-err="endTime"></span>
      </div>
      <div class="form-group">
        <label class="form-label">Tổng nhu cầu nhân sự <span class="required">*</span></label>
        <input type="number" name="totalRequired" class="form-input" value="${shift?.totalRequired||4}" min="1" oninput="AdminShift.updateStaffSummary()">
        <span class="field-error" data-err="totalRequired"></span>
      </div>
    </div>
    <div class="form-group">
      <label class="form-label">Ghi chú</label>
      <textarea name="note" class="form-input" rows="2">${escHtml(shift?.note||'')}</textarea>
    </div>
  </div>

  <div class="form-section">
    <div class="form-section-title">Cấu hình nhân sự</div>
    <div class="form-group mb-4">
      <label class="form-label">Chế độ cấu hình <span class="required">*</span></label>
      <div style="display:flex;gap:12px;flex-wrap:wrap">
        ${['level','user','combined'].map(m => `
          <label class="form-check">
            <input type="radio" name="mode" value="${m}" ${mode===m?'checked':''} onchange="AdminShift.switchMode('${m}')">
            <span class="form-check-label">${{level:'A – Theo Level',user:'B – Theo User cụ thể',combined:'C – Kết hợp'}[m]}</span>
          </label>`).join('')}
      </div>
      <span class="field-error" data-err="mode"></span>
    </div>

    <div id="staff-config-area">
      ${_renderStaffConfigArea(mode, shift, levels)}
    </div>
  </div>

  <div id="staff-summary" class="summary-box">
    ${_renderStaffSummary(shift)}
  </div>
  <span class="field-error" data-err="staffConfig"></span>
  <input type="hidden" name="id" value="${shift?.id||''}">
</form>`;

  App.openModal(isNew ? 'Thêm ca làm việc' : 'Chỉnh sửa ca', body, [
    { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
    { label: isNew ? 'Tạo ca' : 'Lưu thay đổi', cls: 'btn-primary', action: () => submitShiftForm(isNew) },
  ], 'modal-xl');
}

function _renderStaffConfigArea(mode, shift, levels) {
  const levelReqs = shift?.levelRequirements || [];
  const mandatory = shift?.mandatoryUsers    || [];
  const preferred = shift?.preferredUsers    || [];

  let html = '';
  if (mode === 'level') {
    html = _renderLevelConfig(levelReqs, levels);
  } else if (mode === 'user') {
    html = _renderUserConfig(mandatory, preferred);
  } else {
    html = _renderLevelConfig(levelReqs, levels) + '<hr class="divider">' + _renderUserConfig(mandatory, preferred);
  }
  return html;
}

function _renderLevelConfig(levelReqs, levels) {
  return `
<div class="form-section-title" style="font-size:0.857rem">Yêu cầu theo Level</div>
<div id="level-req-list">
  ${levelReqs.map((r,i) => _levelReqRow(r, i, levels)).join('')}
  ${!levelReqs.length ? `<p class="text-muted text-sm">Chưa có yêu cầu Level nào.</p>` : ''}
</div>
<button type="button" class="btn btn-secondary btn-sm mt-2" onclick="AdminShift.addLevelReq()">+ Thêm Level</button>`;
}

function _levelReqRow(r, i, levels) {
  levels = levels || State.get.levels();
  return `<div class="level-config-row" data-level-idx="${i}">
    <select name="level_id_${i}" class="form-input" style="width:140px" onchange="AdminShift.updateStaffSummary()">
      <option value="">-- Level --</option>
      ${levels.map(l => `<option value="${l.id}" ${r.levelId===l.id?'selected':''}>${escHtml(l.name)}</option>`).join('')}
    </select>
    <span style="color:var(--text-muted);font-size:0.857rem">Số lượng:</span>
    <input type="number" name="level_count_${i}" class="form-input" style="width:70px" value="${r.count||1}" min="1" oninput="AdminShift.updateStaffSummary()">
    <button type="button" class="btn btn-danger btn-xs" onclick="AdminShift.removeLevelReq(${i})">Xóa</button>
  </div>`;
}

function _renderUserConfig(mandatory, preferred) {
  const allUsers = State.get.activeUsers();
  return `
<div class="form-section-title" style="font-size:0.857rem">Nhân viên được chỉ định</div>
<div id="user-assign-list">
  ${[...mandatory.map(u=>({...u,_type:'mandatory'})), ...preferred.map(u=>({...u,_type:'preferred'}))].map((u,i) => _userAssignRow(u, i)).join('')}
</div>
<div style="display:flex;gap:8px;margin-top:8px;align-items:center;flex-wrap:wrap">
  <select id="user-pick-select" class="form-input" style="width:200px">
    <option value="">-- Chọn nhân viên --</option>
    ${allUsers.map(u => {
      const lvl = State.get.levelById(u.levelId);
      return `<option value="${u.id}">${escHtml(u.fullName)} (${lvl?lvl.name:'—'})</option>`;
    }).join('')}
  </select>
  <select id="user-pick-type" class="form-input" style="width:140px">
    <option value="mandatory">Bắt buộc</option>
    <option value="preferred">Ưu tiên</option>
  </select>
  <button type="button" class="btn btn-secondary btn-sm" onclick="AdminShift.addUserAssign()">+ Thêm</button>
</div>`;
}

function _userAssignRow(u, i) {
  const user  = State.get.userById(u.userId);
  const level = user ? State.get.levelById(user.levelId) : null;
  const typeLabel = { mandatory: 'Bắt buộc', preferred: 'Ưu tiên', substitutable: 'Thay thế được' };
  return `<div class="level-config-row" data-user-idx="${i}" data-user-id="${u.userId}" data-user-type="${u._type||u.type}">
    <span class="font-medium text-sm">${escHtml(user?.fullName||u.userId)}</span>
    ${level ? `<span class="badge" style="background:${level.color}22;color:${level.color};font-size:0.714rem">${escHtml(level.name)}</span>` : ''}
    <select class="form-input" style="width:130px" onchange="AdminShift.changeUserType(${i}, this.value)">
      ${['mandatory','preferred','substitutable'].map(t =>
        `<option value="${t}" ${(u._type||u.type)===t?'selected':''}>${typeLabel[t]}</option>`
      ).join('')}
    </select>
    <button type="button" class="btn btn-danger btn-xs" onclick="AdminShift.removeUserAssign(${i})">Xóa</button>
  </div>`;
}

let _levelReqs = [], _mandatoryUsers = [], _preferredUsers = [];

function switchMode(mode) {
  const form   = document.getElementById('shift-form');
  const levels = State.get.levels();
  // Đọc lại dữ liệu hiện tại trước khi re-render
  _collectStaffData();
  document.getElementById('staff-config-area').innerHTML = _renderStaffConfigArea(mode, {
    levelRequirements: _levelReqs,
    mandatoryUsers:    _mandatoryUsers,
    preferredUsers:    _preferredUsers,
  }, levels);
  updateStaffSummary();
}

function _collectStaffData() {
  const form = document.getElementById('shift-form');
  if (!form) return;
  // Collect level rows
  const levelRows = form.querySelectorAll('[data-level-idx]');
  _levelReqs = [];
  levelRows.forEach(row => {
    const i = row.dataset.levelIdx;
    const lvlId = form.querySelector(`[name="level_id_${i}"]`)?.value;
    const cnt   = parseInt(form.querySelector(`[name="level_count_${i}"]`)?.value) || 1;
    if (lvlId) _levelReqs.push({ levelId: lvlId, count: cnt });
  });
  // Collect user rows
  const userRows = form.querySelectorAll('[data-user-idx]');
  _mandatoryUsers = [];
  _preferredUsers = [];
  userRows.forEach((row, i) => {
    const uid  = row.dataset.userId;
    const type = row.querySelector('select')?.value || row.dataset.userType;
    if (!uid) return;
    if (type === 'mandatory') _mandatoryUsers.push({ userId: uid, type: 'mandatory' });
    else _preferredUsers.push({ userId: uid, type });
  });
}

function addLevelReq() {
  _collectStaffData();
  _levelReqs.push({ levelId: '', count: 1 });
  const levels = State.get.levels();
  document.getElementById('level-req-list').innerHTML = _levelReqs.map((r,i) => _levelReqRow(r, i, levels)).join('');
  updateStaffSummary();
}

function removeLevelReq(idx) {
  _collectStaffData();
  _levelReqs.splice(idx, 1);
  const levels = State.get.levels();
  document.getElementById('level-req-list').innerHTML = _levelReqs.length
    ? _levelReqs.map((r,i) => _levelReqRow(r, i, levels)).join('')
    : '<p class="text-muted text-sm">Chưa có yêu cầu Level nào.</p>';
  updateStaffSummary();
}

function addUserAssign() {
  const uid  = document.getElementById('user-pick-select')?.value;
  const type = document.getElementById('user-pick-type')?.value;
  if (!uid) { App.toast('Vui lòng chọn nhân viên.', 'warning'); return; }
  _collectStaffData();
  const allAssigned = [..._mandatoryUsers, ..._preferredUsers];
  if (allAssigned.find(u => u.userId === uid)) { App.toast('Nhân viên đã được thêm.', 'warning'); return; }
  if (type === 'mandatory') _mandatoryUsers.push({ userId: uid, type: 'mandatory' });
  else _preferredUsers.push({ userId: uid, type });
  const all = [..._mandatoryUsers.map(u=>({...u,_type:'mandatory'})), ..._preferredUsers.map(u=>({...u,_type:u.type}))];
  document.getElementById('user-assign-list').innerHTML = all.map((u,i) => _userAssignRow(u, i)).join('');
  updateStaffSummary();
}

function removeUserAssign(idx) {
  _collectStaffData();
  const all = [..._mandatoryUsers.map(u=>({...u,_type:'mandatory'})), ..._preferredUsers.map(u=>({...u,_type:u.type}))];
  all.splice(idx, 1);
  _mandatoryUsers = all.filter(u=>u._type==='mandatory').map(u=>({userId:u.userId,type:'mandatory'}));
  _preferredUsers = all.filter(u=>u._type!=='mandatory').map(u=>({userId:u.userId,type:u._type}));
  document.getElementById('user-assign-list').innerHTML = all.map((u,i) => _userAssignRow(u, i)).join('');
  updateStaffSummary();
}

function changeUserType(idx, newType) {
  _collectStaffData();
  const all = [..._mandatoryUsers.map(u=>({...u,_type:'mandatory'})), ..._preferredUsers.map(u=>({...u,_type:u.type}))];
  if (all[idx]) { all[idx]._type = newType; all[idx].type = newType; }
  _mandatoryUsers = all.filter(u=>u._type==='mandatory').map(u=>({userId:u.userId,type:'mandatory'}));
  _preferredUsers = all.filter(u=>u._type!=='mandatory').map(u=>({userId:u.userId,type:u._type}));
  updateStaffSummary();
}

function _renderStaffSummary(shift) {
  const form = document.getElementById('shift-form');
  const total = parseInt(form?.querySelector('[name="totalRequired"]')?.value || shift?.totalRequired || 0);
  _collectStaffData();
  const levelTotal = _levelReqs.reduce((s, r) => s + (parseInt(r.count)||0), 0);
  const mandCount  = _mandatoryUsers.length;
  const prefCount  = _preferredUsers.length;
  const mode       = form?.querySelector('[name="mode"]:checked')?.value || shift?.mode || 'level';

  return `<div class="summary-box-title">Tóm tắt cấu hình nhân sự</div>
    <div class="summary-row"><span class="s-label">Tổng nhu cầu</span><span class="s-value">${total} người</span></div>
    ${mode !== 'level' ? `
    <div class="summary-row"><span class="s-label">User bắt buộc</span><span class="s-value">${mandCount} người</span></div>
    <div class="summary-row"><span class="s-label">User ưu tiên</span><span class="s-value">${prefCount} người</span></div>` : ''}
    ${mode !== 'user' ? `
    <div class="summary-row"><span class="s-label">Vị trí theo Level</span><span class="s-value">${levelTotal} người</span></div>` : ''}`;
}

function updateStaffSummary() {
  const el = document.getElementById('staff-summary');
  if (el) el.innerHTML = _renderStaffSummary(null);
}

function submitShiftForm(isNew) {
  const form = document.getElementById('shift-form');
  if (!form) return;
  clearFormErrors(form);
  _collectStaffData();

  const mode = form.querySelector('[name="mode"]:checked')?.value;
  const data = {
    id:               form.querySelector('[name="id"]').value || null,
    name:             form.querySelector('[name="name"]').value.trim(),
    date:             form.querySelector('[name="date"]').value,
    startTime:        form.querySelector('[name="startTime"]').value,
    endTime:          form.querySelector('[name="endTime"]').value,
    totalRequired:    parseInt(form.querySelector('[name="totalRequired"]').value),
    mode,
    levelRequirements: _levelReqs,
    mandatoryUsers:    _mandatoryUsers,
    preferredUsers:    _preferredUsers,
    note:             form.querySelector('[name="note"]').value.trim(),
    status:           isNew ? 'not_scheduled' : (State.get.shiftById(form.querySelector('[name="id"]').value)?.status || 'not_scheduled'),
  };

  const result = Validate.shiftForm(data);
  if (!result.valid) { showFieldErrors(form, result.errors); return; }

  // Check overlap với ca đã công bố cùng ngày
  const existingOnDay = State.get.shiftsByDate(data.date).filter(s => s.id !== data.id && s.status === 'published');
  if (existingOnDay.length) {
    // Chỉ cảnh báo, không chặn
    App.toast(`Lưu ý: ngày ${formatDate(data.date)} đã có ${existingOnDay.length} ca đã công bố.`, 'info');
  }

  State.mut.saveShift(data);
  logHistory('shift_save', isNew ? 'Tạo ca làm việc' : 'Cập nhật ca làm việc',
    `${data.name} (${data.date})`, State.get.currentUser()?.id || 'admin');

  App.closeModal();
  App.toast(isNew ? 'Đã tạo ca.' : 'Đã cập nhật ca.', 'success');
  App.navigate('shifts');
}

function viewDetail(shiftId) {
  const shift = State.get.shiftById(shiftId);
  if (!shift) return;
  const asgns = State.get.assignmentsByShift(shiftId);
  const users = asgns.map(a => ({ a, user: State.get.userById(a.userId) }));
  const levels = State.get.levels();

  const modeLabel = { level:'Theo Level', user:'Theo User', combined:'Kết hợp' }[shift.mode]||shift.mode;
  const body = `
<div class="detail-list mb-4">
  <div class="detail-item"><span class="detail-label">Tên ca</span><span class="detail-value font-medium">${escHtml(shift.name)}</span></div>
  <div class="detail-item"><span class="detail-label">Ngày</span><span class="detail-value">${formatDate(shift.date)}</span></div>
  <div class="detail-item"><span class="detail-label">Giờ</span><span class="detail-value">${shift.startTime} – ${shift.endTime} (${getShiftDurationLabel(shift.startTime,shift.endTime)})</span></div>
  <div class="detail-item"><span class="detail-label">Chế độ</span><span class="detail-value">${modeLabel}</span></div>
  <div class="detail-item"><span class="detail-label">Nhân sự</span><span class="detail-value">${asgns.length}/${shift.totalRequired} (${shift.totalRequired-asgns.length > 0 ? `thiếu ${shift.totalRequired-asgns.length}` : 'đủ'})</span></div>
  <div class="detail-item"><span class="detail-label">Trạng thái</span><span class="detail-value">${renderBadge(shift.status)}</span></div>
</div>
${shift.levelRequirements?.length ? `
<div class="mb-4"><div class="form-section-title">Yêu cầu Level</div>
  ${shift.levelRequirements.map(r => { const l = State.get.levelById(r.levelId); return `<div class="level-config-row"><span>${l?escHtml(l.name):r.levelId}</span><span>${r.count} người</span></div>`; }).join('')}
</div>` : ''}
<div class="form-section-title">Danh sách nhân viên được phân (${asgns.length})</div>
${users.length ? `<table class="data-table"><thead><tr><th>Họ tên</th><th>Level</th><th>Phân bởi</th><th>Thao tác</th></tr></thead><tbody>
  ${users.map(({a, user}) => {
    const lvl = user ? State.get.levelById(user.levelId) : null;
    return `<tr>
      <td>${escHtml(user?.fullName||a.userId)}</td>
      <td>${lvl ? `<span class="badge" style="background:${lvl.color}22;color:${lvl.color}">${escHtml(lvl.name)}</span>` : '—'}</td>
      <td>${a.assignedBy === 'auto' ? 'Tự động' : 'Admin'}</td>
      <td><button class="btn btn-danger btn-xs" onclick="AdminShift.removeAssignment('${a.id}','${shiftId}')">Xóa</button></td>
    </tr>`;
  }).join('')}
</tbody></table>` : '<p class="text-muted text-sm">Chưa có nhân viên được phân.</p>'}
<div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap">
  <button class="btn btn-primary btn-sm" onclick="App.closeModal();AdminShift.openManualAssign('${shiftId}')">Phân công thủ công</button>
  ${shift.status !== 'published' ? `<button class="btn btn-success btn-sm" onclick="App.closeModal();AdminShift.publishSingle('${shiftId}')">Công bố ca này</button>` : ''}
</div>`;

  App.openModal(`Chi tiết ca: ${shift.name}`, body, [
    { label: 'Đóng', cls: 'btn-secondary', action: () => App.closeModal() },
    { label: 'Chỉnh sửa', cls: 'btn-primary', action: () => { App.closeModal(); openForm(shiftId); } },
  ], 'modal-lg');
}

function removeAssignment(asgnId, shiftId) {
  App.confirm('Xóa phân công này?', '', () => {
    const result = Scheduler.removeAssignment(asgnId, '');
    if (result.success) {
      App.toast('Đã xóa phân công.', 'success');
      App.closeModal();
      viewDetail(shiftId);
    } else {
      App.toast(result.error, 'error');
    }
  }, { type: 'danger' });
}

function openManualAssign(shiftId) {
  const shift   = State.get.shiftById(shiftId);
  if (!shift) return;
  const periods = State.get.periods();
  const users   = State.get.activeUsers();
  const body = `
<div class="alert alert-info mb-4">
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
  <div>Phân công thủ công sẽ kiểm tra điều kiện an toàn. Nếu vi phạm sẽ hiện lỗi cụ thể.</div>
</div>
<form id="manual-assign-form" novalidate>
  <div class="form-group mb-3">
    <label class="form-label">Nhân viên <span class="required">*</span></label>
    <select name="userId" class="form-input">
      <option value="">-- Chọn nhân viên --</option>
      ${users.map(u => { const lvl = State.get.levelById(u.levelId); return `<option value="${u.id}">${escHtml(u.fullName)} (${lvl?lvl.name:'—'})</option>`; }).join('')}
    </select>
  </div>
  <div class="form-group mb-3">
    <label class="form-label">Kỳ đăng ký</label>
    <select name="periodId" class="form-input">
      ${periods.map(p => `<option value="${p.id}">${escHtml(p.name)}</option>`).join('')}
    </select>
  </div>
  <div class="form-group mb-3">
    <label class="form-label">Lý do</label>
    <textarea name="reason" class="form-input" rows="2" placeholder="Nhập lý do phân công thủ công..."></textarea>
  </div>
  <div id="manual-assign-errors" style="color:var(--danger);font-size:0.857rem"></div>
</form>`;

  App.openModal(`Phân công thủ công: ${shift.name}`, body, [
    { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
    { label: 'Phân công', cls: 'btn-primary', action: () => submitManualAssign(shiftId) },
  ]);
}

function submitManualAssign(shiftId) {
  const form = document.getElementById('manual-assign-form');
  if (!form) return;
  const userId   = form.querySelector('[name="userId"]').value;
  const periodId = form.querySelector('[name="periodId"]').value;
  const reason   = form.querySelector('[name="reason"]').value.trim();
  const errEl    = document.getElementById('manual-assign-errors');

  if (!userId) { if (errEl) errEl.textContent = 'Vui lòng chọn nhân viên.'; return; }

  const shift = State.get.shiftById(shiftId);
  const result = Scheduler.manualAssign(userId, shiftId, periodId, reason, true);

  if (!result.success) {
    const msgs = result.errors || [result.error];
    if (errEl) errEl.innerHTML = msgs.map(m => `<div>• ${escHtml(m)}</div>`).join('');
    return;
  }

  if (result.warnings?.length) {
    App.toast('Phân công thành công nhưng có cảnh báo: ' + result.warnings[0], 'warning');
  } else {
    App.toast('Đã phân công thành công.', 'success');
  }
  App.closeModal();
}

function publishSingle(shiftId) {
  App.confirm('Công bố ca này?', 'Nhân viên sẽ thấy ca trong lịch của họ.', () => {
    const result = Scheduler.publishShifts([shiftId], false);
    if (result.success) {
      App.toast('Đã công bố ca.', 'success');
    } else {
      App.confirm(
        'Ca chưa đủ nhân sự',
        result.errors.join('\n') + '\n\nBạn có muốn công bố dù thiếu người không?',
        () => {
          Scheduler.publishShifts([shiftId], true);
          App.toast('Đã công bố ca (thiếu người).', 'warning');
          App.navigate('shifts');
        },
        { type: 'warn', okLabel: 'Vẫn công bố' }
      );
    }
    App.navigate('shifts');
  });
}

function copyShift(shiftId) {
  const s = State.get.shiftById(shiftId);
  if (!s) return;
  openForm(null, s.date);
  // Pre-fill sau khi modal mở
  setTimeout(() => {
    const form = document.getElementById('shift-form');
    if (!form) return;
    form.querySelector('[name="name"]').value = s.name + ' (Bản sao)';
    form.querySelector('[name="startTime"]').value = s.startTime;
    form.querySelector('[name="endTime"]').value   = s.endTime;
    form.querySelector('[name="totalRequired"]').value = s.totalRequired;
    const modeRadio = form.querySelector(`[name="mode"][value="${s.mode}"]`);
    if (modeRadio) { modeRadio.checked = true; AdminShift.switchMode(s.mode); }
    _levelReqs    = JSON.parse(JSON.stringify(s.levelRequirements || []));
    _mandatoryUsers = JSON.parse(JSON.stringify(s.mandatoryUsers || []));
    _preferredUsers = JSON.parse(JSON.stringify(s.preferredUsers || []));
  }, 100);
}

function deleteShift(shiftId) {
  const s = State.get.shiftById(shiftId);
  if (!s) return;
  if (s.status === 'published') { App.toast('Không thể xóa ca đã công bố.', 'error'); return; }
  App.confirm(`Xóa ca "${s.name}" (${formatDate(s.date)})?`, 'Thao tác không thể hoàn tác.', () => {
    State.mut.deleteShift(shiftId);
    logHistory('shift_delete', 'Xóa ca', `${s.name} (${s.date})`, State.get.currentUser()?.id || 'admin');
    App.toast('Đã xóa ca.', 'success');
    App.navigate('shifts');
  }, { type: 'danger' });
}

return {
  render, filterShifts, clearFilters, openForm, submitShiftForm,
  viewDetail, switchMode, addLevelReq, removeLevelReq,
  addUserAssign, removeUserAssign, changeUserType, updateStaffSummary,
  removeAssignment, openManualAssign, submitManualAssign,
  publishSingle, copyShift, deleteShift,
};
})();
