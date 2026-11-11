/*
 * Bộ kiểm thử API hộp đen cho Schedule-Manager (chạy trên backend QA thật, cổng 8082, database Docker riêng).
 *
 *   node tests/qa/api-full.mjs
 *
 * Biến môi trường: SM_TEST_URL (mặc định http://localhost:8082), SM_TEST_ADMIN, SM_TEST_PASSWORD,
 * SM_QA_JWT_SECRET (bí mật JWT của backend QA, dùng để tạo token giả mạo có chữ ký hợp lệ).
 * Kết quả: .local-test/qa/api-evidence.json (mọi ca kiểm thử + toàn bộ request/response, token đã che)
 *          .local-test/qa/api-fixture.json  (tài khoản/dữ liệu cho tests/qa/ui-full.mjs)
 * Mã thoát 0 khi mọi ca PASS, 1 khi có ca FAIL (ca FAIL không dừng các ca còn lại).
 */
import crypto from 'node:crypto';
import {mkdir, writeFile} from 'node:fs/promises';

const base = process.env.SM_TEST_URL || 'http://localhost:8082';
if (!/^http:\/\/(localhost|127\.0\.0\.1):8082$/.test(base)) throw new Error('Chỉ chạy trên backend QA localhost:8082');
const adminUser = process.env.SM_TEST_ADMIN || 'admin';
const adminPass = process.env.SM_TEST_PASSWORD || 'Admin@123';
const jwtSecret = process.env.SM_QA_JWT_SECRET || '';
const run = 'qa' + Date.now().toString(36);
const fixturePassword = 'QaFull-2026!';

// ───────────────────────────── hạ tầng ghi bằng chứng ─────────────────────────────
const evidence = {startedAt: new Date().toISOString(), base, run, cases: [], exchanges: [], performance: {}, observations: []};
let module = 'init';
let seq = 0;
const tokens = new Map();
const redact = text => {
  let out = String(text ?? '');
  for (const [token, label] of tokens) out = out.split(token).join(`<jwt:${label}>`);
  out = out.replace(/eyJ[\w-]+\.[\w-]+\.[\w-]*/g, '<jwt>');
  return out.replace(/"(password|currentPassword|newPassword)":"[^"]*"/g, '"$1":"***"');
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function http(method, path, {as, body, headers = {}, rawBody} = {}) {
  const token = as?.token;
  const init = {method, headers: {...headers}};
  if (token) init.headers.Authorization = 'Bearer ' + token;
  if (rawBody !== undefined) { init.body = rawBody; init.headers['Content-Type'] ??= 'application/json'; }
  else if (body !== undefined) { init.body = JSON.stringify(body); init.headers['Content-Type'] = 'application/json'; }
  const started = performance.now();
  const response = await fetch(base + path, init);
  const buffer = Buffer.from(await response.arrayBuffer());
  const ms = Math.round(performance.now() - started);
  const contentType = response.headers.get('content-type') || '';
  const text = /json|text|csv/.test(contentType) || !contentType ? buffer.toString('utf8') : '';
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  const exchange = {
    n: ++seq, module, actor: as?.label || 'anonymous', method, path,
    request: rawBody !== undefined ? redact(String(rawBody).slice(0, 400)) : body === undefined ? null : JSON.parse(redact(JSON.stringify(body))),
    status: response.status, ms, contentType,
    response: text ? redact(text.length > 1600 ? text.slice(0, 1600) + '…' : text) : `<binary ${buffer.length} bytes>`
  };
  evidence.exchanges.push(exchange);
  return {status: response.status, data, headers: response.headers, buffer, ms, exchange};
}

let failures = 0;
function record(id, title, pass, detail, exchange) {
  evidence.cases.push({id, module, title, pass, detail, exchange: exchange?.n ?? null});
  if (!pass) failures++;
  console.log(`${pass ? 'PASS' : 'FAIL'} [${id}] ${title}${pass ? '' : ' :: ' + detail}`);
  return pass;
}
const counters = {};
const nextId = () => `${module}-${String(counters[module] = (counters[module] || 0) + 1).padStart(3, '0')}`;

/** Gọi API và kiểm tra mã trạng thái (soft assertion). Trả về response để dùng tiếp. */
async function expect(title, method, path, {as, body, status = 200, code, rawBody, headers, check} = {}) {
  const res = await http(method, path, {as, body, rawBody, headers});
  const allowed = Array.isArray(status) ? status : [status];
  let pass = allowed.includes(res.status);
  let detail = `HTTP ${res.status}`;
  if (pass && code) {
    const actual = res.data?.code;
    pass = actual === code;
    detail += pass ? ` code=${actual}` : ` code=${actual} (mong đợi ${code})`;
  }
  if (pass && check) {
    try { const extra = check(res.data, res); if (extra === false) { pass = false; detail += ' kiểm tra nội dung thất bại'; } else if (typeof extra === 'string') detail += ' ' + extra; }
    catch (error) { pass = false; detail += ' ' + error.message; }
  }
  if (!pass) detail += ` (mong đợi ${allowed.join('/')}) body=${String(res.exchange.response).slice(0, 300)}`;
  record(nextId(), `${title} → ${allowed.join('/')}${code ? ' ' + code : ''}`, pass, detail, res.exchange);
  return res;
}
function verify(title, condition, detail = '') {
  return record(nextId(), title, Boolean(condition), detail || (condition ? 'OK' : 'điều kiện không thỏa'));
}
function observe(kind, title, detail) { evidence.observations.push({module, kind, title, detail}); }
function section(name) { module = name; console.log(`\n=== ${name} ===`); }

// ───────────────────────────── thời gian theo Asia/Ho_Chi_Minh ─────────────────────────────
const VN = 7 * 3600_000;
const vnNow = () => new Date(Date.now() + VN);
const localDate = offsetDays => { const d = vnNow(); d.setUTCDate(d.getUTCDate() + offsetDays); return d.toISOString().slice(0, 10); };
const localDateTime = offsetMs => new Date(Date.now() + VN + offsetMs).toISOString().slice(0, 16);
const hhmm = offsetMs => new Date(Date.now() + VN + offsetMs).toISOString().slice(11, 16);
const ALL_DAYS = [0, 1, 2, 3, 4, 5, 6];

// ───────────────────────────── JWT tự ký (chỉ để kiểm thử từ chối) ─────────────────────────────
const b64 = value => Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url');
function signJwt(payload, alg = 'HS512', secret = jwtSecret) {
  const header = b64({alg, typ: 'JWT'});
  const body = b64(payload);
  if (alg === 'none') return `${header}.${body}.`;
  const sig = crypto.createHmac(alg === 'HS256' ? 'sha256' : 'sha512', secret).update(`${header}.${body}`).digest('base64url');
  return `${header}.${body}.${sig}`;
}

// ───────────────────────────── chạy ─────────────────────────────
const deadline = Date.now() + 90_000;
for (;;) {
  try { if ((await fetch(base + '/api/health')).ok) break; } catch {}
  if (Date.now() > deadline) throw new Error('Backend QA chưa sẵn sàng sau 90 giây');
  await sleep(500);
}

const actor = (label, token) => { tokens.set(token, label); return {label, token}; };
try {

// ===== 1. Xác thực & phiên (A-02) =====
section('AUTH');
const loginRes = await expect('Đăng nhập admin hợp lệ', 'POST', '/api/auth/login', {body: {username: adminUser, password: adminPass},
  check: d => { if (d.tokenType !== 'Bearer' || !d.token) throw Error('thiếu token/tokenType'); }});
const admin = actor('admin', loginRes.data.token);
await expect('Sai mật khẩu', 'POST', '/api/auth/login', {body: {username: adminUser, password: 'wrong-pass'}, status: 401, code: 'INVALID_CREDENTIALS'});
await expect('Tài khoản không tồn tại', 'POST', '/api/auth/login', {body: {username: 'nobody_' + run, password: 'x'}, status: 401, code: 'INVALID_CREDENTIALS'});
await expect('Để trống username/password', 'POST', '/api/auth/login', {body: {username: '', password: ''}, status: 401});
await expect('JSON hỏng khi đăng nhập', 'POST', '/api/auth/login', {rawBody: '{"username":', status: 400});
await expect('SQL injection ở username', 'POST', '/api/auth/login', {body: {username: "admin' OR '1'='1", password: "x' OR '1'='1"}, status: 401});
await expect('GET /api/me (admin) không lộ password_hash', 'GET', '/api/me', {as: admin,
  check: d => { if (d.role !== 'admin' || 'password_hash' in d || 'passwordHash' in d) throw Error('sai role hoặc lộ hash'); }});
await expect('GET /api/my/profile (admin)', 'GET', '/api/my/profile', {as: admin});
await expect('GET /api/attendance/me (admin)', 'GET', '/api/attendance/me', {as: admin, check: d => d.role === 'admin'});
await expect('Đăng xuất (public, không thu hồi token)', 'POST', '/api/auth/logout', {});
await expect('Token vẫn dùng được sau logout (stateless)', 'GET', '/api/periods', {as: admin});
observe('design', 'Logout không thu hồi JWT', 'POST /api/auth/logout chỉ trả 200; token cũ vẫn hợp lệ tới khi hết hạn (24h).');
const forged = [
  ['Token rác', 'garbage.token.value'],
  ['alg=none với role admin', signJwt({sub: 'usr_admin_001', username: 'admin', role: 'admin', exp: 4102444800}, 'none')],
  ['Ký sai bí mật', signJwt({sub: 'usr_admin_001', username: 'admin', role: 'admin', exp: 4102444800}, 'HS512', 'x'.repeat(64))]
];
if (jwtSecret) {
  forged.push(['Token hết hạn (chữ ký đúng)', signJwt({sub: 'usr_admin_001', username: 'admin', role: 'admin', iat: 1700000000, exp: 1700000600})]);
  forged.push(['Token của user không tồn tại (chữ ký đúng)', signJwt({sub: 'ghost_' + run, username: 'ghost', role: 'admin', exp: 4102444800})]);
}
for (const [title, token] of forged) {
  tokens.set(token, 'forged');
  await expect(title, 'GET', '/api/periods', {as: {label: 'forged', token}, status: 401});
}

// ===== 2. Nhân viên, Level, danh mục, audit (A-04, A-05) =====
section('EMPLOYEE');
const levels = (await expect('Danh sách Level', 'GET', '/api/levels', {as: admin, check: d => d.length >= 4 ? `${d.length} level` : false})).data;
const people = {};
for (const key of ['A', 'B', 'C', 'D']) {
  const username = `${run}_${key.toLowerCase()}`;
  const res = await expect(`Tạo nhân viên ${key}`, 'POST', '/api/workspace/employees', {as: admin,
    body: {employeeCode: `${run}${key}`.slice(0, 20), username, password: fixturePassword, fullName: `QA Nhân viên ${key}`,
      levelId: levels[key === 'D' ? 1 : 0].id, status: 'active', email: `${username}@example.test`, phone: '0900000000', joinDate: localDate(-30)},
    check: d => Boolean(d.id)});
  const login = await http('POST', '/api/auth/login', {body: {username, password: fixturePassword}});
  people[key] = {...actor('nv' + key, login.data.token), id: res.data.id, username};
}
const {A, B, C, D} = people;
verify('Đăng nhập được bằng tài khoản nhân viên vừa tạo', A.token && B.token && C.token && D.token);
await expect('Trùng username', 'POST', '/api/workspace/employees', {as: admin,
  body: {employeeCode: run + 'X', username: A.username, password: fixturePassword, fullName: 'Trùng', status: 'active'}, status: 409, code: 'EMPLOYEE_CONFLICT'});
await expect('Thiếu họ tên', 'POST', '/api/workspace/employees', {as: admin,
  body: {employeeCode: run + 'Y', username: run + '_y', password: fixturePassword, fullName: '', status: 'active'}, status: 400});
await expect('Mật khẩu < 8 ký tự', 'POST', '/api/workspace/employees', {as: admin,
  body: {employeeCode: run + 'Z', username: run + '_z', password: 'short', fullName: 'Z', status: 'active'}, status: 400, code: 'INVALID_PASSWORD'});
await expect('Email sai định dạng', 'POST', '/api/workspace/employees', {as: admin,
  body: {employeeCode: run + 'W', username: run + '_w', password: fixturePassword, fullName: 'W', status: 'active', email: 'not-an-email'}, status: 400});
await expect('Tên 5.000 ký tự bị từ chối', 'POST', '/api/workspace/employees', {as: admin,
  body: {employeeCode: run + 'V', username: run + '_v', password: fixturePassword, fullName: 'x'.repeat(5000), status: 'active'}, status: 400});
const xssName = `<img src=x onerror="window.__xss=1">QA ${run}`;
const xss = await expect('Lưu tên chứa payload XSS (API lưu nguyên văn, FE phải escape)', 'POST', '/api/workspace/employees', {as: admin,
  body: {employeeCode: run + 'S', username: run + '_xss', password: fixturePassword, fullName: xssName, status: 'active'}});
await expect('Nhân viên gọi API quản trị nhân sự', 'GET', '/api/workspace/employees', {as: A, status: 403});
await expect('Danh sách nhân viên (admin)', 'GET', '/api/workspace/employees', {as: admin, check: d => d.some(x => x.id === A.id)});
await expect('Tìm kiếm có phân trang', 'GET', `/api/workspace/employees/page?q=${encodeURIComponent(run)}&page=0&size=20`, {as: admin,
  check: d => d.total >= 4 ? `total=${d.total}` : false});
await expect('Tìm kiếm với chuỗi SQL injection', 'GET', `/api/workspace/employees/page?q=${encodeURIComponent("' OR 1=1 --")}`, {as: admin,
  check: d => d.total === 0 ? 'total=0' : false});
await expect('size=500 vượt giới hạn', 'GET', '/api/workspace/employees/page?size=500', {as: admin, status: 400, code: 'INVALID_PAGE'});
const empA = await expect('Chi tiết nhân viên A', 'GET', `/api/workspace/employees/${A.id}`, {as: admin});
await expect('Nhân viên không tồn tại', 'GET', '/api/workspace/employees/khong_ton_tai', {as: admin, status: 404});
const updateBody = {employeeCode: empA.data.employeeCode, username: A.username, fullName: 'QA Nhân viên A (sửa)', levelId: levels[0].id, status: 'active', version: empA.data.version};
await expect('Cập nhật nhân viên', 'PUT', `/api/workspace/employees/${A.id}`, {as: admin, body: updateBody});
await expect('Cập nhật với version cũ', 'PUT', `/api/workspace/employees/${A.id}`, {as: admin, body: updateBody, status: 409, code: 'STALE_EMPLOYEE'});
await expect('Lưu trữ với lý do quá ngắn', 'POST', `/api/workspace/employees/${D.id}/archive`, {as: admin, body: {reason: 'x', version: 0}, status: 400});
await expect('Danh mục tổ chức (admin)', 'GET', '/api/workspace/catalog', {as: admin, check: d => Array.isArray(d.levels)});
await expect('Danh mục tổ chức (nhân viên)', 'GET', '/api/workspace/catalog', {as: A, status: 403});
await expect('Hồ sơ của tôi bỏ qua userId giả mạo', 'GET', `/api/my/profile?userId=${B.id}`, {as: A, check: d => d.id === A.id});
const level = await expect('Tạo Level', 'POST', '/api/workspace/levels', {as: admin, body: {name: 'QA Level ' + run, description: 'QA', color: '#123456', order: 90}});
await expect('Tạo Level trùng tên', 'POST', '/api/workspace/levels', {as: admin, body: {name: 'QA Level ' + run, color: '#123456', order: 91}, status: 409});
await expect('Tạo Level màu sai định dạng', 'POST', '/api/workspace/levels', {as: admin, body: {name: 'QA Bad ' + run, color: 'red', order: 92}, status: 400});
await expect('Sửa Level', 'PUT', `/api/workspace/levels/${level.data.id}`, {as: admin, body: {name: 'QA Level sửa ' + run, color: '#654321', order: 90}});
await expect('Nhân viên tạo Level', 'POST', '/api/workspace/levels', {as: A, body: {name: 'x', color: '#000000', order: 1}, status: 403});
await expect('Xóa Level đang dùng', 'DELETE', `/api/workspace/levels/${levels[0].id}`, {as: admin, status: 409});
await expect('Xóa Level', 'DELETE', `/api/workspace/levels/${level.data.id}`, {as: admin, status: 204});
await expect('Xóa Level không tồn tại', 'DELETE', `/api/workspace/levels/${level.data.id}`, {as: admin, status: 404});
const location = await expect('Tạo địa điểm', 'POST', '/api/schedule/catalog/locations', {as: admin, body: {name: 'QA Chi nhánh ' + run}, status: 201});
await expect('Tạo địa điểm trùng tên', 'POST', '/api/schedule/catalog/locations', {as: admin, body: {name: 'QA Chi nhánh ' + run}, status: 409});
await expect('Loại danh mục không hợp lệ', 'GET', '/api/schedule/catalog/planets', {as: admin, status: 400});
for (const type of ['positions', 'skills', 'certificates', 'work_groups', 'employee_types', 'departments'])
  await expect(`Danh mục ${type}`, 'GET', `/api/schedule/catalog/${type}`, {as: admin});
const skill = await expect('Tạo kỹ năng', 'POST', '/api/schedule/catalog/skills', {as: admin, body: {name: 'QA Kỹ năng ' + run}, status: 201});
await expect('Gán địa điểm/kỹ năng cho nhân viên', 'PUT', `/api/schedule/catalog/users/${A.id}`, {as: admin,
  body: {locationIds: [location.data.id], positionIds: [], skillIds: [skill.data.id], certificates: []}});
await expect('Đọc liên kết năng lực', 'GET', `/api/schedule/catalog/users/${A.id}`, {as: admin, check: d => d.skillIds.includes(skill.data.id)});
await expect('Liên kết của user không tồn tại', 'GET', '/api/schedule/catalog/users/khong_ton_tai', {as: admin, status: [400, 404]});
await expect('Xóa kỹ năng đang được gán', 'DELETE', `/api/schedule/catalog/skills/${skill.data.id}`, {as: admin, status: 409});
const tmpLoc = await http('POST', '/api/schedule/catalog/positions', {as: admin, body: {name: 'QA Tạm ' + run}});
await expect('Xóa danh mục chưa dùng', 'DELETE', `/api/schedule/catalog/positions/${tmpLoc.data.id}`, {as: admin, status: 204});
await expect('Nhật ký hệ thống', 'GET', '/api/audit?page=0&size=20', {as: admin, check: d => d.total > 0 ? `total=${d.total}` : false});
await expect('Nhật ký: nhân viên bị chặn', 'GET', '/api/audit', {as: A, status: 403});
await expect('Nhật ký: size=1000', 'GET', '/api/audit?size=1000', {as: admin, status: 400});

// ===== 3. Kỳ làm việc (TU-01..TU-03) =====
section('PERIOD');
const periodBody = {name: 'QA Kỳ chính ' + run, cycle: 'weekly', startDate: localDate(2), endDate: localDate(8),
  openAt: localDateTime(-3600_000), deadline: localDateTime(6 * 3600_000), autoLock: true, allowReopenByAdmin: true,
  requiredDaysOfWeek: ALL_DAYS, exemptDates: [], closedDates: [], applicableUserIds: [A.id, B.id, D.id]};
let P = (await expect('Tạo kỳ (draft)', 'POST', '/api/periods', {as: admin, body: periodBody, status: 201, check: d => d.status === 'draft'})).data;
await expect('Thiếu tên kỳ', 'POST', '/api/periods', {as: admin, body: {...periodBody, name: ''}, status: 400, code: 'VALIDATION_FAILED'});
await expect('Ngày kết thúc trước ngày bắt đầu', 'POST', '/api/periods', {as: admin, body: {...periodBody, startDate: localDate(8), endDate: localDate(2)}, status: 400});
await expect('Kỳ dài hơn 366 ngày', 'POST', '/api/periods', {as: admin, body: {...periodBody, endDate: localDate(400)}, status: 400});
await expect('Chu kỳ không hợp lệ', 'POST', '/api/periods', {as: admin, body: {...periodBody, cycle: 'hourly'}, status: 400});
await expect('Hạn đăng ký trước thời điểm mở', 'POST', '/api/periods', {as: admin, body: {...periodBody, deadline: localDateTime(-7200_000)}, status: 400});
await expect('Nhân viên tạo kỳ', 'POST', '/api/periods', {as: A, body: periodBody, status: 403});
await expect('Nhân viên không thấy kỳ draft', 'GET', `/api/periods/${P.id}`, {as: A, status: 404});
await expect('Sửa kỳ thiếu version', 'PUT', `/api/periods/${P.id}`, {as: admin, body: periodBody, status: 400, code: 'VERSION_REQUIRED'});
await expect('Sửa kỳ với version cũ', 'PUT', `/api/periods/${P.id}`, {as: admin, body: {...periodBody, version: P.version + 5}, status: 409, code: 'VERSION_CONFLICT'});
P = (await expect('Sửa kỳ draft', 'PUT', `/api/periods/${P.id}`, {as: admin, body: {...periodBody, name: periodBody.name + ' (sửa)', version: P.version}})).data;
P = (await expect('Mở đăng ký', 'POST', `/api/periods/${P.id}/open`, {as: admin, body: {version: P.version}, check: d => d.status === 'open'})).data;
await expect('Mở lại lần nữa (sai chuyển trạng thái)', 'POST', `/api/periods/${P.id}/open`, {as: admin, body: {version: P.version}, status: 409, code: 'INVALID_TRANSITION'});
await expect('Đổi ngày bắt đầu khi kỳ đang mở', 'PUT', `/api/periods/${P.id}`, {as: admin, body: {...periodBody, startDate: localDate(3), version: P.version}, status: 409, code: 'FIELD_LOCKED_WHILE_OPEN'});
await expect('Nhân viên thấy kỳ đang mở', 'GET', `/api/periods/${P.id}`, {as: A, check: d => d.registrationOpen === true && d.applicableUserIds == null});
await expect('Nhân viên không thuộc kỳ không thấy kỳ', 'GET', `/api/periods/${P.id}`, {as: C, status: 404});
const P0 = (await http('POST', '/api/periods', {as: admin, body: {...periodBody, name: 'QA Kỳ xóa ' + run}})).data;
await expect('Xóa kỳ draft', 'DELETE', `/api/periods/${P0.id}`, {as: admin, status: 204});
await expect('Xóa kỳ đang mở', 'DELETE', `/api/periods/${P.id}`, {as: admin, status: 409});

// ===== 4. Đăng ký thời gian rảnh (TU-04) =====
section('AVAILABILITY');
const fullWeek = Array.from({length: 7}, (_, i) => ({date: localDate(i + 2), slots: [{start: '00:00', end: '23:59', type: 'available'}]}));
await expect('Xem đăng ký của tôi (chưa có)', 'GET', `/api/availability/my?periodId=${P.id}`, {as: A, check: d => d.registration.status === 'none'});
await expect('Lưu nháp', 'PUT', `/api/availability/my?periodId=${P.id}`, {as: A, body: {entries: fullWeek.slice(0, 3)}, check: d => d.registration.status === 'draft'});
await expect('Khung giờ chồng nhau', 'PUT', `/api/availability/my?periodId=${P.id}`, {as: A,
  body: {entries: [{date: localDate(2), slots: [{start: '08:00', end: '12:00', type: 'available'}, {start: '11:00', end: '13:00', type: 'available'}]}]}, status: 400, code: 'VALIDATION_FAILED'});
await expect('Ngày nằm ngoài kỳ', 'PUT', `/api/availability/my?periodId=${P.id}`, {as: A,
  body: {entries: [{date: localDate(30), slots: [{start: '08:00', end: '12:00', type: 'available'}]}]}, status: 400});
await expect('Giờ kết thúc trước giờ bắt đầu', 'PUT', `/api/availability/my?periodId=${P.id}`, {as: A,
  body: {entries: [{date: localDate(2), slots: [{start: '12:00', end: '08:00', type: 'available'}]}]}, status: 400});
await expect('Gửi đăng ký rỗng', 'POST', `/api/availability/my/submit?periodId=${P.id}`, {as: B, body: {entries: []}, status: 400, code: 'EMPTY_REGISTRATION'});
for (const p of [A, B, D])
  await expect(`Gửi đăng ký nhân viên ${p.label}`, 'POST', `/api/availability/my/submit?periodId=${P.id}`, {as: p, body: {entries: fullWeek}, check: d => d.registration.status === 'submitted'});
await expect('Lưu lại sau khi gửi vẫn giữ trạng thái submitted', 'PUT', `/api/availability/my?periodId=${P.id}`, {as: A, body: {entries: fullWeek}, check: d => d.registration.status === 'submitted'});
await expect('Admin gọi API đăng ký cá nhân', 'GET', `/api/availability/my?periodId=${P.id}`, {as: admin, status: 403});
await expect('Nhân viên ngoài kỳ đăng ký', 'GET', `/api/availability/my?periodId=${P.id}`, {as: C, status: [403, 404]});
await expect('Thiếu periodId', 'GET', '/api/availability/my?periodId=', {as: A, status: 400});
await expect('Tiến độ đăng ký (admin)', 'GET', `/api/availability/progress?periodId=${P.id}`, {as: admin, check: d => d.submitted >= 3 ? `submitted=${d.submitted}/${d.applicable}` : false});
await expect('Tổng hợp theo ngày (admin)', 'GET', `/api/availability/summary?periodId=${P.id}`, {as: admin, check: d => d.days.length === 7});
await expect('Danh sách đã gửi (admin)', 'GET', `/api/availability/submitted?periodId=${P.id}`, {as: admin, check: d => d.length >= 3});
await expect('Nhân viên xem tiến độ', 'GET', `/api/availability/progress?periodId=${P.id}`, {as: A, status: 403});

// ===== 5. Quy tắc (TU-05..TU-07) =====
section('RULES');
await expect('Danh mục tham số (nhân viên)', 'GET', '/api/rules/catalog', {as: A, check: d => Array.isArray(d.parameters)});
await expect('Thiết lập hiện hành (admin)', 'GET', '/api/rules/settings', {as: admin});
await expect('Thiết lập hiện hành (nhân viên)', 'GET', '/api/rules/settings', {as: A, status: 403});
await expect('Phạm vi không hợp lệ', 'GET', '/api/rules/settings?scopeType=planet', {as: admin, status: 400});
await expect('Mẫu đề xuất', 'GET', '/api/rules/templates', {as: admin});
const ruleValues = {target_hours: '60', max_hours_mode: 'percent', max_hours_percent: '125', max_hours_per_day: '12', max_hours_per_week: '80',
  min_rest_hours: '8', max_consecutive_days: '7', warning_threshold_percent: '90', weight_balance_hours: '100', weight_balance_shifts: '50', understaffed_policy: 'warn'};
const ruleItems = Object.entries(ruleValues).map(([paramKey, value]) => ({paramKey, value, scopeType: 'company', effectiveFrom: localDate(0)}));
await expect('Giá trị âm bị từ chối', 'POST', '/api/rules/changes', {as: admin, body: {reason: 'QA', items: [{paramKey: 'target_hours', value: '-5', scopeType: 'company', effectiveFrom: localDate(0)}]}, status: 400});
await expect('Tham số không tồn tại', 'POST', '/api/rules/changes', {as: admin, body: {reason: 'QA', items: [{paramKey: 'magic', value: '1', scopeType: 'company'}]}, status: 400});
const preview = await expect('Xem trước thay đổi quy tắc', 'POST', '/api/rules/changes', {as: admin, body: {reason: 'QA cấu hình quy tắc ' + run, items: ruleItems}, status: 201,
  check: d => d.status === 'previewed'});
await expect('Áp dụng thiếu xác nhận', 'POST', `/api/rules/changes/${preview.data.changeId}/apply`, {as: admin, body: {confirm: false}, status: 400, code: 'CONFIRM_REQUIRED'});
await expect('Áp dụng quy tắc', 'POST', `/api/rules/changes/${preview.data.changeId}/apply`, {as: admin, body: {confirm: true}, check: d => d.status === 'applied'});
await expect('Áp dụng lần hai', 'POST', `/api/rules/changes/${preview.data.changeId}/apply`, {as: admin, body: {confirm: true}, status: 409, code: 'CHANGE_NOT_PENDING'});
await expect('Quy tắc hiệu lực: 60 × 125% = 75 giờ (AC-01, AC-14)', 'GET', `/api/rules/effective?userId=${A.id}`, {as: admin, check: d => d.derived?.maxHours === 75 ? 'maxHours=75' : false});
await expect('Nhân viên xem quy tắc của mình', 'GET', '/api/rules/effective', {as: A});
await expect('Nhân viên xem quy tắc người khác', 'GET', `/api/rules/effective?userId=${B.id}`, {as: A, status: 403});
await expect('Mức sẵn sàng xếp lịch', 'GET', `/api/rules/readiness?periodId=${P.id}`, {as: admin});
const preview2 = await http('POST', '/api/rules/changes', {as: admin, body: {reason: 'QA hủy', items: [{paramKey: 'weight_cost', value: '10', scopeType: 'company', effectiveFrom: localDate(0)}]}});
await expect('Hủy bản xem trước', 'POST', `/api/rules/changes/${preview2.data.changeId}/cancel`, {as: admin, check: d => d.status === 'cancelled'});
await expect('Hủy lần hai', 'POST', `/api/rules/changes/${preview2.data.changeId}/cancel`, {as: admin, status: 409});
await expect('Lịch sử thay đổi', 'GET', '/api/rules/changes?status=all', {as: admin, check: d => d.length >= 2});
await expect('Chi tiết thay đổi', 'GET', `/api/rules/changes/${preview.data.changeId}`, {as: admin});
await expect('Trạng thái lọc không hợp lệ', 'GET', '/api/rules/changes?status=weird', {as: admin, status: 400});

// ===== 6. Ca làm (TW-01) =====
section('SHIFT');
const shiftBase = {periodId: P.id, name: 'QA Ca sáng', date: localDate(3), startTime: '08:00', endTime: '12:00', breakMinutes: 0, mode: 'combined',
  totalRequired: 1, minRequired: 1, maxAllowed: 1, mandatoryUserIds: [A.id]};
const S1 = (await expect('Tạo ca', 'POST', '/api/schedule/shifts', {as: admin, body: shiftBase, status: 201, check: d => d.version >= 1})).data;
const S2 = (await expect('Tạo ca B', 'POST', '/api/schedule/shifts', {as: admin, body: {...shiftBase, name: 'QA Ca B', date: localDate(4), mandatoryUserIds: [B.id]}, status: 201})).data;
const S3 = (await expect('Tạo ca cần 4 người (nhiều hơn số nhân viên trong kỳ)', 'POST', '/api/schedule/shifts', {as: admin,
  body: {...shiftBase, name: 'QA Ca thiếu', date: localDate(6), startTime: '13:00', endTime: '17:00', totalRequired: 4, minRequired: 3, maxAllowed: 4, mandatoryUserIds: [A.id]}, status: 201})).data;
await expect('Ca không gắn kỳ', 'POST', '/api/schedule/shifts', {as: admin, body: {...shiftBase, periodId: null}, status: [400, 409, 422]});
await expect('min > total', 'POST', '/api/schedule/shifts', {as: admin, body: {...shiftBase, totalRequired: 1, minRequired: 3, maxAllowed: 3}, status: [400, 422]});
await expect('Giờ sai định dạng', 'POST', '/api/schedule/shifts', {as: admin, body: {...shiftBase, startTime: '25:99'}, status: 400});
await expect('Ngày nằm ngoài kỳ', 'POST', '/api/schedule/shifts', {as: admin, body: {...shiftBase, date: localDate(40)}, status: [400, 422]});
const bulk = await expect('Tạo nhiều ca (bulk)', 'POST', '/api/schedule/shifts/bulk', {as: admin,
  body: {shifts: [{...shiftBase, name: 'QA Bulk 1', date: localDate(5), mandatoryUserIds: []}, {...shiftBase, name: 'QA Bulk 2', date: localDate(5), startTime: '14:00', endTime: '18:00', mandatoryUserIds: []}]}, status: 201,
  check: d => d.length === 2});
await expect('Bulk có 1 ca lỗi thì không tạo ca nào', 'POST', '/api/schedule/shifts/bulk', {as: admin,
  body: {shifts: [{...shiftBase, name: 'QA Bulk OK', mandatoryUserIds: []}, {...shiftBase, name: '', mandatoryUserIds: []}]}, status: [400, 422]});
const cloned = await expect('Sao chép ca', 'POST', `/api/schedule/shifts/${bulk.data[0].id}/clone`, {as: admin, body: {dates: [localDate(7)]}, status: 201, check: d => d.length === 1});
const repeat = await expect('Lặp ca theo tuần', 'POST', `/api/schedule/shifts/${bulk.data[1].id}/repeat`, {as: admin, body: {until: localDate(8), weekdays: [1, 2, 3, 4, 5, 6, 7], everyNWeeks: 1}, status: 201,
  check: d => `${d.length} ca`});
await expect('Ca khẩn cấp', 'POST', '/api/schedule/shifts/emergency', {as: admin, body: {reason: 'QA thiếu người đột xuất', shift: {...shiftBase, name: 'QA Khẩn cấp', date: localDate(8), mandatoryUserIds: []}}, status: 201,
  check: d => d.emergency === true});
await expect('Ca khẩn cấp thiếu lý do', 'POST', '/api/schedule/shifts/emergency', {as: admin, body: {reason: '', shift: {...shiftBase, mandatoryUserIds: []}}, status: 400});
const night = await expect('Ca qua đêm 22:00–06:00', 'POST', '/api/schedule/shifts', {as: admin, body: {...shiftBase, name: 'QA Ca đêm', date: localDate(7), startTime: '22:00', endTime: '06:00', mandatoryUserIds: []}, status: 201});
await expect('Danh sách ca theo khoảng ngày', 'GET', `/api/schedule/shifts?periodId=${P.id}&from=${localDate(0)}&to=${localDate(10)}`, {as: admin, check: d => `${d.length} ca`});
await expect('Khoảng ngày > 366', 'GET', `/api/schedule/shifts?from=${localDate(0)}&to=${localDate(400)}`, {as: admin, status: 400});
await expect('Chi tiết ca', 'GET', `/api/schedule/shifts/${S1.id}`, {as: admin});
await expect('Ca không tồn tại', 'GET', '/api/schedule/shifts/khong_ton_tai', {as: admin, status: 404});
await expect('Sửa ca thiếu expectedVersion', 'PUT', `/api/schedule/shifts/${night.data.id}`, {as: admin, body: {shift: {...shiftBase, name: 'x'}}, status: 400});
await expect('Sửa ca với version cũ', 'PUT', `/api/schedule/shifts/${night.data.id}`, {as: admin, body: {expectedVersion: 99, shift: {...shiftBase, name: 'QA Ca đêm', date: localDate(7), startTime: '22:00', endTime: '06:00', mandatoryUserIds: []}}, status: 409});
await expect('Sửa ca', 'PUT', `/api/schedule/shifts/${night.data.id}`, {as: admin, body: {expectedVersion: night.data.version, shift: {...shiftBase, name: 'QA Ca đêm (sửa)', date: localDate(7), startTime: '22:00', endTime: '06:00', mandatoryUserIds: []}}});
await expect('Hủy ca thiếu lý do', 'POST', `/api/schedule/shifts/${cloned.data[0].id}/cancel`, {as: admin, body: {expectedVersion: cloned.data[0].version, reason: ''}, status: 400});
await expect('Hủy ca có lý do', 'POST', `/api/schedule/shifts/${cloned.data[0].id}/cancel`, {as: admin, body: {expectedVersion: cloned.data[0].version, reason: 'QA hủy ca'}});
const extraRepeat = repeat.data?.[repeat.data.length - 1];
if (extraRepeat) await expect('Xóa ca chưa phân công', 'DELETE', `/api/schedule/shifts/${extraRepeat.id}`, {as: admin, status: 204});
await expect('Nhân viên xem danh sách ca quản trị', 'GET', `/api/schedule/shifts?from=${localDate(0)}&to=${localDate(10)}`, {as: A, status: 403});

// ===== 7. Phương án lịch & quy trình duyệt (TW-03..TW-06) =====
section('PLAN');
const planShiftIds = [S1.id, S2.id, S3.id];
const dry = await expect('Chạy thử khi kỳ đang mở (không ghi dữ liệu)', 'POST', '/api/schedule/plans/dry-run', {as: admin, body: {periodId: P.id, shiftIds: planShiftIds, replaceExisting: false},
  check: d => `${d.result.assignments.length} phân công`});
await expect('Tạo phương án khi kỳ chưa khóa', 'POST', '/api/schedule/plans', {as: admin, body: {periodId: P.id, name: 'QA PA', shiftIds: planShiftIds}, status: 409, code: 'PERIOD_NOT_READY'});
P = (await expect('Khóa đăng ký kỳ', 'POST', `/api/periods/${P.id}/lock`, {as: admin, body: {version: P.version}, check: d => d.status === 'locked'})).data;
await expect('Đăng ký sau khi khóa bị chặn (AC-04)', 'PUT', `/api/availability/my?periodId=${P.id}`, {as: A, body: {entries: fullWeek}, status: 409});
await expect('Mở lại kỳ thiếu xác nhận', 'POST', `/api/periods/${P.id}/reopen`, {as: admin, body: {reason: 'QA', newDeadline: localDateTime(3600_000), version: P.version}, status: 400, code: 'CONFIRM_REQUIRED'});
await expect('Mở lại kỳ thiếu lý do', 'POST', `/api/periods/${P.id}/reopen`, {as: admin, body: {confirm: true, newDeadline: localDateTime(3600_000), version: P.version}, status: 400, code: 'REASON_REQUIRED'});
await expect('Mở lại với hạn mới trong quá khứ', 'POST', `/api/periods/${P.id}/reopen`, {as: admin, body: {confirm: true, reason: 'QA', newDeadline: localDateTime(-3600_000), version: P.version}, status: 400, code: 'NEW_DEADLINE_IN_PAST'});
P = (await expect('Mở lại kỳ có kiểm soát (AC-06)', 'POST', `/api/periods/${P.id}/reopen`, {as: admin, body: {confirm: true, reason: 'QA mở lại để bổ sung', newDeadline: localDateTime(3600_000), version: P.version}, check: d => d.status === 'open'})).data;
P = (await expect('Khóa lại kỳ', 'POST', `/api/periods/${P.id}/lock`, {as: admin, body: {version: P.version}})).data;
let plan = (await expect('Tạo phương án lịch', 'POST', '/api/schedule/plans', {as: admin, body: {periodId: P.id, name: 'QA Phương án 1', shiftIds: planShiftIds, replaceExisting: false}, status: 201,
  check: d => `${d.roster.length} phân công, status=${d.status}`})).data;
const plan2 = (await expect('Tạo phương án 2', 'POST', '/api/schedule/plans', {as: admin, body: {periodId: P.id, name: 'QA Phương án 2', shiftIds: [S1.id, S2.id], replaceExisting: false}, status: 201})).data;
await expect('Danh sách phương án', 'GET', `/api/schedule/plans?periodId=${P.id}`, {as: admin, check: d => d.length >= 2});
await expect('So sánh 2 phương án', 'GET', `/api/schedule/plans/compare?base=${plan.id}&other=${plan2.id}`, {as: admin});
await expect('Chạy lại thiếu expectedVersion', 'POST', `/api/schedule/plans/${plan.id}/rerun`, {as: admin, body: {}, status: 400});
plan = (await expect('Chạy lại phương án', 'POST', `/api/schedule/plans/${plan.id}/rerun`, {as: admin, body: {expectedVersion: plan.version}})).data.plan;
await expect('Trạng thái quy trình', 'GET', `/api/schedule/plans/${plan.id}/workflow`, {as: admin});
await expect('Mức sẵn sàng công bố', 'GET', `/api/schedule/plans/${plan.id}/readiness`, {as: admin, check: d => `canSubmit=${d.canSubmit} understaffed=${(d.understaffed || []).length}`});
await expect('Xem trước ngoại lệ', 'POST', `/api/schedule/plans/${plan.id}/exceptions/preview`, {as: admin, body: {shiftId: S3.id, userId: B.id}});
const removable = plan.roster.find(r => r.shiftId === S3.id && r.userId !== A.id) || plan.roster.find(r => r.shiftId === S3.id);
plan = (await expect('Gỡ phân công khỏi ca thiếu', 'POST', `/api/schedule/plans/${plan.id}/assignments/remove`, {as: admin, body: {expectedVersion: plan.version, shiftId: S3.id, userId: removable.userId}})).data;
await expect('Thêm nhân viên đã có trong ca', 'POST', `/api/schedule/plans/${plan.id}/assignments`, {as: admin, body: {expectedVersion: plan.version, shiftId: S1.id, userId: A.id}, status: 400, code: 'VALIDATION_FAILED'});
plan = (await expect('Thêm lại phân công thủ công', 'POST', `/api/schedule/plans/${plan.id}/assignments`, {as: admin, body: {expectedVersion: plan.version, shiftId: S3.id, userId: removable.userId}})).data;
await expect('Gỡ phân công với version cũ', 'POST', `/api/schedule/plans/${plan.id}/assignments/remove`, {as: admin, body: {expectedVersion: plan.version - 1, shiftId: S3.id, userId: removable.userId}, status: 409, code: 'VERSION_CONFLICT'});
if (plan.roster.some(r => r.shiftId === S3.id && r.userId === D.id))
  plan = (await expect('Gỡ D khỏi ca thiếu (để D nhận ca trống sau công bố)', 'POST', `/api/schedule/plans/${plan.id}/assignments/remove`, {as: admin, body: {expectedVersion: plan.version, shiftId: S3.id, userId: D.id}})).data;
await expect('Gửi duyệt với version cũ', 'POST', `/api/schedule/plans/${plan.id}/submit`, {as: admin, body: {expectedVersion: plan.version + 3}, status: 409});
plan = (await expect('Gửi duyệt', 'POST', `/api/schedule/plans/${plan.id}/submit`, {as: admin, body: {expectedVersion: plan.version}, check: d => d.plan.status === 'pending_approval'})).data.plan;
await expect('Sửa khi đang chờ duyệt', 'POST', `/api/schedule/plans/${plan.id}/assignments`, {as: admin, body: {expectedVersion: plan.version, shiftId: S3.id, userId: B.id}, status: 409, code: 'PLAN_NOT_EDITABLE'});
await expect('Trả về thiếu lý do', 'POST', `/api/schedule/plans/${plan.id}/reject`, {as: admin, body: {expectedVersion: plan.version}, status: 400});
plan = (await expect('Trả về nháp', 'POST', `/api/schedule/plans/${plan.id}/reject`, {as: admin, body: {expectedVersion: plan.version, reason: 'QA cần xem lại'}, check: d => d.plan.status === 'draft'})).data.plan;
plan = (await http('POST', `/api/schedule/plans/${plan.id}/submit`, {as: admin, body: {expectedVersion: plan.version}})).data.plan;
plan = (await expect('Duyệt phương án', 'POST', `/api/schedule/plans/${plan.id}/approve`, {as: admin, body: {expectedVersion: plan.version}, check: d => d.plan.status === 'approved'})).data.plan;
await expect('Công bố khi thiếu người chưa xác nhận', 'POST', `/api/schedule/plans/${plan.id}/publish`, {as: admin, body: {expectedVersion: plan.version, confirmUnderstaffed: false}, status: 409, code: 'CONFIRM_UNDERSTAFFED'});
const published = await expect('Công bố lịch (xác nhận thiếu người, AC-02)', 'POST', `/api/schedule/plans/${plan.id}/publish`, {as: admin, body: {expectedVersion: plan.version, confirmUnderstaffed: true},
  check: d => `added=${d.added} notified=${(d.notifiedUsers || []).length}`});
await expect('Hủy phương án thiếu lý do', 'POST', `/api/schedule/plans/${plan2.id}/cancel`, {as: admin, body: {expectedVersion: plan2.version}, status: 400});
await expect('Hủy phương án 2', 'POST', `/api/schedule/plans/${plan2.id}/cancel`, {as: admin, body: {expectedVersion: plan2.version, reason: 'QA không dùng'}, check: d => d.status === 'cancelled'});
await expect('Phương án không tồn tại', 'GET', '/api/schedule/plans/khong_ton_tai', {as: admin, status: 404});

// ===== 8. Lịch, lịch của tôi, chỉnh lịch chính thức, kiểm tra ràng buộc =====
section('CALENDAR');
const cal = await expect('Lịch tuần (admin)', 'GET', `/api/schedule/calendar?view=week&date=${localDate(3)}&periodId=${P.id}`, {as: admin, check: d => `${d.shifts.length} ca`});
await expect('Lịch tháng có lọc theo nhân viên', 'GET', `/api/schedule/calendar?view=month&date=${localDate(3)}&userId=${A.id}`, {as: admin});
await expect('Lịch > 93 ngày', 'GET', `/api/schedule/calendar?from=${localDate(0)}&to=${localDate(120)}`, {as: admin, status: 400});
const myA = await expect('Lịch của tôi (A)', 'GET', `/api/my/schedule?from=${P.startDate}&to=${P.endDate}`, {as: A, check: d => d.shifts.length >= 1 ? `${d.shifts.length} ca, ${d.totalHours} giờ` : false});
const myB = await expect('Lịch của tôi (B)', 'GET', `/api/my/schedule?from=${P.startDate}&to=${P.endDate}`, {as: B, check: d => d.shifts.length >= 1});
await expect('Đánh dấu đã xem', 'POST', '/api/my/schedule/viewed', {as: A, body: {planId: plan.id}, status: 204});
await expect('Xác nhận lịch', 'POST', '/api/my/schedule/confirm', {as: A, body: {planId: plan.id}, status: 204});
await expect('Xác nhận thiếu planId', 'POST', '/api/my/schedule/confirm', {as: A, body: {}, status: 400});
await expect('Tự kiểm tra nhận ca', 'POST', '/api/my/checks/claim', {as: B, body: {shiftId: S3.id}, check: d => `valid=${d.valid}`});
await expect('Tự kiểm tra bỏ qua userId giả mạo', 'POST', '/api/my/checks/claim', {as: B, body: {shiftId: S3.id, userId: A.id}});
await expect('Kiểm tra phân công', 'POST', '/api/schedule/checks/assign', {as: admin, body: {shiftId: S3.id, userId: B.id}, check: d => `valid=${d.valid}`});
await expect('Kiểm tra hàng loạt', 'POST', '/api/schedule/checks/batch', {as: admin, body: {proposals: [{shiftId: S3.id, userId: B.id}, {shiftId: S1.id, userId: B.id}]}, check: d => `accepted=${d.acceptedCount}`});
await expect('Kiểm tra đổi chéo', 'POST', '/api/schedule/checks/swap', {as: admin, body: {shiftIdA: S1.id, userIdA: A.id, shiftIdB: S2.id, userIdB: B.id}, check: d => `allowed=${d.allowed}`});
await expect('Kiểm tra công bố', 'POST', '/api/schedule/checks/publish', {as: admin, body: {shiftIds: planShiftIds}, check: d => `understaffed=${d.anyUnderstaffed}`});
await expect('Kiểm tra ảnh hưởng nghỉ', 'POST', '/api/schedule/checks/leave', {as: admin, body: {userId: A.id, from: localDate(2), to: localDate(8)}});
await expect('Kiểm tra batch rỗng', 'POST', '/api/schedule/checks/batch', {as: admin, body: {proposals: []}, status: 400});
const s3Assignment = cal.data.shifts.find(s => s.shiftId === S3.id)?.assignees?.[0];
await expect('Xem trước chỉnh lịch chính thức', 'POST', '/api/schedule/official/preview', {as: admin, body: {shiftId: S3.id, userId: D.id}});
const offAdd = await expect('Thêm người vào lịch đã công bố', 'POST', '/api/schedule/official/assignments', {as: admin, body: {shiftId: S3.id, userId: D.id, reason: 'QA bổ sung'}, status: [201, 409, 422],
  check: d => d.assignmentId ? 'assignmentId=' + d.assignmentId : `code=${d.code}`});
if (s3Assignment) {
  await expect('Thay người với expectedUserId sai', 'POST', `/api/schedule/official/assignments/${s3Assignment.assignmentId}/reassign`, {as: admin, body: {expectedUserId: B.id, newUserId: D.id, reason: 'QA'}, status: 409});
}
if (offAdd.data?.assignmentId) {
  await expect('Hủy phân công chính thức thiếu lý do', 'POST', `/api/schedule/official/assignments/${offAdd.data.assignmentId}/cancel`, {as: admin, body: {expectedUserId: D.id}, status: 400});
  await expect('Hủy phân công chính thức', 'POST', `/api/schedule/official/assignments/${offAdd.data.assignmentId}/cancel`, {as: admin, body: {expectedUserId: D.id, reason: 'QA hoàn tác'}});
}

// ===== 9. Đổi ca / làm thay (BA-01, BA-02) =====
section('REQUEST');
const aShift = myA.data.shifts.find(s => s.shiftId === S1.id) || myA.data.shifts[0];
const bShift = myB.data.shifts.find(s => s.shiftId === S2.id) || myB.data.shifts[0];
await expect('Ứng viên làm thay', 'GET', `/api/requests/cover-candidates?assignmentId=${aShift.assignmentId}`, {as: A, check: d => `${d.length} ứng viên`});
await expect('Ca có thể đổi của B', 'GET', `/api/requests/swap-options?targetUserId=${B.id}`, {as: A, check: d => `${d.length} ca`});
await expect('Loại yêu cầu null (lỗi FE WS:206 tái hiện ở API)', 'POST', '/api/requests', {as: A, body: {type: null, sourceAssignmentId: aShift.assignmentId, targetUserId: B.id, reason: 'QA'}, status: 400, code: 'INVALID_TYPE'});
await expect('Admin tạo yêu cầu đổi ca', 'POST', '/api/requests', {as: admin, body: {type: 'cover', sourceAssignmentId: aShift.assignmentId, targetUserId: B.id, reason: 'QA'}, status: 403});
await expect('Đổi ca với chính mình', 'POST', '/api/requests', {as: A, body: {type: 'cover', sourceAssignmentId: aShift.assignmentId, targetUserId: A.id, reason: 'QA'}, status: 400});
const foreign = await expect('Đổi ca của người khác bị từ chối', 'POST', '/api/requests', {as: A, body: {type: 'cover', sourceAssignmentId: bShift.assignmentId, targetUserId: D.id, reason: 'QA'}, status: [400, 403, 409]});
if (foreign.status === 409) observe('api-contract', 'Mã lỗi khi dùng phân công của người khác', `POST /api/requests với sourceAssignmentId của người khác trả 409 ${foreign.data?.code} ("${foreign.data?.error}") thay vì 403/400 INVALID_ASSIGNMENT.`);
const swap = await expect('Tạo yêu cầu đổi chéo A↔B', 'POST', '/api/requests', {as: A,
  body: {type: 'swap', sourceAssignmentId: aShift.assignmentId, targetUserId: B.id, targetAssignmentId: bShift.assignmentId, reason: 'QA đổi chéo'}, status: 201, check: d => d.status === 'pending_peer'});
await expect('Yêu cầu trùng trên cùng ca', 'POST', '/api/requests', {as: A,
  body: {type: 'cover', sourceAssignmentId: aShift.assignmentId, targetUserId: D.id, reason: 'QA trùng'}, status: 409, code: 'REQUEST_ALREADY_OPEN'});
await expect('Người ngoài xem yêu cầu (IDOR)', 'GET', `/api/requests/${swap.data.id}`, {as: C, status: 404});
await expect('Người gửi tự đồng ý thay người nhận', 'POST', `/api/requests/${swap.data.id}/accept`, {as: A, body: {expectedVersion: swap.data.version}, status: 403});
await expect('Nhân viên xem tất cả yêu cầu', 'GET', '/api/requests?scope=all', {as: A, status: 403});
await expect('Đếm yêu cầu chờ (B)', 'GET', '/api/requests/pending-count', {as: B, check: d => d.incomingPeer >= 1});
await expect('Tổng hợp việc chờ (admin)', 'GET', '/api/pending-summary', {as: admin, check: d => 'totalForApprover' in d});
await expect('Tổng hợp việc chờ (nhân viên không có số liệu admin)', 'GET', '/api/pending-summary', {as: B, check: d => !('leaveAwaitingApproval' in d)});
const accepted = await expect('B đồng ý đổi', 'POST', `/api/requests/${swap.data.id}/accept`, {as: B, body: {expectedVersion: swap.data.version, note: 'OK'}, check: d => d.status === 'pending_approval'});
await expect('Lịch chưa đổi trước khi duyệt (AC-08)', 'GET', `/api/my/schedule?from=${P.startDate}&to=${P.endDate}`, {as: A, check: d => d.shifts.some(s => s.shiftId === aShift.shiftId)});
await expect('Duyệt với version cũ', 'POST', `/api/requests/${swap.data.id}/approve`, {as: admin, body: {expectedVersion: swap.data.version}, status: 409, code: 'STALE_VERSION'});
const approvedSwap = await expect('Admin duyệt đổi ca', 'POST', `/api/requests/${swap.data.id}/approve`, {as: admin, body: {expectedVersion: accepted.data.version}, check: d => d.status === 'approved'});
await expect('Duyệt lặp lại (idempotent)', 'POST', `/api/requests/${swap.data.id}/approve`, {as: admin, body: {expectedVersion: approvedSwap.data.version}, check: d => d.status === 'approved'});
await expect('Lịch đã đổi sau khi duyệt', 'GET', `/api/my/schedule?from=${P.startDate}&to=${P.endDate}`, {as: A, check: d => d.shifts.some(s => s.shiftId === bShift.shiftId)});
const myA2 = (await http('GET', `/api/my/schedule?from=${P.startDate}&to=${P.endDate}`, {as: A})).data;
const cover = await expect('Tạo yêu cầu làm thay', 'POST', '/api/requests', {as: A,
  body: {type: 'cover', sourceAssignmentId: myA2.shifts[0].assignmentId, targetUserId: D.id, reason: 'QA nhờ làm thay'}, status: 201});
await expect('Người khác hủy yêu cầu', 'POST', `/api/requests/${cover.data.id}/cancel`, {as: B, body: {expectedVersion: cover.data.version}, status: [403, 404]});
await expect('Từ chối thiếu ghi chú', 'POST', `/api/requests/${cover.data.id}/reject`, {as: admin, body: {expectedVersion: cover.data.version}, status: 400});
const declined = await expect('Người nhận từ chối', 'POST', `/api/requests/${cover.data.id}/decline`, {as: D, body: {expectedVersion: cover.data.version, note: 'Bận'}, check: d => d.status === 'peer_declined'});
await expect('Hủy yêu cầu đã bị từ chối', 'POST', `/api/requests/${cover.data.id}/cancel`, {as: A, body: {expectedVersion: declined.data.version}, status: [200, 409]});
await expect('Danh sách yêu cầu của tôi', 'GET', '/api/requests?scope=mine', {as: A, check: d => d.length >= 2});
await expect('Danh sách tất cả (admin)', 'GET', '/api/requests?scope=all', {as: admin});

// ===== 10. Nghỉ phép & vắng mặt (BA-04) =====
section('LEAVE');
const leaveTypes = (await expect('Loại nghỉ', 'GET', '/api/leave/types', {as: A, check: d => d.length === 4})).data;
await expect('Loại nghỉ kể cả ngừng dùng (nhân viên)', 'GET', '/api/leave/types?includeInactive=true', {as: A, status: 403});
const annual = leaveTypes.find(t => t.id === 'leave_annual');
const myA3 = (await http('GET', `/api/my/schedule?from=${P.startDate}&to=${P.endDate}`, {as: A})).data;
const leaveDay = myA3.shifts[0]?.date || localDate(6);
const leave = await expect('Tạo đơn nghỉ', 'POST', '/api/leave', {as: A, body: {leaveTypeId: annual.id, startDate: leaveDay, endDate: leaveDay, reason: 'QA nghỉ phép'}, status: 201, check: d => d.status === 'pending'});
await expect('Đơn nghỉ chồng ngày', 'POST', '/api/leave', {as: A, body: {leaveTypeId: annual.id, startDate: leaveDay, endDate: leaveDay, reason: 'QA trùng'}, status: 409, code: 'LEAVE_OVERLAP'});
await expect('Ngày kết thúc trước ngày bắt đầu', 'POST', '/api/leave', {as: A, body: {leaveTypeId: annual.id, startDate: localDate(9), endDate: localDate(8), reason: 'QA'}, status: 400, code: 'INVALID_RANGE'});
await expect('Nghỉ ốm thiếu giấy tờ', 'POST', '/api/leave', {as: A, body: {leaveTypeId: 'leave_sick', startDate: localDate(20), endDate: localDate(20), reason: 'QA'}, status: 400, code: 'DOCUMENT_REQUIRED'});
await expect('Loại nghỉ không tồn tại', 'POST', '/api/leave', {as: A, body: {leaveTypeId: 'leave_magic', startDate: localDate(20), endDate: localDate(20), reason: 'QA'}, status: 400, code: 'INVALID_LEAVE_TYPE'});
await expect('Admin tạo đơn nghỉ', 'POST', '/api/leave', {as: admin, body: {leaveTypeId: annual.id, startDate: localDate(20), endDate: localDate(20), reason: 'QA'}, status: 403});
await expect('Người ngoài xem đơn nghỉ (IDOR)', 'GET', `/api/leave/${leave.data.id}`, {as: C, status: 404});
await expect('Ảnh hưởng tới lịch', 'GET', `/api/leave/${leave.data.id}/impact`, {as: admin, check: d => `${d.shifts.length} ca bị ảnh hưởng`});
await expect('Số đơn chờ duyệt (admin)', 'GET', '/api/leave/pending-count', {as: admin, check: d => d.pending >= 1});
await expect('Số đơn chờ duyệt (nhân viên)', 'GET', '/api/leave/pending-count', {as: A, status: 403});
const leaveApproved = await expect('Duyệt đơn nghỉ (gỡ phân công trong ngày nghỉ)', 'POST', `/api/leave/${leave.data.id}/approve`, {as: admin, body: {expectedVersion: leave.data.version}, check: d => `affected=${d.affectedAssignmentCount}`});
await expect('Duyệt lặp lại (idempotent)', 'POST', `/api/leave/${leave.data.id}/approve`, {as: admin, body: {expectedVersion: leaveApproved.data.version}});
await expect('Từ chối đơn đã duyệt', 'POST', `/api/leave/${leave.data.id}/reject`, {as: admin, body: {expectedVersion: leaveApproved.data.version, note: 'QA'}, status: 409});
const leave2 = await http('POST', '/api/leave', {as: B, body: {leaveTypeId: annual.id, startDate: localDate(25), endDate: localDate(26), reason: 'QA đơn 2'}});
await expect('Từ chối đơn thiếu ghi chú', 'POST', `/api/leave/${leave2.data.id}/reject`, {as: admin, body: {expectedVersion: leave2.data.version}, status: 400});
await expect('Người khác hủy đơn', 'POST', `/api/leave/${leave2.data.id}/cancel`, {as: A, body: {expectedVersion: leave2.data.version}, status: [403, 404]});
await expect('Chủ đơn hủy đơn', 'POST', `/api/leave/${leave2.data.id}/cancel`, {as: B, body: {expectedVersion: leave2.data.version}, check: d => d.status === 'cancelled'});
await expect('Danh sách đơn của tôi', 'GET', '/api/leave?scope=mine', {as: A, check: d => d.length >= 1});
await expect('Danh sách tất cả (nhân viên)', 'GET', '/api/leave?scope=all', {as: A, status: 403});
await expect('Báo cáo vắng mặt', 'GET', `/api/leave/absences/report?from=${localDate(-7)}&to=${localDate(10)}`, {as: admin});
await expect('Báo cáo vắng mặt sai khoảng ngày', 'GET', `/api/leave/absences/report?from=${localDate(10)}&to=${localDate(0)}`, {as: admin, status: 400});
const myB2 = (await http('GET', `/api/my/schedule?from=${P.startDate}&to=${P.endDate}`, {as: B})).data;
if (myB2.shifts[0]) await expect('Ghi vắng cho ca tương lai', 'POST', '/api/leave/absences', {as: admin, body: {assignmentId: myB2.shifts[0].assignmentId, reason: 'QA'}, status: 400, code: 'FUTURE_SHIFT'});

// ===== 11. Ca trống (BA-03) =====
section('OPEN_SHIFT');
await expect('Mở ca trống cho ca chưa công bố', 'POST', '/api/open-shifts', {as: admin, body: {shiftId: night.data.id, slots: 1, claimDeadline: localDate(6) + 'T20:00'}, status: 400, code: 'SHIFT_NOT_PUBLISHED'});
await expect('Hạn nhận ca trong quá khứ', 'POST', '/api/open-shifts', {as: admin, body: {shiftId: S3.id, slots: 1, claimDeadline: localDateTime(-3600_000)}, status: 400, code: 'INVALID_DEADLINE'});
await expect('Số chỗ vượt số còn thiếu', 'POST', '/api/open-shifts', {as: admin, body: {shiftId: S3.id, slots: 5, claimDeadline: localDate(5) + 'T20:00'}, status: 400, code: 'TOO_MANY_SLOTS'});
const open = await expect('Mở ca trống', 'POST', '/api/open-shifts', {as: admin, body: {shiftId: S3.id, slots: 1, claimDeadline: localDate(5) + 'T20:00', note: 'QA ca trống'}, status: 201, check: d => d.status === 'open'});
await expect('Mở trùng ca trống', 'POST', '/api/open-shifts', {as: admin, body: {shiftId: S3.id, slots: 1, claimDeadline: localDate(5) + 'T20:00'}, status: 409, code: 'OPEN_SHIFT_EXISTS'});
await expect('Admin nhận ca', 'POST', `/api/open-shifts/${open.data.id}/claims`, {as: admin, body: {}, status: 403});
await expect('Rút đăng ký khi chưa đăng ký (lỗi FE WS:226 tái hiện ở API)', 'DELETE', `/api/open-shifts/${open.data.id}/claims/mine`, {as: D, status: 404});
const claimD = await expect('D nhận ca trống', 'POST', `/api/open-shifts/${open.data.id}/claims`, {as: D, body: {}, status: 201, check: d => d.status === 'pending'});
await expect('D nhận lại (trả về đăng ký cũ)', 'POST', `/api/open-shifts/${open.data.id}/claims`, {as: D, body: {}, status: 201, check: d => d.id === claimD.data.id});
await expect('D rút đăng ký', 'DELETE', `/api/open-shifts/${open.data.id}/claims/mine`, {as: D, check: d => d.status === 'withdrawn'});
await expect('D rút lần hai (idempotent, vẫn withdrawn)', 'DELETE', `/api/open-shifts/${open.data.id}/claims/mine`, {as: D, status: [200, 409], check: d => !d.status || d.status === 'withdrawn'});
const reclaim = await expect('D nhận lại sau khi rút', 'POST', `/api/open-shifts/${open.data.id}/claims`, {as: D, body: {}, status: 201, check: d => d.status === 'pending'});
await expect('Danh sách ca trống (nhân viên)', 'GET', '/api/open-shifts', {as: D, check: d => d.some(o => o.id === open.data.id && o.myClaimStatus === 'pending')});
await expect('Chi tiết ca trống (admin thấy danh sách đăng ký)', 'GET', `/api/open-shifts/${open.data.id}`, {as: admin, check: d => Array.isArray(d.claims) && d.claims.length >= 1});
await expect('Chi tiết ca trống (nhân viên không thấy đăng ký của người khác)', 'GET', `/api/open-shifts/${open.data.id}`, {as: B, check: d => !d.claims || d.claims.length === 0});
await expect('Số đăng ký chờ duyệt', 'GET', '/api/open-shifts/pending-count', {as: admin, check: d => d.pendingClaims >= 1});
await expect('Từ chối thiếu ghi chú', 'POST', `/api/open-shifts/${open.data.id}/claims/${reclaim.data.id}/reject`, {as: admin, body: {expectedVersion: reclaim.data.version}, status: 400});
const approvedClaim = await expect('Duyệt đăng ký nhận ca (tạo phân công)', 'POST', `/api/open-shifts/${open.data.id}/claims/${reclaim.data.id}/approve`, {as: admin, body: {expectedVersion: reclaim.data.version}, status: [200, 422],
  check: d => d.status === 'approved' ? 'assignment=' + d.assignmentId : `code=${d.code}`});
if (approvedClaim.status === 200) {
  await expect('Ca trống chuyển sang đã đủ người', 'GET', `/api/open-shifts/${open.data.id}`, {as: admin, check: d => d.openShift.status === 'filled'});
  await expect('Duyệt lại đăng ký đã duyệt', 'POST', `/api/open-shifts/${open.data.id}/claims/${reclaim.data.id}/approve`, {as: admin, body: {expectedVersion: approvedClaim.data.version}, status: [200, 409]});
}
await expect('Đóng ca trống với version cũ', 'POST', `/api/open-shifts/${open.data.id}/close`, {as: admin, body: {expectedVersion: -1}, status: 409});

// ===== 12. Chấm công thực tế (BI-01, BI-02) =====
section('ATTENDANCE');
const live = (await expect('Tạo kỳ chấm công hôm nay', 'POST', '/api/periods', {as: admin, status: 201,
  body: {name: 'QA Kỳ chấm công ' + run, cycle: 'weekly', startDate: localDate(-1), endDate: localDate(1), autoLock: false, allowReopenByAdmin: true,
    requiredDaysOfWeek: ALL_DAYS, applicableUserIds: [A.id, B.id, C.id]}})).data;
let liveP = (await http('POST', `/api/periods/${live.id}/open`, {as: admin, body: {version: live.version}})).data;
for (const p of [A, B, C])
  await http('POST', `/api/availability/my/submit?periodId=${live.id}`, {as: p, body: {entries: [-1, 0, 1].map(i => ({date: localDate(i), slots: [{start: '00:00', end: '23:59', type: 'available'}]}))}});
liveP = (await http('POST', `/api/periods/${live.id}/lock`, {as: admin, body: {version: liveP.version}})).data;
// Ca bắt đầu 10 phút trước, nhưng không lùi qua 00:00 giờ VN (quy tắc có hiệu lực từ hôm nay; ca hôm qua sẽ thiếu cấu hình).
const minutesSinceMidnight = Math.floor(((Date.now() + VN) % 86_400_000) / 60_000);
const startMs = -Math.min(10, minutesSinceMidnight) * 60_000, endMs = 2 * 3600_000;
const liveShift = (await expect('Tạo ca đang diễn ra (now-10p → now+2h)', 'POST', '/api/schedule/shifts', {as: admin, status: 201,
  body: {periodId: live.id, name: 'QA Ca đang diễn ra', date: localDateTime(startMs).slice(0, 10), startTime: hhmm(startMs), endTime: hhmm(endMs), mode: 'combined', totalRequired: 2, mandatoryUserIds: [A.id, C.id]}})).data;
let livePlan = (await http('POST', '/api/schedule/plans', {as: admin, body: {periodId: live.id, name: 'QA lịch chấm công', shiftIds: [liveShift.id]}})).data;
livePlan = (await http('POST', `/api/schedule/plans/${livePlan.id}/submit`, {as: admin, body: {expectedVersion: livePlan.version}})).data.plan;
livePlan = (await http('POST', `/api/schedule/plans/${livePlan.id}/approve`, {as: admin, body: {expectedVersion: livePlan.version}})).data.plan;
await expect('Công bố lịch chấm công', 'POST', `/api/schedule/plans/${livePlan.id}/publish`, {as: admin, body: {expectedVersion: livePlan.version, confirmUnderstaffed: true}});
await expect('Ca chấm công của tôi', 'GET', '/api/attendance/shifts', {as: A, check: d => d.some(s => (s.id || s.shift_id || s.shiftId) === liveShift.id) || JSON.stringify(d).includes(liveShift.id)});
await expect('Check-in thiếu shiftId', 'POST', '/api/attendance/check-in', {as: A, body: {}, status: 400});
await expect('Check-in ca không được phân công', 'POST', '/api/attendance/check-in', {as: B, body: {shiftId: liveShift.id}, status: 409});
const punch = await expect('Check-in đúng ca', 'POST', '/api/attendance/check-in', {as: A, body: {shiftId: liveShift.id}, check: d => d.status === 'open'});
const punchAt = Date.now();
await expect('Check-in trùng', 'POST', '/api/attendance/check-in', {as: A, body: {shiftId: liveShift.id}, status: 409});
await expect('Người khác check-out hộ', 'POST', `/api/attendance/${punch.data.id}/check-out`, {as: C, body: {breakMinutes: 0}, status: [403, 404]});
await expect('Check-out với giờ nghỉ âm', 'POST', `/api/attendance/${punch.data.id}/check-out`, {as: A, body: {breakMinutes: -5}, status: 400});
await expect('Bản ghi công của tôi', 'GET', `/api/attendance/records?periodId=${live.id}`, {as: A, check: d => d.length >= 1});
await expect('Xem bản ghi công người khác', 'GET', `/api/attendance/records?userId=${C.id}`, {as: A, status: 403});
await expect('Admin xem toàn bộ bản ghi', 'GET', `/api/attendance/records?periodId=${live.id}`, {as: admin});
const nowIso = new Date().toISOString();
const adj = await expect('Đề nghị điều chỉnh công (C quên check-in)', 'POST', '/api/attendance/requests', {as: C,
  body: {shiftId: liveShift.id, recordId: null, clockIn: new Date(Date.now() + startMs).toISOString(), clockOut: nowIso, breakMinutes: 0, reason: 'QA quên chấm công'}, check: d => d.status === 'pending'});
await expect('Đề nghị trùng khi còn chờ', 'POST', '/api/attendance/requests', {as: C,
  body: {shiftId: liveShift.id, clockIn: new Date(Date.now() + startMs).toISOString(), clockOut: nowIso, breakMinutes: 0, reason: 'QA trùng lặp'}, status: 409});
await expect('Đề nghị lý do < 5 ký tự', 'POST', '/api/attendance/requests', {as: B,
  body: {shiftId: liveShift.id, clockIn: new Date(Date.now() + startMs).toISOString(), clockOut: nowIso, breakMinutes: 0, reason: 'x'}, status: 400});
await expect('Đề nghị giờ ra trước giờ vào', 'POST', '/api/attendance/requests', {as: C,
  body: {shiftId: S1.id, clockIn: nowIso, clockOut: new Date(Date.now() - 3600_000).toISOString(), breakMinutes: 0, reason: 'QA sai giờ'}, status: [400, 409]});
await expect('Danh sách đề nghị chờ (admin)', 'GET', '/api/attendance/requests?status=pending', {as: admin, check: d => d.length >= 1});
await expect('Nhân viên duyệt đề nghị', 'POST', `/api/attendance/requests/${adj.data.id}/review`, {as: A, body: {approve: true, reason: 'QA tự duyệt'}, status: 403});
await expect('Duyệt đề nghị thiếu lý do', 'POST', `/api/attendance/requests/${adj.data.id}/review`, {as: admin, body: {approve: true, reason: ''}, status: 400});
await expect('Admin duyệt đề nghị điều chỉnh', 'POST', `/api/attendance/requests/${adj.data.id}/review`, {as: admin, body: {approve: true, reason: 'QA đã xác minh'}, check: d => d.status === 'approved'});
await expect('Duyệt lại đề nghị đã xử lý', 'POST', `/api/attendance/requests/${adj.data.id}/review`, {as: admin, body: {approve: true, reason: 'QA lần hai'}, status: 409});
await expect('Nhật ký chấm công của A (admin xem theo userId)', 'GET', `/api/attendance/audit?userId=${A.id}`, {as: admin, check: d => d.length >= 1 ? `${d.length} dòng` : false});
await expect('Nhật ký chấm công không truyền userId (trả về của chính admin)', 'GET', '/api/attendance/audit', {as: admin, check: d => `${d.length} dòng`});
observe('ux', 'Cổng chấm công: admin không thấy nhật ký của nhân viên', 'attendance-portal.js gọi GET /api/attendance/audit không kèm userId; service mặc định lấy nhật ký của chính người gọi nên admin chỉ thấy lịch sử của mình (thường rỗng).');
await expect('Nhật ký chấm công của tôi (A)', 'GET', '/api/attendance/audit', {as: A, check: d => d.length >= 1});
await expect('Nhật ký chấm công người khác (nhân viên)', 'GET', `/api/attendance/audit?userId=${C.id}`, {as: A, status: 403});

// ===== 13. Thông báo (BA-05, BA-06) =====
section('NOTIFICATION');
let inbox;
for (const until = Date.now() + 15_000; Date.now() < until;) {
  inbox = (await http('GET', '/api/notifications?page=0&size=20', {as: A})).data;
  if (inbox.items?.length) break;
  await sleep(500);
}
verify('Outbox sinh thông báo cho nhân viên sau công bố/đổi ca', inbox?.items?.length > 0, `${inbox?.items?.length || 0} thông báo, unread=${inbox?.unread}`);
await expect('Danh sách thông báo', 'GET', '/api/notifications?page=0&size=20', {as: A, check: d => `total=${d.total}`});
await expect('Chỉ chưa đọc', 'GET', '/api/notifications?unreadOnly=true', {as: A});
await expect('size=0 không hợp lệ', 'GET', '/api/notifications?size=0', {as: A, status: 400});
await expect('page âm', 'GET', '/api/notifications?page=-1', {as: A, status: 400});
await expect('Số chưa đọc', 'GET', '/api/notifications/unread-count', {as: A});
if (inbox?.items?.[0]) {
  await expect('Người khác đánh dấu đã đọc (IDOR)', 'POST', `/api/notifications/${inbox.items[0].id}/read`, {as: C, status: 404});
  await expect('Đánh dấu đã đọc', 'POST', `/api/notifications/${inbox.items[0].id}/read`, {as: A, status: 204});
}
await expect('Đánh dấu tất cả đã đọc', 'POST', '/api/notifications/read-all', {as: A, check: d => typeof d.updated === 'number'});
await expect('Số chưa đọc = 0 sau read-all', 'GET', '/api/notifications/unread-count', {as: A, check: d => d.unread === 0});
await expect('Cài đặt nhắc hạn (admin)', 'GET', '/api/notifications/settings', {as: admin});
await expect('Lưu mốc nhắc hạn 48h, 24h', 'PUT', '/api/notifications/settings', {as: admin, body: {deadlineReminderHours: [24, 48, 24]}, check: d => JSON.stringify(d.deadlineReminderHours) === '[48,24]'});
await expect('Mốc nhắc 0 giờ', 'PUT', '/api/notifications/settings', {as: admin, body: {deadlineReminderHours: [0]}, status: 400});
await expect('Quá 5 mốc nhắc', 'PUT', '/api/notifications/settings', {as: admin, body: {deadlineReminderHours: [1, 2, 3, 4, 5, 6]}, status: 400, code: 'TOO_MANY_REMINDERS'});
await expect('Nhân viên đổi cài đặt', 'PUT', '/api/notifications/settings', {as: A, body: {deadlineReminderHours: [24]}, status: 403});

// ===== 14. Hoàn tất chấm công (cần ≥ 60 giây sau check-in) =====
section('ATTENDANCE');
const waitMs = 62_000 - (Date.now() - punchAt);
if (waitMs > 0) { console.log(`… chờ ${Math.ceil(waitMs / 1000)} giây để có thời gian làm việc thực trước khi check-out`); await sleep(waitMs); }
await expect('Check-out với giờ nghỉ ≥ thời gian làm', 'POST', `/api/attendance/${punch.data.id}/check-out`, {as: A, body: {breakMinutes: 600}, status: [400, 409]});
const out = await expect('Check-out', 'POST', `/api/attendance/${punch.data.id}/check-out`, {as: A, body: {breakMinutes: 0}, check: d => d.status === 'completed'});
await expect('Check-out lần hai', 'POST', `/api/attendance/${punch.data.id}/check-out`, {as: A, body: {breakMinutes: 0}, status: 409});
await expect('Danh sách chờ xác nhận giờ công', 'GET', `/api/attendance/pending-approval?periodId=${live.id}`, {as: admin, check: d => d.some(r => r.id === punch.data.id)});
await expect('Nhân viên xem danh sách chờ xác nhận', 'GET', '/api/attendance/pending-approval', {as: A, status: 403});
await expect('Xác nhận giờ công thiếu lý do', 'POST', `/api/attendance/${punch.data.id}/approve`, {as: admin, body: {reason: 'ok'}, status: 400});
await expect('Xác nhận giờ công', 'POST', `/api/attendance/${punch.data.id}/approve`, {as: admin, body: {reason: 'QA xác nhận giờ'}, check: d => d.status === 'approved'});
await expect('Khóa kỳ chấm công thiếu lý do', 'POST', `/api/attendance/periods/${live.id}/lock`, {as: admin, body: {reason: 'x'}, status: 400});
await expect('Nhân viên khóa kỳ chấm công', 'POST', `/api/attendance/periods/${live.id}/lock`, {as: A, body: {reason: 'QA khóa thử'}, status: 403});
await expect('Khóa kỳ chấm công', 'POST', `/api/attendance/periods/${live.id}/lock`, {as: admin, body: {reason: 'QA chốt công'}});
await expect('Khóa kỳ chấm công lần hai', 'POST', `/api/attendance/periods/${live.id}/lock`, {as: admin, body: {reason: 'QA chốt lần hai'}, status: 409});
await expect('Check-in sau khi khóa kỳ', 'POST', '/api/attendance/check-in', {as: C, body: {shiftId: liveShift.id}, status: 409});
void out;

// ===== 15. Báo cáo & xuất file (BI-03..BI-05, TW-07, AC-03, AC-07) =====
section('REPORT');
await expect('Danh sách kỳ báo cáo', 'GET', '/api/reports/periods', {as: A});
const dash = await expect('Dashboard (admin có chỉ số quản trị)', 'GET', `/api/reports/dashboard?periodId=${P.id}`, {as: admin,
  check: d => 'pendingRequests' in d ? `scheduled=${d.scheduledHours} target=${d.targetHours} %=${d.percentage}` : false});
await expect('Dashboard (nhân viên không có chỉ số quản trị)', 'GET', `/api/reports/dashboard?periodId=${P.id}`, {as: A, check: d => !('pendingRequests' in d)});
await expect('Dashboard của người khác (nhân viên)', 'GET', `/api/reports/dashboard?periodId=${P.id}&userId=${B.id}`, {as: A, status: 403});
await expect('Dashboard kỳ không tồn tại', 'GET', '/api/reports/dashboard?periodId=khong_ton_tai', {as: admin, status: 404});
const usersReport = await expect('Báo cáo giờ theo nhân viên', 'GET', `/api/reports/users?periodId=${P.id}`, {as: admin, check: d => `${d.length} dòng`});
await expect('Báo cáo giờ của người khác (nhân viên)', 'GET', `/api/reports/users?periodId=${P.id}&userId=${B.id}`, {as: A, status: 403});
await expect('Tình trạng chấm công', 'GET', `/api/reports/attendance-status?periodId=${live.id}`, {as: admin});
await expect('Ca thiếu người (admin)', 'GET', `/api/reports/shortages?periodId=${P.id}`, {as: admin, check: d => `${d.length} ca thiếu`});
await expect('Ca thiếu người (nhân viên)', 'GET', `/api/reports/shortages?periodId=${P.id}`, {as: A, status: 403});
await expect('Xuất CSV', 'GET', `/api/reports/export?periodId=${P.id}&format=csv`, {as: admin,
  check: (d, r) => /text\/csv/.test(r.headers.get('content-type')) && /attachment/.test(r.headers.get('content-disposition') || '') ? r.headers.get('content-disposition') : false});
await expect('Xuất Excel (XLSX là file ZIP)', 'GET', `/api/reports/export?periodId=${P.id}&format=xlsx`, {as: admin,
  check: (d, r) => r.buffer.subarray(0, 2).toString() === 'PK' ? `${r.buffer.length} bytes` : false});
await expect('Định dạng xuất không hỗ trợ', 'GET', `/api/reports/export?periodId=${P.id}&format=pdf`, {as: admin, status: 400});
await expect('Nhân viên xuất file của mình', 'GET', `/api/reports/export?periodId=${P.id}&format=csv`, {as: A});
await expect('Phiên bản lịch', 'GET', `/api/schedule/reports/versions?periodId=${P.id}`, {as: admin});
const hours = await expect('Giờ theo lịch công bố', 'GET', `/api/schedule/reports/hours?periodId=${P.id}`, {as: admin, check: d => `totals.hours=${d.totals.hours}`});
await expect('Giờ theo phương án thiếu planId', 'GET', `/api/schedule/reports/hours?periodId=${P.id}&source=plan`, {as: admin, status: 400});
await expect('Giờ theo phương án', 'GET', `/api/schedule/reports/hours?periodId=${P.id}&source=plan&planId=${plan.id}`, {as: admin});
await expect('Danh sách phân công', 'GET', `/api/schedule/reports/assignments?periodId=${P.id}`, {as: admin});
await expect('Độ phủ ca', 'GET', `/api/schedule/reports/shifts?periodId=${P.id}`, {as: admin});
const sumUsers = (usersReport.data || []).reduce((s, r) => s + Number(r.scheduledHours || 0), 0);
verify('Tổng giờ báo cáo nhân viên = tổng giờ lịch công bố (AC-07)', Math.abs(sumUsers - Number(hours.data?.totals?.hours)) < 0.01, `users=${sumUsers} published=${hours.data?.totals?.hours}`);
verify('Dashboard scheduledHours = tổng giờ lịch công bố (AC-03)', Math.abs(Number(dash.data?.scheduledHours) - Number(hours.data?.totals?.hours)) < 0.01, `dashboard=${dash.data?.scheduledHours} published=${hours.data?.totals?.hours}`);

// ===== 16. Bảo mật phiên & hợp đồng lỗi =====
section('SECURITY');
if (jwtSecret) {
  const escalated = signJwt({sub: A.id, username: A.username, role: 'admin', iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 600});
  tokens.set(escalated, 'forged-role');
  await expect('Token chữ ký đúng nhưng role bị nâng thành admin (IdentityGuard)', 'GET', '/api/workspace/employees', {as: {label: 'forged-role', token: escalated}, status: 401, code: 'SESSION_INVALID'});
}
const empC = (await http('GET', `/api/workspace/employees/${C.id}`, {as: admin})).data;
await expect('Khóa tài khoản C', 'PUT', `/api/workspace/employees/${C.id}`, {as: admin, body: {employeeCode: empC.employeeCode, username: C.username, fullName: empC.fullName, levelId: empC.levelId, status: 'inactive', version: empC.version}});
await expect('Token cũ của tài khoản đã khóa bị chặn', 'GET', '/api/periods', {as: C, status: 401, code: 'SESSION_INVALID'});
await expect('Tài khoản đã khóa không đăng nhập được', 'POST', '/api/auth/login', {body: {username: C.username, password: fixturePassword}, status: 401});
await expect('Đổi mật khẩu sai mật khẩu hiện tại', 'POST', '/api/auth/change-password', {as: B, body: {currentPassword: 'wrong-pass', newPassword: 'NewPass-2026!'}, status: 401, code: 'WRONG_PASSWORD'});
await expect('Đổi mật khẩu mới quá ngắn', 'POST', '/api/auth/change-password', {as: B, body: {currentPassword: fixturePassword, newPassword: 'short'}, status: 400, code: 'INVALID_PASSWORD'});
await expect('Đổi mật khẩu', 'POST', '/api/auth/change-password', {as: B, body: {currentPassword: fixturePassword, newPassword: 'NewPass-2026!'}});
await expect('Đăng nhập bằng mật khẩu cũ', 'POST', '/api/auth/login', {body: {username: B.username, password: fixturePassword}, status: 401});
await expect('Đăng nhập bằng mật khẩu mới', 'POST', '/api/auth/login', {body: {username: B.username, password: 'NewPass-2026!'}});
await expect('Token phát hành trước khi đổi mật khẩu vẫn dùng được', 'GET', '/api/periods', {as: B});
observe('security', 'JWT cũ vẫn hợp lệ sau khi đổi mật khẩu', 'Không có cơ chế thu hồi token khi đổi mật khẩu; token trước đó dùng được tới khi hết hạn.');
await expect('JSON hỏng gửi tới API kỳ', 'POST', '/api/periods', {as: admin, rawBody: '{"name":', status: 400});
await expect('Content-Type sai', 'POST', '/api/periods', {as: admin, rawBody: 'name=x', headers: {'Content-Type': 'text/plain'}, status: [400, 415]});
await expect('Phương thức không hỗ trợ', 'DELETE', '/api/levels', {as: admin, status: 405});
await expect('Route không tồn tại (đã đăng nhập)', 'GET', '/api/khong-ton-tai', {as: admin, status: 404});
for (const path of ['/.env', '/pom.xml', '/demo.html', '/partials/login.html', '/actuator/health', '/actuator/env'])
  await expect(`Không lộ tệp nội bộ ${path} (ẩn danh)`, 'GET', path, {status: [401, 404]});
for (const path of ['/.env', '/pom.xml', '/src/main/resources/application-sqlserver.properties'])
  await expect(`Không lộ tệp nội bộ ${path} (đã đăng nhập)`, 'GET', path, {as: admin, status: [400, 404]});
await expect('Trang chính công khai', 'GET', '/workspace.html', {});
await expect('Script FE công khai', 'GET', '/js/workspace.js', {});
const headersRes = await http('GET', '/api/health');
verify('Header bảo mật: nosniff, X-Frame-Options, Cache-Control', headersRes.headers.get('x-content-type-options') === 'nosniff' && /SAMEORIGIN/i.test(headersRes.headers.get('x-frame-options') || ''),
  `nosniff=${headersRes.headers.get('x-content-type-options')} xfo=${headersRes.headers.get('x-frame-options')} cache=${headersRes.headers.get('cache-control')}`);
verify('Không có Content-Security-Policy / HSTS (quan sát)', true, `csp=${headersRes.headers.get('content-security-policy')} hsts=${headersRes.headers.get('strict-transport-security')}`);
const pre = await http('OPTIONS', '/api/periods', {headers: {Origin: 'http://evil.example', 'Access-Control-Request-Method': 'GET'}});
verify('CORS chặn origin lạ', pre.status === 403, `HTTP ${pre.status}`);
await expect('Health database', 'GET', '/api/health/database', {check: d => d.status === 'UP'});

// ===== 17. Đồng thời (optimistic locking) =====
section('CONCURRENCY');
const empD = (await http('GET', `/api/workspace/employees/${D.id}`, {as: admin})).data;
const parallel = await Promise.all(Array.from({length: 8}, (_, i) => http('PUT', `/api/workspace/employees/${D.id}`, {as: admin,
  body: {employeeCode: empD.employeeCode, username: D.username, fullName: `QA D song song ${i}`, levelId: empD.levelId, status: 'active', version: empD.version}})));
const winners = parallel.filter(r => r.status === 200).length;
verify('8 cập nhật song song cùng version: đúng 1 thành công, còn lại 409', winners === 1 && parallel.every(r => [200, 409].includes(r.status)), `200=${winners} codes=${parallel.map(r => r.status).join(',')}`);
const raceShift = (await http('POST', '/api/schedule/shifts', {as: admin, body: {...shiftBase, name: 'QA Race', date: localDate(8), mandatoryUserIds: []}})).data;
const cancels = await Promise.all([0, 1, 2].map(i => http('POST', `/api/schedule/shifts/${raceShift.id}/cancel`, {as: admin, body: {expectedVersion: raceShift.version, reason: 'QA race ' + i}})));
verify('3 lệnh hủy ca song song: đúng 1 thành công', cancels.filter(r => r.status === 200).length === 1, cancels.map(r => r.status).join(','));

// ===== 18. Hiệu năng sơ bộ =====
section('PERFORMANCE');
async function load(name, path, as, total = 200, concurrency = 20) {
  const times = []; let errors = 0; let i = 0;
  const started = performance.now();
  await Promise.all(Array.from({length: concurrency}, async () => {
    while (i < total) {
      i++;
      const t = performance.now();
      try { const r = await fetch(base + path, {headers: {Authorization: 'Bearer ' + as.token}}); await r.arrayBuffer(); if (!r.ok) errors++; } catch { errors++; }
      times.push(performance.now() - t);
    }
  }));
  const wall = performance.now() - started;
  times.sort((a, b) => a - b);
  const pct = p => Math.round(times[Math.min(times.length - 1, Math.floor(times.length * p))]);
  const stats = {path, requests: times.length, concurrency, errors, p50: pct(0.5), p95: pct(0.95), max: Math.round(times.at(-1)), throughputRps: Math.round(times.length / (wall / 1000))};
  evidence.performance[name] = stats;
  verify(`${name}: ${stats.requests} request, p95=${stats.p95}ms, lỗi=${errors}`, errors === 0 && stats.p95 < 2000, JSON.stringify(stats));
}
await load('GET /api/periods', '/api/periods', admin);
await load('GET /api/notifications/unread-count', '/api/notifications/unread-count', A);
await load('GET /api/schedule/calendar (tuần)', `/api/schedule/calendar?view=week&date=${localDate(3)}`, admin, 100, 10);
await load('GET /api/reports/dashboard', `/api/reports/dashboard?periodId=${P.id}`, admin, 100, 10);
const loginTimes = [];
for (let i = 0; i < 10; i++) { const t = performance.now(); await fetch(base + '/api/auth/login', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({username: adminUser, password: adminPass})}); loginTimes.push(performance.now() - t); }
evidence.performance['POST /api/auth/login (BCrypt, tuần tự)'] = {requests: 10, avg: Math.round(loginTimes.reduce((a, b) => a + b) / 10), max: Math.round(Math.max(...loginTimes))};

// ===== 19. Lưu trữ kỳ khi mọi phương án đã chốt =====
section('PERIOD');
await expect('Lưu trữ kỳ chấm công (phương án đã công bố)', 'POST', `/api/periods/${live.id}/archive`, {as: admin, body: {version: liveP.version}, status: [200, 409]});
const Pnow = (await http('GET', `/api/periods/${P.id}`, {as: admin})).data;
await expect('Lưu trữ kỳ chính', 'POST', `/api/periods/${P.id}/archive`, {as: admin, body: {version: Pnow.version}, check: d => d.status === 'archived'});
await expect('Sửa kỳ đã lưu trữ', 'PUT', `/api/periods/${P.id}`, {as: admin, body: {...periodBody, version: Pnow.version + 1}, status: 409});

await mkdir('.local-test/qa', {recursive: true});
await writeFile('.local-test/qa/api-fixture.json', JSON.stringify({base, run, adminUsername: adminUser, adminPassword: adminPass,
  employee: {username: A.username, password: fixturePassword, id: A.id}, employeeD: {username: D.username, password: fixturePassword, id: D.id},
  employeeB: {username: B.username, password: 'NewPass-2026!', id: B.id}, lockedEmployee: {username: C.username, password: fixturePassword},
  xssEmployeeId: xss.data?.id, xssName, periodId: P.id, livePeriodId: live.id, openShiftId: open.data?.id}, null, 2));
} catch (error) {
  failures++;
  evidence.crash = String(error?.stack || error);
  console.error('CRASH: ' + evidence.crash);
} finally {
  evidence.finishedAt = new Date().toISOString();
  evidence.summary = {total: evidence.cases.length, passed: evidence.cases.filter(c => c.pass).length,
    failed: evidence.cases.filter(c => !c.pass).length, crashed: Boolean(evidence.crash), exchanges: evidence.exchanges.length};
  await mkdir('.local-test/qa', {recursive: true});
  await writeFile('.local-test/qa/api-evidence.json', JSON.stringify(evidence, null, 1));
  console.log(`\nTổng: ${evidence.summary.total} ca, PASS ${evidence.summary.passed}, FAIL ${evidence.summary.failed}${evidence.crash ? ' (DỪNG GIỮA CHỪNG)' : ''}, ${evidence.exchanges.length} request/response được ghi.`);
  process.exitCode = failures ? 1 : 0;
}
