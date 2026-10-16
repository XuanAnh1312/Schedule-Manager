/**
 * user.js — Toàn bộ màn hình User (4 màn hình)
 * 13.1 Tổng quan cá nhân
 * 13.2 Đăng ký thời gian rảnh
 * 13.3 Lịch làm việc cá nhân
 * 13.4 Thông tin cá nhân
 */

const UserView = (() => {

/* ═══════════════════════════════════════════════════════════
   13.1 TỔNG QUAN CÁ NHÂN
═══════════════════════════════════════════════════════════ */
function renderOverview() {
  const me = State.get.currentUser();
  if (!me) return '<p class="text-muted">Chưa đăng nhập.</p>';
  const user   = State.get.userById(me.id);
  const level  = user ? State.get.levelById(user.levelId) : null;
  const periods = State.get.periods();
  const activePeriod = periods.find(p => p.status === 'open') || periods.find(p => p.status === 'locked') || periods[0];

  const targetH   = State.get.targetHoursForUser(me.id);
  const assignedH = activePeriod
    ? State.get.assignedHoursByUser(me.id, activePeriod.startDate, activePeriod.endDate)
    : 0;
  const pct = targetH > 0 ? Math.round(assignedH / targetH * 100) : 0;

  const myAsgns  = State.get.assignmentsByUser(me.id);
  const allShifts = State.get.shifts();
  const today    = new Date().toISOString().slice(0, 10);
  const upcoming = myAsgns
    .map(a => allShifts.find(s => s.id === a.shiftId))
    .filter(s => s && s.date >= today && s.status === 'published')
    .sort((a, b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime))
    .slice(0, 5);

  let regStatus = 'not_registered';
  if (activePeriod) {
    const ft = State.get.freeTimeByUserPeriod(me.id, activePeriod.id);
    regStatus = ft ? ft.status : 'not_registered';
  }

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Xin chào, ${escHtml(user?.fullName || me.id)}!</h1>
    <p class="page-subtitle">${level ? escHtml(level.name) : ''} · ${new Date().toLocaleDateString('vi-VN',{weekday:'long',year:'numeric',month:'long',day:'numeric'})}</p>
  </div>
</div>

<div class="stat-grid mb-4">
  <div class="stat-card">
    <div class="stat-label">Giờ đã phân</div>
    <div class="stat-value">${assignedH.toFixed(1)}h</div>
    <div class="stat-sub">Mục tiêu: ${targetH}h</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Tỷ lệ hoàn thành</div>
    <div class="stat-value ${pct >= 100 ? 'text-success' : pct >= 60 ? '' : 'text-warning'}">${pct}%</div>
    <div class="progress-bar-wrap mt-1">
      <div class="progress-bar ${pct >= 100 ? 'success' : pct >= 60 ? '' : 'warning'}" style="width:${Math.min(pct, 100)}%"></div>
    </div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Tổng ca được phân</div>
    <div class="stat-value">${myAsgns.length}</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Đăng ký kỳ hiện tại</div>
    <div class="stat-value" style="font-size:1rem">${renderBadge(regStatus)}</div>
  </div>
</div>

${activePeriod ? `
<div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:20px">
  <div class="card">
    <div class="card-header"><span class="card-title">Kỳ đăng ký hiện tại</span></div>
    <div class="card-body">
      <div class="detail-list">
        <div class="detail-item"><span class="detail-label">Tên kỳ</span><span class="detail-value font-medium">${escHtml(activePeriod.name)}</span></div>
        <div class="detail-item"><span class="detail-label">Trạng thái kỳ</span><span class="detail-value">${renderBadge(activePeriod.status)}</span></div>
        <div class="detail-item"><span class="detail-label">Hạn đăng ký</span><span class="detail-value">${formatDateTime(activePeriod.deadline)}</span></div>
        <div class="detail-item"><span class="detail-label">Đăng ký của bạn</span><span class="detail-value">${renderBadge(regStatus)}</span></div>
      </div>
      ${activePeriod.status === 'open' && regStatus !== 'submitted' ? `
      <button class="btn btn-primary btn-sm mt-3" onclick="App.navigate('register')">
        ${regStatus === 'draft' ? 'Tiếp tục đăng ký' : 'Đăng ký ngay'}
      </button>` : ''}
    </div>
  </div>
  <div class="card">
    <div class="card-header"><span class="card-title">Giờ làm trong kỳ</span></div>
    <div class="card-body">
      <div style="display:flex;justify-content:space-between;margin-bottom:8px">
        <span class="text-sm text-secondary">Đã phân / Mục tiêu</span>
        <span class="text-sm font-semibold">${assignedH.toFixed(1)}h / ${targetH}h</span>
      </div>
      <div class="progress-bar-wrap" style="height:12px">
        <div class="progress-bar ${pct>=100?'success':pct>=60?'':'warning'}" style="width:${Math.min(pct,100)}%"></div>
      </div>
      <div class="text-sm text-muted mt-1">${pct >= 100 ? '✓ Đã đạt mục tiêu' : `Còn thiếu ${(targetH - assignedH).toFixed(1)}h`}</div>
    </div>
  </div>
</div>` : ''}

<div class="card">
  <div class="card-header">
    <span class="card-title">Ca làm việc sắp tới</span>
    <button class="btn btn-secondary btn-sm" onclick="App.navigate('my-schedule')">Xem tất cả</button>
  </div>
  <div class="card-body" style="padding:8px 20px">
    ${upcoming.length ? upcoming.map(s => `
      <div style="display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px solid var(--gray-100)">
        <div>
          <div class="font-medium text-sm">${escHtml(s.name)}</div>
          <div class="text-secondary text-sm">${formatDate(s.date)} · ${s.startTime}–${s.endTime} · ${getShiftDurationLabel(s.startTime,s.endTime)}</div>
        </div>
        ${renderBadge('published')}
      </div>`).join('') : '<p class="text-muted text-sm" style="padding:12px 0">Chưa có ca làm việc sắp tới.</p>'}
  </div>
</div>`;
}

/* ═══════════════════════════════════════════════════════════
   13.2 ĐĂNG KÝ THỜI GIAN RẢNH
═══════════════════════════════════════════════════════════ */
let _regWeekStart = null;
let _slotCount = 1;

function renderRegister() {
  const me = State.get.currentUser();
  if (!me) return '<p class="text-muted">Chưa đăng nhập.</p>';
  const periods = State.get.periods();
  const activePeriod = periods.find(p => p.status === 'open') || periods.find(p => p.status === 'locked');

  if (!activePeriod) return `
<div class="page-header"><h1 class="page-title">Đăng ký thời gian rảnh</h1></div>
<div class="card"><div class="card-body"><div class="empty-state">
  <div class="empty-state-title">Chưa có kỳ đăng ký nào đang mở</div>
  <div class="empty-state-desc">Chờ Admin mở kỳ đăng ký mới.</div>
</div></div></div>`;

  const ft = State.get.freeTimeByUserPeriod(me.id, activePeriod.id);
  const isLocked = activePeriod.status === 'locked' && !activePeriod.allowEditAfterDeadline;

  // Set default week to period start
  if (!_regWeekStart) {
    _regWeekStart = new Date(activePeriod.startDate);
    const dow = _regWeekStart.getDay();
    if (dow !== 1) _regWeekStart.setDate(_regWeekStart.getDate() + (dow === 0 ? -6 : 1 - dow));
  }

  const weeks = _getWeeksInPeriod(activePeriod.startDate, activePeriod.endDate);

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Đăng ký thời gian rảnh</h1>
    <p class="page-subtitle">${escHtml(activePeriod.name)} · ${formatDate(activePeriod.startDate)} – ${formatDate(activePeriod.endDate)}</p>
  </div>
  <div class="page-actions">
    ${renderBadge(activePeriod.status)}
    ${renderBadge(ft ? ft.status : 'not_registered')}
  </div>
</div>

${isLocked ? `<div class="alert alert-warning mb-4">
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
  <div>Kỳ đăng ký đã khóa. Bạn không thể chỉnh sửa thêm.</div>
</div>` : ''}

${activePeriod.deadline ? `<div class="alert alert-info mb-4">
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
  <div>Hạn chót đăng ký: <strong>${formatDateTime(activePeriod.deadline)}</strong></div>
</div>` : ''}

<div class="card">
  <div class="card-header">
    <span class="card-title">Chọn thời gian rảnh</span>
    <div style="display:flex;gap:8px">
      ${!isLocked ? `
      <button class="btn btn-secondary btn-sm" onclick="UserView.saveDraft()">Lưu nháp</button>
      <button class="btn btn-primary btn-sm" onclick="UserView.submitRegistration()">Gửi đăng ký</button>` : ''}
    </div>
  </div>
  <div class="card-body">
    ${weeks.length > 1 ? `<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:16px">
      ${weeks.map((w, i) => {
        const wStr = _fmtDateStr(w);
        const isCur = _regWeekStart && _fmtDateStr(_regWeekStart) === wStr;
        return `<button class="btn btn-${isCur ? 'primary' : 'secondary'} btn-sm"
                onclick="UserView.selectRegWeek('${wStr}')">Tuần ${i+1} (${_fmtShort(w)})</button>`;
      }).join('')}
    </div>` : ''}
    <div id="reg-week-content">
      ${_renderRegWeek(activePeriod, ft, isLocked)}
    </div>
  </div>
</div>`;
}

function _fmtDateStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
}
function _fmtShort(d) {
  return `${String(d.getDate()).padStart(2,'0')}/${String(d.getMonth()+1).padStart(2,'0')}`;
}

function _getWeeksInPeriod(startDate, endDate) {
  const weeks = [];
  let cur = new Date(startDate);
  const dow = cur.getDay();
  if (dow !== 1) cur.setDate(cur.getDate() + (dow === 0 ? -6 : 1 - dow));
  const end = new Date(endDate);
  while (cur <= end) { weeks.push(new Date(cur)); cur.setDate(cur.getDate() + 7); }
  return weeks;
}

function _renderRegWeek(period, ft, isLocked) {
  const days = [];
  const ws = _regWeekStart || new Date(period.startDate);
  for (let i = 0; i < 7; i++) {
    const d = new Date(ws); d.setDate(d.getDate() + i);
    days.push(d);
  }
  const dowNames = ['CN','T2','T3','T4','T5','T6','T7'];
  const exemptDates  = period.exemptDates || [];
  const requiredDOW  = period.requiredDaysOfWeek || [1,2,3,4,5,6];
  const entries      = ft?.entries || [];

  return `<div style="display:grid;grid-template-columns:repeat(7,1fr);gap:8px">
    ${days.map(d => {
      const dateStr  = _fmtDateStr(d);
      const inPeriod = dateStr >= period.startDate && dateStr <= period.endDate;
      const isExempt = exemptDates.includes(dateStr);
      const isReq    = requiredDOW.includes(d.getDay());
      const entry    = entries.find(e => e.date === dateStr);
      const hasSlots = entry && entry.slots?.length > 0;

      if (!inPeriod) return `<div style="min-height:130px;background:var(--gray-50);border:1px solid var(--border);border-radius:var(--radius);padding:10px;opacity:0.4">
        <div style="font-size:0.786rem;font-weight:600;color:var(--text-muted)">${dowNames[d.getDay()]} ${d.getDate()}</div>
        <div style="font-size:0.714rem;color:var(--text-muted);margin-top:4px">Ngoài kỳ</div>
      </div>`;

      if (isExempt) return `<div style="min-height:130px;background:#f0fdf4;border:1px solid var(--success-border);border-radius:var(--radius);padding:10px">
        <div style="font-size:0.786rem;font-weight:600;color:var(--success)">${dowNames[d.getDay()]} ${d.getDate()}</div>
        <div style="font-size:0.714rem;color:var(--success);margin-top:6px">Ngày được miễn</div>
      </div>`;

      if (!isReq) return `<div style="min-height:130px;background:var(--gray-50);border:1px dashed var(--border);border-radius:var(--radius);padding:10px">
        <div style="font-size:0.786rem;font-weight:600;color:var(--text-muted)">${dowNames[d.getDay()]} ${d.getDate()}</div>
        <div style="font-size:0.714rem;color:var(--text-muted);margin-top:6px">Không cần đăng ký</div>
      </div>`;

      const slotLabel = hasSlots ? entry.slots.map(s => `${s.start}–${s.end}`).join(', ') : '';
      const bg = hasSlots ? 'var(--primary-bg)' : 'white';
      const border = hasSlots ? 'var(--primary-border)' : 'var(--border)';

      return `<div style="min-height:130px;background:${bg};border:1px solid ${border};border-radius:var(--radius);padding:10px;display:flex;flex-direction:column;gap:4px" data-date="${dateStr}">
        <div style="font-size:0.786rem;font-weight:600">${dowNames[d.getDay()]} ${d.getDate()}</div>
        ${hasSlots
          ? `<div style="font-size:0.714rem;color:var(--primary);font-weight:500;word-break:break-all">${escHtml(slotLabel)}</div>`
          : `<div style="font-size:0.714rem;color:var(--text-muted)">Chưa đăng ký</div>`}
        ${!isLocked ? `<div style="margin-top:auto;display:flex;flex-direction:column;gap:3px">
          ${!hasSlots ? `<button class="btn btn-primary btn-xs" onclick="UserView.setAllDay('${dateStr}')">Cả ngày</button>` : ''}
          <button class="btn btn-secondary btn-xs" onclick="UserView.openTimeSlot('${dateStr}')">
            ${hasSlots ? 'Sửa giờ' : 'Chọn giờ'}
          </button>
          ${hasSlots ? `<button class="btn btn-ghost btn-xs" onclick="UserView.clearDay('${dateStr}')">Xóa</button>` : ''}
        </div>` : ''}
      </div>`;
    }).join('')}
  </div>`;
}

function selectRegWeek(dateStr) {
  _regWeekStart = new Date(dateStr);
  const me = State.get.currentUser();
  if (!me) return;
  const periods = State.get.periods();
  const activePeriod = periods.find(p => p.status === 'open') || periods.find(p => p.status === 'locked');
  if (!activePeriod) return;
  const ft = State.get.freeTimeByUserPeriod(me.id, activePeriod.id);
  const isLocked = activePeriod.status === 'locked' && !activePeriod.allowEditAfterDeadline;
  document.getElementById('reg-week-content').innerHTML = _renderRegWeek(activePeriod, ft, isLocked);
  // Re-render week buttons
  App.navigate('register');
}

function openTimeSlot(dateStr) {
  _slotCount = 1;
  const me = State.get.currentUser();
  const periods = State.get.periods();
  const activePeriod = periods.find(p => p.status === 'open') || periods.find(p => p.status === 'locked');
  if (!activePeriod || !me) return;
  const ft    = State.get.freeTimeByUserPeriod(me.id, activePeriod.id);
  const entry = ft?.entries?.find(e => e.date === dateStr);
  const slots = entry?.slots || [{ start: '07:00', end: '22:00' }];

  const body = `
<form id="timeslot-form" novalidate>
  <div class="mb-3"><strong>Ngày: ${formatDate(dateStr)}</strong></div>
  <div id="slot-list">
    ${slots.map((s, i) => `
    <div class="form-row-2 mb-2" data-slot="${i}">
      <div class="form-group"><label class="form-label" for="slot-start-${i}">Giờ bắt đầu khung ${i+1}</label>
        <input type="time" id="slot-start-${i}" name="slot_start_${i}" class="form-input" value="${s.start}"></div>
      <div class="form-group"><label class="form-label" for="slot-end-${i}">Giờ kết thúc khung ${i+1}</label>
        <input type="time" id="slot-end-${i}" name="slot_end_${i}" class="form-input" value="${s.end}"></div>
      ${i > 0 ? `<button type="button" class="btn btn-danger btn-xs" style="margin-top:4px" onclick="this.closest('[data-slot]').remove()" aria-label="Xóa khung giờ ${i+1}">Xóa</button>` : ''}
    </div>`).join('')}
  </div>
  <button type="button" class="btn btn-ghost btn-sm" onclick="UserView.addTimeSlotRow()">+ Thêm khung giờ</button>
</form>`;

  App.openModal(`Chọn thời gian rảnh — ${formatDate(dateStr)}`, body, [
    { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
    { label: 'Lưu', cls: 'btn-primary', action: () => saveTimeSlot(dateStr) },
  ], 'modal-sm');
}

function addTimeSlotRow() {
  const list = document.getElementById('slot-list');
  if (!list) return;
  const i = _slotCount++;
  const row = document.createElement('div');
  row.className = 'form-row-2 mb-2';
  row.dataset.slot = i;
  row.innerHTML = `
    <div class="form-group"><label class="form-label" for="slot-start-${i}">Giờ bắt đầu khung ${i+1}</label><input type="time" id="slot-start-${i}" name="slot_start_${i}" class="form-input" value="07:00"></div>
    <div class="form-group"><label class="form-label" for="slot-end-${i}">Giờ kết thúc khung ${i+1}</label><input type="time" id="slot-end-${i}" name="slot_end_${i}" class="form-input" value="22:00"></div>
    <button type="button" class="btn btn-danger btn-xs" style="margin-top:4px" onclick="this.closest('[data-slot]').remove()" aria-label="Xóa khung giờ ${i+1}">Xóa</button>`;
  list.appendChild(row);
}

function saveTimeSlot(dateStr) {
  const form = document.getElementById('timeslot-form');
  if (!form) return;
  const slots = [];
  form.querySelectorAll('[data-slot]').forEach(row => {
    const i = row.dataset.slot;
    const start = form.querySelector(`[name="slot_start_${i}"]`)?.value;
    const end   = form.querySelector(`[name="slot_end_${i}"]`)?.value;
    if (start && end) slots.push({ start, end });
  });
  if (!slots.length) { App.toast('Vui lòng nhập ít nhất một khung giờ.', 'warning'); return; }
  _updateFreeTimeEntry(dateStr, slots);
  App.closeModal();
  App.navigate('register');
}

function setAllDay(dateStr) {
  _updateFreeTimeEntry(dateStr, [{ start: '00:00', end: '23:59' }]);
  App.navigate('register');
}

function clearDay(dateStr) {
  _updateFreeTimeEntry(dateStr, []);
  App.navigate('register');
}

function _updateFreeTimeEntry(dateStr, slots) {
  const me = State.get.currentUser();
  if (!me) return;
  const periods = State.get.periods();
  const activePeriod = periods.find(p => p.status === 'open') || periods.find(p => p.status === 'locked');
  if (!activePeriod) return;
  let ft = State.get.freeTimeByUserPeriod(me.id, activePeriod.id);
  if (!ft) ft = { userId: me.id, periodId: activePeriod.id, status: 'draft', entries: [], submittedAt: null };
  const entries = ft.entries.filter(e => e.date !== dateStr);
  if (slots.length > 0) entries.push({ date: dateStr, slots });
  State.mut.saveFreeTime({ ...ft, entries });
}

function saveDraft() {
  App.toast('Đã lưu bản nháp thành công.', 'success');
}

function submitRegistration() {
  const me = State.get.currentUser();
  if (!me) return;
  const periods = State.get.periods();
  const activePeriod = periods.find(p => p.status === 'open');
  if (!activePeriod) { App.toast('Kỳ đăng ký chưa mở hoặc đã khóa.', 'warning'); return; }

  const ft = State.get.freeTimeByUserPeriod(me.id, activePeriod.id);
  if (!ft || ft.entries.length === 0) {
    App.toast('Chưa có dữ liệu. Vui lòng chọn thời gian rảnh trước.', 'warning');
    return;
  }

  App.confirm(
    'Gửi đăng ký thời gian rảnh?',
    'Sau khi gửi, bạn không thể chỉnh sửa trừ khi Admin mở lại.',
    () => {
      State.mut.saveFreeTime({ ...ft, status: 'submitted', submittedAt: new Date().toISOString() });
      logHistory('free_time_submit', 'Gửi đăng ký thời gian rảnh',
        `${me.id} gửi đăng ký kỳ ${activePeriod.name}`, me.id);
      App.toast('Đã gửi đăng ký thành công!', 'success');
      App.navigate('register');
    }
  );
}

/* ═══════════════════════════════════════════════════════════
   13.3 LỊCH LÀM VIỆC CÁ NHÂN
═══════════════════════════════════════════════════════════ */
let _myView = 'week';
let _myDate = new Date();

function renderMySchedule() {
  const me = State.get.currentUser();
  if (!me) return '<p class="text-muted">Chưa đăng nhập.</p>';
  const myAsgns   = State.get.assignmentsByUser(me.id);
  const targetH   = State.get.targetHoursForUser(me.id);
  const assignedH = State.get.assignedHoursByUser(me.id, null, null);

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Lịch làm việc của tôi</h1>
    <p class="page-subtitle">Tổng ${myAsgns.length} ca · ${assignedH.toFixed(1)}h / ${targetH}h mục tiêu</p>
  </div>
</div>
<div class="card">
  <div class="card-body">
    <div class="calendar-nav">
      <div style="display:flex;gap:8px;align-items:center">
        <button class="btn btn-secondary btn-sm" onclick="UserView.myPrev()">&#8249;</button>
        <span class="calendar-title" id="my-cal-title"></span>
        <button class="btn btn-secondary btn-sm" onclick="UserView.myNext()">&#8250;</button>
        <button class="btn btn-ghost btn-sm" onclick="UserView.myToday()">Hôm nay</button>
      </div>
      <div class="calendar-view-tabs">
        <button class="cal-view-btn ${_myView==='month'?'active':''}" onclick="UserView.mySetView('month')">Tháng</button>
        <button class="cal-view-btn ${_myView==='week' ?'active':''}" onclick="UserView.mySetView('week')">Tuần</button>
      </div>
    </div>
    <div id="my-cal-body"></div>
  </div>
</div>`;
}

function initMySchedule() {
  _renderMyView();
}

function _renderMyView() {
  const me = State.get.currentUser();
  if (!me) return;
  const titleEl = document.getElementById('my-cal-title');
  const bodyEl  = document.getElementById('my-cal-body');
  if (!bodyEl) return;

  const myAsgns    = State.get.assignmentsByUser(me.id);
  const myShiftIds = new Set(myAsgns.map(a => a.shiftId));
  const myShifts   = State.get.shifts().filter(s => myShiftIds.has(s.id) && s.status === 'published');

  if (_myView === 'month') {
    const y = _myDate.getFullYear(), m = _myDate.getMonth();
    if (titleEl) titleEl.textContent = `Tháng ${m+1}/${y}`;
    bodyEl.innerHTML = _renderMyMonth(y, m, myShifts);
  } else {
    const ws = _getWeekStart(_myDate);
    const we = new Date(ws); we.setDate(we.getDate() + 6);
    if (titleEl) titleEl.textContent = `${_fmtShort(ws)} – ${_fmtShort(we)}/${we.getFullYear()}`;
    bodyEl.innerHTML = _renderMyWeek(ws, myShifts);
  }
  App.refreshTableKeyboardAccess();
}

function _getWeekStart(d) {
  const r = new Date(d);
  const dow = r.getDay();
  r.setDate(r.getDate() - (dow === 0 ? 6 : dow - 1));
  return r;
}

function _renderMyMonth(y, m, myShifts) {
  const firstDay = new Date(y, m, 1);
  const lastDay  = new Date(y, m+1, 0);
  const startDOW = firstDay.getDay();
  const todayStr = _fmtDateStr(new Date());
  let cells = '';
  ['CN','T2','T3','T4','T5','T6','T7'].forEach(d => cells += `<div class="cal-day-header">${d}</div>`);
  for (let i = 0; i < startDOW; i++) cells += `<div class="cal-day other-month"></div>`;
  for (let d = 1; d <= lastDay.getDate(); d++) {
    const ds = `${y}-${String(m+1).padStart(2,'0')}-${String(d).padStart(2,'0')}`;
    const dayShifts = myShifts.filter(s => s.date === ds);
    cells += `<div class="cal-day ${ds===todayStr?'today':''}">
      <div class="cal-day-num">${d}</div>
      <div class="cal-events">
        ${dayShifts.map(s => `<div class="cal-event full" title="${escHtml(s.name)}">${escHtml(s.name)}</div>`).join('')}
      </div>
    </div>`;
  }
  const total = startDOW + lastDay.getDate();
  for (let i = total; i % 7 !== 0; i++) cells += `<div class="cal-day other-month"></div>`;
  return `<div class="calendar-grid">${cells}</div>`;
}

function _renderMyWeek(ws, myShifts) {
  const days = [];
  for (let i = 0; i < 7; i++) {
    const d = new Date(ws); d.setDate(d.getDate() + i);
    days.push(d);
  }
  const dowNames = ['CN','T2','T3','T4','T5','T6','T7'];
  const todayStr = _fmtDateStr(new Date());
  let ths = '<th style="width:50px"></th>';
  days.forEach(d => {
    const ds = _fmtDateStr(d);
    ths += `<th ${ds===todayStr?'style="background:var(--primary-bg)"':''}>${dowNames[d.getDay()]} ${d.getDate()}/${d.getMonth()+1}</th>`;
  });
  let tds = '<td class="text-muted text-xs" style="padding:6px">Ca</td>';
  days.forEach(d => {
    const ds = _fmtDateStr(d);
    const dayShifts = myShifts.filter(s => s.date === ds);
    tds += `<td style="vertical-align:top;padding:6px">
      ${dayShifts.length ? dayShifts.map(s => `
        <div style="background:var(--primary-bg);border:1px solid var(--primary-border);border-radius:var(--radius-sm);padding:6px;margin-bottom:4px;font-size:0.786rem">
          <div class="font-medium">${escHtml(s.name)}</div>
          <div class="text-secondary">${s.startTime}–${s.endTime}</div>
          <div class="text-muted">${getShiftDurationLabel(s.startTime,s.endTime)}</div>
        </div>`).join('') : '<span class="text-muted" style="font-size:0.714rem">—</span>'}
    </td>`;
  });
  return `<div class="week-view"><table class="week-table"><thead><tr>${ths}</tr></thead><tbody><tr>${tds}</tr></tbody></table></div>`;
}

function myPrev()  {
  if (_myView==='month') _myDate.setMonth(_myDate.getMonth()-1);
  else _myDate.setDate(_myDate.getDate()-7);
  _renderMyView();
}
function myNext()  {
  if (_myView==='month') _myDate.setMonth(_myDate.getMonth()+1);
  else _myDate.setDate(_myDate.getDate()+7);
  _renderMyView();
}
function myToday() { _myDate = new Date(); _renderMyView(); }
function mySetView(v) { _myView = v; _renderMyView(); }

/* ═══════════════════════════════════════════════════════════
   13.4 THÔNG TIN CÁ NHÂN
═══════════════════════════════════════════════════════════ */
function renderProfile() {
  const me   = State.get.currentUser();
  if (!me) return '<p class="text-muted">Chưa đăng nhập.</p>';
  const user  = State.get.userById(me.id);
  const level = user ? State.get.levelById(user.levelId) : null;

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Thông tin cá nhân</h1>
  </div>
  <div class="page-actions">
    <button class="btn btn-primary" onclick="UserView.openChangePassword()">Đổi mật khẩu</button>
  </div>
</div>
<div style="display:grid;grid-template-columns:260px 1fr;gap:20px;align-items:start">
  <div class="card">
    <div class="card-body" style="text-align:center;padding:28px">
      <div style="width:72px;height:72px;border-radius:50%;background:var(--primary);color:white;font-size:1.5rem;font-weight:700;display:flex;align-items:center;justify-content:center;margin:0 auto 12px">
        ${escHtml((user?.fullName||'?')[0].toUpperCase())}
      </div>
      <div class="font-semibold" style="font-size:1rem;margin-bottom:4px">${escHtml(user?.fullName||'')}</div>
      <code style="font-size:0.786rem;color:var(--text-secondary)">${escHtml(user?.username||'')}</code>
      <div class="mt-2">${level ? `<span class="badge" style="background:${level.color}22;color:${level.color}">${escHtml(level.name)}</span>` : ''}</div>
      <div class="mt-2">${renderBadge(user?.status||'active')}</div>
    </div>
  </div>
  <div class="card">
    <div class="card-header"><span class="card-title">Thông tin chi tiết</span></div>
    <div class="card-body">
      <div class="detail-list">
        <div class="detail-item"><span class="detail-label">Mã nhân viên</span><span class="detail-value font-medium">${escHtml(user?.code||'')}</span></div>
        <div class="detail-item"><span class="detail-label">Họ và tên</span><span class="detail-value">${escHtml(user?.fullName||'')}</span></div>
        <div class="detail-item"><span class="detail-label">Tài khoản</span><span class="detail-value"><code>${escHtml(user?.username||'')}</code></span></div>
        <div class="detail-item"><span class="detail-label">Level</span><span class="detail-value">${level ? `<span class="badge" style="background:${level.color}22;color:${level.color}">${escHtml(level.name)}</span>` : '—'}</span></div>
        <div class="detail-item"><span class="detail-label">Tỷ lệ giờ mục tiêu</span><span class="detail-value">${user?.targetRate||100}%</span></div>
        <div class="detail-item"><span class="detail-label">Điện thoại</span><span class="detail-value">${escHtml(user?.phone||'—')}</span></div>
        <div class="detail-item"><span class="detail-label">Email</span><span class="detail-value">${escHtml(user?.email||'—')}</span></div>
        <div class="detail-item"><span class="detail-label">Ngày tham gia</span><span class="detail-value">${user?.joinDate ? formatDate(user.joinDate) : '—'}</span></div>
        <div class="detail-item"><span class="detail-label">Ghi chú</span><span class="detail-value text-secondary">${escHtml(user?.note||'—')}</span></div>
      </div>
    </div>
  </div>
</div>`;
}

function openChangePassword() {
  App.openModal('Đổi mật khẩu', `
<form id="chpw-form" novalidate>
  <div class="form-group mb-3">
    <label class="form-label" for="chpw-current">Mật khẩu hiện tại <span class="required" aria-hidden="true">*</span></label>
    <input type="password" id="chpw-current" name="current" class="form-input" placeholder="Nhập mật khẩu hiện tại"
           aria-required="true" aria-describedby="chpw-current-err">
    <span class="field-error" id="chpw-current-err" data-err="current" aria-live="polite"></span>
  </div>
  <div class="form-group mb-3">
    <label class="form-label" for="chpw-newpw">Mật khẩu mới <span class="required" aria-hidden="true">*</span></label>
    <input type="password" id="chpw-newpw" name="newpw" class="form-input" placeholder="Ít nhất 6 ký tự"
           aria-required="true" aria-describedby="chpw-newpw-err">
    <span class="field-error" id="chpw-newpw-err" data-err="newpw" aria-live="polite"></span>
  </div>
  <div class="form-group">
    <label class="form-label" for="chpw-confirm">Xác nhận mật khẩu mới <span class="required" aria-hidden="true">*</span></label>
    <input type="password" id="chpw-confirm" name="confirm" class="form-input" placeholder="Nhập lại mật khẩu mới"
           aria-required="true" aria-describedby="chpw-confirm-err">
    <span class="field-error" id="chpw-confirm-err" data-err="confirm" aria-live="polite"></span>
  </div>
</form>`, [
    { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
    { label: 'Đổi mật khẩu', cls: 'btn-primary', action: submitChangePassword },
  ], 'modal-sm');
}

function submitChangePassword() {
  const form = document.getElementById('chpw-form');
  if (!form) return;
  clearFormErrors(form);
  const current = form.querySelector('[name="current"]').value;
  const newpw   = form.querySelector('[name="newpw"]').value;
  const confirm = form.querySelector('[name="confirm"]').value;
  const me      = State.get.currentUser();
  const user    = me ? State.get.userById(me.id) : null;
  if (!user) return;

  if (user.password !== current) { showFieldErrors(form, { current: 'Mật khẩu hiện tại không đúng.' }); return; }
  const err = V.passwordFormat(newpw);
  if (err) { showFieldErrors(form, { newpw: err }); return; }
  if (newpw !== confirm) { showFieldErrors(form, { confirm: 'Xác nhận mật khẩu không khớp.' }); return; }

  State.mut.saveUser({ ...user, password: newpw });
  App.closeModal();
  App.toast('Đã đổi mật khẩu thành công.', 'success');
}

return {
  renderOverview, renderRegister, renderMySchedule, renderProfile,
  selectRegWeek, openTimeSlot, addTimeSlotRow, setAllDay, clearDay, saveDraft, submitRegistration,
  myPrev, myNext, myToday, mySetView, initMySchedule,
  openChangePassword,
};
})();
