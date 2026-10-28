/**
 * hr-store.js — Module của Bảo (task v1.1: BA-01 … BA-06), lớp dữ liệu cho bản demo.
 *
 * Mỗi hàm ở đây khớp 1-1 với một API backend (xem Docs/bao-requests-leave-notifications.md):
 *   HrStore.createShiftRequest  ↔ POST /api/requests
 *   HrStore.approveShiftRequest ↔ POST /api/requests/{id}/approve
 *   HrStore.listNotifications   ↔ GET  /api/notifications ...
 * Khi Anh nối đăng nhập thật (JWT), chỉ cần thay phần thân các hàm bằng fetch() — giao diện
 * (hr-views.js) không phải sửa.
 *
 * Quy tắc nghiệp vụ giữ giống backend:
 *  - Lịch chính thức KHÔNG đổi khi yêu cầu chưa được duyệt (AC-08).
 *  - Kiểm tra lại ràng buộc lịch tại thời điểm duyệt (BA-02).
 *  - Duyệt lặp không thay đổi lặp (so trạng thái + version).
 *  - Ca trống không bao giờ xác nhận vượt số chỗ (BA-03).
 *  - Mỗi sự kiện có eventId tất định → không gửi trùng thông báo (BA-05/06).
 */

const HR_KEYS = {
  SHIFT_REQUESTS: 'pls_hr_shift_requests',
  LEAVE_TYPES:    'pls_hr_leave_types',
  LEAVE_REQUESTS: 'pls_hr_leave_requests',
  ABSENCES:       'pls_hr_absences',
  OPEN_SHIFTS:    'pls_hr_open_shifts',
  CLAIMS:         'pls_hr_open_shift_claims',
  NOTIFICATIONS:  'pls_hr_notifications',
  EVENTS:         'pls_hr_events',
  SETTINGS:       'pls_hr_settings',
};

class HrError extends Error {
  constructor(code, message, details = []) {
    super(message);
    this.code = code;
    this.details = details;
  }
}

const HrStore = (() => {
  const ROLE_ADMIN = 'role:admin';
  const MAX_TEXT = 1000;

  /* ── Tiện ích lưu trữ ─────────────────────────────────────────────── */
  const read  = key => Storage.getAll(key);
  const write = (key, arr) => Storage.saveAll(key, arr);
  const nowIso = () => new Date().toISOString();

  function upsert(key, item) {
    const arr = read(key);
    const i = arr.findIndex(x => x.id === item.id);
    if (i >= 0) arr[i] = item; else arr.push(item);
    write(key, arr);
    return item;
  }

  function me() {
    const u = State.get.currentUser();
    if (!u) throw new HrError('UNAUTHORIZED', 'Chưa đăng nhập.');
    return { id: u.id, admin: u.role === 'admin' };
  }
  function requireAdmin(u) {
    if (!u.admin) throw new HrError('FORBIDDEN', 'Bạn không có quyền thực hiện thao tác này.');
  }
  function requireText(v, field) {
    const t = (v || '').trim();
    if (!t) throw new HrError('REQUIRED', `${field} là bắt buộc.`);
    if (t.length > MAX_TEXT) throw new HrError('TOO_LONG', `${field} tối đa ${MAX_TEXT} ký tự.`);
    return t;
  }
  function optionalText(v) {
    const t = (v || '').trim();
    if (t.length > MAX_TEXT) throw new HrError('TOO_LONG', `Tối đa ${MAX_TEXT} ký tự.`);
    return t || null;
  }
  function checkVersion(entity, expected) {
    if (expected != null && expected !== entity.version) {
      throw new HrError('STALE_VERSION', 'Dữ liệu đã thay đổi kể từ lúc bạn xem, vui lòng tải lại.');
    }
  }
  const userName = id => State.get.userById(id)?.fullName || id;
  const todayStr = () => _fmtDate(new Date());

  /* ── Seed danh mục loại nghỉ (giống migration V6) ─────────────────── */
  function ensureSeed() {
    if (!Storage.get(HR_KEYS.LEAVE_TYPES)) {
      write(HR_KEYS.LEAVE_TYPES, [
        { id: 'leave_annual',   name: 'Nghỉ phép năm',    paid: true,  requiresDocument: false, minNoticeDays: 0, active: true },
        { id: 'leave_sick',     name: 'Nghỉ ốm',          paid: true,  requiresDocument: true,  minNoticeDays: 0, active: true },
        { id: 'leave_personal', name: 'Nghỉ việc riêng',  paid: false, requiresDocument: false, minNoticeDays: 0, active: true },
        { id: 'leave_unpaid',   name: 'Nghỉ không lương', paid: false, requiresDocument: false, minNoticeDays: 0, active: true },
      ]);
    }
    if (!Storage.get(HR_KEYS.SETTINGS)) {
      // Chưa cấu hình mốc nhắc → không tự điền giá trị (AC-13).
      Storage.set(HR_KEYS.SETTINGS, { deadlineReminderHours: [] });
    }
  }

  /* ── Lịch: đọc + kiểm tra (tương ứng AssignmentValidationService) ─── */
  function shiftStart(shift) { return new Date(`${shift.date}T${shift.startTime}:00`); }
  function shiftLabel(shift) {
    return shift ? `${shift.name} ${formatDate(shift.date)} ${shift.startTime}–${shift.endTime}` : '';
  }
  function assignmentById(id) { return State.get.assignments().find(a => a.id === id) || null; }

  function requireOwnFutureAssignment(assignmentId, ownerId, what) {
    if (!assignmentId) throw new HrError('REQUIRED', `${what} là bắt buộc.`);
    const a = assignmentById(assignmentId);
    if (!a || a.userId !== ownerId || (a.status && a.status !== 'confirmed')) {
      throw new HrError('SCHEDULE_CHANGED', `${what} không còn thuộc người này (lịch đã thay đổi).`);
    }
    const s = State.get.shiftById(a.shiftId);
    if (!s || s.status !== 'published') throw new HrError('SHIFT_NOT_PUBLISHED', `${what} chưa thuộc lịch đã công bố.`);
    if (shiftStart(s) <= new Date()) throw new HrError('SHIFT_STARTED', `${what} đã bắt đầu hoặc đã qua.`);
    return { a, s };
  }

  /**
   * Kiểm tra userId có thể nhận shiftId. Trả về danh sách thông điệp vi phạm bắt buộc.
   * @param ignoreIds phân công của user sẽ được gỡ trong cùng thao tác (đổi chéo)
   */
  function validateTake(userId, shiftId, replacingUserId, ignoreIds = [], requiredLevelId = null) {
    const errors = [];
    const user = State.get.userById(userId);
    if (!user || user.status !== 'active' || user.role !== 'user') {
      return ['Nhân viên không ở trạng thái hoạt động.'];
    }
    const shift = State.get.shiftById(shiftId);
    if (!shift) return ['Không tìm thấy ca.'];
    const ignore = new Set(ignoreIds);
    const asgns = State.get.assignments().filter(a => !ignore.has(a.id) && (!a.status || a.status === 'confirmed'));
    if (asgns.some(a => a.userId === userId && a.shiftId === shiftId)) errors.push('Nhân viên đã có trong ca này.');
    const shifts = State.get.shifts();
    const rules = State.get.rules();
    const overlap = SafetyCheck.checkShiftOverlap(userId, shift, asgns, shifts);
    if (overlap) errors.push(overlap);
    const hours = SafetyCheck.checkHoursLimit(userId, shift, rules, shifts, asgns);
    if (hours) errors.push(hours);
    const rest = SafetyCheck.checkMinRest(userId, shift, rules, shifts, asgns);
    if (rest) errors.push(rest); // mục 3.2: thời gian nghỉ tối thiểu là điều kiện bắt buộc
    const needed = requiredLevelId || (replacingUserId ? State.get.userById(replacingUserId)?.levelId : null);
    if (needed) {
      const lvl = SafetyCheck.checkLevelEligibility(userId, needed, rules);
      if (lvl) errors.push(lvl);
    }
    return errors;
  }

  function reassign(assignmentId, expectedUserId, newUserId, actorId, reason) {
    const a = assignmentById(assignmentId);
    if (!a || a.userId !== expectedUserId) {
      throw new HrError('SCHEDULE_CHANGED', 'Lịch đã thay đổi kể từ khi tạo yêu cầu.');
    }
    State.mut.saveAssignment({ ...a, userId: newUserId, assignedBy: actorId, reason, status: 'confirmed' });
  }

  /* ── Sự kiện + thông báo (tương ứng outbox + dispatcher) ─────────── */
  function publish(eventId, type, entityType, entityId, actorId, recipients, title, body, linkRoute) {
    const seen = new Set(read(HR_KEYS.EVENTS));
    if (seen.has(eventId)) return false;                // chống trùng theo eventId
    const users = State.get.users();
    const resolved = new Set();
    recipients.forEach(r => {
      if (r === ROLE_ADMIN) users.filter(u => u.role === 'admin' && u.status === 'active').forEach(u => resolved.add(u.id));
      else if (users.some(u => u.id === r && u.status === 'active')) resolved.add(r);
    });
    const list = read(HR_KEYS.NOTIFICATIONS);
    resolved.forEach(recipientId => {
      if (list.some(n => n.eventId === eventId && n.recipientId === recipientId)) return;
      list.push({ id: Storage.genId('ntf'), recipientId, eventId, type, title, body, entityType, entityId,
                  linkRoute, read: false, readAt: null, createdAt: nowIso() });
    });
    write(HR_KEYS.NOTIFICATIONS, list);
    seen.add(eventId);
    write(HR_KEYS.EVENTS, [...seen].slice(-2000));
    return true;
  }
  const eventId = (type, id, version) => `${type}:${id}:v${version}`;

  /* ════════════════════════════════════════════════════════════════
     BA-01/02 — Đổi ca / nhờ làm thay
  ════════════════════════════════════════════════════════════════ */
  const typeLabel = t => (t === 'swap' ? 'đổi ca' : 'làm thay ca');

  function enrichRequest(r) {
    return {
      ...r,
      requesterName: userName(r.requesterId),
      targetUserName: userName(r.targetUserId),
      decidedByName: r.decidedBy ? userName(r.decidedBy) : null,
      sourceShift: State.get.shiftById(r.sourceShiftId),
      targetShift: r.targetShiftId ? State.get.shiftById(r.targetShiftId) : null,
      isOpen: r.status === 'pending_peer' || r.status === 'pending_approval',
    };
  }
  function loadRequest(id) {
    const r = read(HR_KEYS.SHIFT_REQUESTS).find(x => x.id === id);
    if (!r) throw new HrError('NOT_FOUND', 'Không tìm thấy yêu cầu.');
    return r;
  }
  function requestViolations(type, requesterId, targetUserId, src, tgt) {
    const out = validateTake(targetUserId, src.a.shiftId, requesterId, tgt ? [tgt.a.id] : [])
      .map(m => `Người nhận: ${m}`);
    if (type === 'swap' && tgt) {
      validateTake(requesterId, tgt.a.shiftId, targetUserId, [src.a.id]).forEach(m => out.push(`Người gửi: ${m}`));
    }
    return out;
  }

  /** Ca sắp tới đã công bố của tôi — nguồn để gửi yêu cầu. */
  function myUpcomingAssignments(userId) {
    const now = new Date();
    return State.get.assignmentsByUser(userId)
      .map(a => ({ a, s: State.get.shiftById(a.shiftId) }))
      .filter(x => x.s && x.s.status === 'published' && shiftStart(x.s) > now)
      .sort((x, y) => shiftStart(x.s) - shiftStart(y.s));
  }

  function coverCandidates(assignmentId) {
    const u = me();
    const { a } = requireOwnFutureAssignment(assignmentId, u.id, 'Ca của bạn');
    return State.get.activeUsers().filter(x => x.id !== u.id).map(x => {
      const reasons = validateTake(x.id, a.shiftId, u.id);
      return { userId: x.id, fullName: x.fullName, eligible: reasons.length === 0, reasons };
    }).sort((p, q) => Number(q.eligible) - Number(p.eligible));
  }

  function createShiftRequest({ type, sourceAssignmentId, targetUserId, targetAssignmentId, reason }) {
    const u = me();
    if (u.admin) throw new HrError('FORBIDDEN', 'Admin chỉnh lịch trực tiếp, không gửi yêu cầu đổi ca.');
    if (type !== 'swap' && type !== 'cover') throw new HrError('INVALID_TYPE', 'Loại yêu cầu không hợp lệ.');
    const why = requireText(reason, 'Lý do');
    const target = State.get.userById(targetUserId);
    if (!targetUserId || targetUserId === u.id || !target || target.status !== 'active' || target.role !== 'user') {
      throw new HrError('INVALID_TARGET', 'Chọn một đồng nghiệp khác đang hoạt động.');
    }
    const src = requireOwnFutureAssignment(sourceAssignmentId, u.id, 'Ca của bạn');
    let tgt = null;
    if (type === 'swap') {
      tgt = requireOwnFutureAssignment(targetAssignmentId, targetUserId, 'Ca của người nhận');
      if (tgt.a.shiftId === src.a.shiftId) throw new HrError('SAME_SHIFT', 'Hai người đang cùng một ca.');
    }
    const open = read(HR_KEYS.SHIFT_REQUESTS).some(r => r.sourceAssignmentId === sourceAssignmentId
      && (r.status === 'pending_peer' || r.status === 'pending_approval'));
    if (open) throw new HrError('REQUEST_ALREADY_OPEN', 'Ca này đã có một yêu cầu đang chờ xử lý.');
    const violations = requestViolations(type, u.id, targetUserId, src, tgt);
    if (violations.length) throw new HrError('VALIDATION_FAILED', 'Yêu cầu vi phạm ràng buộc lịch.', violations);

    const r = {
      id: Storage.genId('sreq'), type, status: 'pending_peer', requesterId: u.id,
      sourceAssignmentId, sourceShiftId: src.a.shiftId, targetUserId,
      targetAssignmentId: tgt ? tgt.a.id : null, targetShiftId: tgt ? tgt.a.shiftId : null,
      reason: why, peerNote: null, peerRespondedAt: null, decidedBy: null, decidedAt: null,
      decisionNote: null, finalResponsibleUserId: null, version: 0, createdAt: nowIso(), updatedAt: nowIso(),
    };
    upsert(HR_KEYS.SHIFT_REQUESTS, r);
    logHistory('shift_request_create', `Gửi yêu cầu ${typeLabel(type)}`,
      `${userName(u.id)} → ${target.fullName}: ${shiftLabel(src.s)}`, u.id, null, r, why);
    publish(eventId('shift_request.created', r.id, r.version), 'shift_request.created', 'shift_request', r.id, u.id,
      [targetUserId], `Yêu cầu ${typeLabel(type)} mới`,
      `${userName(u.id)} đề nghị bạn ${type === 'swap' ? 'đổi ca' : 'làm thay'}: ${shiftLabel(src.s)}${tgt ? ' ⇄ ' + shiftLabel(tgt.s) : ''}.`,
      'my-requests');
    return enrichRequest(r);
  }

  function peerRespond(id, accept, expectedVersion, note) {
    const u = me();
    const r = loadRequest(id);
    if (r.targetUserId !== u.id) throw new HrError('FORBIDDEN', 'Chỉ người được đề nghị mới phản hồi.');
    if (r.status !== 'pending_peer') throw new HrError('INVALID_STATE', 'Yêu cầu không còn chờ bạn phản hồi.');
    checkVersion(r, expectedVersion);
    if (accept) {
      const src = requireOwnFutureAssignment(r.sourceAssignmentId, r.requesterId, 'Ca gốc');
      const tgt = r.type === 'swap' ? requireOwnFutureAssignment(r.targetAssignmentId, r.targetUserId, 'Ca của bạn') : null;
      const v = requestViolations(r.type, r.requesterId, r.targetUserId, src, tgt);
      if (v.length) throw new HrError('VALIDATION_FAILED', 'Bạn không thể nhận ca này do vi phạm ràng buộc lịch.', v);
    }
    const before = { ...r };
    Object.assign(r, { status: accept ? 'pending_approval' : 'peer_declined', peerNote: optionalText(note),
      peerRespondedAt: nowIso(), version: r.version + 1, updatedAt: nowIso() });
    upsert(HR_KEYS.SHIFT_REQUESTS, r);
    const v = enrichRequest(r);
    logHistory(accept ? 'shift_request_peer_accept' : 'shift_request_peer_decline',
      accept ? 'Đồng ý đổi/làm thay ca' : 'Từ chối đổi/làm thay ca', shiftLabel(v.sourceShift), u.id, before, r, r.peerNote || '');
    if (accept) {
      publish(eventId('shift_request.peer_accepted', r.id, r.version), 'shift_request.peer_accepted', 'shift_request',
        r.id, u.id, [ROLE_ADMIN, r.requesterId], `Yêu cầu ${typeLabel(r.type)} chờ duyệt`,
        `${v.targetUserName} đã đồng ý ${typeLabel(r.type)} với ${v.requesterName} (${shiftLabel(v.sourceShift)}).`, 'approvals');
    } else {
      publish(eventId('shift_request.peer_declined', r.id, r.version), 'shift_request.peer_declined', 'shift_request',
        r.id, u.id, [r.requesterId], 'Yêu cầu bị từ chối',
        `${v.targetUserName} đã từ chối ${typeLabel(r.type)} ${shiftLabel(v.sourceShift)}.`, 'my-requests');
    }
    return v;
  }

  function approveShiftRequest(id, expectedVersion, note) {
    const u = me(); requireAdmin(u);
    const r = loadRequest(id);
    if (r.status === 'approved') return enrichRequest(r);          // duyệt lặp → không đổi lịch lần 2
    if (r.status !== 'pending_approval') throw new HrError('INVALID_STATE', 'Chỉ duyệt được yêu cầu mà người nhận đã đồng ý.');
    checkVersion(r, expectedVersion);
    // BA-02: kiểm tra lại ở thời điểm duyệt
    const src = requireOwnFutureAssignment(r.sourceAssignmentId, r.requesterId, 'Ca gốc');
    const tgt = r.type === 'swap' ? requireOwnFutureAssignment(r.targetAssignmentId, r.targetUserId, 'Ca của người nhận') : null;
    const v = requestViolations(r.type, r.requesterId, r.targetUserId, src, tgt);
    if (v.length) throw new HrError('VALIDATION_FAILED', 'Không thể duyệt: lịch hiện tại vi phạm ràng buộc.', v);

    const before = { ...r };
    const why = `Duyệt yêu cầu ${r.id}`;
    reassign(src.a.id, r.requesterId, r.targetUserId, u.id, why);
    if (tgt) reassign(tgt.a.id, r.targetUserId, r.requesterId, u.id, why);
    Object.assign(r, { status: 'approved', decidedBy: u.id, decidedAt: nowIso(), decisionNote: optionalText(note),
      finalResponsibleUserId: r.targetUserId, version: r.version + 1, updatedAt: nowIso() });
    upsert(HR_KEYS.SHIFT_REQUESTS, r);
    const view = enrichRequest(r);
    logHistory('shift_request_approve', `Duyệt ${typeLabel(r.type)}`,
      `${view.requesterName} → ${view.targetUserName}: ${shiftLabel(view.sourceShift)}`, u.id,
      { request: before, assignments: { [src.a.id]: before.requesterId, ...(tgt ? { [tgt.a.id]: before.targetUserId } : {}) } },
      { request: r, assignments: { [src.a.id]: r.targetUserId, ...(tgt ? { [tgt.a.id]: r.requesterId } : {}) } },
      r.decisionNote || '');
    publish(eventId('shift_request.approved', r.id, r.version), 'shift_request.approved', 'shift_request', r.id, u.id,
      [r.requesterId, r.targetUserId], `Đã duyệt ${typeLabel(r.type)}`,
      `Lịch đã cập nhật: ${view.targetUserName} phụ trách ${shiftLabel(view.sourceShift)}`
        + (view.targetShift ? `; ${view.requesterName} phụ trách ${shiftLabel(view.targetShift)}` : '') + '.',
      'my-schedule');
    return view;
  }

  function rejectShiftRequest(id, expectedVersion, note) {
    const u = me(); requireAdmin(u);
    const r = loadRequest(id);
    if (r.status === 'rejected') return enrichRequest(r);
    if (r.status !== 'pending_peer' && r.status !== 'pending_approval') throw new HrError('INVALID_STATE', 'Yêu cầu đã được xử lý.');
    checkVersion(r, expectedVersion);
    const why = requireText(note, 'Lý do từ chối');
    const before = { ...r };
    const notify = [r.requesterId].concat(r.status === 'pending_approval' ? [r.targetUserId] : []);
    Object.assign(r, { status: 'rejected', decidedBy: u.id, decidedAt: nowIso(), decisionNote: why,
      version: r.version + 1, updatedAt: nowIso() });
    upsert(HR_KEYS.SHIFT_REQUESTS, r);
    const v = enrichRequest(r);
    logHistory('shift_request_reject', `Từ chối ${typeLabel(r.type)}`, shiftLabel(v.sourceShift), u.id, before, r, why);
    publish(eventId('shift_request.rejected', r.id, r.version), 'shift_request.rejected', 'shift_request', r.id, u.id,
      notify, `Yêu cầu ${typeLabel(r.type)} bị từ chối`, `${shiftLabel(v.sourceShift)}: ${why}`, 'my-requests');
    return v;
  }

  function cancelShiftRequest(id, expectedVersion) {
    const u = me();
    const r = loadRequest(id);
    if (r.requesterId !== u.id) throw new HrError('FORBIDDEN', 'Chỉ người gửi mới hủy được yêu cầu.');
    if (r.status === 'cancelled') return enrichRequest(r);
    if (r.status !== 'pending_peer' && r.status !== 'pending_approval') throw new HrError('INVALID_STATE', 'Yêu cầu đã được xử lý.');
    checkVersion(r, expectedVersion);
    const before = { ...r };
    const notify = [r.targetUserId].concat(r.status === 'pending_approval' ? [ROLE_ADMIN] : []);
    Object.assign(r, { status: 'cancelled', version: r.version + 1, updatedAt: nowIso() });
    upsert(HR_KEYS.SHIFT_REQUESTS, r);
    const v = enrichRequest(r);
    logHistory('shift_request_cancel', `Hủy yêu cầu ${typeLabel(r.type)}`, shiftLabel(v.sourceShift), u.id, before, r, '');
    publish(eventId('shift_request.cancelled', r.id, r.version), 'shift_request.cancelled', 'shift_request', r.id, u.id,
      notify, 'Yêu cầu đã bị hủy', `${v.requesterName} đã hủy yêu cầu ${typeLabel(r.type)} ${shiftLabel(v.sourceShift)}.`, 'my-requests');
    return v;
  }

  function listShiftRequests(scope = 'mine', status = null) {
    const u = me();
    if (scope === 'all') requireAdmin(u);
    return read(HR_KEYS.SHIFT_REQUESTS)
      .filter(r => scope === 'all' || r.requesterId === u.id || r.targetUserId === u.id)
      .filter(r => !status || r.status === status)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map(enrichRequest);
  }

  /* ════════════════════════════════════════════════════════════════
     BA-04 — Nghỉ phép cơ bản + vắng đột xuất
  ════════════════════════════════════════════════════════════════ */
  const leaveTypes = (includeInactive = false) => read(HR_KEYS.LEAVE_TYPES).filter(t => includeInactive || t.active);
  const leaveRange = l => (l.startDate === l.endDate ? `ngày ${formatDate(l.startDate)}`
    : `từ ${formatDate(l.startDate)} đến ${formatDate(l.endDate)}`);

  function enrichLeave(l) {
    const t = read(HR_KEYS.LEAVE_TYPES).find(x => x.id === l.leaveTypeId);
    return { ...l, userName: userName(l.userId), leaveTypeName: t ? t.name : l.leaveTypeId,
             decidedByName: l.decidedBy ? userName(l.decidedBy) : null };
  }
  function loadLeave(id) {
    const l = read(HR_KEYS.LEAVE_REQUESTS).find(x => x.id === id);
    if (!l) throw new HrError('NOT_FOUND', 'Không tìm thấy đơn nghỉ.');
    return l;
  }

  /** Ca người nghỉ đang được phân trong khoảng nghỉ + độ phủ còn lại nếu duyệt. */
  function leaveImpact(l) {
    const shifts = State.get.assignmentsByUser(l.userId)
      .map(a => ({ a, s: State.get.shiftById(a.shiftId) }))
      .filter(x => x.s && x.s.date >= l.startDate && x.s.date <= l.endDate)
      .map(({ a, s }) => {
        const confirmedNow = State.get.assignmentsByShift(s.id).length;
        const after = Math.max(0, confirmedNow - 1);
        return { assignmentId: a.id, shiftId: s.id, shiftName: s.name, date: s.date, time: `${s.startTime}–${s.endTime}`,
                 published: s.status === 'published', required: s.totalRequired, confirmedNow, confirmedAfter: after,
                 understaffedAfter: after < s.totalRequired };
      });
    return { shifts, understaffedAfterApproval: shifts.filter(x => x.understaffedAfter).length };
  }

  function createLeave({ leaveTypeId, startDate, endDate, reason, documentUrl }) {
    const u = me();
    if (u.admin) throw new HrError('FORBIDDEN', 'Tài khoản Admin không tạo đơn nghỉ trong module này.');
    const type = leaveTypes().find(t => t.id === leaveTypeId);
    if (!type) throw new HrError('INVALID_LEAVE_TYPE', 'Loại nghỉ không hợp lệ.');
    if (!startDate || !endDate) throw new HrError('REQUIRED', 'Ngày bắt đầu và kết thúc là bắt buộc.');
    if (endDate < startDate) throw new HrError('INVALID_RANGE', 'Ngày kết thúc phải sau hoặc bằng ngày bắt đầu.');
    const earliest = _addDays(todayStr(), type.minNoticeDays || 0);
    if (startDate < earliest) {
      throw new HrError('NOTICE_TOO_SHORT', type.minNoticeDays
        ? `${type.name} cần báo trước ít nhất ${type.minNoticeDays} ngày.`
        : 'Không thể xin nghỉ cho ngày đã qua. Vắng đột xuất do Admin ghi nhận.');
    }
    const why = requireText(reason, 'Lý do');
    const doc = optionalText(documentUrl);
    if (type.requiresDocument && !doc) throw new HrError('DOCUMENT_REQUIRED', `${type.name} cần đính kèm tài liệu (đường dẫn).`);
    const overlap = read(HR_KEYS.LEAVE_REQUESTS).some(l => l.userId === u.id && ['pending', 'approved'].includes(l.status)
      && l.startDate <= endDate && l.endDate >= startDate);
    if (overlap) throw new HrError('LEAVE_OVERLAP', 'Bạn đã có đơn nghỉ chờ duyệt/đã duyệt trùng khoảng ngày này.');
    const l = { id: Storage.genId('leave'), userId: u.id, leaveTypeId, startDate, endDate, reason: why, documentUrl: doc,
                status: 'pending', decidedBy: null, decidedAt: null, decisionNote: null, affectedAssignmentCount: 0,
                version: 0, createdAt: nowIso() };
    upsert(HR_KEYS.LEAVE_REQUESTS, l);
    const impact = leaveImpact(l);
    logHistory('leave_create', 'Gửi đơn nghỉ', `${userName(u.id)} — ${type.name} ${leaveRange(l)}`, u.id, null, l, why);
    publish(eventId('leave.requested', l.id, 0), 'leave.requested', 'leave_request', l.id, u.id, [ROLE_ADMIN],
      'Đơn nghỉ chờ duyệt', `${userName(u.id)} xin ${type.name.toLowerCase()} ${leaveRange(l)}`
        + (impact.shifts.length ? ` — ảnh hưởng ${impact.shifts.length} ca, ${impact.understaffedAfterApproval} ca sẽ thiếu người` : '') + '.',
      'approvals');
    return enrichLeave(l);
  }

  function approveLeave(id, expectedVersion, note) {
    const u = me(); requireAdmin(u);
    const l = loadLeave(id);
    if (l.status === 'approved') return enrichLeave(l);
    if (l.status !== 'pending') throw new HrError('INVALID_STATE', 'Đơn nghỉ đã được xử lý.');
    checkVersion(l, expectedVersion);
    const impact = leaveImpact(l);
    const before = { ...l };
    impact.shifts.forEach(s => {                       // gỡ người nghỉ khỏi các ca trong khoảng nghỉ
      const a = assignmentById(s.assignmentId);
      if (!a || a.userId !== l.userId) throw new HrError('SCHEDULE_CHANGED', 'Phân công đã thay đổi, vui lòng tải lại.');
    });
    impact.shifts.forEach(s => State.mut.removeAssignment(s.assignmentId));
    Object.assign(l, { status: 'approved', decidedBy: u.id, decidedAt: nowIso(), decisionNote: optionalText(note),
      affectedAssignmentCount: impact.shifts.length, version: l.version + 1 });
    upsert(HR_KEYS.LEAVE_REQUESTS, l);
    const v = enrichLeave(l);
    logHistory('leave_approve', 'Duyệt đơn nghỉ', `${v.userName} — ${v.leaveTypeName} ${leaveRange(l)}`, u.id,
      before, { leave: l, cancelledAssignments: impact.shifts.map(s => s.assignmentId) }, l.decisionNote || '');
    publish(eventId('leave.approved', l.id, l.version), 'leave.approved', 'leave_request', l.id, u.id, [l.userId],
      'Đơn nghỉ đã được duyệt', `${v.leaveTypeName} ${leaveRange(l)}`
        + (impact.shifts.length ? `. Bạn đã được gỡ khỏi ${impact.shifts.length} ca trong thời gian nghỉ.` : '.'), 'leave');
    const under = impact.shifts.filter(s => s.understaffedAfter);
    if (under.length) {
      publish(eventId('schedule.coverage_warning', l.id, l.version), 'schedule.coverage_warning', 'leave_request', l.id,
        u.id, [ROLE_ADMIN], 'Cảnh báo thiếu người sau khi duyệt nghỉ',
        'Các ca thiếu người: ' + under.map(s => `${s.shiftName} ${formatDate(s.date)} (${s.confirmedAfter}/${s.required})`).join('; ')
          + '. Cân nhắc mở ca trống.', 'open-shifts');
    }
    return v;
  }

  function rejectLeave(id, expectedVersion, note) {
    const u = me(); requireAdmin(u);
    const l = loadLeave(id);
    if (l.status === 'rejected') return enrichLeave(l);
    if (l.status !== 'pending') throw new HrError('INVALID_STATE', 'Đơn nghỉ đã được xử lý.');
    checkVersion(l, expectedVersion);
    const why = requireText(note, 'Lý do từ chối');
    const before = { ...l };
    Object.assign(l, { status: 'rejected', decidedBy: u.id, decidedAt: nowIso(), decisionNote: why, version: l.version + 1 });
    upsert(HR_KEYS.LEAVE_REQUESTS, l);
    const v = enrichLeave(l);
    logHistory('leave_reject', 'Từ chối đơn nghỉ', `${v.userName} — ${leaveRange(l)}`, u.id, before, l, why);
    publish(eventId('leave.rejected', l.id, l.version), 'leave.rejected', 'leave_request', l.id, u.id, [l.userId],
      'Đơn nghỉ bị từ chối', `${v.leaveTypeName} ${leaveRange(l)}: ${why}`, 'leave');
    return v;
  }

  function cancelLeave(id, expectedVersion) {
    const u = me();
    const l = loadLeave(id);
    if (l.userId !== u.id) throw new HrError('FORBIDDEN', 'Chỉ người tạo đơn mới hủy được.');
    if (l.status === 'cancelled') return enrichLeave(l);
    if (l.status !== 'pending') throw new HrError('INVALID_STATE', 'Đơn đã được xử lý, liên hệ Admin nếu cần đổi.');
    checkVersion(l, expectedVersion);
    const before = { ...l };
    Object.assign(l, { status: 'cancelled', version: l.version + 1 });
    upsert(HR_KEYS.LEAVE_REQUESTS, l);
    logHistory('leave_cancel', 'Hủy đơn nghỉ', leaveRange(l), u.id, before, l, '');
    publish(eventId('leave.cancelled', l.id, l.version), 'leave.cancelled', 'leave_request', l.id, u.id, [ROLE_ADMIN],
      'Đơn nghỉ đã được rút', `${userName(u.id)} đã hủy đơn ${leaveRange(l)}.`, 'approvals');
    return enrichLeave(l);
  }

  function listLeave(scope = 'mine', status = null) {
    const u = me();
    if (scope === 'all') requireAdmin(u);
    return read(HR_KEYS.LEAVE_REQUESTS)
      .filter(l => scope === 'all' || l.userId === u.id)
      .filter(l => !status || l.status === status)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map(enrichLeave);
  }

  function impactOf(id) {
    const u = me();
    const l = loadLeave(id);
    if (!u.admin && l.userId !== u.id) throw new HrError('NOT_FOUND', 'Không tìm thấy đơn nghỉ.');
    return leaveImpact(l);
  }

  function recordAbsence({ assignmentId, reason }) {
    const u = me(); requireAdmin(u);
    const why = requireText(reason, 'Lý do vắng');
    const a = assignmentById(assignmentId);
    if (!a) throw new HrError('INVALID_ASSIGNMENT', 'Phân công không tồn tại.');
    const s = State.get.shiftById(a.shiftId);
    if (s.date > todayStr()) throw new HrError('FUTURE_SHIFT', 'Ca chưa diễn ra — hãy dùng đơn nghỉ.');
    const list = read(HR_KEYS.ABSENCES);
    if (list.some(x => x.userId === a.userId && x.shiftId === a.shiftId)) {
      throw new HrError('ABSENCE_EXISTS', 'Đã ghi nhận vắng cho nhân viên này ở ca này.');
    }
    const rec = { id: Storage.genId('abs'), userId: a.userId, shiftId: a.shiftId, assignmentId, reason: why,
                  recordedBy: u.id, recordedAt: nowIso() };
    list.push(rec); write(HR_KEYS.ABSENCES, list);
    logHistory('absence_record', 'Ghi nhận vắng đột xuất', `${userName(a.userId)} — ${shiftLabel(s)}`, u.id, null, rec, why);
    publish(eventId('absence.recorded', rec.id, 0), 'absence.recorded', 'absence', rec.id, u.id, [a.userId],
      'Ghi nhận vắng mặt', `Bạn được ghi nhận vắng ở ${shiftLabel(s)}: ${why}`, 'my-schedule');
    return rec;
  }

  function absenceReport(from, to) {
    const u = me(); requireAdmin(u);
    const items = read(HR_KEYS.ABSENCES).map(x => ({ ...x, shift: State.get.shiftById(x.shiftId), userName: userName(x.userId) }))
      .filter(x => x.shift && x.shift.date >= from && x.shift.date <= to);
    const shiftIds = new Set(State.get.shiftsByDateRange(from, to).map(s => s.id));
    const confirmed = State.get.assignments().filter(a => shiftIds.has(a.shiftId)).length;
    return { from, to, absences: items.length, confirmedAssignments: confirmed,
             absenceRatePercent: confirmed ? Math.round(items.length * 1000 / confirmed) / 10 : 0, items };
  }

  /* ════════════════════════════════════════════════════════════════
     BA-03 — Ca trống
  ════════════════════════════════════════════════════════════════ */
  function enrichOpen(o, viewerId) {
    const s = State.get.shiftById(o.shiftId);
    const claims = read(HR_KEYS.CLAIMS).filter(c => c.openShiftId === o.id);
    const mine = claims.find(c => c.userId === viewerId);
    return { ...o, shift: s, shiftLabel: shiftLabel(s), pendingClaims: claims.filter(c => c.status === 'pending').length,
             myClaimId: mine ? mine.id : null, myClaimStatus: mine ? mine.status : null };
  }
  function loadOpen(id) {
    const o = read(HR_KEYS.OPEN_SHIFTS).find(x => x.id === id);
    if (!o) throw new HrError('NOT_FOUND', 'Không tìm thấy ca trống.');
    return o;
  }
  const missingOf = s => Math.max(0, s.totalRequired - State.get.assignmentsByShift(s.id).length);

  /** Ca đã công bố, chưa bắt đầu, còn thiếu người — gợi ý cho Admin mở ca trống. */
  function understaffedPublishedShifts() {
    const now = new Date();
    return State.get.shifts().filter(s => s.status === 'published' && shiftStart(s) > now && missingOf(s) > 0)
      .sort((a, b) => shiftStart(a) - shiftStart(b)).map(s => ({ ...s, missing: missingOf(s) }));
  }

  function createOpenShift({ shiftId, slots, requiredLevelId, claimDeadline, note }) {
    const u = me(); requireAdmin(u);
    const s = State.get.shiftById(shiftId);
    if (!s) throw new HrError('INVALID_SHIFT', 'Ca không tồn tại.');
    if (s.status !== 'published') throw new HrError('SHIFT_NOT_PUBLISHED', 'Chỉ mở ca trống cho ca đã công bố.');
    if (shiftStart(s) <= new Date()) throw new HrError('SHIFT_STARTED', 'Ca đã bắt đầu hoặc đã qua.');
    const missing = missingOf(s);
    const n = slots == null || slots === '' ? missing : Number(slots);
    if (!Number.isInteger(n) || n < 1) throw new HrError('NO_VACANCY', 'Số chỗ phải ≥ 1 (ca có thể đã đủ người).');
    if (n > missing) throw new HrError('TOO_MANY_SLOTS', `Số chỗ (${n}) vượt số người còn thiếu (${missing}).`);
    const deadline = claimDeadline ? new Date(claimDeadline) : null;
    if (!deadline || isNaN(deadline) || deadline <= new Date()) throw new HrError('INVALID_DEADLINE', 'Hạn nhận ca phải ở tương lai.');
    if (deadline > shiftStart(s)) throw new HrError('INVALID_DEADLINE', 'Hạn nhận ca không được sau giờ bắt đầu ca.');
    if (read(HR_KEYS.OPEN_SHIFTS).some(o => o.shiftId === shiftId && o.status === 'open')) {
      throw new HrError('OPEN_SHIFT_EXISTS', 'Ca này đã có một ca trống đang mở.');
    }
    const o = { id: Storage.genId('open'), shiftId, slots: n, filledCount: 0, requiredLevelId: requiredLevelId || null,
                claimDeadline, note: optionalText(note), status: 'open', createdBy: u.id, version: 0, createdAt: nowIso() };
    upsert(HR_KEYS.OPEN_SHIFTS, o);
    logHistory('open_shift_create', 'Mở ca trống', `${shiftLabel(s)} — ${n} chỗ`, u.id, null, o, o.note || '');
    const rules = State.get.rules();
    const recipients = State.get.activeUsers()
      .filter(x => !o.requiredLevelId || !SafetyCheck.checkLevelEligibility(x.id, o.requiredLevelId, rules))
      .map(x => x.id);
    publish(eventId('open_shift.published', o.id, 0), 'open_shift.published', 'open_shift', o.id, u.id, recipients,
      'Có ca trống mới', `${shiftLabel(s)} cần ${n} người. Hạn nhận: ${claimDeadline.replace('T', ' ')}.`, 'open-shifts');
    return enrichOpen(o, u.id);
  }

  function listOpenShifts() {
    const u = me();
    const now = new Date();
    const claims = read(HR_KEYS.CLAIMS);
    return read(HR_KEYS.OPEN_SHIFTS)
      .filter(o => u.admin || (o.status === 'open' && new Date(o.claimDeadline) > now)
        || claims.some(c => c.openShiftId === o.id && c.userId === u.id))
      .map(o => enrichOpen(o, u.id))
      .sort((a, b) => (a.shift?.date || '').localeCompare(b.shift?.date || ''));
  }

  function claimsOf(openShiftId) {
    requireAdmin(me());
    return read(HR_KEYS.CLAIMS).filter(c => c.openShiftId === openShiftId)
      .map(c => ({ ...c, userName: userName(c.userId),
                   violations: c.status === 'pending' ? validateTake(c.userId, loadOpen(openShiftId).shiftId, null, [], loadOpen(openShiftId).requiredLevelId) : [] }));
  }

  function claimOpenShift(openShiftId, note) {
    const u = me();
    if (u.admin) throw new HrError('FORBIDDEN', 'Admin không đăng ký nhận ca.');
    const o = loadOpen(openShiftId);
    if (o.status !== 'open') throw new HrError('OPEN_SHIFT_CLOSED', 'Ca trống đã đóng hoặc đã đủ người.');
    if (new Date(o.claimDeadline) <= new Date()) throw new HrError('DEADLINE_PASSED', 'Đã hết hạn nhận ca.');
    const v = validateTake(u.id, o.shiftId, null, [], o.requiredLevelId);
    if (v.length) throw new HrError('VALIDATION_FAILED', 'Bạn chưa đủ điều kiện nhận ca này.', v);
    const claims = read(HR_KEYS.CLAIMS);
    let c = claims.find(x => x.openShiftId === openShiftId && x.userId === u.id);
    if (c && (c.status === 'pending' || c.status === 'approved')) return c;      // gửi lặp
    if (c && c.status !== 'withdrawn') throw new HrError('CLAIM_CLOSED', 'Đăng ký trước đó của bạn đã được xử lý.');
    if (c) Object.assign(c, { status: 'pending', note: optionalText(note), version: c.version + 1 });
    else c = { id: Storage.genId('claim'), openShiftId, userId: u.id, status: 'pending', note: optionalText(note),
               decidedBy: null, decidedAt: null, decisionNote: null, assignmentId: null, version: 0, createdAt: nowIso() };
    upsert(HR_KEYS.CLAIMS, c);
    const s = State.get.shiftById(o.shiftId);
    logHistory('open_shift_claim', 'Đăng ký nhận ca trống', shiftLabel(s), u.id, null, c, c.note || '');
    publish(eventId('open_shift.claimed', c.id, c.version), 'open_shift.claimed', 'open_shift_claim', c.id, u.id,
      [ROLE_ADMIN], 'Có người đăng ký ca trống', `${userName(u.id)} đăng ký nhận ${shiftLabel(s)}.`, 'open-shifts');
    return c;
  }

  function withdrawClaim(openShiftId) {
    const u = me();
    const c = read(HR_KEYS.CLAIMS).find(x => x.openShiftId === openShiftId && x.userId === u.id);
    if (!c) throw new HrError('NOT_FOUND', 'Không tìm thấy đăng ký.');
    if (c.status === 'withdrawn') return c;
    if (c.status !== 'pending') throw new HrError('INVALID_STATE', 'Đăng ký đã được xử lý, không thể rút.');
    Object.assign(c, { status: 'withdrawn', version: c.version + 1 });
    upsert(HR_KEYS.CLAIMS, c);
    logHistory('open_shift_withdraw', 'Rút đăng ký ca trống', openShiftId, u.id, null, c, '');
    return c;
  }

  function rejectPendingClaims(o, actorId, reason) {
    const claims = read(HR_KEYS.CLAIMS);
    const affected = [];
    claims.forEach(c => {
      if (c.openShiftId === o.id && c.status === 'pending') {
        Object.assign(c, { status: 'rejected', decidedBy: actorId, decidedAt: nowIso(), decisionNote: reason, version: c.version + 1 });
        affected.push(c.userId);
      }
    });
    write(HR_KEYS.CLAIMS, claims);
    if (affected.length) {
      publish(eventId('open_shift.claim_rejected', o.id, o.version), 'open_shift.claim_rejected', 'open_shift', o.id,
        actorId, affected, 'Đăng ký ca trống không được chọn', `${shiftLabel(State.get.shiftById(o.shiftId))}: ${reason}`, 'open-shifts');
    }
  }

  function approveClaim(openShiftId, claimId, expectedVersion, note) {
    const u = me(); requireAdmin(u);
    const c = read(HR_KEYS.CLAIMS).find(x => x.id === claimId && x.openShiftId === openShiftId);
    if (!c) throw new HrError('NOT_FOUND', 'Không tìm thấy đăng ký.');
    if (c.status === 'approved') return c;                                    // duyệt lặp
    if (c.status !== 'pending') throw new HrError('INVALID_STATE', 'Đăng ký không còn chờ duyệt.');
    checkVersion(c, expectedVersion);
    const o = loadOpen(openShiftId);
    const v = validateTake(c.userId, o.shiftId, null, [], o.requiredLevelId);
    if (v.length) throw new HrError('VALIDATION_FAILED', 'Không thể duyệt: vi phạm ràng buộc lịch.', v);
    if (o.status !== 'open' || o.filledCount >= o.slots) {                    // không bao giờ vượt số chỗ
      throw new HrError('OPEN_SHIFT_FULL', 'Ca trống đã đủ người hoặc đã đóng.');
    }
    const asg = State.mut.saveAssignment({ shiftId: o.shiftId, userId: c.userId, assignedBy: u.id,
                                           reason: `Nhận ca trống ${o.id}`, status: 'confirmed' });
    o.filledCount += 1;
    o.version += 1;
    if (o.filledCount >= o.slots) o.status = 'filled';
    upsert(HR_KEYS.OPEN_SHIFTS, o);
    Object.assign(c, { status: 'approved', decidedBy: u.id, decidedAt: nowIso(), decisionNote: optionalText(note),
                       assignmentId: asg.id, version: c.version + 1 });
    upsert(HR_KEYS.CLAIMS, c);
    const s = State.get.shiftById(o.shiftId);
    logHistory('open_shift_claim_approve', 'Duyệt nhận ca trống', `${shiftLabel(s)} — ${userName(c.userId)}`, u.id,
      { filledCount: o.filledCount - 1 }, { filledCount: o.filledCount, assignmentId: asg.id }, c.decisionNote || '');
    publish(eventId('open_shift.claim_approved', c.id, c.version), 'open_shift.claim_approved', 'open_shift_claim', c.id,
      u.id, [c.userId], 'Bạn đã nhận ca thành công', `Bạn được phân vào ${shiftLabel(s)}.`, 'my-schedule');
    if (o.status === 'filled') rejectPendingClaims(o, u.id, 'Ca trống đã đủ người.');
    return c;
  }

  function rejectClaim(openShiftId, claimId, expectedVersion, note) {
    const u = me(); requireAdmin(u);
    const c = read(HR_KEYS.CLAIMS).find(x => x.id === claimId && x.openShiftId === openShiftId);
    if (!c) throw new HrError('NOT_FOUND', 'Không tìm thấy đăng ký.');
    if (c.status === 'rejected') return c;
    if (c.status !== 'pending') throw new HrError('INVALID_STATE', 'Đăng ký không còn chờ duyệt.');
    checkVersion(c, expectedVersion);
    const why = requireText(note, 'Lý do từ chối');
    Object.assign(c, { status: 'rejected', decidedBy: u.id, decidedAt: nowIso(), decisionNote: why, version: c.version + 1 });
    upsert(HR_KEYS.CLAIMS, c);
    const s = State.get.shiftById(loadOpen(openShiftId).shiftId);
    logHistory('open_shift_claim_reject', 'Từ chối nhận ca trống', `${shiftLabel(s)} — ${userName(c.userId)}`, u.id, null, c, why);
    publish(eventId('open_shift.claim_rejected', c.id, c.version), 'open_shift.claim_rejected', 'open_shift_claim', c.id,
      u.id, [c.userId], 'Đăng ký ca trống không được chọn', `${shiftLabel(s)}: ${why}`, 'open-shifts');
    return c;
  }

  function closeOpenShift(openShiftId, cancel, note) {
    const u = me(); requireAdmin(u);
    const o = loadOpen(openShiftId);
    if (o.status !== 'open') throw new HrError('INVALID_STATE', 'Ca trống không còn mở.');
    const before = { ...o };
    o.status = cancel ? 'cancelled' : 'closed';
    o.version += 1;
    upsert(HR_KEYS.OPEN_SHIFTS, o);
    const msg = cancel ? 'Ca trống đã bị hủy' : 'Ca trống đã đóng';
    const why = optionalText(note);
    logHistory(`open_shift_${o.status}`, msg, shiftLabel(State.get.shiftById(o.shiftId)), u.id, before, o, why || '');
    rejectPendingClaims(o, u.id, why ? `${msg}: ${why}` : `${msg}.`);
    return enrichOpen(o, u.id);
  }

  /* ════════════════════════════════════════════════════════════════
     BA-05 — Thông báo in-app + nhắc hạn đăng ký
  ════════════════════════════════════════════════════════════════ */
  function listNotifications({ unreadOnly = false, page = 0, size = 20 } = {}) {
    const u = me();
    const mine = read(HR_KEYS.NOTIFICATIONS).filter(n => n.recipientId === u.id)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    const filtered = unreadOnly ? mine.filter(n => !n.read) : mine;
    return { items: filtered.slice(page * size, page * size + size), page, size, total: filtered.length,
             unread: mine.filter(n => !n.read).length };
  }
  function unreadCount() {
    const u = me();
    return read(HR_KEYS.NOTIFICATIONS).filter(n => n.recipientId === u.id && !n.read).length;
  }
  function markRead(id) {
    const u = me();
    const list = read(HR_KEYS.NOTIFICATIONS);
    const n = list.find(x => x.id === id && x.recipientId === u.id);   // chỉ thông báo của chính mình
    if (!n) throw new HrError('NOT_FOUND', 'Không tìm thấy thông báo.');
    if (!n.read) { n.read = true; n.readAt = nowIso(); write(HR_KEYS.NOTIFICATIONS, list); }
    return n;
  }
  function markAllRead() {
    const u = me();
    const list = read(HR_KEYS.NOTIFICATIONS);
    let count = 0;
    list.forEach(n => { if (n.recipientId === u.id && !n.read) { n.read = true; n.readAt = nowIso(); count++; } });
    write(HR_KEYS.NOTIFICATIONS, list);
    return count;
  }

  function getSettings() {
    const s = Storage.get(HR_KEYS.SETTINGS) || { deadlineReminderHours: [] };
    return { deadlineReminderHours: s.deadlineReminderHours || [], configured: (s.deadlineReminderHours || []).length > 0 };
  }
  function updateSettings(hours) {
    const u = me(); requireAdmin(u);
    const clean = [...new Set(hours.map(Number))];
    if (clean.some(h => !Number.isInteger(h) || h < 1 || h > 720)) {
      throw new HrError('INVALID_REMINDER_HOURS', 'Mỗi mốc nhắc phải là số giờ nguyên từ 1 đến 720.');
    }
    if (clean.length > 5) throw new HrError('TOO_MANY_REMINDERS', 'Tối đa 5 mốc nhắc.');
    clean.sort((a, b) => b - a);
    const before = getSettings();
    Storage.set(HR_KEYS.SETTINGS, { deadlineReminderHours: clean });
    logHistory('notification_settings_update', 'Cập nhật mốc nhắc hạn đăng ký',
      clean.length ? `Nhắc trước ${clean.join(', ')} giờ` : 'Tắt nhắc hạn', u.id, before, { deadlineReminderHours: clean }, '');
    return getSettings();
  }

  /**
   * Nhắc hạn đăng ký (tương ứng DeadlineReminderJob). Backend chạy theo lịch; bản demo chạy
   * khi đăng nhập/làm mới chuông. eventId gồm kỳ + mốc + hạn chót nên mỗi mốc chỉ nhắc một lần.
   */
  function runDeadlineReminders() {
    const { deadlineReminderHours, configured } = getSettings();
    if (!configured) return 0;
    const now = new Date();
    let created = 0;
    State.get.periods().filter(p => p.status === 'open' && p.deadline).forEach(p => {
      const deadline = new Date(p.deadline);
      if (isNaN(deadline) || deadline <= now) return;
      const pending = State.get.unregisteredUsersInPeriod(p.id).map(u => u.id);
      if (!pending.length) return;
      deadlineReminderHours.forEach(h => {
        if (deadline - now > h * 3600000) return;
        const id = `period.deadline_reminder:${p.id}:${h}h:${deadline.getTime()}`;
        if (publish(id, 'period.deadline_reminder', 'period', p.id, null, pending,
          'Sắp hết hạn đăng ký thời gian rảnh',
          `Kỳ "${p.name}" sẽ hết hạn đăng ký trong khoảng ${h} giờ tới. Bạn chưa gửi đăng ký — hãy hoàn tất trước hạn.`,
          'register')) created++;
      });
    });
    return created;
  }

  /** Bộ đếm việc chờ (tương ứng GET /api/pending-summary). */
  function pendingSummary() {
    const u = me();
    const reqs = read(HR_KEYS.SHIFT_REQUESTS);
    const out = { incomingPeer: reqs.filter(r => r.status === 'pending_peer' && r.targetUserId === u.id).length };
    if (u.admin) {
      out.awaitingApproval = reqs.filter(r => r.status === 'pending_approval').length;
      out.leaveAwaitingApproval = read(HR_KEYS.LEAVE_REQUESTS).filter(l => l.status === 'pending').length;
      const openIds = new Set(read(HR_KEYS.OPEN_SHIFTS).filter(o => o.status === 'open').map(o => o.id));
      out.openShiftClaimsAwaiting = read(HR_KEYS.CLAIMS).filter(c => c.status === 'pending' && openIds.has(c.openShiftId)).length;
      out.totalForApprover = out.awaitingApproval + out.leaveAwaitingApproval + out.openShiftClaimsAwaiting;
    }
    return out;
  }

  function resetAll() { Object.values(HR_KEYS).forEach(k => Storage.remove(k)); ensureSeed(); }

  return {
    ensureSeed, resetAll, shiftLabel, validateTake,
    // đổi ca
    myUpcomingAssignments, coverCandidates, createShiftRequest, peerRespond, approveShiftRequest,
    rejectShiftRequest, cancelShiftRequest, listShiftRequests,
    // nghỉ phép
    leaveTypes, createLeave, approveLeave, rejectLeave, cancelLeave, listLeave, impactOf, recordAbsence, absenceReport,
    // ca trống
    understaffedPublishedShifts, createOpenShift, listOpenShifts, claimsOf, claimOpenShift, withdrawClaim,
    approveClaim, rejectClaim, closeOpenShift,
    // thông báo
    listNotifications, unreadCount, markRead, markAllRead, getSettings, updateSettings, runDeadlineReminders,
    pendingSummary, publish,
  };
})();
