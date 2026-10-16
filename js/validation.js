/**
 * validation.js
 * Validation rules và ràng buộc an toàn cốt lõi.
 * Mọi hàm trả về { valid: bool, errors: {} } hoặc { valid: bool, errors: [] }
 */

/* ── Generic field validators ──────────────────────────────── */
const V = {
  required(val, label) {
    if (val === null || val === undefined || String(val).trim() === '')
      return `${label} không được để trống.`;
    return null;
  },
  maxLength(val, max, label) {
    if (val && String(val).length > max) return `${label} không được vượt quá ${max} ký tự.`;
    return null;
  },
  positiveInt(val, label) {
    const n = parseInt(val);
    if (isNaN(n) || n <= 0) return `${label} phải là số nguyên dương.`;
    return null;
  },
  number(val, min, max, label) {
    const n = parseFloat(val);
    if (isNaN(n)) return `${label} phải là số.`;
    if (min !== undefined && n < min) return `${label} phải >= ${min}.`;
    if (max !== undefined && n > max) return `${label} phải <= ${max}.`;
    return null;
  },
  dateStr(val, label) {
    if (!val) return `${label} không hợp lệ.`;
    if (isNaN(new Date(val).getTime())) return `${label} không hợp lệ.`;
    return null;
  },
  timeStr(val, label) {
    if (!val || !/^\d{2}:\d{2}$/.test(val)) return `${label} không hợp lệ (HH:MM).`;
    const [h, m] = val.split(':').map(Number);
    if (h > 23 || m > 59) return `${label} không hợp lệ.`;
    return null;
  },
  usernameFormat(val) {
    if (!/^[a-z0-9_]{3,30}$/.test(val))
      return 'Tên đăng nhập chỉ gồm chữ thường, số, gạch dưới (3-30 ký tự).';
    return null;
  },
  passwordFormat(val) {
    if (val && val.length < 6) return 'Mật khẩu phải có ít nhất 6 ký tự.';
    return null;
  },
};

/* ── Form-level validators ─────────────────────────────────── */
const Validate = {

  userForm(data, isNew = true) {
    const errors = {};
    const e = (k, msg) => { if (msg) errors[k] = msg; };

    e('code',     V.required(data.code, 'Mã nhân viên') || V.maxLength(data.code, 20, 'Mã nhân viên'));
    e('fullName', V.required(data.fullName, 'Họ tên')   || V.maxLength(data.fullName, 100, 'Họ tên'));

    if (isNew || data.username) {
      e('username', V.required(data.username, 'Tên đăng nhập') || V.usernameFormat(data.username));
    }
    if (isNew) {
      e('password', V.required(data.password, 'Mật khẩu') || V.passwordFormat(data.password));
    } else if (data.password) {
      e('password', V.passwordFormat(data.password));
    }

    e('role', V.required(data.role, 'Vai trò'));
    if (data.role === 'user') e('levelId', V.required(data.levelId, 'Level'));
    e('targetRate', V.number(data.targetRate, 0, 200, 'Tỷ lệ giờ mục tiêu'));

    return { valid: Object.keys(errors).length === 0, errors };
  },

  levelForm(data) {
    const errors = {};
    const e = (k, msg) => { if (msg) errors[k] = msg; };
    e('name',  V.required(data.name, 'Tên Level') || V.maxLength(data.name, 50, 'Tên Level'));
    e('order', V.positiveInt(data.order, 'Thứ tự'));
    return { valid: Object.keys(errors).length === 0, errors };
  },

  periodForm(data) {
    const errors = {};
    const e = (k, msg) => { if (msg) errors[k] = msg; };
    e('name',      V.required(data.name, 'Tên kỳ đăng ký'));
    e('startDate', V.required(data.startDate, 'Ngày bắt đầu') || V.dateStr(data.startDate, 'Ngày bắt đầu'));
    e('endDate',   V.required(data.endDate,   'Ngày kết thúc') || V.dateStr(data.endDate,   'Ngày kết thúc'));
    if (!errors.startDate && !errors.endDate && data.startDate > data.endDate) {
      errors.endDate = 'Ngày kết thúc phải sau ngày bắt đầu.';
    }
    if (data.openAt && isNaN(new Date(data.openAt).getTime()))
      errors.openAt = 'Thời điểm mở đăng ký không hợp lệ.';
    if (data.deadline && isNaN(new Date(data.deadline).getTime()))
      errors.deadline = 'Hạn chót đăng ký không hợp lệ.';
    if (data.openAt && data.deadline && new Date(data.deadline) <= new Date(data.openAt))
      errors.deadline = 'Hạn chót phải sau thời điểm mở đăng ký.';
    return { valid: Object.keys(errors).length === 0, errors };
  },

  shiftForm(data) {
    const errors = {};
    const e = (k, msg) => { if (msg) errors[k] = msg; };
    e('name',          V.required(data.name, 'Tên ca'));
    e('date',          V.required(data.date, 'Ngày làm') || V.dateStr(data.date, 'Ngày làm'));
    e('startTime',     V.required(data.startTime, 'Giờ bắt đầu') || V.timeStr(data.startTime, 'Giờ bắt đầu'));
    e('endTime',       V.required(data.endTime,   'Giờ kết thúc') || V.timeStr(data.endTime,   'Giờ kết thúc'));
    e('mode',          V.required(data.mode, 'Chế độ cấu hình nhân sự'));
    e('totalRequired', V.positiveInt(data.totalRequired, 'Tổng nhu cầu nhân sự'));

    if (!Object.keys(errors).length) {
      const staffResult = this.shiftStaffConfig(data);
      if (!staffResult.valid) errors.staffConfig = staffResult.errors.join(' ');
    }
    return { valid: Object.keys(errors).length === 0, errors };
  },

  shiftStaffConfig(data) {
    const errs = [];
    const total = parseInt(data.totalRequired) || 0;
    const mandatory  = (data.mandatoryUsers || []);
    const preferred  = (data.preferredUsers || []);
    const levelReqs  = (data.levelRequirements || []);
    const levelTotal = levelReqs.reduce((s, r) => s + (parseInt(r.count) || 0), 0);

    if (data.mode === 'level') {
      if (levelReqs.length === 0) errs.push('Phải có ít nhất một yêu cầu Level.');
      levelReqs.forEach(r => {
        if (!r.levelId) errs.push('Mỗi dòng Level phải chọn Level.');
        if ((parseInt(r.count) || 0) <= 0) errs.push('Số lượng mỗi Level phải > 0.');
      });
      if (!errs.length && levelTotal !== total)
        errs.push(`Tổng Level (${levelTotal}) phải bằng tổng nhu cầu (${total}).`);
    }

    if (data.mode === 'user') {
      const allDirect = [...mandatory, ...preferred];
      if (allDirect.length === 0) errs.push('Phải chọn ít nhất một nhân viên.');
      if (mandatory.length > total)
        errs.push(`Số User bắt buộc (${mandatory.length}) vượt tổng nhu cầu (${total}).`);
      // Kiểm tra trùng
      const mandIds = new Set(mandatory.map(u => u.userId));
      preferred.forEach(u => { if (mandIds.has(u.userId)) errs.push('User không thể vừa bắt buộc vừa ưu tiên.'); });
    }

    if (data.mode === 'combined') {
      const mandIds = new Set(mandatory.map(u => u.userId));
      preferred.forEach(u => { if (mandIds.has(u.userId)) errs.push('User không thể vừa bắt buộc vừa ưu tiên.'); });
      if (mandatory.length + levelTotal > total)
        errs.push(`User bắt buộc (${mandatory.length}) + vị trí Level (${levelTotal}) = ${mandatory.length + levelTotal} vượt tổng nhu cầu (${total}). Hãy điều chỉnh.`);
    }

    return { valid: errs.length === 0, errors: errs };
  },

  rulesForm(data) {
    const errors = {};
    const e = (k, msg) => { if (msg) errors[k] = msg; };
    e('standardHours',        V.number(data.standardHours, 1, 744, 'Giờ chuẩn'));
    e('maxHoursPerDay',       V.number(data.maxHoursPerDay, 1, 24, 'Giờ tối đa/ngày'));
    e('maxHoursPerWeek',      V.number(data.maxHoursPerWeek, 1, 168, 'Giờ tối đa/tuần'));
    e('minRestBetweenShifts', V.number(data.minRestBetweenShifts, 0, 24, 'Giờ nghỉ tối thiểu'));
    return { valid: Object.keys(errors).length === 0, errors };
  },
};

/* ── Core safety constraints ───────────────────────────────── */
const SafetyCheck = {

  /** Kiểm tra xung đột ca: trả null nếu OK, string nếu lỗi */
  checkShiftOverlap(userId, newShift, allAssignments, allShifts) {
    const userShiftIds = allAssignments
      .filter(a => a.userId === userId && a.shiftId !== newShift.id)
      .map(a => a.shiftId);

    for (const sid of userShiftIds) {
      const s = allShifts.find(sh => sh.id === sid);
      if (!s) continue;
      // Bỏ qua nếu cách nhau > 1 ngày (trừ ca qua ngày)
      const diff = Math.abs(_dateDiffDays(s.date, newShift.date));
      if (diff > 1) continue;

      if (_shiftsOverlap(newShift, s)) {
        return `Xung đột lịch: "${newShift.name}" (${newShift.date} ${newShift.startTime}-${newShift.endTime}) trùng với ca "${s.name}" (${s.date} ${s.startTime}-${s.endTime}).`;
      }
    }
    return null;
  },

  /** Kiểm tra thời gian rảnh: trả null nếu OK */
  checkFreeTime(userId, shift, periodId) {
    const ft = State.get.freeTimeByUserPeriod(userId, periodId);
    if (!ft || ft.status !== 'submitted')
      return `Nhân viên chưa gửi đăng ký thời gian rảnh cho kỳ này.`;

    const entry = ft.entries.find(e => e.date === shift.date);
    if (!entry)
      return `Nhân viên không đăng ký rảnh ngày ${formatDate(shift.date)}.`;

    const shStart = _timeToMin(shift.startTime);
    let shEnd     = _timeToMin(shift.endTime);
    if (shEnd <= shStart) shEnd += 1440; // ca qua ngày

    const covered = entry.slots.some(slot => {
      const sStart = _timeToMin(slot.start);
      let sEnd     = _timeToMin(slot.end);
      if (sEnd <= sStart) sEnd += 1440;
      return sStart <= shStart && sEnd >= shEnd;
    });
    if (!covered)
      return `Thời gian rảnh của nhân viên không bao phủ ca ${shift.startTime}-${shift.endTime} ngày ${formatDate(shift.date)}.`;
    return null;
  },

  /** Kiểm tra giới hạn giờ làm */
  checkHoursLimit(userId, newShift, rules, allShifts, allAssignments) {
    if (!rules) return null;
    const dur = calcShiftDuration(newShift.startTime, newShift.endTime);

    if (rules.maxHoursPerDay) {
      const dayIds = allAssignments.filter(a => a.userId === userId).map(a => a.shiftId);
      const dayH = allShifts
        .filter(s => dayIds.includes(s.id) && s.date === newShift.date && s.id !== newShift.id)
        .reduce((sum, s) => sum + calcShiftDuration(s.startTime, s.endTime), 0);
      if (dayH + dur > rules.maxHoursPerDay)
        return `Vượt giới hạn giờ/ngày (${rules.maxHoursPerDay}h): hiện ${dayH.toFixed(1)}h + ca mới ${dur.toFixed(1)}h.`;
    }

    if (rules.maxHoursPerWeek) {
      const wk = _startOfWeek(newShift.date);
      const wkEnd = _addDays(wk, 6);
      const wkIds = allAssignments.filter(a => a.userId === userId).map(a => a.shiftId);
      const wkH = allShifts
        .filter(s => wkIds.includes(s.id) && s.date >= wk && s.date <= wkEnd && s.id !== newShift.id)
        .reduce((sum, s) => sum + calcShiftDuration(s.startTime, s.endTime), 0);
      if (wkH + dur > rules.maxHoursPerWeek)
        return `Vượt giới hạn giờ/tuần (${rules.maxHoursPerWeek}h): hiện ${wkH.toFixed(1)}h.`;
    }
    return null;
  },

  /** Kiểm tra thời gian nghỉ tối thiểu */
  checkMinRest(userId, newShift, rules, allShifts, allAssignments) {
    if (!rules || !rules.minRestBetweenShifts) return null;
    const minMin = rules.minRestBetweenShifts * 60;
    const nsStart = _absMin(newShift.date, newShift.startTime);
    const nsEnd   = _absMin(newShift.date, newShift.endTime) + (_isOvernight(newShift) ? 1440 : 0);

    const userIds = allAssignments.filter(a => a.userId === userId).map(a => a.shiftId);
    for (const sid of userIds) {
      const s = allShifts.find(sh => sh.id === sid);
      if (!s || s.id === newShift.id) continue;
      if (Math.abs(_dateDiffDays(s.date, newShift.date)) > 2) continue;

      const sStart = _absMin(s.date, s.startTime);
      const sEnd   = _absMin(s.date, s.endTime) + (_isOvernight(s) ? 1440 : 0);

      const gap1 = nsStart - sEnd;
      const gap2 = sStart  - nsEnd;

      if ((gap1 >= 0 && gap1 < minMin) || (gap2 >= 0 && gap2 < minMin)) {
        return `Thời gian nghỉ giữa ca không đủ ${rules.minRestBetweenShifts}h (ca "${s.name}" ngày ${s.date}).`;
      }
    }
    return null;
  },

  /** Kiểm tra level eligibility kể cả quy tắc thay thế */
  checkLevelEligibility(userId, requiredLevelId, rules) {
    const user = State.get.userById(userId);
    if (!user) return 'Không tìm thấy nhân viên.';
    if (user.levelId === requiredLevelId) return null;
    if (rules && rules.levelSubstitutions) {
      const ok = rules.levelSubstitutions.some(
        sub => sub.higherLevelId === user.levelId && sub.lowerLevelId === requiredLevelId
      );
      if (ok) return null;
    }
    const reqLvl  = State.get.levelById(requiredLevelId);
    const usrLvl  = State.get.levelById(user.levelId);
    return `${usrLvl ? usrLvl.name : user.levelId} không đủ điều kiện cho vị trí ${reqLvl ? reqLvl.name : requiredLevelId}.`;
  },

  /** Full assignment validation: { valid, errors[], warnings[] } */
  validateAssignment(userId, shift, periodId, isAdminOverride = false) {
    const errors = [], warnings = [];
    const rules        = State.get.rules();
    const allShifts    = State.get.shifts();
    const allAsgns     = State.get.assignments();

    const overlapErr = this.checkShiftOverlap(userId, shift, allAsgns, allShifts);
    if (overlapErr) errors.push(overlapErr); // không thể bỏ qua

    const ftErr = this.checkFreeTime(userId, shift, periodId);
    if (ftErr) {
      if (isAdminOverride) warnings.push('[Admin override] ' + ftErr);
      else errors.push(ftErr);
    }

    const hoursErr = this.checkHoursLimit(userId, shift, rules, allShifts, allAsgns);
    if (hoursErr) errors.push(hoursErr); // không thể bỏ qua

    const restErr = this.checkMinRest(userId, shift, rules, allShifts, allAsgns);
    if (restErr) warnings.push(restErr); // cảnh báo, không chặn

    return { valid: errors.length === 0, errors, warnings };
  },
};

/* ── Uniqueness helpers ────────────────────────────────────── */
const UniqueCheck = {
  username(val, excludeId = null) {
    return State.get.users().some(u => u.username === val && u.id !== excludeId);
  },
  userCode(val, excludeId = null) {
    return State.get.users().some(u => u.code === val && u.id !== excludeId);
  },
  levelName(val, excludeId = null) {
    return State.get.levels().some(l => l.name.toLowerCase() === val.toLowerCase() && l.id !== excludeId);
  },
  periodName(val, excludeId = null) {
    return State.get.periods().some(p => p.name.toLowerCase() === val.toLowerCase() && p.id !== excludeId);
  },
};

/* ── UI helpers: inline errors ─────────────────────────────── */
function showFieldErrors(formEl, errors) {
  if (!formEl) return;
  formEl.querySelectorAll('.field-error').forEach(el => {
    el.textContent = '';
    el.setAttribute('aria-live', 'polite');
  });
  formEl.querySelectorAll('.error').forEach(el => el.classList.remove('error'));
  formEl.querySelectorAll('[aria-invalid="true"]').forEach(input => {
    input.setAttribute('aria-invalid', 'false');
    _removeDescribedBy(input, input.dataset.errorDescriptionId);
    delete input.dataset.errorDescriptionId;
  });

  Object.entries(errors).forEach(([field, msg]) => {
    const errEl = formEl.querySelector(`[data-err="${field}"]`);
    const inputEl = formEl.querySelector(`[name="${field}"]`) || formEl.querySelector(`#field-${field}`);
    if (errEl) {
      if (!errEl.id) errEl.id = `${formEl.getAttribute('id') || 'form'}-error-${field}`;
      errEl.textContent = msg;
    }
    if (inputEl) {
      inputEl.classList.add('error');
      inputEl.setAttribute('aria-invalid', 'true');
      if (errEl) {
        inputEl.dataset.errorDescriptionId = errEl.id;
        _addDescribedBy(inputEl, errEl.id);
      }
    }
  });
}

function clearFormErrors(formEl) {
  if (!formEl) return;
  formEl.querySelectorAll('.field-error').forEach(el => { el.textContent = ''; });
  formEl.querySelectorAll('.error').forEach(el => el.classList.remove('error'));
  formEl.querySelectorAll('[aria-invalid="true"]').forEach(input => {
    input.setAttribute('aria-invalid', 'false');
    _removeDescribedBy(input, input.dataset.errorDescriptionId);
    delete input.dataset.errorDescriptionId;
  });
}

function _addDescribedBy(element, id) {
  const ids = new Set((element.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean));
  ids.add(id);
  element.setAttribute('aria-describedby', [...ids].join(' '));
}

function _removeDescribedBy(element, id) {
  if (!id) return;
  const ids = (element.getAttribute('aria-describedby') || '').split(/\s+/).filter(value => value && value !== id);
  if (ids.length) element.setAttribute('aria-describedby', ids.join(' '));
  else element.removeAttribute('aria-describedby');
}

/* ── Internal helpers ──────────────────────────────────────── */
function _timeToMin(t) {
  const [h, m] = (t || '00:00').split(':').map(Number);
  return h * 60 + m;
}
function _isOvernight(shift) {
  return _timeToMin(shift.endTime) < _timeToMin(shift.startTime);
}
function _absMin(dateStr, timeStr) {
  // Minutes from epoch-day 0 for comparison within close dates
  const [y, mo, d] = dateStr.split('-').map(Number);
  const dayOffset = Math.floor(new Date(y, mo - 1, d).getTime() / 86400000);
  return dayOffset * 1440 + _timeToMin(timeStr);
}
function _shiftsOverlap(a, b) {
  const aS = _absMin(a.date, a.startTime);
  const aE = _absMin(a.date, a.endTime) + (_isOvernight(a) ? 1440 : 0);
  const bS = _absMin(b.date, b.startTime);
  const bE = _absMin(b.date, b.endTime) + (_isOvernight(b) ? 1440 : 0);
  return aS < bE && bS < aE;
}
function _dateDiffDays(dA, dB) {
  return Math.round((new Date(dA) - new Date(dB)) / 86400000);
}
function _startOfWeek(dateStr) {
  const d = new Date(dateStr);
  const dow = d.getDay();
  d.setDate(d.getDate() + (dow === 0 ? -6 : 1 - dow));
  return _fmtDate(d);
}
function _addDays(dateStr, n) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + n);
  return _fmtDate(d);
}
function _fmtDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
}
