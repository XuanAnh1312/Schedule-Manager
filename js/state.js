/**
 * state.js
 * Global state management — in-memory cache + LocalStorage sync.
 * Tất cả màn hình dùng chung State, không đọc trực tiếp từ Storage.
 */

const State = (() => {
  /* ── Internal cache ────────────────────────────────────── */
  let _data = {
    currentUser:   null,
    users:         [],
    levels:        [],
    periods:       [],
    freeTime:      [],
    shifts:        [],
    assignments:   [],
    rules:         null,
    history:       [],
  };

  /* ── Load all data from storage ────────────────────────── */
  function load() {
    _data.users       = Storage.getAll(STORAGE_KEYS.USERS);
    _data.levels      = Storage.getAll(STORAGE_KEYS.LEVELS);
    _data.periods     = Storage.getAll(STORAGE_KEYS.PERIODS);
    _data.freeTime    = Storage.getAll(STORAGE_KEYS.FREE_TIME);
    _data.shifts      = Storage.getAll(STORAGE_KEYS.SHIFTS);
    _data.assignments = Storage.getAll(STORAGE_KEYS.ASSIGNMENTS);
    _data.rules       = Storage.get(STORAGE_KEYS.SCHEDULE_RULES);
    _data.history     = Storage.getAll(STORAGE_KEYS.HISTORY);
    _data.currentUser = Storage.get(STORAGE_KEYS.CURRENT_USER);
  }

  /* ── Getters ────────────────────────────────────────────── */
  const get = {
    users:       ()  => [..._data.users],
    activeUsers: ()  => _data.users.filter(u => u.status === 'active' && u.role === 'user'),
    levels:      ()  => [..._data.levels].sort((a,b) => a.order - b.order),
    periods:     ()  => [..._data.periods],
    freeTime:    ()  => [..._data.freeTime],
    shifts:      ()  => [..._data.shifts],
    assignments: ()  => [..._data.assignments],
    rules:       ()  => _data.rules ? { ..._data.rules } : null,
    history:     ()  => [..._data.history],
    currentUser: ()  => _data.currentUser ? { ..._data.currentUser } : null,

    userById:    (id) => _data.users.find(u => u.id === id) || null,
    levelById:   (id) => _data.levels.find(l => l.id === id) || null,
    periodById:  (id) => _data.periods.find(p => p.id === id) || null,
    shiftById:   (id) => _data.shifts.find(s => s.id === id) || null,

    /* Free time registrations cho user trong kỳ */
    freeTimeByUserPeriod(userId, periodId) {
      return _data.freeTime.find(ft => ft.userId === userId && ft.periodId === periodId) || null;
    },

    /* Tất cả đăng ký của một kỳ */
    freeTimeByPeriod(periodId) {
      return _data.freeTime.filter(ft => ft.periodId === periodId);
    },

    /* Assignments của một ca */
    assignmentsByShift(shiftId) {
      return _data.assignments.filter(a => a.shiftId === shiftId);
    },

    /* Assignments của một user */
    assignmentsByUser(userId) {
      return _data.assignments.filter(a => a.userId === userId);
    },

    /* Ca trong một ngày */
    shiftsByDate(date) {
      return _data.shifts.filter(s => s.date === date);
    },

    /* Ca trong khoảng ngày */
    shiftsByDateRange(startDate, endDate) {
      return _data.shifts.filter(s => s.date >= startDate && s.date <= endDate);
    },

    /* User chưa đăng ký trong kỳ */
    unregisteredUsersInPeriod(periodId) {
      const period = _data.periods.find(p => p.id === periodId);
      if (!period) return [];
      const applicable = period.applicableUserIds.length > 0
        ? _data.users.filter(u => u.role === 'user' && u.status === 'active' && period.applicableUserIds.includes(u.id))
        : _data.users.filter(u => u.role === 'user' && u.status === 'active');
      const registered = new Set(_data.freeTime.filter(ft => ft.periodId === periodId && ft.status === 'submitted').map(ft => ft.userId));
      return applicable.filter(u => !registered.has(u.id));
    },

    /* Số giờ đã phân của một user trong khoảng ngày */
    assignedHoursByUser(userId, startDate, endDate) {
      const userShiftIds = new Set(_data.assignments.filter(a => a.userId === userId).map(a => a.shiftId));
      let total = 0;
      _data.shifts.forEach(sh => {
        if (!userShiftIds.has(sh.id)) return;
        if (startDate && sh.date < startDate) return;
        if (endDate && sh.date > endDate) return;
        total += calcShiftDuration(sh.startTime, sh.endTime);
      });
      return total;
    },

    /* Giờ mục tiêu của user */
    targetHoursForUser(userId) {
      const user = _data.users.find(u => u.id === userId);
      if (!user) return 0;
      const rules = _data.rules;
      if (!rules) return 0;
      // Nếu user có targetRate riêng, dùng nó; nếu không dùng của level
      let rate = user.targetRate;
      if (!rate && user.levelId) {
        rate = rules.levelTargetRates[user.levelId] || 100;
      }
      return Math.round(rules.standardHours * (rate || 100) / 100);
    },

    /* Thống kê tổng quan admin */
    dashboardStats(periodId) {
      const period = _data.periods.find(p => p.id === periodId);
      if (!period) return null;
      const periodShifts = _data.shifts.filter(s => s.date >= period.startDate && s.date <= period.endDate);
      const publishedShifts = periodShifts.filter(s => s.status === 'published');
      const activeUsers = _data.users.filter(u => u.role === 'user' && u.status === 'active');
      const unregistered = this.unregisteredUsersInPeriod(periodId);

      let fullShifts = 0;
      let understaffedShifts = 0;
      let totalTargetHours = 0;
      let totalAssignedHours = 0;

      publishedShifts.forEach(sh => {
        const asgns = _data.assignments.filter(a => a.shiftId === sh.id);
        if (asgns.length >= sh.totalRequired) fullShifts++;
        else understaffedShifts++;
      });

      activeUsers.forEach(u => {
        totalTargetHours += this.targetHoursForUser(u.id);
        totalAssignedHours += this.assignedHoursByUser(u.id, period.startDate, period.endDate);
      });

      return {
        totalActiveUsers:    activeUsers.length,
        totalShifts:         periodShifts.length,
        publishedShifts:     publishedShifts.length,
        draftShifts:         periodShifts.filter(s => s.status === 'draft').length,
        fullShifts,
        understaffedShifts,
        unregisteredCount:   unregistered.length,
        totalTargetHours,
        totalAssignedHours,
        progressPercent:     totalTargetHours > 0
          ? Math.round((totalAssignedHours / totalTargetHours) * 100) : 0,
      };
    },
  };

  /* ── Mutations ──────────────────────────────────────────── */
  const mut = {
    /* Users */
    saveUser(user) {
      const now = new Date().toISOString();
      if (!user.id) user.id = Storage.genId('usr');
      user.updatedAt = now;
      if (!user.createdAt) user.createdAt = now;
      Storage.upsert(STORAGE_KEYS.USERS, user);
      const idx = _data.users.findIndex(u => u.id === user.id);
      if (idx >= 0) _data.users[idx] = { ..._data.users[idx], ...user };
      else _data.users.push(user);
      return user;
    },

    deleteUser(userId) {
      Storage.deleteById(STORAGE_KEYS.USERS, userId);
      _data.users = _data.users.filter(u => u.id !== userId);
    },

    /* Levels */
    saveLevel(level) {
      if (!level.id) level.id = Storage.genId('lvl');
      Storage.upsert(STORAGE_KEYS.LEVELS, level);
      const idx = _data.levels.findIndex(l => l.id === level.id);
      if (idx >= 0) _data.levels[idx] = level;
      else _data.levels.push(level);
      return level;
    },

    deleteLevel(levelId) {
      Storage.deleteById(STORAGE_KEYS.LEVELS, levelId);
      _data.levels = _data.levels.filter(l => l.id !== levelId);
    },

    /* Periods */
    savePeriod(period) {
      const now = new Date().toISOString();
      if (!period.id) period.id = Storage.genId('prd');
      period.updatedAt = now;
      if (!period.createdAt) period.createdAt = now;
      Storage.upsert(STORAGE_KEYS.PERIODS, period);
      const idx = _data.periods.findIndex(p => p.id === period.id);
      if (idx >= 0) _data.periods[idx] = { ..._data.periods[idx], ...period };
      else _data.periods.push(period);
      return period;
    },

    /* Free Time */
    saveFreeTime(ft) {
      const now = new Date().toISOString();
      if (!ft.id) ft.id = Storage.genId('ft');
      ft.updatedAt = now;
      Storage.upsert(STORAGE_KEYS.FREE_TIME, ft);
      const idx = _data.freeTime.findIndex(f => f.id === ft.id);
      if (idx >= 0) _data.freeTime[idx] = { ..._data.freeTime[idx], ...ft };
      else _data.freeTime.push(ft);
      return ft;
    },

    /* Shifts */
    saveShift(shift) {
      const now = new Date().toISOString();
      if (!shift.id) shift.id = Storage.genId('sh');
      shift.updatedAt = now;
      if (!shift.createdAt) shift.createdAt = now;
      Storage.upsert(STORAGE_KEYS.SHIFTS, shift);
      const idx = _data.shifts.findIndex(s => s.id === shift.id);
      if (idx >= 0) _data.shifts[idx] = { ..._data.shifts[idx], ...shift };
      else _data.shifts.push(shift);
      return shift;
    },

    deleteShift(shiftId) {
      Storage.deleteById(STORAGE_KEYS.SHIFTS, shiftId);
      _data.shifts = _data.shifts.filter(s => s.id !== shiftId);
      // Xóa assignments liên quan
      _data.assignments = _data.assignments.filter(a => a.shiftId !== shiftId);
      Storage.saveAll(STORAGE_KEYS.ASSIGNMENTS, _data.assignments);
    },

    /* Assignments */
    saveAssignment(asgn) {
      const now = new Date().toISOString();
      if (!asgn.id) asgn.id = Storage.genId('asgn');
      asgn.assignedAt = now;
      Storage.upsert(STORAGE_KEYS.ASSIGNMENTS, asgn);
      const idx = _data.assignments.findIndex(a => a.id === asgn.id);
      if (idx >= 0) _data.assignments[idx] = asgn;
      else _data.assignments.push(asgn);
      return asgn;
    },

    removeAssignment(asgnId) {
      Storage.deleteById(STORAGE_KEYS.ASSIGNMENTS, asgnId);
      _data.assignments = _data.assignments.filter(a => a.id !== asgnId);
    },

    removeAssignmentsByShift(shiftId) {
      _data.assignments = _data.assignments.filter(a => a.shiftId !== shiftId);
      Storage.saveAll(STORAGE_KEYS.ASSIGNMENTS, _data.assignments);
    },

    saveAllAssignments(list) {
      _data.assignments = list;
      Storage.saveAll(STORAGE_KEYS.ASSIGNMENTS, list);
    },

    /* Rules */
    saveRules(rules) {
      rules.updatedAt = new Date().toISOString();
      _data.rules = rules;
      Storage.set(STORAGE_KEYS.SCHEDULE_RULES, rules);
      return rules;
    },

    /* Auth */
    setCurrentUser(user) {
      _data.currentUser = user;
      Storage.set(STORAGE_KEYS.CURRENT_USER, user);
    },

    clearCurrentUser() {
      _data.currentUser = null;
      Storage.remove(STORAGE_KEYS.CURRENT_USER);
    },
  };

  /* ── Public API ─────────────────────────────────────────── */
  return { load, get, mut };
})();

/* ── Utility: tính thời lượng ca (giờ) ──────────────────────── */
function calcShiftDuration(startTime, endTime) {
  const [sh, sm] = startTime.split(':').map(Number);
  const [eh, em] = endTime.split(':').map(Number);
  let startMin = sh * 60 + sm;
  let endMin   = eh * 60 + em;
  if (endMin <= startMin) endMin += 24 * 60; // ca qua ngày
  return (endMin - startMin) / 60;
}

/* ── Date/Time formatters ─────────────────────────────────────── */
function formatDate(dateStr) {
  if (!dateStr) return '—';
  const [y, m, d] = dateStr.split('-');
  return `${d}/${m}/${y}`;
}

function formatDateTime(isoStr) {
  if (!isoStr) return '—';
  const d = new Date(isoStr);
  if (isNaN(d.getTime())) return '—';
  const pad = n => String(n).padStart(2,'0');
  return `${pad(d.getDate())}/${pad(d.getMonth()+1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function formatTime(timeStr) {
  if (!timeStr) return '—';
  return timeStr.slice(0, 5);
}

function getShiftDurationLabel(startTime, endTime) {
  const h = calcShiftDuration(startTime, endTime);
  const hInt = Math.floor(h);
  const mInt = Math.round((h - hInt) * 60);
  if (mInt === 0) return `${hInt} giờ`;
  return `${hInt} giờ ${mInt} phút`;
}

function getDOWLabel(dow) {
  return ['CN','T2','T3','T4','T5','T6','T7'][dow] || '';
}

function getMonthLabel(month) { // 0-indexed
  return `Tháng ${month + 1}`;
}

/* ── Status label maps ───────────────────────────────────────── */
const STATUS_LABELS = {
  // Period status
  draft:         { label: 'Nháp',             cls: 'badge-draft' },
  not_open:      { label: 'Chưa mở đăng ký',  cls: 'badge-not-open' },
  open:          { label: 'Đang mở đăng ký',  cls: 'badge-open' },
  locked:        { label: 'Đã khóa',           cls: 'badge-locked' },
  // Free time status
  not_registered:{ label: 'Chưa đăng ký',     cls: 'badge-not-registered' },
  submitted:     { label: 'Đã gửi đăng ký',   cls: 'badge-submitted' },
  incomplete:    { label: 'Chưa hoàn thành',   cls: 'badge-incomplete' },
  // Shift status
  not_scheduled: { label: 'Chưa phân lịch',   cls: 'badge-draft' },
  scheduling:    { label: 'Đang phân lịch',    cls: 'badge-scheduling' },
  full:          { label: 'Đã đủ người',       cls: 'badge-full' },
  understaffed:  { label: 'Thiếu nhân sự',     cls: 'badge-understaffed' },
  published:     { label: 'Đã công bố',        cls: 'badge-published' },
  // User status
  active:        { label: 'Đang hoạt động',    cls: 'badge-active' },
  inactive:      { label: 'Tạm khóa',          cls: 'badge-inactive' },
};

function renderBadge(status, overrideLabel = null) {
  const s = STATUS_LABELS[status] || { label: status, cls: 'badge-draft' };
  const label = overrideLabel || s.label;
  return `<span class="badge ${s.cls}">${escHtml(label)}</span>`;
}

/* ── Security helpers ───────────────────────────────────────── */
function escHtml(str) {
  if (str == null) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function safeText(str) {
  const el = document.createElement('span');
  el.textContent = str || '';
  return el.innerHTML;
}

/* ── Debounce ───────────────────────────────────────────────── */
function debounce(fn, delay = 300) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}
