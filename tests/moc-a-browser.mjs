import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
let playwright;try{playwright=require('playwright');}catch{playwright=require('../.local-test/node_modules/playwright');}
const {chromium}=playwright;
const fixture=JSON.parse(await readFile('.local-test/browser-fixture.json','utf8'));
if(!/^http:\/\/(localhost|127\.0\.0\.1):8082$/.test(fixture.base))throw new Error('Only isolated test server allowed');
await mkdir('.local-test/screenshots',{recursive:true});
const browser=await chromium.launch({headless:true});
const failures=[],proof=[];
const context=await browser.newContext({viewport:{width:1280,height:900}});
context.setDefaultTimeout(15000);
const page=await context.newPage();
page.on('pageerror',e=>failures.push(e.message));
async function login(page,username,password){page.setDefaultTimeout(15000);console.log('CHECK login page');await page.goto(fixture.base,{timeout:15000});await page.locator('#login-form input[name=username]').fill(username);await page.locator('#login-form input[name=password]').fill(password);await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();await page.locator('#workspace').waitFor({state:'visible'});await page.locator('#content[aria-busy=false]').waitFor();console.log('CHECK logged in');}
async function route(page,id){await page.locator(`[data-route="${id}"]`).click();await page.locator('#content[aria-busy=false]').waitFor();const error=await page.locator('#notice').evaluate(n=>n.classList.contains('error')?n.textContent:'');assert.ok(!error,`${id}: ${error}`);}
async function noOverflow(page,label){assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth+1),`Page overflow: ${label}`);}
try {
  await login(page,fixture.adminUsername,fixture.adminPassword);
  const routes=['periods','availability','rules','shifts','plans','calendar','requests','leave','openShifts','notifications','reports','directory'];
  for(const width of [320,360,390,768,1024,1280]){
    await page.setViewportSize({width,height:900});
    for(const id of routes){await route(page,id);await noOverflow(page,`admin ${id} ${width}px`);}
    await route(page,'periods');await page.getByRole('button',{name:'Tạo kỳ',exact:true}).click();await page.locator('#editor[open]').waitFor();await noOverflow(page,`modal ${width}px`);assert.ok(await page.locator('#editor').evaluate(d=>d.getBoundingClientRect().right<=innerWidth&&d.getBoundingClientRect().left>=0));await page.keyboard.press('Escape');
    proof.push(`Admin: ${routes.length} màn hình + modal tại ${width}px`);console.log('PASS '+proof.at(-1));
  }
  await route(page,'rules');await page.getByRole('button',{name:'Thay đổi quy tắc',exact:true}).click();await page.locator('#editor[open]').waitFor();await page.locator('#editor-form [name=scopeType]').selectOption('company');await page.locator('#editor-form [name=reason]').fill('Kiểm thử preview và xác nhận trên giao diện');await page.locator('#editor-form [name=target_hours]').fill('61');await page.locator('#editor-save').click();await page.locator('#editor').waitFor({state:'hidden'});await page.getByRole('button',{name:'Xác nhận áp dụng',exact:true}).waitFor();page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'Xác nhận áp dụng',exact:true}).click();await page.locator('#content[aria-busy=false]').waitFor();proof.push('UI quy tắc: xem trước tác động rồi xác nhận áp dụng');
  await route(page,'attendance');const frame=page.frameLocator('iframe');await frame.locator('#app-view').waitFor({state:'visible'});assert.equal(await frame.locator('#login-view').isVisible(),false);proof.push('Chấm công dùng chung JWT, không đăng nhập lần hai');
  for(const width of [320,360,390,768,1024,1280]){await page.setViewportSize({width,height:900});await noOverflow(page,`attendance parent ${width}px`);assert.ok(await frame.locator('html').evaluate(n=>n.scrollWidth<=n.clientWidth+1),`Attendance panel overflow ${width}px`);}proof.push('Chấm công sau đăng nhập: đủ 6 viewport');
  await route(page,'periods');await page.getByRole('button',{name:'Tạo kỳ',exact:true}).click();
  const form=page.locator('#editor-form'),name='Kiểm thử UI '+Date.now();
  await form.locator('[name=name]').fill(name);await form.locator('[name=cycle]').selectOption('weekly');await form.locator('[name=startDate]').fill(fixture.period.startDate);await form.locator('[name=endDate]').fill(fixture.period.endDate);await form.locator('[name=openAt]').fill(fixture.period.openAt.slice(0,16));await form.locator('[name=deadline]').fill(fixture.period.deadline.slice(0,16));await form.locator('[name=requiredDaysOfWeek]').selectOption(['0','1','2','3','4','5','6']);await form.locator('[name=applicableUserIds]').selectOption(fixture.people[0].id);
  await page.locator('#editor-save').click();await page.locator('#editor').waitFor({state:'hidden'});await page.locator('#content[aria-busy=false]').waitFor();const row=page.locator('tr').filter({hasText:name});await row.getByRole('button',{name:'Mở đăng ký',exact:true}).click();await page.locator('#content[aria-busy=false]').waitFor();await page.reload();await page.locator('#content[aria-busy=false]').waitFor();assert.ok(await page.getByText(name,{exact:true}).isVisible());proof.push('Tạo/mở kỳ qua UI và tải lại vẫn đọc dữ liệu từ backend');
  const employeeContext=await browser.newContext({viewport:{width:390,height:844}}),employee=await employeeContext.newPage();employee.on('pageerror',e=>failures.push(e.message));await login(employee,fixture.people[0].username,fixture.people[0].password);
  assert.equal(await employee.locator('[data-route=plans]').count(),0);assert.equal(await employee.locator('[data-route=directory]').count(),0);
  await route(employee,'availability');const option=employee.locator('select[name=period] option').filter({hasText:name});await employee.locator('select[name=period]').selectOption(await option.getAttribute('value'));await employee.locator('#content[aria-busy=false]').waitFor();await employee.locator('.availability-row input[type=date]').first().fill(fixture.period.startDate);await employee.getByRole('button',{name:'Gửi đăng ký',exact:true}).click();await employee.locator('#content[aria-busy=false]').waitFor();assert.ok(!(await employee.locator('#notice').textContent()).includes('không hợp lệ'));await employee.reload();await employee.locator('#content[aria-busy=false]').waitFor();await route(employee,'availability');await employee.locator('select[name=period]').selectOption(await employee.locator('select[name=period] option').filter({hasText:name}).getAttribute('value'));await employee.locator('#content[aria-busy=false]').waitFor();assert.ok(await employee.locator('.availability-row').count()>0);proof.push('Nhân viên gửi availability qua UI; đăng nhập lại/tải lại đọc được dữ liệu SQL');
  for(const width of [320,360,390,768,1024,1280]){await employee.setViewportSize({width,height:900});for(const id of ['calendar','requests','leave','openShifts','notifications','reports']){await route(employee,id);await noOverflow(employee,`employee ${id} ${width}px`);}proof.push(`Employee: 6 màn hình tại ${width}px`);}
  await page.screenshot({path:'.local-test/screenshots/admin-1280.png',fullPage:true});await employee.setViewportSize({width:390,height:844});await route(employee,'calendar');await employee.screenshot({path:'.local-test/screenshots/employee-390.png',fullPage:true});
  const scaledContext=await browser.newContext({viewport:{width:1024,height:768},deviceScaleFactor:1.25});const scaled=await scaledContext.newPage();await login(scaled,fixture.adminUsername,fixture.adminPassword);await scaled.evaluate(()=>document.documentElement.style.zoom='1.25');await noOverflow(scaled,'DPR 1.25 / zoom 125%');await scaled.getByRole('button',{name:'Tạo kỳ',exact:true}).click();await noOverflow(scaled,'scaled modal');await scaled.screenshot({path:'.local-test/screenshots/scale-125.png',fullPage:true});proof.push('DPR 1.25 và zoom 125%: navigation/form/modal không tràn');await scaledContext.close();
  assert.deepEqual(failures,[],'Browser runtime errors');
  await writeFile('.local-test/browser-evidence.json',JSON.stringify({at:new Date().toISOString(),proof,runtimeErrors:failures},null,2));console.log('PASS browser flows, responsive, session reuse and runtime checks');
} catch(error) {console.log('Runtime errors:',failures);await page.screenshot({path:'.local-test/screenshots/failure.png',fullPage:true});throw error;
} finally {await browser.close();}
