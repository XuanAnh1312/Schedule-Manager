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

async function loadPartial(mount) {
  const response = await fetch(mount.dataset.include);
  if (!response.ok) {
    throw new Error(`Không thể tải ${mount.dataset.include}: ${response.status}`);
  }
  mount.outerHTML = await response.text();
}

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.onload = resolve;
    script.onerror = () => reject(new Error(`Không thể tải ${src}`));
    document.body.append(script);
  });
}

async function bootstrap() {
  try {
    const mounts = [...document.querySelectorAll('[data-include]')];
    await Promise.all(mounts.map(loadPartial));

    for (const src of appScripts) {
      await loadScript(src);
    }

    window.dispatchEvent(new Event('app:ready'));
  } catch (error) {
    console.error(error);
    document.body.textContent = 'Không thể tải ứng dụng. Vui lòng mở trang qua máy chủ HTTP và tải lại.';
  }
}

bootstrap();