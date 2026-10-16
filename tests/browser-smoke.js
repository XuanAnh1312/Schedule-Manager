const runButton = document.getElementById('run-tests');
const status = document.getElementById('test-status');
const results = document.getElementById('test-results');
const frame = document.getElementById('app-under-test');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function waitFor(predicate, message, timeout = 10000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      try {
        if (predicate()) {
          clearInterval(timer);
          resolve();
        } else if (Date.now() - started > timeout) {
          clearInterval(timer);
          reject(new Error(message));
        }
      } catch (error) {
        clearInterval(timer);
        reject(error);
      }
    }, 50);
  });
}

function reloadApp() {
  return new Promise((resolve, reject) => {
    frame.onload = resolve;
    frame.onerror = () => reject(new Error('Không tải được index.html trong iframe.'));
    frame.src = `../index.html?smoke=${Date.now()}`;
  });
}

function appWindow() {
  return frame.contentWindow;
}

async function runCase(name, test) {
  try {
    await test();
    const item = document.createElement('li');
    item.className = 'passed';
    item.textContent = `Đạt: ${name}`;
    results.append(item);
  } catch (error) {
    const item = document.createElement('li');
    item.className = 'failed';
    item.textContent = `Lỗi: ${name} — ${error.message}`;
    results.append(item);
  }
}

async function runTests() {
  runButton.disabled = true;
  results.replaceChildren();
  status.textContent = 'Đang chạy kiểm thử...';

  const originalData = Array.from({ length: localStorage.length }, (_, index) => {
    const key = localStorage.key(index);
    return [key, localStorage.getItem(key)];
  }).filter(([key]) => key.startsWith('pls_'));

  try {
    for (const key of Object.keys(localStorage)) {
      if (key.startsWith('pls_')) localStorage.removeItem(key);
    }

    await reloadApp();
    await waitFor(
      () => appWindow().eval('typeof App !== "undefined" && typeof State !== "undefined"')
        && appWindow().document.getElementById('bootstrap-status').classList.contains('hidden'),
      'Ứng dụng không hoàn tất khởi động.'
    );
    appWindow().eval('window.__smoke = { App, State, Admin, UserView };');

    await runCase('Đăng nhập và báo lỗi biểu mẫu', async () => {
      const win = appWindow();
      win.document.getElementById('login-form').dispatchEvent(
        new win.Event('submit', { bubbles: true, cancelable: true })
      );
      assert(win.document.getElementById('login-username').getAttribute('aria-invalid') === 'true',
        'Trường tên đăng nhập rỗng chưa được đánh dấu lỗi.');
      assert(win.document.getElementById('login-username').getAttribute('aria-describedby') === 'login-username-err',
        'Lỗi tên đăng nhập chưa được liên kết với input.');
      win.quickLogin('admin', 'admin123');
      assert(win.__smoke.State.get.currentUser()?.role === 'admin', 'Không đăng nhập được Admin demo.');
      assert(win.document.querySelector('.sidebar-nav-item[aria-current="page"]')?.dataset.route === 'dashboard',
        'Route mặc định Admin chưa được chọn.');
    });

    await runCase('Route Admin, bộ lọc và lỗi form có liên kết trợ năng', async () => {
      const win = appWindow();
      win.__smoke.App.navigate('users');
      assert(win.document.querySelector('[aria-label="Tìm nhân viên theo tên hoặc mã"]'),
        'Ô tìm nhân viên thiếu tên trợ năng.');
      win.__smoke.Admin.openUserForm();
      win.__smoke.Admin.submitUserForm(true);
      const input = win.document.querySelector('#user-form [name="code"]');
      assert(input.getAttribute('aria-invalid') === 'true', 'Lỗi form chưa đánh dấu input không hợp lệ.');
      assert(input.getAttribute('aria-describedby')?.includes('user-form-error-code'),
        'Thông báo lỗi chưa được liên kết với input.');
      assert(win.document.getElementById('app-shell').getAttribute('aria-hidden') === 'true',
        'Nội dung nền chưa bị ẩn khỏi trình đọc màn hình khi modal mở.');
    });

    await runCase('Focus trap, Esc và khôi phục focus của modal', async () => {
      const win = appWindow();
      const modal = win.document.getElementById('modal-container');
      assert(modal.getAttribute('aria-labelledby') === 'modal-title', 'Modal thiếu aria-labelledby.');
      win.document.dispatchEvent(new win.KeyboardEvent('keydown', {
        key: 'Tab', shiftKey: true, bubbles: true, cancelable: true,
      }));
      assert(win.document.activeElement.textContent.trim() === 'Thêm nhân viên',
        'Shift+Tab chưa giữ focus trong modal.');
      win.document.dispatchEvent(new win.KeyboardEvent('keydown', {
        key: 'Escape', bubbles: true, cancelable: true,
      }));
      assert(modal.classList.contains('hidden'), 'Esc chưa đóng modal.');
      assert(win.document.activeElement.id === 'main-content', 'Focus chưa được trả về phần nội dung đã mở modal.');
      assert(!win.document.getElementById('app-shell').hasAttribute('aria-hidden'),
        'Nội dung nền chưa được mở lại sau khi đóng modal.');
    });

    await runCase('Hộp xác nhận đóng bằng Esc và trả focus', async () => {
      const win = appWindow();
      win.__smoke.App.confirm('Kiểm thử', 'Nội dung kiểm thử.', () => {});
      const dialog = win.document.getElementById('confirm-dialog');
      assert(dialog.getAttribute('aria-labelledby') === 'confirm-title', 'Hộp xác nhận thiếu aria-labelledby.');
      assert(win.document.activeElement.id === 'confirm-cancel-btn', 'Focus ban đầu chưa vào hộp xác nhận.');
      win.document.dispatchEvent(new win.KeyboardEvent('keydown', {
        key: 'Escape', bubbles: true, cancelable: true,
      }));
      assert(dialog.classList.contains('hidden'), 'Esc chưa đóng hộp xác nhận.');
      assert(win.document.activeElement.id === 'main-content', 'Focus chưa quay lại phần tử mở hộp xác nhận.');
    });

    await runCase('Responsive 320, 375, 768, 1024 và 1440 px; bảng cuộn được bằng bàn phím', async () => {
      const win = appWindow();
      let tableCanReceiveKeyboardFocus = false;
      for (const width of [320, 375, 768, 1024, 1440]) {
        frame.style.width = `${width + 1}px`;
        await new Promise(resolve => setTimeout(resolve, 80));
        assert(win.innerWidth === width, `Viewport ${width}px không được áp dụng.`);
        assert(win.document.documentElement.scrollWidth <= width,
          `Trang bị tràn ngang ở viewport ${width}px.`);
        if (width === 320) {
          tableCanReceiveKeyboardFocus = win.document.querySelector('.table-container')?.tabIndex === 0;
        }
      }
      assert(tableCanReceiveKeyboardFocus, 'Bảng rộng chưa nhận focus bằng bàn phím ở màn hình nhỏ.');
      frame.style.width = '100%';
    });

    await runCase('Menu mobile và menu tài khoản dùng được bằng bàn phím', async () => {
      const win = appWindow();
      frame.style.width = '376px';
      const menuButton = win.document.getElementById('mobile-menu-btn');
      menuButton.click();
      assert(menuButton.getAttribute('aria-expanded') === 'true', 'Menu mobile chưa cập nhật aria-expanded.');
      assert(win.document.activeElement.classList.contains('sidebar-nav-item'),
        'Mở menu mobile chưa chuyển focus vào điều hướng.');
      win.document.dispatchEvent(new win.KeyboardEvent('keydown', {
        key: 'Escape', bubbles: true, cancelable: true,
      }));
      assert(menuButton.getAttribute('aria-expanded') === 'false', 'Esc chưa đóng menu mobile.');
      assert(win.document.activeElement === menuButton, 'Focus chưa quay về nút menu mobile.');
      frame.style.width = '1441px';

      const accountToggle = win.document.getElementById('topbar-user-toggle');
      accountToggle.click();
      assert(accountToggle.getAttribute('aria-expanded') === 'true', 'Menu tài khoản chưa cập nhật aria-expanded.');
      win.document.dispatchEvent(new win.KeyboardEvent('keydown', {
        key: 'Escape', bubbles: true, cancelable: true,
      }));
      assert(accountToggle.getAttribute('aria-expanded') === 'false', 'Esc chưa đóng menu tài khoản.');
      assert(win.document.activeElement === accountToggle, 'Focus chưa quay về nút menu tài khoản.');

      accountToggle.click();
      const logoutButton = win.document.querySelector('#user-dropdown .dropdown-item.danger');
      logoutButton.focus();
      logoutButton.click();
      const confirmDialog = win.document.getElementById('confirm-dialog');
      assert(!confirmDialog.classList.contains('hidden'), 'Đăng xuất chưa mở hộp xác nhận.');
      win.document.getElementById('confirm-cancel-btn').click();
      assert(win.document.activeElement === logoutButton,
        'Hủy đăng xuất chưa khôi phục focus về nút mở hộp xác nhận.');
      assert(accountToggle.getAttribute('aria-expanded') === 'true',
        'Menu tài khoản đóng trong khi hộp xác nhận bị hủy.');
      win.document.dispatchEvent(new win.KeyboardEvent('keydown', {
        key: 'Escape', bubbles: true, cancelable: true,
      }));
      assert(win.document.activeElement === accountToggle, 'Focus chưa quay về nút menu sau khi đóng menu.');
    });

    await runCase('Nhân viên khai báo thời gian rảnh và gửi đăng ký', async () => {
      const win = appWindow();
      win.__smoke.App.switchDemoUser('nv003');
      win.__smoke.App.navigate('register');
      const period = win.__smoke.State.get.periods().find(item => item.status === 'open');
      assert(period, 'Không có kỳ đăng ký đang mở trong dữ liệu demo.');
      win.__smoke.UserView.openTimeSlot(period.startDate);
      const form = win.document.getElementById('timeslot-form');
      assert(form, 'Không mở được biểu mẫu khung giờ.');
      form.querySelector('[name="slot_start_0"]').value = '08:00';
      form.querySelector('[name="slot_end_0"]').value = '16:00';
      win.document.querySelector('#modal-footer .btn-primary').click();
      const registration = win.__smoke.State.get.freeTimeByUserPeriod('nv003', period.id);
      assert(registration?.entries.some(entry => entry.date === period.startDate),
        'Khung giờ chưa được lưu vào đăng ký.');
      win.__smoke.UserView.submitRegistration();
      win.document.getElementById('confirm-ok-btn').click();
      assert(win.__smoke.State.get.freeTimeByUserPeriod('nv003', period.id).status === 'submitted',
        'Đăng ký chưa chuyển sang trạng thái đã gửi.');
    });
  } finally {
    for (const key of Object.keys(localStorage)) {
      if (key.startsWith('pls_')) localStorage.removeItem(key);
    }
    originalData.forEach(([key, value]) => localStorage.setItem(key, value));
    await reloadApp();
    runButton.disabled = false;
  }

  const passed = results.querySelectorAll('.passed').length;
  const failed = results.querySelectorAll('.failed').length;
  status.textContent = `Hoàn tất: ${passed} đạt, ${failed} lỗi.`;
}

runButton.addEventListener('click', runTests);
