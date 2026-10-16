/**
 * admin3.js — Admin màn hình 6, 7, 8, 9
 * 5.6 Quy tắc phân lịch
 * 5.7 Phân lịch tự động
 * 5.8 Duyệt và công bố lịch
 * 5.9 Lịch sử và báo cáo
 */

/* ═══════════════════════════════════════════════════════════
   5.6 QUY TẮC PHÂN LỊCH
═══════════════════════════════════════════════════════════ */
const AdminRules = (() => {

function render() {
  const rules  = State.get.rules() || {};
  const levels = State.get.levels();

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Quy tắc phân lịch</h1>
    <p class="page-subtitle">Cấu hình toàn bộ tham số phân lịch</p>
  </div>
</div>

<form id="rules-form" novalidate>
<div style="display:grid;grid-template-columns:1fr 1fr;gap:20px">

  <!-- Cột trái -->
  <div>
    <div class="card mb-4">
      <div class="card-header"><span class="card-title">Giờ làm & Mục tiêu</span></div>
      <div class="card-body" style="display:flex;flex-direction:column;gap:14px">
        <div class="form-group">
          <label class="form-label">Giờ chuẩn mỗi kỳ</label>
          <input type="number" name="standardHours" class="form-input" value="${rules.standardHours||80}" min="1">
          <span class="form-hint">Giờ mục tiêu = Giờ chuẩn × Tỷ lệ / 100</span>
          <span class="field-error" data-err="standardHours"></span>
        </div>
        <div class="form-group">
          <label class="form-label">Giờ tối đa mỗi ngày</label>
          <input type="number" name="maxHoursPerDay" class="form-input" value="${rules.maxHoursPerDay||12}" min="1" max="24">
          <span class="field-error" data-err="maxHoursPerDay"></span>
        </div>
        <div class="form-group">
          <label class="form-label">Giờ tối đa mỗi tuần</label>
          <input type="number" name="maxHoursPerWeek" class="form-input" value="${rules.maxHoursPerWeek||48}" min="1" max="168">
          <span class="field-error" data-err="maxHoursPerWeek"></span>
        </div>
        <div class="form-group">
          <label class="form-label">Nghỉ tối thiểu giữa 2 ca (giờ)</label>
          <input type="number" name="minRestBetweenShifts" class="form-input" value="${rules.minRestBetweenShifts||8}" min="0" max="24">
          <span class="field-error" data-err="minRestBetweenShifts"></span>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header"><span class="card-title">Tỷ lệ giờ mục tiêu theo Level (%)</span></div>
      <div class="card-body" style="display:flex;flex-direction:column;gap:10px">
        ${levels.map(l => `
          <div style="display:flex;align-items:center;gap:12px">
            <span class="badge" style="width:90px;background:${l.color}22;color:${l.color}">${escHtml(l.name)}</span>
            <input type="number" name="lvl_rate_${l.id}" class="form-input" style="width:80px"
                   value="${rules.levelTargetRates?.[l.id] ?? 100}" min="0" max="200">
            <span class="form-hint">%</span>
          </div>`).join('')}
        <span class="form-hint">Cấu hình riêng của từng nhân viên sẽ được ưu tiên hơn.</span>
      </div>
    </div>
  </div>

  <!-- Cột phải -->
  <div>
    <div class="card mb-4">
      <div class="card-header">
        <span class="card-title">Quy tắc thay thế Level</span>
        <button type="button" class="btn btn-secondary btn-sm" onclick="AdminRules.addSubstitution()">+ Thêm quy tắc</button>
      </div>
      <div class="card-body">
        <p class="form-hint mb-3">Level cao hơn có thể đảm nhận vị trí Level thấp hơn nếu có quy tắc cho phép.</p>
        <div id="sub-rules-list">
          ${_renderSubRules(rules.levelSubstitutions || [])}
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header"><span class="card-title">Thứ tự ưu tiên & Xử lý</span></div>
      <div class="card-body" style="display:flex;flex-direction:column;gap:14px">
        <div class="form-group">
          <label class="form-label">Thứ tự ưu tiên phân lịch</label>
          <select name="schedulingPriority" class="form-input">
            <option value="most_needed" ${rules.schedulingPriority==='most_needed'?'selected':''}>Ưu tiên người thiếu nhiều giờ nhất</option>
            <option value="least_hours" ${rules.schedulingPriority==='least_hours'?'selected':''}>Ưu tiên người có ít giờ nhất</option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">Xử lý ca thiếu nhân sự</label>
          <select name="understaffedPolicy" class="form-input">
            <option value="warn"  ${rules.understaffedPolicy==='warn' ?'selected':''}>Cảnh báo, cho phép tiếp tục</option>
            <option value="block" ${rules.understaffedPolicy==='block'?'selected':''}>Chặn công bố nếu thiếu người</option>
          </select>
        </div>
      </div>
    </div>
  </div>
</div>

<div class="form-footer">
  <button type="button" class="btn btn-primary" onclick="AdminRules.save()">Lưu cấu hình</button>
</div>
</form>`;
}

let _subRules = [];

function _renderSubRules(rules) {
  _subRules = [...rules];
  const levels = State.get.levels();
  if (!_subRules.length) return '<p class="text-muted text-sm">Chưa có quy tắc thay thế nào.</p>';
  return _subRules.map((r, i) => `
    <div class="level-config-row" style="margin-bottom:8px">
      <select class="form-input" style="width:130px" onchange="AdminRules.updateSubRule(${i},'higher',this.value)">
        ${levels.map(l => `<option value="${l.id}" ${r.higherLevelId===l.id?'selected':''}>${escHtml(l.name)}</option>`).join('')}
      </select>
      <span class="text-sm text-secondary">có thể thay</span>
      <select class="form-input" style="width:130px" onchange="AdminRules.updateSubRule(${i},'lower',this.value)">
        ${levels.map(l => `<option value="${l.id}" ${r.lowerLevelId===l.id?'selected':''}>${escHtml(l.name)}</option>`).join('')}
      </select>
      <button type="button" class="btn btn-danger btn-xs" onclick="AdminRules.removeSubRule(${i})">Xóa</button>
    </div>`).join('');
}

function addSubstitution() {
  const levels = State.get.levels();
  if (levels.length < 2) { App.toast('Cần ít nhất 2 Level để tạo quy tắc thay thế.', 'warning'); return; }
  _subRules.push({ higherLevelId: levels[levels.length-1].id, lowerLevelId: levels[0].id });
  document.getElementById('sub-rules-list').innerHTML = _renderSubRules(_subRules);
}

function updateSubRule(idx, key, val) {
  if (key === 'higher') _subRules[idx].higherLevelId = val;
  else _subRules[idx].lowerLevelId = val;
}

function removeSubRule(idx) {
  _subRules.splice(idx, 1);
  document.getElementById('sub-rules-list').innerHTML = _renderSubRules(_subRules);
}

function save() {
  const form = document.getElementById('rules-form');
  if (!form) return;
  clearFormErrors(form);
  const levels = State.get.levels();

  const data = {
    id:                  'rules_main',
    standardHours:       parseFloat(form.querySelector('[name="standardHours"]').value),
    maxHoursPerDay:      parseFloat(form.querySelector('[name="maxHoursPerDay"]').value),
    maxHoursPerWeek:     parseFloat(form.querySelector('[name="maxHoursPerWeek"]').value),
    minRestBetweenShifts:parseFloat(form.querySelector('[name="minRestBetweenShifts"]').value),
    schedulingPriority:  form.querySelector('[name="schedulingPriority"]').value,
    understaffedPolicy:  form.querySelector('[name="understaffedPolicy"]').value,
    levelSubstitutions:  _subRules,
    levelTargetRates: {},
  };
  levels.forEach(l => {
    const inp = form.querySelector(`[name="lvl_rate_${l.id}"]`);
    if (inp) data.levelTargetRates[l.id] = parseFloat(inp.value) || 100;
  });

  const result = Validate.rulesForm(data);
  if (!result.valid) { showFieldErrors(form, result.errors); return; }

  const oldRules = State.get.rules();
  State.mut.saveRules(data);
  logHistory('rules_update', 'Cập nhật quy tắc phân lịch',
    'Cập nhật cấu hình quy tắc phân lịch.',
    State.get.currentUser()?.id || 'admin', oldRules, data);

  App.toast('Đã lưu cấu hình quy tắc.', 'success');
}

return { render, addSubstitution, updateSubRule, removeSubRule, save };
})();

/* ═══════════════════════════════════════════════════════════
   5.7 PHÂN LỊCH TỰ ĐỘNG
═══════════════════════════════════════════════════════════ */
const AdminAutoSchedule = (() => {
let _lastResult = null;

function render() {
  const periods = State.get.periods();
  const activePeriod = periods.find(p => p.status === 'open' || p.status === 'locked') || periods[0];

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Phân lịch tự động</h1>
    <p class="page-subtitle">Chạy thuật toán phân lịch và xem kết quả nháp</p>
  </div>
</div>

<div style="display:grid;grid-template-columns:340px 1fr;gap:20px;align-items:start">
  <!-- Left panel: Controls -->
  <div>
    <div class="card mb-4">
      <div class="card-header"><span class="card-title">Cài đặt</span></div>
      <div class="card-body" style="display:flex;flex-direction:column;gap:14px">
        <div class="form-group">
          <label class="form-label">Kỳ phân lịch</label>
          <select id="sched-period-select" class="form-input" aria-label="Chọn kỳ để phân lịch">
            ${periods.map(p => `<option value="${p.id}" ${activePeriod&&p.id===activePeriod.id?'selected':''}>${escHtml(p.name)}</option>`).join('')}
          </select>
        </div>
        <div id="pre-check-panel">${activePeriod ? _renderPreCheck(activePeriod.id) : ''}</div>
        <div style="display:flex;gap:8px;flex-direction:column">
          <button class="btn btn-secondary" onclick="AdminAutoSchedule.refreshPreCheck()">Kiểm tra dữ liệu đầu vào</button>
          <button class="btn btn-primary" id="run-sched-btn" onclick="AdminAutoSchedule.runSchedule(true)">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>
            Chạy thử (Xem nháp)
          </button>
          <button class="btn btn-success" id="apply-sched-btn" onclick="AdminAutoSchedule.runSchedule(false)" ${!_lastResult?'disabled':''}>
            Áp dụng kết quả
          </button>
        </div>
        ${_lastResult ? `<div class="alert alert-success">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
          <div>Lần chạy trước: ${_lastResult.totalAssignments} phân công, ${_lastResult.understaffed.length} ca thiếu người.</div>
        </div>` : ''}
      </div>
    </div>
  </div>

  <!-- Right panel: Results -->
  <div id="sched-result-panel">
    ${_lastResult ? _renderResult(_lastResult) : `
    <div class="card"><div class="card-body"><div class="empty-state">
      <div class="empty-state-title">Chưa có kết quả</div>
      <div class="empty-state-desc">Chọn kỳ và nhấn "Chạy thử" để xem kết quả nháp.</div>
    </div></div></div>`}
  </div>
</div>`;
}

function _renderPreCheck(periodId) {
  const check = Scheduler.preRunCheck(periodId);
  if (check.ok && check.issues.length === 0) {
    return `<div class="alert alert-success"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg><div>Dữ liệu hợp lệ, sẵn sàng phân lịch.</div></div>`;
  }
  return `<div class="alert alert-warning">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
    <div><div class="alert-title">Lưu ý (${check.issues.length})</div>
      <ul style="margin:4px 0 0 14px;font-size:0.786rem">${check.issues.map(i=>`<li>${escHtml(i)}</li>`).join('')}</ul>
    </div>
  </div>`;
}

function refreshPreCheck() {
  const periodId = document.getElementById('sched-period-select')?.value;
  if (!periodId) return;
  document.getElementById('pre-check-panel').innerHTML = _renderPreCheck(periodId);
}

function runSchedule(dryRun) {
  const periodId = document.getElementById('sched-period-select')?.value;
  if (!periodId) { App.toast('Vui lòng chọn kỳ đăng ký.', 'warning'); return; }

  const btn = document.getElementById(dryRun ? 'run-sched-btn' : 'apply-sched-btn');
  if (btn) { btn.disabled = true; btn.innerHTML = '<span class="loading-spinner" style="display:inline-block"></span> Đang xử lý...'; }

  // Simulate async
  setTimeout(() => {
    try {
      const result = Scheduler.run(periodId, { dryRun });
      _lastResult = result;

      if (!dryRun && result.success) {
        App.toast(`Đã áp dụng ${result.totalAssignments} phân công.`, 'success');
      }

      document.getElementById('sched-result-panel').innerHTML = _renderResult(result);
      const applyBtn = document.getElementById('apply-sched-btn');
      if (applyBtn && dryRun && result.success) applyBtn.disabled = false;
    } catch (err) {
      App.toast('Lỗi khi chạy phân lịch: ' + err.message, 'error');
    }
    if (btn) { btn.disabled = false; btn.innerHTML = dryRun ? '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg> Chạy thử (Xem nháp)' : 'Áp dụng kết quả'; }
  }, 300);
}

function _renderResult(result) {
  if (!result.success) {
    return `<div class="card"><div class="card-body"><div class="alert alert-danger"><div>${escHtml(result.error||'Lỗi không xác định.')}</div></div></div></div>`;
  }
  return `
<div style="display:flex;flex-direction:column;gap:16px">
  <div class="stat-grid">
    <div class="stat-card"><div class="stat-label">Tổng ca</div><div class="stat-value">${result.totalShifts}</div></div>
    <div class="stat-card"><div class="stat-label">Phân công</div><div class="stat-value text-success">${result.totalAssignments}</div></div>
    <div class="stat-card"><div class="stat-label">Ca thiếu người</div><div class="stat-value ${result.understaffed.length?'text-danger':'text-success'}">${result.understaffed.length}</div></div>
    <div class="stat-card"><div class="stat-label">NV chưa đủ giờ</div><div class="stat-value ${result.underTarget?.length?'text-warning':'text-success'}">${result.underTarget?.length||0}</div></div>
  </div>

  ${result.understaffed.length ? `
  <div class="card">
    <div class="card-header"><span class="card-title text-danger">Ca thiếu nhân sự (${result.understaffed.length})</span></div>
    <div class="table-container">
    <table class="data-table"><thead><tr><th>Ca</th><th>Ngày</th><th>Yêu cầu</th><th>Đã phân</th><th>Thiếu</th></tr></thead><tbody>
    ${result.understaffed.map(u => `<tr>
      <td>${escHtml(u.shift.name)}</td>
      <td>${formatDate(u.shift.date)}</td>
      <td>${u.required}</td>
      <td>${u.filled}</td>
      <td class="text-danger font-medium">${u.missing}</td>
    </tr>`).join('')}
    </tbody></table></div>
  </div>` : ''}

  ${result.underTarget?.length ? `
  <div class="card">
    <div class="card-header"><span class="card-title text-warning">Nhân viên chưa đủ giờ mục tiêu</span></div>
    <div class="table-container">
    <table class="data-table"><thead><tr><th>Nhân viên</th><th>Giờ MT</th><th>Giờ đã phân</th><th>Còn thiếu</th></tr></thead><tbody>
    ${result.underTarget.map(x => `<tr>
      <td>${escHtml(x.user.fullName)}</td>
      <td>${x.target}h</td>
      <td>${x.assigned.toFixed(1)}h</td>
      <td class="text-warning font-medium">${x.diff.toFixed(1)}h</td>
    </tr>`).join('')}
    </tbody></table></div>
  </div>` : ''}

  ${result.rejectedCandidates?.length ? `
  <div class="card">
    <div class="card-header">
      <span class="card-title">Lý do ứng viên bị loại (${result.rejectedCandidates.length})</span>
    </div>
    <div class="card-body" style="max-height:300px;overflow-y:auto">
      ${result.rejectedCandidates.slice(0,20).map(r => {
        const user  = State.get.userById(r.userId);
        const shift = State.get.shiftById(r.shiftId);
        return `<div style="padding:6px 0;border-bottom:1px solid var(--border);font-size:0.857rem">
          <strong>${escHtml(user?.fullName||r.userId)}</strong> — ${escHtml(shift?.name||r.shiftId)} (${shift?formatDate(shift.date):''})<br>
          <span class="text-secondary">${r.reasons.map(m=>escHtml(m)).join('; ')}</span>
        </div>`;
      }).join('')}
      ${result.rejectedCandidates.length > 20 ? `<p class="text-muted text-sm mt-2">...và ${result.rejectedCandidates.length-20} lý do khác.</p>` : ''}
    </div>
  </div>` : ''}

  <div class="card">
    <div class="card-header"><span class="card-title">Nhật ký thuật toán</span></div>
    <div class="card-body"><pre style="font-size:0.714rem;white-space:pre-wrap;max-height:300px;overflow-y:auto;color:var(--text-secondary);background:var(--gray-50);padding:12px;border-radius:var(--radius)">${escHtml(result.log.join('\n'))}</pre></div>
  </div>
</div>`;
}

return { render, refreshPreCheck, runSchedule };
})();

/* ═══════════════════════════════════════════════════════════
   5.8 DUYỆT VÀ CÔNG BỐ LỊCH
═══════════════════════════════════════════════════════════ */
const AdminPublish = (() => {
let _pubPeriodId = null;

function render() {
  const periods = State.get.periods();
  const period  = periods.find(p => p.status === 'open' || p.status === 'locked') || periods[0];
  _pubPeriodId  = period?.id || null;

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Duyệt và công bố lịch</h1>
  </div>
  <div class="page-actions">
    <select class="form-input" id="pub-period-select" aria-label="Chọn kỳ cần công bố"
            onchange="AdminPublish.changePeriod(this.value)" style="width:220px">
      ${periods.map(p => `<option value="${p.id}" ${period&&p.id===period.id?'selected':''}>${escHtml(p.name)}</option>`).join('')}
    </select>
  </div>
</div>
<div id="pub-content">
  ${period ? _renderPublishContent(period.id) : '<div class="empty-state"><div class="empty-state-title">Chưa có kỳ đăng ký</div></div>'}
</div>`;
}

function changePeriod(periodId) {
  _pubPeriodId = periodId;
  document.getElementById('pub-content').innerHTML = _renderPublishContent(periodId);
}

function _renderPublishContent(periodId) {
  const period = State.get.periodById(periodId);
  if (!period) return '';
  const shifts = State.get.shiftsByDateRange(period.startDate, period.endDate)
                          .sort((a,b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime));
  const asgns  = State.get.assignments();

  const draftShifts     = shifts.filter(s => s.status === 'draft' || s.status === 'not_scheduled');
  const publishedShifts = shifts.filter(s => s.status === 'published');

  const draftIds    = draftShifts.map(s => s.id);
  const understaffedDraft = draftShifts.filter(s => {
    const filled = asgns.filter(a => a.shiftId === s.id).length;
    return filled < s.totalRequired;
  });

  return `
${understaffedDraft.length ? `
<div class="alert alert-warning mb-4">
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/></svg>
  <div><strong>${understaffedDraft.length} ca</strong> trong lịch nháp đang thiếu nhân sự. Hãy điều chỉnh trước khi công bố.</div>
</div>` : ''}

<div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:16px">
  <div class="stat-card"><div class="stat-label">Ca nháp chờ duyệt</div><div class="stat-value">${draftShifts.length}</div></div>
  <div class="stat-card"><div class="stat-label">Ca đã công bố</div><div class="stat-value text-success">${publishedShifts.length}</div></div>
</div>

${draftShifts.length ? `
<div class="card mb-4">
  <div class="card-header">
    <span class="card-title">Ca chờ công bố (${draftShifts.length})</span>
    <div style="display:flex;gap:8px">
      <button class="btn btn-success btn-sm" onclick="AdminPublish.publishAll(false)">Công bố tất cả</button>
      <button class="btn btn-warning btn-sm" onclick="AdminPublish.publishAll(true)">Công bố dù thiếu người</button>
    </div>
  </div>
  <div class="table-container">
  <table class="data-table"><thead><tr>
    <th><input type="checkbox" id="select-all-shifts" onchange="AdminPublish.selectAll(this.checked)"></th>
    <th>Ngày</th><th>Ca</th><th>Giờ</th><th>Nhân sự</th><th>Trạng thái nhân sự</th><th>Thao tác</th>
  </tr></thead><tbody>
  ${draftShifts.map(s => {
    const filled = asgns.filter(a => a.shiftId === s.id).length;
    const ok     = filled >= s.totalRequired;
    return `<tr>
      <td><input type="checkbox" class="shift-cb" value="${s.id}" ${ok?'checked':''}></td>
      <td>${formatDate(s.date)}</td>
      <td class="font-medium">${escHtml(s.name)}</td>
      <td class="no-wrap">${s.startTime}–${s.endTime}</td>
      <td class="${ok?'text-success':'text-danger'} font-medium">${filled}/${s.totalRequired}</td>
      <td>${ok ? renderBadge('full') : renderBadge('understaffed')}</td>
      <td class="actions-cell">
        <button class="btn btn-ghost btn-sm" onclick="AdminShift.viewDetail('${s.id}')">Xem</button>
        <button class="btn btn-success btn-sm" onclick="AdminPublish.publishOne('${s.id}')">Công bố</button>
      </td>
    </tr>`;
  }).join('')}
  </tbody></table></div>
</div>` : `<div class="alert alert-success mb-4"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg><div>Tất cả ca đã được công bố.</div></div>`}

${publishedShifts.length ? `
<div class="card">
  <div class="card-header"><span class="card-title">Ca đã công bố (${publishedShifts.length})</span></div>
  <div class="table-container">
  <table class="data-table"><thead><tr><th>Ngày</th><th>Ca</th><th>Giờ</th><th>Nhân sự</th></tr></thead><tbody>
  ${publishedShifts.slice(0,20).map(s => {
    const filled = asgns.filter(a => a.shiftId === s.id).length;
    return `<tr>
      <td>${formatDate(s.date)}</td>
      <td>${escHtml(s.name)}</td>
      <td>${s.startTime}–${s.endTime}</td>
      <td class="${filled>=s.totalRequired?'text-success':'text-danger'}">${filled}/${s.totalRequired}</td>
    </tr>`;
  }).join('')}
  ${publishedShifts.length > 20 ? `<tr><td colspan="4" class="text-muted text-sm text-center">...và ${publishedShifts.length-20} ca khác</td></tr>` : ''}
  </tbody></table></div>
</div>` : ''}`;
}

function selectAll(checked) {
  document.querySelectorAll('.shift-cb').forEach(cb => cb.checked = checked);
}

function publishAll(force) {
  const selected = [...document.querySelectorAll('.shift-cb:checked')].map(cb => cb.value);
  if (!selected.length) { App.toast('Chưa chọn ca nào.', 'warning'); return; }

  const msg = force
    ? `Công bố ${selected.length} ca kể cả những ca thiếu nhân sự?`
    : `Công bố ${selected.length} ca đã chọn?`;

  App.confirm(msg, '', () => {
    const result = Scheduler.publishShifts(selected, force);
    if (result.success) {
      App.toast(`Đã công bố ${result.published} ca.`, 'success');
      if (result.warnings?.length) App.toast(result.warnings[0], 'warning');
    } else {
      App.toast('Có lỗi: ' + result.errors?.[0], 'error');
    }
    changePeriod(_pubPeriodId);
  }, { type: force ? 'warn' : 'info' });
}

function publishOne(shiftId) {
  const result = Scheduler.publishShifts([shiftId], false);
  if (result.success) {
    App.toast('Đã công bố ca.', 'success');
  } else {
    App.confirm(
      'Ca chưa đủ nhân sự',
      result.errors[0] + ' Vẫn muốn công bố?',
      () => {
        Scheduler.publishShifts([shiftId], true);
        App.toast('Đã công bố ca (thiếu người).', 'warning');
        changePeriod(_pubPeriodId);
      },
      { type: 'warn' }
    );
    return;
  }
  changePeriod(_pubPeriodId);
}

return { render, changePeriod, selectAll, publishAll, publishOne };
})();

/* ═══════════════════════════════════════════════════════════
   5.9 LỊCH SỬ VÀ BÁO CÁO
═══════════════════════════════════════════════════════════ */
const AdminHistory = (() => {
let _histType = '', _histSearch = '';

function render() {
  const hist    = _getFiltered();
  const periods = State.get.periods();
  const period  = periods.find(p => p.status === 'open' || p.status === 'locked') || periods[0];

  return `
<div class="page-header">
  <div class="page-header-left">
    <h1 class="page-title">Lịch sử và báo cáo</h1>
  </div>
</div>

<div class="tabs" id="hist-tabs">
  <button class="tab-btn active" onclick="AdminHistory.switchTab('history', this)">Lịch sử hoạt động</button>
  <button class="tab-btn" onclick="AdminHistory.switchTab('report', this)">Báo cáo</button>
</div>

<div id="hist-tab-history">
  <div class="card">
    <div class="table-toolbar">
      <div class="search-input-wrap">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" aria-label="Tìm kiếm lịch sử" placeholder="Tìm trong lịch sử..." value="${escHtml(_histSearch)}"
               oninput="AdminHistory.filterHist('search', this.value)">
      </div>
      <select class="form-input filter-select" aria-label="Lọc lịch sử theo loại" onchange="AdminHistory.filterHist('type', this.value)">
        <option value="">Tất cả loại</option>
        <option value="user_save"        ${_histType==='user_save'       ?'selected':''}>Nhân sự</option>
        <option value="period_open"      ${_histType==='period_open'     ?'selected':''}>Kỳ đăng ký</option>
        <option value="schedule_run"     ${_histType==='schedule_run'    ?'selected':''}>Phân lịch</option>
        <option value="schedule_publish" ${_histType==='schedule_publish'?'selected':''}>Công bố lịch</option>
        <option value="manual_assign"    ${_histType==='manual_assign'   ?'selected':''}>Phân công thủ công</option>
        <option value="rules_update"     ${_histType==='rules_update'    ?'selected':''}>Quy tắc</option>
      </select>
    </div>
    <div class="card-body" style="padding:20px">
      ${hist.length ? `<div class="timeline">${hist.map(h => `
        <div class="timeline-item">
          <div class="timeline-dot ${_dotCls(h.type)}"></div>
          <div class="timeline-content">
            <div class="timeline-title">${escHtml(h.title)}</div>
            <div class="timeline-meta">${escHtml(h.actor)} · ${formatDateTime(h.timestamp)} · <span class="badge badge-draft text-xs">${escHtml(h.type)}</span></div>
            ${h.detail ? `<div class="timeline-detail">${escHtml(h.detail)}</div>` : ''}
            ${h.reason ? `<div class="timeline-detail text-secondary">Lý do: ${escHtml(h.reason)}</div>` : ''}
          </div>
        </div>`).join('')}</div>` : '<p class="text-muted text-sm">Không có bản ghi nào.</p>'}
    </div>
  </div>
</div>

<div id="hist-tab-report" class="hidden">
  ${period ? _renderReport(period.id) : '<p class="text-muted">Chưa có kỳ đăng ký.</p>'}
</div>`;
}

function _dotCls(type) {
  if (['schedule_publish','period_open'].includes(type)) return 'success';
  if (['manual_unassign','user_status'].includes(type)) return 'warning';
  if (type === 'shift_delete') return 'danger';
  return '';
}

function _getFiltered() {
  return State.get.history().filter(h => {
    if (_histType   && h.type !== _histType) return false;
    if (_histSearch) {
      const q = _histSearch.toLowerCase();
      if (!h.title.toLowerCase().includes(q) && !(h.detail||'').toLowerCase().includes(q)) return false;
    }
    return true;
  });
}

function filterHist(key, val) {
  if (key === 'type')   _histType   = val;
  if (key === 'search') _histSearch = val;
  const hist = _getFiltered();
  const panel = document.getElementById('hist-tab-history');
  if (panel) {
    const listEl = panel.querySelector('.timeline');
    const emptyEl = panel.querySelector('.text-muted.text-sm');
    if (hist.length) {
      if (emptyEl) emptyEl.textContent = '';
      if (listEl) listEl.innerHTML = hist.map(h => `
        <div class="timeline-item">
          <div class="timeline-dot ${_dotCls(h.type)}"></div>
          <div class="timeline-content">
            <div class="timeline-title">${escHtml(h.title)}</div>
            <div class="timeline-meta">${escHtml(h.actor)} · ${formatDateTime(h.timestamp)}</div>
            ${h.detail ? `<div class="timeline-detail">${escHtml(h.detail)}</div>` : ''}
          </div>
        </div>`).join('');
    }
  }
}

function switchTab(tab, btn) {
  document.querySelectorAll('.tabs .tab-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  document.getElementById('hist-tab-history').classList.toggle('hidden', tab !== 'history');
  document.getElementById('hist-tab-report').classList.toggle('hidden',  tab !== 'report');
}

function _renderReport(periodId) {
  const period = State.get.periodById(periodId);
  if (!period) return '';
  const users  = State.get.activeUsers();
  const asgns  = State.get.assignments();
  const shifts = State.get.shiftsByDateRange(period.startDate, period.endDate);

  const rows = users.map(u => {
    const target   = State.get.targetHoursForUser(u.id);
    const assigned = State.get.assignedHoursByUser(u.id, period.startDate, period.endDate);
    const pct      = target > 0 ? Math.round(assigned / target * 100) : 0;
    const shiftCnt = asgns.filter(a => {
      const sh = shifts.find(s => s.id === a.shiftId);
      return a.userId === u.id && sh;
    }).length;
    const lvl = State.get.levelById(u.levelId);
    return { u, lvl, target, assigned, pct, shiftCnt };
  }).sort((a,b) => a.pct - b.pct);

  return `
<div class="card">
  <div class="card-header"><span class="card-title">Báo cáo kỳ: ${escHtml(period.name)}</span></div>
  <div class="table-container">
  <table class="data-table"><thead><tr>
    <th>Nhân viên</th><th>Level</th><th>Giờ mục tiêu</th><th>Giờ đã phân</th><th>Tỷ lệ</th><th>Số ca</th><th>Đánh giá</th>
  </tr></thead><tbody>
  ${rows.map(r => `<tr>
    <td>${escHtml(r.u.fullName)}</td>
    <td>${r.lvl ? `<span class="badge" style="background:${r.lvl.color}22;color:${r.lvl.color}">${escHtml(r.lvl.name)}</span>` : '—'}</td>
    <td>${r.target}h</td>
    <td>${r.assigned.toFixed(1)}h</td>
    <td>
      <div style="display:flex;align-items:center;gap:8px">
        <div class="progress-bar-wrap" style="width:80px">
          <div class="progress-bar ${r.pct>=100?'success':r.pct>=60?'':'warning'}" style="width:${Math.min(r.pct,100)}%"></div>
        </div>
        <span class="${r.pct<60?'text-danger':r.pct>=100?'text-success':''} font-medium">${r.pct}%</span>
      </div>
    </td>
    <td>${r.shiftCnt}</td>
    <td>${r.pct>=100 ? renderBadge('full') : r.pct>=60 ? renderBadge('scheduling') : renderBadge('understaffed')}</td>
  </tr>`).join('')}
  </tbody></table></div>
</div>`;
}

return { render, filterHist, switchTab };
})();
