/**
 * storage.js
 * Quản lý LocalStorage, khởi tạo dữ liệu demo, persistence.
 * Tách biệt khỏi logic nghiệp vụ để có thể thay bằng API sau này.
 */

const STORAGE_KEYS = {
  USERS:          'pls_users',
  LEVELS:         'pls_levels',
  PERIODS:        'pls_periods',        // kỳ đăng ký
  FREE_TIME:      'pls_free_time',      // đăng ký thời gian rảnh
  SHIFTS:         'pls_shifts',         // ca làm việc
  ASSIGNMENTS:    'pls_assignments',    // phân công
  SCHEDULE_RULES: 'pls_schedule_rules', // quy tắc phân lịch
  HISTORY:        'pls_history',        // lịch sử thay đổi
  CURRENT_USER:   'pls_current_user',
  DATA_VERSION:   'pls_data_version',
};

const DATA_VERSION = '1.0.0';

/* ── Helpers ────────────────────────────────────────────────── */
const Storage = {
  get(key) {
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : null;
    } catch { return null; }
  },

  set(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch (e) {
      console.error('Storage.set error:', e);
      return false;
    }
  },

  remove(key) {
    localStorage.removeItem(key);
  },

  clear() {
    Object.values(STORAGE_KEYS).forEach(k => localStorage.removeItem(k));
  },

  /* Generic CRUD helpers */
  getAll(key)       { return this.get(key) || []; },
  saveAll(key, arr) { return this.set(key, arr); },

  getById(key, id) {
    return this.getAll(key).find(item => item.id === id) || null;
  },

  upsert(key, item) {
    const arr = this.getAll(key);
    const idx = arr.findIndex(i => i.id === item.id);
    if (idx >= 0) arr[idx] = { ...arr[idx], ...item };
    else arr.push(item);
    this.saveAll(key, arr);
    return item;
  },

  deleteById(key, id) {
    const arr = this.getAll(key).filter(i => i.id !== id);
    this.saveAll(key, arr);
  },

  /* Tạo ID dạng prefix_timestamp_random */
  genId(prefix = 'id') {
    return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2,7)}`;
  },
};

/* ── Demo Data Seed ─────────────────────────────────────────── */
function seedDemoData() {
  /* Không ghi đè nếu đã có dữ liệu cùng version */
  if (Storage.get(STORAGE_KEYS.DATA_VERSION) === DATA_VERSION) return;

  /* ── 1. Levels ─────────────────────────────────────────── */
  const levels = [
    { id: 'lvl1', name: 'Level 1', description: 'Nhân viên mới', color: '#7c3aed', order: 1 },
    { id: 'lvl2', name: 'Level 2', description: 'Nhân viên chính thức', color: '#be185d', order: 2 },
    { id: 'lvl3', name: 'Level 3', description: 'Nhân viên kỳ cựu', color: '#c2410c', order: 3 },
    { id: 'lvl4', name: 'Level 4', description: 'Trưởng ca', color: '#065f46', order: 4 },
  ];
  Storage.saveAll(STORAGE_KEYS.LEVELS, levels);

  /* ── 2. Users (12 nhân viên + 1 admin) ─────────────────── */
  const users = [
    { id: 'admin', code: 'ADM001', username: 'admin', password: 'admin123', fullName: 'Nguyễn Quản Trị', role: 'admin', levelId: null, status: 'active', targetRate: 100, phone: '0901000000', email: 'admin@company.vn', joinDate: '2022-01-01', note: '' },
    { id: 'nv001', code: 'NV001', username: 'nv001', password: 'user123', fullName: 'Trần Văn An', role: 'user', levelId: 'lvl1', status: 'active', targetRate: 75, phone: '0901000001', email: 'an@company.vn', joinDate: '2024-03-01', note: '' },
    { id: 'nv002', code: 'NV002', username: 'nv002', password: 'user123', fullName: 'Lê Thị Bích', role: 'user', levelId: 'lvl1', status: 'active', targetRate: 75, phone: '0901000002', email: 'bich@company.vn', joinDate: '2024-04-15', note: '' },
    { id: 'nv003', code: 'NV003', username: 'nv003', password: 'user123', fullName: 'Phạm Minh Châu', role: 'user', levelId: 'lvl1', status: 'active', targetRate: 50, phone: '0901000003', email: 'chau@company.vn', joinDate: '2024-06-01', note: '' },
    { id: 'nv004', code: 'NV004', username: 'nv004', password: 'user123', fullName: 'Hoàng Thanh Dũng', role: 'user', levelId: 'lvl2', status: 'active', targetRate: 100, phone: '0901000004', email: 'dung@company.vn', joinDate: '2023-01-10', note: '' },
    { id: 'nv005', code: 'NV005', username: 'nv005', password: 'user123', fullName: 'Vũ Ngọc Emm', role: 'user', levelId: 'lvl2', status: 'active', targetRate: 100, phone: '0901000005', email: 'emm@company.vn', joinDate: '2023-03-20', note: '' },
    { id: 'nv006', code: 'NV006', username: 'nv006', password: 'user123', fullName: 'Đặng Thị Phương', role: 'user', levelId: 'lvl2', status: 'active', targetRate: 75, phone: '0901000006', email: 'phuong@company.vn', joinDate: '2023-05-01', note: '' },
    { id: 'nv007', code: 'NV007', username: 'nv007', password: 'user123', fullName: 'Nguyễn Xuân Giàu', role: 'user', levelId: 'lvl3', status: 'active', targetRate: 100, phone: '0901000007', email: 'giau@company.vn', joinDate: '2022-07-01', note: '' },
    { id: 'nv008', code: 'NV008', username: 'nv008', password: 'user123', fullName: 'Bùi Thị Hoa', role: 'user', levelId: 'lvl3', status: 'active', targetRate: 100, phone: '0901000008', email: 'hoa@company.vn', joinDate: '2022-09-15', note: '' },
    { id: 'nv009', code: 'NV009', username: 'nv009', password: 'user123', fullName: 'Trương Văn Ích', role: 'user', levelId: 'lvl3', status: 'inactive', targetRate: 75, phone: '0901000009', email: 'ich@company.vn', joinDate: '2022-11-01', note: 'Tạm nghỉ phép dài hạn' },
    { id: 'nv010', code: 'NV010', username: 'nv010', password: 'user123', fullName: 'Đinh Thị Kim', role: 'user', levelId: 'lvl4', status: 'active', targetRate: 100, phone: '0901000010', email: 'kim@company.vn', joinDate: '2021-01-05', note: '' },
    { id: 'nv011', code: 'NV011', username: 'nv011', password: 'user123', fullName: 'Lý Văn Lực', role: 'user', levelId: 'lvl4', status: 'active', targetRate: 100, phone: '0901000011', email: 'luc@company.vn', joinDate: '2021-06-01', note: '' },
    { id: 'nv012', code: 'NV012', username: 'nv012', password: 'user123', fullName: 'Mai Xuân Minh', role: 'user', levelId: 'lvl2', status: 'active', targetRate: 50, phone: '0901000012', email: 'minh@company.vn', joinDate: '2024-09-01', note: 'Nhân viên bán thời gian' },
  ];
  Storage.saveAll(STORAGE_KEYS.USERS, users);

  /* ── 3. Schedule Rules ─────────────────────────────────── */
  const rules = {
    id: 'rules_main',
    standardHours: 80,            // giờ chuẩn/kỳ
    maxHoursPerDay: 12,
    maxHoursPerWeek: 48,
    maxHoursPerPeriod: 80,
    minRestBetweenShifts: 8,      // giờ nghỉ tối thiểu giữa 2 ca
    levelTargetRates: {           // tỷ lệ % mục tiêu theo level
      lvl1: 75,
      lvl2: 100,
      lvl3: 100,
      lvl4: 100,
    },
    levelSubstitutions: [
      // level cao thay thế level thấp
      { higherLevelId: 'lvl4', lowerLevelId: 'lvl3' },
      { higherLevelId: 'lvl4', lowerLevelId: 'lvl2' },
      { higherLevelId: 'lvl4', lowerLevelId: 'lvl1' },
      { higherLevelId: 'lvl3', lowerLevelId: 'lvl2' },
      { higherLevelId: 'lvl3', lowerLevelId: 'lvl1' },
      { higherLevelId: 'lvl2', lowerLevelId: 'lvl1' },
    ],
    schedulingPriority: 'most_needed',  // most_needed | least_hours
    understaffedPolicy: 'warn',         // warn | block
    updatedAt: new Date().toISOString(),
  };
  Storage.set(STORAGE_KEYS.SCHEDULE_RULES, rules);

  /* ── 4. Registration Periods (kỳ đăng ký) ─────────────── */
  const now = new Date();
  const thisYear = now.getFullYear();
  const thisMonth = now.getMonth(); // 0-indexed

  // Kỳ tháng 10/2026 - đang mở
  const p1Start = new Date(thisYear, thisMonth, 1);
  const p1End   = new Date(thisYear, thisMonth + 1, 0);
  const p1Open  = new Date(thisYear, thisMonth - 1, 20, 8, 0, 0);
  const p1Close = new Date(thisYear, thisMonth, 5, 23, 59, 59);

  // Kỳ tháng 11/2026 - chưa mở
  const p2Start = new Date(thisYear, thisMonth + 1, 1);
  const p2End   = new Date(thisYear, thisMonth + 2, 0);

  // Kỳ tháng 9/2026 - đã kết thúc
  const p3Start = new Date(thisYear, thisMonth - 1, 1);
  const p3End   = new Date(thisYear, thisMonth, 0);

  const periods = [
    {
      id: 'period_oct2026',
      name: `Kỳ tháng 10/${thisYear}`,
      cycle: 'monthly',
      startDate: _fmt(p1Start),
      endDate:   _fmt(p1End),
      openAt:    p1Open.toISOString(),
      deadline:  p1Close.toISOString(),
      autoLock:  true,
      allowEditAfterDeadline: false,
      allowReopenByAdmin: true,
      applicableUserIds: [],   // [] = áp dụng tất cả
      requiredDaysOfWeek: [1,2,3,4,5,6],   // T2-T7
      exemptDates: [
        _fmt(new Date(thisYear, thisMonth, 10)), // Ngày nghỉ lễ ví dụ
      ],
      closedDates: [],
      status: 'open',          // draft | not_open | open | locked
      createdAt: new Date(thisYear, thisMonth - 1, 15).toISOString(),
      updatedAt: new Date().toISOString(),
    },
    {
      id: 'period_nov2026',
      name: `Kỳ tháng 11/${thisYear}`,
      cycle: 'monthly',
      startDate: _fmt(p2Start),
      endDate:   _fmt(p2End),
      openAt:    new Date(thisYear, thisMonth, 20, 8, 0).toISOString(),
      deadline:  new Date(thisYear, thisMonth + 1, 5, 23, 59).toISOString(),
      autoLock:  true,
      allowEditAfterDeadline: false,
      allowReopenByAdmin: true,
      applicableUserIds: [],
      requiredDaysOfWeek: [1,2,3,4,5,6],
      exemptDates: [],
      closedDates: [],
      status: 'not_open',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
    {
      id: 'period_sep2026',
      name: `Kỳ tháng 9/${thisYear}`,
      cycle: 'monthly',
      startDate: _fmt(p3Start),
      endDate:   _fmt(p3End),
      openAt:    new Date(thisYear, thisMonth - 2, 20, 8, 0).toISOString(),
      deadline:  new Date(thisYear, thisMonth - 1, 5, 23, 59).toISOString(),
      autoLock:  true,
      allowEditAfterDeadline: false,
      allowReopenByAdmin: true,
      applicableUserIds: [],
      requiredDaysOfWeek: [1,2,3,4,5,6],
      exemptDates: [],
      closedDates: [],
      status: 'locked',
      createdAt: new Date(thisYear, thisMonth - 2, 10).toISOString(),
      updatedAt: new Date(thisYear, thisMonth - 1, 5).toISOString(),
    },
  ];
  Storage.saveAll(STORAGE_KEYS.PERIODS, periods);

  /* ── 5. Free Time Registrations ────────────────────────── */
  const freeTimeList = [];

  // Helper: tạo đăng ký thời gian rảnh
  function makeFreeTime(userId, periodId, dates) {
    return {
      id:       Storage.genId('ft'),
      userId,
      periodId,
      status:   'submitted',  // draft | submitted
      entries:  dates,        // [{date, slots:[{start,end}]}]
      submittedAt: new Date(thisYear, thisMonth, 3, 10, 0).toISOString(),
      updatedAt:   new Date(thisYear, thisMonth, 3, 10, 0).toISOString(),
    };
  }

  // Tạo dữ liệu đăng ký tháng 10 cho một số NV
  const oct10  = _fmt(new Date(thisYear, thisMonth, 10)); // nghỉ lễ
  const dates_nv001 = genWorkdays(thisYear, thisMonth, [0], [oct10]);  // T2-T7, bỏ ngày lễ
  const dates_nv004 = genWorkdays(thisYear, thisMonth, [0], [oct10]);
  const dates_nv007 = genWorkdays(thisYear, thisMonth, [0], [oct10]);
  const dates_nv010 = genWorkdays(thisYear, thisMonth, [0], [oct10]);
  const dates_nv011 = genWorkdays(thisYear, thisMonth, [0], [oct10]);

  freeTimeList.push(makeFreeTime('nv001', 'period_oct2026', dates_nv001.map(d => ({
    date: d,
    slots: [{ start: '07:00', end: '21:00' }],
  }))));
  freeTimeList.push(makeFreeTime('nv004', 'period_oct2026', dates_nv004.map(d => ({
    date: d,
    slots: d.endsWith('0') || parseInt(d.slice(-2)) % 7 === 0
      ? [{ start: '13:00', end: '22:00' }]   // buổi chiều
      : [{ start: '06:00', end: '22:00' }],
  }))));
  freeTimeList.push(makeFreeTime('nv007', 'period_oct2026', dates_nv007.map(d => ({
    date: d,
    slots: [{ start: '06:00', end: '15:00' }],  // chỉ rảnh buổi sáng
  }))));
  freeTimeList.push(makeFreeTime('nv010', 'period_oct2026', dates_nv010.map(d => ({
    date: d,
    slots: [{ start: '06:00', end: '23:59' }],
  }))));
  freeTimeList.push(makeFreeTime('nv011', 'period_oct2026', dates_nv011.map(d => ({
    date: d,
    slots: [{ start: '06:00', end: '23:59' }],
  }))));
  // nv002 đăng ký một phần (bản nháp)
  freeTimeList.push({
    id: Storage.genId('ft'), userId: 'nv002', periodId: 'period_oct2026',
    status: 'draft',
    entries: genWorkdays(thisYear, thisMonth, [0], [oct10]).slice(0, 10).map(d => ({
      date: d, slots: [{ start: '07:00', end: '15:00' }],
    })),
    submittedAt: null,
    updatedAt: new Date(thisYear, thisMonth, 2).toISOString(),
  });
  // nv003 chưa đăng ký
  // nv005, nv006, nv008, nv012 đã đăng ký
  freeTimeList.push(makeFreeTime('nv005', 'period_oct2026', genWorkdays(thisYear, thisMonth, [0], [oct10]).map(d => ({
    date: d, slots: [{ start: '07:00', end: '22:00' }],
  }))));
  freeTimeList.push(makeFreeTime('nv006', 'period_oct2026', genWorkdays(thisYear, thisMonth, [0], [oct10]).map(d => ({
    date: d, slots: [{ start: '13:00', end: '22:00' }],
  }))));
  freeTimeList.push(makeFreeTime('nv008', 'period_oct2026', genWorkdays(thisYear, thisMonth, [0], [oct10]).map(d => ({
    date: d, slots: [{ start: '07:00', end: '15:00' }],
  }))));
  freeTimeList.push(makeFreeTime('nv012', 'period_oct2026', genWorkdays(thisYear, thisMonth, [0,6], [oct10]).map(d => ({
    date: d, slots: [{ start: '08:00', end: '14:00' }],
  }))));

  Storage.saveAll(STORAGE_KEYS.FREE_TIME, freeTimeList);

  /* ── 6. Shifts ──────────────────────────────────────────── */
  const shifts = [];

  // Tạo ca cho từng ngày làm trong tháng 10
  const workdays10 = genWorkdays(thisYear, thisMonth, [0], [oct10]);
  let shiftIdx = 0;

  workdays10.forEach((dateStr, i) => {
    // Ca sáng
    shifts.push(makeShift(`sh_s${i}`, dateStr, 'Ca sáng', '07:00', '15:00', {
      mode: i % 3 === 0 ? 'combined' : 'level',
      levelRequirements: [
        { levelId: 'lvl1', count: 2 },
        { levelId: 'lvl2', count: 2 },
        { levelId: 'lvl3', count: 1 },
        { levelId: 'lvl4', count: 1 },
      ],
      mandatoryUsers: i % 3 === 0 ? [{ userId: 'nv010', type: 'mandatory' }] : [],
      preferredUsers: i % 3 === 0 ? [{ userId: 'nv007', type: 'preferred' }] : [],
      totalRequired: 6,
      status: i < 10 ? 'published' : (i < 15 ? 'draft' : 'not_scheduled'),
    }));

    // Ca tối (không phải cuối tuần)
    const dow = new Date(dateStr).getDay();
    if (dow !== 0 && dow !== 6) {
      shifts.push(makeShift(`sh_e${i}`, dateStr, 'Ca tối', '14:30', '22:30', {
        mode: 'level',
        levelRequirements: [
          { levelId: 'lvl1', count: 2 },
          { levelId: 'lvl2', count: 1 },
          { levelId: 'lvl3', count: 1 },
          { levelId: 'lvl4', count: 1 },
        ],
        mandatoryUsers: [],
        preferredUsers: [],
        totalRequired: 5,
        status: i < 8 ? 'published' : (i < 12 ? 'draft' : 'not_scheduled'),
      }));
    }

    shiftIdx++;
  });

  // Thêm vài ca qua ngày (22:00 - 06:00 hôm sau) vào cuối tuần
  const weekendDays = workdays10.filter(d => [6,0].includes(new Date(d).getDay()));
  weekendDays.slice(0, 4).forEach((dateStr, i) => {
    shifts.push(makeShift(`sh_n${i}`, dateStr, 'Ca đêm', '22:00', '06:00', {
      mode: 'user',
      levelRequirements: [],
      mandatoryUsers: [
        { userId: 'nv010', type: 'mandatory' },
        { userId: 'nv011', type: 'mandatory' },
      ],
      preferredUsers: [{ userId: 'nv008', type: 'preferred' }],
      totalRequired: 3,
      status: i < 2 ? 'published' : 'draft',
    }));
  });

  Storage.saveAll(STORAGE_KEYS.SHIFTS, shifts);

  /* ── 7. Assignments (phân công) ─────────────────────────── */
  const assignments = [];
  const publishedShifts = shifts.filter(s => s.status === 'published');

  const assignPool = {
    // shiftId → [userId, ...]
  };

  publishedShifts.forEach(sh => {
    const userIds = _pickAssignees(sh, users);
    userIds.forEach(uid => {
      assignments.push({
        id:        Storage.genId('asgn'),
        shiftId:   sh.id,
        userId:    uid,
        assignedBy: 'auto',
        assignedAt: sh.updatedAt,
        reason:    '',
        status:    'confirmed',
      });
    });
  });

  Storage.saveAll(STORAGE_KEYS.ASSIGNMENTS, assignments);

  /* ── 8. History ─────────────────────────────────────────── */
  const history = [
    _histEntry('period_create', 'Tạo kỳ đăng ký', `Tạo kỳ tháng 10/${thisYear}`, 'admin', new Date(thisYear, thisMonth-1, 15)),
    _histEntry('period_create', 'Tạo kỳ đăng ký', `Tạo kỳ tháng 11/${thisYear}`, 'admin', new Date(thisYear, thisMonth, 1)),
    _histEntry('period_open', 'Mở kỳ đăng ký', `Mở kỳ tháng 10/${thisYear}`, 'admin', new Date(thisYear, thisMonth-1, 20)),
    _histEntry('schedule_run', 'Chạy phân lịch', `Phân lịch tự động tháng 10/${thisYear}`, 'admin', new Date(thisYear, thisMonth, 6)),
    _histEntry('schedule_publish', 'Công bố lịch', `Công bố lịch 10 ngày đầu tháng 10`, 'admin', new Date(thisYear, thisMonth, 7)),
    _histEntry('manual_assign', 'Phân công thủ công', 'Bổ sung NV010 vào ca đêm cuối tuần', 'admin', new Date(thisYear, thisMonth, 8)),
  ];
  Storage.saveAll(STORAGE_KEYS.HISTORY, history);

  /* ── Đánh dấu đã seed ─────────────────────────────────── */
  Storage.set(STORAGE_KEYS.DATA_VERSION, DATA_VERSION);
  console.log('[Storage] Demo data seeded v' + DATA_VERSION);
}

/* ── Helper functions for seeding ────────────────────────────── */
function _fmt(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function genWorkdays(year, month, skipDOW = [0], skipDates = []) {
  const result = [];
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  for (let d = 1; d <= daysInMonth; d++) {
    const date = new Date(year, month, d);
    const dateStr = _fmt(date);
    if (skipDOW.includes(date.getDay())) continue;
    if (skipDates.includes(dateStr)) continue;
    result.push(dateStr);
  }
  return result;
}

function makeShift(id, date, name, startTime, endTime, config) {
  return {
    id,
    name,
    date,
    startTime,
    endTime,
    mode:               config.mode,          // level | user | combined
    levelRequirements:  config.levelRequirements,
    mandatoryUsers:     config.mandatoryUsers,
    preferredUsers:     config.preferredUsers,
    totalRequired:      config.totalRequired,
    note:               '',
    status:             config.status,        // not_scheduled | draft | published
    createdAt:          new Date().toISOString(),
    updatedAt:          new Date().toISOString(),
  };
}

function _pickAssignees(shift, users) {
  const activeUsers = users.filter(u => u.role === 'user' && u.status === 'active');
  // Lấy danh sách user bắt buộc/ưu tiên
  const mandatory = shift.mandatoryUsers.map(m => m.userId);
  const preferred  = shift.preferredUsers.map(p => p.userId);

  const needed = shift.totalRequired;
  const selected = [...new Set([...mandatory, ...preferred])];

  // Bổ sung thêm ngẫu nhiên từ activeUsers để đủ
  const remaining = activeUsers.filter(u => !selected.includes(u.id));
  let idx = 0;
  while (selected.length < needed && idx < remaining.length) {
    selected.push(remaining[idx].id);
    idx++;
  }
  return selected.slice(0, needed);
}

function _histEntry(type, title, detail, actor, date) {
  return {
    id:        Storage.genId('hist'),
    type,
    title,
    detail,
    actor,
    timestamp: date.toISOString(),
    before:    null,
    after:     null,
    reason:    '',
  };
}

/* ── History logger ──────────────────────────────────────────── */
function logHistory(type, title, detail, actor, before = null, after = null, reason = '') {
  const entry = {
    id:        Storage.genId('hist'),
    type,
    title,
    detail,
    actor,
    timestamp: new Date().toISOString(),
    before,
    after,
    reason,
  };
  const arr = Storage.getAll(STORAGE_KEYS.HISTORY);
  arr.unshift(entry);
  // Giữ tối đa 500 entries
  if (arr.length > 500) arr.splice(500);
  Storage.saveAll(STORAGE_KEYS.HISTORY, arr);
  return entry;
}

/* ── Reset to demo ───────────────────────────────────────────── */
function resetDemoData() {
  Storage.clear();
  seedDemoData();
  console.log('[Storage] Demo data reset.');
}
