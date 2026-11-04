/* Run only against the isolated test server on localhost:8082. No production data. */
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
const base=process.env.SM_TEST_URL||'http://localhost:8082';
if(!/^http:\/\/(localhost|127\.0\.0\.1):8082$/.test(base))throw new Error('Only the isolated localhost:8082 test server is allowed');
const pass=process.env.SM_TEST_PASSWORD||'Admin@123';
const fixturePassword='MocA-test-2026!';
const prefix='e2e_'+Date.now();
const proof=[];
async function call(path,token='',method='GET',body,expected=200){
  const response=await fetch(base+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})});
  const raw=await response.text();let data;try{data=raw?JSON.parse(raw):null;}catch{data=raw;}
  assert.equal(response.status,expected,`${method} ${path}: ${raw}`);return data;
}
function ok(message){proof.push(message);console.log('PASS '+message);}
const localDate=offset=>{const now=new Date();now.setUTCDate(now.getUTCDate()+offset);return new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Ho_Chi_Minh',year:'numeric',month:'2-digit',day:'2-digit'}).format(now);};
const login=async(username,password)=> (await call('/api/auth/login','','POST',{username,password})).token;
const readyDeadline=Date.now()+60000;
while(true){try{const response=await fetch(base+'/api/health');if(response.ok)break;}catch{}
  if(Date.now()>readyDeadline)throw Error('QA backend did not become ready within 60 seconds');
  await new Promise(resolve=>setTimeout(resolve,500));
}
const admin=await login(process.env.SM_TEST_ADMIN||'admin',pass);
const levels=await call('/api/levels',admin);
const people=[];
for(const suffix of ['a','b']){
  const username=prefix+suffix;
  const p=await call('/api/workspace/employees',admin,'POST',{employeeCode:username,username,password:fixturePassword,fullName:'Nhân viên kiểm thử '+suffix.toUpperCase(),levelId:levels[0].id,status:'active'},200);
  people.push({...p,username,token:await login(username,fixturePassword)});
}
ok('Tạo nhân viên và đăng nhập JWT thật');
const period=await call('/api/periods',admin,'POST',{name:'E2E '+prefix,cycle:'weekly',startDate:localDate(1),endDate:localDate(7),openAt:new Date(Date.now()-3600000+7*3600000).toISOString().slice(0,19),deadline:new Date(Date.now()+3600000+7*3600000).toISOString().slice(0,19),autoLock:true,allowReopenByAdmin:true,requiredDaysOfWeek:[0,1,2,3,4,5,6],exemptDates:[],closedDates:[],applicableUserIds:people.map(p=>p.id)},201);
let current=await call('/api/periods/'+period.id+'/open',admin,'POST',{version:period.version});
for(const p of people)await call('/api/availability/my/submit?periodId='+period.id,p.token,'POST',{entries:Array.from({length:7},(_,i)=>({date:localDate(i+1),slots:[{start:'00:00',end:'23:59',type:'available'}]}))});
ok('Mở kỳ và lưu đăng ký vào SQL Server qua API');
const values={target_hours:'60',max_hours_mode:'percent',max_hours_percent:'125',max_hours_per_day:'12',max_hours_per_week:'80',min_rest_hours:'8',max_consecutive_days:'7',warning_threshold_percent:'90',weight_balance_hours:'100',weight_balance_shifts:'50'};
const preview=await call('/api/rules/changes',admin,'POST',{reason:'Dữ liệu E2E trong database test riêng',items:Object.entries(values).map(([paramKey,value])=>({paramKey,value,scopeType:'company',effectiveFrom:localDate(0)}))},201);
await call('/api/rules/changes/'+preview.changeId+'/apply',admin,'POST',{confirm:true});
const effective=await call('/api/rules/effective?userId='+people[0].id,admin);assert.equal(effective.derived.maxHours,75);ok('Preview/xác nhận rules và tự tính 60 × 125% = 75');
const livePeriod=await call('/api/periods',admin,'POST',{name:'Chấm công '+prefix,cycle:'weekly',startDate:localDate(-1),endDate:localDate(1),autoLock:false,allowReopenByAdmin:true,requiredDaysOfWeek:[0,1,2,3,4,5,6],applicableUserIds:[people[1].id]},201);
const liveOpened=await call('/api/periods/'+livePeriod.id+'/open',admin,'POST',{version:livePeriod.version});
await call('/api/availability/my/submit?periodId='+livePeriod.id,people[1].token,'POST',{entries:[-1,0,1].map(i=>({date:localDate(i),slots:[{start:'00:00',end:'23:59',type:'available'}]}))});
await call('/api/periods/'+livePeriod.id+'/lock',admin,'POST',{version:liveOpened.version});
const liveFrom=new Date(Date.now()-3600000+7*3600000).toISOString(),liveTo=new Date(Date.now()+3600000+7*3600000).toISOString();
const liveShift=await call('/api/schedule/shifts',admin,'POST',{periodId:livePeriod.id,name:'Ca đang diễn ra '+prefix,date:liveFrom.slice(0,10),startTime:liveFrom.slice(11,16),endTime:liveTo.slice(11,16),mode:'combined',totalRequired:1,mandatoryUserIds:[people[1].id]},201);
let livePlan=await call('/api/schedule/plans',admin,'POST',{periodId:livePeriod.id,name:'Lịch chấm công '+prefix,shiftIds:[liveShift.id],replaceExisting:false},201);
livePlan=(await call('/api/schedule/plans/'+livePlan.id+'/submit',admin,'POST',{expectedVersion:livePlan.version})).plan;
livePlan=(await call('/api/schedule/plans/'+livePlan.id+'/approve',admin,'POST',{expectedVersion:livePlan.version})).plan;
await call('/api/schedule/plans/'+livePlan.id+'/publish',admin,'POST',{expectedVersion:livePlan.version,confirmUnderstaffed:false});
const livePunch=await call('/api/attendance/check-in',people[1].token,'POST',{shiftId:liveShift.id});
await call('/api/attendance/check-in',people[1].token,'POST',{shiftId:liveShift.id},409);
ok('Chấm công ca đang diễn ra qua JWT thật; chặn check-in trùng');
const shifts=[];
for(let i=0;i<2;i++)shifts.push(await call('/api/schedule/shifts',admin,'POST',{periodId:period.id,name:'Ca E2E '+(i+1),date:localDate(i+2),startTime:'08:00',endTime:'12:00',breakMinutes:30,mode:'combined',totalRequired:1,minRequired:1,maxAllowed:1,mandatoryUserIds:[people[i].id]},201));
const dry=await call('/api/schedule/plans/dry-run',admin,'POST',{periodId:period.id,shiftIds:shifts.map(s=>s.id),replaceExisting:false});assert.equal(dry.result.assignments.length,2);
current=await call('/api/periods/'+period.id+'/lock',admin,'POST',{version:current.version});
await call('/api/availability/my/submit?periodId='+period.id,people[0].token,'POST',{entries:[]},409);
ok('Dry-run không công bố lịch; backend chặn đăng ký sau khóa');
const plan=await call('/api/schedule/plans',admin,'POST',{periodId:period.id,name:'Phương án E2E',shiftIds:shifts.map(s=>s.id),replaceExisting:false},201);
await call('/api/periods/'+period.id+'/archive',admin,'POST',{version:current.version},409);
const submitted=await call('/api/schedule/plans/'+plan.id+'/submit',admin,'POST',{expectedVersion:plan.version});
const approved=await call('/api/schedule/plans/'+plan.id+'/approve',admin,'POST',{expectedVersion:submitted.plan.version});
await call('/api/schedule/plans/'+plan.id+'/publish',admin,'POST',{expectedVersion:approved.plan.version,confirmUnderstaffed:false});
ok('Nháp → duyệt → công bố; không archive kỳ khi còn nháp');
const mine=p=>call('/api/my/schedule?from='+period.startDate+'&to='+period.endDate,p.token);
let schedules=await Promise.all(people.map(mine));
assert.equal(schedules[0].shifts.length,1);assert.equal(schedules[1].shifts.length,1);
await call('/api/my/schedule/confirm',people[0].token,'POST',{planId:plan.id},204);
const req=await call('/api/requests',people[0].token,'POST',{type:'swap',sourceAssignmentId:schedules[0].shifts[0].assignmentId,targetUserId:people[1].id,targetAssignmentId:schedules[1].shifts[0].assignmentId,reason:'Đổi lịch kiểm thử'},201);
const accepted=await call('/api/requests/'+req.id+'/accept',people[1].token,'POST',{expectedVersion:req.version});
const swapped=await call('/api/requests/'+req.id+'/approve',admin,'POST',{expectedVersion:accepted.version});
await call('/api/requests/'+req.id+'/approve',admin,'POST',{expectedVersion:swapped.version});
schedules=await Promise.all(people.map(mine));assert.equal(schedules[0].shifts[0].shiftId,shifts[1].id);assert.equal(schedules[1].shifts[0].shiftId,shifts[0].id);
ok('Đổi chéo ca chính thức và duyệt lặp không đổi lần hai');
const types=await call('/api/leave/types',people[0].token);
const type=types.find(t=>!t.requiresDocument&&t.minNoticeDays===0);
const leave=await call('/api/leave',people[0].token,'POST',{leaveTypeId:type.id,startDate:localDate(3),endDate:localDate(3),reason:'Nghỉ kiểm thử'},201);
await call('/api/leave/'+leave.id+'/approve',admin,'POST',{expectedVersion:leave.version});
assert.equal((await mine(people[0])).shifts.length,0);ok('Nghỉ được duyệt gỡ phân công chính thức');
const extra=await call('/api/schedule/shifts',admin,'POST',{periodId:period.id,name:'Tranh chỗ E2E',date:localDate(5),startTime:'08:00',endTime:'12:00',mode:'combined',totalRequired:1,minRequired:1,maxAllowed:1},201);
// Publish the empty shift through a plan, explicitly confirming the shortage.
const racePlan=await call('/api/schedule/plans',admin,'POST',{periodId:period.id,name:'Ca trống E2E',shiftIds:[extra.id],replaceExisting:false},201);
for(const pair of racePlan.roster)await call('/api/schedule/plans/'+racePlan.id+'/assignments/remove',admin,'POST',{expectedVersion:(await call('/api/schedule/plans/'+racePlan.id,admin)).version,shiftId:pair.shiftId,userId:pair.userId});
let raceView=await call('/api/schedule/plans/'+racePlan.id,admin);
raceView=(await call('/api/schedule/plans/'+racePlan.id+'/submit',admin,'POST',{expectedVersion:raceView.version})).plan;
raceView=(await call('/api/schedule/plans/'+racePlan.id+'/approve',admin,'POST',{expectedVersion:raceView.version})).plan;
await call('/api/schedule/plans/'+racePlan.id+'/publish',admin,'POST',{expectedVersion:raceView.version,confirmUnderstaffed:true});
const open=await call('/api/open-shifts',admin,'POST',{shiftId:extra.id,slots:1,claimDeadline:localDate(4)+'T20:00',note:'Kiểm thử đồng thời'},201);
const claims=await Promise.all(people.map(p=>call('/api/open-shifts/'+open.id+'/claims',p.token,'POST',{},201)));
const results=await Promise.all(claims.map(c=>fetch(base+'/api/open-shifts/'+open.id+'/claims/'+c.id+'/approve',{method:'POST',headers:{Authorization:'Bearer '+admin,'Content-Type':'application/json'},body:JSON.stringify({expectedVersion:c.version})})));
const bodies=await Promise.all(results.map(r=>r.text()));
assert.equal(results.filter(r=>r.ok).length,1,'Only one concurrent approval may win: '+bodies.join(' | '));
assert.ok(results.filter(r=>!r.ok).every(r=>[409,422].includes(r.status)),bodies.join(' | '));
assert.equal((await call('/api/open-shifts/'+open.id,admin)).openShift?.filledCount??(await call('/api/open-shifts/'+open.id,admin)).filledCount,1);
ok('Hai request duyệt đồng thời tranh chỗ cuối: chỉ một request thành công');
const canonical=await call('/api/schedule/reports/hours?periodId='+period.id,admin);
const report=await call('/api/reports/users?periodId='+period.id,admin);
assert.equal(report.reduce((sum,r)=>sum+r.scheduledHours,0),canonical.totals.hours);ok('Báo cáo lịch và báo cáo công cùng tổng giờ');
await call('/api/schedule/plans?periodId='+period.id,people[0].token,'GET',undefined,403);
await call('/api/reports/users?periodId='+period.id+'&userId='+people[1].id,people[0].token,'GET',undefined,403);ok('API chặn vượt quyền và đọc báo cáo người khác');
const notificationDeadline=Date.now()+10000;let notifications;
do{notifications=await call('/api/notifications',people[1].token);if(notifications.items.length)break;await new Promise(r=>setTimeout(r,500));}while(Date.now()<notificationDeadline);
assert.ok(notifications.items.length>0);ok('Công bố / đổi ca sinh thông báo thật qua outbox');
const first=people[0];
const employeeUpdate={employeeCode:first.username,username:first.username,fullName:'Nhân viên kiểm thử A',levelId:levels[0].id,status:'inactive'};
await call('/api/workspace/employees/'+first.id,admin,'PUT',employeeUpdate);
await call('/api/periods',first.token,'GET',undefined,401);
await call('/api/workspace/employees/'+first.id,admin,'PUT',{...employeeUpdate,status:'active'});
ok('Tài khoản bị khóa không tiếp tục dùng được token cũ');
for(let i=0;i<3;i++){
  const racePeriod=await call('/api/periods',admin,'POST',{name:`Archive race ${prefix} ${i}`,cycle:'weekly',startDate:period.startDate,endDate:period.endDate,openAt:period.openAt,deadline:period.deadline,autoLock:true,requiredDaysOfWeek:[0,1,2,3,4,5,6],applicableUserIds:people.map(p=>p.id)},201);
  const opened=await call('/api/periods/'+racePeriod.id+'/open',admin,'POST',{version:racePeriod.version});
  const locked=await call('/api/periods/'+racePeriod.id+'/lock',admin,'POST',{version:opened.version});
  const raceShift=await call('/api/schedule/shifts',admin,'POST',{periodId:racePeriod.id,name:'Race shift',date:localDate(2),startTime:'08:00',endTime:'12:00',mode:'combined',totalRequired:1},201);
  const results=await Promise.all([
    fetch(base+'/api/schedule/plans',{method:'POST',headers:{Authorization:'Bearer '+admin,'Content-Type':'application/json'},body:JSON.stringify({periodId:racePeriod.id,name:'Race plan',shiftIds:[raceShift.id],replaceExisting:false})}),
    fetch(base+'/api/periods/'+racePeriod.id+'/archive',{method:'POST',headers:{Authorization:'Bearer '+admin,'Content-Type':'application/json'},body:JSON.stringify({version:locked.version})})
  ]);
  const statuses=results.map(r=>r.status);
  assert.equal(statuses.filter(s=>s>=200&&s<300).length,1,`archive/create must serialize: ${statuses}`);
  assert.ok(statuses.includes(409),`losing mutation must conflict: ${statuses}`);
  const after=await call('/api/periods/'+racePeriod.id,admin);
  if(after.status==='archived')assert.equal((await call('/api/schedule/plans?periodId='+racePeriod.id,admin)).length,0);
}
ok('Archive và tạo phương án đồng thời: một thao tác thắng, không lưu trữ kỳ có phương án nháp (3 lượt)');
const profile=await call('/api/workspace/employees/'+first.id,admin);
const department=await call('/api/schedule/catalog/departments',admin,'POST',{name:'Bộ phận '+prefix},201);
const profileBody={...employeeUpdate,status:'active',phone:'0901234567',email:prefix+'@example.test',joinDate:localDate(0),departmentId:department.id,managerId:people[1].id,version:profile.version};
await call('/api/workspace/employees/'+first.id,admin,'PUT',profileBody);
await call('/api/workspace/employees/'+first.id,admin,'PUT',profileBody,409);
const freshToken=await login(first.username,fixturePassword);
assert.equal((await call('/api/my/profile?userId='+people[1].id,freshToken)).email,profileBody.email);
assert.equal((await call('/api/me',freshToken)).id,first.id);
const paged=await call('/api/workspace/employees/page?q='+first.username+'&page=0&size=1',admin);
assert.equal(paged.total,1);assert.equal(paged.items[0].departmentId,department.id);
await call('/api/workspace/employees/'+people[1].id,freshToken,'GET',undefined,403);
await call('/api/audit',freshToken,'GET',undefined,403);
await call('/api/schedule/reports/hours?periodId='+period.id+'&branchId=forged',freshToken,'GET',undefined,403);
ok('Hồ sơ liên hệ/tổ chức bền vững; phân trang, version và chống giả userId/branchId');
const changedPassword='MocA-updated-2026!';
await call('/api/auth/change-password',freshToken,'POST',{currentPassword:fixturePassword,newPassword:changedPassword,userId:people[1].id});
await call('/api/auth/login','','POST',{username:first.username,password:fixturePassword},401);
await login(people[1].username,fixturePassword);
const changedToken=await login(first.username,changedPassword);
await call('/api/auth/change-password',changedToken,'POST',{currentPassword:changedPassword,newPassword:fixturePassword});
ok('Đổi mật khẩu thật, kiểm tra mật khẩu cũ và không đổi tài khoản do client chỉ định');
const newLevel=await call('/api/workspace/levels',admin,'POST',{name:'Level '+prefix,color:'#245f48',order:Math.max(...levels.map(l=>l.order))+1,description:'Danh mục kiểm thử'});
await call('/api/workspace/levels/'+newLevel.id,admin,'PUT',{name:'Level sửa '+prefix,color:'#2563eb',order:Math.max(...levels.map(l=>l.order))+1,description:'Đã cập nhật'});
await call('/api/workspace/levels/'+newLevel.id,admin,'DELETE',undefined,204);
const extraUser=await call('/api/workspace/employees',admin,'POST',{employeeCode:prefix+'c',username:prefix+'c',password:fixturePassword,fullName:'Hồ sơ lưu trữ',levelId:levels[0].id,status:'active'});
const extraToken=await login(prefix+'c',fixturePassword);
const extraProfile=await call('/api/workspace/employees/'+extraUser.id,admin);
await call('/api/workspace/employees/'+extraUser.id+'/archive',admin,'POST',{version:extraProfile.version,reason:'Nhân viên đã bàn giao công việc'});
await call('/api/me',extraToken,'GET',undefined,401);
const audits=await call('/api/audit?eventType=employee_update',admin);
assert.ok(audits.items.some(a=>a.detail===first.id&&a.beforeJson&&a.afterJson&&a.actorId));
assert.ok(!JSON.stringify(audits).includes('password_hash'));
ok('CRUD Level, lưu trữ nhân viên, vô hiệu hóa tài khoản và audit before/after không lộ mật khẩu');
const source=(await mine(people[1])).shifts.find(s=>s.shiftId===shifts[0].id);
assert.ok(source);
const competing=await call('/api/requests',people[1].token,'POST',{type:'cover',sourceAssignmentId:source.assignmentId,targetUserId:first.id,reason:'Kiểm thử duyệt cạnh tranh sửa lịch'},201);
const peer=await call('/api/requests/'+competing.id+'/accept',first.token,'POST',{expectedVersion:competing.version});
const competingResponses=await Promise.all([
  fetch(base+'/api/requests/'+competing.id+'/approve',{method:'POST',headers:{Authorization:'Bearer '+admin,'Content-Type':'application/json'},body:JSON.stringify({expectedVersion:peer.version})}),
  fetch(base+'/api/schedule/official/assignments/'+source.assignmentId+'/reassign',{method:'POST',headers:{Authorization:'Bearer '+admin,'Content-Type':'application/json'},body:JSON.stringify({expectedUserId:people[1].id,newUserId:first.id,reason:'Sửa lịch trực tiếp trong cuộc đua'})})
]);
const competingBodies=await Promise.all(competingResponses.map(r=>r.text()));
assert.equal(competingResponses.filter(r=>r.ok).length,1,competingBodies.join(' | '));
assert.ok(competingResponses.filter(r=>!r.ok).every(r=>[409,422].includes(r.status)),competingBodies.join(' | '));
assert.ok((await mine(first)).shifts.some(s=>s.shiftId===shifts[0].id));
assert.ok(!(await mine(people[1])).shifts.some(s=>s.shiftId===shifts[0].id));
ok('Duyệt yêu cầu cạnh tranh sửa lịch: chỉ một giao dịch thắng, không ghi đè lịch mới');
await call('/api/notifications/'+notifications.items[0].id+'/read',first.token,'POST',undefined,404);
ok('Không đánh dấu đọc được thông báo của người khác');
const waitForPunch=Date.parse(livePunch.clock_in_at)+61000-Date.now();
if(waitForPunch>0){console.log('CHECK Chờ đủ một phút chấm công thật trước check-out');await new Promise(r=>setTimeout(r,waitForPunch));}
const checkedOut=await call('/api/attendance/'+livePunch.id+'/check-out',people[1].token,'POST',{breakMinutes:0});
await call('/api/attendance/'+livePunch.id+'/approve',admin,'POST',{reason:'Duyệt giờ công kiểm thử'});
const adjustment=await call('/api/attendance/requests',people[1].token,'POST',{recordId:livePunch.id,clockIn:checkedOut.clock_in_at,clockOut:checkedOut.clock_out_at,breakMinutes:0,reason:'Đề nghị đối chiếu giờ công kiểm thử'});
await call('/api/attendance/requests/'+adjustment.id+'/review',admin,'POST',{approve:true,reason:'Đã đối chiếu và duyệt yêu cầu'});
await call('/api/attendance/periods/'+livePeriod.id+'/lock',admin,'POST',{reason:'Khóa kỳ công sau đối soát'});
assert.equal((await call('/api/attendance/records?periodId='+livePeriod.id,people[1].token))[0].status,'approved');
assert.equal((await call('/api/attendance/records?periodId='+period.id,people[1].token)).length,0);
ok('Check-out, duyệt công, sửa/duyệt điều chỉnh và khóa kỳ; dữ liệu không lẫn giữa các kỳ');
await writeFile('.local-test/browser-fixture.json',JSON.stringify({base,adminUsername:process.env.SM_TEST_ADMIN||'admin',adminPassword:pass,people:people.map(({id,username})=>({id,username,password:fixturePassword})),period,livePeriodId:livePeriod.id,planId:plan.id,proof},null,2));
await writeFile('.local-test/http-evidence.json',JSON.stringify({at:new Date().toISOString(),proof},null,2));
