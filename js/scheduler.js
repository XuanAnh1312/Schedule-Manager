/**
 * scheduler.js
 * Thuật toán phân lịch tự động (demo).
 * Không tuyên bố tối ưu toàn cục.
 * Không tự ý phân sai điều kiện để lịch trông đầy đủ.
 */

const Scheduler = (() => {

  /**
   * Chạy phân lịch tự động cho một kỳ.
   * @param {string} periodId
   * @param {object} options { dryRun: bool }
   * @returns {object} { success, assignments[], understaffed[], rejectedCandidates[], log[], warnings[] }
   */
  function run(periodId, options = {}) {
    const { dryRun = true } = options;

    const period = State.get.periodById(periodId);
    if (!period) return _err('Không tìm thấy kỳ đăng ký.');

    const rules = State.get.rules();
    if (!rules) return _err('Chưa cấu hình quy tắc phân lịch.');

    const allUsers  = State.get.activeUsers();
    const allLevels = State.get.levels();
    const allShifts = State.get.shiftsByDateRange(period.startDate, period.endDate)
                           .filter(s => s.status !== 'published'); // chỉ phân ca chưa công bố

    if (allShifts.length === 0) return _err('Không có ca nào cần phân lịch trong kỳ này.');

    const log               = [];
    const newAssignments    = [];
    const understaffed      = [];
    const rejectedCandidates = [];
    const warnings          = [];

    // Giờ mục tiêu mỗi user
    const targetHours = {};
    const assignedHours = {}; // hours accumulated in this run
    allUsers.forEach(u => {
      targetHours[u.id]  = State.get.targetHoursForUser(u.id);
      assignedHours[u.id] = State.get.assignedHoursByUser(u.id, period.startDate, period.endDate);
    });

    // Sắp xếp ca: ưu tiên ca có ít ứng viên phù hợp (most constrained first)
    const sortedShifts = _sortShiftsByConstraint(allShifts, allUsers, period, rules);

    log.push(`Bắt đầu phân lịch: ${sortedShifts.length} ca cần phân.`);

    for (const shift of sortedShifts) {
      log.push(`\n--- Ca: ${shift.name} (${shift.date} ${shift.startTime}-${shift.endTime}) ---`);

      // Assignments đã có (từ state + run này)
      const runAsgns = [...State.get.assignments(), ...newAssignments];

      // Step 1: Lấy danh sách vị trí cần điền
      const plan = _buildSlotPlan(shift, rules, runAsgns, allUsers, period, log, rejectedCandidates, warnings);

      // Step 2: Điền từng vị trí
      const filled = _fillSlots(plan, shift, rules, runAsgns, assignedHours, targetHours, period, log, rejectedCandidates);

      filled.forEach(asgn => {
        newAssignments.push(asgn);
        assignedHours[asgn.userId] = (assignedHours[asgn.userId] || 0) + calcShiftDuration(shift.startTime, shift.endTime);
      });

      const totalFilled = filled.length;
      if (totalFilled < shift.totalRequired) {
        understaffed.push({
          shift,
          required:  shift.totalRequired,
          filled:    totalFilled,
          missing:   shift.totalRequired - totalFilled,
        });
        warnings.push(`Ca "${shift.name}" (${shift.date}): thiếu ${shift.totalRequired - totalFilled} nhân sự.`);
        log.push(`⚠ Thiếu ${shift.totalRequired - totalFilled} nhân sự.`);
      } else {
        log.push(`✓ Đủ nhân sự (${totalFilled}/${shift.totalRequired}).`);
      }
    }

    // Step 3: Tính user chưa đạt mục tiêu
    const underTarget = allUsers
      .filter(u => u.role === 'user')
      .map(u => ({
        user: u,
        target:   targetHours[u.id] || 0,
        assigned: assignedHours[u.id] || 0,
        diff: (targetHours[u.id] || 0) - (assignedHours[u.id] || 0),
      }))
      .filter(x => x.diff > 0)
      .sort((a, b) => b.diff - a.diff);

    log.push(`\nKết quả: ${newAssignments.length} phân công, ${understaffed.length} ca thiếu người.`);

    // Step 4: Lưu nếu không phải dry run
    if (!dryRun && newAssignments.length > 0) {
      _applyAssignments(newAssignments, sortedShifts);
      logHistory('schedule_run', 'Chạy phân lịch tự động',
        `Kỳ ${period.name}: ${newAssignments.length} phân công, ${understaffed.length} ca thiếu.`,
        State.get.currentUser()?.id || 'system');
    }

    return {
      success:  true,
      periodId,
      period,
      assignments:          newAssignments,
      understaffed,
      underTarget,
      rejectedCandidates,
      log,
      warnings,
      totalShifts:          sortedShifts.length,
      totalAssignments:     newAssignments.length,
    };
  }

  /* ── Build slot plan for a shift ──────────────────────────── */
  function _buildSlotPlan(shift, rules, runAsgns, allUsers, period, log, rejected, warnings) {
    const slots = []; // [{ type: 'mandatory'|'level'|'flex', levelId?, userId? }]
    const already = runAsgns.filter(a => a.shiftId === shift.id).map(a => a.userId);

    if (shift.mode === 'user') {
      // Chế độ B: theo user cụ thể
      (shift.mandatoryUsers || []).forEach(mu => {
        if (!already.includes(mu.userId)) slots.push({ type: 'mandatory', userId: mu.userId });
      });
      (shift.preferredUsers || []).forEach(pu => {
        if (!already.includes(pu.userId)) slots.push({ type: 'preferred', userId: pu.userId });
      });
      // Điền thêm flex nếu chưa đủ
      const directCount = (shift.mandatoryUsers || []).length + (shift.preferredUsers || []).length;
      for (let i = directCount; i < shift.totalRequired; i++) slots.push({ type: 'flex' });
    }
    else if (shift.mode === 'level') {
      // Chế độ A: theo level
      (shift.levelRequirements || []).forEach(lr => {
        for (let i = 0; i < (parseInt(lr.count) || 0); i++) {
          slots.push({ type: 'level', levelId: lr.levelId });
        }
      });
    }
    else if (shift.mode === 'combined') {
      // Chế độ C: kết hợp
      const mandatory = shift.mandatoryUsers || [];
      const preferred  = shift.preferredUsers || [];
      mandatory.forEach(mu => { if (!already.includes(mu.userId)) slots.push({ type: 'mandatory', userId: mu.userId }); });
      preferred.forEach(pu => { if (!already.includes(pu.userId)) slots.push({ type: 'preferred', userId: pu.userId }); });
      const filledDirect = mandatory.length + preferred.length;
      const remaining = shift.totalRequired - filledDirect;
      if (remaining > 0) {
        (shift.levelRequirements || []).forEach(lr => {
          for (let i = 0; i < (parseInt(lr.count) || 0); i++) {
            slots.push({ type: 'level', levelId: lr.levelId });
          }
        });
        // Nếu vẫn còn chỗ trống sau level
        const levelTotal = (shift.levelRequirements || []).reduce((s, r) => s + (parseInt(r.count)||0), 0);
        const flexNeeded = remaining - levelTotal;
        for (let i = 0; i < flexNeeded; i++) slots.push({ type: 'flex' });
      }
    }

    return slots;
  }

  /* ── Fill slots ──────────────────────────────────────────── */
  function _fillSlots(slots, shift, rules, runAsgns, assignedHours, targetHours, period, log, rejected) {
    const filled   = [];
    const usedIds  = new Set(runAsgns.filter(a => a.shiftId === shift.id).map(a => a.userId));
    const allUsers = State.get.activeUsers();

    for (const slot of slots) {
      if (usedIds.size >= shift.totalRequired) break;

      let chosen = null;

      if (slot.type === 'mandatory' && slot.userId) {
        // User bắt buộc: kiểm tra điều kiện
        const result = SafetyCheck.validateAssignment(slot.userId, shift, period.id, false);
        if (result.valid) {
          chosen = slot.userId;
        } else {
          log.push(`✗ User bắt buộc ${slot.userId} bị loại: ${result.errors.join('; ')}`);
          rejected.push({ shiftId: shift.id, userId: slot.userId, reasons: result.errors });
        }
      }
      else if (slot.type === 'preferred' && slot.userId) {
        const result = SafetyCheck.validateAssignment(slot.userId, shift, period.id, false);
        if (result.valid) {
          chosen = slot.userId;
        } else {
          log.push(`⚡ User ưu tiên ${slot.userId} bị loại: ${result.errors.join('; ')} → tìm người thay thế`);
          rejected.push({ shiftId: shift.id, userId: slot.userId, reasons: result.errors });
          // Tìm người thay thế cùng level
          const user = State.get.userById(slot.userId);
          if (user) {
            chosen = _findBestCandidate(allUsers, shift, period, rules, usedIds, runAsgns, assignedHours, targetHours, user.levelId, log, rejected);
          }
        }
      }
      else if (slot.type === 'level' && slot.levelId) {
        chosen = _findBestCandidate(allUsers, shift, period, rules, usedIds, runAsgns, assignedHours, targetHours, slot.levelId, log, rejected);
      }
      else if (slot.type === 'flex') {
        chosen = _findBestCandidate(allUsers, shift, period, rules, usedIds, runAsgns, assignedHours, targetHours, null, log, rejected);
      }

      if (chosen && !usedIds.has(chosen)) {
        usedIds.add(chosen);
        const user = State.get.userById(chosen);
        filled.push({
          id:         Storage.genId('asgn'),
          shiftId:    shift.id,
          userId:     chosen,
          assignedBy: 'auto',
          assignedAt: new Date().toISOString(),
          reason:     '',
          status:     'confirmed',
        });
        log.push(`✓ Phân công: ${user ? user.fullName : chosen}`);
      }
    }

    return filled;
  }

  /* ── Find best candidate for a slot ────────────────────── */
  function _findBestCandidate(allUsers, shift, period, rules, usedIds, runAsgns, assignedHours, targetHours, requiredLevelId, log, rejected) {
    // Lọc ứng viên hợp lệ
    const candidates = allUsers.filter(u => {
      if (usedIds.has(u.id)) return false;

      // Kiểm tra level (kể cả quy tắc thay thế)
      if (requiredLevelId) {
        const lvlErr = SafetyCheck.checkLevelEligibility(u.id, requiredLevelId, rules);
        if (lvlErr) return false;
      }

      // Kiểm tra điều kiện an toàn
      const result = SafetyCheck.validateAssignment(u.id, shift, period.id, false);
      if (!result.valid) {
        rejected.push({ shiftId: shift.id, userId: u.id, reasons: result.errors });
        return false;
      }
      return true;
    });

    if (candidates.length === 0) return null;

    // Ưu tiên người còn thiếu nhiều giờ mục tiêu nhất
    candidates.sort((a, b) => {
      const defA = (targetHours[a.id] || 0) - (assignedHours[a.id] || 0);
      const defB = (targetHours[b.id] || 0) - (assignedHours[b.id] || 0);
      return defB - defA;
    });

    return candidates[0].id;
  }

  /* ── Sort shifts: most constrained first ───────────────── */
  function _sortShiftsByConstraint(shifts, allUsers, period, rules) {
    return [...shifts].sort((a, b) => {
      const cA = _countEligible(a, allUsers, period, rules);
      const cB = _countEligible(b, allUsers, period, rules);
      return cA - cB; // ít ứng viên → ưu tiên phân trước
    });
  }

  function _countEligible(shift, allUsers, period, rules) {
    return allUsers.filter(u => {
      const r = SafetyCheck.validateAssignment(u.id, shift, period.id, false);
      return r.valid;
    }).length;
  }

  /* ── Apply assignments to state ─────────────────────────── */
  function _applyAssignments(newAsgns, shifts) {
    const existing = State.get.assignments();
    const combined = [...existing];
    newAsgns.forEach(a => {
      if (!combined.find(ex => ex.id === a.id)) combined.push(a);
    });
    State.mut.saveAllAssignments(combined);

    // Cập nhật trạng thái ca
    shifts.forEach(sh => {
      const shAsgns = combined.filter(a => a.shiftId === sh.id);
      const newStatus = shAsgns.length >= sh.totalRequired ? 'full' : 'understaffed';
      State.mut.saveShift({ ...sh, status: newStatus === 'full' ? 'draft' : 'draft' });
    });
  }

  /* ── Public helpers ─────────────────────────────────────── */

  /** Duyệt và công bố lịch cho danh sách shiftIds */
  function publishShifts(shiftIds, force = false) {
    const shifts   = State.get.shifts().filter(s => shiftIds.includes(s.id));
    const asgns    = State.get.assignments();
    const warnings = [];
    const errors   = [];

    shifts.forEach(sh => {
      const filled = asgns.filter(a => a.shiftId === sh.id).length;
      if (filled < sh.totalRequired) {
        const msg = `Ca "${sh.name}" (${sh.date}) thiếu ${sh.totalRequired - filled} nhân sự.`;
        if (!force) errors.push(msg);
        else warnings.push(msg);
      }
    });

    if (errors.length > 0) return { success: false, errors, warnings };

    shifts.forEach(sh => {
      State.mut.saveShift({ ...sh, status: 'published' });
    });

    logHistory('schedule_publish', 'Công bố lịch',
      `Công bố ${shifts.length} ca.`, State.get.currentUser()?.id || 'admin');

    return { success: true, published: shifts.length, warnings };
  }

  /** Thêm phân công thủ công (admin) */
  function manualAssign(userId, shiftId, periodId, reason = '', adminOverride = false) {
    const shift = State.get.shiftById(shiftId);
    if (!shift) return { success: false, error: 'Không tìm thấy ca.' };
    if (shift.status === 'published' && !adminOverride) {
      return { success: false, error: 'Ca đã công bố. Cần xác nhận để chỉnh sửa.' };
    }

    const result = SafetyCheck.validateAssignment(userId, shift, periodId, adminOverride);
    if (!result.valid) return { success: false, errors: result.errors, warnings: result.warnings };

    // Kiểm tra đã có chưa
    const existing = State.get.assignmentsByShift(shiftId).find(a => a.userId === userId);
    if (existing) return { success: false, error: 'Nhân viên đã được phân vào ca này.' };

    const asgn = State.mut.saveAssignment({
      shiftId,
      userId,
      assignedBy: 'admin',
      reason,
      status: 'confirmed',
    });

    logHistory('manual_assign', 'Phân công thủ công',
      `Admin phân ${userId} vào ca ${shiftId}. Lý do: ${reason}`,
      State.get.currentUser()?.id || 'admin',
      null, asgn, reason);

    return { success: true, assignment: asgn, warnings: result.warnings };
  }

  /** Xóa phân công */
  function removeAssignment(asgnId, reason = '') {
    const asgns  = State.get.assignments();
    const target = asgns.find(a => a.id === asgnId);
    if (!target) return { success: false, error: 'Không tìm thấy phân công.' };

    const shift = State.get.shiftById(target.shiftId);
    if (shift && shift.status === 'published') {
      logHistory('manual_unassign', 'Xóa phân công (lịch đã công bố)',
        `Xóa phân công ${target.userId} khỏi ca ${target.shiftId}. Lý do: ${reason}`,
        State.get.currentUser()?.id || 'admin', target, null, reason);
    }

    State.mut.removeAssignment(asgnId);
    return { success: true };
  }

  /** Kiểm tra dữ liệu đầu vào trước khi phân lịch */
  function preRunCheck(periodId) {
    const issues = [];
    const period = State.get.periodById(periodId);
    if (!period) return { ok: false, issues: ['Không tìm thấy kỳ đăng ký.'] };

    const shifts = State.get.shiftsByDateRange(period.startDate, period.endDate);
    if (shifts.length === 0) issues.push('Chưa có ca làm việc nào trong kỳ.');

    const unregistered = State.get.unregisteredUsersInPeriod(periodId);
    if (unregistered.length > 0) {
      issues.push(`${unregistered.length} nhân viên chưa đăng ký thời gian rảnh.`);
    }

    const rules = State.get.rules();
    if (!rules) issues.push('Chưa cấu hình quy tắc phân lịch.');

    // Kiểm tra ca không có mode hợp lệ
    const badShifts = shifts.filter(s => !['level','user','combined'].includes(s.mode));
    if (badShifts.length > 0) issues.push(`${badShifts.length} ca chưa cấu hình chế độ nhân sự.`);

    return { ok: issues.filter(i => i.includes('ca')).length === 0, issues };
  }

  function _err(msg) {
    return { success: false, error: msg, assignments: [], understaffed: [], log: [msg], warnings: [] };
  }

  return { run, publishShifts, manualAssign, removeAssignment, preRunCheck };
})();
