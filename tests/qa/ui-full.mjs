/*
 * Bộ kiểm thử giao diện (Playwright/Chromium) cho workspace.html và attendance.html trên backend QA :8082.
 *
 *   node tests/qa/ui-full.mjs            (chạy sau tests/qa/api-full.mjs vì dùng .local-test/qa/api-fixture.json)
 *
 * Gồm: ca kiểm thử chức năng (PASS/FAIL) và "probe" tái hiện nghi vấn lỗi FE (CONFIRMED / NOT_REPRODUCED).
 * Kết quả: .local-test/qa/ui-evidence.json, ảnh chụp ở .local-test/qa/ui/*.png
 * Mã thoát 1 khi có ca chức năng FAIL; probe CONFIRMED không làm đổi mã thoát (được báo cáo như lỗi đã xác nhận).
 */
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';

const require = createRequire(import.meta.url);
const {chromium} = require('playwright');
const fixture = JSON.parse(await readFile('.local-test/qa/api-fixture.json', 'utf8'));
const base = fixture.base;
if (!/^http:\/\/(localhost|127\.0\.0\.1):8082$/.test(base)) throw new Error('Chỉ chạy trên backend QA localhost:8082');
const shotDir = '.local-test/qa/ui';
await mkdir(shotDir, {recursive: true});
const run = 'ui' + Date.now().toString(36);
const password = 'QaUi-2026!';

const evidence = {startedAt: new Date().toISOString(), base, run, cases: [], probes: [], screenshots: [], serverErrors: [], runtimeErrors: []};
let group = 'UI';
const counters = {};
function record(title, pass, detail = '') {
  const id = `${group}-${String(counters[group] = (counters[group] || 0) + 1).padStart(3, '0')}`;
  evidence.cases.push({id, group, title, pass: Boolean(pass), detail});
  console.log(`${pass ? 'PASS' : 'FAIL'} [${id}] ${title}${detail ? ' :: ' + detail : ''}`);
  return pass;
}
function probe(code, title, confirmed, detail, location) {
  evidence.probes.push({code, title, status: confirmed ? 'CONFIRMED' : 'NOT_REPRODUCED', detail, location});
  console.log(`PROBE ${confirmed ? 'CONFIRMED' : 'NOT_REPRODUCED'} [${code}] ${title} :: ${detail}`);
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const VN = 7 * 3600_000;
const localDate = days => { const d = new Date(Date.now() + VN); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10); };
const localDateTime = ms => new Date(Date.now() + VN + ms).toISOString().slice(0, 16);

// ───────────── API trợ giúp để dựng dữ liệu ─────────────
async function api(path, token, method = 'GET', body) {
  const res = await fetch(base + path, {method, headers: {...(token ? {Authorization: 'Bearer ' + token} : {}), ...(body ? {'Content-Type': 'application/json'} : {})}, ...(body ? {body: JSON.stringify(body)} : {})});
  const text = await res.text();
  let data; try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 300)}`);
  return data;
}
const token = async (u, p) => (await api('/api/auth/login', '', 'POST', {username: u, password: p})).token;

// ───────────── Playwright trợ giúp ─────────────
const browser = await chromium.launch({headless: true});
async function newPage(viewport = {width: 1280, height: 900}) {
  const context = await browser.newContext({viewport, locale: 'vi-VN', timezoneId: 'Asia/Ho_Chi_Minh'});
  context.setDefaultTimeout(15000);
  const page = await context.newPage();
  page.dialogs = [];
  page.on('dialog', async dialog => { page.dialogs.push(dialog.message()); await dialog.accept(); });
  page.on('pageerror', error => evidence.runtimeErrors.push({group, message: error.message}));
  page.on('response', response => { if (response.status() >= 500) evidence.serverErrors.push({group, url: response.url(), status: response.status()}); });
  return page;
}
async function shot(page, name) {
  const path = `${shotDir}/${name}.png`;
  await page.screenshot({path, fullPage: false});
  evidence.screenshots.push({name, path, group, viewport: page.viewportSize()});
}
async function idle(page) { await page.locator('#content[aria-busy=false]').waitFor(); await sleep(150); }
async function login(page, username, pass) {
  await page.goto(base + '/');
  await page.locator('#login-form input[name=username]').fill(username);
  await page.locator('#login-form input[name=password]').fill(pass);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await page.locator('#workspace').waitFor({state: 'visible'});
  await idle(page);
}
async function route(page, id) {
  await page.locator(`#navigation [data-route="${id}"]`).click();
  await idle(page);
  return page.locator('#notice').evaluate(n => n.classList.contains('error') ? n.textContent : '');
}
const contentButton = (page, text) => page.locator('#content button', {hasText: text});
async function openEditor(page, text) { await contentButton(page, text).first().click(); await page.locator('#editor[open]').waitFor(); }
async function setField(page, name, value) {
  const input = page.locator(`#editor-fields [name="${name}"]`);
  const tag = await input.evaluate(n => n.tagName.toLowerCase() + (n.multiple ? '[]' : ''));
  if (tag === 'select[]') await input.selectOption(value.map(String));
  else if (tag === 'select') await input.selectOption(String(value));
  else if ((await input.getAttribute('type')) === 'checkbox') await input.setChecked(Boolean(value));
  else await input.fill(String(value));
}
async function saveEditor(page) {
  await page.locator('#editor-save').click();
  for (let i = 0; i < 60; i++) {
    const open = await page.locator('#editor').evaluate(d => d.open);
    const error = await page.locator('#editor-error').textContent();
    if (error) return {ok: false, error};
    if (!open) { await idle(page); return {ok: true}; }
    await sleep(100);
  }
  return {ok: false, error: 'timeout'};
}
const overflow = page => page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);

let exitCode = 0;
try {
  // ───────────── dựng dữ liệu riêng cho UI ─────────────
  const adminToken = await token(fixture.adminUsername, fixture.adminPassword);
  const levels = await api('/api/levels', adminToken);
  const mk = async key => {
    const username = `${run}_${key}`;
    const res = await api('/api/workspace/employees', adminToken, 'POST', {employeeCode: (run + key).slice(-20), username, password, fullName: `UI Nhân viên ${key.toUpperCase()}`, levelId: levels[0].id, status: 'active'});
    return {id: res.id, username};
  };
  const E1 = await mk('e1'), E2 = await mk('e2');
  let UP = await api('/api/periods', adminToken, 'POST', {name: 'UI Kỳ ' + run, cycle: 'weekly', startDate: localDate(2), endDate: localDate(8), openAt: localDateTime(-3600_000),
    deadline: localDateTime(6 * 3600_000), autoLock: true, allowReopenByAdmin: true, requiredDaysOfWeek: [0, 1, 2, 3, 4, 5, 6], applicableUserIds: [E1.id, E2.id]});
  UP = await api(`/api/periods/${UP.id}/open`, adminToken, 'POST', {version: UP.version});
  const e1Token = await token(E1.username, password);
  await api(`/api/availability/my/submit?periodId=${UP.id}`, e1Token, 'POST', {entries: Array.from({length: 7}, (_, i) => ({date: localDate(i + 2), slots: [{start: '00:00', end: '23:59', type: 'available'}]}))});

  // ───────────── 1. Đăng nhập & phiên ─────────────
  group = 'UI-AUTH';
  let page = await newPage();
  await page.goto(base + '/');
  record('Trang đăng nhập hiển thị, ô Tài khoản/Mật khẩu có nhãn', await page.getByLabel('Tài khoản').isVisible() && await page.getByLabel('Mật khẩu').isVisible());
  record('Ô tài khoản bắt buộc, tối đa 30 ký tự', (await page.locator('#login-form [name=username]').getAttribute('maxlength')) === '30' && await page.locator('#login-form [name=username]').evaluate(n => n.required));
  let loginCalls = 0;
  page.on('request', r => { if (r.url().endsWith('/api/auth/login')) loginCalls++; });
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await sleep(300);
  record('Bấm Đăng nhập khi để trống: trình duyệt chặn, không gọi API', loginCalls === 0 && await page.locator('#login-form [name=username]').evaluate(n => !n.validity.valid));
  await shot(page, '01-login');
  await page.locator('#login-form [name=username]').fill(fixture.adminUsername);
  await page.locator('#login-form [name=password]').fill('wrong-password');
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await page.locator('#login-error:not(:empty)').waitFor();
  const wrongMsg = await page.locator('#login-error').textContent();
  record('Sai mật khẩu: không vào được workspace, có thông báo lỗi', await page.locator('#workspace').isHidden() && wrongMsg.length > 0, wrongMsg);
  probe('FE-01', 'Sai mật khẩu hiển thị "Phiên đăng nhập đã hết hạn" thay vì thông báo sai thông tin đăng nhập',
    /hết hạn/i.test(wrongMsg), `Thông báo hiển thị: "${wrongMsg}". API trả 401 INVALID_CREDENTIALS "Tên đăng nhập hoặc mật khẩu không đúng."`, 'js/workspace.js:19');
  await shot(page, '02-login-wrong-password');
  await page.locator('#login-form [name=username]').fill(fixture.lockedEmployee.username);
  await page.locator('#login-form [name=password]').fill(fixture.lockedEmployee.password);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await sleep(800);
  record('Tài khoản đã khóa không đăng nhập được', await page.locator('#workspace').isHidden(), await page.locator('#login-error').textContent());
  await login(page, fixture.adminUsername, fixture.adminPassword);
  record('Đăng nhập admin: vào workspace, hiển thị tên, trang đầu là Kỳ làm việc',
    (await page.locator('#identity').textContent()).length > 0 && (await page.locator('#page-title').textContent()) === 'Kỳ làm việc', await page.locator('#identity').textContent());
  record('JWT lưu trong sessionStorage (không localStorage)', await page.evaluate(() => Boolean(sessionStorage.getItem('attendance_jwt')) && !Object.keys(localStorage).some(k => /jwt|token/i.test(k))));
  await page.reload();
  await page.locator('#workspace').waitFor({state: 'visible'});
  record('Tải lại trang giữ phiên đăng nhập', await page.locator('#login').isHidden());
  await page.locator('#logout').click();
  await page.locator('#login').waitFor({state: 'visible'});
  record('Đăng xuất: xóa JWT khỏi sessionStorage, về màn hình đăng nhập', await page.evaluate(() => sessionStorage.getItem('attendance_jwt') === null));
  await page.evaluate(() => sessionStorage.setItem('attendance_jwt', 'invalid.token.value'));
  await page.reload();
  await page.locator('#login').waitFor({state: 'visible'});
  const expiredMsg = await page.locator('#login-error').textContent();
  record('Token không hợp lệ khi tải trang: quay về đăng nhập, báo phiên hết hạn', /hết hạn/i.test(expiredMsg) && await page.evaluate(() => sessionStorage.getItem('attendance_jwt') === null), expiredMsg);
  await page.context().close();

  // ───────────── 2. Admin: duyệt qua 14 màn hình ─────────────
  group = 'UI-ADMIN';
  page = await newPage();
  await login(page, fixture.adminUsername, fixture.adminPassword);
  const adminRoutes = await page.locator('#navigation [data-route]').evaluateAll(ns => ns.map(n => n.dataset.route));
  record('Admin thấy đủ 14 phân hệ', adminRoutes.length === 14, adminRoutes.join(', '));
  let i = 0;
  const adminPeriods = await api('/api/periods', adminToken);
  for (const id of adminRoutes) {
    const error = await route(page, id);
    if (id === 'attendance') await page.frameLocator('iframe').locator('#app-view').waitFor({state: 'visible'});
    const draftDefault = id === 'availability' && adminPeriods[0]?.status === 'draft' && /không tìm thấy kỳ/i.test(error);
    record(`Màn hình ${id}: tải không lỗi`, !error || draftDefault, draftDefault ? 'kỳ mặc định đang là nháp → xem probe FE-14' : error);
    await shot(page, `10-admin-${String(++i).padStart(2, '0')}-${id}`);
  }
  // Tạo kỳ qua form
  await route(page, 'periods');
  await openEditor(page, 'Tạo kỳ');
  await page.locator('#editor-save').click();
  await sleep(300);
  record('Form Tạo kỳ: để trống bị chặn bởi validation HTML5, dialog vẫn mở', await page.locator('#editor').evaluate(d => d.open) && await page.locator('#editor-fields [name=name]').evaluate(n => !n.validity.valid));
  await page.keyboard.press('Escape');
  await sleep(200);
  record('Phím Esc đóng dialog', await page.locator('#editor').evaluate(d => !d.open));
  await openEditor(page, 'Tạo kỳ');
  await setField(page, 'name', 'UI Kỳ tạo qua form ' + run);
  await setField(page, 'cycle', 'weekly');
  await setField(page, 'startDate', localDate(20));
  await setField(page, 'endDate', localDate(26));
  await setField(page, 'autoLock', false);
  const noDays = await saveEditor(page);
  record('Form Tạo kỳ thiếu "Ngày cần đăng ký": lỗi máy chủ hiển thị trong dialog', !noDays.ok && noDays.error.length > 0, noDays.error);
  probe('FE-09', 'Trường "Ngày cần đăng ký" không bắt buộc ở client dù máy chủ yêu cầu ít nhất 1 ngày', !noDays.ok,
    `Client gửi requiredDaysOfWeek=[] và chỉ nhận lỗi từ máy chủ: "${noDays.error}"`, 'js/workspace.js:65');
  await setField(page, 'requiredDaysOfWeek', [1, 2, 3, 4, 5]);
  const created = await saveEditor(page);
  record('Tạo kỳ hợp lệ qua form', created.ok && await page.locator('#content td', {hasText: 'UI Kỳ tạo qua form ' + run}).count() === 1, created.error || '');
  await shot(page, '20-admin-period-created');
  const draftId = (await api('/api/periods', adminToken)).find(p => p.name === 'UI Kỳ tạo qua form ' + run)?.id;
  await route(page, 'availability');
  await page.locator('#content select[name=period]').selectOption(draftId);
  await idle(page);
  const draftError = await page.locator('#notice').evaluate(n => n.classList.contains('error') ? n.textContent : '');
  await shot(page, '24-admin-availability-draft-period');
  probe('FE-14', 'Màn hình "Đăng ký thời gian" (admin) báo lỗi "Không tìm thấy kỳ đăng ký" khi chọn kỳ nháp',
    /không tìm thấy kỳ/i.test(draftError), `Thông báo: "${draftError}". API /api/availability/progress|summary trả 404 NOT_FOUND cho kỳ draft dù kỳ tồn tại.`,
    'js/workspace.js:87 + api/availability/AvailabilityService');
  // Nhân viên: mật khẩu ngắn, XSS
  await route(page, 'directory');
  await openEditor(page, 'Thêm nhân viên');
  await setField(page, 'employeeCode', (run + 'x').slice(-20));
  await setField(page, 'username', run + '_short');
  await setField(page, 'password', 'short');
  await setField(page, 'fullName', 'UI Mật khẩu ngắn');
  const shortPw = await saveEditor(page);
  record('Thêm nhân viên với mật khẩu < 8 ký tự: hiển thị lỗi máy chủ', !shortPw.ok && /mật khẩu|8/i.test(shortPw.error), shortPw.error);
  probe('FE-10', 'Gợi ý "Ít nhất 8 ký tự" không được kiểm tra ở client (không có minlength)', !shortPw.ok && await page.locator('#editor-fields [name=password]').evaluate(n => !n.minLength || n.minLength < 8),
    'Ô mật khẩu không có minlength; lỗi chỉ đến từ máy chủ.', 'js/workspace.js:256');
  await page.locator('#editor-cancel').click();
  await page.locator('#content input[name=q]').fill(fixture.run);
  await contentButton(page, 'Tìm kiếm').click();
  await idle(page);
  const xssCell = page.locator('#content td', {hasText: 'onerror'});
  const xssRendered = await xssCell.count() > 0;
  const xssExecuted = await page.evaluate(() => window.__xss === 1 || document.querySelectorAll('#content img').length > 0);
  record('Tên chứa payload XSS hiển thị dạng văn bản, không thực thi', xssRendered && !xssExecuted, `hiển thị=${xssRendered} thực thi=${xssExecuted}`);
  await shot(page, '21-admin-directory-xss-escaped');
  // Đổi mật khẩu: mở & hủy
  await page.locator('#change-password').click();
  await page.locator('#editor[open]').waitFor();
  record('Dialog Đổi mật khẩu có 2 trường bắt buộc', await page.locator('#editor-fields input[type=password][required]').count() === 2);
  await page.locator('#editor-cancel').click();

  // Responsive: admin ở 375px
  await page.setViewportSize({width: 375, height: 812});
  const overflowRoutes = [];
  for (const id of adminRoutes) { await route(page, id); if (await overflow(page)) overflowRoutes.push(id); }
  record('Không tràn ngang ở 375px (14 màn hình admin)', overflowRoutes.length === 0, overflowRoutes.join(', '));
  await route(page, 'calendar');
  await shot(page, '22-admin-calendar-375');
  await page.setViewportSize({width: 1280, height: 900});

  // ───────────── 3. Nhân viên E2: đăng ký thời gian qua UI ─────────────
  group = 'UI-EMPLOYEE';
  const emp = await newPage();
  await login(emp, E2.username, password);
  const empRoutes = await emp.locator('#navigation [data-route]').evaluateAll(ns => ns.map(n => n.dataset.route));
  record('Nhân viên thấy 10 phân hệ, không thấy shifts/plans/directory/audit', empRoutes.length === 10 && !empRoutes.some(r => ['shifts', 'plans', 'directory', 'audit'].includes(r)), empRoutes.join(', '));
  record('Trang đầu của nhân viên là Lịch làm việc', (await emp.locator('#page-title').textContent()) === 'Lịch làm việc');
  i = 0;
  for (const id of empRoutes) {
    const error = await route(emp, id);
    if (id === 'attendance') await emp.frameLocator('iframe').locator('#app-view').waitFor({state: 'visible'});
    record(`Màn hình ${id} (nhân viên): tải không lỗi`, !error, error);
    await shot(emp, `30-employee-${String(++i).padStart(2, '0')}-${id}`);
  }
  await route(emp, 'availability');
  record('Màn hình đăng ký có sẵn 1 dòng khung giờ', await emp.locator('#content .availability-row').count() >= 1);
  await contentButton(emp, 'Thêm khoảng giờ').click();
  const rows = emp.locator('#content .availability-row');
  await rows.nth(1).locator('[name=date]').fill(localDate(3));
  await rows.nth(1).locator('[name=start]').fill('13:00');
  await rows.nth(1).locator('[name=end]').fill('18:00');
  await contentButton(emp, 'Lưu nháp').click();
  await idle(emp);
  let mine = await api(`/api/availability/my?periodId=${UP.id}`, await token(E2.username, password));
  record('Lưu nháp qua UI ghi vào máy chủ (2 khung giờ, trạng thái draft)', mine.registration.status === 'draft' && mine.registration.entries.length === 2, JSON.stringify(mine.registration.entries));
  await rows.nth(1).locator('[name=start]').fill('');
  await emp.evaluate(() => { document.getElementById('notice').textContent = ''; });
  await contentButton(emp, 'Gửi đăng ký').click();
  await sleep(500);
  mine = await api(`/api/availability/my?periodId=${UP.id}`, await token(E2.username, password));
  probe('FE-11', 'Khung giờ thiếu thông tin: lưu bị chặn "im lặng" (chỉ có bong bóng HTML5, không có thông báo)', mine.registration.status === 'draft' && !(await emp.locator('#notice').textContent()),
    'reportValidity() trả false nên hàm save return sớm; #notice trống.', 'js/workspace.js:101');
  await rows.nth(1).locator('[name=start]').fill('13:00');
  await contentButton(emp, 'Gửi đăng ký').click();
  await idle(emp);
  mine = await api(`/api/availability/my?periodId=${UP.id}`, await token(E2.username, password));
  record('Gửi đăng ký qua UI: trạng thái submitted', mine.registration.status === 'submitted', (await emp.locator('#notice').textContent()));
  await shot(emp, '31-employee-availability-submitted');

  // ───────────── dựng lịch đã duyệt (chờ công bố) ─────────────
  UP = await api(`/api/periods/${UP.id}`, adminToken);
  UP = await api(`/api/periods/${UP.id}/lock`, adminToken, 'POST', {version: UP.version});
  const SH1 = await api('/api/schedule/shifts', adminToken, 'POST', {periodId: UP.id, name: 'UI Ca E1 ' + run, date: localDate(4), startTime: '08:00', endTime: '12:00', mode: 'combined', totalRequired: 2, minRequired: 1, maxAllowed: 2, mandatoryUserIds: [E1.id]});
  const SH2 = await api('/api/schedule/shifts', adminToken, 'POST', {periodId: UP.id, name: 'UI Ca E2 ' + run, date: localDate(2), startTime: '08:00', endTime: '12:00', mode: 'combined', totalRequired: 1, minRequired: 1, maxAllowed: 1, mandatoryUserIds: [E2.id]});
  let plan = await api('/api/schedule/plans', adminToken, 'POST', {periodId: UP.id, name: 'UI Phương án ' + run, shiftIds: [SH1.id, SH2.id]});
  plan = (await api(`/api/schedule/plans/${plan.id}/submit`, adminToken, 'POST', {expectedVersion: plan.version})).plan;
  plan = (await api(`/api/schedule/plans/${plan.id}/approve`, adminToken, 'POST', {expectedVersion: plan.version})).plan;
  const readiness = await api(`/api/schedule/plans/${plan.id}/readiness`, adminToken);

  // ───────────── 4. Admin công bố qua UI ─────────────
  group = 'UI-ADMIN';
  await route(page, 'plans');
  await page.locator('#content select[name=period]').selectOption(UP.id);
  await idle(page);
  await page.locator('#content tr', {hasText: 'UI Phương án ' + run}).locator('button', {hasText: 'Chi tiết'}).click();
  await contentButton(page, 'Công bố').waitFor();
  await shot(page, '23-admin-plan-detail');
  page.dialogs.length = 0;
  await contentButton(page, 'Công bố').click();
  await idle(page);
  await sleep(500);
  const afterPublish = await api(`/api/schedule/plans/${plan.id}`, adminToken);
  record('Công bố phương án qua UI', afterPublish.status === 'published', `status=${afterPublish.status}; dialogs=${JSON.stringify(page.dialogs)}`);
  probe('FE-04', 'Luôn hỏi "Xác nhận công bố cả khi có ca thiếu người?" kể cả khi không có ca thiếu người',
    (readiness.understaffed || []).length === 0 && page.dialogs.some(m => /thiếu người/i.test(m)),
    `readiness.understaffed=${(readiness.understaffed || []).length}; hộp thoại: ${page.dialogs.join(' | ')}`, 'js/workspace.js:170');
  const open = await api('/api/open-shifts', adminToken, 'POST', {shiftId: SH1.id, slots: 1, claimDeadline: localDate(3) + 'T20:00', note: 'UI ca trống'});
  probe('FE-12', 'Màn hình Ca trống không có bộ chọn kỳ: "Mở ca trống" dùng kỳ đang chọn ở màn hình khác', await (async () => { await route(page, 'openShifts'); return await page.locator('#content select[name=period]').count() === 0; })(),
    'openShiftsScreen() dùng currentPeriod() nhưng không gọi periodPicker().', 'js/workspace.js:222-223');

  // ───────────── 5. Nhân viên: ca trống, đổi ca, nghỉ phép, lịch ─────────────
  group = 'UI-EMPLOYEE';
  await route(emp, 'openShifts');
  const openRow = emp.locator('#content tr', {hasText: 'UI Ca E1 ' + run});
  const withdrawVisible = await openRow.locator('button', {hasText: 'Rút đăng ký'}).count() > 0;
  record('Nhân viên thấy ca trống đang mở và nút Đăng ký', await openRow.locator('button', {hasText: 'Đăng ký'}).first().isVisible());
  await shot(emp, '32-employee-open-shifts');
  if (withdrawVisible) {
    await openRow.locator('button', {hasText: 'Rút đăng ký'}).click();
    await sleep(800);
    const err = await emp.locator('#notice').evaluate(n => n.classList.contains('error') ? n.textContent : '');
    probe('FE-03', 'Nút "Rút đăng ký" hiện cả khi nhân viên chưa đăng ký; bấm vào nhận lỗi', Boolean(err), `Thông báo: "${err}"`, 'js/workspace.js:226');
  } else probe('FE-03', 'Nút "Rút đăng ký" hiện cả khi nhân viên chưa đăng ký', false, 'Nút không hiển thị', 'js/workspace.js:226');
  void open;

  await route(emp, 'requests');
  await openEditor(emp, 'Tạo yêu cầu đổi / thay ca');
  const myShiftOptions = await emp.locator('#editor-fields [name=assignmentId] option').count();
  record('Dialog chọn ca liệt kê ca đã công bố của tôi', myShiftOptions >= 1, `${myShiftOptions} lựa chọn`);
  const blankRequired = await emp.locator('#editor-fields [name=assignmentId]').inputValue();
  probe('FE-13', 'Ô chọn bắt buộc mở ra ở trạng thái trống, phải chọn lại dù chỉ có một lựa chọn', blankRequired === '' && myShiftOptions === 1,
    `Giá trị ban đầu="${blankRequired}" với ${myShiftOptions} lựa chọn; bấm "Tiếp tục" ngay sẽ bị HTML5 chặn.`, 'js/workspace.js:37-45 (field)');
  await emp.locator('#editor-fields [name=assignmentId]').selectOption({index: 0});
  await emp.locator('#editor-save').click();
  await emp.locator('#editor-title', {hasText: 'Gửi yêu cầu'}).waitFor();
  const typeRequired = await emp.locator('#editor-fields [name=type]').evaluate(n => n.required);
  const typeDefault = await emp.locator('#editor-fields [name=type]').inputValue();
  await emp.locator('#editor-fields [name=targetUserId]').selectOption({index: 0});
  await setField(emp, 'reason', 'UI nhờ làm thay');
  const noType = await saveEditor(emp);
  probe('FE-02', 'Trường "Hình thức" không bắt buộc, mặc định "Không chọn" → gửi type=null và bị máy chủ từ chối', !typeRequired && typeDefault === '' && !noType.ok,
    `required=${typeRequired}, giá trị mặc định="${typeDefault}", lỗi: "${noType.error}"`, 'js/workspace.js:206');
  await setField(emp, 'type', 'cover');
  const sent = await saveEditor(emp);
  record('Gửi yêu cầu làm thay qua UI', sent.ok && await emp.locator('#content td', {hasText: 'UI nhờ làm thay'}).count() >= 1, sent.error || '');
  await shot(emp, '33-employee-request-created');

  await route(emp, 'leave');
  await openEditor(emp, 'Gửi đơn nghỉ');
  await setField(emp, 'leaveTypeId', 'leave_annual');
  await setField(emp, 'startDate', localDate(30));
  await setField(emp, 'endDate', localDate(31));
  await setField(emp, 'reason', 'UI nghỉ phép năm');
  const leave = await saveEditor(emp);
  record('Gửi đơn nghỉ qua UI', leave.ok && await emp.locator('#content tr', {hasText: 'UI nghỉ phép năm'}).count() === 1, leave.error || '');
  await openEditor(emp, 'Gửi đơn nghỉ');
  await setField(emp, 'leaveTypeId', 'leave_annual');
  await setField(emp, 'startDate', localDate(30));
  await setField(emp, 'endDate', localDate(30));
  await setField(emp, 'reason', 'UI trùng ngày');
  const overlap = await saveEditor(emp);
  record('Đơn nghỉ trùng ngày: hiển thị lỗi máy chủ trong dialog', !overlap.ok, overlap.error);
  await emp.locator('#editor-cancel').click();
  await shot(emp, '34-employee-leave');

  const e1 = await newPage();
  await login(e1, E1.username, password);
  await e1.locator('#content input[name=date]').fill(localDate(4));
  await contentButton(e1, 'Xem lịch').click();
  await idle(e1);
  record('Nhân viên E1 thấy ca đã công bố trong lịch của mình', await e1.locator('#content article h3', {hasText: 'UI Ca E1 ' + run}).count() === 1);
  await contentButton(e1, 'Xác nhận lịch').first().click();
  await sleep(800);
  record('Nhân viên xác nhận lịch qua UI', /xác nhận/i.test(await e1.locator('#notice').textContent()), await e1.locator('#notice').textContent());
  await shot(e1, '35-employee-calendar-confirmed');

  // Cổng chấm công nhúng: chia sẻ JWT và đăng xuất trong iframe
  await route(e1, 'attendance');
  const frame = e1.frameLocator('iframe');
  await frame.locator('#app-view').waitFor({state: 'visible'});
  record('Cổng chấm công nhúng dùng chung JWT (không phải đăng nhập lại)', await frame.locator('#login-view').isHidden());
  await shot(e1, '36-employee-attendance-iframe');
  await frame.locator('#logout-btn').click();
  await sleep(300);
  const tokenGone = await e1.evaluate(() => sessionStorage.getItem('attendance_jwt') === null);
  const parentStillIn = await e1.locator('#workspace').isVisible();
  const navStillWorks = !(await route(e1, 'leave'));
  probe('FE-07', 'Đăng xuất trong iframe chấm công xóa JWT dùng chung nhưng workspace vẫn hoạt động bằng token trong bộ nhớ', tokenGone && parentStillIn && navStillWorks,
    `sessionStorage trống=${tokenGone}, workspace còn hiển thị=${parentStillIn}, vẫn gọi API được=${navStillWorks}`, 'js/attendance-portal.js:156');
  await e1.reload();
  record('Sau khi tải lại, workspace yêu cầu đăng nhập lại', await e1.locator('#login').isVisible());

  // Responsive nhân viên 320px
  await emp.setViewportSize({width: 320, height: 700});
  const empOverflow = [];
  for (const id of empRoutes) { await route(emp, id); if (await overflow(emp)) empOverflow.push(id); }
  record('Không tràn ngang ở 320px (10 màn hình nhân viên)', empOverflow.length === 0, empOverflow.join(', '));
  await route(emp, 'requests');
  await shot(emp, '37-employee-requests-320');

  group = 'UI-QUALITY';
  record('Không có lỗi JavaScript runtime trên trang', evidence.runtimeErrors.length === 0, JSON.stringify(evidence.runtimeErrors).slice(0, 400));
  record('Không có phản hồi 5xx trong suốt phiên UI', evidence.serverErrors.length === 0, JSON.stringify(evidence.serverErrors).slice(0, 400));
} catch (error) {
  evidence.crash = String(error?.stack || error);
  console.error('CRASH ' + evidence.crash);
  exitCode = 1;
} finally {
  await browser.close();
  evidence.finishedAt = new Date().toISOString();
  evidence.summary = {total: evidence.cases.length, passed: evidence.cases.filter(c => c.pass).length, failed: evidence.cases.filter(c => !c.pass).length,
    probesConfirmed: evidence.probes.filter(p => p.status === 'CONFIRMED').length, probes: evidence.probes.length, screenshots: evidence.screenshots.length, crashed: Boolean(evidence.crash)};
  await writeFile('.local-test/qa/ui-evidence.json', JSON.stringify(evidence, null, 1));
  console.log(`\nUI: ${evidence.summary.total} ca, PASS ${evidence.summary.passed}, FAIL ${evidence.summary.failed}; probe xác nhận ${evidence.summary.probesConfirmed}/${evidence.summary.probes}; ${evidence.summary.screenshots} ảnh.`);
  if (evidence.summary.failed) exitCode = 1;
  process.exitCode = exitCode;
}
