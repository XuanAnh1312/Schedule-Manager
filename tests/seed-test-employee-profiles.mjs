// Synthetic profiles for the five explicitly requested local test accounts.
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
const base=new URL(process.env.SM_SEED_URL||'http://127.0.0.1:8080');
if(base.protocol!=='http:'||!['localhost','127.0.0.1'].includes(base.hostname)||base.username||base.password||base.pathname!=='/')throw Error('Only a local application origin is allowed');
const profiles=[
  {username:'anh.npxa@hrm.com',fullName:'Nguyễn Phạm Xuân Anh',phone:'0900000104',joinDate:'2023-05-15',level:'lvl4',branch:'Chi nhánh TP. Hồ Chí Minh',department:'Nhân sự và vận hành',group:'Nhóm quản lý vận hành',type:'Toàn thời gian',position:'Trưởng nhóm vận hành',skills:['Quản lý nhóm','Điều phối ca','Excel và báo cáo'],certificates:['An toàn lao động','Điều phối vận hành'],locations:['Chi nhánh TP. Hồ Chí Minh','Chi nhánh Đà Nẵng'],manager:null,targetRate:100,contractMaxHoursPerWeek:40},
  {username:'tuong.vdt@hrm.com',fullName:'Võ Đình Tường',phone:'0900000101',joinDate:'2024-01-08',level:'lvl3',branch:'Chi nhánh TP. Hồ Chí Minh',department:'Nhân sự và vận hành',group:'Nhóm vận hành A',type:'Toàn thời gian',position:'Điều phối viên ca làm',skills:['Điều phối ca','Vận hành cơ bản','Excel và báo cáo'],certificates:['An toàn lao động','Điều phối vận hành'],locations:['Chi nhánh TP. Hồ Chí Minh','Chi nhánh Đà Nẵng'],manager:'anh.npxa@hrm.com',targetRate:100,contractMaxHoursPerWeek:40},
  {username:'tuan.ntt@hrm.com',fullName:'Nguyễn Thanh Tuấn',phone:'0900000102',joinDate:'2024-08-12',level:'lvl2',branch:'Chi nhánh TP. Hồ Chí Minh',department:'Vận hành cửa hàng',group:'Nhóm vận hành A',type:'Toàn thời gian',position:'Nhân viên vận hành',skills:['Vận hành cơ bản','Chăm sóc khách hàng','Excel và báo cáo'],certificates:['An toàn lao động','Dịch vụ khách hàng'],locations:['Chi nhánh TP. Hồ Chí Minh'],manager:'tuong.vdt@hrm.com',targetRate:100,contractMaxHoursPerWeek:40},
  {username:'binh.ncb@hrm.com',fullName:'Nguyễn Công Bình',phone:'0900000103',joinDate:'2025-03-03',level:'lvl2',branch:'Chi nhánh Đà Nẵng',department:'Dịch vụ khách hàng',group:'Nhóm dịch vụ B',type:'Bán thời gian',position:'Nhân viên chăm sóc khách hàng',skills:['Chăm sóc khách hàng','Excel và báo cáo','Vận hành cơ bản'],certificates:['An toàn lao động','Dịch vụ khách hàng'],locations:['Chi nhánh Đà Nẵng'],manager:'anh.npxa@hrm.com',targetRate:60,contractMaxHoursPerWeek:24},
  {username:'bao.dlhb@hrm.com',fullName:'Đặng Lê Hoàng Bảo',phone:'0900000105',joinDate:'2025-09-15',level:'lvl1',branch:'Chi nhánh Đà Nẵng',department:'Dịch vụ khách hàng',group:'Nhóm dịch vụ B',type:'Bán thời gian',position:'Nhân viên hỗ trợ vận hành',skills:['Chăm sóc khách hàng','Vận hành cơ bản'],certificates:['An toàn lao động','Dịch vụ khách hàng'],locations:['Chi nhánh Đà Nẵng'],manager:'anh.npxa@hrm.com',targetRate:50,contractMaxHoursPerWeek:20}
];
async function call(path,token,method='GET',body,expected=200){
  const response=await fetch(new URL(path,base),{method,headers:{Authorization:'Bearer '+token,...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
  const data=await response.json().catch(()=>({}));
  if(response.status!==expected)throw Error(`${method} ${path}: HTTP ${response.status} ${data.error||data.message||''}`);
  return data;
}
const login=await call('/api/auth/login','','POST',{username:process.env.SM_SEED_ADMIN||'admin',password:process.env.SM_SEED_ADMIN_PASSWORD||'Admin@123'});
const token=login.token,admin=await call('/api/me',token);assert.equal(admin.role,'admin');
const people=await call('/api/workspace/employees',token);
const byUsername=new Map(people.map(person=>[person.username,person]));
for(const profile of profiles)assert.ok(byUsername.has(profile.username),'Missing requested test account '+profile.username);
const levels=await call('/api/levels',token);
for(const profile of profiles)assert.ok(levels.some(level=>level.id===profile.level),'Missing fixture Level '+profile.level);
const catalogs={};
for(const type of ['locations','departments','work_groups','employee_types','positions','skills','certificates'])catalogs[type]=await call('/api/schedule/catalog/'+type,token);
async function catalogId(type,name){
  let item=catalogs[type].find(item=>item.name===name);
  if(!item){item=await call('/api/schedule/catalog/'+type,token,'POST',{name},201);catalogs[type].push(item);}
  return item.id;
}
const evidence=[];
for(const profile of profiles){
  const original=await call('/api/workspace/employees/'+byUsername.get(profile.username).id,token);
  const managerId=profile.manager?byUsername.get(profile.manager).id:admin.id;
  const expected={employeeCode:original.employeeCode,username:profile.username,fullName:profile.fullName,phone:profile.phone,email:profile.username,joinDate:profile.joinDate,
    levelId:profile.level,status:'active',branchId:await catalogId('locations',profile.branch),departmentId:await catalogId('departments',profile.department),
    groupId:await catalogId('work_groups',profile.group),employeeTypeId:await catalogId('employee_types',profile.type),managerId,
    note:`Dữ liệu giả lập để kiểm thử, không phải hồ sơ cá nhân thật. ${profile.position}; ${profile.type.toLowerCase()}; tỷ lệ mục tiêu ${profile.targetRate}%; giới hạn hợp đồng mẫu ${profile.contractMaxHoursPerWeek} giờ/tuần.`,version:original.version};
  await call('/api/workspace/employees/'+original.id,token,'PUT',expected);
  const links={locationIds:await Promise.all(profile.locations.map(name=>catalogId('locations',name))),positionIds:[await catalogId('positions',profile.position)],
    skillIds:await Promise.all(profile.skills.map(name=>catalogId('skills',name))),certificates:await Promise.all(profile.certificates.map(async name=>({certificateId:await catalogId('certificates',name),validUntil:name==='An toàn lao động'?'2027-12-31':'2028-06-30'})))};
  await call('/api/schedule/catalog/users/'+original.id,token,'PUT',links);
  const actual=await call('/api/workspace/employees/'+original.id,token),actualLinks=await call('/api/schedule/catalog/users/'+original.id,token);
  for(const [key,value]of Object.entries(expected))if(key!=='version')assert.equal(actual[key],value,profile.username+': '+key);
  for(const key of ['locationIds','positionIds','skillIds'])assert.deepEqual([...actualLinks[key]].sort(),[...links[key]].sort());
  assert.deepEqual([...actualLinks.certificates].sort((a,b)=>a.certificateId.localeCompare(b.certificateId)),[...links.certificates].sort((a,b)=>a.certificateId.localeCompare(b.certificateId)));
  const employeeLogin=await call('/api/auth/login','','POST',{username:profile.username,password:'Test@123'});
  const own=await call('/api/me',employeeLogin.token);assert.equal(own.role,'user');assert.equal(own.fullName,profile.fullName);
  evidence.push({...profile,employeeId:original.id,employeeCode:original.employeeCode,manager:profile.manager||admin.username,verification:'PASS'});
  console.log('PASS full synthetic profile, capabilities and preserved login: '+profile.username);
}
await mkdir('.local-test',{recursive:true});
await writeFile('.local-test/employee-profile-seed-evidence.json',JSON.stringify({at:new Date().toISOString(),synthetic:true,profiles:evidence},null,2));
console.log('PASS 5 complete profiles via Admin API; login names, passwords and Employee roles preserved');
