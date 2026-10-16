const appScripts = [
  'js/storage.js',
  'js/state.js',
  'js/validation.js',
  'js/scheduler.js',
  'js/admin.js',
  'js/admin2.js',
  'js/admin3.js',
  'js/user.js',
  'js/app.js',
];
const loadedScripts = new Set();

function setBootstrapStatus(message, state) {
  const status = document.getElementById('bootstrap-status');
  if (!status) return;

  status.className = `bootstrap-status ${state}`;
  status.replaceChildren();

  if (state === 'loading') {
    const spinner = document.createElement('span');
    spinner.className = 'loading-spinner';
    spinner.setAttribute('aria-hidden', 'true');
    status.append(spinner);
  }

  const text = document.createElement('p');
  text.textContent = message;
  status.append(text);

  if (state === 'error') {
    status.setAttribute('role', 'alert');
    status.setAttribute('aria-live', 'assertive');
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'btn btn-primary';
    retry.textContent = 'Thử tải lại';
    retry.addEventListener('click', bootstrap);
    status.append(retry);
  } else {
    status.setAttribute('role', 'status');
    status.setAttribute('aria-live', 'polite');
  }
}

async function loadPartial(mount) {
  const response = await fetch(mount.dataset.include);
  if (!response.ok) {
    throw new Error(`Không thể tải ${mount.dataset.include}: ${response.status}`);
  }
  mount.outerHTML = await response.text();
}

function loadScript(src) {
  if (loadedScripts.has(src)) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.onload = () => {
      loadedScripts.add(src);
      resolve();
    };
    script.onerror = () => {
      script.remove();
      reject(new Error(`Không thể tải ${src}`));
    };
    document.body.append(script);
  });
}

async function bootstrap() {
  const retryButton = document.querySelector('#bootstrap-status button');
  if (retryButton) retryButton.disabled = true;
  setBootstrapStatus('Đang tải ứng dụng...', 'loading');

  try {
    const mounts = [...document.querySelectorAll('[data-include]')];
    await Promise.all(mounts.map(loadPartial));

    for (const src of appScripts) {
      await loadScript(src);
    }

    window.dispatchEvent(new Event('app:ready'));
    document.getElementById('bootstrap-status')?.classList.add('hidden');
  } catch (error) {
    console.error(error);
    // Ẩn các partial đã tải để tránh chồng nội dung lên thông báo lỗi
    const loginScreen = document.getElementById('login-screen');
    const appShell = document.getElementById('app-shell');
    const dialogs = document.getElementById('modal-container');
    const confirmDialog = document.getElementById('confirm-dialog');
    if (loginScreen) loginScreen.classList.add('hidden');
    if (appShell) appShell.classList.add('hidden');
    if (dialogs) dialogs.classList.add('hidden');
    if (confirmDialog) confirmDialog.classList.add('hidden');
    const isFileUrl = window.location.protocol === 'file:';
    const message = isFileUrl
      ? 'Không thể tải ứng dụng khi mở trực tiếp tệp. Hãy chạy ứng dụng qua máy chủ HTTP rồi thử lại.'
      : 'Không thể tải đầy đủ ứng dụng. Hãy kiểm tra kết nối và thử tải lại.';
    setBootstrapStatus(message, 'error');
  }
}

bootstrap();