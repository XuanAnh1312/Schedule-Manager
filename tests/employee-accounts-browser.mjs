import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
const base=new URL(process.env.SM_EMPLOYEE_TEST_URL||'http://127.0.0.1:8080');
if(base.protocol!=='http:'||!['localhost','127.0.0.1'].includes(base.hostname)||base.pathname!=='/'||base.username||base.password)throw Error('Only local employee test accounts may be checked');
const usernames=['tuong.vdt@hrm.com','tuan.ntt@hrm.com','binh.ncb@hrm.com','anh.npxa@hrm.com','bao.dlhb@hrm.com'];
const initial='Test@123',changed='Changed-test-2026!';
const readyDeadline=Date.now()+60000;
while(true){try{const response=await fetch(new URL('/api/health',base));if(response.ok)break;}catch{}
  if(Date.now()>readyDeadline)throw Error('Local frontend/backend did not become ready');await new Promise(resolve=>setTimeout(resolve,500));
}
const require=createRequire(import.meta.url);let playwright;try{playwright=require('playwright');}catch{playwright=require('../.local-test/node_modules/playwright');}
const browser=await playwright.chromium.launch({headless:true});
const runtimeErrors=[],proof=[];let changeToken=null,passwordChanged=false;
async function login(page,username,password){
  await page.goto(base.href);await page.locator('#login-form [name=username]').fill(username);await page.locator('#login-form [name=password]').fill(password);
  await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();await page.locator('#workspace').waitFor({state:'visible'});await page.locator('#content[aria-busy=false]').waitFor();
}
async function auth(username,password){const response=await fetch(new URL('/api/auth/login',base),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username,password})});return {status:response.status,body:await response.json()};}
async function restore(){
  if(!passwordChanged)return;
  const response=await fetch(new URL('/api/auth/change-password',base),{method:'POST',headers:{Authorization:'Bearer '+changeToken,'Content-Type':'application/json'},body:JSON.stringify({currentPassword:changed,newPassword:initial})});
  assert.equal(response.status,200,'Restore the requested initial test password');passwordChanged=false;
}
try{
  for(const username of usernames){
    const context=await browser.newContext({viewport:{width:390,height:844}}),page=await context.newPage();page.on('pageerror',error=>runtimeErrors.push(error.message));
    await login(page,username,initial);
    assert.equal(await page.locator('[data-route=directory]').count(),0);assert.equal(await page.locator('[data-route=plans]').count(),0);assert.equal(await page.locator('[data-route=events]').count(),0);
    const token=await page.evaluate(()=>sessionStorage.getItem('attendance_jwt'));
    const forbidden=await page.request.post(new URL('/api/workspace/employees',base).href,{headers:{Authorization:'Bearer '+token},data:{}});assert.equal(forbidden.status(),403);
    const eventsForbidden=await page.request.get(new URL('/api/admin/events',base).href,{headers:{Authorization:'Bearer '+token}});assert.equal(eventsForbidden.status(),403);
    const signup=await page.request.post(new URL('/api/auth/register',base).href,{headers:{Authorization:'Bearer '+token},data:{}});assert.equal(signup.status(),403);
    if(username===usernames[0]){
      changeToken=token;await page.locator('#change-password').click();
      await page.locator('#editor-form [name=currentPassword]').fill(initial);await page.locator('#editor-form [name=newPassword]').fill(changed);await page.locator('#editor-form [name=confirmPassword]').fill('Does-not-match');await page.locator('#editor-save').click();
      await page.locator('#editor-error').filter({hasText:'Mật khẩu nhập lại chưa khớp.'}).waitFor();assert.ok(await page.locator('#editor').isVisible());
      await page.locator('#editor-form [name=confirmPassword]').fill(changed);await page.locator('#editor-save').click();await page.locator('#editor').waitFor({state:'hidden'});passwordChanged=true;
      assert.equal((await auth(username,initial)).status,401);assert.equal((await auth(username,changed)).status,200);
      await restore();assert.equal((await auth(username,initial)).status,200);
      proof.push('Employee changes own password through UI; mismatched confirmation is rejected; initial test password restored');
    }
    proof.push('Employee login and permission checks: '+username);await context.close();
  }
  const admin=await browser.newPage({viewport:{width:1280,height:900}});admin.on('pageerror',error=>runtimeErrors.push(error.message));
  await login(admin,process.env.SM_SEED_ADMIN||'admin',process.env.SM_SEED_ADMIN_PASSWORD||'Admin@123');
  await admin.locator('[data-route=directory]').click();await admin.locator('#content[aria-busy=false]').waitFor();await admin.getByRole('button',{name:'Thêm nhân viên',exact:true}).click();await admin.locator('#editor[open]').waitFor();
  assert.ok(await admin.locator('#editor-form [name=username]').isVisible());assert.ok(await admin.locator('#editor-form [name=password]').isVisible());
  await mkdir('.local-test/screenshots',{recursive:true});await admin.screenshot({path:'.local-test/screenshots/admin-issue-employee-account.png',fullPage:true});await admin.keyboard.press('Escape');
  proof.push('Admin create-employee form requests the issued login and initial password');
  assert.deepEqual(runtimeErrors,[]);
  await writeFile('.local-test/employee-accounts-browser-evidence.json',JSON.stringify({at:new Date().toISOString(),proof,runtimeErrors},null,2));
  proof.forEach(item=>console.log('PASS '+item));
}finally{try{await restore();}finally{await browser.close();}}
