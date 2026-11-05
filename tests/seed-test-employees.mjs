// Explicit local fixture: Admin issues accounts through the real employee API.
import assert from 'node:assert/strict';
const base=new URL(process.env.SM_SEED_URL||'http://127.0.0.1:8080');
if(base.protocol!=='http:'||!['localhost','127.0.0.1'].includes(base.hostname)||base.username||base.password||base.pathname!=='/')throw Error('Only a local HTTP application origin is allowed');
const accounts=[
  ['NV_TUONG','tuong.vdt@hrm.com','Tường'],
  ['NV_TUAN','tuan.ntt@hrm.com','Tuấn'],
  ['NV_BINH','binh.ncb@hrm.com','Bình'],
  ['NV_ANH','anh.npxa@hrm.com','Anh'],
  ['NV_BAO','bao.dlhb@hrm.com','Bảo']
];
const password='Test@123';
async function call(path,token,method='GET',body,expected=200){
  const response=await fetch(new URL(path,base),{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
  const payload=await response.json().catch(()=>({}));
  if(response.status!==expected)throw Error(`${method} ${path}: HTTP ${response.status} ${payload.error||payload.message||''}`);
  return payload;
}
const admin=await call('/api/auth/login','','POST',{username:process.env.SM_SEED_ADMIN||'admin',password:process.env.SM_SEED_ADMIN_PASSWORD||'Admin@123'});
const me=await call('/api/me',admin.token);assert.equal(me.role,'admin','Seed must be run by Admin');
const existing=await call('/api/workspace/employees',admin.token);
for(const [employeeCode,username,fullName]of accounts){
  const found=existing.find(person=>person.username.toLowerCase()===username);
  if(found){
    // Never silently reset an existing employee's chosen password.
    await call('/api/auth/login','','POST',{username,password});
    console.log('PASS existing test account '+username);
  }else{
    await call('/api/workspace/employees',admin.token,'POST',{employeeCode,username,password,fullName,email:username,status:'active'});
    console.log('CREATED '+username);
  }
  const login=await call('/api/auth/login','','POST',{username,password});
  const profile=await call('/api/me',login.token);assert.equal(profile.username,username);assert.equal(profile.role,'user');
}
console.log('PASS 5 Admin-issued employee accounts can log in; initial password is Test@123');
