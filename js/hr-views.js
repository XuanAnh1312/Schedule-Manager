/**
 * hr-views.js — Giao diện module của Bảo (task v1.1: BA-01 … BA-06).
 *
 * Nhân viên: Đổi ca / Làm thay · Nghỉ phép · Ca trống · Thông báo
 * Admin:     Duyệt yêu cầu (đổi ca, nghỉ phép, vắng đột xuất) · Ca trống · Thông báo (+ cấu hình nhắc hạn)
 * Shell:     chuông thông báo trên topbar + số việc chờ trên menu.
 *
 * Mọi dữ liệu đi qua HrStore (hr-store.js) — khi có API thật chỉ cần đổi HrStore.
 */
const HrViews = (() => {

  /* ── Nhãn trạng thái ─────────────────────────────────────────────── */
  const STATUS = {
    pending_peer:     ['Chờ đồng nghiệp', 'badge-locked'],
    pending_approval: ['Chờ duyệt',       'badge-submitted'],
    pending:          ['Chờ duyệt',       'badge-submitted'],
    approved:         ['Đã duyệt',        'badge-full'],
    rejected:         ['Từ chối',         'badge-understaffed'],
    peer_declined:    ['Đồng nghiệp từ chối', 'badge-understaffed'],
    cancelled:        ['Đã hủy',          'badge-inactive'],
    withdrawn:        ['Đã rút',          'badge-inactive'],
    open:             ['Đang mở',         'badge-open'],
    filled:           ['Đã đủ người',     'badge-full'],
    closed:           ['Đã đóng',         'badge-inactive'],
  };
  const badge = s => {
    const [label, cls] = STATUS[s] || [s, 'badge-draft'];
    return `<span class="badge ${cls}">${escHtml(label)}</span>`;
  };
  const typeName = t => (t === 'swap' ? 'Đổi ca' : 'Làm thay');
  const shiftText = s => (s ? `${escHtml(s.name)} · ${formatDate(s.date)} · ${escHtml(s.startTime)}–${escHtml(s.endTime)}` : '—');
  const user = () => State.get.currentUser();
  const isAdmin = () => user()?.role === 'admin';

  /* ── Chạy thao tác + báo lỗi thống nhất ─────────────────────────── */
  function run(fn, successMsg, rerender = true) {
    try {
      const result = fn();
      if (successMsg) App.toast(successMsg, 'success');
      refreshShell();
      if (rerender) App.navigate(_route());
      return result;
    } catch (e) {
      if (!(e instanceof HrError)) { console.error(e); App.toast('Đã xảy ra lỗi không mong muốn.', 'error'); return null; }
      if (e.details && e.details.length) {
        App.openModal(e.message, `<div class="alert alert-danger"><div class="alert-content"><ul class="hr-violation-list">
          ${e.details.map(d => `<li>${escHtml(d)}</li>`).join('')}</ul></div></div>`,
          [{ label: 'Đóng', cls: 'btn-secondary', action: () => App.closeModal() }]);
      } else {
        App.toast(e.message, 'error');
      }
      return null;
    }
  }
  function _route() {
    return document.querySelector('.sidebar-nav-item.active')?.dataset.route || (isAdmin() ? 'approvals' : 'my-requests');
  }

  const pageHeader = (title, subtitle, actions = '') => `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">${title}</h1>
    ${subtitle ? `<p class="page-subtitle">${subtitle}</p>` : ''}
  </div>
  ${actions ? `<div class="page-actions">${actions}</div>` : ''}
</div>`;

  const empty = (title, desc = '') => `<div class="empty-state"><div class="empty-state-title">${title}</div>
    ${desc ? `<div class="empty-state-desc">${desc}</div>` : ''}</div>`;

  const stat = (label, value, tone = '') => `<div class="stat-card ${tone ? 'hr-stat-' + tone : ''}">
    <div class="stat-card-header"><span class="stat-label">${label}</span></div>
    <div class="stat-value">${value}</div></div>`;

  /* ════════════════════════════════════════════════════════════════
     NHÂN VIÊN — ĐỔI CA / LÀM THAY
  ════════════════════════════════════════════════════════════════ */
  function renderMyRequests() {
    const me = user();
    const all = HrStore.listShiftRequests('mine');
    const incoming = all.filter(r => r.targetUserId === me.id && r.status === 'pending_peer');
    const mine = all.filter(r => r.requesterId === me.id);
    const upcoming = HrStore.myUpcomingAssignments(me.id);

    const incomingRows = incoming.map(r => `
      <tr>
        <td>${typeName(r.type)}</td>
        <td>${escHtml(r.requesterName)}</td>
        <td>${shiftText(r.sourceShift)}${r.targetShift ? `<div class="text-muted hr-small">Bạn đưa lại: ${shiftText(r.targetShift)}</div>` : ''}</td>
        <td class="hr-reason">${escHtml(r.reason)}</td>
        <td class="actions-cell">
          <button class="btn btn-success btn-sm" onclick="HrViews.peerAccept('${r.id}', ${r.version})">Đồng ý</button>
          <button class="btn btn-secondary btn-sm" onclick="HrViews.peerDecline('${r.id}', ${r.version})">Từ chối</button>
        </td>
      </tr>`).join('');

    const mineRows = mine.map(r => `
      <tr>
        <td>${typeName(r.type)}</td>
        <td>${shiftText(r.sourceShift)}${r.targetShift ? `<div class="text-muted hr-small">⇄ ${shiftText(r.targetShift)}</div>` : ''}</td>
        <td>${escHtml(r.targetUserName)}</td>
        <td>${badge(r.status)}${r.decisionNote ? `<div class="text-muted hr-small">${escHtml(r.decisionNote)}</div>` : ''}</td>
        <td>${formatDateTime(r.createdAt)}</td>
        <td class="actions-cell">${r.isOpen ? `<button class="btn btn-ghost btn-sm" onclick="HrViews.cancelRequest('${r.id}', ${r.version})">Hủy</button>` : ''}</td>
      </tr>`).join('');

    return `
${pageHeader('Đổi ca / Nhờ làm thay', 'Yêu cầu chỉ có hiệu lực sau khi đồng nghiệp đồng ý và Admin duyệt. Trong lúc chờ, lịch của bạn không thay đổi.',
  `<button class="btn btn-primary" onclick="HrViews.openCreateRequest()" ${upcoming.length ? '' : 'disabled title="Bạn chưa có ca sắp tới đã công bố"'}>+ Tạo yêu cầu</button>`)}
<div class="stat-grid mb-4">
  ${stat('Ca sắp tới đã công bố', upcoming.length)}
  ${stat('Chờ bạn phản hồi', incoming.length, incoming.length ? 'warn' : '')}
  ${stat('Yêu cầu đang mở', mine.filter(r => r.isOpen).length)}
</div>
<div class="card mb-4">
  <div class="card-header"><span class="card-title">Đồng nghiệp đề nghị bạn</span></div>
  ${incoming.length ? `<div class="table-container"><table class="data-table">
    <thead><tr><th>Loại</th><th>Người gửi</th><th>Ca</th><th>Lý do</th><th></th></tr></thead>
    <tbody>${incomingRows}</tbody></table></div>` : empty('Không có đề nghị nào đang chờ bạn.')}
</div>
<div class="card">
  <div class="card-header"><span class="card-title">Yêu cầu của tôi</span></div>
  ${mine.length ? `<div class="table-container"><table class="data-table">
    <thead><tr><th>Loại</th><th>Ca</th><th>Người nhận</th><th>Trạng thái</th><th>Gửi lúc</th><th></th></tr></thead>
    <tbody>${mineRows}</tbody></table></div>`
    : empty('Bạn chưa gửi yêu cầu nào.', upcoming.length ? '' : 'Chỉ ca đã công bố và chưa bắt đầu mới được đổi/nhờ làm thay.')}
</div>`;
  }

  function openCreateRequest() {
    const me = user();
    const upcoming = HrStore.myUpcomingAssignments(me.id);
    if (!upcoming.length) { App.toast('Bạn chưa có ca sắp tới đã công bố.', 'warning'); return; }
    const colleagues = State.get.activeUsers().filter(u => u.id !== me.id);
    const body = `
<div class="form-group mb-3">
  <label for="hr-req-source">Ca của bạn</label>
  <select id="hr-req-source" class="form-input" onchange="HrViews.refreshCandidates()">
    ${upcoming.map(({ a, s }) => `<option value="${a.id}">${shiftText(s)}</option>`).join('')}
  </select>
</div>
<div class="form-group mb-3">
  <span class="form-label-text">Loại yêu cầu</span>
  <div class="hr-radio-row" role="radiogroup" aria-label="Loại yêu cầu">
    <label><input type="radio" name="hr-req-type" value="cover" checked onchange="HrViews.refreshCandidates()"> Nhờ làm thay (người kia nhận ca của bạn)</label>
    <label><input type="radio" name="hr-req-type" value="swap" onchange="HrViews.refreshCandidates()"> Đổi ca (hai bên đổi chéo)</label>
  </div>
</div>
<div class="form-group mb-3">
  <label for="hr-req-target">Đồng nghiệp</label>
  <select id="hr-req-target" class="form-input" onchange="HrViews.refreshSwapOptions()">
    ${colleagues.map(u => `<option value="${u.id}">${escHtml(u.fullName)}</option>`).join('')}
  </select>
  <div id="hr-req-eligibility" class="hr-small mt-1" aria-live="polite"></div>
</div>
<div class="form-group mb-3 hidden" id="hr-req-swap-wrap">
  <label for="hr-req-target-shift">Ca của đồng nghiệp để đổi lại</label>
  <select id="hr-req-target-shift" class="form-input"></select>
</div>
<div class="form-group">
  <label for="hr-req-reason">Lý do <span class="text-danger">*</span></label>
  <textarea id="hr-req-reason" class="form-input" rows="3" maxlength="1000" placeholder="Ví dụ: có lịch thi, việc gia đình..."></textarea>
</div>`;
    App.openModal('Tạo yêu cầu đổi ca / làm thay', body, [
      { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
      { label: 'Gửi yêu cầu', cls: 'btn-primary', action: submitCreateRequest },
    ]);
    refreshCandidates();
  }

  function _reqType() { return document.querySelector('input[name="hr-req-type"]:checked')?.value || 'cover'; }

  function refreshCandidates() {
    const source = document.getElementById('hr-req-source')?.value;
    const select = document.getElementById('hr-req-target');
    if (!source || !select) return;
    const swap = _reqType() === 'swap';
    document.getElementById('hr-req-swap-wrap').classList.toggle('hidden', !swap);
    if (!swap) {
      // Nhờ làm thay: hiển thị ai đủ điều kiện nhận ca ngay khi chọn
      let candidates = [];
      try { candidates = HrStore.coverCandidates(source); } catch (e) { App.toast(e.message, 'error'); return; }
      const current = select.value;
      select.innerHTML = candidates.map(c => `<option value="${c.userId}" data-reasons="${escHtml(c.reasons.join(' | '))}">
        ${escHtml(c.fullName)}${c.eligible ? ' ✓ đủ điều kiện' : ' — không đủ điều kiện'}</option>`).join('');
      if (candidates.some(c => c.userId === current)) select.value = current;
    }
    refreshSwapOptions();
  }

  function refreshSwapOptions() {
    const select = document.getElementById('hr-req-target');
    const info = document.getElementById('hr-req-eligibility');
    if (!select || !info) return;
    const swap = _reqType() === 'swap';
    if (!swap) {
      const reasons = select.selectedOptions[0]?.dataset.reasons;
      info.innerHTML = reasons ? `<span class="text-danger">${escHtml(reasons)}</span>`
        : '<span class="text-success">Đồng nghiệp này đủ điều kiện nhận ca.</span>';
      return;
    }
    info.textContent = '';
    const target = select.value;
    const options = HrStore.myUpcomingAssignments(target);
    const ts = document.getElementById('hr-req-target-shift');
    ts.innerHTML = options.length
      ? options.map(({ a, s }) => `<option value="${a.id}">${shiftText(s)}</option>`).join('')
      : '<option value="">Đồng nghiệp chưa có ca sắp tới để đổi</option>';
  }

  function submitCreateRequest() {
    const input = {
      type: _reqType(),
      sourceAssignmentId: document.getElementById('hr-req-source').value,
      targetUserId: document.getElementById('hr-req-target').value,
      targetAssignmentId: _reqType() === 'swap' ? (document.getElementById('hr-req-target-shift').value || null) : null,
      reason: document.getElementById('hr-req-reason').value,
    };
    const ok = run(() => HrStore.createShiftRequest(input), 'Đã gửi yêu cầu. Chờ đồng nghiệp phản hồi.', false);
    if (ok) { App.closeModal(); App.navigate('my-requests'); }
  }

  function peerAccept(id, version) {
    App.confirm('Đồng ý nhận ca?', 'Sau khi bạn đồng ý, yêu cầu được chuyển cho Admin duyệt. Lịch chỉ thay đổi khi Admin duyệt.',
      () => run(() => HrStore.peerRespond(id, true, version, null), 'Đã đồng ý. Đang chờ Admin duyệt.'),
      { type: 'info', okLabel: 'Đồng ý' });
  }
  function peerDecline(id, version) {
    App.confirm('Từ chối đề nghị?', '', reason => run(() => HrStore.peerRespond(id, false, version, reason), 'Đã từ chối đề nghị.'),
      { type: 'warn', okLabel: 'Từ chối', requireReason: true, reasonLabel: 'Lý do (gửi cho người đề nghị)' });
  }
  function cancelRequest(id, version) {
    App.confirm('Hủy yêu cầu?', 'Yêu cầu sẽ bị hủy và đồng nghiệp được thông báo.',
      () => run(() => HrStore.cancelShiftRequest(id, version), 'Đã hủy yêu cầu.'), { type: 'danger', okLabel: 'Hủy yêu cầu' });
  }

  /* ════════════════════════════════════════════════════════════════
     NHÂN VIÊN — NGHỈ PHÉP
  ════════════════════════════════════════════════════════════════ */
  function renderLeave() {
    const list = HrStore.listLeave('mine');
    const rows = list.map(l => `
      <tr>
        <td>${escHtml(l.leaveTypeName)}</td>
        <td>${formatDate(l.startDate)}${l.endDate !== l.startDate ? ' → ' + formatDate(l.endDate) : ''}</td>
        <td class="hr-reason">${escHtml(l.reason)}</td>
        <td>${badge(l.status)}${l.decisionNote ? `<div class="text-muted hr-small">${escHtml(l.decisionNote)}</div>` : ''}
            ${l.status === 'approved' && l.affectedAssignmentCount ? `<div class="text-muted hr-small">Đã gỡ khỏi ${l.affectedAssignmentCount} ca</div>` : ''}</td>
        <td class="actions-cell">
          <button class="btn btn-ghost btn-sm" onclick="HrViews.showImpact('${l.id}')">Ảnh hưởng</button>
          ${l.status === 'pending' ? `<button class="btn btn-ghost btn-sm" onclick="HrViews.cancelLeave('${l.id}', ${l.version})">Hủy</button>` : ''}
        </td>
      </tr>`).join('');
    return `
${pageHeader('Nghỉ phép', 'Gửi đơn nghỉ cho Admin duyệt. Khi được duyệt, bạn sẽ được gỡ khỏi các ca trong thời gian nghỉ.',
  '<button class="btn btn-primary" onclick="HrViews.openCreateLeave()">+ Tạo đơn nghỉ</button>')}
<div class="card">
  <div class="card-header"><span class="card-title">Đơn nghỉ của tôi</span></div>
  ${list.length ? `<div class="table-container"><table class="data-table">
    <thead><tr><th>Loại nghỉ</th><th>Thời gian</th><th>Lý do</th><th>Trạng thái</th><th></th></tr></thead>
    <tbody>${rows}</tbody></table></div>` : empty('Bạn chưa có đơn nghỉ nào.')}
</div>`;
  }

  function openCreateLeave() {
    const types = HrStore.leaveTypes();
    const today = _fmtDate(new Date());
    App.openModal('Tạo đơn nghỉ', `
<div class="form-group mb-3">
  <label for="hr-leave-type">Loại nghỉ</label>
  <select id="hr-leave-type" class="form-input" onchange="HrViews.onLeaveTypeChange()">
    ${types.map(t => `<option value="${t.id}" data-doc="${t.requiresDocument ? 1 : 0}" data-notice="${t.minNoticeDays || 0}">${escHtml(t.name)}${t.paid ? '' : ' (không lương)'}</option>`).join('')}
  </select>
  <div id="hr-leave-type-hint" class="hr-small text-muted mt-1"></div>
</div>
<div class="form-row-2 mb-3">
  <div class="form-group"><label for="hr-leave-start">Từ ngày</label><input type="date" id="hr-leave-start" class="form-input" min="${today}" value="${today}"></div>
  <div class="form-group"><label for="hr-leave-end">Đến ngày</label><input type="date" id="hr-leave-end" class="form-input" min="${today}" value="${today}"></div>
</div>
<div class="form-group mb-3">
  <label for="hr-leave-reason">Lý do <span class="text-danger">*</span></label>
  <textarea id="hr-leave-reason" class="form-input" rows="3" maxlength="1000"></textarea>
</div>
<div class="form-group">
  <label for="hr-leave-doc">Tài liệu đính kèm (đường dẫn)</label>
  <input type="url" id="hr-leave-doc" class="form-input" placeholder="https://...">
</div>`, [
      { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
      { label: 'Gửi đơn', cls: 'btn-primary', action: submitLeave },
    ]);
    onLeaveTypeChange();
  }

  function onLeaveTypeChange() {
    const opt = document.getElementById('hr-leave-type')?.selectedOptions[0];
    const hint = document.getElementById('hr-leave-type-hint');
    if (!opt || !hint) return;
    const parts = [];
    if (opt.dataset.doc === '1') parts.push('Bắt buộc đính kèm tài liệu.');
    if (Number(opt.dataset.notice)) parts.push(`Báo trước ít nhất ${opt.dataset.notice} ngày.`);
    hint.textContent = parts.join(' ');
  }

  function submitLeave() {
    const input = {
      leaveTypeId: document.getElementById('hr-leave-type').value,
      startDate: document.getElementById('hr-leave-start').value,
      endDate: document.getElementById('hr-leave-end').value,
      reason: document.getElementById('hr-leave-reason').value,
      documentUrl: document.getElementById('hr-leave-doc').value,
    };
    const ok = run(() => HrStore.createLeave(input), 'Đã gửi đơn nghỉ.', false);
    if (ok) { App.closeModal(); App.navigate(_route()); }
  }

  function cancelLeave(id, version) {
    App.confirm('Hủy đơn nghỉ?', '', () => run(() => HrStore.cancelLeave(id, version), 'Đã hủy đơn nghỉ.'),
      { type: 'danger', okLabel: 'Hủy đơn' });
  }

  function showImpact(id) {
    let impact;
    try { impact = HrStore.impactOf(id); } catch (e) { App.toast(e.message, 'error'); return; }
    const body = impact.shifts.length ? `
      ${impact.understaffedAfterApproval ? `<div class="alert alert-warning mb-3"><div class="alert-content">
        ${impact.understaffedAfterApproval} ca sẽ thiếu người nếu đơn được duyệt.</div></div>` : ''}
      <div class="table-container"><table class="data-table">
        <thead><tr><th>Ca</th><th>Ngày</th><th>Giờ</th><th>Độ phủ hiện tại</th><th>Sau khi nghỉ</th></tr></thead>
        <tbody>${impact.shifts.map(s => `<tr><td>${escHtml(s.shiftName)}</td><td>${formatDate(s.date)}</td><td>${escHtml(s.time)}</td>
          <td>${s.confirmedNow}/${s.required}</td>
          <td>${s.understaffedAfter ? `<span class="badge badge-understaffed">${s.confirmedAfter}/${s.required} thiếu</span>` : `${s.confirmedAfter}/${s.required}`}</td></tr>`).join('')}
        </tbody></table></div>`
      : '<p class="text-muted">Không có ca nào bị ảnh hưởng trong khoảng nghỉ.</p>';
    App.openModal('Ảnh hưởng lên lịch', body, [{ label: 'Đóng', cls: 'btn-secondary', action: () => App.closeModal() }], 'modal-lg');
  }

  /* ════════════════════════════════════════════════════════════════
     CA TRỐNG — nhân viên + admin dùng chung route 'open-shifts'
  ════════════════════════════════════════════════════════════════ */
  function renderOpenShifts() {
    return isAdmin() ? _renderOpenShiftsAdmin() : _renderOpenShiftsUser();
  }

  function _deadlineText(o) { return formatDateTime(new Date(o.claimDeadline).toISOString()); }

  function _renderOpenShiftsUser() {
    const list = HrStore.listOpenShifts();
    const rows = list.map(o => {
      const canClaim = o.status === 'open' && (!o.myClaimStatus || o.myClaimStatus === 'withdrawn');
      return `<tr>
        <td>${shiftText(o.shift)}</td>
        <td>${o.slots - o.filledCount}/${o.slots}</td>
        <td>${o.requiredLevelId ? escHtml(State.get.levelById(o.requiredLevelId)?.name || o.requiredLevelId) : 'Mọi Level'}</td>
        <td>${_deadlineText(o)}</td>
        <td>${o.myClaimStatus ? badge(o.myClaimStatus) : badge(o.status)}</td>
        <td class="actions-cell">
          ${canClaim ? `<button class="btn btn-primary btn-sm" onclick="HrViews.claim('${o.id}')">Đăng ký nhận</button>` : ''}
          ${o.myClaimStatus === 'pending' ? `<button class="btn btn-ghost btn-sm" onclick="HrViews.withdraw('${o.id}')">Rút</button>` : ''}
        </td></tr>`;
    }).join('');
    return `
${pageHeader('Ca trống', 'Đăng ký nhận thêm ca còn thiếu người. Admin sẽ chọn người phù hợp; bạn được thông báo kết quả.')}
<div class="card">
  ${list.length ? `<div class="table-container"><table class="data-table">
    <thead><tr><th>Ca</th><th>Còn chỗ</th><th>Điều kiện</th><th>Hạn nhận</th><th>Trạng thái</th><th></th></tr></thead>
    <tbody>${rows}</tbody></table></div>` : empty('Hiện không có ca trống.')}
</div>`;
  }

  function claim(id) {
    App.confirm('Đăng ký nhận ca?', 'Hệ thống kiểm tra trùng lịch, giới hạn giờ và thời gian nghỉ trước khi ghi nhận.',
      () => run(() => HrStore.claimOpenShift(id, null), 'Đã đăng ký. Chờ Admin chọn.'), { type: 'info', okLabel: 'Đăng ký' });
  }
  function withdraw(id) { run(() => HrStore.withdrawClaim(id), 'Đã rút đăng ký.'); }

  function _renderOpenShiftsAdmin() {
    const list = HrStore.listOpenShifts();
    const gaps = HrStore.understaffedPublishedShifts();
    const rows = list.map(o => `<tr>
        <td>${shiftText(o.shift)}</td>
        <td>${o.filledCount}/${o.slots}</td>
        <td>${o.pendingClaims ? `<span class="badge badge-submitted">${o.pendingClaims} chờ</span>` : '0'}</td>
        <td>${_deadlineText(o)}</td>
        <td>${badge(o.status)}</td>
        <td class="actions-cell">
          <button class="btn btn-secondary btn-sm" onclick="HrViews.openClaims('${o.id}')">Đăng ký</button>
          ${o.status === 'open' ? `<button class="btn btn-ghost btn-sm" onclick="HrViews.closeOpen('${o.id}')">Đóng</button>` : ''}
        </td></tr>`).join('');
    return `
${pageHeader('Ca trống', 'Mở ca trống cho ca đã công bố còn thiếu người. Hệ thống không bao giờ xác nhận vượt số chỗ.',
  `<button class="btn btn-primary" onclick="HrViews.openCreateOpenShift()" ${gaps.length ? '' : 'disabled title="Không có ca đã công bố nào đang thiếu người"'}>+ Mở ca trống</button>`)}
<div class="stat-grid mb-4">
  ${stat('Ca đã công bố đang thiếu người', gaps.length, gaps.length ? 'warn' : '')}
  ${stat('Ca trống đang mở', list.filter(o => o.status === 'open').length)}
  ${stat('Đăng ký chờ chọn', list.reduce((n, o) => n + (o.status === 'open' ? o.pendingClaims : 0), 0))}
</div>
<div class="card">
  <div class="card-header"><span class="card-title">Danh sách ca trống</span></div>
  ${list.length ? `<div class="table-container"><table class="data-table">
    <thead><tr><th>Ca</th><th>Đã nhận</th><th>Đăng ký</th><th>Hạn nhận</th><th>Trạng thái</th><th></th></tr></thead>
    <tbody>${rows}</tbody></table></div>` : empty('Chưa có ca trống nào.', gaps.length ? 'Bấm "Mở ca trống" để tuyển thêm người.' : '')}
</div>`;
  }

  function openCreateOpenShift() {
    const gaps = HrStore.understaffedPublishedShifts();
    if (!gaps.length) { App.toast('Không có ca đã công bố nào đang thiếu người.', 'info'); return; }
    const first = gaps[0];
    App.openModal('Mở ca trống', `
<div class="form-group mb-3">
  <label for="hr-os-shift">Ca còn thiếu người</label>
  <select id="hr-os-shift" class="form-input" onchange="HrViews.onOpenShiftPick()">
    ${gaps.map(s => `<option value="${s.id}" data-missing="${s.missing}" data-start="${s.date}T${s.startTime}">${shiftText(s)} — thiếu ${s.missing}</option>`).join('')}
  </select>
</div>
<div class="form-row-2 mb-3">
  <div class="form-group"><label for="hr-os-slots">Số chỗ</label><input type="number" id="hr-os-slots" class="form-input" min="1" max="${first.missing}" value="${first.missing}"></div>
  <div class="form-group"><label for="hr-os-deadline">Hạn nhận ca</label><input type="datetime-local" id="hr-os-deadline" class="form-input"></div>
</div>
<div class="form-group mb-3">
  <label for="hr-os-level">Điều kiện Level</label>
  <select id="hr-os-level" class="form-input"><option value="">Mọi Level (theo quy tắc thay thế)</option>
    ${State.get.levels().map(l => `<option value="${l.id}">Tối thiểu ${escHtml(l.name)}</option>`).join('')}</select>
</div>
<div class="form-group"><label for="hr-os-note">Ghi chú</label><textarea id="hr-os-note" class="form-input" rows="2" maxlength="1000"></textarea></div>`, [
      { label: 'Hủy', cls: 'btn-secondary', action: () => App.closeModal() },
      { label: 'Mở ca trống', cls: 'btn-primary', action: submitOpenShift },
    ]);
    onOpenShiftPick();
  }

  function onOpenShiftPick() {
    const opt = document.getElementById('hr-os-shift')?.selectedOptions[0];
    if (!opt) return;
    const slots = document.getElementById('hr-os-slots');
    slots.max = opt.dataset.missing; slots.value = opt.dataset.missing;
    // Gợi ý hạn nhận = 2 giờ trước giờ bắt đầu ca (Admin sửa được)
    const start = new Date(opt.dataset.start);
    const suggestion = new Date(Math.max(Date.now() + 3600000, start.getTime() - 2 * 3600000));
    const pad = n => String(n).padStart(2, '0');
    document.getElementById('hr-os-deadline').value =
      `${suggestion.getFullYear()}-${pad(suggestion.getMonth() + 1)}-${pad(suggestion.getDate())}T${pad(suggestion.getHours())}:${pad(suggestion.getMinutes())}`;
    document.getElementById('hr-os-deadline').max = opt.dataset.start;
  }

  function submitOpenShift() {
    const input = {
      shiftId: document.getElementById('hr-os-shift').value,
      slots: document.getElementById('hr-os-slots').value,
      claimDeadline: document.getElementById('hr-os-deadline').value,
      requiredLevelId: document.getElementById('hr-os-level').value || null,
      note: document.getElementById('hr-os-note').value,
    };
    const ok = run(() => HrStore.createOpenShift(input), 'Đã mở ca trống và thông báo cho nhân viên phù hợp.', false);
    if (ok) { App.closeModal(); App.navigate('open-shifts'); }
  }

  function openClaims(openShiftId) {
    const o = HrStore.listOpenShifts().find(x => x.id === openShiftId);
    const claims = HrStore.claimsOf(openShiftId);
    const rows = claims.map(c => `<tr>
      <td>${escHtml(c.userName)}</td>
      <td>${formatDateTime(c.createdAt)}</td>
      <td>${badge(c.status)}${c.violations.length ? `<div class="text-danger hr-small">${c.violations.map(escHtml).join('<br>')}</div>` : ''}
          ${c.decisionNote ? `<div class="text-muted hr-small">${escHtml(c.decisionNote)}</div>` : ''}</td>
      <td class="actions-cell">${c.status === 'pending' && o.status === 'open' ? `
        <button class="btn btn-success btn-sm" onclick="HrViews.approveClaim('${openShiftId}','${c.id}',${c.version})">Chọn</button>
        <button class="btn btn-secondary btn-sm" onclick="HrViews.rejectClaim('${openShiftId}','${c.id}',${c.version})">Từ chối</button>` : ''}</td>
    </tr>`).join('');
    App.openModal(`Đăng ký — ${o ? o.shiftLabel : ''}`, `
      <p class="text-muted mb-3">Đã nhận ${o.filledCount}/${o.slots} chỗ. ${badge(o.status)}</p>
      ${claims.length ? `<div class="table-container"><table class="data-table">
        <thead><tr><th>Nhân viên</th><th>Đăng ký lúc</th><th>Trạng thái / kiểm tra</th><th></th></tr></thead>
        <tbody>${rows}</tbody></table></div>` : '<p class="text-muted">Chưa có ai đăng ký.</p>'}`,
      [{ label: 'Đóng', cls: 'btn-secondary', action: () => App.closeModal() }], 'modal-lg');
  }

  function approveClaim(openShiftId, claimId, version) {
    const ok = run(() => HrStore.approveClaim(openShiftId, claimId, version, null), 'Đã phân nhân viên vào ca.', false);
    if (ok) { App.closeModal(); App.navigate('open-shifts'); openClaims(openShiftId); }
  }
  function rejectClaim(openShiftId, claimId, version) {
    App.closeModal();
    App.confirm('Từ chối đăng ký?', '', reason => {
      run(() => HrStore.rejectClaim(openShiftId, claimId, version, reason), 'Đã từ chối đăng ký.');
    }, { type: 'warn', okLabel: 'Từ chối', requireReason: true, reasonLabel: 'Lý do (gửi cho nhân viên)' });
  }
  function closeOpen(id) {
    App.confirm('Đóng ca trống?', 'Các đăng ký còn chờ sẽ bị từ chối và được thông báo.',
      reason => run(() => HrStore.closeOpenShift(id, false, reason), 'Đã đóng ca trống.'),
      { type: 'warn', okLabel: 'Đóng ca trống', requireReason: true, reasonLabel: 'Lý do' });
  }

  /* ════════════════════════════════════════════════════════════════
     ADMIN — DUYỆT YÊU CẦU
  ════════════════════════════════════════════════════════════════ */
  let _approvalTab = 'shift';

  function renderApprovals() {
    const sum = HrStore.pendingSummary();
    const tabs = [
      ['shift', `Đổi ca / làm thay (${sum.awaitingApproval})`],
      ['leave', `Nghỉ phép (${sum.leaveAwaitingApproval})`],
      ['absence', 'Vắng đột xuất'],
    ];
    const content = _approvalTab === 'leave' ? _approvalsLeave()
      : _approvalTab === 'absence' ? _approvalsAbsence() : _approvalsShift();
    return `
${pageHeader('Duyệt yêu cầu', 'Mọi quyết định được kiểm tra lại với lịch hiện tại, ghi lịch sử và thông báo cho các bên liên quan.')}
<div class="stat-grid mb-4">
  ${stat('Đổi ca chờ duyệt', sum.awaitingApproval, sum.awaitingApproval ? 'warn' : '')}
  ${stat('Đơn nghỉ chờ duyệt', sum.leaveAwaitingApproval, sum.leaveAwaitingApproval ? 'warn' : '')}
  ${stat('Đăng ký ca trống chờ chọn', sum.openShiftClaimsAwaiting)}
</div>
<div class="tabs" role="tablist">
  ${tabs.map(([id, label]) => `<button class="tab-btn ${_approvalTab === id ? 'active' : ''}" role="tab"
     aria-selected="${_approvalTab === id}" onclick="HrViews.setApprovalTab('${id}')">${label}</button>`).join('')}
</div>
${content}`;
  }

  function setApprovalTab(tab) { _approvalTab = tab; App.navigate('approvals'); }

  function _approvalsShift() {
    const all = HrStore.listShiftRequests('all');
    const pending = all.filter(r => r.status === 'pending_approval');
    const waitingPeer = all.filter(r => r.status === 'pending_peer');
    const done = all.filter(r => !r.isOpen).slice(0, 30);
    const row = (r, actions) => {
      const violations = actions ? _requestCheck(r) : [];
      return `<tr>
      <td>${typeName(r.type)}</td>
      <td>${escHtml(r.requesterName)} → <strong>${escHtml(r.targetUserName)}</strong></td>
      <td>${shiftText(r.sourceShift)}${r.targetShift ? `<div class="text-muted hr-small">⇄ ${shiftText(r.targetShift)}</div>` : ''}</td>
      <td class="hr-reason">${escHtml(r.reason)}</td>
      <td>${badge(r.status)}${violations.length ? `<div class="text-danger hr-small">${violations.map(escHtml).join('<br>')}</div>` : ''}
          ${r.decisionNote ? `<div class="text-muted hr-small">${escHtml(r.decisionNote)}</div>` : ''}
          ${r.decidedByName ? `<div class="text-muted hr-small">${escHtml(r.decidedByName)} · ${formatDateTime(r.decidedAt)}</div>` : ''}</td>
      <td class="actions-cell">${actions ? `
        <button class="btn btn-success btn-sm" onclick="HrViews.approveRequest('${r.id}', ${r.version})" ${violations.length ? 'disabled title="Vi phạm ràng buộc lịch"' : ''}>Duyệt</button>
        <button class="btn btn-secondary btn-sm" onclick="HrViews.rejectRequest('${r.id}', ${r.version})">Từ chối</button>` : ''}</td>
    </tr>`;
    };
    const table = (rows, withActions) => `<div class="table-container"><table class="data-table">
      <thead><tr><th>Loại</th><th>Người gửi → người nhận</th><th>Ca</th><th>Lý do</th><th>Trạng thái</th><th></th></tr></thead>
      <tbody>${rows.map(r => row(r, withActions)).join('')}</tbody></table></div>`;
    return `
<div class="card mb-4"><div class="card-header"><span class="card-title">Chờ duyệt (đồng nghiệp đã đồng ý)</span></div>
  ${pending.length ? table(pending, true) : empty('Không có yêu cầu chờ duyệt.')}</div>
<div class="card mb-4"><div class="card-header"><span class="card-title">Đang chờ đồng nghiệp phản hồi</span></div>
  ${waitingPeer.length ? table(waitingPeer, false) : empty('Không có.')}</div>
<div class="card"><div class="card-header"><span class="card-title">Đã xử lý gần đây</span></div>
  ${done.length ? table(done, false) : empty('Chưa có.')}</div>`;
  }

  /** Kiểm tra trước để Admin thấy vi phạm trước khi bấm duyệt (vẫn kiểm tra lại khi bấm). */
  function _requestCheck(r) {
    try {
      const out = HrStore.validateTake(r.targetUserId, r.sourceShiftId, r.requesterId, r.targetAssignmentId ? [r.targetAssignmentId] : [])
        .map(m => `Người nhận: ${m}`);
      if (r.type === 'swap') {
        HrStore.validateTake(r.requesterId, r.targetShiftId, r.targetUserId, [r.sourceAssignmentId]).forEach(m => out.push(`Người gửi: ${m}`));
      }
      return out;
    } catch { return []; }
  }

  function approveRequest(id, version) {
    App.confirm('Duyệt yêu cầu?', 'Lịch chính thức sẽ được cập nhật ngay, hai nhân viên nhận thông báo.',
      () => run(() => HrStore.approveShiftRequest(id, version, null), 'Đã duyệt và cập nhật lịch.'),
      { type: 'info', okLabel: 'Duyệt' });
  }
  function rejectRequest(id, version) {
    App.confirm('Từ chối yêu cầu?', '', reason => run(() => HrStore.rejectShiftRequest(id, version, reason), 'Đã từ chối yêu cầu.'),
      { type: 'warn', okLabel: 'Từ chối', requireReason: true, reasonLabel: 'Lý do từ chối' });
  }

  function _approvalsLeave() {
    const all = HrStore.listLeave('all');
    const pending = all.filter(l => l.status === 'pending');
    const done = all.filter(l => l.status !== 'pending').slice(0, 30);
    const row = (l, actions) => {
      let impact = { shifts: [], understaffedAfterApproval: 0 };
      if (actions) { try { impact = HrStore.impactOf(l.id); } catch { /* bỏ qua */ } }
      return `<tr>
        <td>${escHtml(l.userName)}</td>
        <td>${escHtml(l.leaveTypeName)}</td>
        <td>${formatDate(l.startDate)}${l.endDate !== l.startDate ? ' → ' + formatDate(l.endDate) : ''}</td>
        <td class="hr-reason">${escHtml(l.reason)}${l.documentUrl ? `<div class="hr-small"><a href="${escHtml(l.documentUrl)}" target="_blank" rel="noopener noreferrer">Tài liệu</a></div>` : ''}</td>
        <td>${actions ? `<button class="btn btn-ghost btn-sm" onclick="HrViews.showImpact('${l.id}')">${impact.shifts.length} ca${impact.understaffedAfterApproval ? ` · <span class="text-danger">${impact.understaffedAfterApproval} sẽ thiếu</span>` : ''}</button>`
          : badge(l.status) + (l.decisionNote ? `<div class="text-muted hr-small">${escHtml(l.decisionNote)}</div>` : '')}</td>
        <td class="actions-cell">${actions ? `
          <button class="btn btn-success btn-sm" onclick="HrViews.approveLeave('${l.id}', ${l.version}, ${impact.shifts.length}, ${impact.understaffedAfterApproval})">Duyệt</button>
          <button class="btn btn-secondary btn-sm" onclick="HrViews.rejectLeave('${l.id}', ${l.version})">Từ chối</button>` : ''}</td>
      </tr>`;
    };
    const head = actions => `<thead><tr><th>Nhân viên</th><th>Loại</th><th>Thời gian</th><th>Lý do</th><th>${actions ? 'Ảnh hưởng' : 'Trạng thái'}</th><th></th></tr></thead>`;
    return `
<div class="card mb-4"><div class="card-header"><span class="card-title">Đơn nghỉ chờ duyệt</span></div>
  ${pending.length ? `<div class="table-container"><table class="data-table">${head(true)}<tbody>${pending.map(l => row(l, true)).join('')}</tbody></table></div>`
    : empty('Không có đơn nghỉ chờ duyệt.')}</div>
<div class="card"><div class="card-header"><span class="card-title">Đã xử lý gần đây</span></div>
  ${done.length ? `<div class="table-container"><table class="data-table">${head(false)}<tbody>${done.map(l => row(l, false)).join('')}</tbody></table></div>`
    : empty('Chưa có.')}</div>`;
  }

  function approveLeave(id, version, affected, understaffed) {
    const msg = affected
      ? `Nhân viên sẽ được gỡ khỏi ${affected} ca.${understaffed ? ` ${understaffed} ca sẽ thiếu người — bạn sẽ nhận cảnh báo để mở ca trống.` : ''}`
      : 'Không có ca nào bị ảnh hưởng.';
    App.confirm('Duyệt đơn nghỉ?', msg, () => run(() => HrStore.approveLeave(id, version, null), 'Đã duyệt đơn nghỉ.'),
      { type: understaffed ? 'warn' : 'info', okLabel: 'Duyệt' });
  }
  function rejectLeave(id, version) {
    App.confirm('Từ chối đơn nghỉ?', '', reason => run(() => HrStore.rejectLeave(id, version, reason), 'Đã từ chối đơn nghỉ.'),
      { type: 'warn', okLabel: 'Từ chối', requireReason: true, reasonLabel: 'Lý do từ chối' });
  }

  function _approvalsAbsence() {
    const today = _fmtDate(new Date());
    const from = _addDays(today, -30);
    const report = HrStore.absenceReport(from, today);
    const recent = State.get.shifts().filter(s => s.date <= today && s.date >= _addDays(today, -7))
      .sort((a, b) => b.date.localeCompare(a.date) || b.startTime.localeCompare(a.startTime));
    const options = recent.flatMap(s => State.get.assignmentsByShift(s.id).map(a =>
      `<option value="${a.id}">${formatDate(s.date)} ${escHtml(s.name)} — ${escHtml(State.get.userById(a.userId)?.fullName || a.userId)}</option>`)).join('');
    return `
<div class="card mb-4"><div class="card-header"><span class="card-title">Ghi nhận vắng đột xuất</span></div>
  <div class="card-body">
    ${options ? `<div class="form-row-2 mb-3">
      <div class="form-group"><label for="hr-abs-asg">Phân công (7 ngày gần đây)</label><select id="hr-abs-asg" class="form-input">${options}</select></div>
      <div class="form-group"><label for="hr-abs-reason">Lý do</label><input id="hr-abs-reason" class="form-input" maxlength="1000" placeholder="Ví dụ: không đến, không báo trước"></div>
    </div>
    <button class="btn btn-primary" onclick="HrViews.recordAbsence()">Ghi nhận</button>` : '<p class="text-muted">Không có phân công nào trong 7 ngày gần đây.</p>'}
  </div></div>
<div class="card"><div class="card-header"><span class="card-title">Tỷ lệ vắng 30 ngày (${formatDate(from)} – ${formatDate(today)})</span></div>
  <div class="card-body">
    <div class="stat-grid mb-3">${stat('Lượt vắng', report.absences)}${stat('Phân công trong kỳ', report.confirmedAssignments)}${stat('Tỷ lệ vắng', report.absenceRatePercent + '%')}</div>
    ${report.items.length ? `<div class="table-container"><table class="data-table"><thead><tr><th>Ngày</th><th>Ca</th><th>Nhân viên</th><th>Lý do</th></tr></thead>
      <tbody>${report.items.map(x => `<tr><td>${formatDate(x.shift.date)}</td><td>${escHtml(x.shift.name)}</td><td>${escHtml(x.userName)}</td><td>${escHtml(x.reason)}</td></tr>`).join('')}</tbody></table></div>`
      : '<p class="text-muted">Chưa có lượt vắng nào.</p>'}
  </div></div>`;
  }

  function recordAbsence() {
    const input = { assignmentId: document.getElementById('hr-abs-asg').value, reason: document.getElementById('hr-abs-reason').value };
    run(() => HrStore.recordAbsence(input), 'Đã ghi nhận vắng đột xuất.');
  }

  /* ════════════════════════════════════════════════════════════════
     THÔNG BÁO
  ════════════════════════════════════════════════════════════════ */
  let _notifUnreadOnly = false;

  function renderNotifications() {
    HrStore.runDeadlineReminders();
    const page = HrStore.listNotifications({ unreadOnly: _notifUnreadOnly, size: 100 });
    const items = page.items.map(n => `
      <li class="hr-notif-item ${n.read ? '' : 'unread'}">
        <button class="hr-notif-link" onclick="HrViews.openNotification('${n.id}')">
          <span class="hr-notif-title">${escHtml(n.title)}</span>
          <span class="hr-notif-body">${escHtml(n.body)}</span>
          <span class="hr-notif-time">${formatDateTime(n.createdAt)}</span>
        </button>
      </li>`).join('');
    return `
${pageHeader('Thông báo', `${page.unread} chưa đọc`, `
  <button class="btn btn-secondary" onclick="HrViews.toggleUnreadOnly()">${_notifUnreadOnly ? 'Hiện tất cả' : 'Chỉ chưa đọc'}</button>
  <button class="btn btn-primary" onclick="HrViews.markAllRead()" ${page.unread ? '' : 'disabled'}>Đánh dấu đã đọc tất cả</button>`)}
${isAdmin() ? _settingsCard() : ''}
<div class="card">
  ${page.items.length ? `<ul class="hr-notif-list">${items}</ul>` : empty(_notifUnreadOnly ? 'Không có thông báo chưa đọc.' : 'Chưa có thông báo nào.')}
</div>`;
  }

  function _settingsCard() {
    const s = HrStore.getSettings();
    return `<div class="card mb-4"><div class="card-header"><span class="card-title">Nhắc hạn đăng ký thời gian rảnh</span></div>
      <div class="card-body">
        ${s.configured ? `<p class="mb-3">Đang nhắc nhân viên chưa gửi đăng ký trước hạn: <strong>${s.deadlineReminderHours.join(' giờ, ')} giờ</strong>.</p>`
          : '<div class="alert alert-warning mb-3"><div class="alert-content">Chưa cấu hình mốc nhắc — hệ thống sẽ không gửi nhắc hạn.</div></div>'}
        <div class="form-row-2">
          <div class="form-group"><label for="hr-remind-hours">Mốc nhắc (giờ trước hạn, phân tách bằng dấu phẩy)</label>
            <input id="hr-remind-hours" class="form-input" placeholder="Ví dụ: 48, 24" value="${s.deadlineReminderHours.join(', ')}"></div>
          <div class="form-group hr-align-end"><button class="btn btn-primary" onclick="HrViews.saveSettings()">Lưu cấu hình</button></div>
        </div>
      </div></div>`;
  }

  function saveSettings() {
    const raw = document.getElementById('hr-remind-hours').value;
    const hours = raw.split(',').map(x => x.trim()).filter(Boolean);
    run(() => HrStore.updateSettings(hours), 'Đã lưu mốc nhắc hạn.');
  }
  function toggleUnreadOnly() { _notifUnreadOnly = !_notifUnreadOnly; App.navigate('notifications'); }
  function markAllRead() { run(() => HrStore.markAllRead(), 'Đã đánh dấu đã đọc.'); }

  function openNotification(id) {
    let n;
    try { n = HrStore.markRead(id); } catch (e) { App.toast(e.message, 'error'); return; }
    closeBell();
    refreshShell();
    App.navigate(n.linkRoute || 'notifications');
  }

  /* ════════════════════════════════════════════════════════════════
     SHELL — chuông thông báo + badge menu
  ════════════════════════════════════════════════════════════════ */
  function mountShell() {
    HrStore.ensureSeed();
    const right = document.querySelector('.topbar-right');
    if (right && !document.getElementById('hr-bell')) {
      const wrap = document.createElement('div');
      wrap.className = 'hr-bell-wrap';
      wrap.id = 'hr-bell';
      wrap.innerHTML = `
        <button type="button" class="hr-bell-btn" id="hr-bell-btn" aria-haspopup="true" aria-expanded="false"
                aria-controls="hr-bell-panel" onclick="HrViews.toggleBell()">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
          <span class="hr-bell-count hidden" id="hr-bell-count"></span>
        </button>
        <div class="hr-bell-panel hidden" id="hr-bell-panel" role="region" aria-label="Thông báo mới"></div>`;
      right.insertBefore(wrap, document.getElementById('topbar-user'));
      document.addEventListener('click', e => { if (!wrap.contains(e.target)) closeBell(); });
      document.addEventListener('keydown', e => { if (e.key === 'Escape') closeBell(); });
    }
    if (!mountShell._timer) mountShell._timer = setInterval(() => { if (user()) refreshShell(); }, 15000);
    refreshShell();
  }

  function refreshShell() {
    if (!user()) return;
    HrStore.runDeadlineReminders();
    const n = HrStore.unreadCount();
    const count = document.getElementById('hr-bell-count');
    const btn = document.getElementById('hr-bell-btn');
    if (count) { count.textContent = n > 99 ? '99+' : String(n); count.classList.toggle('hidden', n === 0); }
    if (btn) btn.setAttribute('aria-label', n ? `Thông báo, ${n} chưa đọc` : 'Thông báo');
    // Số việc chờ trên menu
    const sum = HrStore.pendingSummary();
    _navBadge('approvals', sum.totalForApprover || 0);
    _navBadge('my-requests', sum.incomingPeer || 0);
    _navBadge('notifications', n);
  }

  function _navBadge(route, value) {
    const item = document.querySelector(`.sidebar-nav-item[data-route="${route}"]`);
    if (!item) return;
    let b = item.querySelector('.hr-nav-count');
    if (!value) { b?.remove(); return; }
    if (!b) { b = document.createElement('span'); b.className = 'hr-nav-count'; item.appendChild(b); }
    b.textContent = value > 99 ? '99+' : String(value);
  }

  function toggleBell() {
    const panel = document.getElementById('hr-bell-panel');
    if (!panel) return;
    if (!panel.classList.contains('hidden')) { closeBell(); return; }
    const page = HrStore.listNotifications({ size: 6 });
    panel.innerHTML = `
      <div class="hr-bell-head"><strong>Thông báo</strong>
        ${page.unread ? `<button class="btn btn-ghost btn-sm" onclick="HrViews.markAllRead()">Đọc tất cả</button>` : ''}</div>
      ${page.items.length ? `<ul class="hr-notif-list compact">${page.items.map(n => `
        <li class="hr-notif-item ${n.read ? '' : 'unread'}"><button class="hr-notif-link" onclick="HrViews.openNotification('${n.id}')">
          <span class="hr-notif-title">${escHtml(n.title)}</span><span class="hr-notif-body">${escHtml(n.body)}</span>
          <span class="hr-notif-time">${formatDateTime(n.createdAt)}</span></button></li>`).join('')}</ul>`
        : '<p class="hr-bell-empty">Chưa có thông báo.</p>'}
      <button class="hr-bell-all" onclick="HrViews.closeBell(); App.navigate('notifications')">Xem tất cả</button>`;
    panel.classList.remove('hidden');
    document.getElementById('hr-bell-btn')?.setAttribute('aria-expanded', 'true');
  }
  function closeBell() {
    document.getElementById('hr-bell-panel')?.classList.add('hidden');
    document.getElementById('hr-bell-btn')?.setAttribute('aria-expanded', 'false');
  }

  /* Khôi phục dữ liệu demo cũng xóa dữ liệu yêu cầu/thông báo để không trỏ tới phân công cũ. */
  if (typeof resetDemoData === 'function') {
    const originalReset = resetDemoData;
    // eslint-disable-next-line no-global-assign
    resetDemoData = function () { originalReset(); HrStore.resetAll(); };
  }

  return {
    // routes
    renderMyRequests, renderLeave, renderOpenShifts, renderApprovals, renderNotifications,
    // shell
    mountShell, refreshShell, toggleBell, closeBell,
    // đổi ca
    openCreateRequest, refreshCandidates, refreshSwapOptions, peerAccept, peerDecline, cancelRequest,
    approveRequest, rejectRequest, setApprovalTab,
    // nghỉ phép
    openCreateLeave, onLeaveTypeChange, cancelLeave, showImpact, approveLeave, rejectLeave, recordAbsence,
    // ca trống
    claim, withdraw, openCreateOpenShift, onOpenShiftPick, openClaims, approveClaim, rejectClaim, closeOpen,
    // thông báo
    toggleUnreadOnly, markAllRead, openNotification, saveSettings,
  };
})();
