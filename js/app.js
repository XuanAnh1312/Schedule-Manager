/**
 * app.js — SPA router, toast, modal, confirm dialog, auth, bootstrap.
 * Entry point khởi động toàn bộ ứng dụng.
 */

/* ═══════════════════════════════════════════════════════════
   ROUTE CONFIG
═══════════════════════════════════════════════════════════ */
const ADMIN_ROUTES = [
  { id: 'dashboard',    label: 'Tổng quan',           icon: iconGrid(),     section: 'Admin', render: () => Admin.renderDashboard() },
  { id: 'users',        label: 'Quản lý nhân sự',     icon: iconPeople(),   section: 'Admin', render: () => Admin.renderUsers() },
  { id: 'periods',      label: 'Cấu hình đăng ký',    icon: iconConfig(),   section: 'Admin', render: () => AdminPeriod.render() },
  { id: 'calendar',     label: 'Lịch làm việc',       icon: iconCalendar(), section: 'Admin', render: () => AdminCalendar.render(), afterRender: () => AdminCalendar.init() },
  { id: 'shifts',       label: 'Quản lý ca',          icon: iconShift(),    section: 'Admin', render: () => AdminShift.render() },
  { id: 'rules',        label: 'Quy tắc phân lịch',   icon: iconRule(),     section: 'Admin', render: () => AdminRules.render() },
  { id: 'auto-schedule',label: 'Phân lịch tự động',   icon: iconAuto(),     section: 'Admin', render: () => AdminAutoSchedule.render() },
  { id: 'publish',      label: 'Duyệt & công bố',     icon: iconPublishNav(), section: 'Admin', render: () => AdminPublish.render() },
  { id: 'history',      label: 'Lịch sử & báo cáo',  icon: iconHistory(),  section: 'Admin', render: () => AdminHistory.render() },
  // Module yêu cầu / nghỉ phép / thông báo (Bảo — task v1.1 BA-01…06)
  { id: 'approvals',    label: 'Duyệt yêu cầu',       icon: iconInbox(),    section: 'Yêu cầu', render: () => HrViews.renderApprovals() },
  { id: 'open-shifts',  label: 'Ca trống',            icon: iconOpenShift(), section: 'Yêu cầu', render: () => HrViews.renderOpenShifts() },
  { id: 'notifications',label: 'Thông báo',           icon: iconBell(),     section: 'Yêu cầu', render: () => HrViews.renderNotifications() },
  { id: 'attendance-sql', label: 'Chấm công SQL & Báo cáo', icon: iconClock(), section: 'Admin', render: () => AttendanceEntry.render() },
];

const USER_ROUTES = [
  { id: 'overview',    label: 'Tổng quan',             icon: iconHome(),     section: 'User', render: () => UserView.renderOverview() },
  { id: 'register',    label: 'Đăng ký thời gian rảnh',icon: iconClock(),    section: 'User', render: () => UserView.renderRegister() },
  { id: 'my-schedule', label: 'Lịch làm việc của tôi', icon: iconCalendar(), section: 'User', render: () => UserView.renderMySchedule(), afterRender: () => UserView.initMySchedule() },
  { id: 'profile',     label: 'Thông tin cá nhân',     icon: iconUser2(),    section: 'User', render: () => UserView.renderProfile() },
  // Module yêu cầu / nghỉ phép / thông báo (Bảo — task v1.1 BA-01…06)
  { id: 'my-requests', label: 'Đổi ca / Làm thay',     icon: iconSwap(),     section: 'Yêu cầu', render: () => HrViews.renderMyRequests() },
  { id: 'leave',       label: 'Nghỉ phép',             icon: iconLeave(),    section: 'Yêu cầu', render: () => HrViews.renderLeave() },
  { id: 'open-shifts', label: 'Ca trống',              icon: iconOpenShift(), section: 'Yêu cầu', render: () => HrViews.renderOpenShifts() },
  { id: 'notifications',label: 'Thông báo',            icon: iconBell(),     section: 'Yêu cầu', render: () => HrViews.renderNotifications() },
  { id: 'attendance-sql', label: 'Chấm công và báo cáo', icon: iconClock(), section: 'User', render: () => AttendanceEntry.render() },
];

/* ═══════════════════════════════════════════════════════════
   APP CONTROLLER
═══════════════════════════════════════════════════════════ */
const App = (() => {
  let _currentRoute = null;
  let _initialized  = false;
  let _modalOpener  = null;
  let _confirmOpener = null;

  /* ── Bootstrap ─────────────────────────────────────────── */
  function init() {
    if (_initialized) return;
    _initialized = true;

    // 1. Seed demo data nếu chưa có
    seedDemoData();
    // 2. Load state
    State.load();
    // 3. Restore only a valid, active account from the saved session
    const saved = State.get.currentUser();
    const user = saved ? State.get.userById(saved.id) : null;
    if (user && user.status === 'active') {
      const sessionUser = {
        id: user.id,
        username: user.username,
        role: user.role,
        fullName: user.fullName,
      };
      State.mut.setCurrentUser(sessionUser);
      _showApp(sessionUser);
    } else {
      if (saved) State.mut.clearCurrentUser();
      _showLogin();
    }

    // 4. Bind form and global events once
    _bindLoginForm();
    _bindGlobalEvents();
  }

  function _showLogin() {
    document.getElementById('login-screen').classList.remove('hidden');
    document.getElementById('app-shell').classList.add('hidden');
    document.getElementById('login-username')?.focus();
  }

  function _showApp(user) {
    document.getElementById('login-screen').classList.add('hidden');
    document.getElementById('app-shell').classList.remove('hidden');
    _buildSidebar(user);
    _buildTopbar(user);
    // Navigate to default route
    const defaultRoute = user.role === 'admin' ? 'dashboard' : 'overview';
    navigate(defaultRoute);
  }

  /* ── Auth ──────────────────────────────────────────────── */
  function _bindLoginForm() {
    const form = document.getElementById('login-form');
    if (!form) return;
    form.onsubmit = e => {
      e.preventDefault();
      _doLogin();
    };
  }

  function _doLogin() {
    const un = document.getElementById('login-username')?.value?.trim();
    const pw = document.getElementById('login-password')?.value;
    _setLoginFieldError('login-username', '');
    _setLoginFieldError('login-password', '');
    document.getElementById('login-general-err').textContent  = '';

    if (!un) { _setLoginFieldError('login-username', 'Vui lòng nhập tên đăng nhập.'); return; }
    if (!pw) { _setLoginFieldError('login-password', 'Vui lòng nhập mật khẩu.'); return; }

    const users = State.get.users();
    const user  = users.find(u => u.username === un && u.password === pw);

    if (!user) { document.getElementById('login-general-err').textContent = 'Tên đăng nhập hoặc mật khẩu không đúng.'; return; }
    if (user.status !== 'active') { document.getElementById('login-general-err').textContent = 'Tài khoản đã bị khóa. Vui lòng liên hệ Admin.'; return; }

    State.mut.setCurrentUser({ id: user.id, username: user.username, role: user.role, fullName: user.fullName });
    _showApp(State.get.currentUser());
  }

  function _setLoginFieldError(inputId, message) {
    const input = document.getElementById(inputId);
    const error = document.getElementById(`${inputId}-err`);
    if (error) error.textContent = message;
    if (input) input.setAttribute('aria-invalid', message ? 'true' : 'false');
  }

  function logout() {
    confirm('Xác nhận đăng xuất?', '', () => {
      State.mut.clearCurrentUser();
      _currentRoute = null;
      _showLogin();
    }, { type: 'info' });
  }

  /* ── Sidebar ───────────────────────────────────────────── */
  function _buildSidebar(user) {
    const routes = user.role === 'admin' ? ADMIN_ROUTES : USER_ROUTES;
    const nav    = document.getElementById('sidebar-nav');
    if (!nav) return;

    let currentSection = '';
    let html = '';
    routes.forEach(r => {
      if (r.section !== currentSection) {
        currentSection = r.section;
        html += `<div class="sidebar-nav-section"><div class="sidebar-nav-label">${r.section}</div>`;
      }
      html += `<button class="sidebar-nav-item" data-route="${r.id}" onclick="App.navigate('${r.id}')"
        aria-current="${_currentRoute === r.id ? 'page' : 'false'}">
        <span class="nav-item-icon">${r.icon}</span>
        <span class="nav-item-text">${escHtml(r.label)}</span>
      </button>`;
    });
    // Close last section
    if (currentSection) html += '</div>';
    nav.innerHTML = html;

    // Sidebar user info
    const infoEl = document.getElementById('sidebar-user-info');
    if (infoEl) {
      const level = user.role === 'user' ? State.get.levelById(State.get.userById(user.id)?.levelId) : null;
      infoEl.innerHTML = `
        <div class="sidebar-avatar">${escHtml(user.fullName[0].toUpperCase())}</div>
        <div>
          <div class="sidebar-user-name">${escHtml(user.fullName)}</div>
          <div class="sidebar-user-role">${user.role === 'admin' ? 'Quản trị viên' : (level ? level.name : 'Nhân viên')}</div>
        </div>`;
    }
  }

  /* ── Topbar ────────────────────────────────────────────── */
  function _buildTopbar(user) {
    // Avatar & name
    const avatarEl = document.getElementById('topbar-avatar');
    const nameEl   = document.getElementById('topbar-name');
    const roleEl   = document.getElementById('topbar-role');
    if (avatarEl) avatarEl.textContent = user.fullName[0].toUpperCase();
    if (nameEl)   nameEl.textContent   = user.fullName;
    if (roleEl) {
      roleEl.textContent  = user.role === 'admin' ? 'Admin' : 'Nhân viên';
      roleEl.className    = `user-role-badge ${user.role}`;
    }

    // Role switcher (demo)
    _buildRoleSwitcher(user);

    // Điểm mở rộng: chuông thông báo + số việc chờ trên menu (module của Bảo)
    if (typeof HrViews !== 'undefined') HrViews.mountShell(user);

  }

  function _buildRoleSwitcher(user) {
    const wrap = document.getElementById('role-tabs');
    if (!wrap) return;
    const allUsers = State.get.users();
    const admins   = allUsers.filter(u => u.role === 'admin' && u.status === 'active');
    const staff    = allUsers.filter(u => u.role === 'user'  && u.status === 'active').slice(0, 4);

    let html = '';
    admins.forEach(u => {
      html += `<button class="role-tab ${user.id === u.id ? 'active' : ''}" onclick="App.switchDemoUser('${u.id}')" title="${escHtml(u.fullName)}">
        Admin
      </button>`;
    });
    staff.forEach(u => {
      html += `<button class="role-tab ${user.id === u.id ? 'active' : ''}" onclick="App.switchDemoUser('${u.id}')" title="${escHtml(u.fullName)}">
        ${escHtml(u.fullName.split(' ').pop())}
      </button>`;
    });
    wrap.innerHTML = html;
  }

  function switchDemoUser(userId) {
    const user = State.get.userById(userId);
    if (!user) return;
    State.mut.setCurrentUser({ id: user.id, username: user.username, role: user.role, fullName: user.fullName });
    // Rebuild UI
    _buildSidebar(State.get.currentUser());
    _buildTopbar(State.get.currentUser());
    const defaultRoute = user.role === 'admin' ? 'dashboard' : 'overview';
    navigate(defaultRoute);
    toast(`Đã chuyển sang tài khoản: ${user.fullName}`, 'info');
  }

  function closeUserMenu(restoreFocus = false) {
    const userMenu = document.getElementById('topbar-user');
    const toggle = document.getElementById('topbar-user-toggle');
    const focusWasInMenu = Boolean(userMenu?.contains(document.activeElement));
    userMenu?.classList.remove('open');
    toggle?.setAttribute('aria-expanded', 'false');
    if (restoreFocus || focusWasInMenu) toggle?.focus();
  }

  function setMobileNavigation(open, restoreFocus = false) {
    const sidebar = document.getElementById('sidebar');
    const overlay = document.getElementById('sidebar-overlay');
    const menuButton = document.getElementById('mobile-menu-btn');
    if (!sidebar || !overlay || !menuButton) return;

    const isMobile = window.matchMedia('(max-width: 768px)').matches;
    const shouldOpen = isMobile && open;
    sidebar.classList.toggle('mobile-open', shouldOpen);
    sidebar.inert = isMobile && !shouldOpen;
    overlay.classList.toggle('hidden', !shouldOpen);
    overlay.setAttribute('aria-hidden', String(!shouldOpen));
    menuButton.setAttribute('aria-expanded', String(shouldOpen));
    menuButton.setAttribute('aria-label', shouldOpen ? 'Đóng menu điều hướng' : 'Mở menu điều hướng');
    if (shouldOpen) sidebar.querySelector('.sidebar-nav-item')?.focus();
    else if (restoreFocus) menuButton.focus();
  }

  function _syncTableKeyboardAccess() {
    document.querySelectorAll('#main-content .table-container, #main-content .week-view').forEach(container => {
      if (container.scrollWidth > container.clientWidth) {
        container.tabIndex = 0;
        container.setAttribute('aria-label', container.classList.contains('week-view')
          ? 'Lịch tuần có thể cuộn ngang'
          : 'Bảng có thể cuộn ngang');
      } else {
        container.removeAttribute('tabindex');
        container.removeAttribute('aria-label');
      }
    });
  }

  /* ── Router ────────────────────────────────────────────── */
  function navigate(routeId) {
    const user   = State.get.currentUser();
    if (!user) { _showLogin(); return; }
    const routes = user.role === 'admin' ? ADMIN_ROUTES : USER_ROUTES;
    const route  = routes.find(r => r.id === routeId);
    if (!route) {
      // Fallback to first route
      navigate(routes[0].id);
      return;
    }

    _currentRoute = routeId;

    // Update sidebar active state
    document.querySelectorAll('.sidebar-nav-item').forEach(el => {
      el.classList.toggle('active', el.dataset.route === routeId);
      el.setAttribute('aria-current', el.dataset.route === routeId ? 'page' : 'false');
    });

    // Update breadcrumb
    const breadcrumb = document.getElementById('breadcrumb');
    if (breadcrumb) {
      breadcrumb.innerHTML = `
        <span class="breadcrumb-item">${user.role === 'admin' ? 'Admin' : 'Nhân viên'}</span>
        <span class="breadcrumb-sep">›</span>
        <span class="breadcrumb-item active">${escHtml(route.label)}</span>`;
    }

    // Render content
    const main = document.getElementById('main-content');
    if (main) {
      main.innerHTML = route.render();
      _syncTableKeyboardAccess();
      if (route.afterRender) {
        setTimeout(() => {
          if (_currentRoute === routeId && main.isConnected) route.afterRender();
        }, 0);
      }
      main.scrollTop = 0;
      main.focus();
    }

    // Update role switcher active
    _buildRoleSwitcher(user);
    // A route change also dismisses the mobile navigation.
    setMobileNavigation(false);
    closeUserMenu();
  }

  /* ── Modal ─────────────────────────────────────────────── */
  function openModal(title, body, buttons = [], sizeClass = '') {
    const container = document.getElementById('modal-container');
    const box       = document.getElementById('modal-box');
    const titleEl   = document.getElementById('modal-title');
    const bodyEl    = document.getElementById('modal-body');
    const footerEl  = document.getElementById('modal-footer');
    const backdrop  = document.getElementById('modal-backdrop');
    const closeBtn  = document.getElementById('modal-close-btn');
    if (!container || !box || !titleEl || !bodyEl || !footerEl || !backdrop || !closeBtn) {
      console.error('Modal is missing required elements.');
      return;
    }

    if (container.classList.contains('hidden')) _modalOpener = document.activeElement;
    titleEl.textContent = title;
    bodyEl.innerHTML    = body;

    // Remove old size classes
    box.className = 'modal-box';
    if (sizeClass) box.classList.add(sizeClass);

    footerEl.innerHTML = '';
    buttons.forEach(btn => {
      const el = document.createElement('button');
      el.className = `btn ${btn.cls}`;
      el.textContent = btn.label;
      el.addEventListener('click', btn.action);
      footerEl.appendChild(el);
    });

    container.classList.remove('hidden');
    _syncDialogState();
    closeBtn.focus();

    // Close on backdrop click
    backdrop.onclick = () => closeModal();
    closeBtn.onclick = () => closeModal();
  }

  function closeModal() {
    const container = document.getElementById('modal-container');
    if (!container || container.classList.contains('hidden')) return;
    container.classList.add('hidden');
    _syncDialogState();
    _restoreFocus(_modalOpener);
    _modalOpener = null;
  }

  function _syncDialogState() {
    const modal = document.getElementById('modal-container');
    const confirmDialog = document.getElementById('confirm-dialog');
    const modalOpen = Boolean(modal && !modal.classList.contains('hidden'));
    const confirmOpen = Boolean(confirmDialog && !confirmDialog.classList.contains('hidden'));
    document.body.style.overflow = modalOpen || confirmOpen ? 'hidden' : '';
    const appShell = document.getElementById('app-shell');
    if (appShell) {
      if (modalOpen || confirmOpen) appShell.setAttribute('aria-hidden', 'true');
      else appShell.removeAttribute('aria-hidden');
    }
  }

  function _restoreFocus(element) {
    if (element instanceof HTMLElement && element.isConnected
      && !element.closest('.hidden') && element.getClientRects().length > 0) {
      element.focus();
    }
  }

  function _trapDialogFocus(dialog, event) {
    if (event.key !== 'Tab') return;
    const focusable = [...dialog.querySelectorAll(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )].filter(element => !element.closest('.hidden') && element.getClientRects().length > 0);

    if (!focusable.length) {
      event.preventDefault();
      dialog.focus();
      return;
    }

    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) {
      event.preventDefault();
      first.focus();
    }
  }

  /* ── Confirm Dialog ─────────────────────────────────────── */
  function confirm(title, message, onConfirm, options = {}) {
    const dialog  = document.getElementById('confirm-dialog');
    const iconEl  = document.getElementById('confirm-icon');
    const titleEl = document.getElementById('confirm-title');
    const msgEl   = document.getElementById('confirm-message');
    const okBtn   = document.getElementById('confirm-ok-btn');
    const cancelBtn = document.getElementById('confirm-cancel-btn');
    const inputWrap = document.getElementById('confirm-input-wrap');
    const reasonInput = document.getElementById('confirm-reason-input');
    const reasonLabel = document.getElementById('confirm-reason-label');
    if (!dialog || !iconEl || !titleEl || !msgEl || !okBtn || !cancelBtn || !inputWrap || !reasonInput || !reasonLabel) {
      console.error('Confirm dialog is missing required elements.');
      return;
    }

    if (dialog.classList.contains('hidden')) _confirmOpener = document.activeElement;
    const type = options.type || 'info';
    titleEl.textContent  = title;
    msgEl.textContent    = message || '';
    okBtn.textContent    = options.okLabel || 'Xác nhận';
    okBtn.className      = `btn ${type === 'danger' ? 'btn-danger' : type === 'warn' ? 'btn-warning' : 'btn-primary'}`;

    // Icon
    const icons = {
      info:   '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>',
      warn:   '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
      danger: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>',
    };
    iconEl.className = `confirm-icon ${type}`;
    iconEl.innerHTML = icons[type] || icons.info;

    // Optional reason input
    if (options.requireReason) {
      inputWrap.classList.remove('hidden');
      reasonInput.value = '';
      reasonInput.required = true;
      reasonLabel.textContent = options.reasonLabel || 'Lý do';
    } else {
      inputWrap.classList.add('hidden');
      reasonInput.required = false;
    }

    dialog.classList.remove('hidden');
    _syncDialogState();

    // Clean up old listeners
    const newOk     = okBtn.cloneNode(true);
    const newCancel = cancelBtn.cloneNode(true);
    okBtn.parentNode.replaceChild(newOk, okBtn);
    cancelBtn.parentNode.replaceChild(newCancel, cancelBtn);

    newOk.addEventListener('click', () => {
      const reason = options.requireReason ? reasonInput.value.trim() : '';
      if (options.requireReason && !reason) {
        reasonInput.reportValidity();
        return;
      }
      _closeConfirm();
      onConfirm(reason);
    });
    newCancel.addEventListener('click', _closeConfirm);
    // Backdrop
    dialog.querySelector('.confirm-backdrop').onclick = _closeConfirm;
    newCancel.focus();
  }

  function _closeConfirm() {
    const dialog = document.getElementById('confirm-dialog');
    if (!dialog || dialog.classList.contains('hidden')) return;
    dialog.classList.add('hidden');
    _syncDialogState();
    _restoreFocus(_confirmOpener);
    _confirmOpener = null;
  }

  /* ── Toast ─────────────────────────────────────────────── */
  function toast(message, type = 'info', duration = 4000) {
    const container = document.getElementById('toast-container');
    if (!container) {
      console.error('Toast container is missing.');
      return;
    }

    const icons = {
      info:    '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>',
      success: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>',
      warning: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
      error:   '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>',
    };

    const el = document.createElement('div');
    el.className = `toast toast-${type}`;
    el.innerHTML = `
      <div class="toast-icon">${icons[type] || icons.info}</div>
      <div class="toast-content">
        <div class="toast-message">${escHtml(message)}</div>
      </div>
      <button class="toast-close" aria-label="Đóng">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>`;

    el.querySelector('.toast-close').addEventListener('click', () => _removeToast(el));
    container.appendChild(el);

    // Limit to 5 toasts
    const toasts = container.querySelectorAll('.toast');
    if (toasts.length > 5) _removeToast(toasts[0], true);

    if (duration > 0) setTimeout(() => _removeToast(el), duration);
  }

  function _removeToast(el, immediate = false) {
    if (!el || !el.parentNode) return;
    if (immediate) {
      el.parentNode.removeChild(el);
      return;
    }
    if (el.classList.contains('removing')) return;
    el.classList.add('removing');
    setTimeout(() => el.parentNode?.removeChild(el), 220);
  }

  /* ── Sidebar toggle ─────────────────────────────────────── */
  function _bindGlobalEvents() {
    // Sidebar desktop toggle
    const toggleBtn = document.getElementById('sidebar-toggle-btn');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', () => {
        const sidebar = document.getElementById('sidebar');
        const wrapper = document.querySelector('.main-wrapper');
        const isCollapsed = sidebar?.classList.toggle('collapsed') ?? false;
        wrapper?.classList.toggle('sidebar-collapsed', isCollapsed);
        toggleBtn.setAttribute('aria-expanded', String(!isCollapsed));
        toggleBtn.setAttribute('aria-label', isCollapsed
          ? 'Mở rộng thanh điều hướng'
          : 'Thu gọn thanh điều hướng');
      });
    }

    // Mobile menu
    const mobileBtn = document.getElementById('mobile-menu-btn');
    const overlay   = document.getElementById('sidebar-overlay');
    if (mobileBtn && overlay) {
      mobileBtn.addEventListener('click', () => {
        const isOpen = mobileBtn.getAttribute('aria-expanded') === 'true';
        setMobileNavigation(!isOpen, isOpen);
      });
    }
    if (overlay) {
      overlay.addEventListener('click', () => {
        setMobileNavigation(false, true);
      });
    }
    setMobileNavigation(false);
    window.addEventListener('resize', () => {
      if (!window.matchMedia('(max-width: 768px)').matches) setMobileNavigation(false);
      else setMobileNavigation(mobileBtn?.getAttribute('aria-expanded') === 'true');
      _syncTableKeyboardAccess();
    });

    // Topbar user menu
    const topbarUser = document.getElementById('topbar-user');
    const userToggle = document.getElementById('topbar-user-toggle');
    if (topbarUser && userToggle) {
      userToggle.addEventListener('click', () => {
        const isOpen = topbarUser.classList.toggle('open');
        userToggle.setAttribute('aria-expanded', String(isOpen));
      });
      document.addEventListener('click', event => {
        if (event.target instanceof Element && event.target.closest('#confirm-dialog, #modal-container')) return;
        if (!topbarUser.contains(event.target)) closeUserMenu();
      });
    }

    // Enter/Space activation cho các element có role="button" mà không phải native button/a
    // (phòng ngừa trường hợp tương lai có div/span role="button")
    document.addEventListener('keydown', e => {
      if (e.key === ' ' || e.key === 'Enter') {
        const el = e.target;
        if (
          el.getAttribute('role') === 'button' &&
          el.tagName !== 'BUTTON' &&
          el.tagName !== 'A' &&
          !el.disabled
        ) {
          e.preventDefault();
          el.click();
        }
      }
    }, true); // capture phase

    // ESC key
    document.addEventListener('keydown', e => {
      const confirmDialog = document.getElementById('confirm-dialog');
      const modal = document.getElementById('modal-container');
      if (confirmDialog && !confirmDialog.classList.contains('hidden')) {
        _trapDialogFocus(confirmDialog, e);
        if (e.key === 'Escape') {
          _closeConfirm();
        }
      } else if (modal && !modal.classList.contains('hidden')) {
        _trapDialogFocus(modal, e);
        if (e.key === 'Escape') {
          closeModal();
        }
      } else if (e.key === 'Escape') {
        if (document.getElementById('topbar-user')?.classList.contains('open')) {
          closeUserMenu(true);
        } else if (document.getElementById('sidebar')?.classList.contains('mobile-open')) {
          setMobileNavigation(false, true);
        }
      }
    });
  }

  /* ── Reset demo data ─────────────────────────────────────── */
  function resetDemo() {
    confirm(
      'Khôi phục dữ liệu demo?',
      'Toàn bộ dữ liệu hiện tại sẽ bị xóa và thay bằng dữ liệu demo mặc định. Thao tác không thể hoàn tác.',
      () => {
        const currentUser = State.get.currentUser();
        resetDemoData();
        State.load();
        const restoredUser = currentUser ? State.get.userById(currentUser.id) : null;
        if (restoredUser && restoredUser.status === 'active') {
          const sessionUser = {
            id: restoredUser.id,
            username: restoredUser.username,
            role: restoredUser.role,
            fullName: restoredUser.fullName,
          };
          State.mut.setCurrentUser(sessionUser);
          _buildSidebar(sessionUser);
          _buildTopbar(sessionUser);
          navigate(sessionUser.role === 'admin' ? 'dashboard' : 'overview');
        } else {
          State.mut.clearCurrentUser();
          _currentRoute = null;
          _showLogin();
        }
        toast('Đã khôi phục dữ liệu demo.', 'success');
      },
      { type: 'danger', okLabel: 'Xóa và khôi phục' }
    );
  }

  return {
    init, navigate, logout, switchDemoUser, closeUserMenu,
    openModal, closeModal,
    confirm, toast,
    resetDemo, refreshTableKeyboardAccess: _syncTableKeyboardAccess,
  };
})();

/* ═══════════════════════════════════════════════════════════
   GLOBAL HELPERS (called from inline HTML)
═══════════════════════════════════════════════════════════ */
function quickLogin(username, password) {
  const unEl = document.getElementById('login-username');
  const pwEl = document.getElementById('login-password');
  if (unEl) unEl.value = username;
  if (pwEl) pwEl.value = password;
  document.getElementById('login-form')?.dispatchEvent(new Event('submit'));
}

function togglePasswordVisibility(inputId, btn) {
  const input = document.getElementById(inputId);
  if (!input) return;
  input.type = input.type === 'password' ? 'text' : 'password';
  btn.querySelector('svg')?.setAttribute('opacity', input.type === 'text' ? '0.5' : '1');
}

/* ═══════════════════════════════════════════════════════════
   NAV ICONS
═══════════════════════════════════════════════════════════ */
function iconGrid()      { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>`; }
function iconPeople()    { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>`; }
function iconConfig()    { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M4.93 4.93a10 10 0 0 0 0 14.14"/></svg>`; }
function iconCalendar()  { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>`; }
function iconShift()     { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>`; }
function iconRule()      { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>`; }
function iconAuto()      { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>`; }
function iconPublishNav(){ return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>`; }
function iconHistory()   { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 .49-4.95"/></svg>`; }
function iconHome()      { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>`; }
function iconClock()     { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>`; }
function iconUser2()     { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>`; }

/* ═══════════════════════════════════════════════════════════
   BOOTSTRAP
═══════════════════════════════════════════════════════════ */
window.addEventListener('app:ready', () => {
  App.init();
}, { once: true });
function iconBell()      { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>`; }
function iconInbox()     { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg>`; }
function iconSwap()      { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>`; }
function iconLeave()     { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="9" y1="15" x2="15" y2="19"/><line x1="15" y1="15" x2="9" y2="19"/></svg>`; }
function iconOpenShift() { return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="16"/><line x1="8" y1="12" x2="16" y2="12"/></svg>`; }
