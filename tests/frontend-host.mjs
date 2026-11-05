import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {readFile,writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const fixture=JSON.parse(await readFile('.local-test/browser-fixture.json','utf8'));
assert.match(fixture.base,/^http:\/\/(localhost|127\.0\.0\.1):8082$/,'Only QA backend is allowed');
const require=createRequire(import.meta.url);let playwright;
try{playwright=require('playwright');}catch{playwright=require('../.local-test/node_modules/playwright');}
const port=8083,base=`http://127.0.0.1:${port}`;
const existing=process.argv.includes('--existing');
if(!existing){try{await fetch(base);throw Error('Frontend QA port 8083 is already in use');}catch(error){if(!error.cause)throw error;}}
const host=existing?null:spawn(process.execPath,[fileURLToPath(new URL('../HUONG_DAN/frontend-server.mjs',import.meta.url))],{env:{...process.env,FRONTEND_PORT:String(port),BACKEND_URL:fixture.base},stdio:'pipe',windowsHide:true});
let browser;const proof=[];
try{
  const deadline=Date.now()+15000;
  while(true){try{const response=await fetch(base);if(response.ok)break;}catch{}
    if(Date.now()>deadline)throw Error('Frontend host did not start');await new Promise(resolve=>setTimeout(resolve,100));
  }
  for(const path of ['/.env','/pom.xml','/src/main/resources/application.properties','/.git/config','/HUONG_DAN/CHAY_HE_THONG.bat','/demo.html'])assert.equal((await fetch(base+path)).status,404,path);
  assert.equal((await fetch(base+'/workspace.html',{method:'HEAD'})).status,200);
  assert.equal((await fetch(base+'/workspace.html',{method:'POST'})).status,405);
  assert.equal((await fetch(base+'/api/me')).status,401);
  assert.equal((await fetch(base+'/api/health')).status,200);
  proof.push('Public asset allowlist; repository, secrets and demo files return 404; API health and auth statuses are preserved');
  browser=await playwright.chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:390,height:844}});const runtime=[];page.on('pageerror',error=>runtime.push(error.message));
  await page.goto(base);await page.locator('#login-form [name=username]').fill(fixture.adminUsername);await page.locator('#login-form [name=password]').fill(fixture.adminPassword);
  await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();
  try{await page.locator('#workspace').waitFor({state:'visible'});}catch(error){throw Error('Frontend login failed: '+await page.locator('#login-error').textContent(),{cause:error});}
  await page.locator('#content[aria-busy=false]').waitFor();
  await page.locator('[data-route=attendance]').click();await page.frameLocator('iframe').locator('#app-view').waitFor({state:'visible'});
  assert.equal(await page.frameLocator('iframe').locator('#login-view').isVisible(),false);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await page.screenshot({path:'.local-test/screenshots/frontend-proxy-390.png',fullPage:true});
  assert.deepEqual(runtime,[]);proof.push('Frontend 8083 -> backend 8082: real login, shared JWT attendance iframe and 390px viewport');
  await writeFile('.local-test/frontend-host-evidence.json',JSON.stringify({at:new Date().toISOString(),proof,runtimeErrors:runtime},null,2));
  proof.forEach(item=>console.log('PASS '+item));
}finally{if(browser)await browser.close();host?.kill();}
